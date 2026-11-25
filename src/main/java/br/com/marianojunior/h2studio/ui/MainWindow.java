package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.ConnectionConfig;
import br.com.marianojunior.h2studio.core.ConnectionFactory;
import br.com.marianojunior.h2studio.core.ConnectionStore;
import br.com.marianojunior.h2studio.core.DbOpenException;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DriverRegistry;
import br.com.marianojunior.h2studio.core.H2FileInspector;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Janela principal do H2 Studio. */
public class MainWindow {

    public static final String CSS = MainWindow.class.getResource("app.css").toExternalForm();
    public static final String VERSION = "1.10.0";

    private final Stage stage;
    private final HostServices hostServices;
    private final ConnectionStore store = new ConnectionStore();
    private final ConnectionFactory factory = new ConnectionFactory();
    private final List<DbSession> sessions = new ArrayList<>();

    private final DatabaseTree tree = new DatabaseTree();
    private final TabPane tabs = new TabPane();
    private final WelcomePane welcome;
    private final StackPane center = new StackPane();
    private final Label status = new Label("Pronto.");
    private final Label sessionInfo = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Menu recentMenu = new Menu("Abrir recente");

    public MainWindow(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.hostServices = hostServices;
        Fx.hostServices = hostServices;
        welcome = new WelcomePane(() -> openDialog(null), () -> connectDialog(null), this::openRecent, c -> {
            store.remove(c);
            refreshRecent();
        });

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) c -> {
            while (c.next()) {
                for (Tab removed : c.getRemoved()) {
                    if (removed instanceof SessionTab st) {
                        st.dispose(); // fecha a conexão própria do editor SQL, salva rascunho...
                    }
                }
            }
            updateCenter();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b instanceof SessionTab t) {
                showSessionInfo(t.session());
            }
        });
        center.getChildren().addAll(welcome, tabs);
        updateCenter();

        tree.setOnOpenTable(t -> openTable(t.session(), t.table()));
        tree.setOnNewQuery(s -> openSqlEditor(s, null, false));
        tree.setOnImportFile(this::importFile);
        tree.setOnNewTable((s, schema) -> {
            String sch = schema;
            if (sch == null) {
                sch = currentSchemaOf(s);
            }
            openDesigner(s, sch == null ? "" : sch, null);
        });
        tree.setOnAlterTable(t -> openDesigner(t.session(), t.table().schema(), t.table().name()));
        tree.setOnDropTable(t -> dropTable(t.session(), t.table()));
        tree.setOnFlyway(s -> new FlywayDialog(stage, s).showAndWait());
        tree.setOnTableAction((action, t) -> {
            switch (action) {
                case "exportar" -> ExportAction.show(stage, t.session(),
                        t.table().schema().isEmpty() ? t.table().name() : t.table().schema() + "." + t.table().name(),
                        t.table().name(), null, ExportAction.allOf(t.session().type(), t.table().schema(), t.table().name()));
                case "importar" -> importCsv(t.session(), t.table());
                case "converter-para" -> showConvert(t.session(), t.table().schema(), t.table().name());
                default -> { }
            }
        });
        tree.setOnDbAction((action, s) -> {
            switch (action) {
                case "backups" -> showBackups(s);
                case "converter-para" -> showConvert(s, null, null);
                case "compactar" -> compactDatabase(s);
                case "converter" -> convertDatabase(s);
                case "script" -> dumpScript(s);
                default -> { }
            }
        });
        tree.setOnQueryTable(t -> openSqlEditor(t.session(), selectFor(t.session(), t.table()), true));
        tree.setOnCloseSession(this::closeSession);
        tree.setOnRefreshSession(this::refreshSession);
        tree.view().getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            DbSession s = tree.selectedSession();
            if (s != null) {
                showSessionInfo(s);
            }
        });
        tree.setMinWidth(200);

        SplitPane split = new SplitPane(tree, center);
        split.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(tree, false);

        BorderPane root = new BorderPane(split);
        root.setTop(new VBox(buildMenu(), buildToolBar()));
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1280, 780);
        scene.getStylesheets().add(CSS);
        setupDragAndDrop(scene);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN), () -> {
            if (tabs.getSelectionModel().getSelectedItem() instanceof TableTab t) {
                t.focusSearch();
            } else {
                tree.focusFilter();
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN), tree::focusFilter);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), this::newQuery);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), () -> {
            Tab sel = tabs.getSelectionModel().getSelectedItem();
            if (sel instanceof TableTab t) {
                t.save();
            } else if (sel instanceof SqlEditorTab q) {
                q.saveFile();
            }
        });

        stage.setTitle("H2 Studio");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscard(sessionTabs(null))) {
                e.consume();
                return;
            }
            closeAll();
        });
        refreshRecent();
        ConnectionFactory.cleanOldCopies();
    }

    public void show() {
        stage.show();
        center.requestFocus(); // não começa com o cursor no filtro da árvore
    }

    // =====================================================================
    // Menu, barra de ferramentas e status
    // =====================================================================

    private MenuBar buildMenu() {
        MenuItem open = new MenuItem("Abrir arquivo H2…");
        open.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        open.setOnAction(e -> openDialog(null));
        MenuItem connect = new MenuItem("Conectar a servidor ou SQLite…");
        connect.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        connect.setOnAction(e -> connectDialog(null));
        MenuItem fbk = new MenuItem("Restaurar backup do Firebird (.fbk)…");
        fbk.setOnAction(e -> restoreFbk(null));
        MenuItem newQuery = new MenuItem("Nova consulta SQL");
        newQuery.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN));
        newQuery.setOnAction(e -> newQuery());
        MenuItem closeConn = new MenuItem("Desconectar banco selecionado");
        closeConn.setOnAction(e -> {
            DbSession s = currentSession();
            if (s != null) {
                closeSession(s);
            }
        });
        MenuItem closeAll = new MenuItem("Desconectar todos");
        closeAll.setOnAction(e -> {
            if (confirmDiscard(sessionTabs(null))) {
                closeAll();
            }
        });
        MenuItem exit = new MenuItem("Sair");
        exit.setOnAction(e -> {
            if (confirmDiscard(sessionTabs(null))) {
                closeAll();
                stage.close();
            }
        });
        Menu file = new Menu("Arquivo", null, open, connect, fbk, recentMenu, newQuery, new SeparatorMenuItem(), closeConn, closeAll,
                new SeparatorMenuItem(), exit);

        MenuItem refresh = new MenuItem("Atualizar");
        refresh.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        refresh.setOnAction(e -> refreshCurrent());
        MenuItem closeTab = new MenuItem("Fechar aba");
        closeTab.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeTab.setOnAction(e -> {
            Tab t = tabs.getSelectionModel().getSelectedItem();
            if (t instanceof SessionTab tt && tt.hasPendingChanges() && !confirmDiscard(List.of(tt))) {
                return;
            }
            if (t != null) {
                tabs.getTabs().remove(t);
            }
        });
        CheckMenuItem system = new CheckMenuItem("Mostrar schemas do sistema (INFORMATION_SCHEMA, pg_catalog...)");
        system.setOnAction(e -> tree.setShowSystemSchemas(system.isSelected()));
        Menu view = new Menu("Exibir", null, refresh, closeTab, new SeparatorMenuItem(), system);

        MenuItem drivers = new MenuItem("Drivers instalados…");
        drivers.setOnAction(e -> showDrivers());
        CheckMenuItem autoBackup = new CheckMenuItem("Backup automático antes de salvar");
        autoBackup.setSelected(br.com.marianojunior.h2studio.core.AppPreferences.autoBackup());
        autoBackup.setOnAction(e -> br.com.marianojunior.h2studio.core.AppPreferences.setAutoBackup(autoBackup.isSelected()));
        MenuItem backupNow = new MenuItem("Fazer backup agora");
        backupNow.setOnAction(e -> backupNow());
        MenuItem backupFolder = new MenuItem("Backups e restauração…");
        backupFolder.setOnAction(e -> {
            DbSession s = sessionForAnyDb();
            if (s != null) {
                showBackups(s);
            }
        });
        MenuItem scriptItem = new MenuItem("Gerar script SQL do banco…");
        scriptItem.setOnAction(e -> {
            DbSession s = sessionForTools();
            if (s != null) {
                dumpScript(s);
            }
        });
        MenuItem compactItem = new MenuItem("Compactar banco…");
        compactItem.setOnAction(e -> {
            DbSession s = sessionForTools();
            if (s != null) {
                compactDatabase(s);
            }
        });
        MenuItem convertItem = new MenuItem("Converter versão do H2…");
        convertItem.setOnAction(e -> {
            DbSession s = sessionForTools();
            if (s != null) {
                convertDatabase(s);
            }
        });
        MenuItem flywayItem = new MenuItem("Histórico do Flyway…");
        flywayItem.setOnAction(e -> {
            DbSession s = sessionForTools();
            if (s != null) {
                new FlywayDialog(stage, s).showAndWait();
            }
        });
        MenuItem newTable = new MenuItem("Nova tabela…");
        newTable.setOnAction(e -> {
            DbSession s = sessionForAnyDb();
            if (s != null && s.isH2()) {
                openDesigner(s, br.com.marianojunior.h2studio.core.SqlCompletion.casingOf(s.config())
                        == br.com.marianojunior.h2studio.core.SqlCompletion.Casing.LOWER ? "public" : "PUBLIC", null);
            } else if (s != null) {
                String sch = currentSchemaOf(s);
                openDesigner(s, sch == null ? "" : sch, null);
            }
        });
        MenuItem importItem = new MenuItem("Importar planilha ou CSV…");
        importItem.setOnAction(e -> {
            DbSession s = sessionForAnyDb();
            if (s != null) {
                importFile(s, null);
            }
        });
        MenuItem convertTo = new MenuItem("Converter para outro banco…");
        convertTo.setOnAction(e -> {
            DbSession s = sessionForAnyDb();
            if (s != null) {
                showConvert(s, null, null);
            }
        });
        MenuItem sqlHistory = new MenuItem("Histórico de SQL…");
        sqlHistory.setOnAction(e -> showSqlHistory());
        Menu tools = new Menu("Ferramentas", null, newTable, flywayItem, sqlHistory, new SeparatorMenuItem(), importItem, convertTo,
                new SeparatorMenuItem(), backupNow, backupFolder, autoBackup,
                new SeparatorMenuItem(), scriptItem, compactItem, convertItem,
                new SeparatorMenuItem(), drivers);

        MenuItem about = new MenuItem("Sobre o H2 Studio");
        about.setOnAction(e -> Fx.info(stage, "H2 Studio " + VERSION,
                "Gerenciador visual de bancos de dados.\n\n"
                + "H2: abre arquivos das versões 1.4.x, 2.0/2.1 e 2.2+ usando o driver de cada versão, "
                + "sem alterar o formato do arquivo.\n"
                + "Outros bancos (Arquivo › Conectar, Ctrl+Shift+O): PostgreSQL, MySQL, MariaDB, Firebird 2.5 a 5, "
                + "SQL Server e SQLite — estrutura (tabelas, colunas, índices, chaves e DDL), grade com "
                + "paginação, busca, filtro, ordenação e edição, editor SQL, exportação Excel/CSV e backup/restauração com as ferramentas de cada banco. O driver de cada um é baixado "
                + "na primeira conexão.\n\n"
                + "Atalhos gerais: Ctrl+O abrir · F5 atualizar · Ctrl+F buscar · Ctrl+T filtrar tabelas · "
                + "Ctrl+W fechar aba · Ctrl+C copiar células\n\n"
                + "Edição: digite ou F2/Enter para editar · Tab próxima coluna · Esc cancela · "
                + "Insert nova linha · Ctrl+D duplicar · Delete excluir · Shift+Delete NULL · "
                + "Ctrl+Z restaurar valor · F4 escolher chave estrangeira · Ctrl+S salvar\n\n"
                + "Editor SQL: Ctrl+N nova consulta · Ctrl+Enter executar instrução · Ctrl+Shift+Enter executar tudo · "
                + "Ctrl+Espaço sugestões · Ctrl+Shift+F formatar · Ctrl+/ comentar · Ctrl+E plano (EXPLAIN) · "
                + "Ctrl+S salvar arquivo\n\n"
                + "Utilitários (Ferramentas ou botão direito no banco/tabela): exportar Excel/CSV, importar CSV, "
                + "backups e restauração, compactar, converter versão do H2, script SQL do banco."));
        Menu help = new Menu("Ajuda", null, about);

        return new MenuBar(file, view, tools, help);
    }

    private ToolBar buildToolBar() {
        Button open = new Button("Abrir H2");
        open.setGraphic(Icons.open());
        open.setTooltip(new Tooltip("Abrir um arquivo .mv.db / .h2.db (Ctrl+O)"));
        open.setOnAction(e -> openDialog(null));
        Button connect = new Button("Conectar…");
        connect.setGraphic(Icons.database());
        connect.setTooltip(new Tooltip("PostgreSQL, MySQL, MariaDB, Firebird, SQL Server ou SQLite (Ctrl+Shift+O)"));
        connect.setOnAction(e -> connectDialog(null));
        Button refresh = new Button("Atualizar");
        refresh.setGraphic(Icons.refresh());
        refresh.setTooltip(new Tooltip("Atualiza a árvore e a aba atual (F5)"));
        refresh.setOnAction(e -> refreshCurrent());
        Button close = new Button("Desconectar");
        close.setGraphic(Icons.close());
        close.setOnAction(e -> {
            DbSession s = currentSession();
            if (s != null) {
                closeSession(s);
            }
        });
        Button query = new Button("Nova consulta SQL");
        query.setGraphic(Icons.sql());
        query.setTooltip(new Tooltip("Abre um editor SQL para o banco selecionado (Ctrl+N)"));
        query.setOnAction(e -> newQuery());
        ToolBar tb = new ToolBar(open, connect, query, new Separator(Orientation.VERTICAL), refresh, close);
        tb.getStyleClass().add("main-toolbar");
        return tb;
    }

    private HBox buildStatusBar() {
        busy.setMaxSize(14, 14);
        busy.setVisible(false);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        sessionInfo.getStyleClass().add("muted");
        HBox bar = new HBox(8, busy, status, sp, sessionInfo);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void setStatus(String text, boolean working) {
        status.setText(text);
        busy.setVisible(working);
    }

    private void showSessionInfo(DbSession s) {
        if (s == null || s.isClosed()) {
            sessionInfo.setText("");
            return;
        }
        if (!s.isH2()) {
            sessionInfo.setText(s.engineLabel() + (s.isReadOnly() ? " · somente leitura" : "")
                    + " · " + s.config().location());
            return;
        }
        H2FileInspector.Info fi = s.fileInfo();
        String fmt = switch (fi.kind()) {
            case MVSTORE -> "formato " + fi.format();
            case PAGESTORE -> "PageStore";
            case ENCRYPTED -> "criptografado";
            default -> "";
        };
        String opts = s.config().getUrlOptions();
        sessionInfo.setText("H2 " + s.driver().version() + " · " + fmt
                + (opts.isBlank() ? "" : " · " + opts)
                + (s.isReadOnly() ? " · somente leitura" : "")
                + (s.isCopy() ? " · CÓPIA" : "")
                + " · " + (s.isCopy() ? s.config().getCopyOf() : s.config().getFilePath()));
    }

    private void updateCenter() {
        boolean empty = tabs.getTabs().isEmpty();
        welcome.setVisible(empty);
        tabs.setVisible(!empty);
        if (empty) {
            refreshRecent();
        }
    }

    private void refreshRecent() {
        List<ConnectionConfig> list = store.recent();
        welcome.setRecent(list);
        recentMenu.getItems().clear();
        for (ConnectionConfig c : list.subList(0, Math.min(10, list.size()))) {
            MenuItem mi = new MenuItem(c.getName() + "  —  " + (c.isH2() ? "" : c.getType().shortLabel() + " · ") + c.location());
            mi.setMnemonicParsing(false);
            mi.setOnAction(e -> openRecent(c));
            recentMenu.getItems().add(mi);
        }
        recentMenu.setDisable(list.isEmpty());
    }

    // =====================================================================
    // Abrir / fechar
    // =====================================================================

    private void setupDragAndDrop(Scene scene) {
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && e.getDragboard().getFiles().stream()
                    .anyMatch(f -> H2FileInspector.isH2File(f.toPath()) || isSqliteFile(f.toPath())
                            || br.com.marianojunior.h2studio.core.ServerBackupService.isFirebirdBackup(f.toPath()))) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            boolean ok = false;
            if (e.getDragboard().hasFiles()) {
                for (File f : e.getDragboard().getFiles()) {
                    if (H2FileInspector.isH2File(f.toPath()) || isSqliteFile(f.toPath())
                            || br.com.marianojunior.h2studio.core.ServerBackupService.isFirebirdBackup(f.toPath())) {
                        openFile(f.toPath());
                        ok = true;
                    }
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /** Abrir a partir de um arquivo (arrastar e soltar ou linha de comando). */
    public void openFile(Path file) {
        if (br.com.marianojunior.h2studio.core.ServerBackupService.isFirebirdBackup(file)) {
            restoreFbk(file); // .fbk não é banco: vira um .fdb primeiro
            return;
        }
        if (isSqliteFile(file)) {
            ConnectionConfig cfg = new ConnectionConfig();
            cfg.setType(br.com.marianojunior.h2studio.core.DbType.SQLITE);
            cfg.setDatabase(file.toAbsolutePath().normalize().toString());
            cfg.setUser("");
            ConnectionConfig saved = store.findSame(cfg);
            if (saved == null) {
                cfg.setName(file.getFileName().toString());
            }
            open(saved != null ? saved : cfg);
            return;
        }
        ConnectionConfig saved = store.findByFile(file);
        if (saved != null && saved.isRememberPassword()) {
            open(saved);
        } else {
            openDialog(saved != null ? saved : ConnectionConfig.forFile(file));
        }
    }

    /** Arquivo SQLite (cabeçalho "SQLite format 3"). */
    static boolean isSqliteFile(Path p) {
        if (p == null || !Files.isRegularFile(p)) {
            return false;
        }
        try (var in = Files.newInputStream(p)) {
            byte[] h = in.readNBytes(16);
            return new String(h, java.nio.charset.StandardCharsets.ISO_8859_1).equals("SQLite format 3\0");
        } catch (Exception e) {
            return false;
        }
    }

    private void openRecent(ConnectionConfig c) {
        if (!c.isH2()) {
            boolean ready = c.getType() == br.com.marianojunior.h2studio.core.DbType.SQLITE
                    ? c.fileExists() : c.isRememberPassword();
            if (ready) {
                open(c);
            } else {
                connectDialog(c);
            }
            return;
        }
        if (c.isRememberPassword() && Files.isRegularFile(c.file())) {
            open(c);
        } else {
            openDialog(c);
        }
    }

    private void openDialog(ConnectionConfig initial) {
        if (initial != null && !initial.isH2()) {
            connectDialog(initial);
            return;
        }
        Optional<ConnectionConfig> r = new ConnectionDialog(stage, initial).showAndWait();
        r.ifPresent(this::open);
    }

    /** Conectar a PostgreSQL, MySQL/MariaDB, Firebird, SQL Server ou SQLite. */
    private void connectDialog(ConnectionConfig initial) {
        Optional<ConnectionConfig> r = new ServerConnectionDialog(stage, initial).showAndWait();
        r.ifPresent(this::open);
    }

    private void open(ConnectionConfig cfg) {
        String key = cfg.targetKey();
        DbSession already = sessions.stream()
                .filter(s -> !s.isCopy() && s.config().targetKey().equals(key))
                .findFirst().orElse(null);
        if (already != null) {
            tree.select(already);
            setStatus("Este banco já está aberto.", false);
            return;
        }
        if (cfg.isH2()) {
            runOpen(cfg, () -> factory.open(cfg));
        } else {
            DriverSetup.ensure(stage, cfg, () -> runOpen(cfg, () -> factory.open(cfg)));
        }
    }

    private void runOpen(ConnectionConfig cfg, OpenAction action) {
        setStatus("Abrindo " + cfg.getName() + "…", true);
        String optionsBefore = cfg.getUrlOptions();
        CompletableFuture.supplyAsync(() -> {
            try {
                return action.open();
            } catch (DbOpenException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((s, err) -> javafx.application.Platform.runLater(() -> {
            if (err == null) {
                onOpened(s, optionsBefore);
            } else {
                onOpenFailed(cfg, Fx.unwrap(err));
            }
        }));
    }

    @FunctionalInterface
    private interface OpenAction {
        DbSession open() throws DbOpenException;
    }

    private void onOpened(DbSession s, String optionsBefore) {
        sessions.add(s);
        ConnectionConfig cfg = s.config();
        cfg.setLastOpened(System.currentTimeMillis());
        store.save(cfg);
        refreshRecent();
        tree.addSession(s);
        String detected = cfg.getUrlOptions().equals(optionsBefore) ? ""
                : " · opções da URL detectadas e salvas: " + cfg.getUrlOptions();
        setStatus("Conectado a " + s.displayName() + " (" + s.engineLabel() + ")" + detected, false);
        showSessionInfo(s);
    }

    private void onOpenFailed(ConnectionConfig cfg, Throwable err) {
        setStatus("Não foi possível abrir " + cfg.getName() + ".", false);
        if (!(err instanceof DbOpenException e)) {
            Fx.error(stage, "Erro ao abrir o banco", Fx.shortMessage(err), err);
            return;
        }
        if (!cfg.isH2()) {
            String title = switch (e.reason()) {
                case AUTH -> e.getMessage();
                case UNREACHABLE -> "Servidor não encontrado";
                case NOT_FOUND -> "Banco não encontrado";
                case NO_DRIVER -> "Problema com o driver";
                default -> "Erro ao conectar";
            };
            Fx.error(stage, title, e.reason() == DbOpenException.Reason.AUTH
                    ? "Confira o usuário e a senha e tente novamente." : e.getMessage(), e.getCause());
            connectDialog(cfg);
            return;
        }
        switch (e.reason()) {
            case LOCKED -> askLocked(cfg, e);
            case URL_OPTIONS -> {
                if (!cfg.getUrlOptions().isBlank()) {
                    store.save(cfg); // guarda a opção descoberta para a próxima vez
                    refreshRecent();
                }
                Fx.error(stage, "Opções da URL necessárias", e.getMessage(), e.getCause());
                openDialog(cfg);
            }
            case AUTH, FILE_PASSWORD -> {
                Fx.error(stage, e.getMessage(), "Confira os dados de acesso e tente novamente.", null);
                openDialog(cfg);
            }
            case NO_DRIVER -> {
                Fx.error(stage, "Driver não encontrado", e.getMessage(), null);
                showDrivers();
            }
            default -> Fx.error(stage, "Erro ao abrir o banco", e.getMessage(), e.getCause());
        }
    }

    private void askLocked(ConnectionConfig cfg, DbOpenException e) {
        ButtonType copy = new ButtonType("Abrir uma cópia (só leitura)", ButtonBar.ButtonData.OK_DONE);
        ButtonType retry = new ButtonType("Tentar novamente", ButtonBar.ButtonData.OTHER);
        Alert a = new Alert(Alert.AlertType.WARNING, "", copy, retry, Fx.CANCEL);
        a.initOwner(stage);
        a.setTitle("H2 Studio");
        a.setHeaderText("O banco está em uso");
        a.getDialogPane().setContent(Fx.wrapLabel(e.getMessage() + "\n\n"
                + "• Feche o sistema que usa este banco e clique em \"Tentar novamente\"; ou\n"
                + "• Abra uma cópia para consultar os dados agora (alterações não vão para o original; "
                + "dados ainda não gravados pelo sistema podem não aparecer).\n\n"
                + "Dica: se o sistema usar AUTO_SERVER=TRUE na URL, escolha o modo \"Servidor automático\" "
                + "para abrir os dois ao mesmo tempo."));
        a.getDialogPane().getStylesheets().add(CSS);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty()) {
            return;
        }
        if (r.get() == copy) {
            runOpen(cfg, () -> factory.openCopy(cfg));
        } else if (r.get() == retry) {
            open(cfg);
        }
    }

    private static boolean samePath(String a, String b) {
        return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
    }

    private void openTable(DbSession s, br.com.marianojunior.h2studio.core.Meta.TableInfo t) {
        for (Tab tab : tabs.getTabs()) {
            if (tab instanceof TableTab tt && tt.session() == s && tt.table().equals(t)) {
                tabs.getSelectionModel().select(tab);
                return;
            }
        }
        TableTab tab = new TableTab(s, t);
        tab.setOnAlterStructure(() -> openDesigner(s, t.schema(), t.name()));
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    private DbSession currentSession() {
        DbSession s = tree.selectedSession();
        if (s == null && tabs.getSelectionModel().getSelectedItem() instanceof SessionTab t) {
            s = t.session();
        }
        return s;
    }

    private void refreshSession(DbSession s) {
        tree.load(s);
        for (Tab tab : tabs.getTabs()) {
            if (tab instanceof SessionTab t && t.session() == s) {
                t.refresh();
            }
        }
    }

    private void refreshCurrent() {
        if (tabs.getSelectionModel().getSelectedItem() instanceof SessionTab t) {
            t.refresh();
            tree.load(t.session());
        } else {
            DbSession s = tree.selectedSession();
            if (s != null) {
                tree.load(s);
            }
        }
    }

    private void closeSession(DbSession s) {
        if (!confirmDiscard(sessionTabs(s))) {
            return;
        }
        tabs.getTabs().removeIf(t -> t instanceof SessionTab tt && tt.session() == s);
        tree.removeSession(s);
        sessions.remove(s);
        CompletableFuture.runAsync(s::close);
        setStatus("Desconectado de " + s.displayName() + ".", false);
        sessionInfo.setText("");
    }

    private void closeAll() {
        sessionInfo.setText("");
        tabs.getTabs().clear();
        for (DbSession s : new ArrayList<>(sessions)) {
            tree.removeSession(s);
            s.close();
        }
        sessions.clear();
    }

    /** Abas ligadas a bancos (de uma sessão, ou de todas quando s == null). */
    private List<SessionTab> sessionTabs(DbSession s) {
        List<SessionTab> list = new ArrayList<>();
        for (Tab t : tabs.getTabs()) {
            if (t instanceof SessionTab st && (s == null || st.session() == s)) {
                list.add(st);
            }
        }
        return list;
    }

    /** Se alguma aba tem algo pendente (alterações, transação, arquivo), pergunta se pode descartar. */
    private boolean confirmDiscard(List<SessionTab> list) {
        List<String> pending = new ArrayList<>();
        for (SessionTab t : list) {
            if (t.hasPendingChanges()) {
                pending.add("• " + t.pendingDescription() + " — " + t.session().displayName());
            }
        }
        if (pending.isEmpty()) {
            return true;
        }
        ButtonType discard = new ButtonType("Descartar alterações", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.WARNING, "", discard, Fx.CANCEL);
        a.initOwner(stage);
        a.setTitle("H2 Studio");
        a.setHeaderText("Existem alterações não salvas");
        a.getDialogPane().setContent(Fx.wrapLabel(String.join("\n", pending)
                + "\n\nPara gravar, cancele e use Salvar (Ctrl+S) ou Commit em cada aba."));
        a.getDialogPane().getStylesheets().add(CSS);
        return a.showAndWait().filter(b -> b == discard).isPresent();
    }

    // =====================================================================
    // Backup do Firebird (.fbk) → banco
    // =====================================================================

    /** Transforma um .fbk num .fdb (gbak) e abre o banco restaurado. */
    public void restoreFbk(Path fbk) {
        // primeiro as conexões abertas (têm a senha mesmo sem "lembrar senha"), depois as recentes
        List<br.com.marianojunior.h2studio.core.ConnectionConfig> known = new ArrayList<>();
        sessions.forEach(x -> known.add(x.config()));
        known.addAll(store.recent());
        new FbkRestoreDialog(stage, fbk, known).showAndWait().ifPresent(this::open);
    }

    // =====================================================================
    // Converter entre bancos (parte 7)
    // =====================================================================

    private void showConvert(DbSession s, String schema, String table) {
        new ConvertDbDialog(stage, s, schema, table, new ConvertDbDialog.Host() {
            @Override
            public List<DbSession> sessions() {
                return new ArrayList<>(sessions);
            }

            @Override
            public void openConfig(br.com.marianojunior.h2studio.core.ConnectionConfig cfg) {
                open(cfg);
            }

            @Override
            public void refresh(DbSession d) {
                tree.load(d);
                for (Tab t : tabs.getTabs()) {
                    if (t instanceof SqlEditorTab q && q.session() == d) {
                        q.reloadCatalog();
                    }
                }
                setStatus("Conversão para " + d.displayName() + " concluída.", false);
            }
        }).showAndWait();
    }

    // =====================================================================
    // Estrutura (Etapa 4)
    // =====================================================================

    private void openDesigner(DbSession s, String schema, String table) {
        if (table != null) {
            for (Tab t : tabs.getTabs()) {
                if (t instanceof TableDesignerTab d && d.session() == s && table.equals(d.originalTable())
                        && schema.equals(d.schema())) {
                    tabs.getSelectionModel().select(t);
                    return;
                }
            }
        }
        TableDesignerTab tab = new TableDesignerTab(s, schema, table);
        tab.setOnApplied((oldName, newName) -> structureChanged(s, schema, oldName, newName));
        tab.setOnCloseRequest(e -> {
            if (tab.hasPendingChanges() && !confirmDiscard(List.of(tab))) {
                e.consume();
            }
        });
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    /**
     * A estrutura de uma tabela mudou (criada, alterada, renomeada ou excluída):
     * atualiza a árvore, o autocompletar dos editores e as abas da tabela.
     *
     * @param newName null quando a tabela foi excluída
     */
    private void structureChanged(DbSession s, String schema, String oldName, String newName) {
        tree.load(s);
        for (Tab t : new ArrayList<>(tabs.getTabs())) {
            if (t instanceof SqlEditorTab q && q.session() == s) {
                q.reloadCatalog();
            }
            if (t instanceof TableTab tt && tt.session() == s && oldName != null
                    && tt.table().name().equals(oldName) && tt.table().schema().equals(schema)) {
                if (newName == null || !newName.equals(oldName)) {
                    tabs.getTabs().remove(tt); // excluída ou renomeada: a aba antiga não vale mais
                } else {
                    tt.refresh();
                }
            }
            if (newName == null && t instanceof TableDesignerTab d && d.session() == s
                    && oldName != null && oldName.equals(d.originalTable())) {
                tabs.getTabs().remove(d);
            }
        }
        setStatus(newName == null ? "Tabela " + oldName + " excluída." : "Estrutura de " + newName + " atualizada.", false);
    }

    private void dropTable(DbSession s, br.com.marianojunior.h2studio.core.Meta.TableInfo t) {
        Fx.then(s.submit(c -> {
            long rows;
            String from = s.isH2() ? t.qualified() : s.type().qualified(t.schema(), t.name());
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM " + from)) {
                rs.next();
                rows = rs.getLong(1);
            }
            var incoming = br.com.marianojunior.h2studio.core.MetadataService.foreignKeys(c, t.schema(), t.name())
                    .stream().filter(br.com.marianojunior.h2studio.core.Meta.ForeignKeyInfo::incoming).toList();
            return new Object[]{rows, incoming};
        }), r -> {
            long rows = (Long) r[0];
            @SuppressWarnings("unchecked")
            List<br.com.marianojunior.h2studio.core.Meta.ForeignKeyInfo> incoming =
                    (List<br.com.marianojunior.h2studio.core.Meta.ForeignKeyInfo>) r[1];
            confirmDrop(s, t, rows, incoming);
        }, e -> Fx.error(stage, "Não foi possível ler a tabela", Fx.shortMessage(e), e));
    }

    private void confirmDrop(DbSession s, br.com.marianojunior.h2studio.core.Meta.TableInfo t, long rows,
                             List<br.com.marianojunior.h2studio.core.Meta.ForeignKeyInfo> incoming) {
        ButtonType del = new ButtonType("Excluir tabela", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.WARNING, "", del, Fx.CANCEL);
        a.initOwner(stage);
        a.setTitle("H2 Studio");
        a.setHeaderText("Excluir a tabela " + (t.schema().isEmpty() ? "" : t.schema() + ".") + t.name() + "?");
        VBox box = new VBox(8);
        box.getChildren().add(Fx.wrapLabel(String.format("%,d", rows).replace(',', '.') + " linha(s) serão apagadas "
                + "definitivamente, junto com índices e chaves da tabela."
                + (!s.isH2() ? " Não há backup automático neste banco: se precisar, faça antes em Backup e restauração."
                : br.com.marianojunior.h2studio.core.AppPreferences.autoBackup() ? " Um backup do banco será feito antes." : "")));
        javafx.scene.control.CheckBox cascade = new javafx.scene.control.CheckBox(
                "Remover também as chaves estrangeiras de outras tabelas que apontam para ela (CASCADE)");
        if (!incoming.isEmpty()) {
            StringBuilder sb = new StringBuilder("Outras tabelas dependem dela:\n");
            for (var f : incoming) {
                sb.append("• ").append(f.refSchema() == null || f.refSchema().isEmpty() ? "" : f.refSchema() + ".").append(f.refTable()).append(" (").append(f.refColumns())
                        .append(") — chave ").append(f.name()).append('\n');
            }
            Label dep = Fx.wrapLabel(sb.toString().trim());
            dep.getStyleClass().add("text-warn");
            box.getChildren().addAll(dep, cascade);
        }
        javafx.scene.control.TextField confirm = new javafx.scene.control.TextField();
        confirm.setPromptText(t.name());
        box.getChildren().addAll(new Label("Para confirmar, digite o nome da tabela:"), confirm);
        a.getDialogPane().setContent(box);
        a.getDialogPane().getStylesheets().add(CSS);
        javafx.scene.Node delBtn = a.getDialogPane().lookupButton(del);
        delBtn.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !confirm.getText().trim().equalsIgnoreCase(t.name())
                        || (!incoming.isEmpty() && !cascade.isSelected()), confirm.textProperty(), cascade.selectedProperty()));
        ((Button) a.getDialogPane().lookupButton(Fx.CANCEL)).setDefaultButton(true);
        ((Button) delBtn).setDefaultButton(false);
        javafx.application.Platform.runLater(confirm::requestFocus);
        if (a.showAndWait().filter(b -> b == del).isEmpty()) {
            return;
        }
        var casing = br.com.marianojunior.h2studio.core.SqlCompletion.casingOf(s.config());
        String def = !s.isH2() ? currentSchemaOf(s)
                : casing == br.com.marianojunior.h2studio.core.SqlCompletion.Casing.LOWER ? "public" : "PUBLIC";
        List<String> stmts = br.com.marianojunior.h2studio.core.DdlBuilder.dropTableStatements(s.type(), t.schema(), t.name(),
                cascade.isSelected(), incoming, casing, def);
        setStatus("Excluindo " + t.name() + "…", true);
        Fx.then(s.submit(c -> {
            String backup = null;
            if (s.isH2() && br.com.marianojunior.h2studio.core.AppPreferences.autoBackup()) {
                backup = br.com.marianojunior.h2studio.core.BackupService.backup(c, s.config(),
                        br.com.marianojunior.h2studio.core.AppPreferences.backupsToKeep()).getFileName().toString();
                s.setBackedUp(true);
            }
            boolean tx = stmts.size() > 1 && br.com.marianojunior.h2studio.core.DdlBuilder.transactionalDdl(s.type());
            boolean auto = c.getAutoCommit();
            if (tx) {
                c.setAutoCommit(false);
            }
            try (var st = c.createStatement()) {
                for (String sql : stmts) {
                    st.execute(sql);
                }
                if (tx) {
                    c.commit();
                }
            } catch (java.sql.SQLException ex) {
                if (tx) {
                    c.rollback();
                }
                throw ex;
            } finally {
                if (tx) {
                    c.setAutoCommit(auto);
                }
            }
            for (String sql : stmts) {
                br.com.marianojunior.h2studio.core.SqlHistory.forDatabase(s.config()).add(sql, true, "excluir tabela");
            }
            return backup;
        }), backup -> {
            structureChanged(s, t.schema(), t.name(), null);
            setStatus("Tabela " + t.name() + " excluída" + (backup != null ? " (backup: " + backup + ")." : "."), false);
        }, e -> {
            setStatus("Não foi possível excluir " + t.name() + ".", false);
            Fx.error(stage, "Não foi possível excluir a tabela", e instanceof java.sql.SQLException se
                    ? br.com.marianojunior.h2studio.core.SqlErrors.friendly(se, s.type()) : Fx.shortMessage(e), e);
        });
    }

    // =====================================================================
    // Utilitários (Etapa 5)
    // =====================================================================

    private void importCsv(DbSession s, br.com.marianojunior.h2studio.core.Meta.TableInfo t) {
        for (Tab tab : tabs.getTabs()) {
            if (tab instanceof TableTab tt && tt.session() == s && tt.table().equals(t) && tt.hasPendingChanges()) {
                Fx.info(stage, "Há alterações não salvas", "Salve ou descarte as alterações da aba " + t.name()
                        + " antes de importar.");
                tabs.getSelectionModel().select(tab);
                return;
            }
        }
        new ImportDialog(stage, s, t.schema(), t).showAndWait().ifPresent(r -> afterImport(s, r));
    }

    /** Importar planilha/CSV como tabela nova (ou numa existente, escolhida no diálogo). */
    private void importFile(DbSession s, String schema) {
        new ImportDialog(stage, s, schema, null).showAndWait().ifPresent(r -> afterImport(s, r));
    }

    private void afterImport(DbSession s, ImportDialog.Outcome r) {
        setStatus(String.format("Importadas %,d linha(s) em %s.", r.inserted(), r.table()).replace(',', '.')
                + (r.created().size() > 1 ? " Tabelas criadas: " + String.join(", ", r.created()) + "." : ""), false);
        for (Tab tab : tabs.getTabs()) {
            if (tab instanceof TableTab tt && tt.session() == s && tt.table().name().equals(r.table())) {
                tt.refresh();
            }
            if (tab instanceof SqlEditorTab q && q.session() == s && !r.created().isEmpty()) {
                q.reloadCatalog();
            }
        }
        if (!r.created().isEmpty()) {
            tree.load(s);
            Fx.then(s.submit(c -> br.com.marianojunior.h2studio.core.MetadataService.tables(c, r.schema())), list ->
                    list.stream().filter(t -> t.name().equals(r.table())).findFirst().ifPresent(t -> openTable(s, t)),
                    e -> { });
        }
    }

    private void dumpScript(DbSession s) {
        ButtonType all = new ButtonType("Estrutura e dados", ButtonBar.ButtonData.OK_DONE);
        ButtonType ddl = new ButtonType("Só a estrutura", ButtonBar.ButtonData.OTHER);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", all, ddl, Fx.CANCEL);
        a.initOwner(stage);
        a.setTitle("H2 Studio");
        a.setHeaderText("Gerar script SQL de " + s.displayName());
        a.getDialogPane().setContent(Fx.wrapLabel("Gera um arquivo .sql com CREATE TABLE, índices, chaves, views e "
                + "(opcionalmente) os INSERT de todos os dados. Serve como backup legível, para levar o banco a outra "
                + "versão do H2 ou para recriá-lo com RUNSCRIPT."));
        a.getDialogPane().getStylesheets().add(CSS);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() == Fx.CANCEL) {
            return;
        }
        boolean data = r.get() == all;
        String base = Path.of(br.com.marianojunior.h2studio.core.H2FileInspector.basePath(s.config().file())).getFileName().toString();
        File f = Fx.chooseSave(stage, "Salvar script SQL", base + (data ? "" : "_estrutura") + ".sql", "script.pasta",
                new javafx.stage.FileChooser.ExtensionFilter("Script SQL (*.sql)", "*.sql"));
        if (f == null) {
            return;
        }
        Path out = f.toPath();
        TaskDialog.run(stage, "Gerando " + out.getFileName(), false, ctx -> s.submit(c -> {
            br.com.marianojunior.h2studio.core.MaintenanceService.dumpScript(c, out, data);
            return Files.size(out);
        }).get(), size -> Fx.infoWithFile(stage, "Script gerado", out + "\n"
                + br.com.marianojunior.h2studio.core.ValueFormatter.humanSize(size), out),
                e -> Fx.error(stage, "Não foi possível gerar o script", Fx.shortMessage(e), e));
    }

    /**
     * Fecha tudo o que usa o banco (abas, editores SQL, conexão), executa a tarefa com o
     * arquivo livre e reabre o banco. Em caso de erro, reabre a configuração original.
     *
     * @param reopen configuração a reabrir depois do sucesso (null = não reabrir)
     */
    private <R> void runWithDatabaseClosed(DbSession s, String title, TaskDialog.Work<R> work,
                                           java.util.function.Function<R, ConnectionConfig> reopen,
                                           Consumer<R> done) {
        if (!confirmDiscard(sessionTabs(s))) {
            return;
        }
        List<CompletableFuture<Void>> closing = new ArrayList<>();
        for (Tab t : new ArrayList<>(tabs.getTabs())) {
            if (t instanceof SessionTab st && st.session() == s) {
                tabs.getTabs().remove(t); // dispara dispose(): editores SQL fecham a conexão própria
                if (t instanceof SqlEditorTab q) {
                    closing.add(q.closedFuture());
                }
            }
        }
        tree.removeSession(s);
        sessions.remove(s);
        sessionInfo.setText("");
        ConnectionConfig original = s.config().copy();
        TaskDialog.run(stage, title, false, ctx -> {
            ctx.status("Fechando as conexões do banco…");
            try {
                CompletableFuture.allOf(closing.toArray(new CompletableFuture[0])).get(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // segue: o H2 recusa a operação se o arquivo ainda estiver aberto
            }
            s.close();
            return work.run(ctx);
        }, r -> {
            done.accept(r);
            ConnectionConfig rc = reopen.apply(r);
            if (rc != null) {
                runOpen(rc, () -> factory.open(rc));
            }
        }, e -> {
            Fx.error(stage, title + ": não concluído", e instanceof DbOpenException de && de.reason() == DbOpenException.Reason.LOCKED
                    ? "O banco está aberto por outro programa (provavelmente o sistema). Feche-o e tente de novo."
                    : Fx.shortMessage(e), e);
            if (!original.isH2() || java.nio.file.Files.isRegularFile(original.file()) && original.getCopyOf() == null) {
                runOpen(original, () -> factory.open(original));
            }
        });
    }

    /**
     * Restaurar um backup de outro banco: como banco novo (padrão, não mexe no atual) ou
     * substituindo o atual (faz antes um backup de segurança e fecha as abas deste banco).
     */
    private void restoreServer(DbSession s, ServerBackupsDialog.RestoreChoice choice) {
        var type = s.type();
        String db = choice.database() == null || choice.database().isBlank() ? s.config().getDatabase() : choice.database();
        String backupName = java.nio.file.Path.of(choice.backup().replace('\\', '/')).getFileName().toString();
        boolean canReplace = type != br.com.marianojunior.h2studio.core.DbType.SQLSERVER;
        boolean fileBased = type == br.com.marianojunior.h2studio.core.DbType.SQLITE;

        javafx.scene.control.ToggleGroup g = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.RadioButton asNew = new javafx.scene.control.RadioButton(
                fileBased ? "Restaurar em outro arquivo:" : type.isFirebird() ? "Restaurar como banco novo (caminho no servidor):"
                        : "Restaurar como banco novo:");
        asNew.setToggleGroup(g);
        asNew.setSelected(true);
        javafx.scene.control.TextField newName = new javafx.scene.control.TextField(fileBased
                ? s.config().getDatabase().replaceAll("(?i)(\\.[a-z0-9]+)?$", "_restaurado$1")
                : br.com.marianojunior.h2studio.core.ServerBackupService.suggestNewName(type, db));
        newName.setPrefColumnCount(40);
        newName.disableProperty().bind(asNew.selectedProperty().not());
        javafx.scene.control.RadioButton replace = new javafx.scene.control.RadioButton("Substituir o banco atual ("
                + (fileBased ? java.nio.file.Path.of(db).getFileName() : db) + ")");
        replace.setToggleGroup(g);
        replace.setDisable(!canReplace);
        String replaceInfo = !canReplace
                ? "No SQL Server a restauração é feita como banco novo (substituir o banco em uso exigiria desconectar "
                + "todos os usuários). Depois de conferir, troque o banco do sistema ou renomeie pelo SSMS."
                : "Substituir: tudo o que foi gravado depois deste backup se perde. Antes, o H2 Studio faz um backup "
                + "de segurança do banco atual (\"antes-de-restaurar\") e fecha as abas deste banco. O sistema que usa "
                + "o banco deve estar FECHADO."
                + (type == br.com.marianojunior.h2studio.core.DbType.MYSQL || type == br.com.marianojunior.h2studio.core.DbType.MARIADB
                ? "\nNo MySQL a restauração não é uma transação: se parar no meio, use o backup de segurança." : "")
                + (type.isFirebird() ? "\nO gbak recusa substituir se houver alguém conectado ao banco." : "");
        Label warn = Fx.wrapLabel(replaceInfo);
        warn.getStyleClass().add(canReplace ? "warn-text" : "help");
        VBox box = new VBox(8, Fx.wrapLabel("Backup: " + backupName), asNew, newName, replace, warn);
        box.setPrefWidth(620);
        ButtonType go = new ButtonType("Restaurar", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", go, Fx.CANCEL);
        a.initOwner(stage);
        a.setTitle("H2 Studio");
        a.setHeaderText("Restaurar backup de " + s.displayName());
        a.getDialogPane().setContent(box);
        a.getDialogPane().getStylesheets().add(CSS);
        if (a.showAndWait().filter(b -> b == go).isEmpty()) {
            return;
        }
        if (asNew.isSelected()) {
            String target = newName.getText().trim();
            if (target.isEmpty() || (!fileBased && !type.isFirebird() && !target.matches("[A-Za-z_][A-Za-z0-9_$]*"))) {
                Fx.error(stage, "Nome inválido", "Use só letras, números e _ no nome do banco novo (sem espaços).", null);
                return;
            }
            if (fileBased && java.nio.file.Files.exists(java.nio.file.Path.of(target))) {
                Fx.error(stage, "O arquivo já existe", target + "\n\nEscolha outro nome para não sobrescrever.", null);
                return;
            }
            TaskDialog.run(stage, "Restaurando backup", true, ctx -> {
                ctx.progress(-1);
                br.com.marianojunior.h2studio.core.ServerBackupService.restore(s, choice.backup(), db, target, false,
                        br.com.marianojunior.h2studio.core.ServerBackupService.silent(ctx::status, ctx::cancelled));
                return target;
            }, t -> {
                ButtonType open = new ButtonType("Abrir agora", ButtonBar.ButtonData.OK_DONE);
                Alert done = new Alert(Alert.AlertType.INFORMATION, "", open, new ButtonType("Fechar",
                        ButtonBar.ButtonData.CANCEL_CLOSE));
                done.initOwner(stage);
                done.setTitle("H2 Studio");
                done.setHeaderText("Backup restaurado em " + t);
                done.getDialogPane().getStylesheets().add(CSS);
                if (done.showAndWait().filter(b -> b == open).isPresent()) {
                    ConnectionConfig nc = s.config().copy();
                    nc.setId(null);
                    nc.setDatabase(t);
                    nc.setName(fileBased ? java.nio.file.Path.of(t).getFileName().toString()
                            : (type.isFirebird() ? java.nio.file.Path.of(t.replace('\\', '/')).getFileName().toString() : t)
                            + " @ " + s.config().getHost());
                    open(nc);
                }
            }, err -> ServerBackupsDialog.showError(stage, "Não foi possível restaurar", err));
            return;
        }
        // substituir: backup de segurança primeiro
        TaskDialog.run(stage, "Backup de segurança antes de restaurar", true, ctx -> {
            ctx.progress(-1);
            return br.com.marianojunior.h2studio.core.ServerBackupService.backup(s, db, null, "antes-de-restaurar",
                    br.com.marianojunior.h2studio.core.ServerBackupService.silent(ctx::status, ctx::cancelled));
        }, safety -> {
            ConnectionConfig cfg = s.config().copy();
            runWithDatabaseClosed(s, "Restaurando backup", ctx -> {
                ctx.progress(-1);
                br.com.marianojunior.h2studio.core.ServerBackupService.restore(s, choice.backup(), db, db, true,
                        br.com.marianojunior.h2studio.core.ServerBackupService.silent(ctx::status, ctx::cancelled));
                return safety;
            }, r -> cfg, r -> Fx.info(stage, "Backup restaurado", "O banco " + db + " foi restaurado a partir de "
                    + backupName + ".\n\nO estado anterior foi guardado em:\n" + r));
        }, err -> ServerBackupsDialog.showError(stage, "O backup de segurança falhou; nada foi restaurado", err));
    }

    private void showBackups(DbSession s) {
        if (!s.isH2()) {
            new ServerBackupsDialog(stage, s).showAndWait().ifPresent(choice -> restoreServer(s, choice));
            return;
        }
        Optional<Path> chosen = new BackupsDialog(stage, s).showAndWait();
        if (chosen.isEmpty()) {
            return;
        }
        Path src = chosen.get();
        ButtonType restore = new ButtonType("Restaurar", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.WARNING, "", restore, Fx.CANCEL);
        a.initOwner(stage);
        a.setTitle("H2 Studio");
        a.setHeaderText("Restaurar " + src.getFileName() + "?");
        a.getDialogPane().setContent(Fx.wrapLabel("O arquivo " + s.config().file().getFileName()
                + " será substituído pelo do backup. Tudo o que foi gravado depois do backup deixa de aparecer.\n\n"
                + "• O banco atual será guardado antes como \"Cópia antes de restaurar\" (dá para desfazer).\n"
                + "• O sistema que usa este banco precisa estar FECHADO.\n"
                + "• As abas deste banco serão fechadas e o banco será reaberto."));
        a.getDialogPane().getStylesheets().add(CSS);
        ((Button) a.getDialogPane().lookupButton(Fx.CANCEL)).setDefaultButton(true);
        ((Button) a.getDialogPane().lookupButton(restore)).setDefaultButton(false);
        if (a.showAndWait().filter(b -> b == restore).isEmpty()) {
            return;
        }
        ConnectionConfig cfg = s.config().copy();
        runWithDatabaseClosed(s, "Restaurando backup", ctx -> br.com.marianojunior.h2studio.core.MaintenanceService
                .restore(factory, cfg, src, ctx::status), r -> {
                    ConnectionConfig rc = cfg.copy();
                    rc.setFilePath(r.restoredFile().toString());
                    return rc;
                }, r -> Fx.info(stage, "Backup restaurado", "O banco foi restaurado a partir de " + src.getFileName()
                        + ".\n\nO estado anterior foi guardado em:\n" + r.safetyCopy()));
    }

    private void compactDatabase(DbSession s) {
        long size;
        try {
            size = Files.size(s.config().file());
        } catch (Exception e) {
            size = -1;
        }
        ButtonType go = new ButtonType("Compactar", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", go, Fx.CANCEL);
        a.initOwner(stage);
        a.setTitle("H2 Studio");
        a.setHeaderText("Compactar " + s.displayName() + "?");
        a.getDialogPane().setContent(Fx.wrapLabel("Tamanho atual: " + br.com.marianojunior.h2studio.core.ValueFormatter.humanSize(size)
                + "\n\nO H2 reescreve o arquivo sem o espaço que sobrou de dados apagados (SHUTDOWN COMPACT). "
                + "Os dados não mudam.\n\n• Um backup é feito antes.\n• O sistema que usa este banco precisa estar FECHADO.\n"
                + "• As abas deste banco serão fechadas e o banco será reaberto."));
        a.getDialogPane().getStylesheets().add(CSS);
        if (a.showAndWait().filter(b -> b == go).isEmpty()) {
            return;
        }
        ConnectionConfig cfg = s.config().copy();
        runWithDatabaseClosed(s, "Compactando banco", ctx -> br.com.marianojunior.h2studio.core.MaintenanceService
                .compact(factory, cfg, true, ctx::status), r -> cfg, r -> {
                    long saved = r.before() - r.after();
                    Fx.info(stage, "Banco compactado", br.com.marianojunior.h2studio.core.ValueFormatter.humanSize(r.before()) + " → "
                            + br.com.marianojunior.h2studio.core.ValueFormatter.humanSize(r.after())
                            + (saved > 0 ? String.format(" (%.0f%% menor)", 100.0 * saved / r.before()) : " (já estava compacto)")
                            + (r.backup() != null ? "\n\nBackup feito antes: " + r.backup().getFileName() : ""));
                });
    }

    private void convertDatabase(DbSession s) {
        Optional<ConvertDialog.Request> req = new ConvertDialog(stage, s).showAndWait();
        if (req.isEmpty()) {
            return;
        }
        ConvertDialog.Request q = req.get();
        ConnectionConfig cfg = s.config().copy();
        TaskDialog.run(stage, "Convertendo para H2 " + q.target().version(), false, ctx -> s.submit(c ->
                br.com.marianojunior.h2studio.core.MaintenanceService.convert(c, s.driver(), factory, s.config(), q.target(),
                        q.dest(), ctx::status)).get(), r -> {
                    String report = String.format("%d tabela(s) e %,d linha(s) conferidas: tudo igual ao original.",
                            r.tables().size(), r.totalRows()).replace(',', '.') + " (" + r.millis() / 1000.0 + " s)";
                    if (q.replace()) {
                        runWithDatabaseClosed(s, "Substituindo o original", ctx2 -> br.com.marianojunior.h2studio.core.MaintenanceService
                                .replaceOriginal(factory, cfg, r.file(), ctx2::status), p -> {
                                    ConnectionConfig rc = cfg.copy();
                                    rc.setFilePath(p.toString());
                                    rc.setDriverJar(null);
                                    return q.openAfter() ? rc : null;
                                }, p -> Fx.info(stage, "Banco convertido para H2 " + q.target().version(), report
                                        + "\n\nO arquivo original foi guardado nos backups (\"Original antes da conversão\")."
                                        + "\n\nLembre de atualizar o H2 do sistema para " + q.target().version() + "."));
                    } else {
                        Fx.infoWithFile(stage, "Banco convertido para H2 " + q.target().version(), report + "\n\nArquivo novo:\n"
                                + r.file() + "\n\nO original não foi alterado.", r.file());
                        if (q.openAfter()) {
                            ConnectionConfig rc = cfg.copy();
                            rc.setId(null);
                            rc.setFilePath(r.file().toString());
                            rc.setName(ConnectionConfig.defaultName(r.file()));
                            rc.setDriverJar(null);
                            runOpen(rc, () -> factory.open(rc));
                        }
                    }
                }, e -> Fx.error(stage, "Não foi possível converter", Fx.shortMessage(e)
                        + "\n\nO banco original não foi alterado.", e));
    }

    // =====================================================================
    // Editor SQL
    // =====================================================================

    /** Contador de abas SQL por banco (SQL 1, SQL 2...). */
    private final java.util.Map<DbSession, Integer> sqlCounter = new java.util.HashMap<>();
    /** Bancos cujo rascunho já está em uso por uma aba (só a primeira aba usa o rascunho). */
    private final java.util.Set<String> draftsInUse = new java.util.HashSet<>();

    private void newQuery() {
        DbSession s = sessionForAnyDb();
        if (s != null) {
            openSqlEditor(s, null, false);
        }
    }

    private String selectFor(DbSession s, br.com.marianojunior.h2studio.core.Meta.TableInfo t) {
        var casing = br.com.marianojunior.h2studio.core.SqlCompletion.casingOf(s.config());
        if (!s.isH2()) {
            var type = s.type();
            String name = br.com.marianojunior.h2studio.core.SqlCompletion.quoteIfNeeded(t.name(), casing, type);
            String current = currentSchemaOf(s);
            String q = t.schema().isEmpty() || t.schema().equals(current) ? name
                    : br.com.marianojunior.h2studio.core.SqlCompletion.quoteIfNeeded(t.schema(), casing, type) + "." + name;
            return switch (type) {
                case FIREBIRD, FIREBIRD_25 -> "SELECT FIRST 100 * FROM " + q + ";\n";
                case SQLSERVER -> "SELECT TOP 100 * FROM " + q + ";\n";
                default -> "SELECT * FROM " + q + " LIMIT 100;\n";
            };
        }
        String name = br.com.marianojunior.h2studio.core.SqlCompletion.quoteIfNeeded(t.name(), casing);
        boolean defaultSchema = t.schema().equalsIgnoreCase("PUBLIC");
        String q = defaultSchema ? name
                : br.com.marianojunior.h2studio.core.SqlCompletion.quoteIfNeeded(t.schema(), casing) + "." + name;
        return "SELECT * FROM " + q + " LIMIT 100;\n";
    }

    private SqlEditorTab openSqlEditor(DbSession s, String initialText, boolean run) {
        int n = sqlCounter.merge(s, 1, Integer::sum);
        String key = s.config().targetKey();
        boolean useDraft = initialText == null && !s.isCopy() && draftsInUse.add(key);
        SqlEditorTab tab = new SqlEditorTab(s, factory, n, initialText, useDraft);
        tab.setOnStructureChanged(() -> tree.load(s));
        tab.setOnCloseRequest(e -> {
            if (tab.hasPendingChanges() && !confirmDiscard(List.of(tab))) {
                e.consume();
            }
        });
        tab.setOnClosed(e -> {
            if (useDraft) {
                draftsInUse.remove(key);
            }
        });
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        tab.focusEditor();
        if (run) {
            tab.runAllWhenReady();
        }
        return tab;
    }

    private void showSqlHistory() {
        DbSession s = sessionForAnyDb();
        if (s == null) {
            return;
        }
        Optional<String> sql = new HistoryDialog(stage, s.displayName(),
                br.com.marianojunior.h2studio.core.SqlHistory.forDatabase(s.config())).showAndWait();
        sql.ifPresent(text -> {
            Tab sel = tabs.getSelectionModel().getSelectedItem();
            if (sel instanceof SqlEditorTab q && q.session() == s) {
                q.insertSql(text);
            } else {
                openSqlEditor(s, text.strip() + (text.strip().endsWith(";") ? "" : ";") + "\n", false);
            }
        });
    }

    // =====================================================================
    // Backups
    // =====================================================================

    /** Schema em uso pela conexão (o que a árvore mostrou aberto). */
    private String currentSchemaOf(DbSession s) {
        try {
            return s.submit(br.com.marianojunior.h2studio.core.MetadataService::currentSchema).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Banco selecionado, de qualquer tipo (editor SQL e histórico funcionam em todos). */
    private DbSession sessionForAnyDb() {
        DbSession s = currentSession();
        if (s == null && sessions.size() == 1) {
            s = sessions.get(0);
        }
        if (s == null) {
            Fx.info(stage, "Selecione um banco", "Selecione um banco na árvore à esquerda.");
        }
        return s;
    }

    private DbSession sessionForTools() {
        DbSession s = currentSession();
        if (s == null && sessions.size() == 1) {
            s = sessions.get(0);
        }
        if (s == null) {
            Fx.info(stage, "Selecione um banco", "Selecione um banco na árvore à esquerda.");
        } else if (!s.isH2()) {
            Fx.info(stage, "Ferramenta própria do H2",
                    s.displayName() + " é " + s.type().label() + ". Esta ferramenta é dos arquivos H2 (script do "
                    + "RUNSCRIPT, compactar, trocar a versão do H2, Flyway). Para os outros bancos use Backup e "
                    + "restauração, Converter para outro banco e o editor SQL.");
            return null;
        }
        return s;
    }

    private void backupNow() {
        DbSession s = sessionForAnyDb();
        if (s == null) {
            return;
        }
        if (!s.isH2()) {
            ServerBackupsDialog.runBackup(stage, s, s.config().getDatabase(), null, p -> {
                setStatus("Backup criado: " + p, false);
                Fx.info(stage, "Backup criado", p);
            });
            return;
        }
        setStatus("Fazendo backup de " + s.displayName() + "…", true);
        Fx.then(s.submit(c -> br.com.marianojunior.h2studio.core.BackupService.backup(c, s.config(),
                br.com.marianojunior.h2studio.core.AppPreferences.backupsToKeep())),
                p -> {
                    setStatus("Backup criado: " + p, false);
                    Fx.info(stage, "Backup criado", p + "\n\nPara restaurar: feche o sistema que usa o banco e "
                            + "extraia o arquivo do .zip no lugar do original.");
                },
                e -> {
                    setStatus("Falha no backup.", false);
                    Fx.error(stage, "Não foi possível fazer o backup", Fx.shortMessage(e), e);
                });
    }

    private void openBackupFolder() {
        DbSession s = sessionForTools();
        if (s == null) {
            return;
        }
        try {
            Path dir = br.com.marianojunior.h2studio.core.BackupService.backupDir(s.config());
            Files.createDirectories(dir);
            hostServices.showDocument(dir.toUri().toString());
        } catch (Exception ex) {
            Fx.error(stage, "Não foi possível abrir a pasta", ex.getMessage(), null);
        }
    }

    // =====================================================================
    // Drivers
    // =====================================================================

    private void showDrivers() {
        DriverRegistry reg = DriverRegistry.get();
        reg.rescan();
        StringBuilder sb = new StringBuilder();
        if (reg.list().isEmpty()) {
            sb.append("Nenhum driver encontrado!\n");
        }
        for (var j : reg.list()) {
            sb.append("• H2 ").append(j.version()).append("  →  abre arquivos ").append(j.family().label())
                    .append("\n    ").append(j.path()).append('\n');
        }
        sb.append("\nPastas pesquisadas:\n");
        for (Path p : reg.searchDirs()) {
            sb.append("  ").append(p).append(Files.isDirectory(p) ? "" : "  (não existe)").append('\n');
        }
        sb.append("\nPara usar outra versão, copie o jar (ex.: h2-2.2.224.jar) para a pasta de drivers extras.\n");
        sb.append("\nOutros bancos (baixados na primeira conexão para ")
                .append(br.com.marianojunior.h2studio.core.JdbcDrivers.installDir()).append("):\n");
        java.util.Set<br.com.marianojunior.h2studio.core.JdbcDrivers.Spec> seen = new java.util.HashSet<>();
        for (var t : br.com.marianojunior.h2studio.core.DbType.connectable()) {
            var spec = t.driver();
            if (!seen.add(spec)) {
                continue;
            }
            boolean ok = br.com.marianojunior.h2studio.core.JdbcDrivers.isInstalled(spec);
            sb.append("• ").append(spec.label()).append(' ').append(spec.version()).append(ok ? "  →  instalado" : "  →  ainda não baixado");
            for (var art : spec.artifacts()) {
                var p = br.com.marianojunior.h2studio.core.JdbcDrivers.find(art);
                sb.append("\n    ").append(p != null ? p.toString() : art.fileName());
            }
            sb.append('\n');
        }

        javafx.scene.control.TextArea ta = new javafx.scene.control.TextArea(sb.toString());
        ta.setEditable(false);
        ta.getStyleClass().add("mono");
        ta.setPrefSize(760, 440);
        ButtonType openFolder = new ButtonType("Abrir pasta de drivers", ButtonBar.ButtonData.LEFT);
        Alert a = new Alert(Alert.AlertType.NONE, "", openFolder, Fx.CLOSE);
        a.initOwner(stage);
        a.setTitle("Drivers");
        a.setHeaderText("Drivers instalados");
        a.getDialogPane().setContent(ta);
        a.getDialogPane().getStylesheets().add(CSS);
        a.showAndWait().filter(b -> b == openFolder).ifPresent(b -> {
            try {
                Path dir = DriverRegistry.userDir().resolve("drivers");
                Files.createDirectories(dir);
                hostServices.showDocument(dir.toUri().toString());
            } catch (Exception ex) {
                Fx.error(stage, "Não foi possível abrir a pasta", ex.getMessage(), null);
            }
        });
    }

    /** Usado por testes de tela. */
    Consumer<Path> fileOpener() {
        return this::openFile;
    }
}
