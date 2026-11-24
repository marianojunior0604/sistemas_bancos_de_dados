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

    public static TypeInfo typeInfo(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        return TYPES.stream().filter(t -> t.name().equals(n)).findFirst().orElse(null);
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
        TableDesign d = new TableDesign();
        d.schema = schema;
        d.name = "";
        Column id = new Column();
        id.name = DdlBuilder.normalizeName("id", casing);
        id.type = "BIGINT";
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
        for (Meta.ColumnInfo ci : MetadataService.columns(c, schema, table)) {
            Column col = new Column();
            col.name = ci.name();
            col.type = normalizeType(ci.typeName());
            TypeInfo ti = typeInfo(col.type);
            boolean hugeLength = ci.size() <= 0 || ci.size() >= 1_000_000_000;
            col.length = ti != null && ti.hasLength() && !hugeLength ? Integer.valueOf(ci.size()) : null;
            col.scale = ti != null && ti.hasScale() ? Integer.valueOf(ci.scale()) : null;
            col.nullable = ci.nullable();
            col.identity = ci.autoIncrement();
            String def = ci.defaultValue();
            col.defaultExpr = def == null || def.isBlank() || def.toUpperCase(Locale.ROOT).contains("NEXT VALUE FOR")
                    || ci.generated() ? null : def.trim();
            col.primaryKey = ci.primaryKey();
            col.comment = ci.remarks() == null || ci.remarks().isBlank() ? null : ci.remarks();
            col.original = col.copy();
            d.columns.add(col);
        }
        for (Meta.IndexInfo ii : MetadataService.indexes(c, schema, table)) {
            if (ii.primaryKey()) {
                continue;
            }
            Index idx = new Index();
            idx.name = ii.name();
            idx.columns = new ArrayList<>(Arrays.asList(ii.columns().split(",\\s*")));
            idx.unique = ii.unique();
            idx.automatic = AUTOMATIC_INDEX.matcher(ii.name()).matches();
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
}
