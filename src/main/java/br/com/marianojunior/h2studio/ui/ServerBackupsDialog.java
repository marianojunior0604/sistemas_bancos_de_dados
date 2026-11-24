package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DbType;
import br.com.marianojunior.h2studio.core.JdbcDrivers;
import br.com.marianojunior.h2studio.core.MetadataService;
import br.com.marianojunior.h2studio.core.NativeTools;
import br.com.marianojunior.h2studio.core.ServerBackupService;
import br.com.marianojunior.h2studio.core.ServerBackupService.BackupFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Backups dos outros bancos: ferramentas encontradas, lista de backups, fazer backup e
 * escolher o que restaurar (a restauração em si fica com a janela principal, que precisa
 * fechar as abas quando o banco atual é substituído).
 */
public class ServerBackupsDialog extends Dialog<ServerBackupsDialog.RestoreChoice> {

    /** O que restaurar: arquivo local ou caminho no servidor (SQL Server). */
    public record RestoreChoice(String backup, String database) {
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final DbSession session;
    private final DbType type;
    private final TableView<BackupFile> table = new TableView<>();
    private final ComboBox<String> database = new ComboBox<>();
    private final VBox toolsBox = new VBox(4);
    private final Label info = new Label();

    public ServerBackupsDialog(Window owner, DbSession session) {
        this.session = session;
        this.type = session.type();
        initOwner(owner);
        setTitle("Backup e restauração");
        setHeaderText("Backups de " + session.displayName() + " (" + session.engineLabel() + ")");
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        getDialogPane().getButtonTypes().add(new ButtonType("Fechar", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));

        // ---------- banco (MySQL: o servidor pode ter vários) ----------
        HBox dbRow = new HBox(8, new Label(type == DbType.MYSQL || type == DbType.MARIADB ? "Banco do servidor:" : "Banco:"),
                database);
        dbRow.setAlignment(Pos.CENTER_LEFT);
        database.setPrefWidth(320);
        String db = session.config().getDatabase();
        database.setValue(db);
        boolean multi = type == DbType.MYSQL || type == DbType.MARIADB;
        database.setDisable(!multi);
        if (multi) {
            Fx.then(session.submit(c -> MetadataService.schemas(c, false)), list -> {
                database.setItems(FXCollections.observableArrayList(list));
                if (db.isBlank() && !list.isEmpty()) {
                    database.setValue(list.get(0));
                }
            }, e -> { });
        }
        database.valueProperty().addListener((o, a, b) -> reload());

        // ---------- ferramentas ----------
        refreshTools();
        info.setWrapText(true);
        info.getStyleClass().add("help");
        info.setText(switch (type) {
            case SQLSERVER -> "O SQL Server grava o .bak na pasta de backup do próprio servidor (não neste computador). "
                    + "A lista vem do histórico de backups do servidor.";
            case SQLITE -> "A cópia é feita com VACUUM INTO: consistente mesmo com o sistema usando o banco.";
            case FIREBIRD, FIREBIRD_25 -> "O gbak lê o banco pela rede e grava o .fbk neste computador. Use um gbak da "
                    + "mesma versão do servidor (ou mais novo).";
            default -> "Os backups ficam em " + ServerBackupService.backupDir(session.config()) + ".";
        });

        // ---------- lista ----------
        TableColumn<BackupFile, String> when = new TableColumn<>("Data");
        when.setCellValueFactory(c -> new SimpleStringProperty(FMT.format(c.getValue().time().atZone(ZoneId.systemDefault()))));
        when.setPrefWidth(150);
        TableColumn<BackupFile, String> name = new TableColumn<>("Arquivo");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()
                + (c.getValue().safetyCopy() ? "   (cópia antes de restaurar)" : "")));
        name.setPrefWidth(400);
        TableColumn<BackupFile, String> size = new TableColumn<>("Tamanho");
        size.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().size() > 0 ? JdbcDrivers.size(c.getValue().size()) : ""));
        size.setPrefWidth(90);
        table.getColumns().addAll(List.of(when, name, size));
        table.setPlaceholder(new Label("Nenhum backup ainda."));
        table.setPrefHeight(260);
        table.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<BackupFile>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    restoreSelected();
                }
            });
            return row;
        });

        // ---------- botões ----------
        Button backupNow = new Button("Fazer backup agora");
        backupNow.getStyleClass().add("primary");
        backupNow.setOnAction(e -> doBackup(null));
        Button saveAs = new Button("Salvar backup em…");
        saveAs.setOnAction(e -> {
            File f = chooseFile(true);
            if (f != null) {
                doBackup(f.toPath());
            }
        });
        Button restore = new Button("Restaurar…");
        restore.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        restore.setOnAction(e -> restoreSelected());
        Button restoreFile = new Button("Restaurar de outro arquivo…");
        restoreFile.setOnAction(e -> {
            File f = chooseFile(false);
            if (f != null) {
                finish(new RestoreChoice(f.getAbsolutePath(), database.getValue()));
            }
        });
        Button folder = new Button("Abrir pasta");
        folder.setOnAction(e -> {
            try {
                Path dir = ServerBackupService.backupDir(session.config());
                Files.createDirectories(dir);
                Fx.openInSystem(dir);
            } catch (Exception ex) {
                Fx.error(getDialogPane().getScene().getWindow(), "Não foi possível abrir a pasta", Fx.shortMessage(ex), ex);
            }
        });
        Button delete = new Button("Excluir");
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> deleteSelected());
        boolean onServer = type == DbType.SQLSERVER;
        for (Button b : new Button[]{saveAs, restoreFile, folder, delete}) {
            b.setVisible(!onServer);
            b.setManaged(!onServer);
        }
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox actions = new HBox(8, backupNow, saveAs, sp, restore, restoreFile);
        HBox actions2 = new HBox(8, folder, delete);

        VBox content = new VBox(10, dbRow, toolsBox, info, table, actions, actions2);
        content.setPadding(new Insets(6, 2, 2, 2));
        content.setPrefWidth(760);
        getDialogPane().setContent(content);
        setResultConverter(b -> null);
        reload();
    }

    private void refreshTools() {
        toolsBox.getChildren().clear();
        List<NativeTools.Tool> tools = ServerBackupService.toolsFor(type);
        if (tools.isEmpty()) {
            return;
        }
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(4);
        int r = 0;
        for (NativeTools.Tool t : tools) {
            Path p = NativeTools.find(t);
            String ver = p == null ? "" : NativeTools.version(t, p);
            Label l = new Label(p == null ? "não encontrado" : p + (ver.isBlank() ? "" : "   (" + ver + ")"));
            l.getStyleClass().add(p == null ? "error" : "muted");
            Button choose = new Button(p == null ? "Indicar onde está…" : "Trocar…");
            choose.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("Onde está o " + t.exe() + "?");
                File f = fc.showOpenDialog(getDialogPane().getScene().getWindow());
                if (f != null) {
                    NativeTools.configure(t, f.toPath());
                    refreshTools();
                }
            });
            g.addRow(r++, new Label(t.exe() + ":"), l, choose);
            String warn = ServerBackupService.versionWarning(session, t, p);
            if (warn != null) {
                Label w = new Label(warn);
                w.getStyleClass().add("error");
                w.setWrapText(true);
                g.add(w, 1, r++, 2, 1);
            }
        }
        Label title = new Label("Ferramentas do banco");
        title.getStyleClass().add("section-title");
        toolsBox.getChildren().addAll(title, g);
    }

    private void reload() {
        String db = database.getValue();
        Fx.then(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return ServerBackupService.list(session, db);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }), list -> table.setItems(FXCollections.observableArrayList(list)),
                e -> table.setPlaceholder(new Label("Não foi possível listar: " + Fx.shortMessage(e))));
    }

    private void doBackup(Path target) {
        String db = database.getValue();
        Window w = getDialogPane().getScene().getWindow();
        runBackup(w, session, db, target, path -> {
            reload();
            Fx.info(w, "Backup criado", path);
        });
    }

    /** Faz o backup com janela de andamento (usado aqui e no "Fazer backup agora" do menu). */
    static void runBackup(Window w, DbSession session, String db, Path target, java.util.function.Consumer<String> ok) {
        TaskDialog.run(w, "Backup de " + session.displayName(), true, ctx -> {
            ctx.progress(-1);
            return ServerBackupService.backup(session, db, target, null,
                    ServerBackupService.silent(ctx::status, ctx::cancelled));
        }, ok, err -> showError(w, "Não foi possível fazer o backup", err));
    }

    static void showError(Window w, String title, Throwable err) {
        Throwable e = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
        if (e instanceof ServerBackupService.MissingToolException) {
            Fx.error(w, title, e.getMessage(), null);
        } else {
            Fx.error(w, title, e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    private void restoreSelected() {
        BackupFile b = table.getSelectionModel().getSelectedItem();
        if (b != null) {
            finish(new RestoreChoice(b.localFile() != null ? b.localFile().toString() : b.location(), database.getValue()));
        }
    }

    private void finish(RestoreChoice choice) {
        setResultConverter(bt -> choice);
        setResult(choice);
        close();
    }

    private void deleteSelected() {
        BackupFile b = table.getSelectionModel().getSelectedItem();
        if (b == null || b.localFile() == null) {
            return;
        }
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "O arquivo será apagado do disco. Não dá para desfazer.", Fx.YES, Fx.NO);
        a.initOwner(getDialogPane().getScene().getWindow());
        a.setTitle("H2 Studio");
        a.setHeaderText("Excluir " + b.name() + "?");
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        if (a.showAndWait().filter(x -> x == Fx.YES).isPresent()) {
            try {
                Files.deleteIfExists(b.localFile());
            } catch (Exception ex) {
                Fx.error(getDialogPane().getScene().getWindow(), "Não foi possível excluir", Fx.shortMessage(ex), ex);
            }
            reload();
        }
    }

    private File chooseFile(boolean save) {
        if (Fx.testFileChooser != null) {
            return Fx.testFileChooser.apply(save ? "salvar" : "abrir", null);
        }
        FileChooser fc = new FileChooser();
        String ext = ServerBackupService.extension(type);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Backup " + type.shortLabel() + " (*" + ext + ")",
                "*" + ext));
        if (type == DbType.POSTGRESQL || type == DbType.MYSQL || type == DbType.MARIADB) {
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Script SQL (*.sql)", "*.sql"));
        }
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Todos os arquivos", "*.*"));
        Path dir = ServerBackupService.backupDir(session.config());
        if (Files.isDirectory(dir)) {
            fc.setInitialDirectory(dir.toFile());
        }
        if (save) {
            fc.setTitle("Salvar backup como");
            fc.setInitialFileName(session.config().getName().replaceAll("[^A-Za-z0-9._-]", "_") + ext);
            return fc.showSaveDialog(getDialogPane().getScene().getWindow());
        }
        fc.setTitle("Escolher o arquivo de backup");
        return fc.showOpenDialog(getDialogPane().getScene().getWindow());
    }
}
