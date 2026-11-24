package br.com.marianojunior.h2studio.core;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Leitura da tabela {@code flyway_schema_history} dos sistemas que usam Flyway. */
public final class FlywayInfo {

    public record Migration(int rank, String version, String description, String type, String script,
                            String installedBy, String installedOn, int executionMs, boolean success) {
    }

    private FlywayInfo() {
    }

    /** Procura a tabela de histórico do Flyway em qualquer schema (o nome pode estar em minúsculas). */
    public static Optional<Meta.TableInfo> findHistoryTable(Connection c) throws SQLException {
        for (String schema : MetadataService.schemas(c, false)) {
            for (Meta.TableInfo t : MetadataService.tables(c, schema)) {
                if (t.name().equalsIgnoreCase("flyway_schema_history") || t.name().equalsIgnoreCase("schema_version")) {
                    return Optional.of(t);
                }
            }
        }
        return Optional.empty();
    }

    public static List<Migration> read(Connection c, Meta.TableInfo t) throws SQLException {
        List<Migration> list = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + t.qualified())) {
            ResultSetMetaData md = rs.getMetaData();
            Map<String, Integer> col = new HashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                col.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
            }
            while (rs.next()) {
                list.add(new Migration(
                        intOf(rs, col, "installed_rank"), str(rs, col, "version"), str(rs, col, "description"),
                        str(rs, col, "type"), str(rs, col, "script"), str(rs, col, "installed_by"),
                        ValueFormatter.format(obj(rs, col, "installed_on")), intOf(rs, col, "execution_time"),
                        boolOf(rs, col, "success")));
            }
        }
        list.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        return list;
    }

    private static Object obj(ResultSet rs, Map<String, Integer> col, String name) throws SQLException {
        Integer i = col.get(name);
        return i == null ? null : rs.getObject(i);
    }

    private static String str(ResultSet rs, Map<String, Integer> col, String name) throws SQLException {
        Object o = obj(rs, col, name);
        return o == null ? null : String.valueOf(o);
    }

    private static int intOf(ResultSet rs, Map<String, Integer> col, String name) throws SQLException {
        Object o = obj(rs, col, name);
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static boolean boolOf(ResultSet rs, Map<String, Integer> col, String name) throws SQLException {
        Object o = obj(rs, col, name);
        return o instanceof Boolean b ? b : o instanceof Number n ? n.intValue() != 0 : o == null || "true".equalsIgnoreCase(o.toString());
    }

    /**
     * Próxima versão: soma 1 na última parte da maior versão (1.9 → 1.10, 12 → 13,
     * 2026.11.01 → 2026.11.2). Sem migrações, começa em 1.
     */
    public static String nextVersion(List<Migration> migrations) {
        String max = null;
        for (Migration m : migrations) {
            if (m.version() != null && !m.version().isBlank() && (max == null || compare(m.version(), max) > 0)) {
                max = m.version();
            }
        }
        if (max == null) {
            return "1";
        }
        String[] parts = max.split("[._]");
        try {
            parts[parts.length - 1] = new BigInteger(parts[parts.length - 1]).add(BigInteger.ONE).toString();
        } catch (NumberFormatException e) {
            return max + ".1";
        }
        return String.join(max.contains("_") && !max.contains(".") ? "_" : ".", parts);
    }

    /** Compara versões do Flyway numericamente parte por parte. */
    public static int compare(String a, String b) {
        String[] x = a.split("[._]");
        String[] y = b.split("[._]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            BigInteger p = i < x.length ? num(x[i]) : BigInteger.ZERO;
            BigInteger q = i < y.length ? num(y[i]) : BigInteger.ZERO;
            int c = p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static BigInteger num(String s) {
        try {
            return new BigInteger(s.replaceAll("\\D", "").isEmpty() ? "0" : s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    /** Nome do arquivo de migração: V13__alterar_tabela_cliente.sql */
    public static String fileName(String version, String description) {
        String slug = Normalizer.normalize(description == null ? "" : description, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return "V" + version + "__" + (slug.isEmpty() ? "alteracao" : slug) + ".sql";
    }
}
