package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.AppPreferences;
import br.com.marianojunior.h2studio.core.ConnectionConfig;
import br.com.marianojunior.h2studio.core.DbType;
import br.com.marianojunior.h2studio.core.ServerBackupService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Transforma um backup do Firebird (.fbk) num banco (.fdb) com o gbak e, se pedido, abre o banco.
 * Não precisa de conexão aberta: basta um servidor Firebird (normalmente o deste computador).
 * O resultado do diálogo é a conexão para abrir o banco restaurado (ou nada).
 */
public class FbkRestoreDialog extends Dialog<ConnectionConfig> {

    private static final String MANUAL = "Digitar os dados do servidor";

    private final TextField fbkField = new TextField();
    private final ComboBox<String> saved = new ComboBox<>();
    private final TextField host = new TextField("localhost");
    private final TextField port = new TextField("3050");
    private final TextField user = new TextField("SYSDBA");
    private final PasswordField password = new PasswordField();
    private final ComboBox<String> version = new ComboBox<>();
    private final TextField target = new TextField();
    private final CheckBox open = new CheckBox("Abrir o banco depois de restaurar");
    private final Label status = new Label();
    private final Map<String, ConnectionConfig> servers = new LinkedHashMap<>();
    private boolean targetEdited;

    public FbkRestoreDialog(Window owner, Path fbk, List<ConnectionConfig> recent) {
        initOwner(owner);
        setTitle("Restaurar backup do Firebird");
        setHeaderText("Transformar um backup .fbk em banco .fdb");
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        ButtonType go = new ButtonType("Restaurar", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(go, Fx.CANCEL);
        Button goBtn = (Button) getDialogPane().lookupButton(go);
        goBtn.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            restore();
        });
        setResultConverter(b -> null);

        // ---------- backup ----------
        fbkField.setPromptText("arquivo .fbk");
        fbkField.textProperty().addListener((o, a, b) -> suggestTarget());
        Button choose = new Button("Escolher…");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        choose.setOnAction(e -> chooseFbk());
        HBox fbkRow = new HBox(6, fbkField, choose);
        HBox.setHgrow(fbkField, Priority.ALWAYS);

        // ---------- servidor ----------
        servers.put(MANUAL, null);
        for (ConnectionConfig c : recent) {
            if (c.getType() != null && c.getType().isFirebird()) {
                String key = c.getUser() + " @ " + c.getHost() + ":" + (c.getPort() > 0 ? c.getPort() : 3050)
                        + (c.getType() == DbType.FIREBIRD_25 ? " (2.5)" : "");
                servers.putIfAbsent(key, c);
            }
        }
        saved.getItems().addAll(servers.keySet());
        saved.setMaxWidth(Double.MAX_VALUE);
        saved.setOnAction(e -> fillFrom(servers.get(saved.getValue())));
        version.getItems().addAll("Firebird 3, 4 ou 5", "Firebird 2.5");
        version.setValue(version.getItems().get(0));
        port.setPrefColumnCount(5);
        HBox hostRow = new HBox(6, host, new Label("porta"), port);
        HBox.setHgrow(host, Priority.ALWAYS);
        // usa o primeiro servidor salvo, de preferência o deste computador
        servers.keySet().stream().filter(k -> k.contains("@ localhost") || k.contains("@ 127.0.0.1")).findFirst()
                .or(() -> servers.keySet().stream().skip(1).findFirst())
                .ifPresentOrElse(saved::setValue, () -> saved.setValue(MANUAL));
        fillFrom(servers.get(saved.getValue()));

