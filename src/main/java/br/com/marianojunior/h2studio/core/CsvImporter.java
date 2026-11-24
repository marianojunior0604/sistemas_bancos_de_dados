package br.com.marianojunior.h2studio.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Importa linhas de um CSV para uma tabela existente.
 * <p>
 * Primeiro tudo é validado (tipos, tamanhos, obrigatórios) sem tocar no banco; só sem
 * erros a gravação acontece, numa única transação: ou entram todas as linhas, ou nenhuma.
 */
public final class CsvImporter {

    public enum Mode {
        APPEND("Adicionar às linhas existentes"),
        REPLACE("Substituir: apagar as linhas atuais e importar");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Erro de uma célula do CSV. */
    public record RowError(int line, String column, String value, String message) {
        @Override
        public String toString() {
            return "Linha " + line + (column == null ? "" : ", " + column) + ": " + message
                    + (value == null || value.isEmpty() ? "" : " (valor: \"" + (value.length() > 40 ? value.substring(0, 40) + "…" : value) + "\")");
        }
    }

    /** Linhas convertidas prontas para gravar. {@code omitted} marca colunas vazias com valor automático. */
    public record Prepared(List<Object[]> rows, List<BitSet> omitted, List<Integer> lines, List<RowError> errors) {
    }

    public record Result(int inserted, int deleted, boolean identityAdjusted) {
    }

    public interface Progress {
        void rows(int done, int total);

        boolean cancelled();
    }

    public static class CancelledException extends Exception {
        public CancelledException() {
            super("Importação cancelada: nada foi gravado.");
        }
    }

    private static final int MAX_ERRORS = 200;

    private CsvImporter() {
    }

