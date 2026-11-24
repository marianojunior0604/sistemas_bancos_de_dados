package br.com.marianojunior.h2studio.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Converte o texto digitado na grade para o tipo Java da coluna, validando
 * tamanho, precisão e NOT NULL antes de ir ao banco.
 * <p>
 * Aceita os formatos brasileiros: {@code 1.234,56}, {@code 31/12/2026},
 * {@code 31/12/2026 14:30}, {@code sim/não}. Também aceita o formato ISO.
 */
public final class ValueParser {

    /** Erro de conversão com mensagem pronta para o usuário. */
    public static class InvalidValueException extends Exception {
        public InvalidValueException(String message) {
            super(message);
        }
    }

    private static final List<DateTimeFormatter> DATES = List.of(
            strict("dd/MM/uuuu"), strict("d/M/uuuu"), strict("dd/MM/uu"), strict("dd-MM-uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private static final DateTimeFormatter BR_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("d/M/uuuu[ HH:mm[:ss]]")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter ISO_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd[['T'][ ]HH:mm[:ss]]")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private ValueParser() {
    }

    private static DateTimeFormatter strict(String p) {
        return DateTimeFormatter.ofPattern(p).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Converte o texto para o tipo da coluna. Texto vazio vira NULL, exceto em colunas de texto. */
    public static Object parse(String text, Meta.ColumnInfo col) throws InvalidValueException {
        int t = col.jdbcType();
        boolean textual = isTextual(t);
        if (text == null || (!textual && text.isBlank())) {
            return checkNull(null, col);
        }
        String s = textual ? text : text.trim();
        Object v = switch (t) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> parseInt(s, t);
            case Types.BIGINT -> parseLong(s);
            case Types.DECIMAL, Types.NUMERIC -> parseDecimal(s, col);
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> parseDouble(s);
            case Types.BOOLEAN, Types.BIT -> parseBoolean(s);
            case Types.DATE -> parseDate(s);
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> parseTime(s);
            case Types.TIMESTAMP -> parseDateTime(s);
            case Types.TIMESTAMP_WITH_TIMEZONE -> parseDateTime(s).atZone(ZoneId.systemDefault()).toOffsetDateTime();
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> parseHex(s);
            default -> {
                if ("UUID".equalsIgnoreCase(col.typeName())) {
                    yield parseUuid(s);
                }
                yield s;
            }
        };
        if (v instanceof String str && SqlUtil.hasLength(t) && col.size() > 0 && str.length() > col.size()) {
            throw new InvalidValueException("Texto com " + str.length() + " caracteres; a coluna "
                    + col.name() + " aceita no máximo " + col.size() + ".");
        }
        return v;
    }

    /** Valida se a coluna aceita NULL. */
    public static Object checkNull(Object v, Meta.ColumnInfo col) throws InvalidValueException {
        if (v == null && !col.nullable() && !col.autoIncrement()) {
            throw new InvalidValueException("A coluna " + col.name() + " é obrigatória (NOT NULL).");
        }
        return v;
    }

    public static boolean isTextual(int t) {
        return t == Types.VARCHAR || t == Types.CHAR || t == Types.NVARCHAR || t == Types.NCHAR
                || t == Types.LONGVARCHAR || t == Types.LONGNVARCHAR || t == Types.CLOB || t == Types.NCLOB;
    }

    public static boolean isLob(int t) {
        return t == Types.CLOB || t == Types.NCLOB || t == Types.BLOB || t == Types.LONGVARBINARY
                || t == Types.LONGVARCHAR;
    }

    public static boolean isBinary(int t) {
        return t == Types.BINARY || t == Types.VARBINARY || t == Types.LONGVARBINARY || t == Types.BLOB;
    }

    public static boolean isBoolean(int t) {
        return t == Types.BOOLEAN || t == Types.BIT;
    }

    // ---------------------------------------------------------------- números

    /** "1.234,56" → "1234.56"; "1234.56" e "1234" ficam como estão. */
    static String normalizeNumber(String s) throws InvalidValueException {
        String n = s.replace(" ", "").replace("R$", "");
        if (n.contains(",")) {
            n = n.replace(".", "").replace(',', '.');
        } else if (n.matches("-?\\d{1,3}(\\.\\d{3})+")) {
            n = n.replace(".", ""); // 1.234.567 sem vírgula = milhar
        }
        if (!n.matches("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?")) {
            throw new InvalidValueException("\"" + s + "\" não é um número válido.");
        }
        return n;
    }

    private static Integer parseInt(String s, int type) throws InvalidValueException {
        BigDecimal bd = new BigDecimal(normalizeNumber(s));
        long min = type == Types.TINYINT ? Byte.MIN_VALUE : type == Types.SMALLINT ? Short.MIN_VALUE : Integer.MIN_VALUE;
        long max = type == Types.TINYINT ? Byte.MAX_VALUE : type == Types.SMALLINT ? Short.MAX_VALUE : Integer.MAX_VALUE;
        try {
            long v = bd.longValueExact();
            if (v < min || v > max) {
                throw new InvalidValueException("Valor fora do intervalo permitido (" + min + " a " + max + ").");
            }
            return (int) v;
        } catch (ArithmeticException e) {
            throw new InvalidValueException("A coluna aceita apenas números inteiros.");
        }
    }

    private static Long parseLong(String s) throws InvalidValueException {
        try {
            return new BigDecimal(normalizeNumber(s)).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidValueException("A coluna aceita apenas números inteiros.");
        }
    }

    private static BigDecimal parseDecimal(String s, Meta.ColumnInfo col) throws InvalidValueException {
        BigDecimal bd = new BigDecimal(normalizeNumber(s));
        int scale = Math.max(0, col.scale());
        int precision = col.size();
        if (precision > 0 && precision < 1000) {
            bd = bd.setScale(scale, RoundingMode.HALF_UP);
            int intDigits = bd.precision() - bd.scale();
            if (intDigits > precision - scale) {
                throw new InvalidValueException("Número grande demais para " + col.typeDisplay()
                        + " (máximo de " + (precision - scale) + " dígitos antes da vírgula).");
            }
        }
        return bd;
    }

    private static Double parseDouble(String s) throws InvalidValueException {
        return Double.valueOf(normalizeNumber(s));
    }

    private static Boolean parseBoolean(String s) throws InvalidValueException {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "t", "1", "sim", "s", "yes", "y", "verdadeiro", "v" -> Boolean.TRUE;
            case "false", "f", "0", "não", "nao", "n", "no", "falso" -> Boolean.FALSE;
            default -> throw new InvalidValueException("Use sim/não (ou true/false).");
        };
    }

