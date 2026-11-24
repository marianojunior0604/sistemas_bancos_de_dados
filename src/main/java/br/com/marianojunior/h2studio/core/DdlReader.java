package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.Meta.ColumnInfo;
import br.com.marianojunior.h2studio.core.Meta.ForeignKeyInfo;
import br.com.marianojunior.h2studio.core.Meta.IndexInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * DDL (CREATE TABLE / CREATE VIEW) de uma tabela dos outros bancos.
 * <p>
 * Usa o comando do próprio banco quando existe (SHOW CREATE TABLE no MySQL, sqlite_master
 * no SQLite, definição da view no PostgreSQL/Firebird/SQL Server). Nos outros casos monta
 * um CREATE TABLE aproximado a partir dos metadados JDBC, avisando no comentário.
 */
public final class DdlReader {

    private DdlReader() {
    }

    public static String ddl(DbType type, Connection c, Meta.TableInfo t) throws SQLException {
        return switch (type) {
            case H2 -> MetadataService.ddl(c, t.schema(), t.name());
            case MYSQL, MARIADB -> mysql(type, c, t);
            case SQLITE -> sqlite(c, t);
            case POSTGRESQL -> t.view() ? view(type, t, single(c,
                    "SELECT pg_get_viewdef(CAST(? AS regclass), true)", type.qualified(t.schema(), t.name())))
                    : approximate(type, c, t) + postgresIndexes(c, t);
            case FIREBIRD, FIREBIRD_25 -> t.view() ? view(type, t, single(c,
                    "SELECT RDB$VIEW_SOURCE FROM RDB$RELATIONS WHERE RDB$RELATION_NAME = ?", t.name()))
                    : approximate(type, c, t);
            case SQLSERVER -> t.view() ? sqlServerView(c, t) : approximate(type, c, t);
        };
    }

    private static String mysql(DbType type, Connection c, Meta.TableInfo t) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW CREATE TABLE " + type.qualified(t.schema(), t.name()))) {
            return rs.next() ? rs.getString(2) + ";" : "";
        }
    }

    private static String sqlite(Connection c, Meta.TableInfo t) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE tbl_name = ? AND sql IS NOT NULL "
                + "ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 0 ELSE 1 END, name")) {
            ps.setString(1, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1).trim() + ";");
                }
            }
        }
        return String.join("\n\n", out);
    }

    private static String sqlServerView(Connection c, Meta.TableInfo t) throws SQLException {
        String def = single(c, "SELECT OBJECT_DEFINITION(OBJECT_ID(?))", DbType.SQLSERVER.qualified(t.schema(), t.name()));
        return def == null ? "-- definição da view não disponível (sem permissão VIEW DEFINITION?)" : def.trim();
    }

    private static String view(DbType type, Meta.TableInfo t, String body) {
        if (body == null) {
            return "-- definição da view não disponível";
        }
        String b = body.trim();
        if (b.endsWith(";")) {
            b = b.substring(0, b.length() - 1);
        }
        return "CREATE VIEW " + type.qualified(t.schema(), t.name()) + " AS\n" + b + ";";
    }

    private static String single(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Índices do PostgreSQL que não pertencem a restrições (PK/UNIQUE já aparecem no CREATE TABLE). */
    private static String postgresIndexes(Connection c, Meta.TableInfo t) {
        String sql = "SELECT i.indexdef FROM pg_indexes i "
                + "WHERE i.schemaname = ? AND i.tablename = ? AND NOT EXISTS ("
                + " SELECT 1 FROM pg_constraint k WHERE k.conindid = "
                + "  CAST(quote_ident(i.schemaname) || '.' || quote_ident(i.indexname) AS regclass)) "
                + "ORDER BY i.indexname";
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.schema());
            ps.setString(2, t.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("\n\n").append(rs.getString(1)).append(';');
                }
            }
        } catch (SQLException e) {
            return "";
        }
        return sb.toString();
    }

    /** CREATE TABLE montado com os metadados JDBC (serve para consulta, não para recriar a tabela). */
    static String approximate(DbType type, Connection c, Meta.TableInfo t) throws SQLException {
        List<ColumnInfo> cols = MetadataService.columns(c, t.schema(), t.name());
        String name = type.qualified(t.schema(), t.name());
        StringJoiner j = new StringJoiner(",\n    ", "CREATE TABLE " + name + " (\n    ", "\n);");
        List<String> pk = new ArrayList<>();
        for (ColumnInfo col : cols) {
            StringBuilder sb = new StringBuilder(type.quote(col.name())).append(' ').append(col.typeDisplay());
            String tn = col.typeName() == null ? "" : col.typeName().toLowerCase(java.util.Locale.ROOT);
            boolean serial = type == DbType.POSTGRESQL && tn.endsWith("serial"); // o default nextval() já está no tipo
            if (!serial && col.defaultValue() != null && !col.defaultValue().isBlank()) {
                sb.append(" DEFAULT ").append(col.defaultValue().trim());
            }
            if (!col.nullable()) {
                sb.append(" NOT NULL");
            }
            j.add(sb);
            if (col.primaryKey()) {
                pk.add(type.quote(col.name()));
            }
        }
        if (!pk.isEmpty()) {
            j.add("PRIMARY KEY (" + String.join(", ", pk) + ")");
        }
        for (ForeignKeyInfo fk : MetadataService.foreignKeys(c, t.schema(), t.name())) {
            if (fk.incoming()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (!fk.name().startsWith("(")) {
                sb.append("CONSTRAINT ").append(type.quote(fk.name())).append(' ');
            }
            sb.append("FOREIGN KEY (").append(quoteList(type, fk.columns())).append(") REFERENCES ")
                    .append(type.qualified(fk.refSchema(), fk.refTable()))
                    .append(" (").append(quoteList(type, fk.refColumns())).append(')');
            if (!"NO ACTION".equals(fk.onDelete())) {
                sb.append(" ON DELETE ").append(fk.onDelete());
            }
            if (!"NO ACTION".equals(fk.onUpdate())) {
                sb.append(" ON UPDATE ").append(fk.onUpdate());
            }
            j.add(sb);
        }
        StringBuilder out = new StringBuilder("-- DDL montado a partir dos metadados (aproximado: tipos, defaults e ")
                .append("restrições podem precisar de ajuste para recriar a tabela)\n\n").append(j);
        if (type != DbType.POSTGRESQL) { // no PostgreSQL vêm as definições exatas logo depois
            for (IndexInfo ix : MetadataService.indexes(c, t.schema(), t.name())) {
                if (ix.primaryKey()) {
                    continue;
                }
                out.append("\n\nCREATE ").append(ix.unique() ? "UNIQUE " : "").append("INDEX ")
                        .append(type.quote(ix.name())).append(" ON ").append(name)
                        .append(" (").append(quoteList(type, ix.columns())).append(");");
            }
        }
        return out.toString();
    }

    private static String quoteList(DbType type, String commaList) {
        StringJoiner j = new StringJoiner(", ");
        for (String p : commaList.split(",\\s*")) {
            j.add(p.startsWith("(") ? p : type.quote(p));
        }
        return j.toString();
    }
}
