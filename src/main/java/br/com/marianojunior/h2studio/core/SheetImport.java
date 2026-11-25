package br.com.marianojunior.h2studio.core;

import br.com.marianojunior.h2studio.core.DbConverter.Kind;
import br.com.marianojunior.h2studio.core.TableDesign.Column;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Importar planilha/CSV como tabela nova: descobre o tipo de cada coluna olhando os dados,
 * sugere nomes válidos e monta o CREATE TABLE no dialeto do banco.
 */
public final class SheetImport {

    /** O que os dados de uma coluna parecem ser. */
    public static final class Guess {
        public final int index;
        public final String header;
        public Kind kind = Kind.VARCHAR;
        public Integer length;
        public Integer scale;
        public long filled;
        public String sample = "";
        /** Motivo de ter ficado como texto (ex.: zeros à esquerda), para mostrar na tela. */
        public String reason;

        Guess(int index, String header) {
            this.index = index;
            this.header = header;
        }
    }

    private static final int[] TEXT_SIZES = {10, 20, 30, 50, 60, 80, 100, 120, 150, 200, 255, 500, 1000, 2000, 4000};

    private SheetImport() {
    }

    /**
     * @param records linhas de dados (sem o cabeçalho)
     * @param header  nomes das colunas (do cabeçalho ou "Coluna N")
     */
    public static List<Guess> guess(List<String[]> records, String[] header) {
        List<Guess> out = new ArrayList<>();
        for (int i = 0; i < header.length; i++) {
            out.add(guessColumn(records, i, header[i]));
        }
        return out;
    }

    private static Guess guessColumn(List<String[]> records, int col, String header) {
        Guess g = new Guess(col, header);
        boolean allBool = true;
        boolean allInt = true;
        boolean allNum = true;
        boolean allDate = true;
        boolean allDateTime = true;
        boolean allTime = true;
        boolean leadingZero = false;
        long maxAbs = 0;
        int maxIntDigits = 1;
        int maxScale = 0;
        int maxLen = 0;
        for (String[] r : records) {
            String v = col < r.length ? r[col] : "";
            if (v == null || v.isBlank()) {
                continue;
            }
            String s = v.trim();
            g.filled++;
            if (g.sample.isEmpty()) {
                g.sample = s.length() > 40 ? s.substring(0, 40) + "…" : s;
            }
            maxLen = Math.max(maxLen, v.length());
            if (allBool) {
                allBool = isBool(s);
            }
            if (allNum || allInt) {
                // "00123", CPF, CEP, telefone: número com zero à esquerda é código, não quantidade
                if (s.length() > 1 && s.charAt(0) == '0' && Character.isDigit(s.charAt(1))) {
                    leadingZero = true;
                    allNum = false;
                    allInt = false;
                } else {
                    BigDecimal bd = number(s);
                    if (bd == null) {
                        allNum = false;
                        allInt = false;
                    } else {
                        bd = bd.stripTrailingZeros();
                        int scale = Math.max(0, bd.scale());
                        int intDigits = Math.max(1, bd.precision() - bd.scale());
                        maxScale = Math.max(maxScale, scale);
                        maxIntDigits = Math.max(maxIntDigits, intDigits);
                        if (scale > 0 || intDigits > 18) {
                            allInt = false;
                        } else {
                            maxAbs = Math.max(maxAbs, bd.abs().longValue());
                        }
                    }
                }
            }
            if (allDate || allDateTime) {
                boolean hasTime = s.matches(".*\\d{1,2}:\\d{2}.*");
                if (!hasTime && parses(() -> ValueParser.parseDate(s))) {
                    // data pura: serve como data e como data/hora
                } else if (hasTime && parses(() -> ValueParser.parseDateTime(s)) && s.matches(".*\\d{4}.*")) {
                    allDate = false;
                } else {
                    allDate = false;
                    allDateTime = false;
                }
            }
            if (allTime) {
                allTime = s.matches("\\d{1,2}:\\d{2}(:\\d{2})?") && parses(() -> ValueParser.parseTime(
                        s.length() == 4 || s.length() == 7 ? "0" + s : s));
            }
        }
        if (g.filled == 0) {
            g.kind = Kind.VARCHAR;
            g.length = 255;
            g.reason = "coluna vazia";
            return g;
        }
        if (allBool) {
            g.kind = Kind.BOOLEAN;
        } else if (allInt) {
            g.kind = maxAbs > Integer.MAX_VALUE ? Kind.BIGINT : Kind.INTEGER;
        } else if (allNum) {
            g.kind = Kind.DECIMAL;
            int scale = Math.min(Math.max(maxScale, 2), 10);
            g.scale = scale;
            g.length = Math.max(maxIntDigits + scale, 15);
        } else if (allTime) {
            g.kind = Kind.TIME;
        } else if (allDate) {
            g.kind = Kind.DATE;
        } else if (allDateTime) {
            g.kind = Kind.TIMESTAMP;
        } else {
            int size = 0;
            for (int s : TEXT_SIZES) {
                if (maxLen <= s) {
                    size = s;
                    break;
                }
            }
            if (size == 0) {
                g.kind = Kind.TEXT;
            } else {
                g.kind = Kind.VARCHAR;
                g.length = size;
            }
            if (leadingZero) {
                g.reason = "tem zeros à esquerda (código, CPF, CEP…): fica como texto";
            }
        }
        return g;
    }

