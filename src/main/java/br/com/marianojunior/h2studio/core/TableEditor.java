package br.com.marianojunior.h2studio.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Grava alterações de uma tabela (UPDATE / INSERT / DELETE) em uma única transação.
 * Se qualquer comando falhar, nada é gravado (rollback).
 * <p>
 * Cada linha é identificada pela chave primária ou, quando a tabela não tem
 * chave primária, pelo {@code _ROWID_} interno do H2.
 */
public final class TableEditor {

    /** Valor de uma coluna não informada no INSERT: o banco usa o padrão / identidade. */
    public static final Object DEFAULT = new Object() {
        @Override
        public String toString() {
            return "DEFAULT";
        }
    };

    private TableEditor() {
    }

    /**
     * Identifica uma linha.
     *
     * @param rowId  true = usa {@code _ROWID_} (tabela sem chave primária)
     * @param values coluna → valor original (ou "_ROWID_" → id)
     */
    public record Key(boolean rowId, Map<String, Object> values) {

        public static Key ofRowId(Object id) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("_ROWID_", id);
            return new Key(true, m);
        }

        public static Key ofColumns(Map<String, Object> values) {
            return new Key(false, new LinkedHashMap<>(values));
        }

        String where(List<Object> params) {
            return where(params, SqlUtil::quote);
        }

