package br.com.marianojunior.h2studio.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Analisador léxico simples do SQL do H2. Usado para realce de sintaxe, divisão
 * de instruções, formatação e autocompletar. Nunca falha: texto incompleto
 * (aspas ou comentário sem fechar) vira um token até o fim.
 */
public final class SqlLexer {

    public enum Type {
        KEYWORD, TYPE, FUNCTION, IDENT, QUOTED_IDENT, STRING, NUMBER, COMMENT, OPERATOR, SEMICOLON,
        WHITESPACE, PARAM
    }

    public record Token(Type type, int start, int end, String text) {
        public boolean is(Type t) {
            return type == t;
        }

        /** Texto em maiúsculas (para comparar palavras-chave). */
        public String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        public boolean isWord(String w) {
            return (type == Type.KEYWORD || type == Type.IDENT || type == Type.TYPE || type == Type.FUNCTION)
                    && text.equalsIgnoreCase(w);
        }

        public boolean significant() {
            return type != Type.WHITESPACE && type != Type.COMMENT;
        }
    }

    public static final Set<String> KEYWORDS = Set.of(
            "ADD", "ALL", "ALTER", "ALWAYS", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC", "AUTO_INCREMENT",
            "BACKUP", "BEGIN", "BETWEEN", "BY", "CACHED", "CALL", "CASCADE", "CASE", "CHECK", "COLUMN",
            "COMMENT", "COMMIT", "COMPACT", "CONSTRAINT", "CREATE", "CROSS", "CURRENT_DATE", "CURRENT_TIME",
            "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT", "DELETE", "DESC", "DISTINCT", "DO", "DROP", "ELSE",
            "END", "ESCAPE", "EXCEPT", "EXISTS", "EXPLAIN", "FALSE", "FETCH", "FILTER", "FIRST", "FOR", "FOREIGN",
            "FROM", "FULL", "GENERATED", "GLOBAL", "GRANT", "GROUP", "HAVING", "IDENTITY", "IF", "ILIKE", "IN",
            "INDEX", "INNER", "INSERT", "INTERSECT", "INTERVAL", "INTO", "IS", "JOIN", "KEY", "LAST", "LEFT",
            "LIKE", "LIMIT", "LOCAL", "LOCALTIME", "LOCALTIMESTAMP", "MATCHED", "MEMORY", "MERGE", "MINUS",
            "NATURAL", "NEXT", "NO", "NOT", "NULL", "NULLS", "OFFSET", "ON", "ONLY", "OR", "ORDER", "OUTER",
            "OVER", "PARTITION", "PRIMARY", "QUALIFY", "RECURSIVE", "REFERENCES", "RENAME", "RESTRICT", "REVOKE",
            "RIGHT", "ROLLBACK", "ROW", "ROWS", "RUNSCRIPT", "SAVEPOINT", "SCHEMA", "SCRIPT", "SELECT", "SEQUENCE",
            "SET", "SHOW", "SHUTDOWN", "SOME", "TABLE", "TEMPORARY", "THEN", "TO", "TOP", "TRANSACTION",
            "TRIGGER", "TRUE", "TRUNCATE", "UNION", "UNIQUE", "UNKNOWN", "UPDATE", "USER", "USING", "VALUES",
            "VIEW", "WHEN", "WHERE", "WINDOW", "WITH", "WORK");

    public static final Set<String> TYPES = Set.of(
            "BIGINT", "BINARY", "BIT", "BLOB", "BOOL", "BOOLEAN", "BYTEA", "CHAR", "CHARACTER", "CLOB", "DATE",
            "DATETIME", "DEC", "DECFLOAT", "DECIMAL", "DOUBLE", "ENUM", "FLOAT", "GEOMETRY", "INT", "INTEGER",
            "JSON", "LARGE", "LONGVARCHAR", "NUMBER", "NUMERIC", "OBJECT", "PRECISION", "REAL", "SERIAL",
            "SMALLINT", "TEXT", "TIME", "TIMESTAMP", "TINYINT", "UUID", "VARBINARY", "VARCHAR",
            "VARCHAR_IGNORECASE", "VARYING", "ZONE");

    /** Funções mais usadas (para autocompletar e realce). */
    public static final List<String> FUNCTIONS = List.of(
            "ABS", "AVG", "CASEWHEN", "CAST", "CEIL", "CHAR_LENGTH", "COALESCE", "CONCAT", "CONCAT_WS", "CONVERT",
            "COUNT", "CURRENT_DATE", "CURRENT_TIMESTAMP", "DATEADD", "DATEDIFF", "DATE_TRUNC", "DAY_OF_WEEK",
            "EXTRACT", "FLOOR", "FORMATDATETIME", "GREATEST", "GROUP_CONCAT", "IFNULL", "INSTR", "LEAST", "LEFT",
            "LENGTH", "LISTAGG", "LOCATE", "LOWER", "LPAD", "LTRIM", "MAX", "MIN", "MOD", "MONTH", "NOW", "NULLIF",
            "NVL", "PARSEDATETIME", "POSITION", "POWER", "RAND", "RANDOM_UUID", "REGEXP_REPLACE", "REPEAT",
            "REPLACE", "RIGHT", "ROUND", "ROW_NUMBER", "RPAD", "RTRIM", "SUBSTR", "SUBSTRING", "SUM", "SYSDATE",
            "TO_CHAR", "TO_DATE", "TRIM", "TRUNC", "TRUNCATE", "UPPER", "YEAR");

    private static final Set<String> FUNCTION_SET = Set.copyOf(FUNCTIONS);

    private SqlLexer() {
    }

