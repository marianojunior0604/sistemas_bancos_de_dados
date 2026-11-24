package br.com.marianojunior.h2studio.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Executa uma instrução SQL qualquer e devolve o resultado (linhas ou quantidade afetada). */
public final class SqlRunner {

    private SqlRunner() {
    }

    /**
     * @param query      true quando a instrução devolveu linhas
     * @param truncated  havia mais linhas do que o limite
     * @param updateCount linhas afetadas (-1 quando não se aplica)
     */
    public record Result(String sql, boolean query, List<TableDataService.Column> columns, List<Object[]> rows,
                         boolean truncated, long updateCount, long elapsedMs) {

        public String summary() {
            if (query) {
                return rows.size() + (rows.size() == 1 ? " linha" : " linhas")
                        + (truncated ? " (limitado; há mais)" : "") + " · " + elapsedMs + " ms";
            }
            if (updateCount >= 0) {
                return updateCount + (updateCount == 1 ? " linha afetada" : " linhas afetadas") + " · " + elapsedMs + " ms";
            }
            return "Executado · " + elapsedMs + " ms";
        }
    }

    /**
     * Executa a instrução.
     *
     * @param onStatement recebe o Statement antes da execução (para permitir cancelar)
     */
    public static Result run(Connection c, String sql, int maxRows, Consumer<Statement> onStatement) throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Statement st = c.createStatement()) {
            if (onStatement != null) {
                onStatement.accept(st);
            }
            if (maxRows > 0) {
                st.setMaxRows(maxRows + 1); // uma a mais para saber se há mais linhas
            }
            boolean hasResult = st.execute(sql);
            long firstCount = hasResult ? -1 : st.getLargeUpdateCount();
            if (!hasResult) {
                // procedures do SQL Server/MySQL podem devolver contagens antes das linhas: procura o resultado
                long count = firstCount;
                for (int guard = 0; count != -1 && guard < 50; guard++) {
                    try {
                        hasResult = st.getMoreResults();
                    } catch (SQLException e) {
                        break;
                    }
                    if (hasResult) {
                        break;
                    }
                    count = st.getLargeUpdateCount();
                }
            }
            if (hasResult) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<TableDataService.Column> cols = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) {
                        cols.add(new TableDataService.Column(md.getColumnLabel(i), md.getColumnType(i), md.getColumnTypeName(i)));
                    }
                    List<Object[]> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (maxRows > 0 && rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        Object[] row = new Object[n];
                        for (int i = 1; i <= n; i++) {
                            row[i - 1] = TableDataService.readValue(rs, i);
                        }
                        rows.add(row);
                    }
                    return new Result(sql, true, cols, rows, truncated, -1, System.currentTimeMillis() - t0);
                }
            }
            long count = firstCount;
            if (SqlScript.classify(sql) == SqlScript.Kind.DDL && count == 0) {
                count = -1; // CREATE/ALTER/DROP: "0 linhas afetadas" confunde
            }
            return new Result(sql, false, List.of(), List.of(), false, count, System.currentTimeMillis() - t0);
        }
    }
}
