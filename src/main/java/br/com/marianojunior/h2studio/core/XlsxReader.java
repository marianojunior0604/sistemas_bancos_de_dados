package br.com.marianojunior.h2studio.core;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lê planilhas .xlsx (Excel 2007 em diante, LibreOffice, Google Planilhas) sem bibliotecas
 * externas: o arquivo é um zip com XML.
 * <p>
 * Os valores saem como texto no formato que o importador entende: datas "dd/MM/yyyy" (e
 * "dd/MM/yyyy HH:mm:ss"), números com vírgula decimal e sem separador de milhar ("1234,5"),
 * verdadeiro/falso como "true"/"false". Texto fica como está (inclusive "00123").
 * O formato antigo .xls (Excel 97–2003) não é suportado.
 */
public final class XlsxReader {

    /** Uma aba. {@code lines} é o número da linha no Excel de cada registro. */
    public record Sheet(String name, List<String[]> records, int[] lines) {
    }

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter BR_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private XlsxReader() {
    }

    /** Nomes das abas (na ordem do arquivo, sem as ocultas). */
    public static List<String> sheetNames(Path file) throws IOException {
        try (ZipFile z = open(file)) {
            return workbook(z).stream().map(s -> s[0]).toList();
        }
    }

    /** Lê uma aba inteira (linhas totalmente vazias são puladas). */
    public static Sheet read(Path file, String sheetName) throws IOException {
        try (ZipFile z = open(file)) {
            List<String[]> sheets = workbook(z);
            String[] s = sheets.stream().filter(x -> x[0].equals(sheetName)).findFirst()
                    .orElseThrow(() -> new IOException("A aba \"" + sheetName + "\" não existe."));
            boolean date1904 = "1".equals(s[2]) || "true".equals(s[2]);
            List<String> shared = sharedStrings(z);
            Map<Integer, Boolean> dateStyles = dateStyles(z);
            ZipEntry e = z.getEntry(s[1]);
            if (e == null) {
                throw new IOException("Aba \"" + sheetName + "\" não encontrada dentro do arquivo.");
            }
            try (InputStream in = z.getInputStream(e)) {
                return parseSheet(sheetName, in, shared, dateStyles, date1904);
            }
        }
    }

    private static ZipFile open(Path file) throws IOException {
        try {
            return new ZipFile(file.toFile());
        } catch (java.util.zip.ZipException e) {
            throw new IOException("O arquivo não é uma planilha .xlsx válida"
                    + (file.toString().toLowerCase(Locale.ROOT).endsWith(".xls")
                    ? " (o formato antigo .xls não é suportado: abra no Excel/LibreOffice e salve como .xlsx)." : "."), e);
        }
    }

