package br.com.marianojunior.h2studio.core;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Traduz os erros mais comuns do H2 para mensagens curtas em português. */
public final class SqlErrors {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    /** "PUBLIC.UK_X ON PUBLIC.CLIENTE(CPF NULLS FIRST) VALUES ( /* 6 *&#47; CAST('0001' AS CHAR(11)) )" */
    private static final Pattern UNIQUE = Pattern.compile(
            "(?:[^ .\"]+\\.)?([^ .\"]+) ON (?:[^ .\"]+\\.)?([^ (]+)\\(([^)]*)\\)(?:\\s*VALUES\\s*(?:\\(\\s*(?:/\\*.*?\\*/\\s*)?(.*)\\s*\\)|\\S+))?\\s*$");
    /** "FK_X: PUBLIC.VENDA FOREIGN KEY(CLIENTE_ID) REFERENCES PUBLIC.CLIENTE(ID) (99999)" */
    private static final Pattern FK = Pattern.compile(
            "(?:[^ .:]+\\.)?([^ .:(]+) FOREIGN KEY\\(([^)]*)\\) REFERENCES (?:[^ .]+\\.)?([^ (]+)\\(([^)]*)\\)(?:\\s*\\((.*)\\))?");
    private static final Pattern CAST = Pattern.compile("CAST\\((.*?) AS .*");

    private SqlErrors() {
    }

    public static String friendly(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String first = msg.split("\n")[0].replaceFirst(";\\s*SQL statement:?$", "").trim();
        String quoted = firstQuoted(first);
        return switch (e.getErrorCode()) {
            case 23505 -> duplicate(quoted);
            case 23502 -> "A coluna " + (quoted != null ? quoted : "") + " é obrigatória e não pode ficar vazia (NULL).";
            case 23506 -> parentMissing(quoted);
            case 23503 -> childExists(quoted);
            case 22001 -> "Valor maior do que o tamanho permitido pela coluna" + (quoted != null ? " " + quoted : "") + ".";
            case 22003 -> "Número fora do intervalo permitido pela coluna.";
            case 22018, 22007 -> "Valor em formato inválido para a coluna" + (quoted != null ? " (" + quoted + ")" : "") + ".";
            case 23513, 23514 -> "O valor não atende a uma regra (CHECK) da tabela"
                    + (quoted != null ? ": " + quoted : "") + ".";
            case 42000, 42001 -> syntax(first);
            case 42102, 42104 -> "Tabela " + quotedOr(quoted, "") + " não encontrada" + hintEmpty(first) + ".";
            case 42122 -> "Coluna " + quotedOr(quoted, "") + " não encontrada.";
            case 42101 -> "Já existe uma tabela ou view chamada " + quotedOr(quoted, "") + ".";
            case 42111 -> "Já existe um índice chamado " + quotedOr(quoted, "") + ".";
            case 42121 -> "Coluna duplicada: " + quotedOr(quoted, "") + ".";
            case 90079 -> "Schema " + quotedOr(quoted, "") + " não encontrado.";
            case 90022 -> "Função " + quotedOr(quoted, "") + " não encontrada.";
            case 90008 -> "Valor inválido: " + first;
            case 57014 -> "Execução interrompida.";
            case 90097 -> "O banco está aberto em modo somente leitura.";
            case 90154, 90155 -> "A coluna " + (quoted != null ? quoted : "") + " é calculada/gerada e não pode ser alterada.";
            case 50200 -> "Tempo esgotado esperando outro programa liberar o registro (bloqueio).";
            case 40001 -> "Conflito com outra transação (deadlock). Tente salvar de novo.";
            default -> first.isEmpty() ? e.getClass().getSimpleName() : first;
        };
    }

