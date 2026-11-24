package br.com.marianojunior.h2studio.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/** Formata valores do banco para exibição (padrão brasileiro de datas). */
public final class ValueFormatter {

    public static final String NULL_TEXT = "NULL";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ValueFormatter() {
    }

    public static String format(Object v) {
        if (v == null) {
            return NULL_TEXT;
        }
        if (v instanceof TableDataService.LongText t) {
            String s = t.preview();
            return t.length() > s.length() ? s + "… (" + t.length() + " caracteres)" : s;
        }
        if (v == TableEditor.DEFAULT) {
            return "(padrão)";
        }
        if (v instanceof byte[] bytes) {
            byte[] head = java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 16));
            return "(binário, " + humanSize(bytes.length) + ") " + HexFormat.of().formatHex(head)
                    + (bytes.length > head.length ? "…" : "");
        }
        if (v instanceof TableDataService.BinaryValue b) {
            return "(binário, " + humanSize(b.size()) + ") " + HexFormat.of().formatHex(b.head())
                    + (b.size() > b.head().length ? "…" : "");
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(withFraction(ts.getNanos()));
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().format(DATE);
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().format(TIME);
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.format(withFraction(ldt.getNano()));
        }
        if (v instanceof LocalDate ld) {
            return ld.format(DATE);
        }
        if (v instanceof LocalTime lt) {
            return lt.format(TIME);
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toLocalDateTime().format(withFraction(odt.getNano())) + " " + odt.getOffset();
        }
        if (v instanceof ZonedDateTime zdt) {
            return zdt.toLocalDateTime().format(withFraction(zdt.getNano())) + " " + zdt.getZone();
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return String.valueOf(v);
    }

    private static DateTimeFormatter withFraction(int nanos) {
        return nanos == 0 ? DATE_TIME : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS");
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
