package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.TableDesign.Column;
import br.com.marianojunior.h2studio.core.TableDesign.ForeignKey;
import br.com.marianojunior.h2studio.core.TableDesign.Index;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Gera o SQL (CREATE / ALTER TABLE, índices, chaves) a partir de um {@link TableDesign}.
 * <p>
 * A sintaxe usada funciona igual no H2 1.4.200, 2.1 e 2.3 (testado). Os comandos saem na
 * ordem segura: renomear tabela → remover chaves/índices → remover chave primária →
 * remover/renomear/alterar colunas → adicionar colunas → chave primária → índices →
 * chaves estrangeiras → comentários.
 * <p>
 * Atenção: no H2 cada comando de estrutura é gravado na hora (não há rollback entre eles).
 */
public final class DdlBuilder {

    /** Resultado: comandos, erros (impedem aplicar) e avisos (pedem atenção). */
    public record Plan(List<String> statements, List<String> errors, List<String> warnings) {
        public boolean hasChanges() {
            return !statements.isEmpty();
        }

        /** Script pronto (para copiar ou salvar como migração). */
        public String script() {
            return String.join(";\n\n", statements) + (statements.isEmpty() ? "" : ";\n");
        }
    }

    private final TableDesign d;
    private final SqlCompletion.Casing casing;
    private final String defaultSchema;
    private final Set<String> existingTables;
    private final List<String> sql = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private DdlBuilder(TableDesign d, SqlCompletion.Casing casing, String defaultSchema, Set<String> existingTables) {
        this.d = d;
        this.casing = casing;
        this.defaultSchema = defaultSchema;
        this.existingTables = existingTables;
    }

    /**
     * @param existingTables nomes das tabelas já existentes no schema (para avisar nome repetido)
     */
    public static Plan build(TableDesign d, SqlCompletion.Casing casing, String defaultSchema, Set<String> existingTables) {
        DdlBuilder b = new DdlBuilder(d, casing, defaultSchema, existingTables == null ? Set.of() : existingTables);
        b.validate();
        if (b.errors.isEmpty()) {
            if (d.isNew()) {
                b.buildCreate();
            } else {
                b.buildAlter();
            }
        }
        return new Plan(List.copyOf(b.sql), List.copyOf(b.errors), List.copyOf(b.warnings));
    }

    // =====================================================================
    // Nomes
    // =====================================================================

    /** Ajusta o nome digitado ao jeito que o banco guarda nomes sem aspas. */
    public static String normalizeName(String name, SqlCompletion.Casing casing) {
        if (name == null) {
            return "";
        }
        String n = name.trim();
        return switch (casing) {
            case UPPER -> n.toUpperCase(Locale.ROOT);
            case LOWER -> n.toLowerCase(Locale.ROOT);
            case EXACT -> n;
        };
    }

    private String id(String name) {
        return SqlCompletion.quoteIfNeeded(name, casing);
    }

    private String table(String name) {
        boolean def = d.schema == null || d.schema.equals(defaultSchema);
        return def ? id(name) : id(d.schema) + "." + id(name);
    }

    private String tableRef(String schema, String name) {
        boolean def = schema == null || schema.equals(defaultSchema);
        return def ? id(name) : id(schema) + "." + id(name);
    }

    private String qualifiedIndex(String name) {
        boolean def = d.schema == null || d.schema.equals(defaultSchema);
        return def ? id(name) : id(d.schema) + "." + id(name);
    }

    private static boolean validIdentifier(String n) {
        if (n == null || n.isBlank()) {
            return false;
        }
        if (!SqlLexer.isIdentStart(n.charAt(0))) {
            return false;
        }
        return n.chars().allMatch(ch -> SqlLexer.isIdentPart((char) ch));
    }

    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // =====================================================================
    // Tipos e definição de coluna
    // =====================================================================

    public static String typeSql(Column c) {
        String t = c.type == null ? "" : c.type.trim().toUpperCase(Locale.ROOT);
        TableDesign.TypeInfo ti = TableDesign.typeInfo(t);
        boolean len = ti == null ? c.length != null : ti.hasLength();
        boolean sc = ti == null ? c.scale != null : ti.hasScale();
        if (len && c.length != null) {
            return t + "(" + c.length + (sc && c.scale != null ? ", " + c.scale : "") + ")";
        }
        return t;
    }

