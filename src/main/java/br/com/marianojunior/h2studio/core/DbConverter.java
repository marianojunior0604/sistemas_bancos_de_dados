package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.TableDesign.Column;
import br.com.marianojunior.h2studio.core.TableDesign.ForeignKey;
import br.com.marianojunior.h2studio.core.TableDesign.Index;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converte tabelas de um banco para outro (qualquer combinação de H2, PostgreSQL, MySQL/MariaDB,
 * Firebird, SQL Server e SQLite), para um script .sql no dialeto do destino ou para uma planilha
 * .xlsx com uma aba por tabela.
 * <p>
 * Ordem: (apaga as tabelas que serão substituídas) → cria as tabelas sem índices e sem chaves
 * estrangeiras → copia os dados em lotes → acerta o próximo valor do auto incremento → cria os
 * índices → cria as chaves estrangeiras. Assim a ordem de cópia não importa e a carga fica rápida.
 * Os tipos passam por um "tipo comum" ({@link Kind}) e viram o tipo equivalente do destino.
 * Views, gatilhos, procedures e generators avulsos não são convertidos (cada banco tem a sua linguagem).
 */
public final class DbConverter {

    /** Como ficam os nomes nos bancos que guardam do jeito escrito (MySQL, SQL Server, SQLite). */
    public enum NameStyle {
        KEEP("manter como estão"), LOWER("minúsculas"), UPPER("MAIÚSCULAS");

        public final String label;

