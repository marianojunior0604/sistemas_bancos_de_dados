package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.Meta.ColumnInfo;
import br.com.marianojunior.h2studio.core.Meta.ForeignKeyInfo;
import br.com.marianojunior.h2studio.core.Meta.IndexInfo;
import br.com.marianojunior.h2studio.core.Meta.TableInfo;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Lê a estrutura do banco usando apenas JDBC DatabaseMetaData, que funciona
 * igual no H2 1.4 e no 2.x (o INFORMATION_SCHEMA mudou bastante entre eles).
 */
public final class MetadataService {

    public static final Set<String> SYSTEM_SCHEMAS = Set.of("INFORMATION_SCHEMA");

    private MetadataService() {
    }

    /**
     * Como o banco organiza as tabelas:
     * <ul>
     *   <li>SCHEMA — H2, PostgreSQL, SQL Server (schemas dentro do banco);</li>
     *   <li>CATALOG — MySQL/MariaDB (cada "banco" do servidor é um catálogo);</li>
     *   <li>NONE — Firebird e SQLite (todas as tabelas no mesmo nível).</li>
     * </ul>
     * Nos três casos o resto do programa trata o nível como "schema"; em NONE ele é "".
     */
    public enum Namespace { SCHEMA, CATALOG, NONE }

    private static final java.util.Map<Connection, Namespace> NS_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static Namespace namespace(Connection c) throws SQLException {
        Namespace ns = NS_CACHE.get(c);
        if (ns == null) {
            DatabaseMetaData md = c.getMetaData();
            if (md.supportsSchemasInTableDefinitions()) {
                ns = Namespace.SCHEMA;
            } else if (md.supportsCatalogsInTableDefinitions()) {
                ns = Namespace.CATALOG;
            } else {
                ns = Namespace.NONE;
            }
            NS_CACHE.put(c, ns);
        }
        return ns;
    }

    /** Argumento "catalog" das chamadas do DatabaseMetaData para este schema. */
    public static String cat(Namespace ns, String schema) {
        return ns == Namespace.CATALOG ? schema : null;
    }

    /** Argumento "schema" das chamadas do DatabaseMetaData. */
    public static String sch(Namespace ns, String schema) {
        return ns == Namespace.SCHEMA ? schema : null;
    }

    /** Schema (ou catálogo) de uma linha de resultado do DatabaseMetaData. */
    private static String rowSchema(Namespace ns, ResultSet rs, String catCol, String schCol) throws SQLException {
        return switch (ns) {
            case SCHEMA -> rs.getString(schCol);
            case CATALOG -> rs.getString(catCol);
            case NONE -> "";
        };
    }

    /** Schema em uso pela conexão (public, dbo, o banco do MySQL...) ou null. */
    public static String currentSchema(Connection c) {
        try {
            return switch (namespace(c)) {
                case SCHEMA -> c.getSchema();
                case CATALOG -> c.getCatalog();
                case NONE -> "";
            };
        } catch (SQLException | AbstractMethodError e) {
            return null;
        }
    }

    private static boolean isSystem(String schema) {
        return SYSTEM_SCHEMAS.contains(schema.toUpperCase(Locale.ROOT)) || DbType.isSystemSchema(schema);
    }

