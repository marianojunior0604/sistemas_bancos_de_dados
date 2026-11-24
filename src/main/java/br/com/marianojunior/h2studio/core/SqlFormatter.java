package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.SqlLexer.Token;
import br.com.marianojunior.h2studio.core.SqlLexer.Type;
import java.util.List;
import java.util.Set;

/**
 * Formatador simples: palavras-chave em maiúsculas e uma cláusula por linha.
 * Só mexe em espaços e na caixa das palavras-chave; textos, comentários e
 * identificadores entre aspas ficam intactos.
 */
public final class SqlFormatter {

    /** Cláusulas que começam uma nova linha. */
    private static final Set<String> CLAUSES = Set.of("SELECT", "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "MINUS", "VALUES", "SET", "FETCH", "QUALIFY", "WINDOW");
    /** Começam linha nova junto com a palavra seguinte (GROUP BY, ORDER BY). */
    private static final Set<String> TWO_WORD_CLAUSES = Set.of("GROUP", "ORDER");
    private static final Set<String> JOIN_START = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "NATURAL");

    private SqlFormatter() {
    }

    /**
     * @param upperKeywords false quando o banco diferencia maiúsculas (DATABASE_TO_UPPER=FALSE):
     *                      assim um identificador com nome de palavra-chave não muda
     */
    public static String format(String sql, boolean upperKeywords) {
        return format(sql, upperKeywords, DbType.H2);
    }

    /** Formata no dialeto do banco (aspas `...` e [...], comentários #, $corpo$...). */
    public static String format(String sql, boolean upperKeywords, DbType type) {
        List<Token> tokens = SqlLexer.tokenize(sql, type);
        StringBuilder out = new StringBuilder();
        int depth = 0;
        String clause = "";
        Token prev = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is(Type.WHITESPACE)) {
                continue;
            }
            String text = t.text();
            if (upperKeywords && (t.is(Type.KEYWORD) || t.is(Type.TYPE) || t.is(Type.FUNCTION))) {
                text = t.upper();
            }
            String u = t.upper();
            boolean keyword = t.is(Type.KEYWORD);
            boolean newLine = false;
            String indent = "";
            if (keyword && depth == 0 && prev != null && !prev.is(Type.SEMICOLON)) {
                boolean afterJoinWord = prev.is(Type.KEYWORD) && JOIN_START.contains(prev.upper());
                if (CLAUSES.contains(u) && !(u.equals("SET") && clause.equals("UPDATE_SET_DONE"))) {
                    newLine = !(u.equals("SELECT") && prev.text().equals("("));
                } else if (TWO_WORD_CLAUSES.contains(u) && next(tokens, i).isWord("BY")) {
                    newLine = true;
                } else if (JOIN_START.contains(u) && !afterJoinWord) {
                    newLine = true;
                } else if ((u.equals("AND") || u.equals("OR")) && (clause.equals("WHERE") || clause.equals("HAVING")
                        || clause.equals("ON")) && !prevIsBetween(tokens, i)) {
                    newLine = true;
                    indent = "  ";
                } else if (u.equals("ON") && clause.equals("JOIN")) {
                    newLine = true;
                    indent = "  ";
                }
            }
            if (keyword && depth == 0) {
                if (CLAUSES.contains(u) || TWO_WORD_CLAUSES.contains(u)) {
                    clause = u;
                } else if (JOIN_START.contains(u)) {
                    clause = "JOIN";
                } else if (u.equals("ON") && clause.equals("JOIN")) {
                    clause = "ON";
                }
            }
            // espaço / quebra antes do token
            if (out.length() > 0) {
                if (newLine) {
                    trimEnd(out);
                    out.append('\n').append(indent);
                } else if (prev != null && prev.is(Type.SEMICOLON)) {
                    trimEnd(out);
                    out.append("\n\n");
                } else if (prev != null && prev.is(Type.COMMENT) && prev.text().startsWith("--")) {
                    out.append('\n');
                } else if (needsSpace(prev, t)) {
                    out.append(' ');
                }
            }
            out.append(text);
            if (t.text().equals("(")) {
                depth++;
            } else if (t.text().equals(")")) {
                depth = Math.max(0, depth - 1);
            } else if (t.is(Type.SEMICOLON)) {
                depth = 0;
                clause = "";
            }
            prev = t;
        }
        return out.toString().trim();
    }

    private static Token next(List<Token> tokens, int i) {
        for (int j = i + 1; j < tokens.size(); j++) {
            if (tokens.get(j).significant()) {
                return tokens.get(j);
            }
        }
        return new Token(Type.WHITESPACE, 0, 0, "");
    }

    /** "BETWEEN a AND b": esse AND não quebra linha. */
    private static boolean prevIsBetween(List<Token> tokens, int i) {
        int seen = 0;
        for (int j = i - 1; j >= 0 && seen < 6; j--) {
            Token t = tokens.get(j);
            if (!t.significant()) {
                continue;
            }
            seen++;
            if (t.isWord("BETWEEN")) {
                return true;
            }
            if (t.isWord("AND") || t.isWord("OR") || t.isWord("WHERE")) {
                return false;
            }
        }
        return false;
    }

    private static boolean needsSpace(Token prev, Token t) {
        if (prev == null) {
            return false;
        }
        String p = prev.text();
        String c = t.text();
        if (c.equals(",") || c.equals(")") || c.equals(".") || c.equals(";") || p.equals("(") || p.equals(".")
                || p.equals("::") || c.equals("::")) {
            return false;
        }
        if (c.equals("(")) {
            // função(…) junto; palavra-chave / operador ( … ) separado
            return !(prev.is(Type.FUNCTION) || prev.is(Type.IDENT) || prev.is(Type.QUOTED_IDENT) || prev.is(Type.TYPE));
        }
        return true;
    }

    private static void trimEnd(StringBuilder sb) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
    }
}