    /** Nome simplificado para comparar cabeçalhos: sem acentos, minúsculo, só letras e números. */
    public static String simplify(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Liga cada coluna do CSV a uma coluna da tabela pelo nome (sem diferenciar maiúsculas,
     * acentos, espaços e _). Sem cabeçalho, liga pela posição.
     *
     * @return para cada coluna do CSV, a coluna da tabela (ou null = ignorar)
     */
    public static List<Meta.ColumnInfo> autoMap(String[] header, List<Meta.ColumnInfo> cols, boolean hasHeader) {
        List<Meta.ColumnInfo> out = new ArrayList<>();
        Map<String, Meta.ColumnInfo> byName = new HashMap<>();
        for (Meta.ColumnInfo c : cols) {
            byName.put(simplify(c.name()), c);
        }
        for (int i = 0; i < header.length; i++) {
            Meta.ColumnInfo c;
            if (hasHeader) {
                String h = simplify(header[i]);
                c = byName.get(h);
                if (c == null && h.length() >= 3) {
                    // "Data de Nascimento" → NASCIMENTO, "Cód. Cliente" → CLIENTE_ID...: só se apenas uma coluna combinar
                    List<Meta.ColumnInfo> near = cols.stream().filter(x -> {
                        String n = simplify(x.name());
                        return n.length() >= 3 && (h.contains(n) || n.contains(h));
                    }).toList();
                    c = near.size() == 1 ? near.get(0) : null;
                }
            } else {
                c = i < cols.size() ? cols.get(i) : null;
            }
            out.add(c != null && !c.generated() && !out.contains(c) ? c : null);
        }
        return out;
    }

    /**
     * Converte e valida todas as linhas.
     *
     * @param records  registros do CSV (sem o cabeçalho)
     * @param lines    número da linha no arquivo de cada registro
     * @param mapping  coluna da tabela para cada coluna do CSV (null = ignorar)
     * @param allCols  todas as colunas da tabela (para checar obrigatórias não mapeadas)
     */
    public static Prepared prepare(List<String[]> records, int[] lines, List<Meta.ColumnInfo> mapping,
                                   List<Meta.ColumnInfo> allCols) {
        List<RowError> errors = new ArrayList<>();
        List<Meta.ColumnInfo> targets = mapping.stream().filter(java.util.Objects::nonNull).toList();
        // obrigatórias sem valor automático que ficaram de fora do mapeamento
        for (Meta.ColumnInfo c : allCols) {
            if (!c.nullable() && !c.hasAutomaticValue() && !targets.contains(c)) {
                errors.add(new RowError(0, c.name(), null,
                        "coluna obrigatória (NOT NULL) sem coluna do CSV ligada a ela"));
            }
        }
        if (targets.isEmpty()) {
            errors.add(new RowError(0, null, null, "nenhuma coluna do CSV está ligada a uma coluna da tabela"));
        }
        List<Object[]> rows = new ArrayList<>();
        List<BitSet> omitted = new ArrayList<>();
        List<Integer> lineNos = new ArrayList<>();
        if (!errors.isEmpty()) {
            return new Prepared(rows, omitted, lineNos, errors);
        }
        for (int r = 0; r < records.size(); r++) {
            String[] rec = records.get(r);
            int line = lines[r];
            Object[] vals = new Object[targets.size()];
            BitSet om = new BitSet();
            int t = 0;
            for (int i = 0; i < mapping.size(); i++) {
                Meta.ColumnInfo c = mapping.get(i);
                if (c == null) {
                    continue;
                }
                String text = i < rec.length ? rec[i] : "";
                try {
                    vals[t] = convert(text, c, om, t);
                } catch (ValueParser.InvalidValueException e) {
                    if (errors.size() < MAX_ERRORS) {
                        errors.add(new RowError(line, c.name(), text, e.getMessage()));
                    }
                }
                t++;
            }
            if (rec.length > mapping.size() && errors.size() < MAX_ERRORS
                    && java.util.Arrays.stream(rec, mapping.size(), rec.length).anyMatch(x -> !x.isBlank())) {
                errors.add(new RowError(line, null, null, "a linha tem " + rec.length + " colunas; o cabeçalho tem "
                        + mapping.size() + " (verifique o separador ou aspas sem fechar)"));
            }
            rows.add(vals);
            omitted.add(om);
            lineNos.add(line);
        }
        return new Prepared(rows, omitted, lineNos, errors);
    }

    private static Object convert(String text, Meta.ColumnInfo c, BitSet om, int idx)
            throws ValueParser.InvalidValueException {
        boolean empty = text == null || text.isEmpty() || (!ValueParser.isTextual(c.jdbcType()) && text.isBlank());
        if (empty) {
            if (c.hasAutomaticValue()) {
                om.set(idx); // deixa o banco preencher (identidade, padrão)
                return null;
            }
            if (c.nullable()) {
                return null;
            }
            throw new ValueParser.InvalidValueException("valor obrigatório (NOT NULL) vazio");
        }
        if (ValueParser.isBinary(c.jdbcType())) {
            return ValueParser.parse(text, new Meta.ColumnInfo(c.position(), c.name(), c.typeName(),
                    java.sql.Types.VARBINARY, c.size(), c.scale(), c.nullable(), c.defaultValue(), c.autoIncrement(),
                    c.generated(), c.primaryKey(), c.fkSchema(), c.fkTable(), c.fkColumn(), c.remarks()));
        }
        return ValueParser.parse(text, c);
    }

    /** Grava as linhas preparadas numa transação. */
    public static Result run(Connection c, String schema, String table, List<Meta.ColumnInfo> mapping, Prepared p,
                             Mode mode, Progress progress) throws SQLException, CancelledException {
        List<Meta.ColumnInfo> targets = mapping.stream().filter(java.util.Objects::nonNull).toList();
        String t = SqlUtil.quote(schema) + "." + SqlUtil.quote(table);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        int deleted = 0;
        int done = 0;
        Map<BitSet, PreparedStatement> stmts = new HashMap<>();
        try {
            if (mode == Mode.REPLACE) {
                try (Statement st = c.createStatement()) {
                    deleted = st.executeUpdate("DELETE FROM " + t);
                }
            }
            for (int r = 0; r < p.rows().size(); r++) {
                Object[] vals = p.rows().get(r);
                BitSet om = p.omitted().get(r);
                PreparedStatement ps = stmts.get(om);
                if (ps == null) {
                    StringJoiner cols = new StringJoiner(", ");
                    StringJoiner qs = new StringJoiner(", ");
                    for (int i = 0; i < targets.size(); i++) {
                        if (!om.get(i)) {
                            cols.add(SqlUtil.quote(targets.get(i).name()));
                            qs.add("?");
                        }
                    }
                    String sql = cols.length() == 0 ? "INSERT INTO " + t + " DEFAULT VALUES"
                            : "INSERT INTO " + t + " (" + cols + ") VALUES (" + qs + ")";
                    ps = c.prepareStatement(sql);
                    stmts.put((BitSet) om.clone(), ps);
                }
                int k = 1;
                for (int i = 0; i < targets.size(); i++) {
                    if (!om.get(i)) {
                        TableEditor.bind(ps, k++, vals[i]);
                    }
                }
                try {
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new SQLException("Linha " + p.lines().get(r) + " do arquivo: " + SqlErrors.friendly(e),
                            e.getSQLState(), e.getErrorCode(), e);
                }
                done++;
                if (done % 500 == 0) {
                    progress.rows(done, p.rows().size());
                    if (progress.cancelled()) {
                        throw new CancelledException();
                    }
                }
            }
            c.commit();
        } catch (SQLException | CancelledException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // o erro original é mais útil
            }
            throw e;
        } finally {
            for (PreparedStatement ps : stmts.values()) {
                try {
                    ps.close();
                } catch (SQLException ignored) {
                    // nada a fazer
                }
            }
            c.setAutoCommit(auto);
        }
        progress.rows(done, p.rows().size());
        // valores explícitos numa coluna de identidade não avançam o contador no H2:
        // ajusta para o próximo INSERT do sistema não repetir um ID importado
        boolean adjusted = false;
        for (int i = 0; i < targets.size(); i++) {
            Meta.ColumnInfo col = targets.get(i);
            final int idx = i;
            boolean explicit = col.autoIncrement() && p.omitted().stream().anyMatch(b -> !b.get(idx));
            if (explicit && SqlUtil.isNumeric(col.jdbcType())) {
                long next;
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(" + SqlUtil.quote(col.name()) + "), 0) + 1 FROM " + t)) {
                    rs.next();
                    next = rs.getLong(1);
                }
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE " + t + " ALTER COLUMN " + SqlUtil.quote(col.name()) + " RESTART WITH " + next);
                    adjusted = true;
                } catch (SQLException ignored) {
                    // versões sem suporte: o sistema ainda funciona, só pode tentar um ID repetido
                }
            }
        }
        return new Result(done, deleted, adjusted);
    }
}
