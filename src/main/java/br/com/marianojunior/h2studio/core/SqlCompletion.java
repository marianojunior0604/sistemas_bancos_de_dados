package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.SqlLexer.Token;
import br.com.marianojunior.h2studio.core.SqlLexer.Type;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sugestões do autocompletar: palavras-chave, funções, schemas, tabelas e colunas,
 * de acordo com o contexto (depois de FROM/JOIN sugere tabelas; depois de
 * "alias." sugere as colunas da tabela daquele alias).
 */
public final class SqlCompletion {

    public enum Kind { COLUMN, TABLE, VIEW, SCHEMA, KEYWORD, FUNCTION }

    /** Uma sugestão. {@code insert} é o texto que substitui a palavra sendo digitada. */
    public record Item(String label, String detail, Kind kind, String insert) {
    }

    /** Resultado: as sugestões e o trecho [replaceStart, caret) que será substituído. */
    public record Result(List<Item> items, int replaceStart, String prefix) {
    }

    /**
     * Como o banco trata nomes sem aspas: UPPER (padrão do H2), LOWER
     * (DATABASE_TO_LOWER=TRUE) ou EXACT (DATABASE_TO_UPPER=FALSE).
     */
    public enum Casing { UPPER, LOWER, EXACT }

    /** Estrutura do banco usada pelo autocompletar. */
    public record Catalog(String defaultSchema, List<String> schemas, List<Meta.TableInfo> tables,
                          Map<String, List<String[]>> columns, Casing casing, DbType type) {

        public Catalog(String defaultSchema, List<String> schemas, List<Meta.TableInfo> tables,
                       Map<String, List<String[]>> columns, Casing casing) {
            this(defaultSchema, schemas, tables, columns, casing, DbType.H2);
        }

        /** Colunas da tabela: lista de {nome, tipo}. Chave = SCHEMA.TABELA. */
        public List<String[]> columnsOf(String schema, String table) {
            return columns.getOrDefault(schema + "." + table, List.of());
        }

        public static Catalog empty() {
            return new Catalog("PUBLIC", List.of(), List.of(), Map.of(), Casing.UPPER);
        }
    }

    private static final int MAX_ITEMS = 200;
    private static final Set<String> TABLE_CONTEXT = Set.of("FROM", "JOIN", "UPDATE", "INTO", "TABLE", "TRUNCATE");

    private SqlCompletion() {
    }

    // =====================================================================
    // Catálogo
    // =====================================================================

    public static Catalog loadCatalog(Connection c, Casing casing) throws SQLException {
        return loadCatalog(c, casing, DbType.H2);
    }