    public static List<Token> tokenize(String s) {
        return tokenize(s, DbType.H2);
    }

    /**
     * Divide o texto em tokens conforme o dialeto: `nome` e # comentário no MySQL,
     * [nome] no SQL Server, $tag$...$tag$ no PostgreSQL, // comentário só no H2.
     */
    public static List<Token> tokenize(String s, DbType dialect) {
        DbType d = dialect == null ? DbType.H2 : dialect;
        boolean mysql = d == DbType.MYSQL || d == DbType.MARIADB;
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int start = i;
            Type type;
            if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                type = Type.WHITESPACE;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-'
                    || d == DbType.H2 && c == '/' && i + 1 < n && s.charAt(i + 1) == '/'
                    || mysql && c == '#') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                type = Type.COMMENT;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                type = Type.COMMENT;
            } else if (c == '\'') {
                i = quoted(s, i, '\'', mysql);
                type = Type.STRING;
            } else if (c == '"') {
                i = quoted(s, i, '"', mysql);
                type = mysql ? Type.STRING : Type.QUOTED_IDENT; // no MySQL "..." é texto
            } else if (mysql && c == '`') {
                i = quoted(s, i, '`', false);
                type = Type.QUOTED_IDENT;
            } else if (d == DbType.SQLSERVER && c == '[') {
                int e = s.indexOf(']', i + 1);
                while (e >= 0 && e + 1 < n && s.charAt(e + 1) == ']') {
                    e = s.indexOf(']', e + 2);
                }
                i = e < 0 ? n : e + 1;
                type = Type.QUOTED_IDENT;
            } else if (c == '$' && (d == DbType.H2 ? s.startsWith("$$", i) : d == DbType.POSTGRESQL && dollarTag(s, i) != null)) {
                String tag = d == DbType.H2 ? "$$" : dollarTag(s, i);
                int e = s.indexOf(tag, i + tag.length());
                i = e < 0 ? n : e + tag.length();
                type = Type.STRING;
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E') && i + 1 < n
                        && (Character.isDigit(s.charAt(i + 1)) || (s.charAt(i + 1) == '-' || s.charAt(i + 1) == '+')
                        && i + 2 < n && Character.isDigit(s.charAt(i + 2)))) {
                    i += 2;
                    while (i < n && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                type = Type.NUMBER;
            } else if (isIdentStart(c)) {
                // no MySQL o '$' fica de fora para "END$$" (DELIMITER $$) separar o fim do corpo
                while (i < n && isIdentPart(s.charAt(i)) && !(mysql && s.charAt(i) == '$')) {
                    i++;
                }
                String w = s.substring(start, i).toUpperCase(Locale.ROOT);
                int j = i;
                while (j < n && s.charAt(j) == ' ') {
                    j++;
                }
                boolean call = j < n && s.charAt(j) == '(';
                if (call && FUNCTION_SET.contains(w) && !KEYWORDS.contains(w)) {
                    type = Type.FUNCTION;
                } else if (KEYWORDS.contains(w)) {
                    type = Type.KEYWORD;
                } else if (TYPES.contains(w)) {
                    type = Type.TYPE;
                } else if (call && FUNCTION_SET.contains(w)) {
                    type = Type.FUNCTION;
                } else {
                    type = Type.IDENT;
                }
            } else if (c == ';') {
                i++;
                type = Type.SEMICOLON;
            } else if (c == '?' || c == ':' && i + 1 < n && isIdentStart(s.charAt(i + 1)) && (i == 0 || s.charAt(i - 1) != ':')) {
                i++;
                while (i < n && isIdentPart(s.charAt(i))) {
                    i++;
                }
                type = Type.PARAM;
            } else {
                i++;
                if (i < n) {
                    String two = s.substring(start, i + 1);
                    if (two.equals("<=") || two.equals(">=") || two.equals("<>") || two.equals("!=")
                            || two.equals("||") || two.equals("::")) {
                        i++;
                    }
                }
                type = Type.OPERATOR;
            }
            out.add(new Token(type, start, i, s.substring(start, i)));
        }
        return out;
    }

    /** "$$" ou "$corpo$" no início de um texto do PostgreSQL; null se não for. */
    private static String dollarTag(String s, int i) {
        int j = i + 1;
        while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
            j++;
        }
        if (j < s.length() && s.charAt(j) == '$' && (j == i + 1 || !Character.isDigit(s.charAt(i + 1)))) {
            return s.substring(i, j + 1);
        }
        return null;
    }

    private static int quoted(String s, int i, char q) {
        return quoted(s, i, q, false);
    }

    private static int quoted(String s, int i, char q, boolean backslashEscapes) {
        int n = s.length();
        i++;
        while (i < n) {
            if (backslashEscapes && s.charAt(i) == '\\') {
                i += 2; // MySQL: 'it\'s'
                continue;
            }
            if (s.charAt(i) == q) {
                if (i + 1 < n && s.charAt(i + 1) == q) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    public static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    public static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Remove aspas de um identificador ("Nome" → Nome). */
    public static String unquote(String ident) {
        if (ident.length() >= 2 && ident.startsWith("\"") && ident.endsWith("\"")) {
            return ident.substring(1, ident.length() - 1).replace("\"\"", "\"");
        }
        if (ident.length() >= 2 && ident.startsWith("`") && ident.endsWith("`")) {
            return ident.substring(1, ident.length() - 1).replace("``", "`");
        }
        if (ident.length() >= 2 && ident.startsWith("[") && ident.endsWith("]")) {
            return ident.substring(1, ident.length() - 1).replace("]]", "]");
        }
        return ident;
    }
}
