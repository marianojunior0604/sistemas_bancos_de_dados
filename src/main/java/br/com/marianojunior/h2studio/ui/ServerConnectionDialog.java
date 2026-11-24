package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.ConnectionConfig;
import br.com.marianojunior.h2studio.core.ConnectionFactory;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DbType;
import br.com.marianojunior.h2studio.core.JdbcDrivers;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Conectar a um banco que não é H2: PostgreSQL, MySQL/MariaDB, Firebird, SQL Server ou arquivo SQLite. */
public class ServerConnectionDialog extends Dialog<ConnectionConfig> {

    private final ComboBox<DbType> type = new ComboBox<>(FXCollections.observableArrayList(DbType.connectable()));
    private final TextField name = new TextField();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField database = new TextField();
    private final Button browse = new Button("Procurar…");
    private final Label databaseLabel = new Label();
    private final TextField user = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox remember = new CheckBox("Lembrar senha neste computador");
    private final ComboBox<String> charset = new ComboBox<>(FXCollections.observableArrayList(
            "WIN1252", "UTF8", "ISO8859_1", "NONE"));
    private final Label charsetLabel = new Label("Charset:");
    private final Label charsetHelp = new Label("Sistemas antigos em Delphi/Windows quase sempre usam WIN1252. "
            + "Se os acentos aparecerem errados, troque aqui.");
    private final CheckBox readOnly = new CheckBox("Somente leitura (não deixa gravar nada neste banco)");
    private final TextField properties = new TextField();
    private final Label driverInfo = new Label();
    private final Button driverPick = new Button("Usar .jar local…");
    private final Button driverReset = new Button("Usar o padrão");
    private final Label testResult = new Label();
    private final ConnectionConfig cfg;
    private String customJar;
    private boolean nameEdited;
    private DbType previousType;