    // ---------------------------------------------------------------- datas

    private static LocalDate parseDate(String s) throws InvalidValueException {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.equals("hoje")) {
            return LocalDate.now();
        }
        for (DateTimeFormatter f : DATES) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        throw new InvalidValueException("\"" + s + "\" não é uma data válida. Use dd/mm/aaaa.");
    }

    private static LocalTime parseTime(String s) throws InvalidValueException {
        if (s.equalsIgnoreCase("agora")) {
            return LocalTime.now().withNano(0);
        }
        try {
            return LocalTime.parse(s.length() == 5 ? s + ":00" : s);
        } catch (DateTimeParseException e) {
            throw new InvalidValueException("\"" + s + "\" não é uma hora válida. Use hh:mm ou hh:mm:ss.");
        }
    }

    private static LocalDateTime parseDateTime(String s) throws InvalidValueException {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.equals("agora")) {
            return LocalDateTime.now().withNano(0);
        }
        if (l.equals("hoje")) {
            return LocalDate.now().atStartOfDay();
        }
        for (DateTimeFormatter f : List.of(BR_DATE_TIME, ISO_DATE_TIME)) {
            try {
                return LocalDateTime.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        try {
            return OffsetDateTime.parse(s).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            throw new InvalidValueException("\"" + s + "\" não é data/hora válida. Use dd/mm/aaaa hh:mm:ss.");
        }
    }

    // ---------------------------------------------------------------- outros

    private static byte[] parseHex(String s) throws InvalidValueException {
        String h = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
        h = h.replace(" ", "");
        try {
            return HexFormat.of().parseHex(h);
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException("Valor binário deve ser hexadecimal (ex.: CAFE01).");
        }
    }

    private static UUID parseUuid(String s) throws InvalidValueException {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException("\"" + s + "\" não é um UUID válido.");
        }
    }
}
