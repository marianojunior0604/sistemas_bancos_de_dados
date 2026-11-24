package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.SqlLexer.Token;
import br.com.marianojunior.h2studio.core.SqlLexer.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Operações sobre um texto SQL: dividir em instruções, classificar, avisos, posição de erro. */
public final class SqlScript {

    /** Uma instrução do script: posições no texto original (sem o ';' final). */
    public record Statement(int start, int end, String text) {
    }

    public enum Kind {
        /** Só lê (SELECT, WITH, EXPLAIN, SHOW, VALUES, SCRIPT sem TO...). */
        QUERY,
        /** Altera dados (INSERT, UPDATE, DELETE, MERGE). */
        DML,
        /** Altera estrutura (CREATE, ALTER, DROP, TRUNCATE, COMMENT...). */
        DDL,
        /** Controle de transação (COMMIT, ROLLBACK...). */
        TRANSACTION,
        OTHER
    }

    private static final Set<String> QUERY_WORDS = Set.of("SELECT", "WITH", "EXPLAIN", "SHOW", "VALUES", "TABLE",
            "SCRIPT", "HELP");
    private static final Set<String> DML_WORDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT");
    private static final Set<String> DDL_WORDS = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "RENAME",
            "GRANT", "REVOKE", "ANALYZE");
    private static final Set<String> TX_WORDS = Set.of("COMMIT", "ROLLBACK", "BEGIN", "SAVEPOINT", "RELEASE",
            "START");

    private SqlScript() {
    }

    /** Divide o texto em instruções separadas por ';' (ignora ';' dentro de textos e comentários). */
    public static List<Statement> split(String text) {
        List<Statement> out = new ArrayList<>();
        List<Token> tokens = SqlLexer.tokenize(text);
        int stmtStart = -1;
        int stmtEnd = -1;
        for (Token t : tokens) {
            if (t.is(Type.SEMICOLON)) {
                if (stmtStart >= 0) {
                    out.add(new Statement(stmtStart, stmtEnd, text.substring(stmtStart, stmtEnd)));
                }
                stmtStart = -1;
                continue;
            }
            if (!t.significant()) {
                continue;
            }
            if (stmtStart < 0) {
                stmtStart = t.start();
            }
            stmtEnd = t.end();
        }
        if (stmtStart >= 0) {
            out.add(new Statement(stmtStart, stmtEnd, text.substring(stmtStart, stmtEnd)));
        }
        return out;
    }

    private static final java.util.regex.Pattern SET_TERM =
            java.util.regex.Pattern.compile("(?i)SET\\s+TERM\\s+(\\S+?)\\s*\\S*\\s*");
    private static final java.util.regex.Pattern DELIMITER =
            java.util.regex.Pattern.compile("(?i)DELIMITER\\s+(\\S+)\\s*");
    private static final java.util.regex.Pattern BLOCK_START = java.util.regex.Pattern.compile(
            "(?is)(?:(?:CREATE(?:\\s+OR\\s+(?:ALTER|REPLACE))?|ALTER|RECREATE)\\s+"
            + "(?:DEFINER\\s*=\\s*\\S+\\s+)?(?:PROCEDURE|PROC|FUNCTION|TRIGGER|PACKAGE|EVENT)\\b"
            + "|EXECUTE\\s+BLOCK\\b).*");

    /**
     * Divide o script no dialeto do banco. Além do ';', entende:
     * <ul>
     *   <li>Firebird: {@code SET TERM ^ ;} … {@code SET TERM ; ^} (como no isql/IBExpert);</li>
     *   <li>MySQL/MariaDB: {@code DELIMITER $$} … {@code DELIMITER ;};</li>
     *   <li>SQL Server: linhas com {@code GO} separando lotes;</li>
     *   <li>procedures, funções e gatilhos com BEGIN … END: os ';' de dentro do corpo não
     *       quebram a instrução (SQL Server: o corpo vai até o próximo GO ou o fim).</li>
     * </ul>
     */
    public static List<Statement> split(String text, DbType type) {
        DbType d = type == null ? DbType.H2 : type;
        if (d == DbType.H2) {
            return split(text);
        }
        boolean mysql = d == DbType.MYSQL || d == DbType.MARIADB;
        List<Token> tokens = SqlLexer.tokenize(text, d);
        List<Statement> out = new ArrayList<>();
        String term = ";";
        int stmtStart = -1;
        int stmtEnd = -1;
        int skipUntil = -1;
        boolean block = false;
        boolean seenBegin = false;
        boolean sawAs = false;
        int depth = 0;
        for (int k = 0; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.start() < skipUntil) {
                continue;
            }
            int lineEnd = text.indexOf('\n', t.start());
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            // diretivas do cliente (não vão para o banco), só no início de uma instrução
            if (stmtStart < 0 && t.significant()) {
                String line = text.substring(t.start(), lineEnd).strip();
                java.util.regex.Matcher m;
                if (d.isFirebird() && (m = SET_TERM.matcher(line)).matches()) {
                    term = m.group(1);
                    skipUntil = lineEnd;
                    continue;
                }
                if (mysql && (m = DELIMITER.matcher(line)).matches()) {
                    term = m.group(1);
                    skipUntil = lineEnd;
                    continue;
                }
            }
            if (d == DbType.SQLSERVER && t.significant() && t.upper().equals("GO") && aloneOnLine(text, t)) {
                if (stmtStart >= 0) {
                    out.add(new Statement(stmtStart, stmtEnd, text.substring(stmtStart, stmtEnd)));
                }
                stmtStart = -1;
                block = false;
                skipUntil = lineEnd;
                continue;
            }
            boolean isTerm = term.equals(";") ? t.is(Type.SEMICOLON)
                    : t.type() != Type.STRING && t.type() != Type.COMMENT && t.type() != Type.QUOTED_IDENT
                    && t.type() != Type.WHITESPACE && text.startsWith(term, t.start());
            if (isTerm) {
                boolean insideBody = block && (d == DbType.SQLSERVER || depth > 0
                        || (d.isFirebird() && sawAs && !seenBegin));
                if (!insideBody) {
                    if (stmtStart >= 0) {
                        out.add(new Statement(stmtStart, stmtEnd, text.substring(stmtStart, stmtEnd)));
                    }
                    stmtStart = -1;
                    block = false;
                    skipUntil = t.start() + term.length();
                    continue;
                }
            }
            if (!t.significant()) {
                continue;
            }
            if (stmtStart < 0) {
                stmtStart = t.start();
                block = BLOCK_START.matcher(text.substring(t.start(), Math.min(text.length(), t.start() + 300))).matches();
                depth = 0;
                seenBegin = false;
                sawAs = false;
            }
            if (block && (t.type() == Type.KEYWORD || t.type() == Type.IDENT)) {
                String u = t.upper();
                switch (u) {
                    case "BEGIN" -> {
                        depth++;
                        seenBegin = true;
                    }
                    case "CASE" -> depth++;
                    case "END" -> {
                        String next = nextWord(tokens, k);
                        // END IF / END LOOP / END WHILE / END REPEAT (MySQL) não fecham um BEGIN
                        if (!Set.of("IF", "LOOP", "WHILE", "REPEAT", "FOR").contains(next)) {
                            depth--;
                        }
                    }
                    case "AS" -> {
                        if (depth == 0) {
                            sawAs = true;
                        }
                    }
                    default -> { }
                }
            }
            stmtEnd = isTerm ? t.start() + term.length() : t.end();
        }
        if (stmtStart >= 0) {
            out.add(new Statement(stmtStart, stmtEnd, text.substring(stmtStart, stmtEnd)));
        }
        return out;
    }

    private static boolean aloneOnLine(String text, Token t) {
        int ls = text.lastIndexOf('\n', t.start() - 1) + 1;
        int le = text.indexOf('\n', t.end());
        if (le < 0) {
            le = text.length();
        }
        return text.substring(ls, t.start()).isBlank()
                && text.substring(t.end(), le).strip().matches("(\\d+)?\\s*;?");
    }

    private static String nextWord(List<Token> tokens, int k) {
        for (int j = k + 1; j < tokens.size(); j++) {
            if (tokens.get(j).significant()) {
                return tokens.get(j).upper();
            }
        }
        return "";
    }

    /** Instrução onde está o cursor, no dialeto do banco. */
    public static Statement statementAt(String text, int caret, DbType type) {
        if (type == null || type == DbType.H2) {
            return statementAt(text, caret);
        }
        return statementAt(split(text, type), text, caret);
    }

    /**
     * Instrução onde está o cursor. Se o cursor estiver entre instruções (linha em branco,
     * logo depois de um ';'), usa a instrução anterior mais próxima.
     */
    public static Statement statementAt(String text, int caret) {
        return statementAt(split(text), text, caret);
    }

    private static Statement statementAt(List<Statement> all, String text, int caret) {
        Statement best = null;
        for (Statement s : all) {
            if (caret >= s.start() && caret <= s.end()) {
                return s;
            }
            if (s.end() <= caret) {
                best = s;
            }
        }
        if (best != null) {
            // só usa a anterior se entre ela e o cursor houver apenas espaços e o ';'
            String between = text.substring(best.end(), Math.min(caret, text.length()));
            if (between.replace(";", "").isBlank()) {
                return best;
            }
        }
        for (Statement s : all) {
            if (s.start() >= caret) {
                String between = text.substring(caret, s.start());
                return between.isBlank() ? s : null;
            }
        }
        return best;
    }

    /** Primeira palavra significativa da instrução, em maiúsculas. */
    public static String firstWord(String sql) {
        for (Token t : SqlLexer.tokenize(sql)) {
            if (t.significant()) {
                return t.upper();
            }
        }
        return "";
    }

    public static Kind classify(String sql) {
        String w = firstWord(sql);
        if (w.equals("SCRIPT")) {
            return Kind.QUERY;
        }
        if (QUERY_WORDS.contains(w)) {
            return Kind.QUERY;
        }
        if (DML_WORDS.contains(w)) {
            return Kind.DML;
        }
        if (DDL_WORDS.contains(w)) {
            return Kind.DDL;
        }
        if (TX_WORDS.contains(w)) {
            return Kind.TRANSACTION;
        }
        return Kind.OTHER;
    }

    /** Altera o banco (motivo para fazer backup antes). */
    public static boolean modifiesDatabase(String sql) {
        Kind k = classify(sql);
        String w = firstWord(sql);
        return k == Kind.DML || k == Kind.DDL || w.equals("RUNSCRIPT") || w.equals("CALL") || w.equals("SET");
    }

    /** Muda a estrutura (motivo para atualizar a árvore de tabelas). */
    public static boolean changesStructure(String sql) {
        String w = firstWord(sql);
        return classify(sql) == Kind.DDL || w.equals("RUNSCRIPT");
    }

    /** Avisos para instruções perigosas (null quando não há nada a avisar). */
    public static String warning(String sql) {
        List<Token> sig = SqlLexer.tokenize(sql).stream().filter(Token::significant).toList();
        if (sig.isEmpty()) {
            return null;
        }
        String w = sig.get(0).upper();
        boolean hasWhere = sig.stream().anyMatch(t -> t.isWord("WHERE"));
        String target = targetAfter(sig, w.equals("DELETE") ? "FROM" : w.equals("UPDATE") ? "UPDATE" : "TABLE");
        return switch (w) {
            case "DELETE" -> hasWhere ? null : "DELETE sem WHERE: apaga TODAS as linhas" + on(target) + ".";
            case "UPDATE" -> hasWhere ? null : "UPDATE sem WHERE: altera TODAS as linhas" + on(target) + ".";
            case "TRUNCATE" -> "TRUNCATE: apaga todas as linhas" + on(target) + " sem possibilidade de desfazer.";
            case "DROP" -> "DROP: remove " + describeDrop(sig) + " definitivamente, com todos os dados.";
            case "ALTER" -> sig.stream().anyMatch(t -> t.isWord("DROP"))
                    ? "ALTER … DROP: remove uma coluna ou restrição" + on(target) + "." : null;
            case "SHUTDOWN" -> "SHUTDOWN fecha o banco: esta conexão do H2 Studio deixará de funcionar.";
            case "RUNSCRIPT" -> "RUNSCRIPT executa todos os comandos de um arquivo.";
            default -> null;
        };
    }

    private static String on(String target) {
        return target == null ? "" : " de " + target;
    }

    private static String targetAfter(List<Token> sig, String word) {
        for (int i = 0; i < sig.size() - 1; i++) {
            if (sig.get(i).isWord(word)) {
                int j = i + 1;
                while (j < sig.size() && (sig.get(j).isWord("TABLE") || sig.get(j).isWord("FROM")
                        || sig.get(j).isWord("IF") || sig.get(j).isWord("EXISTS") || sig.get(j).isWord("ONLY"))) {
                    j++;
                }
                if (j < sig.size() && (sig.get(j).is(Type.IDENT) || sig.get(j).is(Type.QUOTED_IDENT)
                        || sig.get(j).is(Type.KEYWORD) || sig.get(j).is(Type.TYPE))) {
                    StringBuilder name = new StringBuilder(sig.get(j).text());
                    while (j + 2 < sig.size() && sig.get(j + 1).text().equals(".")) {
                        name.append('.').append(sig.get(j + 2).text());
                        j += 2;
                    }
                    return name.toString();
                }
            }
        }
        return null;
    }

    private static String describeDrop(List<Token> sig) {
        if (sig.size() < 2) {
            return "um objeto";
        }
        String obj = sig.get(1).upper();
        String name = targetAfter(sig, obj);
        String what = switch (obj) {
            case "TABLE" -> "a tabela";
            case "VIEW" -> "a view";
            case "SCHEMA" -> "o schema";
            case "INDEX" -> "o índice";
            case "SEQUENCE" -> "a sequência";
            case "ALL" -> "TODOS os objetos do banco";
            default -> obj.toLowerCase();
        };
        return what + (name != null && !obj.equals("ALL") ? " " + name : "");
    }

    /**
     * Posição do erro na instrução para qualquer banco (-1 se não der para saber):
     * <ul>
     *   <li>H2: marcador [*] ou o nome não encontrado;</li>
     *   <li>PostgreSQL: "Position: N" da mensagem;</li>
     *   <li>Firebird: "line L, column C";</li>
     *   <li>MySQL: "near '...' at line L"; SQL Server e SQLite: near "..." / nome não encontrado.</li>
     * </ul>
     */
    public static int errorPosition(java.sql.SQLException e, String statement, DbType type) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (type == null || type == DbType.H2) {
            int off = errorOffset(msg, statement);
            return off >= 0 ? off : nameOffset(e, statement);
        }
        java.util.regex.Matcher m;
        if ((m = java.util.regex.Pattern.compile("Position: (\\d+)").matcher(msg)).find()) {
            int p = Integer.parseInt(m.group(1)) - 1;
            return p >= 0 && p <= statement.length() ? p : -1;
        }
        if ((m = java.util.regex.Pattern.compile("(?i)line (\\d+), col(?:umn)? (\\d+)").matcher(msg)).find()) {
            return lineColumn(statement, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        if ((m = java.util.regex.Pattern.compile("(?s)near '(.*?)' at line (\\d+)").matcher(msg)).find()) {
            String near = m.group(1);
            int lineStart = lineColumn(statement, Integer.parseInt(m.group(2)), 1);
            if (near.isEmpty()) {
                return statement.length();
            }
            int idx = statement.indexOf(near, Math.max(0, lineStart));
            if (idx < 0) {
                idx = statement.indexOf(near.length() > 20 ? near.substring(0, 20) : near);
            }
            return idx;
        }
        // nomes citados: near "X" (SQLite), near 'X' (SQL Server), no such table: X, Invalid object name 'X',
        // Table 'db.X' doesn't exist, Unknown column 'X', column "X" does not exist...
        String[] patterns = {
            "(?i)near \"([^\"]+)\"", "(?i)near '([^']+)'", "(?i)no such (?:table|column): ([\\w.$]+)",
            "(?i)Invalid (?:object|column) name '([^']+)'", "(?i)Table '([^']+)' doesn't exist",
            "(?i)Unknown column '([^']+)'", "(?i)(?:column|relation) \"([^\"]+)\" does not exist",
            "(?i)(?:Table|Column) unknown\\W+([\\w$.\"]+)"};
        for (String p : patterns) {
            if ((m = java.util.regex.Pattern.compile(p).matcher(msg)).find()) {
                String name = m.group(1).replace("\"", "");
                name = name.substring(name.lastIndexOf('.') + 1);
                java.util.regex.Matcher w = java.util.regex.Pattern.compile("(?i)(?<![\\w$])[\"`\\[]?"
                        + java.util.regex.Pattern.quote(name) + "[\"`\\]]?(?![\\w$])").matcher(statement);
                if (w.find()) {
                    return w.start();
                }
            }
        }
        return -1;
    }

    private static int lineColumn(String text, int line, int column) {
        int pos = 0;
        for (int l = 1; l < line; l++) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) {
                return -1;
            }
            pos = nl + 1;
        }
        int p = pos + Math.max(0, column - 1);
        return p <= text.length() ? p : -1;
    }

    /**
     * Para "tabela/coluna não encontrada" o H2 não marca a posição, mas diz o nome:
     * procura esse nome na instrução.
     */
    public static int nameOffset(java.sql.SQLException e, String statement) {
        int code = e.getErrorCode();
        if (code != 42102 && code != 42104 && code != 42122 && code != 90079 && code != 90022 || e.getMessage() == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(e.getMessage());
        if (!m.find()) {
            return -1;
        }
        String name = unescapeH2(m.group(1));
        name = name.substring(name.lastIndexOf('.') + 1);
        java.util.regex.Matcher w = java.util.regex.Pattern.compile("(?i)(?<![\\w$])\"?" + java.util.regex.Pattern.quote(name)
                + "\"?(?![\\w$])").matcher(statement);
        return w.find() ? w.start() : -1;
    }

    /** O H2 escreve caracteres especiais da instrução como \\000a (quebra de linha), \\0009... */
    public static String unescapeH2(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 4 < s.length() && s.substring(i + 1, i + 5).matches("[0-9a-fA-F]{4}")) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
            } else if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '\\') {
                sb.append('\\');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Posição do erro dentro da instrução, usando o marcador {@code [*]} que o H2 coloca
     * na mensagem (ex.: {@code Syntax error in SQL statement "SELEC[*]T 1"}). Retorna -1
     * se não der para localizar.
     */
    public static int errorOffset(String message, String statement) {
        if (message == null) {
            return -1;
        }
        int marker = message.indexOf("[*]");
        if (marker < 0) {
            return -1;
        }
        int q = message.lastIndexOf('"', marker);
        // o SQL citado começa logo após a aspa que abre (ex.: statement "SELECT...)
        while (q > 0 && !(message.charAt(q) == '"' && (q == 0 || Character.isWhitespace(message.charAt(q - 1))))) {
            q = message.lastIndexOf('"', q - 1);
        }
        if (q < 0) {
            return -1;
        }
        String before = unescapeH2(message.substring(q + 1, marker));
        int idx = statement.indexOf(before);
        if (idx >= 0) {
            return idx + before.length();
        }
        // o H2 pode normalizar espaços: compara ignorando espaços
        String compact = before.replaceAll("\\s+", "");
        int count = 0;
        for (int i = 0; i < statement.length(); i++) {
            if (!Character.isWhitespace(statement.charAt(i))) {
                if (count == compact.length()) {
                    return i;
                }
                if (statement.charAt(i) != compact.charAt(count)) {
                    return -1;
                }
                count++;
            }
        }
        return count == compact.length() ? statement.length() : -1;
    }
}
