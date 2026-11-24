package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.AppPreferences;
import br.com.marianojunior.h2studio.core.BackupService;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.MaintenanceService;
import br.com.marianojunior.h2studio.core.ValueFormatter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Lista os backups do banco e permite fazer um agora ou escolher um para restaurar. */
public class BackupsDialog extends Dialog<Path> {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final DbSession session;
    private final TableView<MaintenanceService.BackupFile> table = new TableView<>();
    private final Label info = new Label();

    public BackupsDialog(Window owner, DbSession session) {
        this.session = session;
        initOwner(owner);
        setTitle("Backups");
        setHeaderText("Backups e restauração — " + session.displayName());
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);

        table.setPrefSize(720, 300);
        table.setPlaceholder(new Label("Nenhum backup deste banco ainda."));
        table.getColumns().add(col("Data", b -> b.when().format(FMT), 160));
        table.getColumns().add(col("Tipo", MaintenanceService.BackupFile::kind, 200));
        table.getColumns().add(col("Tamanho", b -> ValueFormatter.humanSize(b.size()), 90));
        table.getColumns().add(col("Arquivo", b -> b.path().getFileName().toString(), 260));
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                setResult(table.getSelectionModel().getSelectedItem().path());
                close();
            }
        });
        info.setWrapText(true);
        info.getStyleClass().add("muted");
        Label help = new Label("Restaurar substitui o arquivo do banco pelo do backup. Antes, o banco atual é guardado "
                + "aqui como \"Cópia antes de restaurar\" (dá para desfazer). O sistema que usa o banco precisa estar fechado.");
        help.setWrapText(true);
        help.setMinHeight(Label.USE_PREF_SIZE);
        info.setMinHeight(Label.USE_PREF_SIZE);
        VBox box = new VBox(8, table, info, help);
        box.setPadding(new Insets(6, 4, 0, 4));
        box.setPrefWidth(740);
        getDialogPane().setContent(box);

        ButtonType restore = new ButtonType("Restaurar selecionado…", ButtonBar.ButtonData.OK_DONE);
        ButtonType fromFile = new ButtonType("Restaurar de outro arquivo…", ButtonBar.ButtonData.LEFT);
        ButtonType now = new ButtonType("Fazer backup agora", ButtonBar.ButtonData.LEFT);
        ButtonType folder = new ButtonType("Abrir pasta", ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(now, folder, fromFile, restore, Fx.CLOSE);
        boolean canRestore = !session.isCopy();
        // cópias temporárias (banco travado) não podem ser restauradas
        getDialogPane().lookupButton(restore).disableProperty().bind(
                table.getSelectionModel().selectedItemProperty().isNull().or(new javafx.beans.property.SimpleBooleanProperty(!canRestore)));
        getDialogPane().lookupButton(fromFile).setDisable(!canRestore);
        getDialogPane().lookupButton(now).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            backupNow();
        });
        getDialogPane().lookupButton(folder).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            try {
                Path dir = BackupService.backupDir(session.config());
                Files.createDirectories(dir);
                Fx.openInSystem(dir);
            } catch (Exception ex) {
                Fx.error(getDialogPane().getScene().getWindow(), "Não foi possível abrir a pasta", ex.getMessage(), null);
            }
        });
        getDialogPane().lookupButton(fromFile).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            File f = Fx.chooseOpen(getDialogPane().getScene().getWindow(), "Escolher backup", "backup.pasta",
                    new FileChooser.ExtensionFilter("Backup ou banco H2 (*.zip, *.mv.db, *.h2.db)", "*.zip", "*.mv.db", "*.h2.db"));
            if (f != null) {
                setResult(f.toPath());
                close();
            }
        });
        setResultConverter(bt -> bt == restore && table.getSelectionModel().getSelectedItem() != null
                ? table.getSelectionModel().getSelectedItem().path() : null);
        refresh();
    }

    private void refresh() {
        try {
            table.getItems().setAll(MaintenanceService.listBackups(session.config()));
            info.setText(table.getItems().size() + " backup(s) em " + BackupService.backupDir(session.config())
                    + " · o backup automático mantém os " + AppPreferences.backupsToKeep() + " mais recentes.");
        } catch (Exception e) {
            info.setText("Erro ao listar: " + e.getMessage());
        }
    }

    private void backupNow() {
        Window w = getDialogPane().getScene().getWindow();
        TaskDialog.run(w, "Fazendo backup", false, ctx -> session.submit(c ->
                BackupService.backup(c, session.config(), AppPreferences.backupsToKeep())).get(), p -> {
                    refresh();
                    table.getSelectionModel().selectFirst();
                }, e -> Fx.error(w, "Não foi possível fazer o backup", Fx.shortMessage(e), e));
    }

    private static <T> TableColumn<MaintenanceService.BackupFile, T> col(String t, Function<MaintenanceService.BackupFile, T> f, double w) {
        TableColumn<MaintenanceService.BackupFile, T> c = new TableColumn<>(t);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setPrefWidth(w);
        return c;
    }
}
