package br.com.marianojunior.h2studio.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gera uma planilha .xlsx (Excel / LibreOffice) gravando linha a linha, sem carregar os
 * dados na memória e sem bibliotecas externas.
 * <p>
 * Uso: {@code header()}, várias {@code row()}, {@code close()}. Números ficam como número,
 * datas como data do Excel (dd/mm/aaaa), a primeira linha fica congelada e com filtro.
 */
public final class XlsxWriter implements AutoCloseable {

    /** Limite de linhas de uma planilha do Excel. */
    public static final int MAX_ROWS = 1_048_576;
    /** Limite de caracteres de uma célula do Excel. */
    public static final int MAX_CELL_CHARS = 32_767;

    // índices em cellXfs (styles.xml)
    private static final int ST_HEADER = 1;
    private static final int ST_DATE = 2;
    private static final int ST_DATETIME = 3;
    private static final int ST_TIME = 4;
    private static final int ST_MONEY = 5;

    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);

    private final ZipOutputStream zip;
    private final Writer w;
    private String sheetName;
    private int rows;
    private int columns;
    private boolean truncatedText;
    private boolean started;
    /** Abas já fechadas: nome, colunas, linhas (para o workbook.xml). */
    private final java.util.List<Object[]> done = new java.util.ArrayList<>();

    public XlsxWriter(Path file, String sheetName) throws IOException {
        OutputStream out = Files.newOutputStream(file);
        this.zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        this.w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 1 << 16);
        this.sheetName = safeSheetName(sheetName);
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
    }

    /**
     * Fecha a aba atual e começa outra (chame {@code header()} em seguida). O nome deve ser único
     * na planilha (até 31 caracteres).
     */
    public void newSheet(String name) throws IOException {
        finishSheet();
        sheetName = safeSheetName(name);
        rows = 0;
        columns = 0;
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + (done.size() + 1) + ".xml"));
    }

    private void finishSheet() throws IOException {
        if (!started) {
            header(java.util.List.of(), null, false); // aba sem cabeçalho: ainda precisa ser XML válido
        }
        started = false;
        w.write("</sheetData>");
        if (columns > 0 && rows > 0) {
            w.write("<autoFilter ref=\"A1:" + column(columns - 1) + rows + "\"/>");
        }
        w.write("</worksheet>");
        w.flush();
        zip.closeEntry();
        done.add(new Object[]{sheetName, columns, rows});
    }

    /** Escreve o cabeçalho e as larguras das colunas (em caracteres). */
    public void header(List<String> names, int[] widths, boolean show) throws IOException {
        started = true;
        columns = names.size();
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        w.write("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        w.write("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" "
                + "activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        if (columns > 0) {
            w.write("<cols>");
            for (int i = 0; i < columns; i++) {
                int width = widths != null && i < widths.length ? Math.max(6, Math.min(widths[i], 80)) : 14;
                w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + width + "\" customWidth=\"1\"/>");
            }
            w.write("</cols>");
        }
        w.write("<sheetData>");
        if (show) {
            writeRow(names.toArray(), true);
        }
    }

    /** Escreve uma linha. Valores aceitos: números, textos, datas, booleanos, null. */
    public void row(Object[] values) throws IOException {
        if (rows >= MAX_ROWS) {
            throw new IOException("O Excel aceita no máximo " + MAX_ROWS + " linhas por planilha. "
                    + "Use um filtro ou exporte em CSV.");
        }
        writeRow(values, false);
    }

    public int dataRows() {
        return Math.max(0, rows - 1);
    }

    /** Algum texto foi cortado no limite de 32.767 caracteres por célula. */
    public boolean truncatedText() {
        return truncatedText;
    }

    private void writeRow(Object[] values, boolean header) throws IOException {
        rows++;
        w.write("<row r=\"" + rows + "\">");
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            if (v == null) {
                continue;
            }
            String ref = column(i) + rows;
            if (header) {
                inlineString(ref, String.valueOf(v), ST_HEADER);
            } else if (v instanceof BigDecimal bd) {
                number(ref, bd.toPlainString(), bd.scale() == 2 ? ST_MONEY : 0);
            } else if (v instanceof Double d) {
                if (d.isNaN() || d.isInfinite()) {
                    inlineString(ref, d.toString(), 0);
                } else {
                    number(ref, BigDecimal.valueOf(d).toPlainString(), 0);
                }
            } else if (v instanceof Float f) {
                number(ref, new BigDecimal(f.toString()).toPlainString(), 0);
            } else if (v instanceof Number n) {
                // inteiros acima de 15 dígitos perdem precisão no Excel: vão como texto
                String s = n.toString();
                if (s.replace("-", "").length() > 15) {
                    inlineString(ref, s, 0);
                } else {
                    number(ref, s, 0);
                }
            } else if (v instanceof Boolean b) {
                w.write("<c r=\"" + ref + "\" t=\"b\"><v>" + (b ? 1 : 0) + "</v></c>");
            } else if (v instanceof LocalDate d) {
                number(ref, String.valueOf(ChronoUnit.DAYS.between(EPOCH, d)), ST_DATE);
            } else if (v instanceof LocalDateTime dt) {
                number(ref, serial(dt), ST_DATETIME);
            } else if (v instanceof LocalTime t) {
                number(ref, BigDecimal.valueOf(t.toNanoOfDay()).divide(BigDecimal.valueOf(86_400_000_000_000L),
                        10, java.math.RoundingMode.HALF_UP).toPlainString(), ST_TIME);
            } else {
                inlineString(ref, String.valueOf(v), 0);
            }
        }
        w.write("</row>");
    }

    private static String serial(LocalDateTime dt) {
        long days = ChronoUnit.DAYS.between(EPOCH, dt.toLocalDate());
        BigDecimal frac = BigDecimal.valueOf(dt.toLocalTime().toNanoOfDay())
                .divide(BigDecimal.valueOf(86_400_000_000_000L), 10, java.math.RoundingMode.HALF_UP);
        return BigDecimal.valueOf(days).add(frac).stripTrailingZeros().toPlainString();
    }

    private void number(String ref, String value, int style) throws IOException {
        w.write("<c r=\"" + ref + "\"" + (style > 0 ? " s=\"" + style + "\"" : "") + "><v>" + value + "</v></c>");
    }

    private void inlineString(String ref, String s, int style) throws IOException {
        if (s.length() > MAX_CELL_CHARS) {
            s = s.substring(0, MAX_CELL_CHARS);
            truncatedText = true;
        }
        w.write("<c r=\"" + ref + "\" t=\"inlineStr\"" + (style > 0 ? " s=\"" + style + "\"" : "")
                + "><is><t xml:space=\"preserve\">");
        w.write(xml(s));
        w.write("</t></is></c>");
    }

    /** A, B, … Z, AA, AB… */
    public static String column(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    /** Escapa XML e remove caracteres de controle que o Excel não aceita. */
    static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        if (Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < s.length()
                                && Character.isLowSurrogate(s.charAt(i + 1)))
                                && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1)))) {
                            continue; // metade de um caractere: inválido em XML
                        }
                        if (c != 0xFFFE && c != 0xFFFF) {
                            sb.append(c);
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    static String safeSheetName(String name) {
        String n = name == null || name.isBlank() ? "Dados" : name.replaceAll("[\\[\\]:*?/\\\\]", "_");
        return n.length() > 31 ? n.substring(0, 31) : n;
    }

    @Override
    public void close() throws IOException {
        finishSheet();
        StringBuilder overrides = new StringBuilder();
        StringBuilder sheets = new StringBuilder();
        StringBuilder filters = new StringBuilder();
        StringBuilder rels = new StringBuilder();
        for (int i = 0; i < done.size(); i++) {
            String name = (String) done.get(i)[0];
            int cols = (Integer) done.get(i)[1];
            int rws = (Integer) done.get(i)[2];
            overrides.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            sheets.append("<sheet name=\"").append(xml(name)).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
            if (cols > 0 && rws > 0) {
                filters.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                        .append("\" hidden=\"1\">'").append(xml(name.replace("'", "''"))).append("'!$A$1:$")
                        .append(column(cols - 1)).append("$").append(rws).append("</definedName>");
            }
            rels.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i + 1).append(".xml\"/>");
        }
        entry("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + overrides
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>");
        entry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        String filter = filters.length() > 0 ? "<definedNames>" + filters + "</definedNames>" : "";
        entry("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets>" + sheets + "</sheets>" + filter
                + "</workbook>");
        entry("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + rels
                + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
        entry("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"3\"><numFmt numFmtId=\"164\" formatCode=\"dd/mm/yyyy\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"dd/mm/yyyy hh:mm:ss\"/>"
                + "<numFmt numFmtId=\"166\" formatCode=\"hh:mm:ss\"/></numFmts>"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F6FEB\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"6\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"4\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>");
        zip.close();
    }

    private void entry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