    /**
     * Catálogo de um banco qualquer. Nos servidores com muitos schemas/bancos (mais de 5) lê só o
     * schema em uso, para não varrer o servidor inteiro a cada aba aberta.
     */
    public static Catalog loadCatalog(Connection c, Casing casing, DbType type) throws SQLException {
        if (type == null || type == DbType.H2) {
            return loadH2Catalog(c, casing);
        }
        DatabaseMetaData md = c.getMetaData();
        MetadataService.Namespace ns = MetadataService.namespace(c);
        String current = MetadataService.currentSchema(c);
        List<String> schemas = MetadataService.schemas(c, false);
        List<String> load = schemas.size() <= 5 || current == null || !schemas.contains(current)
                ? schemas : List.of(current);
        List<Meta.TableInfo> tables = new ArrayList<>();
        Map<String, List<String[]>> cols = new HashMap<>();
        for (String s : load) {
            tables.addAll(MetadataService.tables(c, s));
            try (ResultSet rs = md.getColumns(MetadataService.cat(ns, s), MetadataService.sch(ns, s), "%", "%")) {
                while (rs.next()) {
                    if (ns == MetadataService.Namespace.SCHEMA && !s.equals(rs.getString("TABLE_SCHEM"))) {
                        continue;
                    }
                    cols.computeIfAbsent(s + "." + rs.getString("TABLE_NAME"), k -> new ArrayList<>())
                            .add(new String[]{rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")});
                }
            }
        }
        String def = current != null ? current : schemas.isEmpty() ? "" : schemas.get(0);
        return new Catalog(def, schemas, tables, cols, casing, type);
    }

    private static Catalog loadH2Catalog(Connection c, Casing casing) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        List<String> schemas = MetadataService.schemas(c, false);
        Set<String> schemaSet = new LinkedHashSet<>(schemas);
        List<Meta.TableInfo> tables = new ArrayList<>();
        for (String s : schemas) {
            tables.addAll(MetadataService.tables(c, s));
        }
        Map<String, List<String[]>> cols = new HashMap<>();
        try (ResultSet rs = md.getColumns(null, null, "%", "%")) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (!schemaSet.contains(schema)) {
                    continue;
                }
                cols.computeIfAbsent(schema + "." + rs.getString("TABLE_NAME"), k -> new ArrayList<>())
                        .add(new String[]{rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME")});
            }
        }
        String def = schemas.stream().filter(s -> s.equalsIgnoreCase("PUBLIC")).findFirst()
                .orElse(schemas.isEmpty() ? "PUBLIC" : schemas.get(0));
        return new Catalog(def, schemas, tables, cols, casing);
    }

    public static Casing casingOf(ConnectionConfig cfg) {
        switch (cfg.getType()) {
            case POSTGRESQL:
                return Casing.LOWER; // nomes sem aspas viram minúsculas
            case FIREBIRD:
            case FIREBIRD_25:
                return Casing.UPPER;
            case MYSQL:
            case MARIADB:
            case SQLSERVER:
            case SQLITE:
                return Casing.EXACT; // não diferenciam maiúsculas (ou respeitam o que foi escrito)
            default:
                break;
        }
        String o = cfg.getUrlOptions().toUpperCase(Locale.ROOT).replace(" ", "");
        if (o.contains("DATABASE_TO_LOWER=TRUE")) {
            return Casing.LOWER;
        }
        if (o.contains("DATABASE_TO_UPPER=FALSE")) {
            return Casing.EXACT;
        }
        return Casing.UPPER;
    }

    /** Coloca aspas no nome quando ele não seria reconhecido escrito sem aspas. */
    public static String quoteIfNeeded(String name, Casing casing) {
        return quoteIfNeeded(name, casing, DbType.H2);
    }

    /** Idem, com as aspas do banco (`nome`, [nome] ou "nome"). */
    public static String quoteIfNeeded(String name, Casing casing, DbType type) {
        boolean simple = !name.isEmpty() && SqlLexer.isIdentStart(name.charAt(0))
                && name.chars().allMatch(ch -> SqlLexer.isIdentPart((char) ch));
        boolean caseOk = switch (casing) {
            case UPPER -> name.equals(name.toUpperCase(Locale.ROOT));
            case LOWER -> name.equals(name.toLowerCase(Locale.ROOT));
            case EXACT -> true;
        };
        String u = name.toUpperCase(Locale.ROOT);
        boolean reserved = SqlLexer.KEYWORDS.contains(u);
        if (simple && caseOk && !reserved) {
            return name;
        }
        return type == null || type == DbType.H2 ? SqlUtil.quote(name) : type.quote(name);
    }

    // =====================================================================
    // Sugestões
    // =====================================================================

    public static Result complete(String text, int caret, Catalog cat) {
        caret = Math.max(0, Math.min(caret, text.length()));
        int start = caret;
        while (start > 0 && SqlLexer.isIdentPart(text.charAt(start - 1))) {
            start--;
        }
        String prefix = text.substring(start, caret);
        String qualifier = null;
        if (start > 0 && text.charAt(start - 1) == '.') {
            int qEnd = start - 1;
            int qStart = qEnd;
            if (qStart > 0 && text.charAt(qStart - 1) == '"') {
                int open = text.lastIndexOf('"', qStart - 2);
                qStart = open < 0 ? qStart : open;
            } else {
                while (qStart > 0 && SqlLexer.isIdentPart(text.charAt(qStart - 1))) {
                    qStart--;
                }
            }
            if (qStart < qEnd) {
                qualifier = SqlLexer.unquote(text.substring(qStart, qEnd));
            }
        }

        SqlScript.Statement stmt = SqlScript.statementAt(text, caret);
        String stmtText = stmt == null ? "" : stmt.text();
        Map<String, Meta.TableInfo> aliases = tableRefs(stmtText, cat);

        List<Item> items = new ArrayList<>();
        if (qualifier != null) {
            String q = qualifier;
            String schema = cat.schemas().stream().filter(s -> s.equalsIgnoreCase(q)).findFirst().orElse(null);
            Meta.TableInfo t = aliases.get(q.toUpperCase(Locale.ROOT));
            if (t == null) {
                t = findTable(cat, null, q);
            }
            if (t != null) {
                addColumns(items, cat, t, false);
            }
            if (schema != null) {
                for (Meta.TableInfo ti : cat.tables()) {
                    if (ti.schema().equals(schema)) {
                        items.add(tableItem(ti, cat, false));
                    }
                }
            }
        } else {
            String prev = previousWord(text, start);
            boolean tableContext = TABLE_CONTEXT.contains(prev);
            if (tableContext) {
                addTables(items, cat);
                for (String s : cat.schemas()) {
                    items.add(new Item(s, "schema", Kind.SCHEMA, quoteIfNeeded(s, cat.casing(), cat.type())));
                }
            } else {
                // colunas das tabelas usadas na instrução, depois o resto
                Set<Meta.TableInfo> seen = new LinkedHashSet<>(aliases.values());
                for (Meta.TableInfo t : seen) {
                    addColumns(items, cat, t, seen.size() > 1);
                }
                for (String k : SqlLexer.KEYWORDS) {
                    items.add(new Item(k, "palavra-chave", Kind.KEYWORD, k));
                }
                for (String f : SqlLexer.FUNCTIONS) {
                    items.add(new Item(f + "(…)", "função", Kind.FUNCTION, f + "("));
                }
                addTables(items, cat);
            }
        }
        return new Result(filter(items, prefix), start, prefix);
    }

    private static void addTables(List<Item> items, Catalog cat) {
        for (Meta.TableInfo t : cat.tables()) {
            items.add(tableItem(t, cat, !t.schema().equals(cat.defaultSchema())));
        }
    }

    private static Item tableItem(Meta.TableInfo t, Catalog cat, boolean qualify) {
        String name = quoteIfNeeded(t.name(), cat.casing(), cat.type());
        String insert = qualify && !t.schema().isEmpty()
                ? quoteIfNeeded(t.schema(), cat.casing(), cat.type()) + "." + name : name;
        return new Item(qualify ? t.schema() + "." + t.name() : t.name(),
                (t.view() ? "view" : "tabela") + (qualify ? "" : " · " + t.schema()),
                t.view() ? Kind.VIEW : Kind.TABLE, insert);
    }

    private static void addColumns(List<Item> items, Catalog cat, Meta.TableInfo t, boolean showTable) {
        for (String[] col : cat.columnsOf(t.schema(), t.name())) {
            items.add(new Item(col[0], col[1] + (showTable ? " · " + t.name() : ""), Kind.COLUMN,
                    quoteIfNeeded(col[0], cat.casing(), cat.type())));
        }
    }

    /** Filtra pelo que já foi digitado: primeiro quem começa com o prefixo, depois quem contém. */
    private static List<Item> filter(List<Item> items, String prefix) {
        String p = prefix.toUpperCase(Locale.ROOT);
        List<Item> starts = new ArrayList<>();
        List<Item> contains = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        for (Item it : items) {
            String l = it.label().toUpperCase(Locale.ROOT);
            String bare = l.contains(".") ? l.substring(l.lastIndexOf('.') + 1) : l;
            if (!dedupe.add(it.kind() + "|" + it.label() + "|" + it.detail())) {
                continue;
            }
            if (p.isEmpty() || l.startsWith(p) || bare.startsWith(p)) {
                starts.add(it);
            } else if (p.length() >= 2 && l.contains(p)) {
                contains.add(it);
            }
        }
        starts.addAll(contains);
        // não sugere exatamente o que já está escrito
        starts.removeIf(it -> it.insert().equalsIgnoreCase(prefix) && it.kind() == Kind.KEYWORD);
        return starts.size() > MAX_ITEMS ? new ArrayList<>(starts.subList(0, MAX_ITEMS)) : starts;
    }

    /** O cursor está num lugar onde se espera nome de tabela (depois de FROM, JOIN, UPDATE...). */
    public static boolean isTableContext(String text, int caret) {
        int start = Math.max(0, Math.min(caret, text.length()));
        while (start > 0 && SqlLexer.isIdentPart(text.charAt(start - 1))) {
            start--;
        }
        return TABLE_CONTEXT.contains(previousWord(text, start));
    }

    private static String previousWord(String text, int pos) {
        List<Token> tokens = SqlLexer.tokenize(text.substring(0, pos));
        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (!t.significant()) {
                continue;
            }
            if (t.text().equals(",")) {
                // lista do FROM: "FROM a, b" → continua sendo contexto de tabela
                for (int j = i - 1; j >= 0; j--) {
                    Token k = tokens.get(j);
                    if (k.is(Type.KEYWORD) && !k.isWord("AS")) {
                        return k.upper().equals("FROM") ? "FROM" : "";
                    }
                }
                return "";
            }
            return t.upper();
        }
        return "";
    }

