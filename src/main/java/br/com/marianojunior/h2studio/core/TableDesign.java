package br.com.marianojunior.h2studio.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Modelo editável da estrutura de uma tabela (usado pelo designer).
 * <p>
 * Cada coluna, índice e chave guarda uma cópia do estado original lido do banco;
 * o {@link DdlBuilder} compara original × editado para gerar os ALTER TABLE.
 * Os campos são públicos de propósito: é um modelo de formulário, alterado pela tela.
 */
public final class TableDesign {

    /** Tipos oferecidos no designer (o campo aceita qualquer outro tipo do H2 digitado). */
    public record TypeInfo(String name, boolean hasLength, boolean hasScale, Integer defaultLength, Integer defaultScale) {
    }

    public static final List<TypeInfo> TYPES = List.of(
            new TypeInfo("INTEGER", false, false, null, null),
            new TypeInfo("BIGINT", false, false, null, null),
            new TypeInfo("SMALLINT", false, false, null, null),
            new TypeInfo("TINYINT", false, false, null, null),
            new TypeInfo("DECIMAL", true, true, 15, 2),
            new TypeInfo("NUMERIC", true, true, 15, 2),
            new TypeInfo("DOUBLE PRECISION", false, false, null, null),
            new TypeInfo("REAL", false, false, null, null),
            new TypeInfo("BOOLEAN", false, false, null, null),
            new TypeInfo("VARCHAR", true, false, 255, null),
            new TypeInfo("CHAR", true, false, 1, null),
            new TypeInfo("VARCHAR_IGNORECASE", true, false, 255, null),
            new TypeInfo("CLOB", false, false, null, null),
            new TypeInfo("DATE", false, false, null, null),
            new TypeInfo("TIME", false, false, null, null),
            new TypeInfo("TIMESTAMP", false, false, null, null),
            new TypeInfo("TIMESTAMP WITH TIME ZONE", false, false, null, null),
            new TypeInfo("BLOB", false, false, null, null),
            new TypeInfo("VARBINARY", true, false, 255, null),
            new TypeInfo("UUID", false, false, null, null),
            new TypeInfo("JSON", false, false, null, null));

    public static final List<String> FK_RULES = List.of("RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT", "NO ACTION");

    private static final List<TypeInfo> PG_TYPES = List.of(
            new TypeInfo("INTEGER", false, false, null, null),
            new TypeInfo("BIGINT", false, false, null, null),
            new TypeInfo("SMALLINT", false, false, null, null),
            new TypeInfo("NUMERIC", true, true, 15, 2),
            new TypeInfo("DOUBLE PRECISION", false, false, null, null),
            new TypeInfo("REAL", false, false, null, null),
            new TypeInfo("BOOLEAN", false, false, null, null),
            new TypeInfo("VARCHAR", true, false, 255, null),
            new TypeInfo("CHAR", true, false, 1, null),
            new TypeInfo("TEXT", false, false, null, null),
            new TypeInfo("DATE", false, false, null, null),
            new TypeInfo("TIME", false, false, null, null),
            new TypeInfo("TIMESTAMP", false, false, null, null),
            new TypeInfo("TIMESTAMP WITH TIME ZONE", false, false, null, null),
            new TypeInfo("BYTEA", false, false, null, null),
            new TypeInfo("UUID", false, false, null, null),
            new TypeInfo("JSONB", false, false, null, null),
            new TypeInfo("JSON", false, false, null, null));

