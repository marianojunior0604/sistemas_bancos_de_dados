package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.SqlHistory;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Histórico de instruções de um banco: busca, visualização e reaproveitamento. */
public class HistoryDialog extends Dialog<String> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public HistoryDialog(Window owner, String dbName, SqlHistory history) {
        initOwner(owner);
        setTitle("Histórico de SQL");
        setHeaderText("Histórico de SQL — " + dbName);
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);

        List<SqlHistory.Entry> all = history.list();
        TextField search = new TextField();
        search.setPromptText("Buscar no histórico…");
        ListView<SqlHistory.Entry> list = new ListView<>();
        list.getStyleClass().add("history-list");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(SqlHistory.Entry e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().remove("history-error");
                if (empty || e == null) {
                    setText(null);
                    return;
                }
                String first = e.sql().replaceAll("\\s+", " ");
                if (first.length() > 120) {
                    first = first.substring(0, 120) + "…";
                }
                String info = (e.ok() ? "" : "ERRO · ") + e.info();
                setText(first + "\n" + e.when().format(FMT) + (info.isBlank() ? "" : " · " + info));
                if (!e.ok()) {
                    getStyleClass().add("history-error");
                }
            }
        });
        list.getItems().setAll(all);
        list.setPlaceholder(new Label("Nenhuma instrução executada neste banco ainda."));
        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.getStyleClass().add("mono");
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> preview.setText(b == null ? "" : b.sql()));
        search.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.toLowerCase(Locale.ROOT).trim();
            list.getItems().setAll(all.stream().filter(e -> q.isEmpty() || e.sql().toLowerCase(Locale.ROOT).contains(q)).toList());
            list.getSelectionModel().selectFirst();
        });
        SplitPane split = new SplitPane(list, preview);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        split.setPrefSize(780, 520);
        Label file = new Label("Arquivo: " + history.file());
        file.getStyleClass().add("muted");
        VBox box = new VBox(8, search, split, file);
        box.setPadding(new Insets(6, 4, 0, 4));
        getDialogPane().setContent(box);

        ButtonType insert = new ButtonType("Inserir no editor", ButtonBar.ButtonData.OK_DONE);
        ButtonType copy = new ButtonType("Copiar", ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(insert, copy, Fx.CLOSE);
        getDialogPane().lookupButton(insert).disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        getDialogPane().lookupButton(copy).disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        // "Copiar" não fecha o diálogo
        getDialogPane().lookupButton(copy).addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            Fx.copy(preview.getText());
            ev.consume();
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                setResult(list.getSelectionModel().getSelectedItem().sql());
                close();
            }
        });
        setResultConverter(bt -> bt == insert && list.getSelectionModel().getSelectedItem() != null
                ? list.getSelectionModel().getSelectedItem().sql() : null);
        list.getSelectionModel().selectFirst();
        Platform.runLater(search::requestFocus);
    }
}
