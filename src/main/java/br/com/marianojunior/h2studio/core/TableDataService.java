package br.com.marianojunior.h2studio.core;

import java.io.Reader;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Leitura paginada dos dados de uma tabela ou view.
 * <p>
 * A partir da versão 1.2 monta o SQL no dialeto de cada banco: aspas, paginação
 * (LIMIT/OFFSET, FIRST/SKIP no Firebird, OFFSET/FETCH ou TOP no SQL Server) e a busca
 * rápida sem diferenciar maiúsculas.
 */
public final class TableDataService {

    /** Tamanho máximo de texto longo (CLOB) carregado para exibição na grade. */
    public static final int CLOB_PREVIEW = 2000;

    private TableDataService() {
    }

    public record Column(String name, int jdbcType, String typeName) {
    }

    /** Conteúdo binário resumido (não carrega o BLOB inteiro na grade). */
    public record BinaryValue(long size, byte[] head) {
    }

    /** Texto longo resumido. */
    public record LongText(String preview, long length) {
    }

    /**
     * @param where       condição SQL digitada pelo usuário (sem a palavra WHERE), pode ser vazia
     * @param quickSearch texto da busca rápida (procura em todas as colunas pesquisáveis)
     * @param searchColumns colunas usadas pela busca rápida
     * @param orderBy     coluna de ordenação (null = ordem natural)
     */
    public record PageRequest(String schema, String table, String where, String quickSearch,
                              List<Meta.ColumnInfo> searchColumns, String orderBy, boolean ascending,
                              long offset, int limit, boolean withRowId, DbType type) {

        public PageRequest {
            type = type == null ? DbType.H2 : type;
            withRowId = withRowId && type == DbType.H2; // _ROWID_ só existe no H2
        }

        public PageRequest(String schema, String table, String where, String quickSearch,
                           List<Meta.ColumnInfo> searchColumns, String orderBy, boolean ascending,
                           long offset, int limit, boolean withRowId) {
            this(schema, table, where, quickSearch, searchColumns, orderBy, ascending, offset, limit, withRowId, DbType.H2);
        }

        public PageRequest(String schema, String table, String where, String quickSearch,
                           List<Meta.ColumnInfo> searchColumns, String orderBy, boolean ascending,
                           long offset, int limit) {
            this(schema, table, where, quickSearch, searchColumns, orderBy, ascending, offset, limit, false);
        }

        /** O mesmo pedido para outro tipo de banco. */
        public PageRequest withType(DbType t) {
            return new PageRequest(schema, table, where, quickSearch, searchColumns, orderBy, ascending, offset, limit,
                    withRowId, t);
        }

        String from() {
            return type == DbType.H2 ? SqlUtil.quote(schema) + "." + SqlUtil.quote(table) : type.qualified(schema, table);
        }

        String quote(String identifier) {
            return type == DbType.H2 ? SqlUtil.quote(identifier) : type.quote(identifier);
        }

        String orderClause() {
            return orderBy == null ? "" : " ORDER BY " + quote(orderBy) + (ascending ? " ASC" : " DESC");
        }
    }

    /**
     * @param rowIds identificador interno (_ROWID_) de cada linha, quando pedido; usado para
     *               editar tabelas sem chave primária
     */
    public record PageResult(List<Column> columns, List<Object[]> rows, List<Object> rowIds, long total,
                             long elapsedMs, String sql) {
    }

    /** Alias da coluna _ROWID_ (entre aspas: funciona também com DATABASE_TO_LOWER). */
    static final String ROWID_ALIAS = "__h2studio_rowid";

    /** Consulta pronta (SQL + parâmetros), usada para exportar com os mesmos filtros da grade. */
    public record Query(String sql, List<Object> params) {
    }

    /** SELECT * com os filtros e a ordenação do pedido, sem paginação. */
    public static Query exportQuery(PageRequest r) {
        List<Object> params = new ArrayList<>();
        String where = whereClause(r, params);
        return new Query("SELECT * FROM " + r.from() + where + r.orderClause(), params);
    }

    private static String whereClause(PageRequest r, List<Object> params) {
        List<String> conds = new ArrayList<>();
        if (r.where() != null && !r.where().isBlank()) {
            conds.add("(" + r.where().trim() + ")");
        }
        if (r.quickSearch() != null && !r.quickSearch().isBlank() && r.searchColumns() != null) {
            List<String> ors = new ArrayList<>();
            String text = r.quickSearch().trim();
            for (Meta.ColumnInfo col : r.searchColumns()) {
                if (SqlUtil.isSearchable(col.jdbcType())) {
                    ors.add(searchCondition(r.type(), r.quote(col.name()), col.jdbcType()));
                    params.add(r.type().isFirebird() ? text : likePattern(r.type(), text));
                }
            }
            if (!ors.isEmpty()) {
                conds.add("(" + String.join(" OR ", ors) + ")");
            }
        }
        return conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
    }