    private interface Check {
        Object run() throws ValueParser.InvalidValueException;
    }

    private static boolean parses(Check c) {
        try {
            return c.run() != null;
        } catch (ValueParser.InvalidValueException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isBool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "false", "sim", "não", "nao", "verdadeiro", "falso" -> true;
            default -> false;
        };
    }

    private static BigDecimal number(String s) {
        try {
            return new BigDecimal(ValueParser.normalizeNumber(s));
        } catch (ValueParser.InvalidValueException | NumberFormatException e) {
            return null;
        }
    }

    // =====================================================================
    // Nomes e tipos
    // =====================================================================

    /** "Data de Nascimento" → data_de_nascimento (DATA_DE_NASCIMENTO no Firebird/H2). */
    public static String columnName(String raw, int index, DbType to, SqlCompletion.Casing casing) {
        String n = raw == null ? "" : Normalizer.normalize(raw.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (n.isEmpty()) {
            n = "coluna_" + (index + 1);
        } else if (Character.isDigit(n.charAt(0))) {
            n = "c_" + n;
        }
        if (casing == SqlCompletion.Casing.UPPER) {
            n = n.toUpperCase(Locale.ROOT);
        }
        int max = to != null && to.isFirebird() ? 31 : 60;
        return n.length() > max ? n.substring(0, max) : n;
    }

    /** Nomes únicos para todas as colunas (sufixo _2, _3… nas repetidas). */
    public static List<String> columnNames(String[] header, DbType to, SqlCompletion.Casing casing) {
        List<String> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < header.length; i++) {
            String base = columnName(header[i], i, to, casing);
            String n = base;
            for (int k = 2; !used.add(n.toUpperCase(Locale.ROOT)); k++) {
                String suf = "_" + k;
                n = (to != null && to.isFirebird() && base.length() + suf.length() > 31
                        ? base.substring(0, 31 - suf.length()) : base) + suf;
            }
            out.add(n);
        }
        return out;
    }

    /** Tipo sugerido no dialeto do banco (ex.: "VARCHAR(60)", "NUMERIC(15, 2)", "DATETIME"). */
    public static String typeFor(Guess g, DbType to) {
        return DbConverter.targetType(g.kind, g.length, g.scale, to).display();
    }

    private static final Pattern TYPE = Pattern.compile("(?i)^\\s*([A-Z][A-Z0-9_ ]*?)\\s*(\\(\\s*(\\d+)\\s*(,\\s*(\\d+)\\s*)?\\))?\\s*$");