    public static List<String> schemas(Connection c, boolean includeSystem) throws SQLException {
        Namespace ns = namespace(c);
        List<String> list = new ArrayList<>();
        if (ns == Namespace.NONE) {
            list.add("");
            return list;
        }
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = ns == Namespace.SCHEMA ? md.getSchemas() : md.getCatalogs()) {
            while (rs.next()) {
                String s = rs.getString(ns == Namespace.SCHEMA ? "TABLE_SCHEM" : "TABLE_CAT");
                if (s != null && (includeSystem || !isSystem(s))) {
                    list.add(s);
                }
            }
        }
        // PUBLIC (ou dbo) primeiro, o resto em ordem alfabética
        list.sort((a, b) -> {
            boolean pa = a.equalsIgnoreCase("PUBLIC") || a.equals("dbo");
            boolean pb = b.equalsIgnoreCase("PUBLIC") || b.equals("dbo");
            if (pa != pb) {
                return pa ? -1 : 1;
            }
            return a.compareToIgnoreCase(b);
        });
        return list;
    }

    public static List<TableInfo> tables(Connection c, String schema) throws SQLException {
        Namespace ns = namespace(c);
        boolean system = schema != null && isSystem(schema);
        List<TableInfo> list = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(cat(ns, schema), sch(ns, schema), "%", null)) {
            while (rs.next()) {
                String rowSchema = rowSchema(ns, rs, "TABLE_CAT", "TABLE_SCHEM");
                if (ns == Namespace.SCHEMA && schema != null && !schema.equals(rowSchema)) {
                    continue; // o nome do schema é um padrão LIKE: "_" casaria com outros
                }
                String type = rs.getString("TABLE_TYPE");
                String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
                // H2 1.4: TABLE / VIEW / SYSTEM TABLE ; H2 2.x: BASE TABLE / VIEW ...
                // PostgreSQL: PARTITIONED TABLE, FOREIGN TABLE, MATERIALIZED VIEW
                boolean view = t.contains("VIEW") && (system || !t.startsWith("SYSTEM"));
                boolean table = t.equals("TABLE") || t.equals("BASE TABLE") || t.equals("TABLE LINK")
                        || t.equals("EXTERNAL") || t.equals("PARTITIONED TABLE") || t.equals("FOREIGN TABLE")
                        || (t.equals("SYSTEM TABLE") && system);
                if (view || table) {
                    list.add(new TableInfo(rowSchema == null ? "" : rowSchema, rs.getString("TABLE_NAME"), view,
                            rs.getString("REMARKS")));
                }
            }
        }
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return list;
    }

    public static List<ColumnInfo> columns(Connection c, String schema, String table) throws SQLException {
        Namespace ns = namespace(c);
        String ca = cat(ns, schema);
        String sc = sch(ns, schema);
        DatabaseMetaData md = c.getMetaData();
        Set<String> pk = new HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(ca, sc, table)) {
            while (rs.next()) {
                pk.add(rs.getString("COLUMN_NAME"));
            }
        }
        // FK simples (uma coluna): coluna -> {schema, tabela, coluna referenciada}
        Map<String, String[]> fk = new HashMap<>();
        Map<String, Integer> fkSize = new HashMap<>();
        try (ResultSet rs = md.getImportedKeys(ca, sc, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME") + "|" + rs.getString("PKTABLE_NAME");
                fkSize.merge(fkName, 1, Integer::sum);
                fk.put(rs.getString("FKCOLUMN_NAME"), new String[]{
                    rowSchema(ns, rs, "PKTABLE_CAT", "PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"),
                    rs.getString("PKCOLUMN_NAME"), fkName});
            }
        }
        // chaves compostas não ganham a busca de valores (precisariam de várias colunas juntas)
        fk.values().removeIf(v -> fkSize.getOrDefault(v[3], 1) > 1);
        List<ColumnInfo> list = new ArrayList<>();
        try (ResultSet rs = md.getColumns(ca, sc, table, "%")) {
            while (rs.next()) {
                // nomes são padrões LIKE: "cad_cliente" também casaria com "cadxcliente"
                if (!table.equals(rs.getString("TABLE_NAME"))
                        || (ns == Namespace.SCHEMA && schema != null && !schema.equals(rs.getString("TABLE_SCHEM")))) {
                    continue;
                }
                String name = rs.getString("COLUMN_NAME");
                String auto = safeString(rs, "IS_AUTOINCREMENT");
                boolean generated = "YES".equalsIgnoreCase(safeString(rs, "IS_GENERATEDCOLUMN"));
                String def = rs.getString("COLUMN_DEF");
                String defUp = def == null ? "" : def.toUpperCase(Locale.ROOT);
                boolean isAuto = "YES".equalsIgnoreCase(auto)
                        || defUp.contains("NEXT VALUE FOR") || defUp.startsWith("NEXTVAL(");
                String[] ref = fk.get(name);
                list.add(new ColumnInfo(
                        rs.getInt("ORDINAL_POSITION"), name, rs.getString("TYPE_NAME"), rs.getInt("DATA_TYPE"),
                        rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        generated ? null : def, isAuto, generated, pk.contains(name),
                        ref == null ? null : ref[0], ref == null ? null : ref[1], ref == null ? null : ref[2],
                        rs.getString("REMARKS")));
            }
        }
        list.sort((a, b) -> Integer.compare(a.position(), b.position()));
        if (ns == Namespace.NONE && md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("firebird")) {
            list = markFirebirdTriggerKeys(c, table, list);
        }
        if (ns == Namespace.NONE && md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("sqlite")) {
            list = sqliteTypes(list);
        }
        return list;
    }

    /**
     * O SQLite não tem tipos de verdade: o driver informa DATE como VARCHAR e BOOLEAN como INTEGER.
     * Usa o tipo declarado no CREATE TABLE para a grade validar e converter como o sistema espera,
     * e trata "INTEGER PRIMARY KEY" (apelido do rowid) como auto incremento.
     */
    static List<ColumnInfo> sqliteTypes(List<ColumnInfo> list) {
        long pkCount = list.stream().filter(ColumnInfo::primaryKey).count();
        List<ColumnInfo> out = new ArrayList<>(list.size());
        for (ColumnInfo x : list) {
            String t = x.typeName() == null ? "" : x.typeName().toUpperCase(Locale.ROOT).trim();
            int jdbc = x.jdbcType();
            if (t.contains("BOOL")) {
                jdbc = java.sql.Types.BOOLEAN;
            } else if (t.startsWith("DATETIME") || t.startsWith("TIMESTAMP")) {
                jdbc = java.sql.Types.TIMESTAMP;
            } else if (t.equals("DATE")) {
                jdbc = java.sql.Types.DATE;
            } else if (t.equals("TIME")) {
                jdbc = java.sql.Types.TIME;
            } else if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")) {
                jdbc = java.sql.Types.NUMERIC;
            }
            int size = x.size() >= 2_000_000_000 ? 0 : x.size(); // "sem limite" no SQLite
            boolean auto = x.autoIncrement() || (x.primaryKey() && pkCount == 1 && t.equals("INTEGER"));
            out.add(new ColumnInfo(x.position(), x.name(), x.typeName(), jdbc, size, x.scale(), x.nullable(),
                    x.defaultValue(), auto, x.generated(), x.primaryKey(), x.fkSchema(), x.fkTable(), x.fkColumn(),
                    x.remarks()));
        }
        return out;
    }

    /** NEW.ID = GEN_ID(GEN_CLIENTES, 1)  /  NEW."ID" = NEXT VALUE FOR SEQ_X */
    private static final java.util.regex.Pattern FB_GENERATOR = java.util.regex.Pattern.compile(
            "NEW\\.(\"?)([\\w$]+)\\1\\s*=\\s*(?:GEN_ID\\s*\\(|NEXT\\s+VALUE\\s+FOR\\b)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Colunas preenchidas por gatilho BEFORE com generator (padrão dos sistemas Firebird antigos). */
    public static Set<String> generatorColumns(String triggerSource) {
        Set<String> cols = new HashSet<>();
        if (triggerSource != null) {
            java.util.regex.Matcher m = FB_GENERATOR.matcher(triggerSource);
            while (m.find()) {
                cols.add(m.group(1).isEmpty() ? m.group(2).toUpperCase(Locale.ROOT) : m.group(2));
            }
        }
        return cols;
    }

    /**
     * No Firebird antigo o "auto incremento" é um generator usado num gatilho BEFORE INSERT,
     * que o JDBC não informa. Sem isso a grade exigiria digitar o código de toda linha nova.
     */
    private static List<ColumnInfo> markFirebirdTriggerKeys(Connection c, String table, List<ColumnInfo> list) {
        Set<String> auto = new HashSet<>();
        String sql = "SELECT RDB$TRIGGER_SOURCE, RDB$TRIGGER_TYPE FROM RDB$TRIGGERS "
                + "WHERE RDB$RELATION_NAME = ? AND COALESCE(RDB$TRIGGER_INACTIVE, 0) = 0";
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // tipos ímpares são BEFORE (1 = before insert; 17, 25, 113... = ações combinadas)
                    if ((rs.getInt(2) & 1) == 1) {
                        auto.addAll(generatorColumns(rs.getString(1)));
                    }
                }
            }
        } catch (SQLException e) {
            return list; // sem permissão nas tabelas de sistema: segue sem a dica
        }
        if (auto.isEmpty()) {
            return list;
        }
        List<ColumnInfo> out = new ArrayList<>(list.size());
        for (ColumnInfo x : list) {
            out.add(auto.contains(x.name()) && !x.autoIncrement()
                    ? new ColumnInfo(x.position(), x.name(), x.typeName(), x.jdbcType(), x.size(), x.scale(), x.nullable(),
                            x.defaultValue(), true, x.generated(), x.primaryKey(), x.fkSchema(), x.fkTable(), x.fkColumn(),
                            x.remarks())
                    : x);
        }
        return out;
    }

    public static List<IndexInfo> indexes(Connection c, String schema, String table) throws SQLException {
        Namespace ns = namespace(c);
        DatabaseMetaData md = c.getMetaData();
        String pkName = null;
        java.util.Set<String> pkCols = new java.util.HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(cat(ns, schema), sch(ns, schema), table)) {
            while (rs.next()) {
                if (pkName == null) {
                    pkName = rs.getString("PK_NAME");
                }
                pkCols.add(rs.getString("COLUMN_NAME"));
            }
        }
        Map<String, List<String>> cols = new LinkedHashMap<>();
        Map<String, Boolean> unique = new HashMap<>();
        try (ResultSet rs = md.getIndexInfo(cat(ns, schema), sch(ns, schema), table, false, true)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String col = rs.getString("COLUMN_NAME");
                cols.computeIfAbsent(name, k -> new ArrayList<>()).add(col == null ? "(expressão)" : col);
                unique.put(name, !rs.getBoolean("NON_UNIQUE"));
            }
        }
        List<IndexInfo> list = new ArrayList<>();
        for (var e : cols.entrySet()) {
            String n = e.getKey();
            boolean isPk = (pkName != null && !pkName.isEmpty() && n.equals(pkName))
                    || n.toUpperCase(Locale.ROOT).startsWith("PRIMARY_KEY")
                    || n.equals("PRIMARY") // MySQL/MariaDB
                    || (n.startsWith("sqlite_autoindex_") && new java.util.HashSet<>(e.getValue()).equals(pkCols)
                        && unique.getOrDefault(n, false));
            list.add(new IndexInfo(n, unique.getOrDefault(n, false), String.join(", ", e.getValue()), isPk));
        }
        return list;
    }

    public static List<ForeignKeyInfo> foreignKeys(Connection c, String schema, String table) throws SQLException {
        Namespace ns = namespace(c);
        DatabaseMetaData md = c.getMetaData();
        List<ForeignKeyInfo> list = new ArrayList<>();
        try (ResultSet rs = md.getImportedKeys(cat(ns, schema), sch(ns, schema), table)) {
            collectFks(ns, rs, list, false);
        }
        try (ResultSet rs = md.getExportedKeys(cat(ns, schema), sch(ns, schema), table)) {
            collectFks(ns, rs, list, true);
        } catch (SQLException e) {
            // alguns drivers antigos não implementam: mostra só as chaves desta tabela
        }
        return list;
    }

    private static void collectFks(Namespace ns, ResultSet rs, List<ForeignKeyInfo> out, boolean incoming)
            throws SQLException {
        // agrupa chaves compostas pelo nome
        Map<String, String[]> map = new LinkedHashMap<>();
        while (rs.next()) {
            String name = rs.getString("FK_NAME");
            if (name == null || name.isEmpty()) {
                name = "(sem nome)";
            }
            String key = name + "|" + rs.getString(incoming ? "FKTABLE_NAME" : "PKTABLE_NAME");
            String[] v = map.get(key);
            String col = rs.getString(incoming ? "PKCOLUMN_NAME" : "FKCOLUMN_NAME");
            String refCol = rs.getString(incoming ? "FKCOLUMN_NAME" : "PKCOLUMN_NAME");
            if (v == null) {
                map.put(key, new String[]{
                    name, col,
                    incoming ? rowSchema(ns, rs, "FKTABLE_CAT", "FKTABLE_SCHEM")
                            : rowSchema(ns, rs, "PKTABLE_CAT", "PKTABLE_SCHEM"),
                    rs.getString(incoming ? "FKTABLE_NAME" : "PKTABLE_NAME"),
                    refCol, rule(rs.getShort("UPDATE_RULE")), rule(rs.getShort("DELETE_RULE"))});
            } else {
                v[1] += ", " + col;
                v[4] += ", " + refCol;
            }
        }
        for (String[] v : map.values()) {
            out.add(new ForeignKeyInfo(v[0], v[1], v[2], v[3], v[4], v[5], v[6], incoming));
        }
    }

    private static String rule(short r) {
        return switch (r) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    /**
     * DDL da tabela usando o comando SCRIPT do próprio H2 (exige usuário admin).
     * Quando não for possível, monta um CREATE TABLE aproximado a partir dos metadados.
     */
    public static String ddl(Connection c, String schema, String table) throws SQLException {
        String sql = "SCRIPT NODATA NOPASSWORDS NOSETTINGS TABLE " + SqlUtil.quote(schema) + "." + SqlUtil.quote(table);
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<String> stmts = new ArrayList<>();
            while (rs.next()) {
                String line = rs.getString(1).trim();
                String u = line.toUpperCase(Locale.ROOT);
                if (u.startsWith("CREATE USER") || u.startsWith("--") || u.startsWith("SET ")
                        || u.startsWith("CREATE SCHEMA") || u.startsWith("ALTER USER")) {
                    continue;
                }
                if ((u.startsWith("CREATE CACHED TABLE") || u.startsWith("CREATE MEMORY TABLE")) && !mentions(line, table)) {
                    continue;
                }
                stmts.add(line.endsWith(";") ? line : line + ";");
            }
            // o H2 1.4 exporta as sequências de todas as tabelas: mantém só as usadas por esta
            String rest = String.join("\n", stmts.stream()
                    .filter(s -> !s.toUpperCase(Locale.ROOT).startsWith("CREATE SEQUENCE")).toList());
            StringBuilder sb = new StringBuilder();
            for (String s : stmts) {
                if (s.toUpperCase(Locale.ROOT).startsWith("CREATE SEQUENCE")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("CREATE SEQUENCE (?:IF NOT EXISTS )?(\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(s);
                    if (m.find()) {
                        String name = m.group(1);
                        String bare = name.substring(name.lastIndexOf('.') + 1);
                        if (!rest.contains(name) && !rest.contains(bare)) {
                            continue;
                        }
                    }
                }
                sb.append(s).append("\n\n");
            }
            return sb.toString().trim();
        } catch (SQLException e) {
            return "-- Não foi possível usar o comando SCRIPT (" + e.getMessage().split("\n")[0] + ")\n"
                    + "-- DDL aproximado montado pelos metadados:\n\n" + approximateDdl(c, schema, table);
        }
    }

    private static boolean mentions(String line, String table) {
        return line.contains("\"" + table + "\"") || line.contains("." + table + "(") || line.contains("." + table + " ");
    }

    private static String approximateDdl(Connection c, String schema, String table) throws SQLException {
        List<ColumnInfo> cols = columns(c, schema, table);
        StringJoiner j = new StringJoiner(",\n    ", "CREATE TABLE " + SqlUtil.quote(schema) + "." + SqlUtil.quote(table) + " (\n    ", "\n);");
        List<String> pk = new ArrayList<>();
        for (ColumnInfo col : cols) {
            StringBuilder sb = new StringBuilder(SqlUtil.quote(col.name())).append(' ').append(col.typeDisplay());
            if (col.defaultValue() != null) {
                sb.append(" DEFAULT ").append(col.defaultValue());
            }
            if (!col.nullable()) {
                sb.append(" NOT NULL");
            }
            j.add(sb);
            if (col.primaryKey()) {
                pk.add(SqlUtil.quote(col.name()));
            }
        }
        if (!pk.isEmpty()) {
            j.add("PRIMARY KEY (" + String.join(", ", pk) + ")");
        }
        return j.toString();
    }

    private static String safeString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (SQLException e) {
            return null;
        }
    }
}
