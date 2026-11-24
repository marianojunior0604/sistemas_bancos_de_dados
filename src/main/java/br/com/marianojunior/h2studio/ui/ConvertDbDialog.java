package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.ConnectionConfig;
import br.com.marianojunior.h2studio.core.ConnectionFactory;
import br.com.marianojunior.h2studio.core.DbConverter;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DbType;
import br.com.marianojunior.h2studio.core.Meta;
import br.com.marianojunior.h2studio.core.MetadataService;
import br.com.marianojunior.h2studio.core.SqlCompletion;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Converte tabelas do banco selecionado para outro banco aberto, um arquivo novo (SQLite ou H2),
 * um script .sql no dialeto escolhido ou uma planilha .xlsx. Mostra antes o que será criado
 * (tipos de cada coluna, avisos, SQL) e no fim um relatório.
 */
public class ConvertDbDialog extends Dialog<Void> {

    /** O que o diálogo precisa da janela principal. */
    public interface Host {
        List<DbSession> sessions();

        /** Abre na árvore o banco criado pela conversão. */
        void openConfig(ConnectionConfig cfg);

        /** O destino mudou: atualiza árvore e abas. */
        void refresh(DbSession s);
    }

    enum Dest {
        DB("Outro banco aberto"), NEW_SQLITE("Novo arquivo SQLite"), NEW_H2("Novo banco H2"),
        SCRIPT("Script SQL (.sql) para"), XLSX("Planilha Excel (.xlsx)");

        final String label;

        Dest(String label) {
            this.label = label;
        }
    }

    static final class TableItem {
        final String name;
        final SimpleBooleanProperty selected = new SimpleBooleanProperty(true);