        String where(List<Object> params, java.util.function.UnaryOperator<String> quote) {
            StringJoiner j = new StringJoiner(" AND ");
            for (var e : values.entrySet()) {
                if (rowId) {
                    j.add("_ROWID_ = ?");
                    params.add(e.getValue());
                } else if (e.getValue() == null) {
                    j.add(quote.apply(e.getKey()) + " IS NULL");
                } else {
                    j.add(quote.apply(e.getKey()) + " = ?");
                    params.add(e.getValue());
                }
            }
            return j.toString();
        }
    }

    /** Uma alteração pendente. {@code ref} é um identificador livre (ex.: a linha na grade). */
    public sealed interface Change permits Update, Insert, Delete {
        Object ref();
    }

    public record Update(Key key, Map<String, Object> values, Object ref) implements Change {
    }

    public record Insert(Map<String, Object> values, Object ref) implements Change {
    }

    public record Delete(Key key, Object ref) implements Change {
    }

    public record Result(int updated, int inserted, int deleted) {
        public String summary() {
            List<String> p = new ArrayList<>();
            if (updated > 0) {
                p.add(updated + (updated == 1 ? " alterada" : " alteradas"));
            }
            if (inserted > 0) {
                p.add(inserted + (inserted == 1 ? " inserida" : " inseridas"));
            }
            if (deleted > 0) {
                p.add(deleted + (deleted == 1 ? " excluída" : " excluídas"));
            }
            return p.isEmpty() ? "nada a salvar" : String.join(", ", p);
        }
    }

    /** Falha ao salvar: tudo foi desfeito. {@code ref} aponta a alteração que falhou. */
    public static class SaveException extends Exception {
        private final transient Object ref;

        public SaveException(String message, Object ref, Throwable cause) {
            super(message, cause);
            this.ref = ref;
        }

        public Object ref() {
            return ref;
        }
    }

    /**
     * Aplica as alterações em ordem: exclusões, alterações, inclusões
     * (assim uma linha excluída libera um valor único para outra).
     */
    public static Result apply(Connection c, String schema, String table, List<Change> changes) throws SaveException {
        return apply(DbType.H2, c, schema, table, changes);
    }

    /**
     * Mesma coisa no dialeto de outro banco: aspas, INSERT sem colunas, conversão de valores
     * (datas e booleanos no SQLite) e mensagens de erro do servidor traduzidas.
     */
    public static Result apply(DbType type, Connection c, String schema, String table, List<Change> changes)
            throws SaveException {
        java.util.function.UnaryOperator<String> q = type == DbType.H2 ? SqlUtil::quote : type::quote;
        String target = type == DbType.H2 ? SqlUtil.quote(schema) + "." + SqlUtil.quote(table) : type.qualified(schema, table);
        List<Change> ordered = new ArrayList<>();
        changes.stream().filter(x -> x instanceof Delete).forEach(ordered::add);
        changes.stream().filter(x -> x instanceof Update).forEach(ordered::add);
        changes.stream().filter(x -> x instanceof Insert).forEach(ordered::add);

        boolean auto;
        try {
            auto = c.getAutoCommit();
            c.setAutoCommit(false);
        } catch (SQLException e) {
            throw new SaveException(SqlErrors.friendly(e, type), null, e);
        }
        int u = 0;
        int i = 0;
        int d = 0;
        Change current = null;
        try {
            for (Change ch : ordered) {
                current = ch;
                switch (ch) {
                    case Delete del -> {
                        List<Object> params = new ArrayList<>();
                        String sql = "DELETE FROM " + target + " WHERE " + del.key().where(params, q);
                        expectOne(execute(type, c, sql, params), ch);
                        d++;
                    }
                    case Update up -> {
                        if (up.values().isEmpty()) {
                            continue;
                        }
                        List<Object> params = new ArrayList<>();
                        StringJoiner set = new StringJoiner(", ");
                        for (var e : up.values().entrySet()) {
                            if (e.getValue() == DEFAULT) {
                                if (type == DbType.SQLITE) {
                                    throw new SaveException("O SQLite não aceita voltar uma coluna ao valor padrão "
                                            + "(DEFAULT) num UPDATE: digite o valor de " + e.getKey() + ".", ch.ref(), null);
                                }
                                set.add(q.apply(e.getKey()) + " = DEFAULT");
                            } else {
                                set.add(q.apply(e.getKey()) + " = ?");
                                params.add(e.getValue());
                            }
                        }
                        String sql = "UPDATE " + target + " SET " + set + " WHERE " + up.key().where(params, q);
                        expectOne(execute(type, c, sql, params), ch);
                        u++;
                    }
                    case Insert ins -> {
                        List<Object> params = new ArrayList<>();
                        StringJoiner cols = new StringJoiner(", ");
                        StringJoiner vals = new StringJoiner(", ");
                        for (var e : ins.values().entrySet()) {
                            if (e.getValue() == DEFAULT) {
                                continue; // omitida: o banco preenche
                            }
                            cols.add(q.apply(e.getKey()));
                            vals.add("?");
                            params.add(e.getValue());
                        }
                        String sql = cols.length() == 0
                                ? (type == DbType.MYSQL || type == DbType.MARIADB
                                        ? "INSERT INTO " + target + " () VALUES ()"
                                        : "INSERT INTO " + target + " DEFAULT VALUES")
                                : "INSERT INTO " + target + " (" + cols + ") VALUES (" + vals + ")";
                        execute(type, c, sql, params);
                        i++;
                    }
                }
            }
            c.commit();
            return new Result(u, i, d);
        } catch (SaveException e) {
            rollback(c);
            throw e;
        } catch (SQLException e) {
            rollback(c);
            throw new SaveException(SqlErrors.friendly(e, type), current == null ? null : current.ref(), e);
        } finally {
            try {
                c.setAutoCommit(auto);
            } catch (SQLException ignored) {
                // nada a fazer
            }
        }
    }

    private static int execute(DbType type, Connection c, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (type != DbType.H2) {
                // registro bloqueado por outro usuário: desiste em vez de travar a tela
                try {
                    ps.setQueryTimeout(30);
                } catch (SQLException ignored) {
                    // driver sem suporte
                }
            }
            for (int k = 0; k < params.size(); k++) {
                bind(type, ps, k + 1, params.get(k));
            }
            return ps.executeUpdate();
        }
    }

    /**
     * O SQLite não tem tipos de data nem booleano: o driver gravaria datas como número de
     * milissegundos. Grava como texto ISO (o formato que os sistemas costumam usar) e 1/0.
     */
    private static final java.time.format.DateTimeFormatter SQLITE_DATETIME =
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    static void bind(DbType type, PreparedStatement ps, int idx, Object v) throws SQLException {
        if (type == DbType.SQLITE) {
            if (v instanceof java.time.LocalDate || v instanceof java.time.LocalTime) {
                ps.setString(idx, v.toString());
                return;
            }
            if (v instanceof java.time.LocalDateTime ldt) {
                ps.setString(idx, SQLITE_DATETIME.format(ldt)); // mesmo formato do datetime() do SQLite
                return;
            }
            if (v instanceof java.time.OffsetDateTime odt) {
                ps.setString(idx, SQLITE_DATETIME.format(odt.toLocalDateTime()));
                return;
            }
            if (v instanceof Boolean b) {
                ps.setInt(idx, b ? 1 : 0);
                return;
            }
        }
        bind(ps, idx, v);
    }

    static void bind(PreparedStatement ps, int idx, Object v) throws SQLException {
        if (v instanceof byte[] b) {
            ps.setBytes(idx, b);
        } else {
            ps.setObject(idx, v);
        }
    }

    private static void expectOne(int count, Change ch) throws SaveException {
        if (count == 0) {
            throw new SaveException("O registro não foi encontrado: pode ter sido alterado ou excluído por outro "
                    + "programa. Nada foi gravado; atualize a tela (F5) e tente de novo.", ch.ref(), null);
        }
        if (count > 1) {
            throw new SaveException("A alteração atingiria " + count + " registros de uma vez (a chave da linha não é "
                    + "única). Nada foi gravado.", ch.ref(), null);
        }
    }

    private static void rollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // conexão já com problema: o erro original é mais útil
        }
    }

    /** Lê o valor completo de uma coluna (texto longo ou binário) de uma linha. */
    public static Object readFull(Connection c, String schema, String table, Key key, String column) throws Exception {
        return readFull(DbType.H2, c, schema, table, key, column);
    }

    /** Lê o valor completo de uma coluna usando as aspas do banco informado. */
    public static Object readFull(DbType type, Connection c, String schema, String table, Key key, String column)
            throws Exception {
        if (key.values().isEmpty()) {
            throw new SQLException("A tabela não tem chave primária: não dá para localizar a linha.");
        }
        java.util.function.UnaryOperator<String> q = type == DbType.H2 ? SqlUtil::quote : type::quote;
        String from = type == DbType.H2 ? SqlUtil.quote(schema) + "." + SqlUtil.quote(table) : type.qualified(schema, table);
        List<Object> params = new ArrayList<>();
        String sql = "SELECT " + q.apply(column) + " FROM " + from + " WHERE " + key.where(params, q);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int k = 0; k < params.size(); k++) {
                bind(ps, k + 1, params.get(k));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Registro não encontrado.");
                }
                Object v = rs.getObject(1);
                if (v instanceof Clob clob) {
                    StringWriter w = new StringWriter();
                    try (Reader r = clob.getCharacterStream()) {
                        r.transferTo(w);
                    }
                    return w.toString();
                }
                if (v instanceof Blob blob) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = blob.getBinaryStream()) {
                        in.transferTo(out);
                    }
                    return out.toByteArray();
                }
                return v;
            }
        }
    }
}