    /** Tabelas usadas na instrução: apelido (ou nome) em maiúsculas → tabela. */
    public static Map<String, Meta.TableInfo> tableRefs(String sql, Catalog cat) {
        Map<String, Meta.TableInfo> map = new LinkedHashMap<>();
        List<Token> sig = SqlLexer.tokenize(sql, cat.type()).stream().filter(Token::significant).toList();
        for (int i = 0; i < sig.size(); i++) {
            Token t = sig.get(i);
            boolean fromList = t.isWord("FROM");
            if (!(fromList || t.isWord("JOIN") || t.isWord("UPDATE") || t.isWord("INTO"))) {
                continue;
            }
            int j = i + 1;
            while (j < sig.size()) {
                if (!isName(sig.get(j))) {
                    break;
                }
                String schema = null;
                String name = SqlLexer.unquote(sig.get(j).text());
                if (j + 2 < sig.size() && sig.get(j + 1).text().equals(".") && isName(sig.get(j + 2))) {
                    schema = name;
                    name = SqlLexer.unquote(sig.get(j + 2).text());
                    j += 2;
                }
                j++;
                Meta.TableInfo ti = findTable(cat, schema, name);
                String alias = null;
                if (j < sig.size() && sig.get(j).isWord("AS")) {
                    j++;
                }
                if (j < sig.size() && (sig.get(j).is(Type.IDENT) || sig.get(j).is(Type.QUOTED_IDENT))) {
                    alias = SqlLexer.unquote(sig.get(j).text());
                    j++;
                }
                if (ti != null) {
                    map.put(ti.name().toUpperCase(Locale.ROOT), ti);
                    if (alias != null) {
                        map.put(alias.toUpperCase(Locale.ROOT), ti);
                    }
                }
                if (fromList && j < sig.size() && sig.get(j).text().equals(",")) {
                    j++;
                    continue;
                }
                break;
            }
        }
        return map;
    }

    private static boolean isName(Token t) {
        return t.is(Type.IDENT) || t.is(Type.QUOTED_IDENT) || t.is(Type.TYPE) || t.is(Type.FUNCTION);
    }

    private static Meta.TableInfo findTable(Catalog cat, String schema, String name) {
        Meta.TableInfo loose = null;
        for (Meta.TableInfo t : cat.tables()) {
            boolean schemaOk = schema == null ? t.schema().equals(cat.defaultSchema()) : t.schema().equalsIgnoreCase(schema);
            if (t.name().equals(name) && schemaOk) {
                return t;
            }
            if (t.name().equalsIgnoreCase(name) && (schemaOk || loose == null)) {
                loose = t;
            }
        }
        return loose;
    }
}
