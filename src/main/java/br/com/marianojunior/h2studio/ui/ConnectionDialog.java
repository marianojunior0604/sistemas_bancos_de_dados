package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.ConnectionConfig;
import br.com.marianojunior.h2studio.core.ConnectionConfig.Mode;
import br.com.marianojunior.h2studio.core.DriverRegistry;
import br.com.marianojunior.h2studio.core.DriverRegistry.DriverJar;
import br.com.marianojunior.h2studio.core.H2FileInspector;
import br.com.marianojunior.h2studio.core.ValueFormatter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Diálogo para escolher o arquivo do banco e os dados de acesso. */
public class ConnectionDialog extends Dialog<ConnectionConfig> {

    private static File lastDir;

    private final TextField file = new TextField();
    private final TextField name = new TextField();
    private final TextField user = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox encrypted = new CheckBox("Banco criptografado (CIPHER=AES)");
    private final PasswordField filePassword = new PasswordField();
    private final ComboBox<Mode> mode = new ComboBox<>(FXCollections.observableArrayList(Mode.values()));
    private final Label modeHelp = new Label();
    private final ComboBox<DriverJar> driver = new ComboBox<>();
    private final TextField urlOptions = new TextField();
    private final CheckBox remember = new CheckBox("Lembrar senha neste computador");
    private final Label detected = new Label();
    private boolean nameEdited;

    public ConnectionDialog(Window owner, ConnectionConfig initial) {
        initOwner(owner);
        setTitle("Abrir banco H2");
        setHeaderText("Abrir banco de dados H2");
        setResizable(true);

        ButtonType openType = new ButtonType("Abrir", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(openType, Fx.CANCEL);
        getDialogPane().getStyleClass().add("connection-dialog");
        getDialogPane().getStylesheets().add(MainWindow.CSS);

        file.setPromptText("Caminho do arquivo .mv.db ou .h2.db");
        Button browse = new Button("Procurar…");
        browse.setOnAction(e -> browse());
        HBox fileRow = new HBox(6, file, browse);
        HBox.setHgrow(file, Priority.ALWAYS);

        detected.getStyleClass().add("detected");
        detected.setWrapText(true);

        name.setPromptText("Nome para identificar na lista");
        name.textProperty().addListener((o, a, b) -> {
            if (name.isFocused()) {
                nameEdited = true;
            }
        });
        user.setPromptText("sa");
        filePassword.setPromptText("Senha do arquivo");
        filePassword.disableProperty().bind(encrypted.selectedProperty().not());

        mode.valueProperty().addListener((o, a, b) -> modeHelp.setText(help(b)));
        modeHelp.getStyleClass().add("help");
        modeHelp.setWrapText(true);

        driver.getItems().add(null);
        driver.getItems().addAll(DriverRegistry.get().list());
        driver.setButtonCell(new DriverCell(true));
        driver.setCellFactory(v -> new DriverCell(false));
        driver.setMaxWidth(Double.MAX_VALUE);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(10, 4, 4, 4));
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(110);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(420);
        g.getColumnConstraints().addAll(c0, c1);
        int r = 0;
        g.addRow(r++, new Label("Arquivo:"), fileRow);
        g.add(detected, 1, r++);
        g.addRow(r++, new Label("Nome:"), name);
        g.addRow(r++, new Label("Usuário:"), user);
        g.addRow(r++, new Label("Senha:"), password);
        g.add(encrypted, 1, r++);
        g.addRow(r++, new Label("Senha arquivo:"), filePassword);
        g.addRow(r++, new Label("Modo:"), mode);
        g.add(modeHelp, 1, r++);
        g.addRow(r++, new Label("Driver:"), driver);
        urlOptions.setPromptText("Opcional. Ex.: MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        urlOptions.getStyleClass().add("mono");
        Label optHelp = new Label("Use as mesmas opções da URL do sistema que criou o banco. "
                + "DATABASE_TO_LOWER é detectado automaticamente.");
        optHelp.getStyleClass().add("help");
        optHelp.setWrapText(true);
        g.addRow(r++, new Label("Opções da URL:"), urlOptions);
        g.add(optHelp, 1, r++);
        g.add(remember, 1, r++);
        getDialogPane().setContent(g);

        ConnectionConfig cfg = initial != null ? initial : new ConnectionConfig();
        file.setText(cfg.getFilePath() == null ? "" : cfg.getFilePath());
        name.setText(cfg.getName() == null ? "" : cfg.getName());
        nameEdited = cfg.getId() != null;
        user.setText(cfg.getUser() == null ? "sa" : cfg.getUser());
        password.setText(cfg.getPassword());
        encrypted.setSelected(cfg.isEncrypted());
        filePassword.setText(cfg.getFilePassword());
        mode.setValue(cfg.getMode());
        remember.setSelected(cfg.isRememberPassword());
        urlOptions.setText(cfg.getUrlOptions());
        driver.setValue(DriverRegistry.get().list().stream()
                .filter(j -> j.path().toString().equals(cfg.getDriverJar())).findFirst().orElse(null));

        file.textProperty().addListener((o, a, b) -> onFileChanged());
        onFileChanged();

        Node ok = getDialogPane().lookupButton(openType);
        ok.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !isValidFile(file.getText()), file.textProperty()));