    /** Texto da busca rápida dentro de um LIKE, com os curingas escapados. */
    public static String likePattern(DbType type, String text) {
        if (type == DbType.H2) {
            return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
        // '!' como escape: o '\\' tem significados diferentes no MySQL e no SQL Server
        String t = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        if (type == DbType.SQLSERVER) {
            t = t.replace("[", "![");
        }
        return "%" + t + "%";
    }

    /**
     * Condição da busca rápida para uma coluna, sem diferenciar maiúsculas.
     * Cada banco converte para texto de um jeito; no Firebird o CONTAINING já faz tudo.
     */
    public static String searchCondition(DbType type, String col, int jdbcType) {
        boolean text = SqlUtil.isText(jdbcType);
        return switch (type) {
            case H2 -> "CAST(" + col + " AS VARCHAR) ILIKE ? ESCAPE '\\'";
            case POSTGRESQL -> "CAST(" + col + " AS TEXT) ILIKE ? ESCAPE '!'";
            case MYSQL, MARIADB -> "CAST(" + col + " AS CHAR) LIKE ? ESCAPE '!'";
            case SQLITE -> "CAST(" + col + " AS TEXT) LIKE ? ESCAPE '!'";
            case SQLSERVER -> (text ? col : "CAST(" + col + " AS NVARCHAR(100))") + " LIKE ? ESCAPE '!'";
            case FIREBIRD, FIREBIRD_25 -> col + " CONTAINING ?";
        };
    }

    /** SELECT paginado no dialeto do banco. */
    public static String pagedSelect(PageRequest r, String where, boolean legacySqlServer) {
        String from = " FROM " + r.from();
        String order = r.orderClause();
        return switch (r.type()) {
            case H2 -> (r.withRowId() ? "SELECT _ROWID_ AS " + SqlUtil.quote(ROWID_ALIAS) + ", *" : "SELECT *")
                    + from + where + order + " LIMIT " + r.limit() + " OFFSET " + r.offset();
            case FIREBIRD, FIREBIRD_25 -> "SELECT FIRST " + r.limit() + " SKIP " + r.offset() + " *" + from + where + order;
            // SQL Server 2008 e anteriores não têm OFFSET/FETCH: lê as primeiras linhas e pula na mão
            case SQLSERVER -> legacySqlServer
                    ? "SELECT TOP " + (r.offset() + r.limit()) + " *" + from + where + order
                    : "SELECT *" + from + where + (order.isEmpty() ? " ORDER BY (SELECT NULL)" : order)
                    + " OFFSET " + r.offset() + " ROWS FETCH NEXT " + r.limit() + " ROWS ONLY";
            default -> "SELECT *" + from + where + order + " LIMIT " + r.limit() + " OFFSET " + r.offset();
        };
    }

    public static PageResult fetch(Connection c, PageRequest r) throws SQLException {
        long t0 = System.currentTimeMillis();
        String from = " FROM " + r.from();
        List<Object> params = new ArrayList<>();
        String where = whereClause(r, params);

        long total;
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*)" + from + where)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
            }
        }

        boolean legacySqlServer = r.type() == DbType.SQLSERVER && c.getMetaData().getDatabaseMajorVersion() < 11;
        String sql = pagedSelect(r, where, legacySqlServer);
        int skip = r.withRowId() ? 1 : 0;
        long toSkip = legacySqlServer ? r.offset() : 0;

        List<Column> cols = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        List<Object> rowIds = r.withRowId() ? new ArrayList<>() : null;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                for (int i = 1 + skip; i <= n; i++) {
                    cols.add(new Column(md.getColumnLabel(i), md.getColumnType(i), md.getColumnTypeName(i)));
                }
                while (rs.next()) {
                    if (toSkip > 0) {
                        toSkip--;
                        continue;
                    }
                    Object[] row = new Object[n - skip];
                    for (int i = 1 + skip; i <= n; i++) {
                        row[i - 1 - skip] = readValue(rs, i);
                    }
                    rows.add(row);
                    if (rowIds != null) {
                        rowIds.add(rs.getObject(1));
                    }
                }
            }
        }
        return new PageResult(cols, rows, rowIds, total, System.currentTimeMillis() - t0, sql);
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    /** Converte LOBs e arrays enquanto o ResultSet ainda está aberto. */
    static Object readValue(ResultSet rs, int i) throws SQLException {
        Object v;
        try {
            v = rs.getObject(i);
        } catch (SQLException e) {
            // ex.: data "0000-00-00" do MySQL, que o driver não converte: mostra o texto gravado
            String s = rs.getString(i);
            return s == null ? null : s;
        }
        if (v == null) {
            return null;
        }
        try {
            if (v instanceof Clob clob) {
                long len = clob.length();
                char[] buf = new char[(int) Math.min(len, CLOB_PREVIEW)];
                try (Reader rd = clob.getCharacterStream()) {
                    int read = rd.read(buf);
                    return new LongText(read <= 0 ? "" : new String(buf, 0, read), len);
                }
            }
            if (v instanceof Blob blob) {
                long len = blob.length();
                return new BinaryValue(len, blob.getBytes(1, (int) Math.min(len, 16)));
            }
            if (v instanceof byte[] b) {
                byte[] head = new byte[Math.min(b.length, 16)];
                System.arraycopy(b, 0, head, 0, head.length);
                return new BinaryValue(b.length, head);
            }
            if (v instanceof Array arr) {
                Object[] items = (Object[]) arr.getArray();
                return java.util.Arrays.deepToString(items);
            }
        } catch (java.io.IOException e) {
            return "(erro ao ler: " + e.getMessage() + ")";
        }
        return v;
    }
}