    private String columnDef(Column c) {
        StringBuilder sb = new StringBuilder(id(c.name)).append(' ').append(typeSql(c));
        if (c.identity) {
            sb.append(" GENERATED BY DEFAULT AS IDENTITY");
        } else if (c.defaultExpr != null && !c.defaultExpr.isBlank()) {
            sb.append(" DEFAULT ").append(c.defaultExpr.trim());
        }
        if (!c.nullable && !c.primaryKey && !c.identity) {
            sb.append(" NOT NULL");
        }
        return sb.toString();
    }

    /** Aumentar tamanho/precisão do mesmo tipo, ou ir para um inteiro maior, nunca perde dados. */
    static boolean isWidening(Column from, Column to) {
        String a = from.type == null ? "" : from.type.trim().toUpperCase(Locale.ROOT);
        String b = to.type == null ? "" : to.type.trim().toUpperCase(Locale.ROOT);
        List<String> ints = List.of("TINYINT", "SMALLINT", "INTEGER", "BIGINT");
        if (ints.contains(a) && ints.contains(b)) {
            return ints.indexOf(b) >= ints.indexOf(a);
        }
        if (ints.contains(a) && (b.equals("DECIMAL") || b.equals("NUMERIC")) && to.length != null && to.length >= 19) {
            return true;
        }
        if (!a.equals(b)) {
            return a.equals("CHAR") && b.equals("VARCHAR") && (to.length == null || from.length == null || to.length >= from.length)
                    || a.equals("VARCHAR") && b.equals("CLOB") || a.equals("REAL") && b.equals("DOUBLE PRECISION");
        }
        boolean lenOk = to.length == null || (from.length != null && to.length >= from.length);
        int fs = from.scale == null ? 0 : from.scale;
        int ts = to.scale == null ? 0 : to.scale;
        boolean intDigitsOk = from.length == null || to.length == null || (to.length - ts) >= (from.length - fs);
        return lenOk && ts >= fs && intDigitsOk;
    }