    /** Coluna a partir do texto do tipo digitado/escolhido na tela. */
    public static Column column(String name, String typeText) {
        Column c = new Column();
        c.name = name;
        c.nullable = true;
        String t = typeText == null ? "" : typeText.trim();
        Matcher m = TYPE.matcher(t);
        if (m.matches() && !t.toUpperCase(Locale.ROOT).contains("MAX")) {
            c.type = m.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            c.length = m.group(3) == null ? null : Integer.valueOf(m.group(3));
            c.scale = m.group(5) == null ? null : Integer.valueOf(m.group(5));
        } else {
            c.type = t.toUpperCase(Locale.ROOT); // VARCHAR(MAX), NVARCHAR(MAX)…
            c.length = null;
        }
        return c;
    }

    /** Coluna "id" com auto incremento, chave primária. */
    public static Column idColumn(DbType to, SqlCompletion.Casing casing) {
        TableDesign d = TableDesign.newTable("", casing, to);
        return d.columns.get(0);
    }

    /** Nome sugerido para a tabela (nome do arquivo ou da aba). */
    public static String tableName(String raw, DbType to, SqlCompletion.Casing casing) {
        String n = columnName(raw.replaceAll("(?i)\\.(xlsx|csv|txt)$", ""), 0, to, casing);
        return n.startsWith("coluna_") || n.startsWith("COLUNA_") ? DdlBuilder.normalizeName("importada", casing) : n;
    }

    /** CREATE TABLE (e o que mais o banco precisar) para a tabela nova. */
    public static DdlBuilder.Plan createPlan(String schema, String defaultSchema, String table, List<Column> columns,
                                             DbType to, SqlCompletion.Casing casing, Set<String> existing) {
        TableDesign d = new TableDesign();
        d.schema = schema;
        d.name = table;
        d.columns.addAll(columns);
        return DdlBuilder.build(d, casing, defaultSchema, existing, to);
    }

    /** Resultado de criar + importar: a tabela criada e as linhas, ou os erros (e a tabela é apagada). */
    public record NewTableResult(String table, CsvImporter.Result result, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Cria a tabela (comandos do plano), confere todas as linhas contra as colunas reais e
     * importa numa transação. Se alguma linha não servir ou o banco recusar, apaga a tabela
     * criada: nada fica pela metade.
     *
     * @param fileColumnTarget para cada coluna do arquivo, o nome da coluna criada (null = ignorar)
     */
    public static NewTableResult createAndImport(java.sql.Connection c, DbType type, String schema, String table,
                                                 DdlBuilder.Plan plan, List<String> fileColumnTarget,
                                                 List<String[]> records, int[] lines, CsvImporter.Progress progress)
            throws Exception {
        for (String s : plan.statements()) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute(s);
            }
        }
        if (!c.getAutoCommit()) {
            c.commit();
        }
        String real = MetadataService.tables(c, schema).stream().map(Meta.TableInfo::name)
                .filter(n -> n.equalsIgnoreCase(table)).findFirst().orElse(table);
        try {
            List<Meta.ColumnInfo> cols = MetadataService.columns(c, schema, real);
            List<Meta.ColumnInfo> map = new ArrayList<>();
            for (String target : fileColumnTarget) {
                map.add(target == null ? null : cols.stream().filter(x -> x.name().equalsIgnoreCase(target)).findFirst().orElse(null));
            }
            CsvImporter.Prepared prep = CsvImporter.prepare(records, lines, map, cols);
            if (!prep.errors().isEmpty()) {
                drop(c, type, schema, real);
                return new NewTableResult(real, null, prep.errors().stream().map(CsvImporter.RowError::toString).toList());
            }
            CsvImporter.Result r = CsvImporter.run(c, type, schema, real, map, prep, CsvImporter.Mode.APPEND, progress);
            return new NewTableResult(real, r, List.of());
        } catch (Exception e) {
            drop(c, type, schema, real);
            throw e;
        }
    }

    private static void drop(java.sql.Connection c, DbType type, String schema, String table) {
        String t = type == DbType.H2 ? SqlUtil.quote(schema) + "." + SqlUtil.quote(table) : type.qualified(schema, table);
        try (java.sql.Statement st = c.createStatement()) {
            st.execute("DROP TABLE " + t);
            if (!c.getAutoCommit()) {
                c.commit();
            }
        } catch (Exception ignored) {
            // a tabela pode nem ter sido criada
        }
    }
}
