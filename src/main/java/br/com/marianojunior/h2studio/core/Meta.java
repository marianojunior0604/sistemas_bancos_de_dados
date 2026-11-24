package br.com.marianojunior.h2studio.core;

/** Estruturas de metadados lidas do banco. */
public final class Meta {

    private Meta() {
    }

    public record TableInfo(String schema, String name, boolean view, String remarks) {
        public String qualified() {
            return SqlUtil.quote(schema) + "." + SqlUtil.quote(name);
        }
    }

    /**
     * @param jdbcType  constante de java.sql.Types
     * @param generated coluna calculada (GENERATED ALWAYS AS / AS expressão): não pode ser gravada
     * @param fkSchema  schema da tabela referenciada, quando a coluna é chave estrangeira simples
     */
    public record ColumnInfo(int position, String name, String typeName, int jdbcType, int size, int scale,
                             boolean nullable, String defaultValue, boolean autoIncrement, boolean generated,
                             boolean primaryKey, String fkSchema, String fkTable, String fkColumn,
                             String remarks) {

        /** "TABELA.COLUNA" quando a coluna é chave estrangeira, ou null. */
        public String fkTarget() {
            return fkTable == null ? null : fkTable + "." + fkColumn;
        }

        public boolean isForeignKey() {
            return fkTable != null;
        }

        /** Tem valor automático quando omitida num INSERT (padrão, identidade ou calculada). */
        public boolean hasAutomaticValue() {
            return autoIncrement || generated || (defaultValue != null && !defaultValue.isBlank());
        }

        public String typeDisplay() {
            String t = typeName == null ? "?" : typeName;
            if (SqlUtil.hasLength(jdbcType) && size > 0 && size < Integer.MAX_VALUE && !t.contains("(")) {
                return t + "(" + size + ")";
            }
            if (SqlUtil.hasPrecision(jdbcType) && size > 0 && !t.contains("(")) {
                return scale > 0 ? t + "(" + size + "," + scale + ")" : t + "(" + size + ")";
            }
            return t;
        }
    }

    public record IndexInfo(String name, boolean unique, String columns, boolean primaryKey) {
    }

    /** Chave estrangeira desta tabela apontando para outra (ou de outra para esta, quando incoming=true). */
    public record ForeignKeyInfo(String name, String columns, String refSchema, String refTable,
                                 String refColumns, String onUpdate, String onDelete, boolean incoming) {
    }
}