    private static boolean sameType(Column a, Column b) {
        return typeSql(a).replaceAll("\\s+", " ").equalsIgnoreCase(typeSql(b).replaceAll("\\s+", " "));
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // =====================================================================
    // Validação
    // =====================================================================

    private void validate() {
        String tn = normalizeName(d.name, casing);
        if (tn.isEmpty()) {
            errors.add("Informe o nome da tabela.");
        } else if (!validIdentifier(tn)) {
            errors.add("Nome da tabela inválido: use letras, números e _ (começando por letra).");
        } else {
            boolean renamed = d.isNew() || !tn.equals(d.originalName);
            if (renamed && existingTables.stream().anyMatch(t -> t.equalsIgnoreCase(tn))) {
                errors.add("Já existe uma tabela chamada " + tn + " neste schema.");
            }
            if (SqlLexer.KEYWORDS.contains(tn.toUpperCase(Locale.ROOT))) {
                warnings.add("\"" + tn + "\" é palavra reservada do SQL: o sistema precisará escrever o nome entre aspas.");
            }
        }
        List<Column> active = d.activeColumns();
        if (active.isEmpty()) {
            errors.add("A tabela precisa de pelo menos uma coluna.");
        }
        Set<String> seen = new HashSet<>();
        for (Column c : active) {
            String n = c.isNew() || !Objects.equals(c.name, c.original.name) ? normalizeName(c.name, casing) : c.name;
            if (n.isEmpty()) {
                errors.add("Há uma coluna sem nome.");
                continue;
            }
            if ((c.isNew() || !n.equals(c.original.name)) && !validIdentifier(n)) {
                errors.add("Nome de coluna inválido: " + n + " (use letras, números e _).");
            }
            if (!seen.add(n.toUpperCase(Locale.ROOT))) {
                errors.add("Coluna repetida: " + n + ".");
            }
            if (c.type == null || c.type.isBlank()) {
                errors.add("Informe o tipo da coluna " + n + ".");
            }
            if (c.length != null && c.length <= 0) {
                errors.add("Tamanho inválido na coluna " + n + ".");
            }
            if (c.scale != null && (c.scale < 0 || (c.length != null && c.scale > c.length))) {
                errors.add("Casas decimais inválidas na coluna " + n + " (devem ser entre 0 e o tamanho).");
            }
            if (c.identity && !isIntegerType(c)) {
                errors.add("Auto incremento só em INTEGER/BIGINT/SMALLINT (coluna " + n + ").");
            }
            if (!c.isNew() && c.identity != c.original.identity) {
                errors.add("Ligar/desligar o auto incremento de uma coluna existente não é suportado aqui (coluna " + n
                        + "). Crie uma coluna nova ou use o editor SQL.");
            }
            if (SqlLexer.KEYWORDS.contains(n.toUpperCase(Locale.ROOT)) && (c.isNew() || !n.equals(c.original.name))) {
                warnings.add("\"" + n + "\" é palavra reservada do SQL: o sistema precisará escrever o nome entre aspas.");
            }
        }
        Set<String> colNames = new HashSet<>();
        for (Column c : active) {
            colNames.add(currentName(c).toUpperCase(Locale.ROOT));
        }
        Set<String> idxNames = new HashSet<>();
        for (Index i : d.indexes) {
            if (i.dropped || i.automatic) {
                continue;
            }
            String n = normalizeName(i.name, casing);
            if (n.isEmpty()) {
                errors.add("Há um índice sem nome.");
            } else if (!idxNames.add(n.toUpperCase(Locale.ROOT))) {
                errors.add("Índice repetido: " + n + ".");
            }
            if (i.columns.isEmpty()) {
                errors.add("O índice " + n + " não tem colunas.");
            }
            for (String col : i.columns) {
                if (!colNames.contains(col.toUpperCase(Locale.ROOT))) {
                    errors.add("O índice " + n + " usa a coluna " + col + ", que não existe (ou será removida).");
                }
            }
        }
        for (ForeignKey f : d.foreignKeys) {
            if (f.dropped) {
                continue;
            }
            String n = normalizeName(f.name, casing);
            if (n.isEmpty()) {
                errors.add("Há uma chave estrangeira sem nome.");
            }
            if (f.refTable == null || f.refTable.isBlank()) {
                errors.add("Escolha a tabela referenciada pela chave " + n + ".");
            }
            if (f.columns.isEmpty() || f.columns.size() != f.refColumns.size()) {
                errors.add("A chave " + n + " precisa do mesmo número de colunas dos dois lados.");
            }
            for (String col : f.columns) {
                if (!colNames.contains(col.toUpperCase(Locale.ROOT))) {
                    errors.add("A chave " + n + " usa a coluna " + col + ", que não existe (ou será removida).");
                }
            }
        }
    }

    private static boolean isIntegerType(Column c) {
        String t = c.type == null ? "" : c.type.trim().toUpperCase(Locale.ROOT);
        return t.equals("INTEGER") || t.equals("BIGINT") || t.equals("SMALLINT") || t.equals("TINYINT") || t.equals("INT");
    }

    /** Nome que a coluna terá depois das alterações. */
    private String currentName(Column c) {
        if (!c.isNew() && Objects.equals(c.name, c.original.name)) {
            return c.name;
        }
        return normalizeName(c.name, casing);
    }

    // =====================================================================
    // CREATE TABLE
    // =====================================================================

    private void buildCreate() {
        String tn = normalizeName(d.name, casing);
        List<Column> cols = d.activeColumns();
        List<String> lines = new ArrayList<>();
        for (Column c : cols) {
            Column x = c.copy();
            x.name = currentName(c);
            lines.add(columnDef(x));
        }
        List<String> pk = cols.stream().filter(c -> c.primaryKey).map(this::currentName).toList();
        if (!pk.isEmpty()) {
            lines.add("PRIMARY KEY (" + String.join(", ", pk.stream().map(this::id).toList()) + ")");
        } else {
            warnings.add("A tabela não tem chave primária: o sistema e o H2 Studio vão identificar as linhas pelo _ROWID_.");
        }
        for (ForeignKey f : d.foreignKeys) {
            if (!f.dropped) {
                lines.add(fkClause(f));
            }
        }
        sql.add("CREATE TABLE " + table(tn) + " (\n    " + String.join(",\n    ", lines) + "\n)");
        for (Index i : d.indexes) {
            if (!i.dropped && !i.automatic) {
                sql.add(createIndex(i, tn));
            }
        }
        comments(tn, true);
    }

    private String fkClause(ForeignKey f) {
        return "CONSTRAINT " + id(normalizeName(f.name, casing)) + " FOREIGN KEY ("
                + String.join(", ", f.columns.stream().map(this::id).toList()) + ") REFERENCES "
                + tableRef(f.refSchema, f.refTable) + " (" + String.join(", ", f.refColumns.stream().map(this::id).toList()) + ")"
                + rule(" ON DELETE ", f.onDelete) + rule(" ON UPDATE ", f.onUpdate);
    }

    private static String rule(String prefix, String r) {
        return r == null || r.isBlank() || r.equalsIgnoreCase("RESTRICT") ? "" : prefix + r.toUpperCase(Locale.ROOT);
    }

    private String createIndex(Index i, String tableName) {
        return "CREATE " + (i.unique ? "UNIQUE " : "") + "INDEX " + qualifiedIndex(normalizeName(i.name, casing)) + " ON "
                + table(tableName) + " (" + String.join(", ", i.columns.stream().map(this::id).toList()) + ")";
    }

    private void comments(String tn, boolean create) {
        String tc = norm(d.comment);
        if (create ? tc != null : !Objects.equals(tc, norm(d.originalComment))) {
            sql.add("COMMENT ON TABLE " + table(tn) + " IS " + (tc == null ? "''" : lit(tc)));
        }
        for (Column c : d.activeColumns()) {
            String cc = norm(c.comment);
            String oc = c.isNew() ? null : norm(c.original.comment);
            if (create || c.isNew() ? cc != null : !Objects.equals(cc, oc)) {
                sql.add("COMMENT ON COLUMN " + table(tn) + "." + id(currentName(c)) + " IS " + (cc == null ? "''" : lit(cc)));
            }
        }
    }

    // =====================================================================
    // ALTER TABLE
    // =====================================================================

    private void buildAlter() {
        String tn = d.originalName;
        String newName = normalizeName(d.name, casing);
        if (!newName.equals(d.originalName) && !d.name.trim().equals(d.originalName)) {
            sql.add("ALTER TABLE " + table(tn) + " RENAME TO " + id(newName));
            tn = newName;
        }
        final String t = tn;
        // nomes originais → novos (para comparar índices e chaves depois de renomear colunas)
        Map<String, String> renamed = new HashMap<>();
        for (Column c : d.columns) {
            if (!c.isNew() && !c.dropped) {
                renamed.put(c.original.name, currentName(c));
            }
        }

        // 1) remove chaves estrangeiras alteradas/removidas
        List<ForeignKey> fkToAdd = new ArrayList<>();
        for (ForeignKey f : d.foreignKeys) {
            boolean changed = !f.isNew() && !sameFk(f, renamed);
            if (!f.isNew() && (f.dropped || changed)) {
                sql.add("ALTER TABLE " + table(t) + " DROP CONSTRAINT " + qualifiedIndex(f.original.name));
            }
            if (!f.dropped && (f.isNew() || changed)) {
                fkToAdd.add(f);
            }
        }
        // 2) remove índices alterados/removidos
        List<Index> idxToAdd = new ArrayList<>();
        for (Index i : d.indexes) {
            if (i.automatic) {
                continue;
            }
            boolean changed = !i.isNew() && !sameIndex(i, renamed);
            if (!i.isNew() && (i.dropped || changed)) {
                sql.add("DROP INDEX " + qualifiedIndex(i.original.name));
            }
            if (!i.dropped && (i.isNew() || changed)) {
                idxToAdd.add(i);
            }
        }
        // 3) chave primária
        List<String> oldPk = d.columns.stream().filter(c -> !c.isNew() && c.original.primaryKey)
                .map(c -> c.original.name).toList();
        List<String> newPk = d.activeColumns().stream().filter(c -> c.primaryKey).map(this::currentName).toList();
        List<String> oldPkRenamed = oldPk.stream().map(n -> renamed.getOrDefault(n, n)).toList();
        boolean pkChanged = !oldPkRenamed.equals(newPk) || d.columns.stream().anyMatch(c -> c.dropped && !c.isNew() && c.original.primaryKey);
        if (pkChanged && !oldPk.isEmpty()) {
            sql.add("ALTER TABLE " + table(t) + " DROP PRIMARY KEY");
            if (newPk.isEmpty()) {
                warnings.add("A chave primária será removida.");
            }
        }
        // 4) colunas existentes
        for (Column c : d.columns) {
            if (c.isNew()) {
                continue;
            }
            if (c.dropped) {
                sql.add("ALTER TABLE " + table(t) + " DROP COLUMN " + id(c.original.name));
                warnings.add("A coluna " + c.original.name + " será REMOVIDA com todos os seus dados.");
                continue;
            }
            String cur = c.original.name;
            String nn = currentName(c);
            if (!nn.equals(cur)) {
                sql.add("ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + " RENAME TO " + id(nn));
                cur = nn;
            }
            if (!sameType(c, c.original)) {
                sql.add("ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + " SET DATA TYPE " + typeSql(c));
                if (!isWidening(c.original, c)) {
                    warnings.add("Mudança de tipo em " + cur + " (" + typeSql(c.original) + " → " + typeSql(c)
                            + "): falha se algum valor não couber no novo tipo.");
                }
            }
            if (!c.identity && !Objects.equals(norm(c.defaultExpr), norm(c.original.defaultExpr))) {
                sql.add("ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur)
                        + (norm(c.defaultExpr) == null ? " DROP DEFAULT" : " SET DEFAULT " + c.defaultExpr.trim()));
            }
            boolean wantNotNull = !c.nullable || c.primaryKey;
            boolean hadNotNull = !c.original.nullable || c.original.primaryKey;
            if (wantNotNull != hadNotNull && !(c.primaryKey && !c.original.primaryKey)) {
                sql.add("ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + (wantNotNull ? " SET NOT NULL" : " DROP NOT NULL"));
                if (wantNotNull) {
                    warnings.add("A coluna " + cur + " passará a ser obrigatória: falha se já existir linha com ela vazia.");
                }
            }
        }
        // 5) colunas novas
        for (Column c : d.activeColumns()) {
            if (!c.isNew()) {
                continue;
            }
            Column x = c.copy();
            x.name = currentName(c);
            sql.add("ALTER TABLE " + table(t) + " ADD COLUMN " + columnDef(x));
            if (!c.nullable && !c.identity && norm(c.defaultExpr) == null && !c.primaryKey) {
                warnings.add("A nova coluna " + x.name + " é obrigatória e não tem valor padrão: só funciona se a tabela estiver vazia.");
            }
        }
        // 6) nova chave primária
        if (pkChanged && !newPk.isEmpty()) {
            sql.add("ALTER TABLE " + table(t) + " ADD PRIMARY KEY (" + String.join(", ", newPk.stream().map(this::id).toList()) + ")");
        }
        // 7) índices e chaves
        for (Index i : idxToAdd) {
            sql.add(createIndex(i, t));
        }
        for (ForeignKey f : fkToAdd) {
            sql.add("ALTER TABLE " + table(t) + " ADD " + fkClause(f));
        }
        // 8) comentários
        comments(t, false);
    }

    private boolean sameIndex(Index i, Map<String, String> renamed) {
        List<String> origCols = i.original.columns.stream().map(n -> renamed.getOrDefault(n, n)).toList();
        return normalizeName(i.name, casing).equals(i.original.name) || i.name.equals(i.original.name)
                ? origCols.equals(i.columns) && i.unique == i.original.unique
                : false;
    }

    private boolean sameFk(ForeignKey f, Map<String, String> renamed) {
        List<String> origCols = f.original.columns.stream().map(n -> renamed.getOrDefault(n, n)).toList();
        boolean sameName = f.name.equals(f.original.name) || normalizeName(f.name, casing).equals(f.original.name);
        return sameName && origCols.equals(f.columns) && Objects.equals(f.refTable, f.original.refTable)
                && Objects.equals(f.refSchema, f.original.refSchema) && f.refColumns.equals(f.original.refColumns)
                && ruleEq(f.onDelete, f.original.onDelete) && ruleEq(f.onUpdate, f.original.onUpdate);
    }

    private static boolean ruleEq(String a, String b) {
        String x = a == null || a.equalsIgnoreCase("NO ACTION") ? "RESTRICT" : a.toUpperCase(Locale.ROOT);
        String y = b == null || b.equalsIgnoreCase("NO ACTION") ? "RESTRICT" : b.toUpperCase(Locale.ROOT);
        return x.equals(y);
    }

    /** DROP TABLE (com CASCADE remove também as chaves de outras tabelas que apontam para ela). */
    public static String dropTable(String schema, String table, boolean cascade, SqlCompletion.Casing casing, String defaultSchema) {
        boolean def = schema == null || schema.equals(defaultSchema);
        String t = def ? SqlCompletion.quoteIfNeeded(table, casing)
                : SqlCompletion.quoteIfNeeded(schema, casing) + "." + SqlCompletion.quoteIfNeeded(table, casing);
        return "DROP TABLE " + t + (cascade ? " CASCADE" : "");
    }
}