        NameStyle(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Tipo "comum" usado na tradução entre bancos. */
    public enum Kind {
        BOOLEAN, TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, REAL, DOUBLE, CHAR, VARCHAR, TEXT,
        DATE, TIME, TIMESTAMP, TIMESTAMPTZ, BINARY, UUID, JSON, OTHER;

        boolean isInteger() {
            return this == TINYINT || this == SMALLINT || this == INTEGER || this == BIGINT;
        }

        boolean isLob() {
            return this == TEXT || this == BINARY || this == JSON;
        }

        boolean isString() {
            return this == CHAR || this == VARCHAR || this == TEXT || this == UUID || this == JSON || this == OTHER;
        }
    }

    public record Options(NameStyle names, boolean data, boolean indexes, boolean foreignKeys, boolean replace,
                          int batchSize) {
        public static Options defaults() {
            return new Options(NameStyle.KEEP, true, true, true, false, 500);
        }
    }

    /** Andamento: o diálogo mostra o texto e a barra; {@code cancelled} interrompe entre lotes. */
    public interface Listener {
        void status(String text);

        void progress(double fraction);

        boolean cancelled();

        Listener NONE = new Listener() {
            @Override
            public void status(String text) {
            }

            @Override
            public void progress(double fraction) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    /** Uma coluna: nome e tipo na origem e no destino. */
    public static final class ColumnMap {
        public final String source;
        public final String target;
        public final String sourceType;
        public final Kind kind;
        public String targetType;
        public Kind targetKind;
        /** Tamanho máximo do texto no destino (corta o que passar), ou null. */
        public Integer maxChars;
        /** Mesmo tipo de banco e tipo especial: copia o objeto do driver sem converter. */
        boolean nativeCopy;

        ColumnMap(String source, String target, String sourceType, Kind kind) {
            this.source = source;
            this.target = target;
            this.sourceType = sourceType;
            this.kind = kind;
        }
    }

    public record ForeignKeyPlan(String name, String table, String refTable, String statement) {
    }

    /** O que será feito com uma tabela. */
    public static final class TablePlan {
        public final Meta.TableInfo source;
        public final String target;
        public final List<ColumnMap> columns = new ArrayList<>();
        /** CREATE TABLE (e generator/gatilho no Firebird 2.5, comentários). */
        public final List<String> create = new ArrayList<>();
        public final List<String> indexes = new ArrayList<>();
        public final List<ForeignKeyPlan> foreignKeys = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        /** Coluna com auto incremento no destino (índice em columns), ou -1. */
        public int identity = -1;
        /** Generator do Firebird 2.5. */
        String generator;
        public long rows = -1;
        /** Já existe no destino. */
        public boolean exists;
        /** Erro que impede converter a tabela (vem do planejamento). */
        public String error;

        TablePlan(Meta.TableInfo source, String target) {
            this.source = source;
            this.target = target;
        }
    }

    /** Plano completo (mostrado antes de executar). */
    public static final class Plan {
        public final DbType from;
        public final DbType to;
        public final String sourceSchema;
        public final String targetSchema;
        final String targetDefaultSchema;
        final SqlCompletion.Casing casing;
        public final Options options;
        public final List<TablePlan> tables = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        /** Views da origem (não convertidas). */
        public final List<String> views = new ArrayList<>();
        /** Comandos para apagar as tabelas que já existem no destino (opção substituir). */
        public final List<String> drop = new ArrayList<>();
        /** Comandos de remoção de chaves que apontam para tabelas que serão apagadas. */
        public final List<String> dropKeys = new ArrayList<>();

        Plan(DbType from, DbType to, String sourceSchema, String targetSchema, String targetDefaultSchema,
             SqlCompletion.Casing casing, Options options) {
            this.from = from;
            this.to = to;
            this.sourceSchema = sourceSchema;
            this.targetSchema = targetSchema;
            this.targetDefaultSchema = targetDefaultSchema;
            this.casing = casing;
            this.options = options;
        }

        public long totalRows() {
            return tables.stream().filter(t -> t.error == null).mapToLong(t -> Math.max(0, t.rows)).sum();
        }

        /** Tabelas que já existem no destino e não serão substituídas. */
        public List<String> conflicts() {
            return options.replace() ? List.of() : tables.stream().filter(t -> t.exists).map(t -> t.target).toList();
        }

        /** Estrutura que será criada (prévia, sem os dados). */
        public String script() {
            List<String> all = new ArrayList<>();
            all.addAll(dropKeys);
            all.addAll(drop);
            for (TablePlan t : tables) {
                if (t.error == null) {
                    all.addAll(t.create);
                }
            }
            if (options.indexes()) {
                tables.stream().filter(t -> t.error == null).forEach(t -> all.addAll(t.indexes));
            }
            if (options.foreignKeys()) {
                tables.stream().filter(t -> t.error == null).forEach(t -> t.foreignKeys.forEach(f -> all.add(f.statement())));
            }
            return new DdlBuilder.Plan(all, List.of(), List.of()).script(to);
        }
    }

    public record TableResult(String source, String target, long expected, long copied, String error) {
    }

    public record Report(List<TableResult> tables, List<String> errors, List<String> notes, long millis,
                         boolean cancelled) {
        public long rows() {
            return tables.stream().mapToLong(TableResult::copied).sum();
        }

        public boolean ok() {
            return errors.isEmpty() && !cancelled;
        }
    }

    private DbConverter() {
    }

    // =====================================================================
    // Arquivo novo como destino
    // =====================================================================

    /** Cria um arquivo SQLite vazio (um arquivo de 0 bytes já é um banco SQLite válido). */
    public static ConnectionConfig newSqliteFile(Path file) throws IOException {
        if (Files.exists(file)) {
            throw new IOException("O arquivo já existe: " + file);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.createFile(file);
        ConnectionConfig c = new ConnectionConfig();
        c.setType(DbType.SQLITE);
        c.setDatabase(file.toAbsolutePath().toString());
        c.setName(file.getFileName().toString());
        return c;
    }

    /** Cria um banco H2 novo (formato do driver H2 mais novo disponível). */
    public static ConnectionConfig newH2File(Path file, String user, String password) throws Exception {
        String f = file.toAbsolutePath().toString();
        String base = f.endsWith(".mv.db") ? f.substring(0, f.length() - 6) : f.endsWith(".h2.db") ? f.substring(0, f.length() - 6) : f;
        Path mv = Path.of(base + ".mv.db");
        if (Files.exists(mv)) {
            throw new IOException("O arquivo já existe: " + mv);
        }
        DriverRegistry.DriverJar jar = DriverRegistry.get().list().stream()
                .max(java.util.Comparator.comparingInt(DriverRegistry.DriverJar::major)
                        .thenComparingInt(DriverRegistry.DriverJar::minor).thenComparingInt(DriverRegistry.DriverJar::patch))
                .orElseThrow(() -> new IOException("Nenhum driver H2 encontrado para criar o banco."));
        Files.createDirectories(mv.getParent());
        ConnectionConfig c = ConnectionConfig.forFile(mv);
        c.setUser(user == null || user.isBlank() ? "sa" : user);
        c.setPassword(password == null ? "" : password);
        try (Connection x = new ConnectionFactory().createDatabase(c, jar, base)) {
            x.isValid(2);
        }
        return c;
    }

    // =====================================================================
    // Planejamento
    // =====================================================================

    /**
     * Monta o plano: lê a estrutura da origem e traduz para o destino.
     *
     * @param tableNames          tabelas a converter (null = todas do schema)
     * @param targetSchema        schema/banco de destino ("" no Firebird/SQLite e nos arquivos)
     * @param targetDefaultSchema schema em uso na conexão de destino (nomes nele saem sem prefixo)
     * @param targetExisting      tabelas que já existem no destino (null para script/planilha)
     */
    public static Plan plan(Connection src, DbType from, String sourceSchema, List<String> tableNames, DbType to,
                            SqlCompletion.Casing casing, String targetSchema, String targetDefaultSchema,
                            Set<String> targetExisting, Options opt) throws SQLException {
        Plan p = new Plan(from, to, sourceSchema, targetSchema == null ? "" : targetSchema,
                targetDefaultSchema == null ? "" : targetDefaultSchema, casing, opt);
        List<Meta.TableInfo> all = MetadataService.tables(src, sourceSchema);
        List<Meta.TableInfo> chosen = new ArrayList<>();
        for (Meta.TableInfo t : all) {
            if (t.view()) {
                if (tableNames == null || tableNames.contains(t.name())) {
                    p.views.add(t.name());
                }
            } else if (tableNames == null || tableNames.contains(t.name())) {
                chosen.add(t);
            }
        }
        Names names = new Names(to, casing, opt.names());
        Map<String, String> tableMap = new LinkedHashMap<>();
        for (Meta.TableInfo t : chosen) {
            tableMap.put(t.name(), names.unique(names.map(t.name()), names.tables));
        }
        Map<String, TableDesign> designs = new HashMap<>();
        Map<String, Map<String, String>> columnMaps = new HashMap<>();
        Set<String> constraintNames = new HashSet<>();
        for (Meta.TableInfo t : chosen) {
            TableDesign sd = TableDesign.load(src, sourceSchema, t.name(), from);
            designs.put(t.name(), sd);
            Map<String, String> cm = new LinkedHashMap<>();
            Set<String> used = new HashSet<>();
            for (Column c : sd.columns) {
                cm.put(c.name, names.unique(names.map(c.name), used));
            }
            columnMaps.put(t.name(), cm);
        }
        boolean comments = false;
        for (Meta.TableInfo t : chosen) {
            TablePlan tp = new TablePlan(t, tableMap.get(t.name()));
            p.tables.add(tp);
            try {
                comments |= planTable(src, p, tp, designs.get(t.name()), columnMaps, tableMap, names, constraintNames);
            } catch (RuntimeException e) {
                tp.error = "Não foi possível traduzir a estrutura: " + e.getMessage();
            }
            try (Statement st = src.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + from.qualified(sourceSchema, t.name()))) {
                rs.next();
                tp.rows = rs.getLong(1);
            } catch (SQLException e) {
                tp.notes.add("Não foi possível contar as linhas: " + e.getMessage());
            }
            if (targetExisting != null && targetExisting.stream().anyMatch(x -> x.equalsIgnoreCase(tp.target))) {
                tp.exists = true;
            }
        }
        if (comments && (to == DbType.SQLSERVER || to == DbType.SQLITE)) {
            p.notes.add("Comentários de tabelas e colunas não são copiados para o " + to.shortLabel() + ".");
        }
        if (!p.views.isEmpty()) {
            p.notes.add("Views não são convertidas (o SQL delas é do banco de origem): " + String.join(", ", p.views)
                    + ". Copie a consulta pelo DDL da view e ajuste no editor SQL do destino.");
        }
        return p;
    }

    /** Monta o comando para apagar as tabelas que já existem no destino (opção substituir). */
    public static void planDrops(Plan p, Connection dst) throws SQLException {
        p.drop.clear();
        p.dropKeys.clear();
        if (!p.options.replace()) {
            return;
        }
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (TablePlan t : p.tables) {
            if (!t.exists || t.error != null) {
                continue;
            }
            String real = existingName(dst, p.targetSchema, t.target);
            List<Meta.ForeignKeyInfo> incoming = p.to == DbType.POSTGRESQL || p.to == DbType.SQLITE ? List.of()
                    : MetadataService.foreignKeys(dst, p.targetSchema, real).stream().filter(Meta.ForeignKeyInfo::incoming).toList();
            List<String> st = DdlBuilder.dropTableStatements(p.to, p.targetSchema, real, true, incoming, p.casing,
                    p.targetDefaultSchema);
            for (int i = 0; i < st.size() - 1; i++) {
                keys.add(st.get(i));
            }
            p.drop.add(st.get(st.size() - 1));
        }
        p.dropKeys.addAll(keys);
    }

    private static String existingName(Connection dst, String schema, String name) throws SQLException {
        for (Meta.TableInfo t : MetadataService.tables(dst, schema)) {
            if (t.name().equalsIgnoreCase(name)) {
                return t.name();
            }
        }
        return name;
    }

    /** @return a tabela tinha comentários */
    private static boolean planTable(Connection src, Plan p, TablePlan tp, TableDesign sd, Map<String, Map<String, String>> columnMaps,
                                     Map<String, String> tableMap, Names names, Set<String> constraintNames) {
        DbType to = p.to;
        Map<String, String> cm = columnMaps.get(tp.source.name());
        // colunas usadas em índices/chaves: tipos "outros" viram VARCHAR (texto longo não pode ser indexado em vários bancos)
        Set<String> keyed = new HashSet<>();
        sd.indexes.forEach(i -> keyed.addAll(i.columns));
        sd.foreignKeys.forEach(f -> keyed.addAll(f.columns));
        sd.columns.stream().filter(c -> c.primaryKey).forEach(c -> keyed.add(c.name));

        TableDesign td = new TableDesign();
        td.schema = p.targetSchema;
        td.name = tp.target;
        boolean hadComments = norm(sd.comment) != null;
        td.comment = to == DbType.SQLSERVER || to == DbType.SQLITE ? null : norm(sd.comment);
        long pkCount = sd.columns.stream().filter(c -> c.primaryKey).count();
        for (Column c : sd.columns) {
            Kind k = kindOf(c, p.from);
            ColumnMap m = new ColumnMap(c.name, cm.get(c.name), DdlBuilder.typeSql(c, p.from), k);
            if (k == Kind.TEXT && keyed.contains(c.name) && to != DbType.POSTGRESQL && to != DbType.SQLITE) {
                // texto sem limite usado em índice/chave (comum no PostgreSQL): vira VARCHAR do tamanho dos dados
                int longest = longest(src, p, tp, c.name);
                int limit = to == DbType.FIREBIRD_25 ? 250 : to.isFirebird() ? 500 : to == DbType.SQLSERVER ? 850 : 768;
                if (longest >= 0 && longest <= limit) {
                    int n = Math.min(limit, Math.max(255, (longest + 49) / 50 * 50));
                    c = c.copy();
                    c.length = n;
                    k = Kind.VARCHAR;
                    m = new ColumnMap(m.source, m.target, m.sourceType, k);
                    tp.notes.add("Coluna " + m.target + ": texto sem limite usado em índice virou VARCHAR(" + n
                            + ") (o maior valor tem " + longest + " caracteres).");
                }
            }
            Column x = new Column();
            x.name = m.target;
            x.nullable = c.nullable;
            x.primaryKey = c.primaryKey;
            hadComments |= norm(c.comment) != null;
            x.comment = to == DbType.SQLSERVER || to == DbType.SQLITE ? null : norm(c.comment);
            mapType(c, k, m, x, p.from, to, keyed.contains(c.name), tp.notes);
            if (c.identity) {
                String why = null;
                if (!m.targetKind.isInteger()) {
                    why = "o tipo não é inteiro";
                } else if (to == DbType.SQLITE && (!c.primaryKey || pkCount != 1)) {
                    why = "no SQLite só a chave primária sozinha pode ter auto incremento";
                } else if ((to == DbType.MYSQL || to == DbType.MARIADB) && !c.primaryKey) {
                    why = "no MySQL o auto incremento precisa ser a chave primária";
                }
                if (why == null) {
                    x.identity = true;
                    if (to == DbType.SQLITE) {
                        x.type = "INTEGER";
                    }
                    tp.identity = tp.columns.size();
                } else {
                    tp.notes.add("Coluna " + m.target + ": auto incremento não mantido (" + why + "); os valores são copiados.");
                }
            }
            if (!x.identity) {
                x.defaultExpr = translateDefault(c.defaultExpr, m.targetKind, p.from, to, m.target, tp.notes);
            }
            td.columns.add(x);
            tp.columns.add(m);
        }
        if (to == DbType.MYSQL || to == DbType.MARIADB) {
            mysqlRowSize(td, tp);
        }
        Map<String, Kind> targetKinds = new HashMap<>();
        Map<String, Integer> targetLen = new HashMap<>();
        for (int i = 0; i < tp.columns.size(); i++) {
            targetKinds.put(tp.columns.get(i).target, tp.columns.get(i).targetKind);
            targetLen.put(tp.columns.get(i).target, td.columns.get(i).length);
        }
        List<String> pkTarget = td.columns.stream().filter(c -> c.primaryKey).map(c -> c.name).toList();

        // ---------- chaves estrangeiras
        List<ForeignKey> fks = new ArrayList<>();
        for (ForeignKey f : sd.foreignKeys) {
            String refTable = tableMap.get(f.refTable);
            boolean sameSchema = Objects.equals(f.refSchema, p.sourceSchema) || f.refSchema == null
                    || f.refSchema.isEmpty();
            if (refTable == null || !sameSchema) {
                tp.notes.add("Chave " + f.name + " não convertida: a tabela " + f.refTable + " não está na conversão.");
                continue;
            }
            Map<String, String> refCols = columnMaps.get(f.refTable);
            ForeignKey x = new ForeignKey();
            x.name = names.unique(constraintName(f.name, "fk_" + tp.target, names), constraintNames);
            x.columns = new ArrayList<>(f.columns.stream().map(cm::get).toList());
            x.refSchema = p.targetSchema;
            x.refTable = refTable;
            x.refColumns = new ArrayList<>(f.refColumns.stream().map(n -> refCols.getOrDefault(n, n)).toList());
            x.onDelete = rule(f.onDelete, to, tp, x.name);
            x.onUpdate = rule(f.onUpdate, to, tp, x.name);
            if (x.columns.contains(null)) {
                continue;
            }
            fks.add(x);
        }
        if (to == DbType.SQLITE) {
            td.foreignKeys.addAll(fks); // o SQLite só aceita chaves no CREATE TABLE
        }

        DdlBuilder.Plan ddl = DdlBuilder.build(td, p.casing, p.targetDefaultSchema, Set.of(), to);
        if (!ddl.errors().isEmpty()) {
            tp.error = String.join(" ", ddl.errors());
            return hadComments;
        }
        tp.create.addAll(ddl.statements());
        for (String w : ddl.warnings()) {
            if (!w.contains("palavra reservada")) {
                tp.notes.add(w);
            }
        }
        if (tp.identity >= 0 && to == DbType.FIREBIRD_25) {
            tp.generator = fbName("GEN_" + DdlBuilder.normalizeName(tp.target, p.casing) + "_"
                    + DdlBuilder.normalizeName(tp.columns.get(tp.identity).target, p.casing));
        }

        // ---------- índices (criados depois dos dados)
        Set<List<String>> seen = new HashSet<>();
        seen.add(pkTarget.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
        int n = 0;
        for (Index i : sd.indexes) {
            List<String> cols = i.columns.stream().map(c -> cm.getOrDefault(c, null)).toList();
            if (cols.contains(null) || cols.isEmpty()) {
                continue;
            }
            List<String> keyU = cols.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList();
            if (!seen.add(keyU)) {
                continue; // igual à chave primária ou a outro índice
            }
            boolean fkIndex = !i.unique && i.automatic && fks.stream().anyMatch(f -> f.columns.equals(cols));
            if (fkIndex && (to == DbType.MYSQL || to == DbType.MARIADB || to.isFirebird() || to == DbType.H2)) {
                continue; // o destino cria sozinho o índice da chave estrangeira
            }
            String lob = cols.stream().filter(c -> targetKinds.get(c).isLob()).findFirst().orElse(null);
            if (lob != null && to != DbType.POSTGRESQL && to != DbType.SQLITE && to != DbType.H2) {
                tp.notes.add("Índice " + i.name + " não criado: a coluna " + lob + " é texto longo/binário, que o "
                        + to.shortLabel() + " não indexa.");
                continue;
            }
            int bytes = cols.stream().mapToInt(c -> keyBytes(targetKinds.get(c), targetLen.get(c))).sum();
            int limit = to == DbType.MYSQL || to == DbType.MARIADB ? 3072 : to == DbType.FIREBIRD_25 ? 1000
                    : to.isFirebird() ? 2000 : to == DbType.SQLSERVER ? 1700 : Integer.MAX_VALUE;
            if (bytes > limit) {
                tp.notes.add("Índice " + i.name + " não criado: as colunas passam do tamanho máximo de chave do "
                        + to.shortLabel() + " (" + limit + " bytes).");
                continue;
            }
            n++;
            String name = names.unique(fkIndex ? names.map("ix_" + tp.target + "_" + cols.get(0))
                    : indexName(i.name, (i.unique ? "ux_" : "ix_") + tp.target + "_" + n, names), constraintNames);
            tp.indexes.add("CREATE " + (i.unique ? "UNIQUE " : "") + "INDEX "
                    + (to == DbType.H2 ? tref(p, name) : id(p, name)) + " ON " + tref(p, tp.target)
                    + " (" + String.join(", ", cols.stream().map(c -> id(p, c)).toList()) + ")");
        }

        // ---------- chaves estrangeiras (depois dos dados; no SQLite já estão no CREATE)
        if (to != DbType.SQLITE) {
            for (ForeignKey f : fks) {
                tp.foreignKeys.add(new ForeignKeyPlan(f.name, tp.target, f.refTable,
                        "ALTER TABLE " + tref(p, tp.target) + " ADD CONSTRAINT " + id(p, f.name) + " FOREIGN KEY ("
                                + String.join(", ", f.columns.stream().map(c -> id(p, c)).toList()) + ") REFERENCES "
                                + tref(p, f.refTable) + " (" + String.join(", ", f.refColumns.stream().map(c -> id(p, c)).toList())
                                + ")" + ruleSql(" ON DELETE ", f.onDelete) + ruleSql(" ON UPDATE ", f.onUpdate)));
            }
        } else {
            fks.forEach(f -> tp.foreignKeys.add(new ForeignKeyPlan(f.name, tp.target, f.refTable, null)));
        }
        return hadComments;
    }

    /** Maior texto da coluna na origem (-1 se não deu para medir). */
    private static int longest(Connection src, Plan p, TablePlan tp, String column) {
        String fn = switch (p.from) {
            case SQLSERVER -> "LEN";
            case SQLITE -> "LENGTH";
            default -> "CHAR_LENGTH";
        };
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(" + fn + "(" + p.from.quote(column) + ")) FROM "
                     + p.from.qualified(p.sourceSchema, tp.source.name()))) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    private static int keyBytes(Kind k, Integer len) {
        return switch (k) {
            case CHAR, VARCHAR -> (len == null ? 255 : len) * 4;
            case UUID -> 36 * 4;
            case BIGINT, DOUBLE, TIMESTAMP, TIMESTAMPTZ -> 8;
            case DECIMAL -> 16;
            default -> 4;
        };
    }

    private static String rule(String r, DbType to, TablePlan tp, String fk) {
        String x = r == null ? "RESTRICT" : r.toUpperCase(Locale.ROOT);
        if (x.equals("SET DEFAULT") && (to == DbType.MYSQL || to == DbType.MARIADB)) {
            tp.notes.add("Chave " + fk + ": SET DEFAULT não existe no MySQL (InnoDB); ficou sem ação.");
            return "RESTRICT";
        }
        return x;
    }

    private static String ruleSql(String prefix, String r) {
        return r == null || r.isBlank() || r.equalsIgnoreCase("RESTRICT") || r.equalsIgnoreCase("NO ACTION") ? ""
                : prefix + r.toUpperCase(Locale.ROOT);
    }

    private static final Pattern GENERATED_NAME = Pattern.compile(
            "(?i)(PRIMARY_KEY_.*|.*_INDEX_[0-9A-F]+|CONSTRAINT_[0-9A-F]+|SYS_.*|sqlite_autoindex_.*|RDB\\$.*|INTEG_\\d+|"
                    + "FK_[0-9A-F]{8,}|PK__.*|UQ__.*)");

    private static String indexName(String source, String fallback, Names names) {
        return source == null || GENERATED_NAME.matcher(source).matches() ? names.map(fallback) : names.map(source);
    }

    private static String constraintName(String source, String fallback, Names names) {
        return source == null || GENERATED_NAME.matcher(source).matches() ? names.map(fallback) : names.map(source);
    }

    /** MySQL: a soma dos VARCHAR (4 bytes por caractere) não pode passar de 65.535 bytes por linha. */
    private static void mysqlRowSize(TableDesign td, TablePlan tp) {
        while (true) {
            int bytes = 0;
            Column biggest = null;
            int bigIdx = -1;
            for (int i = 0; i < td.columns.size(); i++) {
                Column c = td.columns.get(i);
                Kind k = tp.columns.get(i).targetKind;
                if ((k == Kind.VARCHAR || k == Kind.CHAR) && c.length != null) {
                    bytes += c.length * 4 + 2;
                    if (c.length > 255 && !c.primaryKey && (biggest == null || c.length > biggest.length)) {
                        biggest = c;
                        bigIdx = i;
                    }
                } else {
                    bytes += 16;
                }
            }
            if (bytes <= 60_000 || biggest == null) {
                return;
            }
            tp.notes.add("Coluna " + biggest.name + ": VARCHAR(" + biggest.length + ") virou TEXT (no MySQL a linha "
                    + "inteira não pode passar de 64 KB).");
            biggest.type = "TEXT";
            biggest.length = null;
            tp.columns.get(bigIdx).targetType = "TEXT";
            tp.columns.get(bigIdx).targetKind = Kind.TEXT;
        }
    }

    // =====================================================================
    // Tipos
    // =====================================================================

    /** Tipo comum de uma coluna lida da origem. */
    static Kind kindOf(Column c, DbType from) {
        String t = c.type == null ? "" : c.type.trim().toUpperCase(Locale.ROOT);
        String base = t.replaceAll("\\(.*\\)", "").trim();
        if (base.endsWith(" UNSIGNED")) {
            base = base.substring(0, base.length() - 9).trim();
        }
        if (t.equals("VARCHAR(MAX)") || t.equals("NVARCHAR(MAX)")) {
            return Kind.TEXT;
        }
        if (t.equals("VARBINARY(MAX)")) {
            return Kind.BINARY;
        }
        return switch (base) {
            case "BOOLEAN", "BOOL", "BIT" -> Kind.BOOLEAN;
            case "TINYINT" -> from == DbType.SQLSERVER ? Kind.SMALLINT : Kind.TINYINT; // tinyint do SQL Server vai até 255
            case "SMALLINT", "INT2", "YEAR" -> Kind.SMALLINT;
            case "INTEGER", "INT", "INT4", "MEDIUMINT", "SERIAL" -> from == DbType.SQLITE ? Kind.BIGINT : Kind.INTEGER;
            case "BIGINT", "INT8", "BIGSERIAL", "INT64" -> Kind.BIGINT;
            case "DECIMAL", "NUMERIC", "NUMBER", "MONEY", "SMALLMONEY", "DECFLOAT", "INT128" -> Kind.DECIMAL;
            case "REAL", "FLOAT4" -> Kind.REAL;
            case "FLOAT" -> from == DbType.SQLSERVER ? Kind.DOUBLE : Kind.REAL;
            case "DOUBLE", "DOUBLE PRECISION", "FLOAT8" -> from == DbType.SQLITE ? Kind.DOUBLE : Kind.DOUBLE;
            case "CHAR", "NCHAR", "CHARACTER", "BPCHAR" -> c.length == null ? Kind.TEXT : Kind.CHAR;
            case "VARCHAR", "NVARCHAR", "CHARACTER VARYING", "VARCHAR2", "VARCHAR_IGNORECASE", "CITEXT", "SYSNAME" ->
                    c.length == null ? Kind.TEXT : Kind.VARCHAR;
            case "TEXT", "CLOB", "NCLOB", "NTEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "BLOB SUB_TYPE TEXT",
                 "BLOB SUB_TYPE 1", "CHARACTER LARGE OBJECT", "LONGVARCHAR", "XML" -> Kind.TEXT;
            case "DATE" -> Kind.DATE;
            case "TIME", "TIMETZ", "TIME WITH TIME ZONE" -> Kind.TIME;
            case "TIMESTAMP", "DATETIME", "DATETIME2", "SMALLDATETIME", "TIMESTAMP WITHOUT TIME ZONE" -> Kind.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ", "DATETIMEOFFSET" -> Kind.TIMESTAMPTZ;
            case "BLOB", "BYTEA", "VARBINARY", "BINARY", "LONGBLOB", "MEDIUMBLOB", "TINYBLOB", "IMAGE",
                 "BINARY LARGE OBJECT", "LONGVARBINARY", "BLOB SUB_TYPE 0", "BINARY VARYING", "RAW" -> Kind.BINARY;
            case "UUID", "UNIQUEIDENTIFIER" -> Kind.UUID;
            case "JSON", "JSONB" -> Kind.JSON;
            default -> Kind.OTHER;
        };
    }

    private static boolean sameFamily(DbType a, DbType b) {
        if (a == b) {
            return true;
        }
        boolean my = (a == DbType.MYSQL || a == DbType.MARIADB) && (b == DbType.MYSQL || b == DbType.MARIADB);
        return my || a.isFirebird() && b.isFirebird() && b != DbType.FIREBIRD_25;
    }

    /** Preenche tipo/tamanho da coluna de destino. */
    private static void mapType(Column c, Kind k, ColumnMap m, Column x, DbType from, DbType to, boolean keyed,
                                List<String> notes) {
        Integer len = c.length;
        Integer scale = c.scale;
        x.length = null;
        x.scale = null;
        Kind tk = k;
        String type;
        boolean my = to == DbType.MYSQL || to == DbType.MARIADB;
        switch (k) {
            case BOOLEAN -> type = switch (to) {
                case SQLSERVER -> "BIT";
                case FIREBIRD_25 -> "SMALLINT";
                default -> "BOOLEAN";
            };
            case TINYINT -> type = to == DbType.H2 || my ? "TINYINT" : to == DbType.SQLITE ? "INTEGER" : "SMALLINT";
            case SMALLINT -> type = to == DbType.SQLITE ? "INTEGER" : "SMALLINT";
            case INTEGER -> type = my || to == DbType.SQLSERVER ? "INT" : "INTEGER";
            case BIGINT -> type = to == DbType.SQLITE ? "INTEGER" : "BIGINT";
            case DECIMAL -> {
                int max = to.isFirebird() ? 18 : my ? 65 : 38;
                Integer p = len;
                Integer s = scale;
                if (p == null && (to == DbType.POSTGRESQL || to == DbType.SQLITE || to == DbType.H2)) {
                    type = to == DbType.POSTGRESQL ? "NUMERIC" : to == DbType.SQLITE ? "NUMERIC" : "DECIMAL";
                    x.type = type;
                    break;
                }
                if (p == null) {
                    p = to.isFirebird() ? 18 : 38;
                    s = to.isFirebird() ? 4 : 10;
                    notes.add("Coluna " + m.target + ": número sem precisão definida virou " + (to.isFirebird() ? "NUMERIC" : "DECIMAL")
                            + "(" + p + ", " + s + ").");
                }
                if (s == null) {
                    s = 0;
                }
                if (p > max) {
                    notes.add("Coluna " + m.target + ": precisão " + p + " reduzida para " + max + " (máximo do "
                            + to.shortLabel() + ").");
                    s = Math.min(s, Math.max(0, max - (p - s))); // mantém os dígitos inteiros
                    p = max;
                }
                if (s > p) {
                    s = p;
                }
                if (my && s > 30) {
                    s = 30;
                }
                type = to == DbType.POSTGRESQL || to.isFirebird() ? "NUMERIC" : "DECIMAL";
                x.length = p;
                x.scale = s;
            }
            case REAL -> type = my || to.isFirebird() ? "FLOAT" : "REAL";
            case DOUBLE -> type = switch (to) {
                case MYSQL, MARIADB -> "DOUBLE";
                case SQLSERVER -> "FLOAT";
                case SQLITE -> "REAL";
                default -> "DOUBLE PRECISION";
            };
            case CHAR, VARCHAR -> {
                int n = len == null ? 255 : len;
                int maxVar = switch (to) {
                    case MYSQL, MARIADB -> 16_383;
                    case FIREBIRD, FIREBIRD_25 -> 8_191;
                    case SQLSERVER -> 4_000;
                    case POSTGRESQL -> 10_485_760;
                    case H2 -> 1_048_576;
                    default -> Integer.MAX_VALUE;
                };
                if (n > maxVar) {
                    tk = Kind.TEXT;
                    type = textType(to);
                    notes.add("Coluna " + m.target + ": " + (k == Kind.CHAR ? "CHAR" : "VARCHAR") + "(" + n
                            + ") passa do máximo do " + to.shortLabel() + " e virou " + type + ".");
                    if (keyed) {
                        notes.add("Coluna " + m.target + " está em índice/chave e virou texto longo: confira os índices.");
                    }
                } else {
                    boolean useChar = k == Kind.CHAR && to != DbType.SQLITE && !(my && n > 255);
                    type = to == DbType.SQLSERVER ? (useChar ? "NCHAR" : "NVARCHAR") : useChar ? "CHAR" : "VARCHAR";
                    x.length = n;
                    m.maxChars = n;
                }
            }
            case TEXT -> type = textType(to);
            case DATE -> type = "DATE";
            case TIME -> type = "TIME";
            case TIMESTAMP -> type = switch (to) {
                case MYSQL, MARIADB, SQLITE -> "DATETIME";
                case SQLSERVER -> "DATETIME2";
                default -> "TIMESTAMP";
            };
            case TIMESTAMPTZ -> {
                switch (to) {
                    case H2, POSTGRESQL -> type = "TIMESTAMP WITH TIME ZONE";
                    case SQLSERVER -> type = "DATETIMEOFFSET";
                    default -> {
                        type = my || to == DbType.SQLITE ? "DATETIME" : "TIMESTAMP";
                        tk = Kind.TIMESTAMP;
                        notes.add("Coluna " + m.target + ": data com fuso horário virou " + type
                                + " (horário convertido para o fuso deste computador).");
                    }
                }
            }
            case BINARY -> type = switch (to) {
                case POSTGRESQL -> "BYTEA";
                case MYSQL, MARIADB -> "LONGBLOB";
                case SQLSERVER -> "VARBINARY(MAX)";
                default -> "BLOB";
            };
            case UUID -> {
                switch (to) {
                    case H2, POSTGRESQL -> type = "UUID";
                    case SQLSERVER -> type = "UNIQUEIDENTIFIER";
                    default -> {
                        type = to == DbType.SQLITE ? "VARCHAR" : "CHAR";
                        x.length = 36;
                        tk = Kind.CHAR;
                    }
                }
            }
            case JSON -> {
                switch (to) {
                    case POSTGRESQL -> type = c.type != null && c.type.equalsIgnoreCase("JSON") ? "JSON" : "JSONB";
                    case MYSQL, MARIADB -> type = "JSON";
                    default -> {
                        type = textType(to);
                        tk = Kind.TEXT;
                    }
                }
            }
            default -> {
                if (sameFamily(from, to)) {
                    type = c.type == null ? "VARCHAR" : c.type.trim().toUpperCase(Locale.ROOT);
                    x.length = len;
                    x.scale = scale;
                    m.nativeCopy = true;
                } else if (keyed) {
                    type = to == DbType.SQLSERVER ? "NVARCHAR" : "VARCHAR";
                    x.length = 255;
                    m.maxChars = 255;
                    tk = Kind.VARCHAR;
                    notes.add("Coluna " + m.target + ": tipo " + c.type + " não existe no " + to.shortLabel()
                            + "; virou " + type + "(255) com o texto do valor.");
                } else {
                    type = textType(to);
                    tk = Kind.TEXT;
                    notes.add("Coluna " + m.target + ": tipo " + c.type + " não existe no " + to.shortLabel()
                            + "; virou " + type + " com o texto do valor.");
                }
            }
        }
        x.type = type;
        m.targetType = DdlBuilder.typeSql(x, to);
        m.targetKind = tk;
    }

    /** Tipo de destino escolhido para um tipo comum (usado pela importação de planilhas). */
    public record TypeChoice(String type, Integer length, Integer scale, String display) {
    }

    /** Traduz um tipo comum (com tamanho/escala) para o tipo do banco de destino. */
    public static TypeChoice targetType(Kind k, Integer length, Integer scale, DbType to) {
        Column c = new Column();
        c.name = "x";
        c.type = switch (k) {
            case CHAR -> "CHAR";
            case VARCHAR -> "VARCHAR";
            case TEXT -> "TEXT";
            case DECIMAL -> "DECIMAL";
            default -> k.name();
        };
        c.length = length;
        c.scale = scale;
        ColumnMap m = new ColumnMap("x", "x", "", k);
        Column x = new Column();
        x.name = "x";
        mapType(c, k, m, x, DbType.H2, to, false, new ArrayList<>());
        return new TypeChoice(x.type, x.length, x.scale, m.targetType);
    }

    private static String textType(DbType to) {
        return switch (to) {
            case H2 -> "CLOB";
            case MYSQL, MARIADB -> "LONGTEXT";
            case FIREBIRD, FIREBIRD_25 -> "BLOB SUB_TYPE TEXT";
            case SQLSERVER -> "NVARCHAR(MAX)";
            default -> "TEXT";
        };
    }

    // =====================================================================
    // Valor padrão
    // =====================================================================

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    private static final Pattern NOW = Pattern.compile("(?i)now\\(\\)|current_timestamp(\\(\\d*\\))?|getdate\\(\\)|"
            + "sysdatetime\\(\\)|localtimestamp(\\(\\d*\\))?|'now'|transaction_timestamp\\(\\)|statement_timestamp\\(\\)|"
            + "datetime\\('now'(\\s*,\\s*'localtime')?\\)|systimestamp|getutcdate\\(\\)|sysutcdatetime\\(\\)");
    private static final Pattern TODAY = Pattern.compile("(?i)current_date(\\(\\))?|curdate\\(\\)|'today'|"
            + "date\\('now'(\\s*,\\s*'localtime')?\\)|cast\\s*\\(\\s*getdate\\(\\)\\s+as\\s+date\\s*\\)|"
            + "convert\\s*\\(\\s*date\\s*,\\s*getdate\\(\\)\\s*\\)");
    private static final Pattern CURTIME = Pattern.compile("(?i)current_time(\\(\\d*\\))?|curtime\\(\\)|localtime(\\(\\d*\\))?|"
            + "time\\('now'(\\s*,\\s*'localtime')?\\)");

    /** Traduz o valor padrão da coluna; devolve null (com aviso) quando não dá. */
    static String translateDefault(String expr, Kind kind, DbType from, DbType to, String col, List<String> notes) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        String e = expr.trim();
        while (e.length() >= 2 && e.startsWith("(") && e.endsWith(")") && balanced(e.substring(1, e.length() - 1))) {
            e = e.substring(1, e.length() - 1).trim();
        }
        if (e.toUpperCase(Locale.ROOT).startsWith("DEFAULT ")) {
            e = e.substring(8).trim();
        }
        e = e.replaceFirst("::[a-zA-Z_ ]+(\\[\\])?$", "");
        if (e.equalsIgnoreCase("NULL")) {
            return null;
        }
        boolean my = to == DbType.MYSQL || to == DbType.MARIADB;
        String out;
        if (kind == Kind.BOOLEAN) {
            Boolean b = boolLiteral(e);
            if (b == null) {
                notes.add("Coluna " + col + ": valor padrão " + expr + " não convertido.");
                return null;
            }
            out = to == DbType.SQLSERVER || to == DbType.FIREBIRD_25 || to == DbType.SQLITE ? (b ? "1" : "0")
                    : b ? "TRUE" : "FALSE";
        } else if (NUMBER.matcher(e).matches()) {
            out = e;
        } else if (e.startsWith("'") && e.endsWith("'") && e.length() >= 2 && !e.equalsIgnoreCase("'now'")
                && !e.equalsIgnoreCase("'today'")) {
            out = e;
        } else if (e.startsWith("N'") && e.endsWith("'")) {
            out = e.substring(1);
        } else if (NOW.matcher(e).matches() || TODAY.matcher(e).matches() || CURTIME.matcher(e).matches()) {
            boolean today = TODAY.matcher(e).matches() || kind == Kind.DATE;
            boolean time = CURTIME.matcher(e).matches() || kind == Kind.TIME;
            if (today) {
                out = to == DbType.SQLSERVER ? "CAST(GETDATE() AS DATE)" : my ? "(CURRENT_DATE)" : "CURRENT_DATE";
            } else if (time) {
                out = to == DbType.SQLSERVER ? "CAST(GETDATE() AS TIME)" : my ? "(CURRENT_TIME)" : "CURRENT_TIME";
            } else {
                out = "CURRENT_TIMESTAMP";
            }
        } else if ((from == DbType.MYSQL || from == DbType.MARIADB) && kind.isString() && !e.contains("(")) {
            out = "'" + e.replace("'", "''") + "'"; // MySQL informa o texto sem aspas
        } else if (sameFamily(from, to)) {
            out = e;
        } else {
            notes.add("Coluna " + col + ": valor padrão " + expr + " é do " + from.shortLabel()
                    + " e não foi convertido (ajuste depois no designer).");
            return null;
        }
        if (my && (kind == Kind.TEXT || kind == Kind.BINARY || kind == Kind.JSON) && !out.startsWith("(")) {
            out = "(" + out + ")"; // MySQL 8: texto longo só aceita padrão como expressão
        }
        return out;
    }

    private static boolean balanced(String s) {
        int depth = 0;
        boolean q = false;
        for (char ch : s.toCharArray()) {
            if (ch == '\'') {
                q = !q;
            } else if (!q && ch == '(') {
                depth++;
            } else if (!q && ch == ')' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static Boolean boolLiteral(String e) {
        String v = e.replace("'", "").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "t", "1", "b1", "y", "s", "yes", "sim" -> Boolean.TRUE;
            case "false", "f", "0", "b0", "n", "no", "nao", "não" -> Boolean.FALSE;
            default -> null;
        };
    }

    // =====================================================================
    // Nomes
    // =====================================================================

    static final class Names {
        final DbType to;
        final SqlCompletion.Casing casing;
        final NameStyle style;
        final Set<String> tables = new HashSet<>();

        Names(DbType to, SqlCompletion.Casing casing, NameStyle style) {
            this.to = to;
            this.casing = casing;
            this.style = style;
        }

        String map(String name) {
            String n = name == null ? "" : name.trim();
            if (to.isFirebird()) {
                n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            }
            n = n.replaceAll("[^\\p{L}\\p{N}_$]", "_");
            if (n.isEmpty() || !(Character.isLetter(n.charAt(0)) || n.charAt(0) == '_')) {
                n = "c_" + n;
            }
            n = switch (style) {
                case LOWER -> n.toLowerCase(Locale.ROOT);
                case UPPER -> n.toUpperCase(Locale.ROOT);
                case KEEP -> n;
            };
            n = DdlBuilder.normalizeName(n, casing);
            if (to.isFirebird() && n.length() > 31) {
                n = n.substring(0, 31);
            }
            return n;
        }

        String unique(String n, Set<String> used) {
            String c = n;
            int i = 2;
            while (used.contains(c.toUpperCase(Locale.ROOT))) {
                String suf = "_" + i++;
                String base = to.isFirebird() && n.length() + suf.length() > 31 ? n.substring(0, 31 - suf.length()) : n;
                c = base + suf;
            }
            used.add(c.toUpperCase(Locale.ROOT));
            return c;
        }
    }

    private static String fbName(String n) {
        String u = n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_$]", "_");
        return u.length() > 31 ? u.substring(0, 31) : u;
    }

    private static String id(Plan p, String name) {
        return SqlCompletion.quoteIfNeeded(name, p.casing, p.to);
    }

    private static String tref(Plan p, String name) {
        boolean def = p.targetSchema == null || p.targetSchema.isEmpty() || p.targetSchema.equals(p.targetDefaultSchema);
        return def ? id(p, name) : id(p, p.targetSchema) + "." + id(p, name);
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // =====================================================================
    // Execução
    // =====================================================================

    /** Para onde vão os dados. */
    public interface Target extends AutoCloseable {
        /** Comando de estrutura (no banco: executa; no script: escreve). */
        void ddl(String sql) throws Exception;

        void startTable(Plan p, TablePlan t) throws Exception;

        void insert(Plan p, TablePlan t, List<Object[]> rows) throws Exception;

        void endTable(Plan p, TablePlan t, Object maxIdentity) throws Exception;

        /** Recebe DDL (banco e script); a planilha só recebe dados. */
        default boolean structure() {
            return true;
        }

        @Override
        default void close() throws Exception {
        }
    }

    /** Linha que o destino recusou (para mostrar qual foi). */
    public static final class RowException extends Exception {
        public final Object[] row;

        RowException(Object[] row, Throwable cause) {
            super(cause.getMessage(), cause);
            this.row = row;
        }
    }

    /** Executa o plano lendo da conexão de origem. */
    public static Report run(Plan p, Connection src, Target target, Listener l) throws Exception {
        long t0 = System.currentTimeMillis();
        Listener lis = l == null ? Listener.NONE : l;
        int planNotes = p.notes.size();
        List<String> errors = new ArrayList<>();
        List<String> tableNotes = new ArrayList<>();
        Map<TablePlan, Long> copied = new LinkedHashMap<>();
        Map<TablePlan, String> failed = new HashMap<>();
        for (TablePlan t : p.tables) {
            if (t.error != null) {
                failed.put(t, t.error);
                errors.add(t.source.name() + ": " + t.error);
            }
            t.notes.forEach(n -> tableNotes.add(t.target + " — " + n));
        }
        boolean cancelled = false;
        try {
            if (target.structure()) {
                lis.status("Criando as tabelas…");
                for (String s : p.dropKeys) {
                    try {
                        target.ddl(s);
                    } catch (Exception e) {
                        // a chave pode já ter saído junto com outra tabela
                    }
                }
                for (String s : p.drop) {
                    target.ddl(s);
                }
                for (TablePlan t : p.tables) {
                    if (failed.containsKey(t)) {
                        continue;
                    }
                    lis.status("Criando " + t.target + "…");
                    try {
                        for (String s : t.create) {
                            target.ddl(s);
                        }
                    } catch (Exception e) {
                        failed.put(t, "não foi possível criar: " + msg(e));
                        errors.add(t.source.name() + ": não foi possível criar a tabela — " + msg(e));
                    }
                }
            }
            long total = Math.max(1, p.totalRows());
            long done = 0;
            for (TablePlan t : p.tables) {
                if (failed.containsKey(t)) {
                    continue;
                }
                if (lis.cancelled()) {
                    cancelled = true;
                    break;
                }
                if (!p.options.data()) {
                    copied.put(t, 0L);
                    continue;
                }
                long[] count = {0};
                final long base = done;
                Object[] maxId = {null};
                try {
                    copyTable(p, t, src, target, lis, n -> {
                        count[0] = n;
                        lis.progress((base + n) / (double) total);
                    }, maxId);
                    target.endTable(p, t, maxId[0]);
                } catch (CancelledException e) {
                    cancelled = true;
                    copied.put(t, count[0]);
                    break;
                } catch (RowException e) {
                    failed.put(t, msg(e.getCause()));
                    errors.add(t.source.name() + ": parou em " + count[0] + " de " + t.rows + " linhas — " + msg(e.getCause())
                            + "\n    Linha recusada: " + preview(t, e.row));
                } catch (Exception e) {
                    failed.put(t, msg(e));
                    errors.add(t.source.name() + ": " + msg(e));
                }
                copied.put(t, count[0]);
                done += Math.max(t.rows, count[0]);
            }
            if (target.structure() && !cancelled) {
                if (p.options.indexes()) {
                    for (TablePlan t : p.tables) {
                        if (failed.containsKey(t)) {
                            continue;
                        }
                        for (String s : t.indexes) {
                            lis.status("Criando índices de " + t.target + "…");
                            try {
                                target.ddl(s);
                            } catch (Exception e) {
                                errors.add(t.target + ": índice não criado — " + msg(e) + "\n    " + s);
                            }
                        }
                    }
                }
                if (p.options.foreignKeys()) {
                    Set<String> bad = new HashSet<>();
                    failed.keySet().forEach(t -> bad.add(t.target));
                    for (TablePlan t : p.tables) {
                        if (failed.containsKey(t)) {
                            continue;
                        }
                        for (ForeignKeyPlan f : t.foreignKeys) {
                            if (f.statement() == null) {
                                continue;
                            }
                            if (bad.contains(f.refTable())) {
                                tableNotes.add(t.target + " — chave " + f.name() + " não criada: a tabela " + f.refTable()
                                        + " falhou.");
                                continue;
                            }
                            lis.status("Criando chaves estrangeiras de " + t.target + "…");
                            try {
                                target.ddl(f.statement());
                            } catch (Exception e) {
                                errors.add(t.target + ": chave " + f.name() + " não criada — " + msg(e)
                                        + " (há registros sem o correspondente em " + f.refTable() + "?)");
                            }
                        }
                    }
                }
            }
        } finally {
            target.close();
        }
        List<TableResult> res = new ArrayList<>();
        for (TablePlan t : p.tables) {
            res.add(new TableResult(t.source.name(), t.target, t.rows, copied.getOrDefault(t, 0L), failed.get(t)));
        }
        // planilha: avisos de tipos, chaves e índices não se aplicam; só os da leitura dos dados
        List<String> notes = new ArrayList<>(target.structure() ? p.notes : p.notes.subList(planNotes, p.notes.size()));
        if (target.structure()) {
            notes.addAll(tableNotes);
        }
        return new Report(res, errors, notes, System.currentTimeMillis() - t0, cancelled);
    }

    static final class CancelledException extends Exception {
    }

    private static String msg(Throwable e) {
        Throwable c = e;
        if (c instanceof SQLException se) {
            String m = se.getMessage();
            return m == null ? se.toString() : m.replaceFirst("^ERROR:\\s*", "").trim();
        }
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    private static String preview(TablePlan t, Object[] row) {
        if (row == null) {
            return "?";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length && i < 6; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object v = row[i];
            String s = v == null ? "NULL" : v instanceof byte[] b ? "(" + b.length + " bytes)" : String.valueOf(v);
            sb.append(t.columns.get(i).source).append('=').append(s.length() > 40 ? s.substring(0, 40) + "…" : s);
        }
        if (row.length > 6) {
            sb.append(", …");
        }
        return sb.toString();
    }

    private static void copyTable(Plan p, TablePlan t, Connection src, Target target, Listener lis,
                                  java.util.function.LongConsumer progress, Object[] maxId) throws Exception {
        String cols = String.join(", ", t.columns.stream().map(c -> p.from.quote(c.source)).toList());
        String sql = "SELECT " + cols + " FROM " + p.from.qualified(p.sourceSchema, t.source.name());
        boolean auto = src.getAutoCommit();
        if (p.from == DbType.POSTGRESQL && auto) {
            src.setAutoCommit(false); // no PostgreSQL o cursor só traz aos poucos dentro de uma transação
        }
        Reader reader = new Reader(p, t);
        target.startTable(p, t);
        lis.status("Copiando " + t.target + (t.rows > 0 ? String.format(" (%,d linhas)…", t.rows).replace(',', '.') : "…"));
        long n = 0;
        try (Statement st = src.createStatement()) {
            st.setFetchSize(p.from == DbType.MYSQL || p.from == DbType.MARIADB ? 1000 : 1000);
            try (ResultSet rs = st.executeQuery(sql)) {
                List<Object[]> batch = new ArrayList<>(p.options.batchSize());
                while (rs.next()) {
                    Object[] row = new Object[t.columns.size()];
                    for (int i = 0; i < row.length; i++) {
                        row[i] = reader.read(rs, i + 1, t.columns.get(i));
                    }
                    if (t.identity >= 0 && row[t.identity] instanceof Number num
                            && (maxId[0] == null || num.longValue() > ((Number) maxId[0]).longValue())) {
                        maxId[0] = num.longValue();
                    }
                    batch.add(row);
                    if (batch.size() >= p.options.batchSize()) {
                        target.insert(p, t, batch);
                        n += batch.size();
                        progress.accept(n);
                        batch.clear();
                        if (lis.cancelled()) {
                            throw new CancelledException();
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    target.insert(p, t, batch);
                    n += batch.size();
                    progress.accept(n);
                }
            }
        } finally {
            if (p.from == DbType.POSTGRESQL && auto) {
                try {
                    src.rollback();
                    src.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // leitura apenas
                }
            }
        }
        reader.report(t, n);
    }

    // =====================================================================
    // Leitura dos valores
    // =====================================================================

    private static final class Reader {
        final Plan p;
        final TablePlan t;
        long unreadable;
        long truncated;
        long badDates;

        Reader(Plan p, TablePlan t) {
            this.p = p;
            this.t = t;
        }

        void report(TablePlan t, long rows) {
            if (unreadable > 0) {
                p.notes.add(t.target + " — " + unreadable + " valor(es) que não puderam ser lidos viraram NULL.");
            }
            if (badDates > 0) {
                p.notes.add(t.target + " — " + badDates + " data(s) inválida(s) (ex.: 0000-00-00 ou texto) viraram NULL.");
            }
            if (truncated > 0) {
                p.notes.add(t.target + " — " + truncated + " texto(s) maiores que a coluna foram cortados.");
            }
        }

        Object read(ResultSet rs, int i, ColumnMap c) {
            Object v;
            try {
                v = readRaw(rs, i, c);
            } catch (SQLException | RuntimeException e) {
                if (c.kind == Kind.DATE || c.kind == Kind.TIMESTAMP || c.kind == Kind.TIMESTAMPTZ || c.kind == Kind.TIME) {
                    badDates++;
                } else {
                    unreadable++;
                }
                return null;
            }
            if (v instanceof String s && c.maxChars != null && s.length() > c.maxChars) {
                truncated++;
                v = s.substring(0, c.maxChars);
            }
            return v;
        }

        private Object readRaw(ResultSet rs, int i, ColumnMap c) throws SQLException {
            DbType from = p.from;
            if (c.nativeCopy) {
                return rs.getObject(i);
            }
            switch (c.kind) {
                case BOOLEAN -> {
                    Object o = rs.getObject(i);
                    if (o == null) {
                        return null;
                    }
                    if (o instanceof Boolean b) {
                        return b;
                    }
                    if (o instanceof Number n) {
                        return n.intValue() != 0;
                    }
                    Boolean b = boolLiteral(String.valueOf(o));
                    if (b == null) {
                        unreadable++;
                    }
                    return b;
                }
                case TINYINT, SMALLINT, INTEGER, BIGINT -> {
                    Object o = rs.getObject(i);
                    if (o == null) {
                        return null;
                    }
                    if (o instanceof Boolean b) {
                        return b ? 1L : 0L;
                    }
                    if (o instanceof Number n) {
                        return n instanceof BigDecimal bd ? bd.longValue() : n.longValue();
                    }
                    return new BigDecimal(String.valueOf(o).trim()).longValue();
                }
                case DECIMAL -> {
                    if (from == DbType.SQLITE) {
                        Object o = rs.getObject(i);
                        return o == null ? null : o instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(o).trim());
                    }
                    return rs.getBigDecimal(i);
                }
                case REAL, DOUBLE -> {
                    double d = rs.getDouble(i);
                    return rs.wasNull() ? null : d;
                }
                case BINARY -> {
                    return rs.getBytes(i);
                }
                case DATE, TIME, TIMESTAMP, TIMESTAMPTZ -> {
                    return temporal(rs, i, c.kind);
                }
                default -> {
                    return rs.getString(i);
                }
            }
        }

        private Object temporal(ResultSet rs, int i, Kind k) throws SQLException {
            if (p.from == DbType.SQLITE) {
                Object o = rs.getObject(i);
                if (o == null) {
                    return null;
                }
                Object r = parseTemporal(o, k);
                if (r == null) {
                    badDates++;
                }
                return r;
            }
            switch (k) {
                case DATE -> {
                    try {
                        return rs.getObject(i, LocalDate.class);
                    } catch (SQLException | RuntimeException e) {
                        java.sql.Date d = rs.getDate(i);
                        return d == null ? null : d.toLocalDate();
                    }
                }
                case TIME -> {
                    try {
                        return rs.getObject(i, LocalTime.class);
                    } catch (SQLException | RuntimeException e) {
                        java.sql.Time d = rs.getTime(i);
                        return d == null ? null : d.toLocalTime();
                    }
                }
                case TIMESTAMPTZ -> {
                    try {
                        return rs.getObject(i, OffsetDateTime.class);
                    } catch (SQLException | RuntimeException e) {
                        java.sql.Timestamp d = rs.getTimestamp(i);
                        return d == null ? null : d.toLocalDateTime();
                    }
                }
                default -> {
                    try {
                        return rs.getObject(i, LocalDateTime.class);
                    } catch (SQLException | RuntimeException e) {
                        java.sql.Timestamp d = rs.getTimestamp(i);
                        return d == null ? null : d.toLocalDateTime();
                    }
                }
            }
        }
    }

    private static final DateTimeFormatter ISO_LOOSE = new java.time.format.DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd[ HH:mm[:ss]]")
            .optionalStart().appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .parseDefaulting(java.time.temporal.ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(java.time.temporal.ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(java.time.temporal.ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter();

    /** Datas do SQLite: texto ISO ("2024-05-01 10:00:00"), milissegundos ou dias julianos. */
    static Object parseTemporal(Object o, Kind k) {
        try {
            LocalDateTime dt;
            if (o instanceof Number n) {
                double d = n.doubleValue();
                if (d > 1_000_000 && d < 3_000_000) { // dia juliano
                    long millis = Math.round((d - 2440587.5) * 86_400_000d);
                    dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneOffset.UTC);
                } else {
                    long millis = n.longValue() < 100_000_000_000L ? n.longValue() * 1000 : n.longValue(); // segundos ou ms
                    dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault());
                }
            } else {
                String s = String.valueOf(o).trim().replace('T', ' ');
                if (s.isEmpty()) {
                    return null;
                }
                if (k == Kind.TIME) {
                    return LocalTime.parse(s.length() == 5 ? s + ":00" : s);
                }
                s = s.replaceFirst("(Z|[+-]\\d{2}:?\\d{2})$", "");
                dt = LocalDateTime.parse(s, ISO_LOOSE);
            }
            return switch (k) {
                case DATE -> dt.toLocalDate();
                case TIME -> dt.toLocalTime();
                default -> dt;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    // =====================================================================
    // Destino: outro banco
    // =====================================================================

    /** Grava num banco aberto (comita a cada lote). */
    public static Target database(Connection dst) {
        return new DbTarget(dst);
    }

    private static final class DbTarget implements Target {
        final Connection c;
        PreparedStatement ps;
        boolean restoreAuto;
        int majorVersion;

        DbTarget(Connection c) {
            this.c = c;
            try {
                majorVersion = c.getMetaData().getDatabaseMajorVersion();
            } catch (SQLException e) {
                majorVersion = 0;
            }
        }

        @Override
        public void ddl(String sql) throws Exception {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            if (!c.getAutoCommit()) {
                c.commit();
            }
        }

        @Override
        public void startTable(Plan p, TablePlan t) throws Exception {
            if (p.to == DbType.MYSQL || p.to == DbType.MARIADB) {
                try (Statement st = c.createStatement()) {
                    // id 0 não vira um número novo (sistemas antigos às vezes têm o registro 0)
                    st.execute("SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_AUTO_VALUE_ON_ZERO')");
                }
            }
            if (p.to == DbType.SQLSERVER && t.identity >= 0) {
                ddl("SET IDENTITY_INSERT " + tref(p, t.target) + " ON");
            }
            String cols = String.join(", ", t.columns.stream().map(x -> id(p, x.target)).toList());
            String marks = String.join(", ", t.columns.stream().map(x -> "?").toList());
            ps = c.prepareStatement("INSERT INTO " + tref(p, t.target) + " (" + cols + ") VALUES (" + marks + ")");
            restoreAuto = c.getAutoCommit();
            c.setAutoCommit(false);
        }

        @Override
        public void insert(Plan p, TablePlan t, List<Object[]> rows) throws Exception {
            try {
                for (Object[] r : rows) {
                    bindRow(p, t, ps, r);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                try {
                    ps.clearBatch();
                } catch (SQLException ignored) {
                    // segue
                }
                c.rollback();
                // refaz linha a linha para achar a que o banco recusa (as anteriores ficam gravadas)
                for (Object[] r : rows) {
                    try {
                        bindRow(p, t, ps, r);
                        ps.executeUpdate();
                        c.commit();
                    } catch (SQLException ex) {
                        c.rollback();
                        throw new RowException(r, ex);
                    }
                }
            }
        }

        @Override
        public void endTable(Plan p, TablePlan t, Object maxId) throws Exception {
            try {
                if (ps != null) {
                    ps.close();
                }
            } finally {
                ps = null;
                c.setAutoCommit(restoreAuto);
            }
            if (p.to == DbType.SQLSERVER && t.identity >= 0) {
                ddl("SET IDENTITY_INSERT " + tref(p, t.target) + " OFF");
            }
            for (String s : identityReset(p, t, maxId, majorVersion)) {
                ddl(s);
            }
        }

        @Override
        public void close() {
            if (ps != null) {
                try {
                    ps.close();
                    c.rollback();
                    c.setAutoCommit(restoreAuto);
                } catch (SQLException ignored) {
                    // conexão pode ter caído
                }
            }
        }
    }

    /** Próximo valor do auto incremento depois de copiar ids explícitos. */
    static List<String> identityReset(Plan p, TablePlan t, Object maxId, int majorVersion) {
        if (t.identity < 0 || !(maxId instanceof Number n)) {
            return List.of();
        }
        long max = n.longValue();
        String col = id(p, t.columns.get(t.identity).target);
        String tab = tref(p, t.target);
        return switch (p.to) {
            case H2, POSTGRESQL -> List.of("ALTER TABLE " + tab + " ALTER COLUMN " + col + " RESTART WITH " + (max + 1));
            // Firebird 3: o próximo é o valor + 1; no 4 em diante, o próprio valor
            case FIREBIRD -> List.of("ALTER TABLE " + tab + " ALTER COLUMN " + col + " RESTART WITH "
                    + (majorVersion >= 4 || majorVersion == 0 ? max + 1 : max));
            case FIREBIRD_25 -> List.of("SET GENERATOR " + id(p, t.generator) + " TO " + max);
            default -> List.of(); // MySQL, SQL Server e SQLite ajustam sozinhos
        };
    }

    private static void bindRow(Plan p, TablePlan t, PreparedStatement ps, Object[] r) throws SQLException {
        for (int i = 0; i < r.length; i++) {
            bind(p, t.columns.get(i), ps, i + 1, r[i]);
        }
    }

    private static void bind(Plan p, ColumnMap c, PreparedStatement ps, int idx, Object v) throws SQLException {
        DbType to = p.to;
        if (v == null) {
            ps.setNull(idx, nullType(c, to));
            return;
        }
        Object x = adapt(v, c, to);
        if (to == DbType.POSTGRESQL && x instanceof String
                && (c.targetKind == Kind.JSON || c.targetKind == Kind.UUID || c.nativeCopy)) {
            ps.setObject(idx, x, Types.OTHER);
            return;
        }
        TableEditor.bind(to, ps, idx, x);
    }

    /** Ajusta o valor lido ao tipo do destino. */
    static Object adapt(Object v, ColumnMap c, DbType to) {
        if (v instanceof Boolean b && (to == DbType.FIREBIRD_25 || c.targetKind != Kind.BOOLEAN)) {
            return b ? 1 : 0;
        }
        if (v instanceof OffsetDateTime odt && c.targetKind != Kind.TIMESTAMPTZ) {
            return odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (v instanceof LocalDateTime ldt && c.targetKind == Kind.DATE) {
            return ldt.toLocalDate();
        }
        if (c.targetKind == Kind.UUID && (to == DbType.H2 || to == DbType.POSTGRESQL) && v instanceof String s) {
            try {
                return java.util.UUID.fromString(s.trim());
            } catch (IllegalArgumentException e) {
                return s;
            }
        }
        if (v instanceof java.util.UUID u) {
            return u.toString();
        }
        return v;
    }

    private static int nullType(ColumnMap c, DbType to) {
        if (to == DbType.POSTGRESQL && (c.targetKind == Kind.JSON || c.targetKind == Kind.UUID || c.nativeCopy)) {
            return Types.OTHER;
        }
        return switch (c.targetKind) {
            case BOOLEAN -> to == DbType.SQLSERVER ? Types.BIT : to == DbType.FIREBIRD_25 ? Types.SMALLINT : Types.BOOLEAN;
            case TINYINT, SMALLINT -> Types.SMALLINT;
            case INTEGER -> Types.INTEGER;
            case BIGINT -> Types.BIGINT;
            case DECIMAL -> Types.DECIMAL;
            case REAL -> Types.REAL;
            case DOUBLE -> Types.DOUBLE;
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
            case TIMESTAMPTZ -> to == DbType.SQLSERVER ? -155 : Types.TIMESTAMP_WITH_TIMEZONE;
            case BINARY -> Types.VARBINARY;
            default -> Types.VARCHAR;
        };
    }

    // =====================================================================
    // Destino: script .sql
    // =====================================================================

    /** Escreve um script .sql no dialeto do destino (estrutura + INSERTs). */
    public static Target script(Path file) throws IOException {
        return new ScriptTarget(file);
    }

    private static final class ScriptTarget implements Target {
        final Writer w;
        boolean headerDone;
        final List<String> pending = new ArrayList<>();
        Plan plan;

        ScriptTarget(Path file) throws IOException {
            w = new BufferedWriter(new java.io.OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8), 1 << 16);
        }

        private void header(Plan p) throws IOException {
            if (headerDone) {
                return;
            }
            headerDone = true;
            plan = p;
            w.write("-- Convertido pelo H2 Studio de " + p.from.label() + " para " + p.to.label() + " em "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + "\n");
            w.write("-- Estrutura, dados, auto incremento, índices e chaves estrangeiras (nesta ordem).\n\n");
            switch (p.to) {
                case MYSQL, MARIADB -> w.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS = 0;\n"
                        + "SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_AUTO_VALUE_ON_ZERO');\n\n");
                case SQLITE -> w.write("PRAGMA foreign_keys = OFF;\nBEGIN TRANSACTION;\n\n");
                case POSTGRESQL -> w.write("SET client_encoding = 'UTF8';\nSET standard_conforming_strings = on;\n\n");
                default -> { }
            }
        }

        void statement(Plan p, String sql) throws IOException {
            header(p);
            if (DdlBuilder.isBlock(sql) && p.to.isFirebird()) {
                w.write("SET TERM ^ ;\n" + sql + "^\nSET TERM ; ^\n\n");
            } else if (p.to == DbType.SQLSERVER) {
                w.write(sql + "\nGO\n\n");
            } else {
                w.write(sql + ";\n\n");
            }
        }

        @Override
        public void ddl(String sql) throws Exception {
            pending.add(sql);
        }

        private void flushPending(Plan p) throws IOException {
            for (String s : pending) {
                statement(p, s);
            }
            pending.clear();
        }

        @Override
        public void startTable(Plan p, TablePlan t) throws Exception {
            flushPending(p);
            w.write("-- " + t.target + "\n");
            if (p.to == DbType.SQLSERVER && t.identity >= 0) {
                statement(p, "SET IDENTITY_INSERT " + tref(p, t.target) + " ON");
            }
        }

        @Override
        public void insert(Plan p, TablePlan t, List<Object[]> rows) throws Exception {
            String head = "INSERT INTO " + tref(p, t.target) + " ("
                    + String.join(", ", t.columns.stream().map(x -> id(p, x.target)).toList()) + ") VALUES";
            boolean multi = !p.to.isFirebird();
            int per = p.to == DbType.SQLSERVER ? 500 : 100;
            for (int i = 0; i < rows.size(); i += multi ? per : 1) {
                StringBuilder sb = new StringBuilder(head);
                int end = multi ? Math.min(rows.size(), i + per) : i + 1;
                for (int j = i; j < end; j++) {
                    sb.append(j == i ? "\n  (" : ",\n  (");
                    Object[] r = rows.get(j);
                    for (int k = 0; k < r.length; k++) {
                        if (k > 0) {
                            sb.append(", ");
                        }
                        sb.append(literal(r[k] == null ? null : adapt(r[k], t.columns.get(k), p.to), p.to));
                    }
                    sb.append(')');
                }
                statement(p, sb.toString());
            }
            if (p.to.isFirebird()) {
                w.write("COMMIT;\n\n");
            }
        }

        @Override
        public void endTable(Plan p, TablePlan t, Object maxId) throws Exception {
            if (p.to == DbType.SQLSERVER && t.identity >= 0) {
                statement(p, "SET IDENTITY_INSERT " + tref(p, t.target) + " OFF");
            }
            for (String s : identityReset(p, t, maxId, 0)) {
                statement(p, s);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                if (plan != null || !pending.isEmpty()) {
                    Plan p = plan;
                    if (p != null) {
                        flushPending(p);
                        switch (p.to) {
                            case MYSQL, MARIADB -> w.write("SET FOREIGN_KEY_CHECKS = 1;\n");
                            case SQLITE -> w.write("COMMIT;\nPRAGMA foreign_keys = ON;\n");
                            case FIREBIRD, FIREBIRD_25 -> w.write("COMMIT;\n");
                            default -> { }
                        }
                    }
                }
            } finally {
                w.close();
            }
        }
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

    /** Valor escrito como literal SQL do destino. */
    static String literal(Object v, DbType to) {
        if (v == null) {
            return "NULL";
        }
        boolean my = to == DbType.MYSQL || to == DbType.MARIADB;
        if (v instanceof Boolean b) {
            return to == DbType.SQLSERVER || to == DbType.FIREBIRD_25 || to == DbType.SQLITE ? (b ? "1" : "0")
                    : b ? "TRUE" : "FALSE";
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return "NULL";
        }
        if (v instanceof Float f && (f.isNaN() || f.isInfinite())) {
            return "NULL";
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof byte[] b) {
            StringBuilder hex = new StringBuilder(b.length * 2);
            for (byte x : b) {
                hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            }
            return switch (to) {
                case POSTGRESQL -> "'\\x" + hex + "'";
                case SQLSERVER -> "0x" + hex;
                default -> "X'" + hex + "'";
            };
        }
        String s;
        if (v instanceof LocalDateTime dt) {
            s = to == DbType.SQLITE ? dt.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")) : dt.format(TS);
        } else if (v instanceof OffsetDateTime odt) {
            s = odt.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSxxx"));
        } else if (v instanceof java.sql.Timestamp ts) {
            s = ts.toLocalDateTime().format(TS);
        } else {
            s = String.valueOf(v);
        }
        String esc = s.replace("'", "''");
        if (my) {
            esc = esc.replace("\\", "\\\\");
        }
        return (to == DbType.SQLSERVER && !(v instanceof java.time.temporal.Temporal) ? "N'" : "'") + esc + "'";
    }

    // =====================================================================
    // Destino: planilha
    // =====================================================================

    /** Planilha .xlsx: aba "Resumo" e uma aba por tabela. */
    public static Target spreadsheet(Path file) throws IOException {
        return new XlsxTarget(file);
    }

    private static final class XlsxTarget implements Target {
        final XlsxWriter x;
        boolean first = true;
        final Set<String> sheetNames = new HashSet<>();
        boolean full;

        XlsxTarget(Path file) throws IOException {
            x = new XlsxWriter(file, "Resumo");
        }

        @Override
        public boolean structure() {
            return false;
        }

        @Override
        public void ddl(String sql) {
        }

        @Override
        public void startTable(Plan p, TablePlan t) throws Exception {
            if (first) {
                first = false;
                sheetNames.add("RESUMO");
                x.header(List.of("Tabela", "Linhas", "Colunas"), new int[]{30, 12, 80}, true);
                for (TablePlan tp : p.tables) {
                    x.row(new Object[]{tp.source.name(), tp.rows < 0 ? null : tp.rows,
                            String.join(", ", tp.columns.stream().map(c -> c.source).toList())});
                }
            }
            String name = XlsxWriter.safeSheetName(t.source.name());
            String n = name;
            int i = 2;
            while (!sheetNames.add(n.toUpperCase(Locale.ROOT))) {
                String suf = "_" + i++;
                n = (name.length() + suf.length() > 31 ? name.substring(0, 31 - suf.length()) : name) + suf;
            }
            x.newSheet(n);
            int[] widths = new int[t.columns.size()];
            for (int k = 0; k < widths.length; k++) {
                ColumnMap c = t.columns.get(k);
                widths[k] = switch (c.kind) {
                    case DATE -> 12;
                    case TIMESTAMP, TIMESTAMPTZ -> 19;
                    case BOOLEAN, TINYINT, SMALLINT, INTEGER -> 10;
                    case TEXT -> 50;
                    default -> Math.max(12, Math.min(40, c.source.length() + 2));
                };
            }
            x.header(t.columns.stream().map(c -> c.source).toList(), widths, true);
            full = false;
        }

        @Override
        public void insert(Plan p, TablePlan t, List<Object[]> rows) throws Exception {
            for (Object[] r : rows) {
                if (x.dataRows() >= XlsxWriter.MAX_ROWS - 1) {
                    if (!full) {
                        full = true;
                        p.notes.add(t.source.name() + " — passou do limite de 1.048.576 linhas do Excel; o restante ficou de fora.");
                    }
                    return;
                }
                Object[] o = new Object[r.length];
                for (int k = 0; k < r.length; k++) {
                    Object v = r[k];
                    if (v instanceof OffsetDateTime odt) {
                        v = odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                    } else if (v instanceof byte[] b) {
                        v = "(binário, " + b.length + " bytes)";
                    } else if (v != null && !(v instanceof Number || v instanceof Boolean || v instanceof String
                            || v instanceof java.time.temporal.Temporal)) {
                        v = String.valueOf(v);
                    }
                    o[k] = v;
                }
                x.row(o);
            }
        }

        @Override
        public void endTable(Plan p, TablePlan t, Object maxId) {
        }

        @Override
        public void close() throws Exception {
            if (first) {
                x.header(List.of("Tabela"), new int[]{30}, true);
            }
            if (x.truncatedText()) {
                // avisado pelo XlsxWriter nas células
            }
            x.close();
        }
    }
}