        // ---------- destino ----------
        target.setPromptText("caminho do .fdb no computador do servidor");
        target.setOnKeyTyped(e -> targetEdited = true);
        Button chooseTarget = new Button("Escolher…");
        chooseTarget.setMinWidth(Region.USE_PREF_SIZE);
        chooseTarget.setOnAction(e -> chooseTarget());
        HBox targetRow = new HBox(6, target, chooseTarget);
        HBox.setHgrow(target, Priority.ALWAYS);
        Label targetHelp = new Label("É um caminho no computador onde o Firebird está instalado. Com o Firebird nesta "
                + "máquina, é uma pasta deste computador, e o serviço do Firebird precisa poder gravar nela.");
        targetHelp.getStyleClass().add("help");
        targetHelp.setWrapText(true);
        targetHelp.setMinHeight(Region.USE_PREF_SIZE);
        open.setSelected(true);

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(8);
        g.getColumnConstraints().addAll(new ColumnConstraints(130), new ColumnConstraints(10, 480, Double.MAX_VALUE));
        int r = 0;
        g.add(section("Backup"), 0, r++, 2, 1);
        g.addRow(r++, new Label("Arquivo .fbk:"), fbkRow);
        g.add(section("Servidor Firebird"), 0, r++, 2, 1);
        g.addRow(r++, new Label("Usar:"), saved);
        g.addRow(r++, new Label("Servidor:"), hostRow);
        g.addRow(r++, new Label("Usuário:"), user);
        g.addRow(r++, new Label("Senha:"), password);
        g.addRow(r++, new Label("Versão:"), version);
        g.add(section("Banco novo"), 0, r++, 2, 1);
        g.addRow(r++, new Label("Arquivo .fdb:"), targetRow);
        g.add(targetHelp, 1, r++);
        g.add(open, 1, r++);
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);
        status.getStyleClass().add("text-error");
        VBox box = new VBox(10, g, status);
        box.setPadding(new Insets(4));
        box.setPrefWidth(660);
        getDialogPane().setContent(box);

        if (fbk != null) {
            fbkField.setText(fbk.toAbsolutePath().toString());
        }
    }

    private static Label section(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("section-title");
        return l;
    }

    private void fillFrom(ConnectionConfig c) {
        if (c == null) {
            return;
        }
        host.setText(c.getHost());
        port.setText(String.valueOf(c.getPort() > 0 ? c.getPort() : 3050));
        user.setText(c.getUser());
        password.setText(c.getPassword() == null ? "" : c.getPassword());
        version.setValue(c.getType() == DbType.FIREBIRD_25 ? version.getItems().get(1) : version.getItems().get(0));
        suggestTarget();
    }

    private boolean localServer() {
        String h = host.getText().trim().toLowerCase(Locale.ROOT);
        return h.isEmpty() || h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1");
    }

    /** Sugere o .fdb ao lado do .fbk (servidor local) ou só o nome (servidor remoto). */
    private void suggestTarget() {
        if (targetEdited) {
            return;
        }
        String f = fbkField.getText().trim();
        if (f.isEmpty()) {
            return;
        }
        Path p = Path.of(f);
        String base = p.getFileName().toString().replaceAll("(?i)\\.(fbk|gbk)$", "");
        Path folder = p.toAbsolutePath().getParent();
        String last = AppPreferences.get("fbk.pasta", null);
        if (last != null && Files.isDirectory(Path.of(last))) {
            folder = Path.of(last);
        }
        Path t = folder.resolve(base + ".fdb");
        for (int i = 2; Files.exists(t); i++) {
            t = folder.resolve(base + "_" + i + ".fdb");
        }
        target.setText(localServer() ? t.toString() : base + ".fdb");
    }

    private void chooseFbk() {
        File f = Fx.chooseOpen(window(), "Escolher backup do Firebird", "fbk.origem",
                new FileChooser.ExtensionFilter("Backup do Firebird (*.fbk, *.gbk)", "*.fbk", "*.FBK", "*.gbk", "*.GBK"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*"));
        if (f != null) {
            fbkField.setText(f.getAbsolutePath());
        }
    }

    private void chooseTarget() {
        String cur = target.getText().trim();
        File f = Fx.chooseSave(window(), "Onde criar o banco", cur.isEmpty() ? "banco.fdb" : Path.of(cur).getFileName().toString(),
                "fbk.pasta", new FileChooser.ExtensionFilter("Banco Firebird (*.fdb)", "*.fdb", "*.FDB"));
        if (f != null) {
            target.setText(f.getAbsolutePath());
            targetEdited = true;
        }
    }

    private Window window() {
        return getDialogPane().getScene() == null ? getOwner() : getDialogPane().getScene().getWindow();
    }

    private void restore() {
        List<String> problems = new ArrayList<>();
        Path fbk = fbkField.getText().isBlank() ? null : Path.of(fbkField.getText().trim());
        if (fbk == null || !Files.isRegularFile(fbk)) {
            problems.add("Escolha o arquivo .fbk.");
        }
        String t = target.getText().trim();
        if (t.isEmpty()) {
            problems.add("Informe onde criar o banco (.fdb).");
        } else if (localServer() && Files.exists(Path.of(t))) {
            problems.add("Já existe um arquivo em " + t + ". Escolha outro nome: a restauração nunca substitui um banco.");
        }
        int p;
        try {
            p = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            p = -1;
            problems.add("Porta inválida.");
        }
        if (host.getText().isBlank() || user.getText().isBlank()) {
            problems.add("Informe o servidor e o usuário do Firebird.");
        }
        if (!problems.isEmpty()) {
            status.setText(String.join("\n", problems));
            return;
        }
        status.setText("");
        ConnectionConfig cfg = new ConnectionConfig();
        cfg.setType(version.getValue().contains("2.5") ? DbType.FIREBIRD_25 : DbType.FIREBIRD);
        cfg.setHost(host.getText().trim());
        cfg.setPort(p);
        cfg.setUser(user.getText().trim());
        cfg.setPassword(password.getText());
        cfg.setDatabase(t);
        cfg.setName(Path.of(t).getFileName().toString().replaceAll("(?i)\\.fdb$", ""));
        ConnectionConfig base = servers.get(saved.getValue());
        if (base != null && base.getCharset() != null && !base.getCharset().isBlank()) {
            cfg.setCharset(base.getCharset());
        }
        if (localServer()) {
            Path parent = Path.of(t).toAbsolutePath().getParent();
            if (parent != null) {
                AppPreferences.set("fbk.pasta", parent.toString());
            }
        }
        Path file = fbk;
        long size = fbk.toFile().length();
        TaskDialog.run(window(), "Restaurando " + fbk.getFileName(), true, ctx -> {
            ctx.status("Iniciando o gbak…");
            long[] lines = {0};
            ServerBackupService.restoreFirebirdBackup(cfg, file, t, new ServerBackupService.Progress() {
                @Override
                public void line(String text) {
                    lines[0]++;
                    String s = text.replaceFirst("^gbak:\\s*", "");
                    if (!s.isBlank()) {
                        ctx.status(s.length() > 90 ? s.substring(0, 90) + "…" : s);
                    }
                }

                @Override
                public boolean cancelled() {
                    return ctx.cancelled();
                }
            });
            return lines[0];
        }, n -> {
            Fx.info(window(), "Backup restaurado", "O banco foi criado em:\n" + t + "\n\nBackup de origem: " + file.getFileName()
                    + String.format(" (%,.1f MB)", size / 1048576.0).replace(',', '#').replace('.', ',').replace('#', '.'));
            setResult(open.isSelected() ? cfg : null);
            close();
        }, e -> {
            if (e instanceof ServerBackupService.MissingToolException) {
                ServerBackupsDialog.showError(window(), "O gbak não foi encontrado", e);
            } else {
                status.setText(Fx.shortMessage(e).split("\n\nSaída do gbak")[0]);
                Fx.error(window(), "Não foi possível restaurar", e.getMessage(), e);
            }
        });
    }
}