    private static final List<TypeInfo> MYSQL_TYPES = List.of(
            new TypeInfo("INT", false, false, null, null),
            new TypeInfo("BIGINT", false, false, null, null),
            new TypeInfo("SMALLINT", false, false, null, null),
            new TypeInfo("TINYINT", false, false, null, null),
            new TypeInfo("DECIMAL", true, true, 15, 2),
            new TypeInfo("DOUBLE", false, false, null, null),
            new TypeInfo("FLOAT", false, false, null, null),
            new TypeInfo("BOOLEAN", false, false, null, null),
            new TypeInfo("VARCHAR", true, false, 255, null),
            new TypeInfo("CHAR", true, false, 1, null),
            new TypeInfo("TEXT", false, false, null, null),
            new TypeInfo("MEDIUMTEXT", false, false, null, null),
            new TypeInfo("LONGTEXT", false, false, null, null),
            new TypeInfo("DATE", false, false, null, null),
            new TypeInfo("TIME", false, false, null, null),
            new TypeInfo("DATETIME", false, false, null, null),
            new TypeInfo("TIMESTAMP", false, false, null, null),
            new TypeInfo("BLOB", false, false, null, null),
            new TypeInfo("LONGBLOB", false, false, null, null),
            new TypeInfo("JSON", false, false, null, null));

    private static final List<TypeInfo> FIREBIRD_TYPES = List.of(
            new TypeInfo("INTEGER", false, false, null, null),
            new TypeInfo("BIGINT", false, false, null, null),
            new TypeInfo("SMALLINT", false, false, null, null),
            new TypeInfo("NUMERIC", true, true, 15, 2),
            new TypeInfo("DECIMAL", true, true, 15, 2),
            new TypeInfo("DOUBLE PRECISION", false, false, null, null),
            new TypeInfo("FLOAT", false, false, null, null),
            new TypeInfo("BOOLEAN", false, false, null, null),
            new TypeInfo("VARCHAR", true, false, 100, null),
            new TypeInfo("CHAR", true, false, 1, null),
            new TypeInfo("BLOB SUB_TYPE TEXT", false, false, null, null),
            new TypeInfo("DATE", false, false, null, null),
            new TypeInfo("TIME", false, false, null, null),
            new TypeInfo("TIMESTAMP", false, false, null, null),
            new TypeInfo("BLOB", false, false, null, null));

    private static final List<TypeInfo> SQLSERVER_TYPES = List.of(
            new TypeInfo("INT", false, false, null, null),
            new TypeInfo("BIGINT", false, false, null, null),
            new TypeInfo("SMALLINT", false, false, null, null),
            new TypeInfo("TINYINT", false, false, null, null),
            new TypeInfo("DECIMAL", true, true, 15, 2),
            new TypeInfo("NUMERIC", true, true, 15, 2),
            new TypeInfo("FLOAT", false, false, null, null),
            new TypeInfo("REAL", false, false, null, null),
            new TypeInfo("BIT", false, false, null, null),
            new TypeInfo("VARCHAR", true, false, 255, null),
            new TypeInfo("NVARCHAR", true, false, 255, null),
            new TypeInfo("CHAR", true, false, 1, null),
            new TypeInfo("NCHAR", true, false, 1, null),
            new TypeInfo("VARCHAR(MAX)", false, false, null, null),
            new TypeInfo("NVARCHAR(MAX)", false, false, null, null),
            new TypeInfo("DATE", false, false, null, null),
            new TypeInfo("TIME", false, false, null, null),
            new TypeInfo("DATETIME2", false, false, null, null),
            new TypeInfo("DATETIME", false, false, null, null),
            new TypeInfo("VARBINARY(MAX)", false, false, null, null),
            new TypeInfo("UNIQUEIDENTIFIER", false, false, null, null));

    private static final List<TypeInfo> SQLITE_TYPES = List.of(
            new TypeInfo("INTEGER", false, false, null, null),
            new TypeInfo("REAL", false, false, null, null),
            new TypeInfo("NUMERIC", false, false, null, null),
            new TypeInfo("DECIMAL", true, true, 15, 2),
            new TypeInfo("TEXT", false, false, null, null),
            new TypeInfo("VARCHAR", true, false, 255, null),
            new TypeInfo("BOOLEAN", false, false, null, null),
            new TypeInfo("DATE", false, false, null, null),
            new TypeInfo("DATETIME", false, false, null, null),
            new TypeInfo("BLOB", false, false, null, null));

