package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.AppPreferences;
import br.com.marianojunior.h2studio.core.BackupService;
import br.com.marianojunior.h2studio.core.ConnectionFactory;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DriverRegistry;
import br.com.marianojunior.h2studio.core.SqlCompletion;
import br.com.marianojunior.h2studio.core.SqlErrors;
import br.com.marianojunior.h2studio.core.SqlFormatter;
import br.com.marianojunior.h2studio.core.SqlHistory;
import br.com.marianojunior.h2studio.core.SqlLexer;
import br.com.marianojunior.h2studio.core.SqlRunner;
import br.com.marianojunior.h2studio.core.SqlScript;
import java.io.File;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Editor SQL de um banco: realce de sintaxe, autocompletar, execução da instrução
 * sob o cursor ou do script, resultados em abas, histórico e transação própria.
 * <p>
 * Cada aba usa uma conexão só sua: com o auto-commit desligado, as alterações
 * ficam invisíveis para a grade (e para o sistema) até o COMMIT.
 */
public class SqlEditorTab extends Tab implements SessionTab {

    private static final KeyCombination RUN = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination RUN_ALL = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination COMPLETE = new KeyCodeCombination(KeyCode.SPACE, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination FORMAT = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination COMMENT = new KeyCodeCombination(KeyCode.SLASH, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination COMMENT_NUMPAD = new KeyCodeCombination(KeyCode.DIVIDE, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination EXPLAIN = new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN);
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DbSession main;
    private final ConnectionFactory factory;
    private final int number;
    private DbSession conn;
    private boolean ownConnection;

    private final CodeArea area = new CodeArea();
    private final TabPane results = new TabPane();
    private final ListView<LogEntry> log = new ListView<>();
    private final Tab messagesTab = new Tab("Mensagens");
    private final Label status = new Label("Conectando…");
    private final Label caretInfo = new Label();
    private final Label txLabel = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private final Button runBtn = new Button("Executar");
    private final Button runAllBtn = new Button("Tudo");
    private final Button cancelBtn = new Button("Parar");
    private final Button explainBtn = new Button("Plano");
    private final Button commitBtn = new Button("Commit");
    private final Button rollbackBtn = new Button("Rollback");
    private final CheckBox autoCommit = new CheckBox("Auto-commit");
    private final CheckBox continueOnError = new CheckBox("Continuar após erro");
    private final ComboBox<Integer> maxRows = new ComboBox<>(FXCollections.observableArrayList(100, 500, 1000, 5000, 10000, 50000));

    private final SqlHistory history;
    private volatile Statement running;
    private volatile boolean cancelRequested;
    private boolean isRunning;
    private boolean txPending;
    private int txCount;
    private boolean suppressAutoCommitEvent;
    private SqlCompletion.Catalog catalog = SqlCompletion.Catalog.empty();
    private Set<String> tableNames = Set.of();
    private int errorStart = -1;
    private int errorEnd = -1;
    private Path file;
    private String savedText = "";
    private final Path draftFile;
    private final PauseTransition draftSaver = new PauseTransition(javafx.util.Duration.seconds(1.5));
    private Runnable onStructureChanged = () -> { };
    private Runnable runWhenReady;
    /** Completo quando a conexão própria da aba terminou de fechar. */
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    // popup do autocompletar
    private final Popup popup = new Popup();
    private final ListView<SqlCompletion.Item> popupList = new ListView<>();
    private SqlCompletion.Result completion;

    /** Linha do painel de mensagens. {@code errorPos} = posição do erro no texto (-1 se não houver). */
    private record LogEntry(String time, String kind, String text, int errorPos) {
    }

    public SqlEditorTab(DbSession main, ConnectionFactory factory, int number, String initialText, boolean useDraft) {
        this.main = main;
        this.factory = factory;
        this.number = number;
        this.history = SqlHistory.forDatabase(main.config());
        this.draftFile = useDraft ? DriverRegistry.userDir().resolve("rascunhos")
                .resolve(history.file().getFileName().toString()) : null;
        setGraphic(Icons.sql());
        setTooltip(new Tooltip("Editor SQL — " + main.displayName()));
        updateTitle();

        buildEditor();
        buildResults();
        BorderPane root = new BorderPane();
        root.setTop(buildToolbar());
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);
        SplitPane split = new SplitPane(scroll, results);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.45);
        root.setCenter(split);
        root.setBottom(buildStatusBar());
        root.getStyleClass().add("sql-editor");
        setContent(root);

        // texto inicial: o pedido (ex.: SELECT da árvore) ou o rascunho salvo
        String text = initialText;
        if ((text == null || text.isBlank()) && draftFile != null && Files.isRegularFile(draftFile)) {
            try {
                text = Files.readString(draftFile, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                text = "";
            }
        }
        if (text != null && !text.isEmpty()) {
            area.replaceText(text);
            area.moveTo(area.getLength());
        }
        area.getUndoManager().forgetHistory();
        applyHighlight();
        setRunningUi(false);
        updateTxUi();
        runBtn.setDisable(true);
        runAllBtn.setDisable(true);
        explainBtn.setDisable(true);
        connect();
    }

    public void setOnStructureChanged(Runnable r) {
        onStructureChanged = r == null ? () -> { } : r;
    }

    /** Executa todo o texto assim que a conexão estiver pronta (usado pelo "Consultar com SQL"). */
    public void runAllWhenReady() {
        if (conn != null) {
            runAll();
        } else {
            runWhenReady = this::runAll;
        }
    }

    @Override
    public DbSession session() {
        return main;
    }

    public void focusEditor() {
        Platform.runLater(area::requestFocus);
    }

    // =====================================================================
    // Conexão
    // =====================================================================

    private void connect() {
        CompletableFuture.supplyAsync(() -> {
            try {
                return factory.openSibling(main);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((s, err) -> Platform.runLater(() -> {
            if (err == null) {
                conn = s;
                ownConnection = true;
                status.setText("Pronto. Ctrl+Enter executa a instrução sob o cursor.");
            } else {
                conn = main; // usa a conexão principal (sem transação própria)
                ownConnection = false;
                autoCommit.setDisable(true);
                addLog("info", "Não foi possível abrir uma conexão própria (" + Fx.shortMessage(Fx.unwrap(err))
                        + "). Usando a conexão principal, sempre com auto-commit.", -1);
                status.setText("Pronto (conexão compartilhada).");
            }
            if (main.isReadOnly()) {
                autoCommit.setDisable(true);
                txLabel.setText("Banco somente leitura");
            }
            runBtn.setDisable(false);
            runAllBtn.setDisable(false);
            explainBtn.setDisable(false);
            reloadCatalog();
            if (runWhenReady != null) {
                Runnable r = runWhenReady;
                runWhenReady = null;
                r.run();
            }
        }));
    }

    /** Recarrega tabelas/colunas usadas pelo autocompletar e pelo realce. */
    public void reloadCatalog() {
        DbSession c = conn != null ? conn : main;
        Fx.then(c.submit(x -> SqlCompletion.loadCatalog(x, SqlCompletion.casingOf(main.config()), main.type())), cat -> {
            catalog = cat;
            Set<String> names = new HashSet<>();
            cat.tables().forEach(t -> names.add(t.name().toUpperCase(Locale.ROOT)));
            tableNames = names;
            applyHighlight();
        }, e -> addLog("info", "Autocompletar sem a lista de tabelas: " + Fx.shortMessage(e), -1));
    }

    @Override
    public void refresh() {
        reloadCatalog();
    }

    // =====================================================================
    // Montagem da tela
    // =====================================================================

    private Node buildToolbar() {
        runBtn.getStyleClass().add("primary");
        runBtn.setTooltip(new Tooltip("Executa a instrução sob o cursor ou o texto selecionado (Ctrl+Enter)"));
        runBtn.setOnAction(e -> runCurrent());
        runAllBtn.setTooltip(new Tooltip("Executa todas as instruções, uma após a outra (Ctrl+Shift+Enter)"));
        runAllBtn.setOnAction(e -> runAll());
        cancelBtn.setTooltip(new Tooltip("Interrompe a execução"));
        cancelBtn.setOnAction(e -> cancel());
        explainBtn.setTooltip(new Tooltip(explainPrefix() == null
                ? "O " + main.type().shortLabel() + " não tem EXPLAIN por SQL (no SQL Server/Firebird o plano é visto "
                + "nas ferramentas do próprio banco)"
                : "Mostra como o banco vai executar a consulta (" + explainPrefix().trim() + ", Ctrl+E)"));
        explainBtn.setDisable(explainPrefix() == null);
        explainBtn.setOnAction(e -> explain());
        Button format = new Button("Formatar");
        format.setTooltip(new Tooltip("Formata o texto selecionado ou todo o editor (Ctrl+Shift+F)"));
        format.setOnAction(e -> format());
        Button hist = new Button("Histórico");
        hist.setTooltip(new Tooltip("Instruções já executadas neste banco"));
        hist.setOnAction(e -> showHistory());
        Button open = new Button("Abrir…");
        open.setTooltip(new Tooltip("Abrir arquivo .sql"));
        open.setOnAction(e -> openFile());
        Button save = new Button("Salvar");
        save.setTooltip(new Tooltip("Salvar em arquivo .sql (Ctrl+S)"));
        save.setOnAction(e -> saveFile());

        autoCommit.setSelected(true);
        autoCommit.setTooltip(new Tooltip("Desligado: as alterações só valem depois do Commit (e podem ser desfeitas com Rollback)"));
        autoCommit.selectedProperty().addListener((o, a, b) -> onAutoCommitChanged(b));
        commitBtn.setOnAction(e -> endTransaction(true));
        rollbackBtn.setOnAction(e -> endTransaction(false));
        commitBtn.setDisable(true);
        rollbackBtn.setDisable(true);
        txLabel.getStyleClass().add("tx-label");
        continueOnError.setTooltip(new Tooltip("Em \"Executar tudo\", segue para a próxima instrução mesmo se uma falhar"));

        maxRows.setValue(1000);
        maxRows.setTooltip(new Tooltip("Máximo de linhas exibidas por consulta"));
        runBtn.setGraphic(Icons.play());
        runAllBtn.setGraphic(Icons.playAll());

        // opções menos usadas ficam num menu, para a barra caber em telas menores
        javafx.scene.control.CheckMenuItem contItem = new javafx.scene.control.CheckMenuItem("Continuar após erro (Executar tudo)");
        contItem.selectedProperty().bindBidirectional(continueOnError.selectedProperty());
        javafx.scene.control.MenuItem commentItem = new javafx.scene.control.MenuItem("Comentar / descomentar linhas");
        commentItem.setAccelerator(COMMENT);
        commentItem.setOnAction(e -> toggleComment());
        javafx.scene.control.MenuItem completeItem = new javafx.scene.control.MenuItem("Sugestões (autocompletar)");
        completeItem.setAccelerator(COMPLETE);
        completeItem.setOnAction(e -> showCompletion(false));
        javafx.scene.control.MenuItem saveAs = new javafx.scene.control.MenuItem("Salvar como…");
        saveAs.setOnAction(e -> {
            Path old = file;
            file = null;
            saveFile();
            if (file == null) {
                file = old;
            }
        });
        javafx.scene.control.MenuButton more = new javafx.scene.control.MenuButton("Opções", null,
                contItem, new javafx.scene.control.SeparatorMenuItem(), commentItem, completeItem, saveAs);

        Label max = new Label("Máx. linhas:");
        HBox maxBox = new HBox(4, max, maxRows);
        maxBox.setAlignment(Pos.CENTER_LEFT);
        HBox txBox = new HBox(6, autoCommit, commitBtn, rollbackBtn, txLabel);
        txBox.setAlignment(Pos.CENTER_LEFT);
        // FlowPane: em telas estreitas a barra quebra em duas linhas em vez de cortar os botões
        javafx.scene.layout.FlowPane bar = new javafx.scene.layout.FlowPane(6, 4, runBtn, runAllBtn, cancelBtn, explainBtn,
                vsep(), format, hist, open, save, more, vsep(), txBox, vsep(), maxBox);
        for (Node n : List.of(runBtn, runAllBtn, cancelBtn, explainBtn, format, hist, open, save, more, autoCommit,
                commitBtn, rollbackBtn, max, maxRows)) {
            ((javafx.scene.control.Control) n).setMinWidth(Region.USE_PREF_SIZE);
        }
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 6, 8));
        bar.getStyleClass().add("sql-toolbar");
        return bar;
    }

    private static Separator vsep() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.setPrefHeight(22);
        return s;
    }

    private Node buildStatusBar() {
        busy.setMaxSize(14, 14);
        busy.setVisible(false);
        status.getStyleClass().add("muted");
        caretInfo.getStyleClass().add("muted");
        Label db = new Label(main.displayName() + " · " + main.engineLabel());
        db.getStyleClass().add("muted");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bar = new HBox(8, busy, status, sp, caretInfo, new Separator(Orientation.VERTICAL), db);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 8, 3, 8));
        bar.getStyleClass().add("sql-status");
        return bar;
    }

    private void buildEditor() {
        area.getStyleClass().add("sql-area");
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.multiPlainChanges().successionEnds(Duration.ofMillis(60)).subscribe(x -> applyHighlight());
        area.plainTextChanges().subscribe(ch -> {
            errorStart = -1;
            errorEnd = -1;
            updateTitle();
            scheduleDraft();
            String ins = ch.getInserted();
            if (popup.isShowing()) {
                Platform.runLater(this::updatePopup);
            } else if (ins.equals(".")) {
                Platform.runLater(() -> showCompletion(true));
            } else if (ins.length() == 1 && SqlLexer.isIdentPart(ins.charAt(0))
                    && SqlCompletion.isTableContext(area.getText(), area.getCaretPosition())) {
                Platform.runLater(() -> showCompletion(true));
            }
        });
        area.caretPositionProperty().addListener((o, a, b) -> caretInfo.setText(
                "Lin " + (area.getCurrentParagraph() + 1) + ", Col " + (area.getCaretColumn() + 1)));
        // fecha a lista quando o foco vai para outro componente da tela; a própria
        // janela da lista pode receber o foco do sistema ao abrir, e isso não conta
        area.focusedProperty().addListener((o, a, b) -> {
            if (!b) {
                Platform.runLater(() -> {
                    boolean otherNode = area.getScene() != null && area.getScene().getFocusOwner() != area;
                    if (otherNode && !popup.isFocused()) {
                        popup.hide();
                    }
                });
            }
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        popupList.getStyleClass().add("completion-list");
        popupList.setPrefWidth(420);
        popupList.setFixedCellSize(24);
        popupList.setCellFactory(v -> new CompletionCell());
        popupList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                acceptCompletion();
            }
        });
        popup.getContent().add(popupList);
        // sem autoHide: em alguns sistemas a janela da lista ganha e devolve o foco ao abrir,
        // e o autoHide a fecharia na hora. O fechamento é controlado abaixo.
        popup.setAutoHide(false);
        area.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> popup.hide());
        area.sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene == null) {
                popup.hide();
                return;
            }
            scene.windowProperty().addListener((o2, oldW, win) -> {
                if (win != null) {
                    // trocou de aplicativo / minimizou: fecha a lista se nenhuma das janelas tem o foco
                    win.focusedProperty().addListener((o3, was, now) -> {
                        if (!now) {
                            javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(javafx.util.Duration.millis(150));
                            p.setOnFinished(ev -> {
                                if (!win.isFocused() && !popup.isFocused()) {
                                    popup.hide();
                                }
                            });
                            p.play();
                        }
                    });
                }
            });
        });
        selectedProperty().addListener((o, a, b) -> {
            if (!b) {
                popup.hide();
            }
        });
        // Em alguns sistemas a janela da lista recebe o foco do teclado: tudo o que for
        // digitado com a lista aberta é repassado ao editor, e as setas/Enter controlam a lista.
        popupList.addEventFilter(KeyEvent.KEY_PRESSED, this::onPopupKey);
        popupList.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            String ch = e.getCharacter();
            if (ch != null && !ch.isEmpty() && !Character.isISOControl(ch.charAt(0))
                    && !e.isShortcutDown() && !e.isAltDown()) {
                area.replaceSelection(ch);
            }
            e.consume();
        });
        popupList.getStylesheets().add(MainWindow.CSS);
        draftSaver.setOnFinished(e -> saveDraft());
    }

    private void buildResults() {
        log.getStyleClass().add("sql-log");
        log.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(LogEntry it, boolean empty) {
                super.updateItem(it, empty);
                getStyleClass().removeAll("log-ok", "log-error", "log-info");
                if (empty || it == null) {
                    setText(null);
                    return;
                }
                setText(it.time() + "  " + it.text());
                getStyleClass().add("log-" + it.kind());
            }
        });
        log.setOnMouseClicked(e -> {
            LogEntry it = log.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && it != null && it.errorPos() >= 0 && it.errorPos() <= area.getLength()) {
                area.moveTo(it.errorPos());
                area.requestFollowCaret();
                area.requestFocus();
            }
        });
        log.setPlaceholder(new Label("Execute uma instrução (Ctrl+Enter) para ver as mensagens aqui."));
        messagesTab.setContent(log);
        messagesTab.setClosable(false);
        results.getTabs().add(messagesTab);
        results.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
    }

    // =====================================================================
    // Realce de sintaxe
    // =====================================================================

    private void applyHighlight() {
        String text = area.getText();
        if (text.isEmpty()) {
            return;
        }
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int last = 0;
        for (SqlLexer.Token t : SqlLexer.tokenize(text, main.type())) {
            String style = switch (t.type()) {
                case KEYWORD -> "sql-keyword";
                case TYPE -> "sql-type";
                case FUNCTION -> "sql-function";
                case STRING -> "sql-string";
                case NUMBER -> "sql-number";
                case COMMENT -> "sql-comment";
                case PARAM -> "sql-param";
                case QUOTED_IDENT, IDENT -> tableNames.contains(SqlLexer.unquote(t.text()).toUpperCase(Locale.ROOT))
                        ? "sql-table" : t.is(SqlLexer.Type.QUOTED_IDENT) ? "sql-quoted" : null;
                default -> null;
            };
            if (t.start() > last) {
                b.add(Collections.emptyList(), t.start() - last);
            }
            b.add(style == null ? Collections.emptyList() : List.of(style), t.end() - t.start());
            last = t.end();
        }
        if (last < text.length()) {
            b.add(Collections.emptyList(), text.length() - last);
        }
        StyleSpans<Collection<String>> spans = b.create();
        if (errorStart >= 0 && errorEnd > errorStart && errorEnd <= text.length()) {
            StyleSpansBuilder<Collection<String>> eb = new StyleSpansBuilder<>();
            eb.add(Collections.emptyList(), errorStart);
            eb.add(List.of("sql-error"), errorEnd - errorStart);
            eb.add(Collections.emptyList(), text.length() - errorEnd);
            spans = spans.overlay(eb.create(), (x, y) -> {
                if (y.isEmpty()) {
                    return x;
                }
                List<String> m = new ArrayList<>(x);
                m.addAll(y);
                return m;
            });
        }
        area.setStyleSpans(0, spans);
    }

    // =====================================================================
    // Teclado
    // =====================================================================

    private void onKey(KeyEvent e) {
        if (popup.isShowing()) {
            switch (e.getCode()) {
                case DOWN -> {
                    popupList.getSelectionModel().selectNext();
                    popupList.scrollTo(popupList.getSelectionModel().getSelectedIndex());
                    e.consume();
                    return;
                }
                case UP -> {
                    popupList.getSelectionModel().selectPrevious();
                    popupList.scrollTo(popupList.getSelectionModel().getSelectedIndex());
                    e.consume();
                    return;
                }
                case ENTER, TAB -> {
                    if (!e.isShortcutDown()) {
                        acceptCompletion();
                        e.consume();
                        return;
                    }
                }
                case ESCAPE -> {
                    popup.hide();
                    e.consume();
                    return;
                }
                default -> { }
            }
        }
        if (RUN_ALL.match(e)) {
            runAll();
        } else if (RUN.match(e)) {
            runCurrent();
        } else if (COMPLETE.match(e)) {
            showCompletion(false);
        } else if (FORMAT.match(e)) {
            format();
        } else if (COMMENT.match(e) || COMMENT_NUMPAD.match(e)) {
            toggleComment();
        } else if (EXPLAIN.match(e)) {
            explain();
        } else if (e.getCode() == KeyCode.TAB && !e.isShiftDown() && !e.isShortcutDown()) {
            area.replaceSelection("    ");
        } else {
            return;
        }
        e.consume();
    }

    // =====================================================================
    // Autocompletar
    // =====================================================================

    private void onPopupKey(KeyEvent e) {
        switch (e.getCode()) {
            case DOWN -> popupList.getSelectionModel().selectNext();
            case UP -> popupList.getSelectionModel().selectPrevious();
            case PAGE_DOWN -> popupList.getSelectionModel().select(
                    Math.min(popupList.getItems().size() - 1, popupList.getSelectionModel().getSelectedIndex() + 9));
            case PAGE_UP -> popupList.getSelectionModel().select(
                    Math.max(0, popupList.getSelectionModel().getSelectedIndex() - 9));
            case ENTER, TAB -> {
                if (e.isShortcutDown()) {
                    popup.hide();
                    area.requestFocus();
                    javafx.event.Event.fireEvent(area, e.copyFor(area, area));
                } else {
                    acceptCompletion();
                }
            }
            case ESCAPE -> {
                popup.hide();
                area.requestFocus();
            }
            case BACK_SPACE -> area.deletePreviousChar();
            case DELETE -> area.deleteNextChar();
            case LEFT -> {
                area.previousChar(org.fxmisc.richtext.NavigationActions.SelectionPolicy.CLEAR);
                Platform.runLater(this::updatePopup);
            }
            case RIGHT -> {
                area.nextChar(org.fxmisc.richtext.NavigationActions.SelectionPolicy.CLEAR);
                Platform.runLater(this::updatePopup);
            }
            case SHIFT, CONTROL, ALT, META, CAPS -> { }
            default -> {
                if (e.isShortcutDown() || e.isAltDown()) {
                    // atalhos (Ctrl+Enter, Ctrl+S...) vão para o editor
                    popup.hide();
                    area.requestFocus();
                    javafx.event.Event.fireEvent(area, e.copyFor(area, area));
                } else {
                    return; // teclas de texto viram KEY_TYPED (tratado acima)
                }
            }
        }
        popupList.scrollTo(Math.max(0, popupList.getSelectionModel().getSelectedIndex() - 3));
        e.consume();
    }

    private void showCompletion(boolean auto) {
        boolean editorHasFocus = area.getScene() != null && area.getScene().getFocusOwner() == area;
        if (auto && !editorHasFocus && !popup.isShowing()) {
            return;
        }
        completion = SqlCompletion.complete(area.getText(), area.getCaretPosition(), catalog);
        List<SqlCompletion.Item> items = completion.items();
        if (auto) {
            // automático: só tabelas/colunas/schemas (não enche a tela de palavras-chave)
            items = items.stream().filter(i -> i.kind() != SqlCompletion.Kind.KEYWORD
                    && i.kind() != SqlCompletion.Kind.FUNCTION).toList();
        }
        if (items.isEmpty()) {
            popup.hide();
            if (!auto) {
                status.setText("Nenhuma sugestão aqui.");
            }
            return;
        }
        if (!auto && items.size() == 1 && !completion.prefix().isEmpty()) {
            insertCompletion(items.get(0));
            return;
        }
        popupList.getItems().setAll(items);
        popupList.getSelectionModel().selectFirst();
        popupList.scrollTo(0);
        popupList.setPrefHeight(Math.min(items.size(), 10) * 24 + 4);
        showPopupAtCaret(true);
    }

    /** Abre a lista junto ao cursor; logo após digitar, a posição pode ainda não estar calculada. */
    private void showPopupAtCaret(boolean retry) {
        Optional<Bounds> b = area.getCaretBounds();
        if (b.isPresent()) {
            popup.show(area, b.get().getMinX(), b.get().getMaxY() + 2);
        } else if (retry) {
            area.requestFollowCaret();
            Platform.runLater(() -> showPopupAtCaret(false));
        } else {
            Bounds a = area.localToScreen(area.getBoundsInLocal());
            if (a != null) {
                popup.show(area, a.getMinX() + 40, a.getMinY() + 24);
            }
        }
    }

    private void updatePopup() {
        int caret = area.getCaretPosition();
        if (completion == null || caret < completion.replaceStart()) {
            popup.hide();
            return;
        }
        boolean wasAutoFiltered = popupList.getItems().stream().noneMatch(i -> i.kind() == SqlCompletion.Kind.KEYWORD);
        completion = SqlCompletion.complete(area.getText(), caret, catalog);
        List<SqlCompletion.Item> items = completion.items();
        if (wasAutoFiltered) {
            items = items.stream().filter(i -> i.kind() != SqlCompletion.Kind.KEYWORD
                    && i.kind() != SqlCompletion.Kind.FUNCTION).toList();
        }
        if (items.isEmpty()) {
            popup.hide();
            return;
        }
        popupList.getItems().setAll(items);
        popupList.getSelectionModel().selectFirst();
        popupList.setPrefHeight(Math.min(items.size(), 10) * 24 + 4);
    }

    private void acceptCompletion() {
        SqlCompletion.Item it = popupList.getSelectionModel().getSelectedItem();
        if (it != null) {
            insertCompletion(it);
        }
        popup.hide();
    }

    private void insertCompletion(SqlCompletion.Item it) {
        int caret = area.getCaretPosition();
        int start = Math.min(completion.replaceStart(), caret);
        area.replaceText(start, caret, it.insert());
        popup.hide();
        area.requestFocus();
        // depois de "tabela." ou de escolher um schema, já abre as próximas sugestões
        if (it.kind() == SqlCompletion.Kind.SCHEMA) {
            area.insertText(area.getCaretPosition(), ".");
        }
    }

    private static final class CompletionCell extends ListCell<SqlCompletion.Item> {
        @Override
        protected void updateItem(SqlCompletion.Item it, boolean empty) {
            super.updateItem(it, empty);
            if (empty || it == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label kind = new Label(switch (it.kind()) {
                case COLUMN -> "col";
                case TABLE -> "tab";
                case VIEW -> "view";
                case SCHEMA -> "sch";
                case KEYWORD -> "sql";
                case FUNCTION -> "fn";
            });
            kind.getStyleClass().addAll("completion-kind", "kind-" + it.kind().name().toLowerCase(Locale.ROOT));
            kind.setMinWidth(36);
            Label name = new Label(it.label());
            name.getStyleClass().add("completion-name");
            Label detail = new Label(it.detail());
            detail.getStyleClass().add("muted");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            HBox row = new HBox(6, kind, name, sp, detail);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }

    // =====================================================================
    // Execução
    // =====================================================================

    private void runCurrent() {
        String sel = area.getSelectedText();
        if (!sel.isBlank()) {
            execute(SqlScript.split(sel, main.type()), area.getSelection().getStart(), false);
            return;
        }
        SqlScript.Statement s = SqlScript.statementAt(area.getText(), area.getCaretPosition(), main.type());
        if (s == null) {
            status.setText("Nenhuma instrução sob o cursor.");
            return;
        }
        execute(List.of(s), 0, false);
    }

    private void runAll() {
        String sel = area.getSelectedText();
        if (!sel.isBlank()) {
            execute(SqlScript.split(sel, main.type()), area.getSelection().getStart(), false);
        } else {
            execute(SqlScript.split(area.getText(), main.type()), 0, false);
        }
    }

    /** Comando de plano de execução do banco (null quando não há um por SQL). */
    private String explainPrefix() {
        return switch (main.type()) {
            case SQLITE -> "EXPLAIN QUERY PLAN ";
            case SQLSERVER, FIREBIRD, FIREBIRD_25 -> null;
            default -> "EXPLAIN ";
        };
    }

    private void explain() {
        if (explainPrefix() == null) {
            status.setText("O " + main.type().shortLabel() + " não tem EXPLAIN por SQL.");
            return;
        }
        SqlScript.Statement s = SqlScript.statementAt(area.getText(), area.getCaretPosition(), main.type());
        if (s == null) {
            status.setText("Posicione o cursor numa consulta.");
            return;
        }
        if (SqlScript.classify(s.text()) != SqlScript.Kind.QUERY && SqlScript.classify(s.text()) != SqlScript.Kind.DML) {
            status.setText("EXPLAIN só se aplica a consultas e a INSERT/UPDATE/DELETE.");
            return;
        }
        String sql = SqlScript.firstWord(s.text()).equals("EXPLAIN") ? s.text() : explainPrefix() + s.text();
        execute(List.of(new SqlScript.Statement(s.start(), s.end(), sql)), 0, true);
    }

    private void execute(List<SqlScript.Statement> stmts, int base, boolean explain) {
        if (conn == null || isRunning) {
            return;
        }
        if (stmts.isEmpty()) {
            status.setText("Nada para executar.");
            return;
        }
        popup.hide();
        if (!explain) {
            List<String> warnings = new ArrayList<>();
            for (SqlScript.Statement s : stmts) {
                String w = SqlScript.warning(s.text());
                if (w != null) {
                    warnings.add("• " + w);
                }
            }
            if (!warnings.isEmpty() && !confirmDanger(warnings)) {
                status.setText("Execução cancelada.");
                return;
            }
        }
        boolean modifies = !explain && stmts.stream().anyMatch(s -> SqlScript.modifiesDatabase(s.text()));
        if (modifies && !main.isH2() && !main.isBackedUp() && !main.isReadOnly()) {
            // servidor: sem backup automático; confirma uma vez por conexão
            if (!askYesNo("Estas instruções alteram o banco " + main.type().label() + " ("
                    + main.config().location() + ") e o H2 Studio não faz backup automático deste tipo de banco. "
                    + "Executar?")) {
                status.setText("Execução cancelada.");
                return;
            }
            main.setBackedUp(true);
            doExecute(stmts, base, explain);
        } else if (modifies && main.isH2() && AppPreferences.autoBackup() && !main.isBackedUp() && !main.isReadOnly()) {
            setRunningUi(true);
            status.setText("Fazendo backup do banco antes de alterar…");
            Fx.then(conn.submit(c -> BackupService.backup(c, main.config(), AppPreferences.backupsToKeep())), p -> {
                main.setBackedUp(true);
                addLog("info", "Backup criado antes das alterações: " + p, -1);
                setRunningUi(false);
                doExecute(stmts, base, explain);
            }, err -> {
                setRunningUi(false);
                if (askYesNo("Não foi possível fazer o backup automático (" + Fx.shortMessage(err) + "). Executar mesmo assim?")) {
                    main.setBackedUp(true);
                    doExecute(stmts, base, explain);
                } else {
                    status.setText("Execução cancelada.");
                }
            });
        } else {
            doExecute(stmts, base, explain);
        }
    }

    /** Resultado agregado de uma execução. */
    private static final class RunSummary {
        int ok;
        int errors;
        boolean structureChanged;
        boolean modified;
        long ms;
    }

    private void doExecute(List<SqlScript.Statement> stmts, int base, boolean explain) {
        results.getTabs().removeIf(t -> t != messagesTab);
        errorStart = -1;
        errorEnd = -1;
        applyHighlight();
        cancelRequested = false;
        setRunningUi(true);
        status.setText(stmts.size() == 1 ? "Executando…" : "Executando " + stmts.size() + " instruções…");
        int limit = maxRows.getValue();
        boolean cont = continueOnError.isSelected();
        boolean inTx = !autoCommit.isSelected() && ownConnection;
        final int[] resultNo = {0};
        RunSummary sum = new RunSummary();
        Fx.then(conn.submit(c -> {
            long t0 = System.currentTimeMillis();
            for (SqlScript.Statement s : stmts) {
                if (cancelRequested) {
                    break;
                }
                try {
                    SqlRunner.Result r = SqlRunner.run(c, s.text(), limit, st -> running = st);
                    sum.ok++;
                    if (SqlScript.changesStructure(s.text())) {
                        sum.structureChanged = true;
                    }
                    if (SqlScript.modifiesDatabase(s.text())) {
                        sum.modified = true;
                    }
                    history.add(s.text(), true, r.summary());
                    Platform.runLater(() -> onResult(s, r, ++resultNo[0]));
                } catch (SQLException ex) {
                    sum.errors++;
                    String msg = SqlErrors.friendly(ex, main.type());
                    history.add(s.text(), false, msg);
                    int off = explain ? -1 : SqlScript.errorPosition(ex, s.text(), main.type());
                    final int errOff = off;
                    Platform.runLater(() -> onError(s, base, errOff, msg, ex));
                    if (!cont) {
                        break;
                    }
                } finally {
                    running = null;
                }
            }
            sum.ms = System.currentTimeMillis() - t0;
            return sum;
        }), r -> finishRun(r, inTx), err -> {
            setRunningUi(false);
            addLog("error", "Falha: " + Fx.shortMessage(err), -1);
            results.getSelectionModel().select(messagesTab);
        });
    }

    private void onResult(SqlScript.Statement s, SqlRunner.Result r, int n) {
        addLog("ok", oneLine(s.text()) + "  →  " + r.summary(), -1);
        if (r.query()) {
            Tab t = new Tab("Resultado " + n, new ResultView(r, () -> exportResult(r, n)));
            t.setTooltip(new Tooltip(s.text()));
            int idx = results.getTabs().indexOf(messagesTab);
            results.getTabs().add(idx, t);
        }
    }

    /** Exporta o resultado executando a consulta de novo, sem o limite de linhas, na conexão desta aba. */
    private void exportResult(SqlRunner.Result r, int n) {
        DbSession c = conn;
        if (c == null) {
            return;
        }
        var q = new br.com.marianojunior.h2studio.core.TableDataService.Query(r.sql(), List.of());
        ExportAction.show(window(), c, "o resultado " + n, "consulta", null, q);
    }

    private void onError(SqlScript.Statement s, int base, int off, String msg, SQLException ex) {
        int pos = -1;
        if (off >= 0) {
            pos = base + s.start() + off;
            String text = area.getText();
            if (pos >= text.length()) {
                pos = Math.max(0, text.length() - 1);
            }
            int end = pos;
            while (end < text.length() && SqlLexer.isIdentPart(text.charAt(end))) {
                end++;
            }
            errorStart = pos;
            errorEnd = Math.max(end, pos + 1);
            applyHighlight();
            area.moveTo(pos);
            area.requestFollowCaret();
        }
        addLog("error", oneLine(s.text()) + "  →  ERRO: " + msg + (ex.getErrorCode() != 0 ? " [" + ex.getErrorCode() + "]" : ""), pos);
    }

    private void finishRun(RunSummary sum, boolean inTx) {
        setRunningUi(false);
        if (cancelRequested) {
            addLog("info", "Execução interrompida pelo usuário.", -1);
        }
        status.setText(sum.ok + " executada(s)" + (sum.errors > 0 ? ", " + sum.errors + " com erro" : "")
                + " · " + sum.ms + " ms");
        if (inTx && sum.modified) {
            txPending = true;
            txCount += sum.ok;
            updateTxUi();
        }
        if (sum.structureChanged) {
            reloadCatalog();
            onStructureChanged.run();
        }
        // mostra o primeiro resultado, ou as mensagens se não houver resultado / houver erro
        if (sum.errors == 0 && results.getTabs().size() > 1) {
            results.getSelectionModel().select(0);
        } else {
            results.getSelectionModel().select(messagesTab);
            if (!log.getItems().isEmpty()) {
                log.scrollTo(log.getItems().size() - 1);
            }
        }
        updateMessagesTitle();
        area.requestFocus();
    }

    private void cancel() {
        cancelRequested = true;
        Statement st = running;
        if (st != null) {
            new Thread(() -> {
                try {
                    st.cancel();
                } catch (SQLException ignored) {
                    // a instrução já terminou
                }
            }, "h2studio-cancel").start();
        }
        status.setText("Interrompendo…");
    }

    private void setRunningUi(boolean r) {
        isRunning = r;
        busy.setVisible(r);
        runBtn.setDisable(r || conn == null);
        runAllBtn.setDisable(r || conn == null);
        explainBtn.setDisable(r || conn == null);
        cancelBtn.setDisable(!r);
        autoCommit.setDisable(r || !ownConnection || main.isReadOnly());
        commitBtn.setDisable(r || !txPending);
        rollbackBtn.setDisable(r || !txPending);
    }

    private boolean confirmDanger(List<String> warnings) {
        ButtonType run = new ButtonType("Executar mesmo assim", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.WARNING, "", run, Fx.CANCEL);
        a.initOwner(window());
        a.setTitle("H2 Studio");
        a.setHeaderText("Atenção: instrução que pode apagar ou alterar muitos dados");
        String extra = autoCommit.isSelected() && ownConnection
                ? "\n\nO auto-commit está ligado: não será possível desfazer."
                : "\n\nO auto-commit está desligado: você ainda poderá usar Rollback (exceto para DROP/TRUNCATE/ALTER).";
        a.getDialogPane().setContent(Fx.wrapLabel(String.join("\n", warnings) + extra));
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        // o padrão é Cancelar
        ((Button) a.getDialogPane().lookupButton(run)).setDefaultButton(false);
        ((Button) a.getDialogPane().lookupButton(Fx.CANCEL)).setDefaultButton(true);
        return a.showAndWait().filter(b -> b == run).isPresent();
    }

    private boolean askYesNo(String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", Fx.YES, Fx.NO);
        a.initOwner(window());
        a.setTitle("H2 Studio");
        a.setHeaderText(text);
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        return a.showAndWait().filter(b -> b == Fx.YES).isPresent();
    }

    private Window window() {
        return getTabPane() == null || getTabPane().getScene() == null ? null : getTabPane().getScene().getWindow();
    }

    // =====================================================================
    // Transação
    // =====================================================================

    private void onAutoCommitChanged(boolean on) {
        if (suppressAutoCommitEvent || conn == null || !ownConnection) {
            return;
        }
        if (on && txPending) {
            ButtonType commit = new ButtonType("Commit e ligar", ButtonBar.ButtonData.YES);
            ButtonType rollback = new ButtonType("Rollback e ligar", ButtonBar.ButtonData.NO);
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", commit, rollback, Fx.CANCEL);
            a.initOwner(window());
            a.setTitle("H2 Studio");
            a.setHeaderText("Há " + txCount + " instrução(ões) ainda não confirmadas.");
            a.getDialogPane().getStylesheets().add(MainWindow.CSS);
            Optional<ButtonType> r = a.showAndWait();
            if (r.isEmpty() || r.get() == Fx.CANCEL) {
                suppressAutoCommitEvent = true;
                autoCommit.setSelected(false);
                suppressAutoCommitEvent = false;
                return;
            }
            boolean doCommit = r.get() == commit;
            Fx.then(conn.submit(c -> {
                if (doCommit) {
                    c.commit();
                } else {
                    c.rollback();
                }
                c.setAutoCommit(true);
                return null;
            }), x -> {
                addLog("info", (doCommit ? "COMMIT" : "ROLLBACK") + " executado; auto-commit ligado.", -1);
                txPending = false;
                txCount = 0;
                updateTxUi();
            }, e -> addLog("error", "Falha: " + Fx.shortMessage(e), -1));
            return;
        }
        Fx.then(conn.submit(c -> {
            c.setAutoCommit(on);
            return null;
        }), x -> {
            addLog("info", on ? "Auto-commit ligado: cada instrução é gravada na hora."
                    : "Auto-commit desligado: use Commit para gravar ou Rollback para desfazer.", -1);
            updateTxUi();
        }, e -> addLog("error", "Falha ao mudar o auto-commit: " + Fx.shortMessage(e), -1));
    }

    private void endTransaction(boolean commit) {
        if (conn == null || !ownConnection) {
            return;
        }
        Fx.then(conn.submit(c -> {
            if (commit) {
                c.commit();
            } else {
                c.rollback();
            }
            return null;
        }), x -> {
            addLog("info", commit ? "COMMIT: " + txCount + " instrução(ões) gravadas." : "ROLLBACK: alterações desfeitas.", -1);
            history.add(commit ? "COMMIT" : "ROLLBACK", true, "");
            txPending = false;
            txCount = 0;
            updateTxUi();
            if (!commit) {
                reloadCatalog();
                onStructureChanged.run();
            }
        }, e -> addLog("error", "Falha: " + Fx.shortMessage(e), -1));
    }

    private void updateTxUi() {
        boolean manual = !autoCommit.isSelected();
        commitBtn.setDisable(!txPending || isRunning);
        rollbackBtn.setDisable(!txPending || isRunning);
        commitBtn.setVisible(manual);
        rollbackBtn.setVisible(manual);
        commitBtn.setManaged(manual);
        rollbackBtn.setManaged(manual);
        txLabel.setText(txPending ? "Transação aberta: " + txCount + " instrução(ões) sem Commit" : "");
        getStyleClass().remove("tab-pending");
        if (txPending) {
            getStyleClass().add("tab-pending");
        }
        updateTitle();
    }

    // =====================================================================
    // Formatar, comentar, histórico
    // =====================================================================

    private void format() {
        boolean upper = !main.isH2() || SqlCompletion.casingOf(main.config()) != SqlCompletion.Casing.EXACT;
        if (!area.getSelectedText().isBlank()) {
            var sel = area.getSelection();
            area.replaceText(sel.getStart(), sel.getEnd(), SqlFormatter.format(area.getSelectedText(), upper, main.type()));
        } else {
            area.replaceText(SqlFormatter.format(area.getText(), upper, main.type()) + "\n");
        }
    }

    private void toggleComment() {
        var sel = area.getSelection();
        int startPar = area.offsetToPosition(sel.getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        int endPar = area.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward).getMajor();
        boolean allCommented = true;
        for (int p = startPar; p <= endPar; p++) {
            String line = area.getParagraph(p).getText();
            if (!line.isBlank() && !line.stripLeading().startsWith("--")) {
                allCommented = false;
            }
        }
        for (int p = startPar; p <= endPar; p++) {
            String line = area.getParagraph(p).getText();
            int lineStart = area.getAbsolutePosition(p, 0);
            if (allCommented) {
                int i = line.indexOf("--");
                if (i >= 0) {
                    int len = line.startsWith("-- ", i) ? 3 : 2;
                    area.replaceText(lineStart + i, lineStart + i + len, "");
                }
            } else if (!line.isBlank()) {
                area.insertText(lineStart, "-- ");
            }
        }
    }

    public void showHistory() {
        Optional<String> sql = new HistoryDialog(window(), main.displayName(), history).showAndWait();
        sql.ifPresent(this::insertSql);
    }

    /** Insere SQL no editor (numa linha própria, no fim se o cursor estiver no meio de outra instrução). */
    public void insertSql(String sql) {
        String text = area.getText();
        String s = sql.strip();
        if (!s.endsWith(";")) {
            s += ";";
        }
        if (text.isBlank()) {
            area.replaceText(s + "\n");
            area.moveTo(0);
        } else {
            int pos = area.getLength();
            String prefix = text.endsWith("\n\n") ? "" : text.endsWith("\n") ? "\n" : "\n\n";
            area.insertText(pos, prefix + s + "\n");
            area.moveTo(pos + prefix.length());
        }
        area.requestFollowCaret();
        area.requestFocus();
    }

    // =====================================================================
    // Arquivos e rascunho
    // =====================================================================

    private void updateTitle() {
        if (file != null) {
            boolean modified = !area.getText().equals(savedText);
            setText(file.getFileName() + (modified ? " *" : ""));
        } else {
            setText("SQL " + number + (txPending ? " •" : ""));
        }
    }

    private void scheduleDraft() {
        if (draftFile != null && file == null) {
            draftSaver.playFromStart();
        }
    }

    private void saveDraft() {
        if (draftFile == null || file != null) {
            return;
        }
        try {
            Files.createDirectories(draftFile.getParent());
            Files.writeString(draftFile, area.getText(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // rascunho é só conveniência
        }
    }

    private void openFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Abrir script SQL");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Scripts SQL (*.sql)", "*.sql"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*"));
        File f = fc.showOpenDialog(window());
        if (f == null) {
            return;
        }
        if (!area.getText().isBlank() && (file == null || !area.getText().equals(savedText))
                && !askYesNo("Substituir o texto atual do editor pelo arquivo " + f.getName() + "?")) {
            return;
        }
        try {
            String text;
            try {
                text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                text = Files.readString(f.toPath(), StandardCharsets.ISO_8859_1); // arquivos antigos do Windows
            }
            saveDraft();
            file = f.toPath();
            savedText = text;
            area.replaceText(text);
            area.moveTo(0);
            area.getUndoManager().forgetHistory();
            updateTitle();
            setTooltip(new Tooltip(file + "\n" + main.displayName()));
        } catch (Exception e) {
            Fx.error(window(), "Não foi possível abrir o arquivo", e.getMessage(), e);
        }
    }

    /** Ctrl+S */
    public void saveFile() {
        if (file == null) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Salvar script SQL");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Scripts SQL (*.sql)", "*.sql"));
            fc.setInitialFileName("consulta.sql");
            File f = fc.showSaveDialog(window());
            if (f == null) {
                return;
            }
            Path p = f.toPath();
            if (!p.getFileName().toString().contains(".")) {
                p = p.resolveSibling(p.getFileName() + ".sql");
            }
            file = p;
        }
        try {
            Files.writeString(file, area.getText(), StandardCharsets.UTF_8);
            savedText = area.getText();
            updateTitle();
            setTooltip(new Tooltip(file + "\n" + main.displayName()));
            status.setText("Salvo em " + file);
        } catch (Exception e) {
            Fx.error(window(), "Não foi possível salvar o arquivo", e.getMessage(), e);
        }
    }

    // =====================================================================
    // Mensagens
    // =====================================================================

    private void addLog(String kind, String text, int errorPos) {
        log.getItems().add(new LogEntry(LocalTime.now().format(HMS), kind, text, errorPos));
        log.scrollTo(log.getItems().size() - 1);
        updateMessagesTitle();
    }

    private void updateMessagesTitle() {
        long errors = log.getItems().stream().filter(e -> e.kind().equals("error")).count();
        messagesTab.setText(errors == 0 ? "Mensagens" : "Mensagens (" + errors + " erro" + (errors == 1 ? "" : "s") + ")");
    }

    private static String oneLine(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    // =====================================================================
    // SessionTab
    // =====================================================================

    @Override
    public boolean hasPendingChanges() {
        return txPending || (file != null && !area.getText().equals(savedText));
    }

    @Override
    public String pendingDescription() {
        List<String> p = new ArrayList<>();
        if (txPending) {
            p.add("transação aberta com " + txCount + " instrução(ões) sem Commit");
        }
        if (file != null && !area.getText().equals(savedText)) {
            p.add("arquivo " + file.getFileName() + " não salvo");
        }
        String name = file != null ? file.getFileName().toString() : "SQL " + number;
        return name + ": " + String.join(", ", p);
    }

    /** Fecha a conexão própria (desfaz transação aberta) e salva o rascunho. */
    @Override
    public void dispose() {
        popup.hide();
        draftSaver.stop();
        saveDraft();
        cancelRequested = true;
        Statement st = running;
        DbSession c = conn;
        boolean own = ownConnection;
        conn = null;
        new Thread(() -> {
            try {
                if (st != null) {
                    st.cancel();
                }
            } catch (SQLException ignored) {
                // ignora
            }
            if (own && c != null) {
                try {
                    c.rawConnection().rollback();
                } catch (SQLException ignored) {
                    // auto-commit ligado ou conexão já fechada
                }
                c.close();
            }
            closed.complete(null);
        }, "h2studio-sql-close").start();
    }

    /** Para operações que precisam do arquivo fechado (compactar, restaurar...). */
    public CompletableFuture<Void> closedFuture() {
        return closed;
    }

    /** Texto do editor (testes). */
    String text() {
        return area.getText();
    }

    CodeArea area() {
        return area;
    }
}