        TableItem(String name, boolean sel) {
            this.name = name;
            selected.set(sel);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final DbSession source;
    private final Host host;
    private final ComboBox<String> sourceSchema = new ComboBox<>();
    private final ListView<TableItem> tables = new ListView<>();
    private final ToggleGroup destGroup = new ToggleGroup();
    private final ComboBox<DbSession> targetDb = new ComboBox<>();
    private final ComboBox<String> targetSchema = new ComboBox<>();
    private final ComboBox<DbType> scriptType = new ComboBox<>();
    private final TextField newFile = new TextField();
    private final CheckBox data = new CheckBox("Copiar os dados");
    private final CheckBox indexes = new CheckBox("Criar os índices");
    private final CheckBox keys = new CheckBox("Criar as chaves estrangeiras");
    private final CheckBox replace = new CheckBox("Substituir tabelas que já existem no destino (apaga e recria)");
    private final ComboBox<DbConverter.NameStyle> names = new ComboBox<>();
    private final TableView<DbConverter.TablePlan> planTable = new TableView<>();
    private final TableView<DbConverter.ColumnMap> columnTable = new TableView<>();
    private final TextArea sqlArea = new TextArea();
    private final TextArea notesArea = new TextArea();
    private final Tab notesTab = new Tab("Avisos");
    private final Label status = new Label();
    private final ButtonType analyzeType = new ButtonType("Analisar", ButtonBar.ButtonData.LEFT);
    private final ButtonType convertType = new ButtonType("Converter", ButtonBar.ButtonData.OK_DONE);
    private DbConverter.Plan plan;
    private Set<String> preselect;
    private boolean busy;

    /**
     * @param schema    schema de origem inicial (null = o da conexão)
     * @param onlyTable tabela marcada (null = todas)
     */
    public ConvertDbDialog(Window owner, DbSession source, String schema, String onlyTable, Host host) {
        this.source = source;
        this.host = host;
        this.preselect = onlyTable == null ? null : Set.of(onlyTable);
        initOwner(owner);
        setTitle("Converter para outro banco");
        setHeaderText("Converter " + source.displayName() + " (" + source.engineLabel() + ")");
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        getDialogPane().getButtonTypes().addAll(analyzeType, convertType, new ButtonType("Fechar", ButtonBar.ButtonData.CANCEL_CLOSE));
        getDialogPane().setContent(buildLayout());
        getDialogPane().setPrefSize(1060, 760);

        Button analyze = (Button) getDialogPane().lookupButton(analyzeType);
        analyze.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            analyze(null);
        });
        Button convert = (Button) getDialogPane().lookupButton(convertType);
        convert.getStyleClass().add("primary");
        convert.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (plan == null) {
                analyze(this::confirmAndRun);
            } else {
                confirmAndRun();
            }
        });

        // ---------- dados iniciais ----------
        Fx.then(source.submit(c -> new Object[]{MetadataService.schemas(c, false), MetadataService.currentSchema(c),
                MetadataService.namespace(c)}), r -> {
            @SuppressWarnings("unchecked") List<String> list = (List<String>) r[0];
            String cur = schema != null ? schema : (String) r[1];
            boolean none = r[2] == MetadataService.Namespace.NONE;
            sourceSchema.setItems(FXCollections.observableArrayList(none ? List.of("") : list));
            sourceSchema.setDisable(none);
            sourceSchema.setValue(none ? "" : cur != null && list.contains(cur) ? cur : list.isEmpty() ? "" : list.get(0));
        }, e -> status.setText("Não foi possível ler os schemas: " + Fx.shortMessage(e)));
        sourceSchema.valueProperty().addListener((o, a, b) -> loadTables());
    }

    // =====================================================================
    // Layout
    // =====================================================================

    private Node buildLayout() {
        // ---------- origem ----------
        tables.setCellFactory(CheckBoxListCell.forListView(t -> t.selected));
        tables.setPrefHeight(200);
        Button all = new Button("Marcar todas");
        all.setOnAction(e -> tables.getItems().forEach(t -> t.selected.set(true)));
        Button none = new Button("Desmarcar");
        none.setOnAction(e -> tables.getItems().forEach(t -> t.selected.set(false)));
        sourceSchema.setPrefWidth(200);
        HBox srcTop = new HBox(8, new Label("Schema:"), sourceSchema);
        srcTop.setAlignment(Pos.CENTER_LEFT);
        HBox srcButtons = new HBox(6, all, none);
        VBox src = new VBox(6, srcTop, tables, srcButtons);
        VBox.setVgrow(tables, Priority.ALWAYS);
        TitledPane srcPane = new TitledPane("Origem: tabelas", src);
        srcPane.setCollapsible(false);
        srcPane.setMaxHeight(Double.MAX_VALUE);

        // ---------- destino ----------
        GridPane dest = new GridPane();
        dest.setHgap(8);
        dest.setVgap(6);
        int row = 0;
        for (Dest d : Dest.values()) {
            RadioButton rb = new RadioButton(d.label);
            rb.setUserData(d);
            rb.setToggleGroup(destGroup);
            dest.add(rb, 0, row);
            switch (d) {
                case DB -> {
                    targetDb.setPrefWidth(250);
                    targetSchema.setPrefWidth(150);
                    targetSchema.setPromptText("schema");
                    HBox h = new HBox(6, targetDb, targetSchema);
                    dest.add(h, 1, row);
                }
                case NEW_SQLITE -> {
                    Button pick = new Button("Escolher…");
                    pick.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                    pick.setOnAction(e -> pickNewFile());
                    newFile.setPrefColumnCount(24);
                    dest.add(new HBox(6, newFile, pick), 1, row, 1, 2);
                }
                case SCRIPT -> {
                    scriptType.setItems(FXCollections.observableArrayList(DbType.POSTGRESQL, DbType.MYSQL, DbType.MARIADB,
                            DbType.FIREBIRD, DbType.FIREBIRD_25, DbType.SQLSERVER, DbType.SQLITE, DbType.H2));
                    scriptType.setValue(DbType.POSTGRESQL);
                    scriptType.setPrefWidth(250);
                    scriptType.setCellFactory(l -> typeCell());
                    scriptType.setButtonCell(typeCell());
                    dest.add(scriptType, 1, row);
                }
                case XLSX -> {
                    Label h = new Label("uma aba por tabela, mais uma aba de resumo");
                    h.getStyleClass().add("help");
                    dest.add(h, 1, row);
                }
                default -> { }
            }
            rb.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            row++;
        }
        List<DbSession> others = host.sessions().stream().filter(s -> s != source && !s.isReadOnly()).toList();
        targetDb.setItems(FXCollections.observableArrayList(others));
        targetDb.setCellFactory(l -> sessionCell());
        targetDb.setButtonCell(sessionCell());
        if (!others.isEmpty()) {
            targetDb.setValue(others.get(0));
        }
        targetDb.valueProperty().addListener((o, a, b) -> loadTargetSchemas());
        destGroup.selectToggle(destGroup.getToggles().get(others.isEmpty() ? 1 : 0));
        destGroup.selectedToggleProperty().addListener((o, a, b) -> {
            updateDest();
            stale();
        });
        TitledPane destPane = new TitledPane("Destino", dest);
        destPane.setCollapsible(false);

        // ---------- opções ----------
        data.setSelected(true);
        indexes.setSelected(true);
        keys.setSelected(true);
        names.setItems(FXCollections.observableArrayList(DbConverter.NameStyle.values()));
        names.setValue(defaultNameStyle());
        HBox nameRow = new HBox(8, new Label("Nomes das tabelas e colunas:"), names);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label nameHelp = new Label("No PostgreSQL os nomes ficam em minúsculas e no Firebird/H2 em maiúsculas (o jeito "
                + "que cada um guarda nomes sem aspas). Espaços e símbolos viram _.");
        nameHelp.getStyleClass().add("help");
        nameHelp.setWrapText(true);
        nameHelp.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        nameHelp.setMaxWidth(600);
        VBox opts = new VBox(5, data, indexes, keys, replace, nameRow, nameHelp);
        TitledPane optPane = new TitledPane("Opções", opts);
        optPane.setCollapsible(false);
        for (CheckBox cb : List.of(data, indexes, keys, replace)) {
            cb.selectedProperty().addListener((o, a, b) -> stale());
        }
        names.valueProperty().addListener((o, a, b) -> stale());
        targetSchema.valueProperty().addListener((o, a, b) -> stale());
        scriptType.valueProperty().addListener((o, a, b) -> {
            updateDest();
            stale();
        });

        VBox right = new VBox(8, destPane, optPane);
        HBox top = new HBox(10, srcPane, right);
        HBox.setHgrow(srcPane, Priority.ALWAYS);
        srcPane.setPrefWidth(330);
        right.setPrefWidth(640);

        // ---------- prévia ----------
        TableColumn<DbConverter.TablePlan, String> cSrc = new TableColumn<>("Origem");
        cSrc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source.name()));
        cSrc.setPrefWidth(170);
        TableColumn<DbConverter.TablePlan, String> cDst = new TableColumn<>("Destino");
        cDst.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().target + (c.getValue().exists ? "  (já existe)" : "")));
        cDst.setPrefWidth(190);
        TableColumn<DbConverter.TablePlan, String> cRows = new TableColumn<>("Linhas");
        cRows.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().rows < 0 ? "?"
                : String.format("%,d", c.getValue().rows).replace(',', '.')));
        cRows.setPrefWidth(80);
        cRows.setStyle("-fx-alignment: CENTER-RIGHT;");
        TableColumn<DbConverter.TablePlan, String> cNote = new TableColumn<>("Situação");
        cNote.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().error != null ? "✕ " + c.getValue().error
                : c.getValue().notes.isEmpty() ? "ok" : "⚠ " + c.getValue().notes.size() + " aviso(s)"));
        cNote.setPrefWidth(200);
        planTable.getColumns().addAll(List.of(cSrc, cDst, cRows, cNote));
        planTable.setPlaceholder(new Label("Clique em Analisar para ver como as tabelas ficam no destino."));
        planTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) ->
                columnTable.setItems(b == null ? FXCollections.observableArrayList()
                        : FXCollections.observableArrayList(b.columns)));

        TableColumn<DbConverter.ColumnMap, String> k1 = new TableColumn<>("Coluna");
        k1.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source));
        k1.setPrefWidth(140);
        TableColumn<DbConverter.ColumnMap, String> k2 = new TableColumn<>("Tipo na origem");
        k2.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sourceType));
        k2.setPrefWidth(140);
        TableColumn<DbConverter.ColumnMap, String> k3 = new TableColumn<>("No destino");
        k3.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().target));
        k3.setPrefWidth(140);
        TableColumn<DbConverter.ColumnMap, String> k4 = new TableColumn<>("Tipo no destino");
        k4.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().targetType));
        k4.setPrefWidth(170);
        columnTable.getColumns().addAll(List.of(k1, k2, k3, k4));
        columnTable.setPlaceholder(new Label("Selecione uma tabela."));
        SplitPane split = new SplitPane(planTable, columnTable);
        split.setDividerPositions(0.5);

        sqlArea.setEditable(false);
        sqlArea.getStyleClass().add("mono");
        notesArea.setEditable(false);
        notesArea.setWrapText(true);
        Tab t1 = new Tab("Tabelas e tipos", split);
        Tab t2 = new Tab("SQL da estrutura", sqlArea);
        notesTab.setContent(notesArea);
        TabPane preview = new TabPane(t1, t2, notesTab);
        preview.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        preview.setPrefHeight(300);
        VBox.setVgrow(preview, Priority.ALWAYS);

        status.setWrapText(true);
        status.getStyleClass().add("muted");
        VBox box = new VBox(10, top, preview, status);
        box.setPadding(new Insets(4));
        updateDest();
        loadTargetSchemas();
        return box;
    }

    private static ListCell<DbType> typeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(DbType t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.label());
            }
        };
    }

    private static ListCell<DbSession> sessionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(DbSession s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.displayName() + " · " + s.type().shortLabel());
            }
        };
    }

    private Dest dest() {
        return destGroup.getSelectedToggle() == null ? Dest.DB : (Dest) destGroup.getSelectedToggle().getUserData();
    }

    private void updateDest() {
        Dest d = dest();
        targetDb.setDisable(d != Dest.DB || targetDb.getItems().isEmpty());
        targetSchema.setDisable(d != Dest.DB || targetSchema.getItems().isEmpty());
        newFile.setDisable(d != Dest.NEW_SQLITE && d != Dest.NEW_H2);
        scriptType.setDisable(d != Dest.SCRIPT);
        if ((d == Dest.NEW_SQLITE || d == Dest.NEW_H2)) {
            String ext = d == Dest.NEW_SQLITE ? ".sqlite" : ".mv.db";
            String cur = newFile.getText();
            if (cur.isBlank() || cur.endsWith(".sqlite") || cur.endsWith(".mv.db")) {
                Path base = suggestedFolder().resolve(baseName() + ext);
                newFile.setText(base.toString());
            }
        }
        DbType to = targetType();
        boolean exact = to != null && casing(to) == SqlCompletion.Casing.EXACT;
        names.setDisable(d == Dest.XLSX || !exact);
        if (!exact) {
            names.setValue(DbConverter.NameStyle.KEEP);
        } else if (names.getValue() == DbConverter.NameStyle.KEEP && sourceIsUpper()) {
            names.setValue(DbConverter.NameStyle.LOWER);
        }
        boolean structure = d != Dest.XLSX;
        indexes.setDisable(!structure);
        keys.setDisable(!structure);
        replace.setDisable(d != Dest.DB);
        data.setDisable(d == Dest.XLSX);
        if (d == Dest.XLSX) {
            data.setSelected(true);
        }
    }

    private boolean sourceIsUpper() {
        SqlCompletion.Casing c = SqlCompletion.casingOf(source.config());
        return c == SqlCompletion.Casing.UPPER;
    }

    private DbConverter.NameStyle defaultNameStyle() {
        return sourceIsUpper() ? DbConverter.NameStyle.LOWER : DbConverter.NameStyle.KEEP;
    }

    private String baseName() {
        String n = source.config().getName() == null || source.config().getName().isBlank()
                ? source.config().getDatabase() : source.config().getName();
        n = Path.of(n.isBlank() ? "convertido" : n).getFileName().toString();
        n = n.replaceAll("(?i)\\.(mv\\.db|h2\\.db|fdb|gdb|sqlite3?|db)$", "").replaceAll("[^\\p{L}\\p{N}_.-]", "_");
        return (n.isBlank() ? "convertido" : n) + "_convertido";
    }

    private Path suggestedFolder() {
        String last = br.com.marianojunior.h2studio.core.AppPreferences.get("converter.pasta", null);
        if (last != null && Files.isDirectory(Path.of(last))) {
            return Path.of(last);
        }
        return Path.of(System.getProperty("user.home"));
    }

    private void pickNewFile() {
        Dest d = dest();
        if (d != Dest.NEW_SQLITE && d != Dest.NEW_H2) {
            destGroup.selectToggle(destGroup.getToggles().get(1));
            d = Dest.NEW_SQLITE;
        }
        File f = Fx.chooseSave(getDialogPane().getScene().getWindow(), "Arquivo do banco novo",
                Path.of(newFile.getText()).getFileName().toString(), "converter.pasta",
                d == Dest.NEW_SQLITE ? new FileChooser.ExtensionFilter("SQLite (*.sqlite, *.db)", "*.sqlite", "*.db")
                        : new FileChooser.ExtensionFilter("H2 (*.mv.db)", "*.mv.db"));
        if (f != null) {
            newFile.setText(f.getAbsolutePath());
        }
    }

    private void loadTables() {
        String sch = sourceSchema.getValue();
        if (sch == null) {
            return;
        }
        Fx.then(source.submit(c -> MetadataService.tables(c, sch)), list -> {
            List<TableItem> items = new ArrayList<>();
            for (Meta.TableInfo t : list) {
                if (!t.view()) {
                    items.add(new TableItem(t.name(), preselect == null || preselect.contains(t.name())));
                }
            }
            items.forEach(i -> i.selected.addListener((o, a, b) -> stale()));
            tables.setItems(FXCollections.observableArrayList(items));
            long views = list.stream().filter(Meta.TableInfo::view).count();
            status.setText(items.size() + " tabela(s)" + (views > 0 ? "; " + views + " view(s) não são convertidas" : "")
                    + ". Escolha o destino e clique em Analisar.");
            stale();
        }, e -> status.setText("Não foi possível listar as tabelas: " + Fx.shortMessage(e)));
    }

    private void loadTargetSchemas() {
        DbSession d = targetDb.getValue();
        if (d == null) {
            targetSchema.setItems(FXCollections.observableArrayList());
            return;
        }
        Fx.then(d.submit(c -> new Object[]{MetadataService.schemas(c, false), MetadataService.currentSchema(c),
                MetadataService.namespace(c)}), r -> {
            @SuppressWarnings("unchecked") List<String> list = (List<String>) r[0];
            boolean none = r[2] == MetadataService.Namespace.NONE;
            targetSchema.setItems(FXCollections.observableArrayList(none ? List.of() : list));
            targetSchema.setValue(none ? null : (String) r[1]);
            updateDest();
            stale();
        }, e -> status.setText("Não foi possível ler o destino: " + Fx.shortMessage(e)));
    }

    private void stale() {
        plan = null;
        if (!busy) {
            planTable.getItems().clear();
            sqlArea.clear();
            notesArea.clear();
            notesTab.setText("Avisos");
        }
    }

    // =====================================================================
    // Plano
    // =====================================================================

    private DbType targetType() {
        return switch (dest()) {
            case DB -> targetDb.getValue() == null ? null : targetDb.getValue().type();
            case NEW_SQLITE -> DbType.SQLITE;
            case NEW_H2 -> DbType.H2;
            case SCRIPT -> scriptType.getValue();
            case XLSX -> DbType.SQLITE; // só usa os tipos para ler os dados
        };
    }

    static SqlCompletion.Casing casing(DbType t) {
        return switch (t) {
            case POSTGRESQL -> SqlCompletion.Casing.LOWER;
            case H2, FIREBIRD, FIREBIRD_25 -> SqlCompletion.Casing.UPPER;
            default -> SqlCompletion.Casing.EXACT;
        };
    }

    private DbConverter.Options options() {
        return new DbConverter.Options(names.getValue() == null ? DbConverter.NameStyle.KEEP : names.getValue(),
                data.isSelected(), indexes.isSelected() && !indexes.isDisabled(), keys.isSelected() && !keys.isDisabled(),
                replace.isSelected() && dest() == Dest.DB, 500);
    }

    private List<String> chosenTables() {
        return tables.getItems().stream().filter(t -> t.selected.get()).map(t -> t.name).toList();
    }

    private void analyze(Runnable then) {
        List<String> chosen = chosenTables();
        if (chosen.isEmpty()) {
            status.setText("Marque pelo menos uma tabela.");
            return;
        }
        Dest d = dest();
        DbType to = targetType();
        if (to == null) {
            status.setText("Escolha o banco de destino (abra-o antes na árvore) ou outro tipo de destino.");
            return;
        }
        String srcSchema = sourceSchema.getValue() == null ? "" : sourceSchema.getValue();
        DbConverter.Options opt = options();
        busy = true;
        status.setText("Analisando…");
        CompletableFuture<DbConverter.Plan> f;
        if (d == Dest.DB) {
            DbSession dst = targetDb.getValue();
            String wanted = targetSchema.getValue();
            SqlCompletion.Casing cs = SqlCompletion.casingOf(dst.config());
            f = dst.submit(c -> {
                String cur = MetadataService.currentSchema(c);
                String sch = wanted == null ? (cur == null ? "" : cur) : wanted;
                Set<String> existing = new HashSet<>();
                for (Meta.TableInfo t : MetadataService.tables(c, sch)) {
                    existing.add(t.name());
                }
                return new Object[]{cur == null ? "" : cur, sch, existing};
            }).thenCompose(r -> source.submit(c -> {
                @SuppressWarnings("unchecked") Set<String> ex = (Set<String>) r[2];
                return DbConverter.plan(c, source.type(), srcSchema, chosen, dst.type(), cs, (String) r[1], (String) r[0], ex, opt);
            })).thenCompose(p -> dst.submit(c -> {
                DbConverter.planDrops(p, c);
                return p;
            }));
        } else {
            String sch = to == DbType.H2 && d == Dest.NEW_H2 ? "PUBLIC" : "";
            f = source.submit(c -> DbConverter.plan(c, source.type(), srcSchema, chosen, to, casing(to), sch, sch, null, opt));
        }
        Fx.then(f, p -> {
            busy = false;
            plan = p;
            showPlan(p);
            if (then != null) {
                then.run();
            }
        }, e -> {
            busy = false;
            status.setText("Não foi possível analisar: " + Fx.shortMessage(e));
            Fx.error(getDialogPane().getScene().getWindow(), "Não foi possível analisar", Fx.shortMessage(e), e);
        });
    }

    private void showPlan(DbConverter.Plan p) {
        planTable.setItems(FXCollections.observableArrayList(p.tables));
        if (!p.tables.isEmpty()) {
            planTable.getSelectionModel().select(0);
        }
        sqlArea.setText(dest() == Dest.XLSX ? "-- A planilha recebe só os dados (uma aba por tabela)." : p.script());
        List<String> lines = new ArrayList<>();
        if (!p.conflicts().isEmpty()) {
            lines.add("✕ Já existem no destino: " + String.join(", ", p.conflicts())
                    + ". Marque \"Substituir tabelas que já existem\" ou escolha outro schema/banco.");
        }
        if (!p.drop.isEmpty()) {
            lines.add("⚠ Serão APAGADAS e recriadas no destino: " + p.tables.stream().filter(t -> t.exists)
                    .map(t -> t.target).reduce((a, b) -> a + ", " + b).orElse("") + ".");
        }
        p.tables.stream().filter(t -> t.error != null).forEach(t -> lines.add("✕ " + t.source.name() + ": " + t.error));
        p.notes.forEach(n -> lines.add("⚠ " + n));
        p.tables.forEach(t -> t.notes.forEach(n -> lines.add("⚠ " + t.target + ": " + n)));
        notesArea.setText(lines.isEmpty() ? "Nenhum aviso." : String.join("\n\n", lines));
        notesTab.setText(lines.isEmpty() ? "Avisos" : "Avisos (" + lines.size() + ")");
        long rows = p.totalRows();
        status.setText(p.tables.size() + " tabela(s), " + String.format("%,d", rows).replace(',', '.')
                + " linha(s) → " + destLabel() + ". Confira os tipos e os avisos e clique em Converter.");
    }

    private String destLabel() {
        return switch (dest()) {
            case DB -> targetDb.getValue().displayName() + " (" + targetDb.getValue().type().shortLabel() + ")";
            case NEW_SQLITE -> "novo SQLite";
            case NEW_H2 -> "novo banco H2";
            case SCRIPT -> "script " + scriptType.getValue().shortLabel();
            case XLSX -> "planilha";
        };
    }

    // =====================================================================
    // Execução
    // =====================================================================

    private Window window() {
        return getDialogPane().getScene().getWindow();
    }

    private void confirmAndRun() {
        DbConverter.Plan p = plan;
        if (p == null) {
            return;
        }
        if (!p.conflicts().isEmpty()) {
            Fx.info(window(), "Tabelas já existem no destino", String.join(", ", p.conflicts())
                    + "\n\nMarque \"Substituir tabelas que já existem no destino\" para apagá-las e recriá-las, "
                    + "ou escolha outro schema/banco.");
            return;
        }
        if (p.tables.stream().allMatch(t -> t.error != null)) {
            Fx.info(window(), "Nada para converter", "Nenhuma tabela pôde ser traduzida. Veja a aba Avisos.");
            return;
        }
        Dest d = dest();
        Path file = null;
        if (d == Dest.SCRIPT || d == Dest.XLSX) {
            boolean x = d == Dest.XLSX;
            File f = Fx.chooseSave(window(), x ? "Salvar planilha" : "Salvar script SQL",
                    baseName() + (x ? ".xlsx" : "_" + p.to.name().toLowerCase(Locale.ROOT) + ".sql"), "converter.pasta",
                    x ? new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx")
                            : new FileChooser.ExtensionFilter("Script SQL (*.sql)", "*.sql"));
            if (f == null) {
                return;
            }
            file = f.toPath();
        } else if (d == Dest.NEW_SQLITE || d == Dest.NEW_H2) {
            String t = newFile.getText().trim();
            if (t.isEmpty()) {
                Fx.info(window(), "Informe o arquivo", "Escolha onde criar o banco novo.");
                return;
            }
            file = Path.of(t);
            if (d == Dest.NEW_H2 && !t.endsWith(".mv.db")) {
                file = Path.of(t + ".mv.db");
            }
            if (Files.exists(file)) {
                Fx.info(window(), "O arquivo já existe", file + "\n\nEscolha outro nome: a conversão sempre cria um banco novo.");
                return;
            }
            br.com.marianojunior.h2studio.core.AppPreferences.set("converter.pasta", file.toAbsolutePath().getParent().toString());
        }
        if (d == Dest.DB) {
            DbSession dst = targetDb.getValue();
            ButtonType go = new ButtonType("Converter", ButtonBar.ButtonData.OK_DONE);
            Alert a = new Alert(p.drop.isEmpty() ? Alert.AlertType.CONFIRMATION : Alert.AlertType.WARNING, "", go, Fx.CANCEL);
            a.initOwner(window());
            a.setTitle("H2 Studio");
            a.setHeaderText("Converter " + p.tables.size() + " tabela(s) para " + dst.displayName() + "?");
            String msg = String.format("%,d", p.totalRows()).replace(',', '.') + " linha(s) serão copiadas para "
                    + dst.displayName() + " (" + dst.type().label() + ")"
                    + (p.targetSchema.isEmpty() ? "" : ", schema " + p.targetSchema) + ".";
            if (!p.drop.isEmpty()) {
                msg += "\n\nAs tabelas abaixo JÁ EXISTEM no destino e serão APAGADAS com os dados delas:\n"
                        + p.tables.stream().filter(t -> t.exists).map(t -> "• " + t.target).reduce((x, y) -> x + "\n" + y).orElse("");
            }
            msg += "\n\nO banco de origem não é alterado.";
            a.getDialogPane().setContent(Fx.wrapLabel(msg));
            a.getDialogPane().getStylesheets().add(MainWindow.CSS);
            if (!p.drop.isEmpty()) {
                ((Button) a.getDialogPane().lookupButton(Fx.CANCEL)).setDefaultButton(true);
                ((Button) a.getDialogPane().lookupButton(go)).setDefaultButton(false);
            }
            if (a.showAndWait().filter(b -> b == go).isEmpty()) {
                return;
            }
        }
        run(p, d, file);
    }

    private void run(DbConverter.Plan p, Dest d, Path file) {
        DbSession dst = targetDb.getValue();
        String title = "Convertendo para " + destLabel();
        ConnectionConfig[] created = {null};
        TaskDialog.run(window(), title, true, ctx -> {
            DbConverter.Listener l = new DbConverter.Listener() {
                @Override
                public void status(String text) {
                    ctx.status(text);
                }

                @Override
                public void progress(double fraction) {
                    ctx.progress(fraction);
                }

                @Override
                public boolean cancelled() {
                    return ctx.cancelled();
                }
            };
            return switch (d) {
                case DB -> source.submit(c -> dst.submit(x -> DbConverter.run(p, c, DbConverter.database(x), l)).get()).get();
                case SCRIPT -> source.submit(c -> DbConverter.run(p, c, DbConverter.script(file), l)).get();
                case XLSX -> source.submit(c -> DbConverter.run(p, c, DbConverter.spreadsheet(file), l)).get();
                case NEW_SQLITE, NEW_H2 -> {
                    ctx.status("Criando " + file.getFileName() + "…");
                    ConnectionConfig cfg = d == Dest.NEW_SQLITE ? DbConverter.newSqliteFile(file)
                            : DbConverter.newH2File(file, "sa", "");
                    created[0] = cfg;
                    try (DbSession x = new ConnectionFactory().open(cfg)) {
                        yield source.submit(c -> x.runNow(xc -> DbConverter.run(p, c, DbConverter.database(xc), l))).get();
                    }
                }
            };
        }, report -> {
            if (d == Dest.DB) {
                host.refresh(dst);
            }
            if (created[0] != null) {
                host.openConfig(created[0]);
            }
            showReport(report, d, file);
            if (report.ok()) {
                close();
            }
        }, e -> Fx.error(window(), "A conversão falhou", Fx.shortMessage(e), e));
    }

    private void showReport(DbConverter.Report r, Dest d, Path file) {
        StringBuilder sb = new StringBuilder();
        for (DbConverter.TableResult t : r.tables()) {
            sb.append(t.error() == null ? "✓ " : "✕ ").append(t.source());
            if (!t.source().equals(t.target())) {
                sb.append(" → ").append(t.target());
            }
            sb.append(": ").append(String.format("%,d", t.copied()).replace(',', '.'));
            if (t.expected() >= 0 && t.expected() != t.copied()) {
                sb.append(" de ").append(String.format("%,d", t.expected()).replace(',', '.'));
            }
            sb.append(" linha(s)\n");
        }
        if (!r.errors().isEmpty()) {
            sb.append("\nPROBLEMAS:\n");
            r.errors().forEach(e -> sb.append("✕ ").append(e).append("\n"));
        }
        if (!r.notes().isEmpty()) {
            sb.append("\nAVISOS:\n");
            r.notes().forEach(n -> sb.append("⚠ ").append(n).append("\n"));
        }
        String head = r.cancelled() ? "Conversão cancelada" : r.errors().isEmpty() ? "Conversão concluída"
                : "Conversão concluída com problemas";
        String sub = String.format("%,d", r.rows()).replace(',', '.') + " linha(s) em "
                + String.format(Locale.ROOT, "%.1f", r.millis() / 1000.0).replace('.', ',') + " s"
                + (r.cancelled() ? ". As tabelas já criadas ficaram no destino." : ".")
                + (d == Dest.NEW_H2 ? " Usuário do banco novo: sa, sem senha." : "");
        ButtonType openDir = new ButtonType("Abrir pasta", ButtonBar.ButtonData.LEFT);
        ButtonType copy = new ButtonType("Copiar relatório", ButtonBar.ButtonData.LEFT);
        List<ButtonType> bts = new ArrayList<>();
        if (file != null) {
            bts.add(openDir);
        }
        bts.add(copy);
        bts.add(Fx.OK);
        Alert a = new Alert(r.ok() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING, "", bts.toArray(ButtonType[]::new));
        a.initOwner(window());
        a.setTitle("H2 Studio");
        a.setHeaderText(head);
        TextArea ta = new TextArea(sb.toString().strip());
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefSize(720, 320);
        VBox box = new VBox(8, Fx.wrapLabel(sub + (file != null ? "\n" + file : "")), ta);
        a.getDialogPane().setContent(box);
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        a.setResizable(true);
        Button copyBtn = (Button) a.getDialogPane().lookupButton(copy);
        copyBtn.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            Fx.copy(head + "\n" + sub + "\n\n" + sb);
        });
        a.showAndWait().ifPresent(b -> {
            if (b == openDir && file != null) {
                Fx.openInSystem(file.toAbsolutePath().getParent());
            }
        });
    }

    /** Usado pelos testes. */
    DbConverter.Plan plan() {
        return plan;
    }
}