    private static XMLStreamReader xml(InputStream in) throws IOException {
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            f.setProperty(XMLInputFactory.IS_COALESCING, true);
            return f.createXMLStreamReader(in, "UTF-8");
        } catch (XMLStreamException e) {
            throw new IOException("XML inválido na planilha: " + e.getMessage(), e);
        }
    }

    private static InputStream entry(ZipFile z, String name) throws IOException {
        ZipEntry e = z.getEntry(name);
        return e == null ? null : z.getInputStream(e);
    }

    /** Abas: {nome, caminho do XML, date1904}. */
    private static List<String[]> workbook(ZipFile z) throws IOException {
        Map<String, String> rels = new HashMap<>();
        try (InputStream in = entry(z, "xl/_rels/workbook.xml.rels")) {
            if (in != null) {
                XMLStreamReader r = xml(in);
                try {
                    while (r.hasNext()) {
                        if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                            String target = r.getAttributeValue(null, "Target");
                            if (target != null) {
                                target = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                                rels.put(r.getAttributeValue(null, "Id"), target);
                            }
                        }
                    }
                } catch (XMLStreamException e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        String date1904 = "0";
        try (InputStream in = entry(z, "xl/workbook.xml")) {
            if (in == null) {
                throw new IOException("O arquivo não é uma planilha .xlsx (falta xl/workbook.xml).");
            }
            XMLStreamReader r = xml(in);
            try {
                int n = 0;
                while (r.hasNext()) {
                    if (r.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    if (r.getLocalName().equals("workbookPr")) {
                        String d = r.getAttributeValue(null, "date1904");
                        date1904 = d == null ? "0" : d;
                    } else if (r.getLocalName().equals("sheet")) {
                        n++;
                        String name = r.getAttributeValue(null, "name");
                        String state = r.getAttributeValue(null, "state");
                        String rid = null;
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            if (r.getAttributeLocalName(i).equals("id")) {
                                rid = r.getAttributeValue(i);
                            }
                        }
                        String path = rid != null && rels.containsKey(rid) ? rels.get(rid) : "xl/worksheets/sheet" + n + ".xml";
                        if (state == null || state.equals("visible")) {
                            out.add(new String[]{name, path, null});
                        }
                    }
                }
            } catch (XMLStreamException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        for (String[] s : out) {
            s[2] = date1904;
        }
        return out;
    }

    private static List<String> sharedStrings(ZipFile z) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = entry(z, "xl/sharedStrings.xml")) {
            if (in == null) {
                return out;
            }
            XMLStreamReader r = xml(in);
            try {
                StringBuilder sb = null;
                int phonetic = 0;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "si" -> sb = new StringBuilder();
                            case "rPh" -> phonetic++;
                            case "t" -> {
                                if (sb != null && phonetic == 0) {
                                    sb.append(r.getElementText());
                                }
                            }
                            default -> { }
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        if (r.getLocalName().equals("rPh")) {
                            phonetic--;
                        } else if (r.getLocalName().equals("si") && sb != null) {
                            out.add(sb.toString());
                            sb = null;
                        }
                    }
                }
            } catch (XMLStreamException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return out;
    }

    /** Estilos de célula (índice em cellXfs) que formatam como data/hora. */
    private static Map<Integer, Boolean> dateStyles(ZipFile z) throws IOException {
        Map<Integer, Boolean> out = new HashMap<>();
        Map<Integer, String> custom = new HashMap<>();
        try (InputStream in = entry(z, "xl/styles.xml")) {
            if (in == null) {
                return out;
            }
            XMLStreamReader r = xml(in);
            try {
                boolean inXfs = false;
                int idx = 0;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        switch (r.getLocalName()) {
                            case "numFmt" -> {
                                try {
                                    custom.put(Integer.parseInt(r.getAttributeValue(null, "numFmtId")),
                                            r.getAttributeValue(null, "formatCode"));
                                } catch (NumberFormatException ignored) {
                                    // formato estranho: trata como número
                                }
                            }
                            case "cellXfs" -> inXfs = true;
                            case "xf" -> {
                                if (inXfs) {
                                    String id = r.getAttributeValue(null, "numFmtId");
                                    int fmt = id == null ? 0 : Integer.parseInt(id);
                                    out.put(idx++, isDateFormat(fmt, custom.get(fmt)));
                                }
                            }
                            default -> { }
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("cellXfs")) {
                        inXfs = false;
                    }
                }
            } catch (XMLStreamException | NumberFormatException e) {
                throw new IOException("Estilos inválidos na planilha: " + e.getMessage(), e);
            }
        }
        return out;
    }

    static boolean isDateFormat(int id, String code) {
        if ((id >= 14 && id <= 22) || (id >= 27 && id <= 36) || (id >= 45 && id <= 47) || (id >= 50 && id <= 58)) {
            return true;
        }
        if (code == null) {
            return false;
        }
        // tira textos entre aspas, [cor]/[$-416] e caracteres escapados antes de procurar d, m, y, h, s
        String c = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\\\.", "")
                .toLowerCase(Locale.ROOT);
        if (c.contains("general")) {
            return false;
        }
        return c.matches(".*[dyhs].*") || c.matches(".*m.*") && !c.matches(".*[0#?].*");
    }

    private static Sheet parseSheet(String name, InputStream in, List<String> shared, Map<Integer, Boolean> dateStyles,
                                    boolean date1904) throws IOException {
        List<String[]> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        XMLStreamReader r = xml(in);
        try {
            List<String> row = null;
            int rowNum = 0;
            int nextCol = 0;
            String ref = null;
            String t = null;
            int style = 0;
            String value = null;
            StringBuilder inline = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> {
                            String rn = r.getAttributeValue(null, "r");
                            rowNum = rn == null ? rowNum + 1 : Integer.parseInt(rn);
                            row = new ArrayList<>();
                            nextCol = 0;
                        }
                        case "c" -> {
                            ref = r.getAttributeValue(null, "r");
                            t = r.getAttributeValue(null, "t");
                            String s = r.getAttributeValue(null, "s");
                            style = s == null ? 0 : Integer.parseInt(s);
                            value = null;
                            inline = null;
                        }
                        case "v" -> value = r.getElementText();
                        case "is" -> inline = new StringBuilder();
                        case "t" -> {
                            if (inline != null) {
                                inline.append(r.getElementText());
                            }
                        }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("c") && row != null) {
                        int col = ref == null ? nextCol : column(ref);
                        while (row.size() < col) {
                            row.add("");
                        }
                        String text = cell(t, value, inline, style, shared, dateStyles, date1904);
                        if (row.size() == col) {
                            row.add(text);
                        } else {
                            row.set(col, text);
                        }
                        nextCol = col + 1;
                    } else if (r.getLocalName().equals("row") && row != null) {
                        while (!row.isEmpty() && row.get(row.size() - 1).isEmpty()) {
                            row.remove(row.size() - 1);
                        }
                        if (!row.isEmpty()) {
                            records.add(row.toArray(String[]::new));
                            lines.add(rowNum);
                        }
                        row = null;
                    }
                }
            }
        } catch (XMLStreamException | NumberFormatException e) {
            throw new IOException("Não foi possível ler a aba \"" + name + "\": " + e.getMessage(), e);
        }
        return new Sheet(name, records, lines.stream().mapToInt(Integer::intValue).toArray());
    }

    /** "C5" → 2 (A = 0). */
    static int column(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                n = n * 26 + (ch - 'A' + 1);
            } else if (ch >= 'a' && ch <= 'z') {
                n = n * 26 + (ch - 'a' + 1);
            } else {
                break;
            }
        }
        return n - 1;
    }

    private static String cell(String t, String v, StringBuilder inline, int style, List<String> shared,
                               Map<Integer, Boolean> dateStyles, boolean date1904) {
        if ("inlineStr".equals(t)) {
            return inline == null ? "" : inline.toString();
        }
        if (v == null) {
            return "";
        }
        if ("s".equals(t)) {
            int i = Integer.parseInt(v.trim());
            return i >= 0 && i < shared.size() ? shared.get(i) : "";
        }
        if ("str".equals(t)) {
            return v; // resultado de fórmula em texto
        }
        if ("b".equals(t)) {
            return "1".equals(v.trim()) ? "true" : "false";
        }
        if ("e".equals(t)) {
            return ""; // #N/D, #DIV/0!...
        }
        // número (ou data, conforme o estilo)
        BigDecimal bd;
        try {
            bd = new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return v;
        }
        if (Boolean.TRUE.equals(dateStyles.get(style))) {
            return excelDate(bd, date1904);
        }
        String s = bd.stripTrailingZeros().toPlainString();
        return s.replace('.', ',');
    }

    /** Número de série do Excel → texto de data/hora no formato brasileiro. */
    static String excelDate(BigDecimal serial, boolean date1904) {
        double d = serial.doubleValue();
        long days = (long) Math.floor(d);
        long secs = Math.round((d - days) * 86_400d);
        if (secs >= 86_400) {
            days++;
            secs -= 86_400;
        }
        if (days == 0 && !date1904) {
            return LocalTime.ofSecondOfDay(secs).format(TIME); // só hora
        }
        // 1900: o Excel considera 1900 bissexto (dia 60 = 29/02/1900, que não existe)
        LocalDate base = date1904 ? LocalDate.of(1904, 1, 1) : LocalDate.of(1899, 12, 30);
        if (!date1904 && days < 61) {
            base = LocalDate.of(1899, 12, 31);
        }
        LocalDate date = base.plusDays(days);
        if (secs == 0) {
            return date.format(BR_DATE);
        }
        return LocalDateTime.of(date, LocalTime.ofSecondOfDay(secs)).format(BR_DATE_TIME);
    }
}
