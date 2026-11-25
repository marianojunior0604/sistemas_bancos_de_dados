package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.ConnectionConfig;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Tela inicial: abrir arquivo H2, conectar a outro banco, arrastar arquivo, conexões recentes. */
public class WelcomePane extends VBox {

    private final ListView<ConnectionConfig> recent = new ListView<>();
    private final Label recentTitle = new Label("Abertos recentemente");

    public WelcomePane(Runnable onOpen, Runnable onConnect, Consumer<ConnectionConfig> onOpenRecent,
                       Consumer<ConnectionConfig> onRemove) {
        getStyleClass().add("welcome");
        setAlignment(Pos.TOP_CENTER);
        setSpacing(14);
        setPadding(new Insets(40, 40, 30, 40));

        Label title = new Label("H2 Studio");
        title.getStyleClass().add("welcome-title");
        Label sub = new Label("H2 (1.4, 2.1 e 2.2+) · PostgreSQL · MySQL · MariaDB · Firebird · SQL Server · SQLite");
        sub.getStyleClass().add("muted");
        Button open = new Button("Abrir arquivo H2…");
        open.getStyleClass().add("primary");
        open.setGraphic(Icons.open());
        open.setOnAction(e -> onOpen.run());
        Button connect = new Button("Conectar a servidor ou SQLite…");
        connect.setGraphic(Icons.database());
        connect.setOnAction(e -> onConnect.run());
        HBox buttons = new HBox(10, open, connect);
        buttons.setAlignment(Pos.CENTER);

        VBox drop = new VBox(8, Icons.databaseLarge(), new Label("Arraste para cá um banco .mv.db, .h2.db, SQLite ou um backup .fbk"));
        drop.setAlignment(Pos.CENTER);
        drop.getStyleClass().add("drop-zone");
        drop.setMaxWidth(560);
        drop.setPadding(new Insets(22));

        recentTitle.getStyleClass().add("section-title");
        recent.setMaxWidth(560);
        recent.setPrefHeight(260);
        recent.getStyleClass().add("recent-list");
        recent.setPlaceholder(new Label("Nenhum banco aberto ainda."));
        recent.setCellFactory(v -> new RecentCell(onRemove));
        recent.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && recent.getSelectionModel().getSelectedItem() != null) {
                onOpenRecent.accept(recent.getSelectionModel().getSelectedItem());
            }
        });
        recent.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && recent.getSelectionModel().getSelectedItem() != null) {
                onOpenRecent.accept(recent.getSelectionModel().getSelectedItem());
            }
        });
        VBox.setVgrow(recent, Priority.SOMETIMES);

        HBox titleRow = new HBox(recentTitle);
        titleRow.setMaxWidth(560);
        getChildren().addAll(title, sub, buttons, drop, titleRow, recent);
    }

    public void setRecent(List<ConnectionConfig> list) {
        recent.getItems().setAll(list);
    }

    private static final class RecentCell extends ListCell<ConnectionConfig> {
        private final Consumer<ConnectionConfig> onRemove;
        private static final SimpleDateFormat FMT = new SimpleDateFormat("dd/MM/yyyy HH:mm");

        RecentCell(Consumer<ConnectionConfig> onRemove) {
            this.onRemove = onRemove;
        }

        @Override
        protected void updateItem(ConnectionConfig c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                setText(null);
                setContextMenu(null);
                return;
            }
            Label name = new Label(c.getName());
            name.getStyleClass().add("recent-name");
            Label path = new Label(c.isH2() ? c.location() : c.getType().shortLabel() + " · " + c.location());
            path.getStyleClass().add("muted");
            boolean exists = c.fileExists();
            if (!exists) {
                path.setText(path.getText() + "  (arquivo não encontrado)");
                path.getStyleClass().add("text-error");
            }
            VBox left = new VBox(2, name, path);
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Label when = new Label(c.getLastOpened() > 0 ? FMT.format(new Date(c.getLastOpened())) : "");
            when.getStyleClass().add("muted");
            name.setMinWidth(0);
            path.setMinWidth(0);
            left.setMinWidth(0);
            HBox.setHgrow(left, Priority.ALWAYS);
            when.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = new HBox(10, Icons.database(), left, sp, when);
            row.setAlignment(Pos.CENTER_LEFT);
            setPrefWidth(0); // evita rolagem horizontal: a célula acompanha a largura da lista
            setText(null);
            setGraphic(row);
            MenuItem remove = new MenuItem("Remover da lista");
            remove.setOnAction(e -> onRemove.accept(c));
            setContextMenu(new ContextMenu(remove));
        }
    }
}
