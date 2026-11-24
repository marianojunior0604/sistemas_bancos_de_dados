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
 * <p>
 * A partir da versão 1.6 gera também para PostgreSQL, MySQL/MariaDB, Firebird, SQL Server e
 * SQLite, cada um com a sua sintaxe (auto incremento, renomear, mudar tipo, comentários). No
 * SQLite, mudanças que o ALTER TABLE não suporta recriam a tabela copiando os dados.
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

        /** Script no padrão do banco (Firebird: gatilhos entre SET TERM, como no isql/IBExpert). */
        public String script(DbType type) {
            if (type == DbType.SQLSERVER && !statements.isEmpty()) {
                return String.join("\nGO\n\n", statements) + "\nGO\n";
            }
            if (type == null || !type.isFirebird() || statements.stream().noneMatch(DdlBuilder::isBlock)) {
                return script();
            }
            StringBuilder sb = new StringBuilder();
            for (String st : statements) {
                if (isBlock(st)) {
                    sb.append("SET TERM ^ ;\n\n").append(st).append("^\n\nSET TERM ; ^\n\n");
                } else {
                    sb.append(st).append(";\n\n");
                }
            }
            return sb.toString().stripTrailing() + "\n";
        }
    }

    static boolean isBlock(String st) {
        return st.startsWith("CREATE TRIGGER") || st.startsWith("CREATE OR ALTER TRIGGER");
    }

    /** O banco desfaz CREATE/ALTER numa transação (então dá para aplicar tudo ou nada). */
    public static boolean transactionalDdl(DbType type) {
        return type == DbType.POSTGRESQL || type == DbType.SQLSERVER || type == DbType.SQLITE;
    }

    private final TableDesign d;
    private final DbType type;
    private final SqlCompletion.Casing casing;
    private final String defaultSchema;
    private final Set<String> existingTables;
    private final List<String> sql = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private DdlBuilder(TableDesign d, SqlCompletion.Casing casing, String defaultSchema, Set<String> existingTables,
                       DbType type) {
        this.d = d;
        this.type = type == null ? DbType.H2 : type;
        this.casing = casing;
        this.defaultSchema = defaultSchema;
        this.existingTables = existingTables;
    }

    /**
     * @param existingTables nomes das tabelas já existentes no schema (para avisar nome repetido)
     */
    public static Plan build(TableDesign d, SqlCompletion.Casing casing, String defaultSchema, Set<String> existingTables) {
        return build(d, casing, defaultSchema, existingTables, DbType.H2);
    }

    /** Idem para o banco informado. */
    public static Plan build(TableDesign d, SqlCompletion.Casing casing, String defaultSchema, Set<String> existingTables,
                             DbType type) {
        DdlBuilder b = new DdlBuilder(d, casing, defaultSchema, existingTables == null ? Set.of() : existingTables, type);
        b.validate();
        if (b.errors.isEmpty()) {
            if (d.isNew()) {
                b.buildCreate();
            } else if (b.type == DbType.SQLITE && b.sqliteNeedsRebuild()) {
                b.buildSqliteRebuild();
            } else if (b.type == DbType.H2) {
                b.buildAlter();
            } else {
                b.buildAlterOther();
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
        return SqlCompletion.quoteIfNeeded(name, casing, type);
    }

    private String table(String name) {
        boolean def = d.schema == null || d.schema.isEmpty() || d.schema.equals(defaultSchema);
        return def ? id(name) : id(d.schema) + "." + id(name);
    }

    private String tableRef(String schema, String name) {
        boolean def = schema == null || schema.isEmpty() || schema.equals(defaultSchema);
        return def ? id(name) : id(schema) + "." + id(name);
    }

    /** Nome de objeto do Firebird 2.5 (no máximo 31 caracteres). */
    private String fbName(String n) {
        String u = n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_$]", "_");
        return u.length() > 31 ? u.substring(0, 31) : u;
    }

    private String pkConstraintName(String tableName) {
        String n = normalizeName("pk_" + tableName, casing);
        return type.isFirebird() ? fbName(n) : n;
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

    /** Tipo com tamanho, usando a lista de tipos do banco. */
    public static String typeSql(Column c, DbType type) {
        if (type == null || type == DbType.H2) {
            return typeSql(c);
        }
        String t = c.type == null ? "" : c.type.trim().toUpperCase(Locale.ROOT);
        TableDesign.TypeInfo ti = TableDesign.typeInfo(t, type);
        boolean len = ti == null ? c.length != null && !t.contains("(") : ti.hasLength();
        boolean sc = ti == null ? c.scale != null : ti.hasScale();
        if (len && c.length != null) {
            return t + "(" + c.length + (sc && c.scale != null ? ", " + c.scale : "") + ")";
        }
        return t;
    }

    private String columnDef(Column c) {
        if (type != DbType.H2) {
            return columnDefOther(c, false);
        }
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

    /**
     * Definição de coluna nos outros bancos. {@code inlinePk}: SQLite com auto incremento
     * (INTEGER PRIMARY KEY AUTOINCREMENT fica na própria coluna).
     */
    private String columnDefOther(Column c, boolean forAlterMysql) {
        StringBuilder sb = new StringBuilder(id(c.name)).append(' ').append(typeSql(c, type));
        boolean notNull = !c.nullable || c.primaryKey;
        String def = norm(c.defaultExpr);
        if (c.identity) {
            switch (type) {
                case POSTGRESQL, FIREBIRD -> {
                    sb.append(" GENERATED BY DEFAULT AS IDENTITY");
                    notNull = false; // identidade já é obrigatória
                }
                case SQLSERVER -> sb.append(" IDENTITY(1,1)");
                case SQLITE -> {
                    sb.append(" PRIMARY KEY AUTOINCREMENT");
                    notNull = false;
                }
                default -> { } // MySQL: AUTO_INCREMENT depois do NOT NULL; Firebird 2.5: gatilho
            }
            def = null;
        }
        if (def != null) {
            sb.append(" DEFAULT ").append(def);
        }
        if (notNull) {
            sb.append(" NOT NULL");
        }
        if (c.identity && (type == DbType.MYSQL || type == DbType.MARIADB)) {
            sb.append(" AUTO_INCREMENT");
        }
        if ((type == DbType.MYSQL || type == DbType.MARIADB) && norm(c.comment) != null) {
            sb.append(" COMMENT ").append(lit(c.comment.trim()));
        }
        return sb.toString();
    }

    private boolean sameType(Column a, Column b) {
        if (type == DbType.H2) {
            return typeSql(a).replaceAll("\\s+", " ").equalsIgnoreCase(typeSql(b).replaceAll("\\s+", " "));
        }
        return typeSql(a, type).replaceAll("\\s+", " ").equalsIgnoreCase(typeSql(b, type).replaceAll("\\s+", " "));
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // =====================================================================
    // Validação
    // =====================================================================

    private void validate() {
        validateBase();
        validateDialect();
    }

    private void validateBase() {
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

    /** Regras que dependem do banco. */
    private void validateDialect() {
        if (type == DbType.H2) {
            return;
        }
        String tn = normalizeName(d.name, casing);
        boolean renamed = !d.isNew() && !tn.equals(d.originalName) && !d.name.trim().equals(d.originalName);
        if (renamed && type.isFirebird()) {
            errors.add("O Firebird não permite renomear tabelas. Crie uma tabela nova e copie os dados pelo editor SQL.");
        }
        if (type == DbType.FIREBIRD_25) {
            for (String n : allNames()) {
                if (n.length() > 31) {
                    errors.add("No Firebird 2.5 os nomes têm no máximo 31 caracteres: " + n + ".");
                }
            }
        }
        long pkCount = d.activeColumns().stream().filter(c -> c.primaryKey).count();
        for (Column c : d.activeColumns()) {
            String n = currentName(c);
            if (c.identity && type == DbType.SQLITE && (!c.primaryKey || pkCount != 1
                    || !"INTEGER".equalsIgnoreCase(c.type == null ? "" : c.type.trim()))) {
                errors.add("No SQLite o auto incremento só existe em INTEGER chave primária sozinha (coluna " + n + ").");
            }
            if (c.identity && (type == DbType.MYSQL || type == DbType.MARIADB) && !c.primaryKey) {
                errors.add("No MySQL o auto incremento precisa ser a chave primária (coluna " + n + ").");
            }
            if (!c.isNew() && type == DbType.FIREBIRD_25) {
                boolean wantNotNull = !c.nullable || c.primaryKey;
                boolean hadNotNull = !c.original.nullable || c.original.primaryKey;
                if (wantNotNull != hadNotNull) {
                    errors.add("O Firebird 2.5 não muda a obrigatoriedade de uma coluna existente (" + n
                            + "). Crie uma coluna nova ou atualize para o Firebird 3.");
                }
            }
        }
        boolean anyComment = norm(d.comment) != null || d.activeColumns().stream().anyMatch(c -> norm(c.comment) != null);
        boolean commentChanged = !Objects.equals(norm(d.comment), norm(d.originalComment))
                || d.activeColumns().stream().anyMatch(c -> !Objects.equals(norm(c.comment),
                c.isNew() ? null : norm(c.original.comment)));
        if ((type == DbType.SQLSERVER || type == DbType.SQLITE) && anyComment && commentChanged) {
            warnings.add("Comentários não são gravados no " + type.shortLabel()
                    + (type == DbType.SQLSERVER ? " por aqui (use as propriedades estendidas no SSMS)." : " (o banco não tem comentários)."));
        }
    }

    private List<String> allNames() {
        List<String> out = new ArrayList<>();
        out.add(normalizeName(d.name, casing));
        d.activeColumns().forEach(c -> out.add(currentName(c)));
        d.indexes.stream().filter(i -> !i.dropped && !i.automatic).forEach(i -> out.add(normalizeName(i.name, casing)));
        d.foreignKeys.stream().filter(f -> !f.dropped).forEach(f -> out.add(normalizeName(f.name, casing)));
        return out;
    }

    private static boolean isIntegerType(Column c) {
        String t = c.type == null ? "" : c.type.trim().toUpperCase(Locale.ROOT);
        return t.equals("INTEGER") || t.equals("BIGINT") || t.equals("SMALLINT") || t.equals("TINYINT") || t.equals("INT")
                || t.equals("MEDIUMINT");
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
        boolean inlinePk = type == DbType.SQLITE && cols.stream().anyMatch(c -> c.identity);
        if (!pk.isEmpty() && !inlinePk) {
            String cols2 = String.join(", ", pk.stream().map(this::id).toList());
            lines.add(type == DbType.SQLSERVER || type.isFirebird()
                    ? "CONSTRAINT " + id(pkConstraintName(tn)) + " PRIMARY KEY (" + cols2 + ")"
                    : "PRIMARY KEY (" + cols2 + ")");
        } else if (pk.isEmpty()) {
            warnings.add(type == DbType.H2
                    ? "A tabela não tem chave primária: o sistema e o H2 Studio vão identificar as linhas pelo _ROWID_."
                    : "A tabela não tem chave primária: a grade do H2 Studio não vai conseguir editar as linhas dela.");
        }
        for (ForeignKey f : d.foreignKeys) {
            if (!f.dropped) {
                lines.add(fkClause(f));
            }
        }
        sql.add("CREATE TABLE " + table(tn) + " (\n    " + String.join(",\n    ", lines) + "\n)");
        for (Column c : cols) {
            if (c.identity && type == DbType.FIREBIRD_25) {
                firebird25Identity(tn, currentName(c));
            }
        }
        for (Index i : d.indexes) {
            if (!i.dropped && !i.automatic) {
                sql.add(createIndex(i, tn));
            }
        }
        comments(tn, true);
    }

    /** Firebird 2.5 não tem IDENTITY: generator + gatilho BEFORE INSERT, o padrão dos sistemas da época. */
    private void firebird25Identity(String tableName, String column) {
        String gen = fbName("GEN_" + tableName + "_" + column);
        String trg = fbName("TG_" + tableName + "_BI");
        sql.add("CREATE GENERATOR " + id(gen));
        sql.add("CREATE TRIGGER " + id(trg) + " FOR " + table(tableName) + " ACTIVE BEFORE INSERT POSITION 0 AS\n"
                + "BEGIN\n  IF (NEW." + id(column) + " IS NULL) THEN NEW." + id(column) + " = GEN_ID(" + id(gen) + ", 1);\nEND");
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
        String n = normalizeName(i.name, casing);
        return "CREATE " + (i.unique ? "UNIQUE " : "") + "INDEX " + (type == DbType.H2 ? qualifiedIndex(n) : id(n)) + " ON "
                + table(tableName) + " (" + String.join(", ", i.columns.stream().map(this::id).toList()) + ")";
    }

    private void comments(String tn, boolean create) {
        if (type == DbType.SQLSERVER || type == DbType.SQLITE) {
            return; // avisado na validação
        }
        String tc = norm(d.comment);
        if (type == DbType.MYSQL || type == DbType.MARIADB) {
            // comentários de coluna vão na própria definição (CREATE / CHANGE COLUMN)
            if (create ? tc != null : !Objects.equals(tc, norm(d.originalComment))) {
                sql.add("ALTER TABLE " + table(tn) + " COMMENT = " + (tc == null ? "''" : lit(tc)));
            }
            return;
        }
        String empty = type == DbType.H2 ? "''" : "NULL";
        if (create ? tc != null : !Objects.equals(tc, norm(d.originalComment))) {
            sql.add("COMMENT ON TABLE " + table(tn) + " IS " + (tc == null ? empty : lit(tc)));
        }
        for (Column c : d.activeColumns()) {
            String cc = norm(c.comment);
            String oc = c.isNew() ? null : norm(c.original.comment);
            if (create || c.isNew() ? cc != null : !Objects.equals(cc, oc)) {
                sql.add("COMMENT ON COLUMN " + table(tn) + "." + id(currentName(c)) + " IS " + (cc == null ? empty : lit(cc)));
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

    // =====================================================================
    // ALTER TABLE nos outros bancos
    // =====================================================================

    private boolean mysql() {
        return type == DbType.MYSQL || type == DbType.MARIADB;
    }

    /** [schema].[tabela] sem aspas extras, para o sp_rename do SQL Server. */
    private String mssqlName(String tableName, String column) {
        String base = (d.schema == null || d.schema.isEmpty() ? "" : DbType.SQLSERVER.quote(d.schema) + ".")
                + DbType.SQLSERVER.quote(tableName);
        return column == null ? base : base + "." + DbType.SQLSERVER.quote(column);
    }

    /** O SQL Server guarda o DEFAULT como restrição com nome gerado: acha e remove. */
    private String mssqlDropDefault(String tableName, String column) {
        String obj = mssqlName(tableName, null).replace("'", "''");
        return "DECLARE @df sysname = (SELECT d.name FROM sys.default_constraints d JOIN sys.columns c "
                + "ON c.object_id = d.parent_object_id AND c.column_id = d.parent_column_id "
                + "WHERE d.parent_object_id = OBJECT_ID(N'" + obj + "') AND c.name = N'" + column.replace("'", "''") + "'); "
                + "IF @df IS NOT NULL EXEC(N'ALTER TABLE " + obj.replace("'", "''") + " DROP CONSTRAINT [' + @df + N']')";
    }

    private String dropFk(String t, String name) {
        return "ALTER TABLE " + table(t) + (mysql() ? " DROP FOREIGN KEY " : " DROP CONSTRAINT ") + id(name);
    }

    private String dropIndex(String t, String name) {
        return switch (type) {
            case POSTGRESQL -> "DROP INDEX " + (d.schema == null || d.schema.isEmpty() ? "" : id(d.schema) + ".") + id(name);
            case MYSQL, MARIADB, SQLSERVER -> "DROP INDEX " + id(name) + " ON " + table(t);
            default -> "DROP INDEX " + id(name);
        };
    }

    private void buildAlterOther() {
        String tn = d.originalName;
        String newName = normalizeName(d.name, casing);
        if (!newName.equals(d.originalName) && !d.name.trim().equals(d.originalName)) {
            sql.add(type == DbType.SQLSERVER
                    ? "EXEC sp_rename N'" + mssqlName(tn, null).replace("'", "''") + "', N'" + newName.replace("'", "''") + "'"
                    : "ALTER TABLE " + table(tn) + " RENAME TO " + id(newName));
            tn = newName;
        }
        final String t = tn;
        Map<String, String> renamed = new HashMap<>();
        for (Column c : d.columns) {
            if (!c.isNew() && !c.dropped) {
                renamed.put(c.original.name, currentName(c));
            }
        }
        // 1) chaves estrangeiras alteradas/removidas
        List<ForeignKey> fkToAdd = new ArrayList<>();
        for (ForeignKey f : d.foreignKeys) {
            boolean changed = !f.isNew() && !sameFk(f, renamed);
            if (!f.isNew() && (f.dropped || changed)) {
                sql.add(dropFk(t, f.original.name));
            }
            if (!f.dropped && (f.isNew() || changed)) {
                fkToAdd.add(f);
            }
        }
        // 2) índices alterados/removidos
        List<Index> idxToAdd = new ArrayList<>();
        for (Index i : d.indexes) {
            if (i.automatic) {
                continue;
            }
            boolean changed = !i.isNew() && !sameIndex(i, renamed);
            if (!i.isNew() && (i.dropped || changed)) {
                sql.add(dropIndex(t, i.original.name));
            }
            if (!i.dropped && (i.isNew() || changed)) {
                idxToAdd.add(i);
            }
        }
        // 3) chave primária
        List<String> oldPk = d.columns.stream().filter(c -> !c.isNew() && c.original.primaryKey).map(c -> c.original.name).toList();
        List<String> newPk = d.activeColumns().stream().filter(c -> c.primaryKey).map(this::currentName).toList();
        List<String> oldPkRenamed = oldPk.stream().map(n -> renamed.getOrDefault(n, n)).toList();
        boolean pkChanged = !oldPkRenamed.equals(newPk)
                || d.columns.stream().anyMatch(c -> c.dropped && !c.isNew() && c.original.primaryKey);
        if (pkChanged && !oldPk.isEmpty()) {
            if (mysql()) {
                sql.add("ALTER TABLE " + table(t) + " DROP PRIMARY KEY");
            } else if (d.pkName == null || d.pkName.isBlank()) {
                errors.add("Não foi possível descobrir o nome da chave primária atual para removê-la.");
                return;
            } else {
                sql.add("ALTER TABLE " + table(t) + " DROP CONSTRAINT " + id(d.pkName));
            }
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
                if (type == DbType.SQLSERVER) {
                    sql.add(mssqlDropDefault(t, c.original.name));
                }
                sql.add("ALTER TABLE " + table(t) + (type.isFirebird() ? " DROP " : " DROP COLUMN ") + id(c.original.name));
                warnings.add("A coluna " + c.original.name + " será REMOVIDA com todos os seus dados.");
                continue;
            }
            String cur = c.original.name;
            String nn = currentName(c);
            Column x = c.copy();
            x.name = nn;
            boolean typeChanged = !sameType(c, c.original);
            boolean defChanged = !c.identity && !Objects.equals(norm(c.defaultExpr), norm(c.original.defaultExpr));
            boolean wantNotNull = !c.nullable || c.primaryKey;
            boolean hadNotNull = !c.original.nullable || c.original.primaryKey;
            boolean nullChanged = wantNotNull != hadNotNull;
            boolean commentChanged = !Objects.equals(norm(c.comment), norm(c.original.comment));
            if (typeChanged && !isWidening(c.original, c)) {
                warnings.add("Mudança de tipo em " + nn + " (" + typeSql(c.original, type) + " → " + typeSql(c, type)
                        + "): falha se algum valor não couber no novo tipo.");
            }
            if (nullChanged && wantNotNull) {
                warnings.add("A coluna " + nn + " passará a ser obrigatória: falha se já existir linha com ela vazia.");
            }
            if (mysql()) {
                // o MySQL altera a coluna inteira de uma vez (nome, tipo, padrão, obrigatoriedade e comentário)
                if (!nn.equals(cur) || typeChanged || defChanged || nullChanged || commentChanged) {
                    sql.add("ALTER TABLE " + table(t) + " CHANGE COLUMN " + id(cur) + " " + columnDefOther(x, true));
                }
                continue;
            }
            if (!nn.equals(cur)) {
                sql.add(switch (type) {
                    case SQLSERVER -> "EXEC sp_rename N'" + mssqlName(t, cur).replace("'", "''") + "', N'"
                            + nn.replace("'", "''") + "', N'COLUMN'";
                    case FIREBIRD, FIREBIRD_25 -> "ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + " TO " + id(nn);
                    default -> "ALTER TABLE " + table(t) + " RENAME COLUMN " + id(cur) + " TO " + id(nn);
                });
                cur = nn;
            }
            if (type == DbType.SQLSERVER) {
                if (typeChanged || nullChanged) {
                    sql.add("ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + " " + typeSql(c, type)
                            + (wantNotNull ? " NOT NULL" : " NULL"));
                }
                if (defChanged) {
                    sql.add(mssqlDropDefault(t, cur));
                    if (norm(c.defaultExpr) != null) {
                        sql.add("ALTER TABLE " + table(t) + " ADD CONSTRAINT " + id(normalizeName("df_" + t + "_" + cur, casing))
                                + " DEFAULT " + c.defaultExpr.trim() + " FOR " + id(cur));
                    }
                }
                continue;
            }
            if (typeChanged) {
                sql.add(type == DbType.POSTGRESQL
                        ? "ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + " TYPE " + typeSql(c, type)
                        + " USING " + id(cur) + "::" + typeSql(c, type)
                        : "ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + " TYPE " + typeSql(c, type));
            }
            if (defChanged) {
                sql.add("ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur)
                        + (norm(c.defaultExpr) == null ? " DROP DEFAULT" : " SET DEFAULT " + c.defaultExpr.trim()));
            }
            // PostgreSQL: ADD PRIMARY KEY já torna a coluna obrigatória; Firebird exige antes
            boolean becomesPk = c.primaryKey && !c.original.primaryKey;
            if (nullChanged && !(becomesPk && type == DbType.POSTGRESQL)) {
                sql.add("ALTER TABLE " + table(t) + " ALTER COLUMN " + id(cur) + (wantNotNull ? " SET NOT NULL" : " DROP NOT NULL"));
            }
        }
        // 5) colunas novas
        for (Column c : d.activeColumns()) {
            if (!c.isNew()) {
                continue;
            }
            Column x = c.copy();
            x.name = currentName(c);
            boolean keyword = type == DbType.POSTGRESQL || mysql() || type == DbType.SQLITE;
            sql.add("ALTER TABLE " + table(t) + " ADD " + (keyword ? "COLUMN " : "") + columnDefOther(x, false));
            if (c.identity && type == DbType.FIREBIRD_25) {
                firebird25Identity(t, x.name);
            }
            if (!c.nullable && !c.identity && norm(c.defaultExpr) == null && !c.primaryKey) {
                warnings.add("A nova coluna " + x.name + " é obrigatória e não tem valor padrão: só funciona se a tabela estiver vazia.");
            }
        }
        // 6) nova chave primária
        if (pkChanged && !newPk.isEmpty()) {
            String cols = String.join(", ", newPk.stream().map(this::id).toList());
            sql.add(type == DbType.SQLSERVER || type.isFirebird()
                    ? "ALTER TABLE " + table(t) + " ADD CONSTRAINT " + id(pkConstraintName(t)) + " PRIMARY KEY (" + cols + ")"
                    : "ALTER TABLE " + table(t) + " ADD PRIMARY KEY (" + cols + ")");
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

    // =====================================================================
    // SQLite: recriar a tabela
    // =====================================================================

    /** O ALTER TABLE do SQLite só renomeia e adiciona colunas simples: o resto exige recriar. */
    private boolean sqliteNeedsRebuild() {
        for (Column c : d.columns) {
            if (c.isNew()) {
                if (!c.dropped && (c.primaryKey || c.identity || (!c.nullable && norm(c.defaultExpr) == null))) {
                    return true;
                }
                continue;
            }
            if (c.dropped || !sameType(c, c.original) || c.primaryKey != c.original.primaryKey
                    || c.nullable != c.original.nullable || c.identity != c.original.identity
                    || !Objects.equals(norm(c.defaultExpr), norm(c.original.defaultExpr))) {
                return true;
            }
        }
        for (ForeignKey f : d.foreignKeys) {
            if (f.isNew() ? !f.dropped : f.dropped || !sameFk(f, Map.of())) {
                return true;
            }
        }
        return false;
    }

    private void buildSqliteRebuild() {
        String oldName = d.originalName;
        String newName = normalizeName(d.name, casing);
        String finalName = !newName.equals(oldName) && !d.name.trim().equals(oldName) ? newName : oldName;
        String tmp = finalName + "__h2studio_novo";
        List<Column> cols = d.activeColumns();
        List<String> lines = new ArrayList<>();
        for (Column c : cols) {
            Column x = c.copy();
            x.name = currentName(c);
            lines.add(columnDefOther(x, false));
        }
        List<String> pk = cols.stream().filter(c -> c.primaryKey).map(this::currentName).toList();
        boolean inlinePk = cols.stream().anyMatch(c -> c.identity);
        if (!pk.isEmpty() && !inlinePk) {
            lines.add("PRIMARY KEY (" + String.join(", ", pk.stream().map(this::id).toList()) + ")");
        }
        for (ForeignKey f : d.foreignKeys) {
            if (!f.dropped) {
                lines.add(fkClause(f));
            }
        }
        sql.add("CREATE TABLE " + id(tmp) + " (\n    " + String.join(",\n    ", lines) + "\n)");
        List<String> to = new ArrayList<>();
        List<String> from = new ArrayList<>();
        for (Column c : cols) {
            if (!c.isNew()) {
                to.add(id(currentName(c)));
                from.add(id(c.original.name));
            }
        }
        if (!to.isEmpty()) {
            sql.add("INSERT INTO " + id(tmp) + " (" + String.join(", ", to) + ") SELECT " + String.join(", ", from)
                    + " FROM " + table(oldName));
        }
        sql.add("DROP TABLE " + table(oldName));
        sql.add("ALTER TABLE " + id(tmp) + " RENAME TO " + id(finalName));
        Map<String, String> renamed = new HashMap<>();
        for (Column c : d.columns) {
            if (!c.isNew() && !c.dropped) {
                renamed.put(c.original.name, currentName(c));
            }
        }
        int n = 0;
        for (Index i : d.indexes) {
            if (i.dropped) {
                continue;
            }
            Index x = i.copy();
            x.columns = i.columns.stream().map(col -> renamed.getOrDefault(col, col)).toList();
            if (i.automatic) {
                // UNIQUE da definição antiga vira índice único com nome próprio
                x.name = "ux_" + finalName + "_" + (++n);
                x.unique = true;
            }
            sql.add(createIndex(x, finalName));
        }
        warnings.add("SQLite: esta alteração recria a tabela (cria a nova estrutura, copia os dados, apaga a antiga e "
                + "renomeia), tudo numa transação. Gatilhos desta tabela, se houver, precisam ser recriados.");
        for (Column c : cols) {
            if (c.isNew() && !c.nullable && norm(c.defaultExpr) == null && !c.identity) {
                warnings.add("A nova coluna " + currentName(c) + " é obrigatória e não tem valor padrão: só funciona se a tabela estiver vazia.");
            }
            if (!c.isNew() && !sameType(c, c.original) && !isWidening(c.original, c)) {
                warnings.add("No SQLite a mudança de tipo de " + currentName(c) + " só muda a declaração: os valores são copiados como estão.");
            }
        }
        for (Column c : d.columns) {
            if (c.dropped && !c.isNew()) {
                warnings.add("A coluna " + c.original.name + " será REMOVIDA com todos os seus dados.");
            }
        }
    }

    /**
     * Comandos para excluir a tabela. Com {@code cascade}: CASCADE no H2/PostgreSQL; nos outros,
     * remove antes as chaves estrangeiras das tabelas que apontam para ela.
     */
    public static List<String> dropTableStatements(DbType type, String schema, String table, boolean cascade,
                                                   List<Meta.ForeignKeyInfo> incoming, SqlCompletion.Casing casing,
                                                   String defaultSchema) {
        DbType t = type == null ? DbType.H2 : type;
        if (t == DbType.H2) {
            return List.of(dropTable(schema, table, cascade, casing, defaultSchema));
        }
        java.util.function.BiFunction<String, String, String> ref = (sc, n) ->
                sc == null || sc.isEmpty() || sc.equals(defaultSchema)
                        ? SqlCompletion.quoteIfNeeded(n, casing, t)
                        : SqlCompletion.quoteIfNeeded(sc, casing, t) + "." + SqlCompletion.quoteIfNeeded(n, casing, t);
        List<String> out = new ArrayList<>();
        if (cascade && t != DbType.POSTGRESQL && t != DbType.SQLITE && incoming != null) {
            for (Meta.ForeignKeyInfo f : incoming) {
                out.add("ALTER TABLE " + ref.apply(f.refSchema(), f.refTable())
                        + (t == DbType.MYSQL || t == DbType.MARIADB ? " DROP FOREIGN KEY " : " DROP CONSTRAINT ")
                        + SqlCompletion.quoteIfNeeded(f.name(), casing, t));
            }
        }
        out.add("DROP TABLE " + ref.apply(schema, table) + (cascade && t == DbType.POSTGRESQL ? " CASCADE" : ""));
        return out;
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