    public ServerConnectionDialog(Window owner, ConnectionConfig initial) {
        initOwner(owner);
        setTitle("Conectar a banco de dados");
        setHeaderText("Conectar a servidor ou arquivo SQLite");
        setResizable(true);
        cfg = initial != null ? initial : new ConnectionConfig();
        if (cfg.isH2()) {
            cfg.setType(DbType.POSTGRESQL);
            cfg.setUser(DbType.POSTGRESQL.defaultUser());
            cfg.setProperties(DbType.POSTGRESQL.defaultProperties());
        }
        customJar = cfg.getDriverJar();

        ButtonType connectType = new ButtonType("Conectar", ButtonBar.ButtonData.OK_DONE);
        ButtonType testType = new ButtonType("Testar conexão", ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(testType, connectType, Fx.CANCEL);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        getDialogPane().getStyleClass().add("connection-dialog");

        name.setPromptText("Ex.: Cliente X — vendas");
        name.textProperty().addListener((o, a, b) -> {
            if (name.isFocused()) {
                nameEdited = true;
            }
        });
        host.setPromptText("localhost ou 192.168.0.10");
        port.setPrefColumnCount(6);
        port.textProperty().addListener((o, a, b) -> {
            if (!b.matches("\\d{0,5}")) {
                port.setText(a);
            }
        });
        HBox hostRow = new HBox(8, host, new Label("Porta:"), port);
        hostRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(host, Priority.ALWAYS);
        HBox dbRow = new HBox(6, database, browse);
        HBox.setHgrow(database, Priority.ALWAYS);
        browse.setOnAction(e -> browseFile());
        charset.setEditable(true);
        charsetHelp.getStyleClass().add("help");
        charsetHelp.setWrapText(true);
        properties.getStyleClass().add("mono");
        properties.setPromptText("chave=valor;chave=valor (opcional)");
        driverInfo.setWrapText(true);
        driverInfo.getStyleClass().add("help");
        driverPick.setOnAction(e -> {
            File f = DriverSetup.chooseJar(getDialogPane().getScene().getWindow());
            if (f != null) {
                customJar = f.getAbsolutePath();
                updateDriverInfo();
            }
        });
        driverReset.setOnAction(e -> {
            customJar = null;
            updateDriverInfo();
        });
        testResult.setWrapText(true);
        testResult.getStyleClass().add("detected");

        GridPane g = grid();
        int r = 0;
        g.addRow(r++, new Label("Tipo:"), type);
        g.addRow(r++, new Label("Nome:"), name);
        Label hostLabel = new Label("Servidor:");
        g.addRow(r++, hostLabel, hostRow);
        g.addRow(r++, databaseLabel, dbRow);
        Label userLabel = new Label("Usuário:");
        Label passLabel = new Label("Senha:");
        g.addRow(r++, userLabel, user);
        g.addRow(r++, passLabel, password);
        g.add(remember, 1, r++);
        g.addRow(r++, charsetLabel, charset);
        g.add(charsetHelp, 1, r++);
        g.add(readOnly, 1, r++);
        g.add(testResult, 1, r++);

        GridPane adv = grid();
        int a = 0;
        adv.addRow(a++, new Label("Propriedades JDBC:"), properties);
        HBox drvButtons = new HBox(6, driverPick, driverReset);
        adv.addRow(a++, new Label("Driver:"), new VBox(6, driverInfo, drvButtons));
        TitledPane advanced = new TitledPane("Avançado", adv);
        advanced.setExpanded(false);
        advanced.setAnimated(false);
        advanced.expandedProperty().addListener((o, x, y) -> Platform.runLater(() -> {
            if (getDialogPane().getScene() != null) {
                getDialogPane().getScene().getWindow().sizeToScene();
            }
        }));

        VBox content = new VBox(10, g, advanced);
        getDialogPane().setContent(content);

        // campos que só existem para servidores, para o SQLite ou para o Firebird
        for (Node n : new Node[]{hostLabel, hostRow}) {
            n.visibleProperty().bind(Bindings.createBooleanBinding(() -> type.getValue() != null && type.getValue().isServer(),
                    type.valueProperty()));
            n.managedProperty().bind(n.visibleProperty());
        }
        for (Node n : new Node[]{userLabel, user, passLabel, password, remember}) {
            n.visibleProperty().bind(Bindings.createBooleanBinding(() -> type.getValue() != DbType.SQLITE, type.valueProperty()));
            n.managedProperty().bind(n.visibleProperty());
        }
        for (Node n : new Node[]{charsetLabel, charset, charsetHelp}) {
            n.visibleProperty().bind(Bindings.createBooleanBinding(() -> type.getValue() != null && type.getValue().isFirebird(),
                    type.valueProperty()));
            n.managedProperty().bind(n.visibleProperty());
        }
        browse.visibleProperty().bind(type.valueProperty().isEqualTo(DbType.SQLITE));
        browse.managedProperty().bind(browse.visibleProperty());

        // ---------- valores iniciais ----------
        previousType = cfg.getType();
        type.setValue(cfg.getType());
        name.setText(cfg.getName() == null ? "" : cfg.getName());
        nameEdited = cfg.getId() != null && cfg.getName() != null;
        host.setText(cfg.getHost().isBlank() && cfg.getType().isServer() ? "localhost" : cfg.getHost());
        port.setText(String.valueOf(cfg.getPort() > 0 ? cfg.getPort() : cfg.getType().defaultPort()));
        database.setText(cfg.getDatabase());
        user.setText(cfg.getUser() == null ? "" : cfg.getUser());
        password.setText(cfg.getPassword());
        remember.setSelected(cfg.isRememberPassword());
        charset.setValue(cfg.getCharset().isBlank() ? "WIN1252" : cfg.getCharset());
        readOnly.setSelected(cfg.getMode() == ConnectionConfig.Mode.READ_ONLY);
        properties.setText(cfg.getProperties());
        onTypeChanged();
        type.valueProperty().addListener((o, x, y) -> onTypeChanged());
        for (TextField f : new TextField[]{host, port, database}) {
            f.textProperty().addListener((o, x, y) -> autoName());
        }

        Node connect = getDialogPane().lookupButton(connectType);
        Node test = getDialogPane().lookupButton(testType);
        var invalid = Bindings.createBooleanBinding(this::invalid, type.valueProperty(), host.textProperty(),
                database.textProperty());
        connect.disableProperty().bind(invalid);
        test.disableProperty().bind(invalid);
        // "Testar" não fecha o diálogo
        ((Button) test).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            testConnection();
        });