    /** Tipos oferecidos no designer para o banco. */
    public static List<TypeInfo> typesFor(DbType type) {
        return switch (type == null ? DbType.H2 : type) {
            case H2 -> TYPES;
            case POSTGRESQL -> PG_TYPES;
            case MYSQL, MARIADB -> MYSQL_TYPES;
            case FIREBIRD, FIREBIRD_25 -> FIREBIRD_TYPES;
            case SQLSERVER -> SQLSERVER_TYPES;
            case SQLITE -> SQLITE_TYPES;
        };
    }

    public static TypeInfo typeInfo(String name) {
        return typeInfo(name, DbType.H2);
    }

    public static TypeInfo typeInfo(String name, DbType type) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        return typesFor(type).stream().filter(t -> t.name().equals(n)).findFirst().orElse(null);
    }

    /** Nome do tipo como o designer de cada banco mostra (o driver informa nomes internos: int4, bpchar...). */
    public static String normalizeType(String raw, DbType type) {
        if (type == null || type == DbType.H2) {
            return normalizeType(raw);
        }
        String t = raw == null ? "VARCHAR" : raw.trim().toUpperCase(Locale.ROOT);
        t = t.replaceFirst("\\s+IDENTITY$", "").replaceFirst("\\s+UNSIGNED$", "").trim();
        return switch (type) {
            case POSTGRESQL -> switch (t) {
                case "INT4", "INT", "SERIAL" -> "INTEGER";
                case "INT8", "BIGSERIAL" -> "BIGINT";
                case "INT2", "SMALLSERIAL" -> "SMALLINT";
                case "FLOAT8" -> "DOUBLE PRECISION";
                case "FLOAT4" -> "REAL";
                case "BOOL" -> "BOOLEAN";
                case "BPCHAR" -> "CHAR";
                case "TIMESTAMPTZ" -> "TIMESTAMP WITH TIME ZONE";
                case "DECIMAL" -> "NUMERIC";
                default -> t;
            };
            case MYSQL, MARIADB -> switch (t) {
                case "INTEGER", "MEDIUMINT" -> "INT";
                case "BIT", "BOOL" -> "BOOLEAN";
                case "NUMERIC" -> "DECIMAL";
                case "DOUBLE PRECISION", "REAL" -> "DOUBLE";
                default -> t;
            };
            case FIREBIRD, FIREBIRD_25 -> switch (t) {
                case "BLOB SUB_TYPE 1", "BLOB SUB_TYPE TEXT", "CLOB", "LONGVARCHAR" -> "BLOB SUB_TYPE TEXT";
                case "BLOB SUB_TYPE 0", "BLOB SUB_TYPE BINARY", "LONGVARBINARY" -> "BLOB";
                case "DOUBLE" -> "DOUBLE PRECISION";
                case "INT", "INT64" -> t.equals("INT") ? "INTEGER" : "BIGINT";
                default -> t;
            };
            case SQLSERVER -> t;
            case SQLITE -> t.isEmpty() ? "TEXT" : t;
            case H2 -> normalizeType(raw);
        };
    }

    /** Nome do tipo como o designer mostra (o H2 2.x devolve "CHARACTER VARYING", o 1.4 "VARCHAR"...). */
    public static String normalizeType(String raw) {
        if (raw == null) {
            return "VARCHAR";
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "CHARACTER VARYING", "VARCHAR2", "NVARCHAR", "LONGVARCHAR" -> "VARCHAR";
            case "CHARACTER", "NCHAR" -> "CHAR";
            case "CHARACTER LARGE OBJECT", "NCLOB", "TEXT" -> "CLOB";
            case "BINARY LARGE OBJECT", "LONGVARBINARY", "BYTEA" -> "BLOB";
            case "BINARY VARYING", "BINARY" -> "VARBINARY";
            case "DOUBLE", "FLOAT", "FLOAT8" -> "DOUBLE PRECISION";
            case "INT", "INT4", "SIGNED", "MEDIUMINT" -> "INTEGER";
            case "INT8" -> "BIGINT";
            case "BOOL", "BIT" -> "BOOLEAN";
            case "DATETIME", "SMALLDATETIME" -> "TIMESTAMP";
            default -> t;
        };
    }

    /** Uma coluna em edição. {@code original} é null para colunas novas. */
    public static final class Column {
        public String name;
        public String type = "VARCHAR";
        public Integer length = 255;
        public Integer scale;
        public boolean nullable = true;
        public String defaultExpr;
        public boolean identity;
        public boolean primaryKey;
        public String comment;
        public boolean dropped;
        public Column original;

        public boolean isNew() {
            return original == null;
        }

        public Column copy() {
            Column c = new Column();
            c.name = name;
            c.type = type;
            c.length = length;
            c.scale = scale;
            c.nullable = nullable;
            c.defaultExpr = defaultExpr;
            c.identity = identity;
            c.primaryKey = primaryKey;
            c.comment = comment;
            return c;
        }
    }

    /** Um índice em edição. Índices automáticos (de PK/FK/UNIQUE) não podem ser alterados. */
    public static final class Index {
        public String name;
        public List<String> columns = new ArrayList<>();
        public boolean unique;
        public boolean automatic;
        public boolean dropped;
        public Index original;

        public boolean isNew() {
            return original == null;
        }

        public Index copy() {
            Index i = new Index();
            i.name = name;
            i.columns = new ArrayList<>(columns);
            i.unique = unique;
            i.automatic = automatic;
            return i;
        }
    }

    /** Uma chave estrangeira em edição (desta tabela para outra). */
    public static final class ForeignKey {
        public String name;
        public List<String> columns = new ArrayList<>();
        public String refSchema;
        public String refTable;
        public List<String> refColumns = new ArrayList<>();
        public String onDelete = "RESTRICT";
        public String onUpdate = "RESTRICT";
        public boolean dropped;
        public ForeignKey original;

        public boolean isNew() {
            return original == null;
        }

        public ForeignKey copy() {
            ForeignKey f = new ForeignKey();
            f.name = name;
            f.columns = new ArrayList<>(columns);
            f.refSchema = refSchema;
            f.refTable = refTable;
            f.refColumns = new ArrayList<>(refColumns);
            f.onDelete = onDelete;
            f.onUpdate = onUpdate;
            return f;
        }
    }

    public String schema;
    public String name;
    /** Nome da restrição da chave primária (para remover/trocar fora do H2). */
    public String pkName;
    public String comment;
    /** Nome e comentário originais (null = tabela nova). */
    public String originalName;
    public String originalComment;
    public final List<Column> columns = new ArrayList<>();
    public final List<Index> indexes = new ArrayList<>();
    public final List<ForeignKey> foreignKeys = new ArrayList<>();

    public boolean isNew() {
        return originalName == null;
    }

    public List<Column> activeColumns() {
        return columns.stream().filter(c -> !c.dropped).toList();
    }

    /** Nova tabela com uma coluna ID de identidade como chave primária. */
    public static TableDesign newTable(String schema, SqlCompletion.Casing casing) {
        return newTable(schema, casing, DbType.H2);
    }

    public static TableDesign newTable(String schema, SqlCompletion.Casing casing, DbType type) {
        TableDesign d = new TableDesign();
        d.schema = schema;
        d.name = "";
        Column id = new Column();
        id.name = DdlBuilder.normalizeName("id", casing);
        // no SQLite o auto incremento é "INTEGER PRIMARY KEY"; no Firebird 2.5 usa generator + gatilho
        id.type = type == DbType.SQLITE ? "INTEGER" : type == DbType.FIREBIRD_25 ? "INTEGER" : "BIGINT";
        id.length = null;
        id.identity = true;
        id.primaryKey = true;
        id.nullable = false;
        d.columns.add(id);
        return d;
    }

    private static final Pattern AUTOMATIC_INDEX = Pattern.compile("(?i)(PRIMARY_KEY_.*|.*_INDEX_[0-9A-F]+)");

    /** Lê a estrutura atual da tabela. */
    public static TableDesign load(Connection c, String schema, String table) throws SQLException {
        return load(c, schema, table, DbType.H2);
    }

    private static final java.util.regex.Pattern DECLARED_SIZE =
            java.util.regex.Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");

    /** Tamanho e escala como escritos no CREATE TABLE do SQLite (coluna → {tamanho, escala ou -1}). */
    static java.util.Map<String, int[]> sqliteDeclaredSizes(Connection c, String table) throws SQLException {
        java.util.Map<String, int[]> out = new java.util.HashMap<>();
        try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT name, type FROM pragma_table_info(?)")) {
            ps.setString(1, table);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(2);
                    java.util.regex.Matcher m = DECLARED_SIZE.matcher(t == null ? "" : t);
                    if (m.find()) {
                        out.put(rs.getString(1), new int[] {Integer.parseInt(m.group(1)),
                                m.group(2) == null ? -1 : Integer.parseInt(m.group(2))});
                    }
                }
            }
        }
        return out;
    }

    /** Lê a estrutura atual da tabela de qualquer banco. */
    public static TableDesign load(Connection c, String schema, String table, DbType type) throws SQLException {
        DbType dt = type == null ? DbType.H2 : type;
        java.util.Set<String> constraintIndexes = dt == DbType.H2 ? java.util.Set.of() : constraintIndexes(c, dt, schema, table);
        TableDesign d = new TableDesign();
        d.schema = schema;
        d.name = table;
        d.originalName = table;
        for (Meta.TableInfo t : MetadataService.tables(c, schema)) {
            if (t.name().equals(table)) {
                d.comment = t.remarks() == null || t.remarks().isBlank() ? null : t.remarks();
            }
        }
        d.originalComment = d.comment;
        java.util.Map<String, int[]> declared = dt == DbType.SQLITE ? sqliteDeclaredSizes(c, table) : java.util.Map.of();
        for (Meta.ColumnInfo ci : MetadataService.columns(c, schema, table)) {
            Column col = new Column();
            col.name = ci.name();
            col.type = normalizeType(ci.typeName(), dt);
            TypeInfo ti = typeInfo(col.type, dt);
            if (dt == DbType.SQLSERVER && ci.size() >= Integer.MAX_VALUE - 1
                    && (col.type.equals("VARCHAR") || col.type.equals("NVARCHAR") || col.type.equals("VARBINARY"))) {
                col.type = col.type + "(MAX)";
                ti = typeInfo(col.type, dt);
            }
            boolean hugeLength = ci.size() <= 0 || ci.size() >= 1_000_000_000;
            col.length = ti != null && ti.hasLength() && !hugeLength ? Integer.valueOf(ci.size()) : null;
            col.scale = ti != null && ti.hasScale() ? Integer.valueOf(ci.scale()) : null;
            if (dt == DbType.SQLITE) {
                // o driver do SQLite informa tamanho/escala errados para DECIMAL(10,2): usa o tipo declarado
                int[] ds = declared.get(ci.name());
                col.length = ti != null && ti.hasLength() && ds != null ? Integer.valueOf(ds[0]) : null;
                col.scale = ti != null && ti.hasScale() && ds != null && ds[1] >= 0 ? Integer.valueOf(ds[1]) : null;
            }
            col.nullable = ci.nullable();
            col.identity = ci.autoIncrement();
            String def = ci.defaultValue();
            col.defaultExpr = def == null || def.isBlank() || def.toUpperCase(Locale.ROOT).contains("NEXT VALUE FOR")
                    || def.toLowerCase(Locale.ROOT).startsWith("nextval(") || ci.generated() || ci.autoIncrement()
                    ? null : cleanDefault(def.trim(), dt);
            col.primaryKey = ci.primaryKey();
            col.comment = ci.remarks() == null || ci.remarks().isBlank() ? null : ci.remarks();
            col.original = col.copy();
            d.columns.add(col);
        }
        for (Meta.IndexInfo ii : MetadataService.indexes(c, schema, table)) {
            if (ii.primaryKey()) {
                d.pkName = ii.name();
                continue;
            }
            Index idx = new Index();
            idx.name = ii.name();
            idx.columns = new ArrayList<>(Arrays.asList(ii.columns().split(",\\s*")));
            idx.unique = ii.unique();
            idx.automatic = dt == DbType.H2 ? AUTOMATIC_INDEX.matcher(ii.name()).matches()
                    : constraintIndexes.contains(ii.name());
            idx.original = idx.copy();
            d.indexes.add(idx);
        }
        for (Meta.ForeignKeyInfo fi : MetadataService.foreignKeys(c, schema, table)) {
            if (fi.incoming()) {
                continue;
            }
            ForeignKey fk = new ForeignKey();
            fk.name = fi.name();
            fk.columns = new ArrayList<>(Arrays.asList(fi.columns().split(",\\s*")));
            fk.refSchema = fi.refSchema();
            fk.refTable = fi.refTable();
            fk.refColumns = new ArrayList<>(Arrays.asList(fi.refColumns().split(",\\s*")));
            fk.onDelete = fi.onDelete();
            fk.onUpdate = fi.onUpdate();
            fk.original = fk.copy();
            d.foreignKeys.add(fk);
        }
        return d;
    }

    /**
     * Índices que pertencem a restrições (UNIQUE, chave estrangeira) e não podem ser removidos
     * com DROP INDEX: aparecem no designer, mas só para consulta.
     */
    static java.util.Set<String> constraintIndexes(Connection c, DbType type, String schema, String table) {
        java.util.Set<String> out = new java.util.HashSet<>();
        String sql = switch (type) {
            case POSTGRESQL -> "SELECT conname FROM pg_constraint WHERE conrelid = CAST(? AS regclass)";
            case SQLSERVER -> "SELECT name FROM sys.key_constraints WHERE parent_object_id = OBJECT_ID(?)";
            case FIREBIRD, FIREBIRD_25 -> "SELECT TRIM(RDB$INDEX_NAME) FROM RDB$RELATION_CONSTRAINTS "
                    + "WHERE RDB$RELATION_NAME = ? AND RDB$INDEX_NAME IS NOT NULL";
            default -> null;
        };
        if (sql != null) {
            String param = type.isFirebird() ? table : type.qualified(schema, table);
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, param);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1).trim());
                    }
                }
            } catch (SQLException ignored) {
                // sem permissão no catálogo: o DROP INDEX mostra o erro do banco
            }
        }
        try {
            // índices com o nome de uma chave estrangeira (MySQL cria um para cada FK)
            for (Meta.ForeignKeyInfo f : MetadataService.foreignKeys(c, schema, table)) {
                if (!f.incoming()) {
                    out.add(f.name());
                }
            }
            for (Meta.IndexInfo ii : MetadataService.indexes(c, schema, table)) {
                if (ii.name().startsWith("sqlite_autoindex_") || ii.name().startsWith("RDB$")) {
                    out.add(ii.name());
                }
            }
        } catch (SQLException ignored) {
            // segue sem a marcação
        }
        return out;
    }

    /** Valor padrão como o usuário escreveria: tira o "::tipo" do PostgreSQL e os parênteses do SQL Server. */
    static String cleanDefault(String def, DbType type) {
        String d = def;
        if (type == DbType.POSTGRESQL) {
            d = d.replaceFirst("::[a-zA-Z_ ]+(\\[\\])?$", "");
        }
        if (type == DbType.SQLSERVER) {
            while (d.length() >= 2 && d.startsWith("(") && d.endsWith(")")) {
                d = d.substring(1, d.length() - 1);
            }
        }
        if (type.isFirebird() && d.toUpperCase(Locale.ROOT).startsWith("DEFAULT ")) {
            d = d.substring(8).trim();
        }
        return d;
    }
}