    /**
     * Mensagem amigável para o erro de qualquer banco. No H2 usa a tradução detalhada acima;
     * nos outros reconhece os casos comuns pelo SQLState/código do fabricante e acrescenta a
     * mensagem do servidor, que já diz qual coluna ou restrição.
     */
    public static String friendly(SQLException e, DbType type) {
        if (type == null || type == DbType.H2) {
            return friendly(e);
        }
        String msg = e.getMessage() == null ? "" : e.getMessage();
        // o PostgreSQL começa com "ERROR:" (ou "ERRO:" em português): o programa já mostra "ERRO"
        String first = msg.split("\n")[0].trim().replaceFirst("^(ERROR|ERRO|FATAL):\\s*", "");
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        int code = e.getErrorCode();
        String low = msg.toLowerCase(java.util.Locale.ROOT);
        String prefix;
        if (state.equals("23505") || code == 1062 || code == 2627 || code == 2601 || code == 335544665
                || code == 335544349 || low.contains("unique constraint failed") || low.contains("duplicate")) {
            prefix = "Valor duplicado: já existe outro registro com esse valor em uma chave única.";
        } else if (state.equals("23502") || code == 1048 || code == 515 || code == 335544347
                || low.contains("not null constraint failed")) {
            prefix = "Coluna obrigatória ficou vazia (NULL).";
        } else if (state.equals("23503") || code == 1451 || code == 1452 || code == 335544466
                || (code == 547 && (low.contains("foreign key") || low.contains("reference constraint")))
                || (low.contains("foreign key") && low.contains("constraint failed"))) {
            boolean child = low.contains("still referenced") || code == 1451 || low.contains("reference constraint")
                    || low.contains("foreign key references are present") || low.contains("referenced from table");
            prefix = child
                    ? "Não é possível excluir ou alterar: o registro é usado em outra tabela."
                    : "Referência inválida: o valor informado não existe na tabela relacionada.";
        } else if (state.equals("23514") || code == 3819 || code == 335544558 || code == 547
                || low.contains("check constraint")) {
            prefix = "O valor não atende a uma regra (CHECK) da tabela.";
        } else if (state.equals("22001") || code == 1406 || code == 8152 || code == 2628
                || low.contains("string right truncation") || low.contains("too long")) {
            prefix = "Valor maior do que o tamanho permitido pela coluna.";
        } else if (state.equals("25006") || low.contains("readonly database") || low.contains("read-only")) {
            prefix = "O banco ou a conexão está em modo somente leitura.";
        } else if (state.equals("55P03") || code == 1205 || code == 1222 || code == 335544345
                || code == 335544336 || state.equals("HYT00") || state.equals("57014") || low.contains("timeout")
                || low.contains("lock conflict") || low.contains("database is locked")) {
            prefix = "O registro está bloqueado por outro usuário ou programa (tempo esgotado). Tente de novo em instantes.";
        } else if (state.equals("40001") || state.equals("40P01") || code == 1213 || code == 1205) {
            prefix = "Conflito com outra transação (deadlock). Tente salvar de novo.";
        } else {
            return first.isEmpty() ? e.getClass().getSimpleName() : first;
        }
        return prefix + (first.isEmpty() ? "" : "\nDetalhe do servidor: " + first);
    }

    private static String quotedOr(String q, String alt) {
        return q != null ? "\"" + q + "\"" : alt;
    }

    private static String hintEmpty(String first) {
        return first.contains("this database is empty") ? " (o banco está vazio)" : "";
    }

    /** "Syntax error in SQL statement "SELECT [*]FRON X"; expected "FROM"" → texto curto em português. */
    private static String syntax(String first) {
        String msg = SqlScript.unescapeH2(first);
        int m = msg.indexOf("[*]");
        String near = "";
        if (m >= 0) {
            int end = msg.indexOf('"', m + 3);
            near = msg.substring(m + 3, end < 0 ? msg.length() : end).strip();
            int nl = near.indexOf('\n');
            if (nl >= 0) {
                near = near.substring(0, nl);
            }
            if (near.length() > 40) {
                near = near.substring(0, 40) + "…";
            }
        }
        String expected = "";
        int e = msg.indexOf("expected \"");
        if (e >= 0) {
            int end = msg.lastIndexOf('"');
            if (end > e + 10) {
                expected = msg.substring(e + 10, end);
                String[] parts = expected.split(",\\s*");
                if (parts.length > 6) {
                    expected = String.join(", ", java.util.Arrays.copyOf(parts, 6)) + "…";
                }
            }
        }
        return "Erro de sintaxe" + (near.isEmpty() ? (m >= 0 ? " no fim da instrução" : "") : " perto de \"" + near + "\"")
                + (expected.isEmpty() ? "" : " (esperado: " + expected + ")") + ".";
    }

    private static String duplicate(String detail) {
        if (detail != null) {
            Matcher m = UNIQUE.matcher(detail);
            if (m.find()) {
                String cols = m.group(3).replaceAll("(?i)\\s+NULLS (FIRST|LAST)", "").replace("\"", "");
                String value = m.group(4) == null ? null : unCast(m.group(4));
                boolean pk = m.group(1).toUpperCase().startsWith("PRIMARY_KEY");
                return "Valor duplicado em " + cols + ": já existe outro registro"
                        + (value != null && !value.isBlank() ? " com " + value : " com esse valor")
                        + (pk ? " (chave primária)." : " (índice único " + m.group(1) + ").");
            }
        }
        return "Valor duplicado: já existe outro registro com esse valor em uma chave única.";
    }

    private static String parentMissing(String detail) {
        if (detail != null) {
            Matcher m = FK.matcher(detail);
            if (m.find()) {
                String value = m.group(5) == null ? "informado" : unCast(m.group(5));
                return "Referência inválida em " + m.group(2) + ": o valor " + value + " não existe em "
                        + m.group(3) + "." + m.group(4) + ".";
            }
        }
        return "Referência inválida: o valor informado não existe na tabela relacionada.";
    }

    private static String childExists(String detail) {
        if (detail != null) {
            Matcher m = FK.matcher(detail);
            if (m.find()) {
                return "Não é possível excluir ou alterar: este registro é usado na tabela " + m.group(1)
                        + " (coluna " + m.group(2) + "). Remova ou altere esses registros antes.";
            }
        }
        return "Não é possível excluir ou alterar: existem registros em outra tabela que usam este registro.";
    }

    private static String unCast(String v) {
        String s = v.trim();
        Matcher m = CAST.matcher(s);
        return m.matches() ? m.group(1).trim() : s;
    }

    private static String firstQuoted(String s) {
        Matcher m = QUOTED.matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
