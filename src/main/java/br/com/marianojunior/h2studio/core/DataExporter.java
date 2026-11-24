package br.com.marianojunior.h2studio.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Exporta o resultado de uma consulta para CSV ou Excel (.xlsx), linha a linha. */
public final class DataExporter {

    public enum Format { XLSX, CSV }

    /** Formato do CSV. */
    public enum CsvStyle {
        /** Abre direto no Excel em português: ponto e vírgula, vírgula decimal, dd/mm/aaaa, UTF-8 com BOM. */
        EXCEL_BR("Excel em português (; e vírgula decimal)", ';', true, true, true),
        /** Padrão internacional: vírgula, ponto decimal, datas ISO, UTF-8 sem BOM. */
        STANDARD("Padrão internacional (, e ponto decimal)", ',', false, false, false),
        /** Separado por TAB. */
        TAB("Separado por TAB", '\t', false, false, false);

        public final String label;
        public final char separator;
        public final boolean decimalComma;
        public final boolean brDates;
        public final boolean bom;

        CsvStyle(String label, char separator, boolean decimalComma, boolean brDates, boolean bom) {
            this.label = label;
            this.separator = separator;
            this.decimalComma = decimalComma;
            this.brDates = brDates;
            this.bom = bom;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record Options(Format format, CsvStyle csv, boolean header, String sheetName) {
    }

    /** Acompanhamento: recebe a quantidade de linhas e diz se o usuário cancelou. */
    public interface Progress {
        void rows(long count);

        boolean cancelled();
    }

    public record Result(long rows, List<String> warnings) {
    }

    /** Exportação interrompida pelo usuário (o arquivo parcial é apagado). */
    public static class CancelledException extends Exception {
        public CancelledException() {
            super("Exportação cancelada.");
        }
    }

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter BR_DT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter BR_DT_MS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS");
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DT_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private DataExporter() {
    }

    public static Result export(Connection c, String sql, List<Object> params, Path out, Options opt, Progress progress)
            throws SQLException, IOException, CancelledException {
        // O driver do PostgreSQL só lê em partes (fetchSize) dentro de uma transação; sem isso
        // carregaria a tabela inteira na memória antes de gravar a primeira linha.
        boolean pgStream = c.getAutoCommit()
                && c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres");
        if (!pgStream) {
            return exportImpl(c, sql, params, out, opt, progress);
        }
        c.setAutoCommit(false);
        try {
            return exportImpl(c, sql, params, out, opt, progress);
        } finally {
            try {
                c.rollback(); // só leitura: nada a gravar
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static Result exportImpl(Connection c, String sql, List<Object> params, Path out, Options opt,
                                     Progress progress) throws SQLException, IOException, CancelledException {
        Path tmp = out.resolveSibling(out.getFileName() + ".parcial");
        boolean ok = false;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(1000);
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
            }
            Result r;
            try (ResultSet rs = ps.executeQuery()) {
                r = opt.format() == Format.XLSX ? writeXlsx(rs, tmp, opt, progress) : writeCsv(rs, tmp, opt, progress);
            }
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ok = true;
            return r;
        } finally {
            if (!ok) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    // =====================================================================
    // CSV
    // =====================================================================

    private static Result writeCsv(ResultSet rs, Path out, Options opt, Progress p)
            throws SQLException, IOException, CancelledException {
        CsvStyle st = opt.csv() == null ? CsvStyle.EXCEL_BR : opt.csv();
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        long count = 0;
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8), 1 << 16)) {
            if (st.bom) {
                w.write('﻿');
            }
            if (opt.header()) {
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        w.write(st.separator);
                    }
                    w.write(csvField(md.getColumnLabel(i), st.separator));
                }
                w.write("\r\n");
            }
            while (rs.next()) {
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        w.write(st.separator);
                    }
                    Object v = read(rs, i, md.getColumnType(i), false);
                    w.write(csvField(csvText(v, st), st.separator));
                }
                w.write("\r\n");
                count++;
                if (count % 500 == 0) {
                    p.rows(count);
                    if (p.cancelled()) {
                        throw new CancelledException();
                    }
                }
            }
        }
        p.rows(count);
        return new Result(count, List.of());
    }

    static String csvText(Object v, CsvStyle st) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal bd) {
            String s = bd.toPlainString();
            return st.decimalComma ? s.replace('.', ',') : s;
        }
        if (v instanceof Double || v instanceof Float) {
            String s = new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
            return st.decimalComma ? s.replace('.', ',') : s;
        }
        if (v instanceof LocalDate d) {
            return st.brDates ? d.format(BR_DATE) : d.toString();
        }
        if (v instanceof LocalDateTime dt) {
            boolean ms = dt.getNano() != 0;
            return dt.format(st.brDates ? (ms ? BR_DT_MS : BR_DT) : (ms ? ISO_DT_MS : ISO_DT));
        }
        if (v instanceof LocalTime t) {
            return t.format(TIME);
        }
        if (v instanceof byte[] b) {
            return HexFormat.of().formatHex(b);
        }
        return String.valueOf(v);
    }

    static String csvField(String s, char sep) {
        boolean quote = s.indexOf(sep) >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || (!s.isEmpty() && (Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1))));
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // =====================================================================
    // Excel
    // =====================================================================

    private static Result writeXlsx(ResultSet rs, Path out, Options opt, Progress p)
            throws SQLException, IOException, CancelledException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> names = new ArrayList<>();
        int[] widths = new int[n];
        int[] types = new int[n];
        for (int i = 1; i <= n; i++) {
            names.add(md.getColumnLabel(i));
            types[i - 1] = md.getColumnType(i);
            widths[i - 1] = Math.max(md.getColumnLabel(i).length() + 3, defaultWidth(types[i - 1], md.getPrecision(i)));
        }
        List<String> warnings = new ArrayList<>();
        long count = 0;
        try (XlsxWriter x = new XlsxWriter(out, opt.sheetName())) {
            x.header(names, widths, opt.header());
            Object[] row = new Object[n];
            while (rs.next()) {
                for (int i = 1; i <= n; i++) {
                    row[i - 1] = read(rs, i, types[i - 1], true);
                }
                x.row(row);
                count++;
                if (count % 500 == 0) {
                    p.rows(count);
                    if (p.cancelled()) {
                        throw new CancelledException();
                    }
                }
            }
            if (x.truncatedText()) {
                warnings.add("Algum texto passou de 32.767 caracteres (limite do Excel por célula) e foi cortado.");
            }
        }
        p.rows(count);
        return new Result(count, warnings);
    }

    private static int defaultWidth(int type, int precision) {
        return switch (type) {
            case Types.DATE -> 12;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> 20;
            case Types.TIME -> 10;
            case Types.BOOLEAN, Types.BIT -> 8;
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> 10;
            case Types.BIGINT -> 14;
            case Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.REAL, Types.FLOAT -> 14;
            default -> precision > 0 && precision < 60 ? precision + 2 : 30;
        };
    }

    // =====================================================================
    // Leitura dos valores
    // =====================================================================

    /** Lê o valor completo (CLOB inteiro, BLOB inteiro) já convertido para tipos java.time. */
    static Object read(ResultSet rs, int i, int type, boolean forExcel) throws SQLException, IOException {
        Object v = rs.getObject(i);
        if (v == null) {
            return null;
        }
        if (v instanceof Clob clob) {
            try (Reader r = clob.getCharacterStream()) {
                java.io.StringWriter sw = new java.io.StringWriter();
                r.transferTo(sw);
                return sw.toString();
            }
        }
        if (v instanceof Blob blob) {
            if (forExcel) {
                return "(binário, " + ValueFormatter.humanSize(blob.length()) + ")";
            }
            try (InputStream in = blob.getBinaryStream()) {
                return in.readAllBytes();
            }
        }
        if (v instanceof byte[] b) {
            return forExcel ? "(binário, " + ValueFormatter.humanSize(b.length) + ")" : b;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        if (v instanceof OffsetDateTime odt) {
            return forExcel ? odt.toLocalDateTime() : odt.toString();
        }
        if (v instanceof ZonedDateTime zdt) {
            return forExcel ? zdt.toLocalDateTime() : zdt.toString();
        }
        if (v instanceof Array arr) {
            return java.util.Arrays.deepToString((Object[]) arr.getArray());
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof String || v instanceof LocalDate
                || v instanceof LocalDateTime || v instanceof LocalTime) {
            return v;
        }
        return String.valueOf(v);
    }

    /** Nome de arquivo seguro a partir do nome da tabela. */
    public static String fileName(String base, Format f) {
        String b = base == null || base.isBlank() ? "dados" : base.replaceAll("[\\\\/:*?\"<>|]", "_");
        return b + (f == Format.XLSX ? ".xlsx" : ".csv");
    }

    /** Usado pela importação para ler arquivos em outra codificação. */
    static Charset windows1252() {
        return Charset.forName("windows-1252");
    }
}