        setResultConverter(bt -> bt == connectType ? fill(cfg) : null);
        Platform.runLater(() -> (host.getText().isBlank() || !type.getValue().isServer() ? database
                : password.getText().isEmpty() ? password : name).requestFocus());
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(10, 4, 4, 4));
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(120);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(420);
        g.getColumnConstraints().addAll(c0, c1);
        return g;
    }

    private boolean invalid() {
        DbType t = type.getValue();
        if (t == null) {
            return true;
        }
        if (t == DbType.SQLITE) {
            return database.getText().isBlank();
        }
        if (t.isFirebird() && database.getText().isBlank()) {
            return true;
        }
        return host.getText().isBlank();
    }

    private void onTypeChanged() {
        DbType t = type.getValue();
        if (t == null) {
            return;
        }
        databaseLabel.setText(t.databaseLabel() + ":");
        database.setPromptText(switch (t) {
            case FIREBIRD, FIREBIRD_25 -> "caminho no servidor (C:\\Sistema\\Dados\\BANCO.FDB) ou alias";
            case SQLITE -> "Caminho do arquivo .db / .sqlite";
            case POSTGRESQL -> "postgres";
            case MYSQL, MARIADB -> "vazio = todos os bancos do servidor";
            default -> "";
        });
        if (previousType != t) {
            // troca de tipo: ajusta porta, usuário e propriedades que ainda estavam no padrão do anterior
            if (previousType != null && (port.getText().isBlank()
                    || port.getText().equals(String.valueOf(previousType.defaultPort())))) {
                port.setText(t.defaultPort() > 0 ? String.valueOf(t.defaultPort()) : "");
            }
            if (previousType != null && (user.getText().isBlank() || user.getText().equals(previousType.defaultUser()))) {
                user.setText(t.defaultUser());
            }
            if (previousType != null && properties.getText().equals(previousType.defaultProperties())) {
                properties.setText(t.defaultProperties());
            }
            if (t.isServer() && host.getText().isBlank()) {
                host.setText("localhost");
            }
            customJar = null;
            previousType = t;
        }
        updateDriverInfo();
        autoName();
        testResult.setText("");
        if (getDialogPane().getScene() != null) {
            getDialogPane().getScene().getWindow().sizeToScene();
        }
    }

    private void updateDriverInfo() {
        DbType t = type.getValue();
        if (t == null) {
            return;
        }
        driverReset.setDisable(customJar == null);
        if (customJar != null) {
            driverInfo.setText("Jar escolhido: " + customJar);
            return;
        }
        JdbcDrivers.Spec spec = t.driver();
        boolean installed = JdbcDrivers.isInstalled(spec);
        driverInfo.setText(spec.label() + " " + spec.version() + (installed ? " — instalado."
                : " — será baixado na primeira conexão (" + JdbcDrivers.size(spec.approxSize()) + ")."));
    }

    private void autoName() {
        if (nameEdited) {
            return;
        }
        DbType t = type.getValue();
        String db = database.getText().trim();
        if (t == DbType.SQLITE) {
            name.setText(db.isEmpty() ? "" : Path.of(db).getFileName().toString());
            return;
        }
        if (t != null && t.isFirebird() && !db.isEmpty()) {
            db = db.replace('\\', '/');
            db = db.substring(db.lastIndexOf('/') + 1);
        }
        String h = host.getText().trim();
        name.setText((db.isEmpty() ? "" : db + " @ ") + h);
    }

    private void browseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Selecionar banco SQLite");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Bancos SQLite (*.db, *.sqlite, *.sqlite3, *.db3)",
                        "*.db", "*.sqlite", "*.sqlite3", "*.db3"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*"));
        String cur = database.getText().trim();
        if (!cur.isEmpty() && Files.isRegularFile(Path.of(cur))) {
            fc.setInitialDirectory(Path.of(cur).toAbsolutePath().getParent().toFile());
        }
        File f = fc.showOpenDialog(getDialogPane().getScene().getWindow());
        if (f != null) {
            database.setText(f.getAbsolutePath());
        }
    }

    private ConnectionConfig fill(ConnectionConfig target) {
        DbType t = type.getValue();
        target.setType(t);
        target.setHost(t.isServer() ? host.getText().trim() : "");
        int p;
        try {
            p = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            p = 0;
        }
        target.setPort(t.isServer() && p != t.defaultPort() ? p : 0);
        String db = database.getText().trim();
        if (t == DbType.SQLITE && !db.isEmpty()) {
            db = Path.of(db).toAbsolutePath().normalize().toString();
        }
        target.setDatabase(db);
        target.setUser(t == DbType.SQLITE ? "" : user.getText().trim());
        target.setPassword(t == DbType.SQLITE ? "" : password.getText());
        target.setRememberPassword(remember.isSelected());
        target.setCharset(t.isFirebird() && charset.getValue() != null ? charset.getValue().trim() : "");
        target.setMode(readOnly.isSelected() ? ConnectionConfig.Mode.READ_ONLY : ConnectionConfig.Mode.NORMAL);
        target.setProperties(properties.getText() == null ? "" : properties.getText().trim());
        target.setDriverJar(customJar);
        target.setFilePath(null);
        target.setUrlOptions("");
        target.setEncrypted(false);
        String n = name.getText().trim();
        target.setName(n.isEmpty() ? t.label() + " " + target.location() : n);
        return target;
    }

    private void testConnection() {
        ConnectionConfig probe = fill(cfg.copy());
        Window w = getDialogPane().getScene().getWindow();
        DriverSetup.ensure(w, probe, () -> {
            customJar = probe.getDriverJar();
            updateDriverInfo();
            testResult.getStyleClass().removeAll("ok", "error", "warn");
            testResult.setText("Conectando…");
            CompletableFuture.supplyAsync(() -> {
                try (DbSession s = new ConnectionFactory().open(probe)) {
                    return s.engineLabel();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err == null) {
                    testResult.setText("Conectado com sucesso: " + v);
                    testResult.getStyleClass().add("ok");
                } else {
                    testResult.setText(Fx.shortMessage(err));
                    testResult.getStyleClass().add("error");
                }
                w.sizeToScene();
            }));
        });
    }
}
