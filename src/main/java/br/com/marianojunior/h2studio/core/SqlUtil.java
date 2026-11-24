package br.com.marianojunior.h2studio.core;

import java.sql.Types;

public final class SqlUtil {

    private SqlUtil() {
    }

    /** Identificador entre aspas duplas (preserva maiúsculas/minúsculas e evita injeção). */
    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static boolean hasLength(int t) {
        return t == Types.VARCHAR || t == Types.CHAR || t == Types.NVARCHAR || t == Types.NCHAR
                || t == Types.VARBINARY || t == Types.BINARY;
    }

    public static boolean hasPrecision(int t) {
        return t == Types.DECIMAL || t == Types.NUMERIC;
    }

    public static boolean isNumeric(int t) {
        return switch (t) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL, Types.NUMERIC,
                 Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
            default -> false;
        };
    }

    /** Colunas de texto (CHAR, VARCHAR e afins). */
    public static boolean isText(int t) {
        return switch (t) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> true;
            default -> false;
        };
    }

    /** Colunas que fazem sentido na busca rápida (texto, números, datas). */
    public static boolean isSearchable(int t) {
        return switch (t) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB, Types.CLOB, Types.NCLOB,
                 Types.ARRAY, Types.JAVA_OBJECT, Types.OTHER, Types.STRUCT -> false;
            default -> true;
        };
    }
}