        setResultConverter(bt -> {
            if (bt != openType) {
                return null;
            }
            cfg.setFilePath(Path.of(file.getText().trim()).toAbsolutePath().normalize().toString());
            cfg.setName(name.getText().isBlank() ? ConnectionConfig.defaultName(cfg.file()) : name.getText().trim());
            cfg.setUser(user.getText().trim());
            cfg.setPassword(password.getText());
            cfg.setEncrypted(encrypted.isSelected());
            cfg.setFilePassword(encrypted.isSelected() ? filePassword.getText() : "");
            cfg.setMode(mode.getValue());
            cfg.setDriverJar(driver.getValue() == null ? null : driver.getValue().path().toString());
            cfg.setRememberPassword(remember.isSelected());
            cfg.setUrlOptions(urlOptions.getText() == null ? "" : urlOptions.getText().trim());
            return cfg;
        });

        Platform.runLater(() -> {
            if (file.getText().isBlank()) {
                file.requestFocus();
            } else {
                password.requestFocus();
            }
        });
    }

    private static boolean isValidFile(String text) {
        try {
            return !text.isBlank() && Files.isRegularFile(Path.of(text.trim()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void browse() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Selecionar banco H2");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Bancos H2 (*.mv.db, *.h2.db)", "*.mv.db", "*.h2.db"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*"));
        File current = isValidFile(file.getText()) ? Path.of(file.getText().trim()).toFile().getParentFile() : lastDir;
        if (current != null && current.isDirectory()) {
            fc.setInitialDirectory(current);
        }
        File f = fc.showOpenDialog(getDialogPane().getScene().getWindow());
        if (f != null) {
            lastDir = f.getParentFile();
            file.setText(f.getAbsolutePath());
        }
    }

    private void onFileChanged() {
        detected.getStyleClass().removeAll("ok", "warn", "error");
        if (!isValidFile(file.getText())) {
            detected.setText(file.getText().isBlank() ? "Escolha o arquivo do banco." : "Arquivo não encontrado.");
            detected.getStyleClass().add(file.getText().isBlank() ? "warn" : "error");
            return;
        }
        Path p = Path.of(file.getText().trim());
        if (!nameEdited) {
            name.setText(ConnectionConfig.defaultName(p));
        }
        try {
            H2FileInspector.Info info = H2FileInspector.inspect(p);
            String size = ValueFormatter.humanSize(Files.size(p));
            String text = "Detectado: " + info.description() + " · " + size;
            switch (info.kind()) {
                case UNKNOWN -> {
                    detected.setText("Este arquivo não parece ser um banco H2.");
                    detected.getStyleClass().add("error");
                    return;
                }
                case ENCRYPTED -> {
                    encrypted.setSelected(true);
                    detected.setText(text);
                    detected.getStyleClass().add("warn");
                    return;
                }
                default -> { }
            }
            // bancos pequenos: já mostra a opção DATABASE_TO_LOWER no diálogo
            // (nos grandes a detecção acontece ao abrir, fora da tela)
            if (info.format() >= 2 && Files.size(p) < 64L * 1024 * 1024
                    && !urlOptions.getText().toUpperCase().contains("DATABASE_TO_LOWER")
                    && Boolean.TRUE.equals(H2FileInspector.usesLowerCaseSchema(p))) {
                String cur = urlOptions.getText().trim();
                urlOptions.setText(cur.isEmpty() ? "DATABASE_TO_LOWER=TRUE" : cur + ";DATABASE_TO_LOWER=TRUE");
                text += " · nomes em minúsculas";
            }
            DriverJar best = DriverRegistry.get().best(info.family()).orElse(null);
            if (best == null) {
                detected.setText(text + "\nNenhum driver instalado para esta versão. Veja Ferramentas › Drivers H2.");
                detected.getStyleClass().add("error");
            } else {
                detected.setText(text + " → driver " + best.version());
                detected.getStyleClass().add("ok");
            }
        } catch (Exception e) {
            detected.setText("Não foi possível ler o arquivo (pode estar em uso por outro programa).");
            detected.getStyleClass().add("warn");
        }
    }

    private static String help(Mode m) {
        if (m == null) {
            return "";
        }
        return switch (m) {
            case NORMAL -> "Abre com acesso total. O sistema que usa este banco precisa estar fechado.";
            case READ_ONLY -> "Apenas consulta: nenhuma alteração é gravada no arquivo.";
            case AUTO_SERVER -> "Permite abrir junto com o sistema, desde que o sistema também use AUTO_SERVER=TRUE na URL.";
        };
    }

    private static final String AUTO = "Automático pelo formato do arquivo (recomendado)";

    private static final class DriverCell extends ListCell<DriverJar> {
        private final boolean button;

        DriverCell(boolean button) {
            this.button = button;
        }

        @Override
        protected void updateItem(DriverJar item, boolean empty) {
            super.updateItem(item, empty);
            if (item != null) {
                setText(item.toString());
            } else {
                setText(empty && !button ? null : AUTO);
            }
        }
    }
}
