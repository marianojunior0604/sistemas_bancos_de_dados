package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.SqlRunner;
import br.com.marianojunior.h2studio.core.SqlUtil;
import br.com.marianojunior.h2studio.core.TableDataService;
import br.com.marianojunior.h2studio.core.ValueFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Resultado de uma consulta do editor SQL (somente leitura). */
public class ResultView extends BorderPane {

    private final List<String> names = new ArrayList<>();
    private final TableView<Object[]> table = new TableView<>();

    public ResultView(SqlRunner.Result r, Runnable onExport) {
        getStyleClass().add("result-view");
        Label info = new Label(r.summary());
        info.getStyleClass().add("muted");
        if (r.truncated()) {
            info.setText(r.summary() + " — aumente \"Máx. linhas\" para ver mais");
            info.getStyleClass().add("text-warn");
        }
        Button copyAll = new Button("Copiar tudo");
        copyAll.setTooltip(new javafx.scene.control.Tooltip("Copia todas as linhas com cabeçalho (cola direto no Excel)"));
        copyAll.setOnAction(e -> copyRows(r.rows()));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button export = new Button("Exportar…");
        export.setTooltip(new javafx.scene.control.Tooltip("Excel ou CSV, com TODAS as linhas da consulta (sem o limite de exibição)"));
        export.setOnAction(e -> onExport.run());
        export.setVisible(onExport != null && r.query());
        export.setManaged(export.isVisible());
        HBox bar = new HBox(8, info, sp, copyAll, export);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        setTop(bar);

        boolean plan = r.columns().size() == 1 && r.columns().get(0).name().equalsIgnoreCase("PLAN");
        if (plan) {
            // EXPLAIN: o plano é um texto longo
            StringBuilder sb = new StringBuilder();
            for (Object[] row : r.rows()) {
                sb.append(ValueFormatter.format(row[0])).append("\n\n");
            }
            TextArea ta = new TextArea(sb.toString().trim());
            ta.setEditable(false);
            ta.getStyleClass().addAll("mono", "plan-text");
            setCenter(ta);
            return;
        }

        table.getSelectionModel().setCellSelectionEnabled(true);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("A consulta não retornou linhas."));
        TableColumn<Object[], Object> num = new TableColumn<>("#");
        num.setSortable(false);
        num.getStyleClass().add("row-number-column");
        num.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || getIndex() < 0 ? null : String.valueOf(getIndex() + 1));
            }
        });
        num.setPrefWidth(52);
        table.getColumns().add(num);
        for (int i = 0; i < r.columns().size(); i++) {
            final int idx = i;
            TableDataService.Column col = r.columns().get(i);
            names.add(col.name());
            TableColumn<Object[], Object> tc = new TableColumn<>(col.name());
            tc.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()[idx]));
            boolean numeric = SqlUtil.isNumeric(col.jdbcType());
            tc.setCellFactory(c -> new TableCell<>() {
                {
                    if (numeric) {
                        getStyleClass().add("cell-number");
                    }
                }

                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().remove("cell-null");
                    if (empty) {
                        setText(null);
                        return;
                    }
                    String t = ValueFormatter.format(item);
                    int nl = t.indexOf('\n');
                    setText(nl >= 0 ? t.substring(0, nl) + " ↵…" : t);
                    if (item == null) {
                        getStyleClass().add("cell-null");
                    }
                }
            });
            // a ordenação aqui é só da página exibida (comparando o texto/número)
            tc.setComparator(ResultView::compare);
            tc.setPrefWidth(Math.max(80, Math.min(260, col.name().length() * 9 + 40)));
            table.getColumns().add(tc);
        }
        table.getItems().setAll(r.rows());
        table.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
                copySelection();
                e.consume();
            }
        });
        MenuItem copy = new MenuItem("Copiar");
        copy.setOnAction(e -> copySelection());
        MenuItem copyRowsItem = new MenuItem("Copiar linhas (com cabeçalho)");
        copyRowsItem.setOnAction(e -> {
            TreeSet<Integer> idx = new TreeSet<>();
            table.getSelectionModel().getSelectedCells().forEach(p -> idx.add(p.getRow()));
            List<Object[]> rows = new ArrayList<>();
            idx.forEach(i -> rows.add(table.getItems().get(i)));
            copyRows(rows);
        });
        MenuItem view = new MenuItem("Ver valor completo…");
        view.setOnAction(e -> showValue());
        table.setContextMenu(new ContextMenu(copy, copyRowsItem, view));
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                showValue();
            }
        });
        setCenter(table);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        if (a instanceof Comparable ca && a.getClass().isInstance(b)) {
            return ca.compareTo(b);
        }
        return ValueFormatter.format(a).compareToIgnoreCase(ValueFormatter.format(b));
    }

    @SuppressWarnings("rawtypes")
    private void copySelection() {
        List<TablePosition> cells = new ArrayList<>(table.getSelectionModel().getSelectedCells());
        cells.sort((a, b) -> a.getRow() != b.getRow() ? Integer.compare(a.getRow(), b.getRow())
                : Integer.compare(a.getColumn(), b.getColumn()));
        StringBuilder sb = new StringBuilder();
        int row = -1;
        for (TablePosition p : cells) {
            if (row >= 0) {
                sb.append(p.getRow() != row ? "\n" : "\t");
            }
            row = p.getRow();
            int c = p.getColumn();
            Object v = c <= 0 ? (Object) (p.getRow() + 1) : table.getItems().get(p.getRow())[c - 1];
            sb.append(v == null ? "" : ValueFormatter.format(v));
        }
        Fx.copy(sb.toString());
    }

    private void copyRows(List<Object[]> rows) {
        StringBuilder sb = new StringBuilder(String.join("\t", names)).append('\n');
        for (Object[] r : rows) {
            for (int i = 0; i < r.length; i++) {
                sb.append(i > 0 ? "\t" : "").append(r[i] == null ? "" : ValueFormatter.format(r[i]));
            }
            sb.append('\n');
        }
        Fx.copy(sb.toString());
    }

    @SuppressWarnings("rawtypes")
    private void showValue() {
        TablePosition p = table.getFocusModel().getFocusedCell();
        if (p == null || p.getRow() < 0 || p.getColumn() <= 0) {
            return;
        }
        Object v = table.getItems().get(p.getRow())[p.getColumn() - 1];
        String text = v instanceof TableDataService.LongText lt
                ? lt.preview() + (lt.length() > lt.preview().length()
                ? "\n\n[… exibindo " + lt.preview().length() + " de " + lt.length() + " caracteres]" : "")
                : ValueFormatter.format(v);
        new TextEditDialog(getScene().getWindow(), names.get(p.getColumn() - 1) + " — linha " + (p.getRow() + 1),
                text, false, false, 0).showAndWait();
    }
}
