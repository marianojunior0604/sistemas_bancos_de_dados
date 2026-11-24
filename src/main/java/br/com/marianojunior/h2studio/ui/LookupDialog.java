package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.Meta;
import br.com.marianojunior.h2studio.core.MetadataService;
import br.com.marianojunior.h2studio.core.SqlUtil;
import br.com.marianojunior.h2studio.core.TableDataService;
import br.com.marianojunior.h2studio.core.ValueFormatter;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Escolha do valor de uma chave estrangeira: mostra a tabela referenciada
 * com busca, e devolve o valor da coluna referenciada da linha escolhida.
 */
public class LookupDialog extends Dialog<Object> {

    private static final int LIMIT = 200;

    private final DbSession session;
    private final Meta.ColumnInfo fkColumn;
    private final TableView<Object[]> table = new TableView<>();
    private final TextField search = new TextField();
    private final Label info = new Label();
    private List<Meta.ColumnInfo> refColumns = List.of();
    private int keyIndex = -1;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    public LookupDialog(Window owner, DbSession session, Meta.ColumnInfo fkColumn, Object current) {
        this.session = session;
        this.fkColumn = fkColumn;
        initOwner(owner);
        setTitle("Escolher " + fkColumn.name());
        setHeaderText(fkColumn.name() + " → " + fkColumn.fkSchema() + "." + fkColumn.fkTable()
                + " (" + fkColumn.fkColumn() + ")");
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);

        ButtonType choose = new ButtonType("Escolher", ButtonBar.ButtonData.OK_DONE);
        ButtonType setNull = new ButtonType("Deixar vazio (NULL)", ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().add(choose);
        if (fkColumn.nullable()) {
            getDialogPane().getButtonTypes().add(setNull);
        }
        getDialogPane().getButtonTypes().add(Fx.CANCEL);

        search.setPromptText("Buscar em todas as colunas de " + fkColumn.fkTable() + "…");
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        debounce.setOnFinished(e -> load());
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                table.requestFocus();
                table.getSelectionModel().selectFirst();
            }
        });
        info.getStyleClass().add("muted");
        table.setPlaceholder(new Label("Carregando…"));
        table.setPrefSize(760, 420);
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                setResult(selectedValue());
                close();
            }
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && table.getSelectionModel().getSelectedItem() != null) {
                setResult(selectedValue());
                close();
            }
        });
        Label currentLbl = new Label("Valor atual: " + ValueFormatter.format(current));
        currentLbl.getStyleClass().add("muted");
        currentLbl.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(10, search, currentLbl);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox box = new VBox(8, top, table, info);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(6, 4, 0, 4));
        getDialogPane().setContent(box);

        Node ok = getDialogPane().lookupButton(choose);
        ok.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        setResultConverter(bt -> {
            if (bt == choose) {
                return selectedValue();
            }
            if (bt == setNull) {
                return NULL_CHOSEN;
            }
            return null;
        });

        Fx.then(session.submit(c -> MetadataService.columns(c, fkColumn.fkSchema(), fkColumn.fkTable())), cols -> {
            refColumns = cols;
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).name().equals(fkColumn.fkColumn())) {
                    keyIndex = i;
                }
            }
            load();
        }, e -> table.setPlaceholder(new Label("Erro: " + Fx.shortMessage(e))));
        Platform.runLater(search::requestFocus);
    }

    /** Marca a escolha explícita de NULL (o resultado null significa "cancelado"). */
    public static final Object NULL_CHOSEN = new Object();

    private Object selectedValue() {
        Object[] row = table.getSelectionModel().getSelectedItem();
        return row == null || keyIndex < 0 ? null : row[keyIndex];
    }

    private void load() {
        var req = new TableDataService.PageRequest(fkColumn.fkSchema(), fkColumn.fkTable(), null, search.getText(),
                refColumns, fkColumn.fkColumn(), true, 0, LIMIT).withType(session.type());
        Fx.then(session.submit(c -> TableDataService.fetch(c, req)), r -> {
            if (table.getColumns().isEmpty()) {
                buildColumns(r.columns());
            }
            table.getItems().setAll(r.rows());
            table.setPlaceholder(new Label("Nenhum registro encontrado."));
            info.setText(r.total() > r.rows().size()
                    ? "Mostrando " + r.rows().size() + " de " + r.total() + " registros: refine a busca."
                    : r.total() + " registro(s). Duplo clique ou Enter para escolher.");
        }, e -> table.setPlaceholder(new Label("Erro: " + Fx.shortMessage(e))));
    }

    private void buildColumns(List<TableDataService.Column> cols) {
        for (int i = 0; i < cols.size(); i++) {
            final int idx = i;
            TableColumn<Object[], Object> tc = new TableColumn<>(cols.get(i).name());
            tc.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()[idx]));
            boolean numeric = SqlUtil.isNumeric(cols.get(i).jdbcType());
            tc.setCellFactory(c -> new TableCell<>() {
                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : ValueFormatter.format(item));
                }
            });
            if (numeric) {
                tc.setStyle("-fx-alignment: center-right;");
            }
            if (idx == keyIndex) {
                tc.setGraphic(Icons.key());
            }
            tc.setPrefWidth(Math.max(80, Math.min(240, cols.get(i).name().length() * 10 + 40)));
            table.getColumns().add(tc);
        }
    }
}
