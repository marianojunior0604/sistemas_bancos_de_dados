package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.AppPreferences;
import br.com.marianojunior.h2studio.core.BackupService;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DdlBuilder;
import br.com.marianojunior.h2studio.core.FlywayInfo;
import br.com.marianojunior.h2studio.core.Meta;
import br.com.marianojunior.h2studio.core.MetadataService;
import br.com.marianojunior.h2studio.core.SqlCompletion;
import br.com.marianojunior.h2studio.core.SqlErrors;
import br.com.marianojunior.h2studio.core.SqlHistory;
import br.com.marianojunior.h2studio.core.TableDesign;
import br.com.marianojunior.h2studio.core.TableDesign.Column;
import br.com.marianojunior.h2studio.core.TableDesign.ForeignKey;
import br.com.marianojunior.h2studio.core.TableDesign.Index;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Designer de tabela: cria uma tabela nova ou altera uma existente (colunas,
 * chave primária, índices, chaves estrangeiras, comentários), mostrando o SQL
 * que será executado. Pode aplicar no banco (com backup antes) ou salvar o
 * script como migração do Flyway.
 */
public class TableDesignerTab extends Tab implements SessionTab {

    private final DbSession session;
    private final SqlCompletion.Casing casing;
    private final String defaultSchema;
    private TableDesign design;
    private DdlBuilder.Plan plan = new DdlBuilder.Plan(List.of(), List.of(), List.of());

    private final TextField tableName = new TextField();
    private final TextField tableComment = new TextField();
    private final Label schemaLabel = new Label();
    private final GridPane columnsGrid = new GridPane();
    private final GridPane indexGrid = new GridPane();
    private final GridPane fkGrid = new GridPane();
    private final TextArea sqlPreview = new TextArea();
    private final Tab sqlTab = new Tab("SQL");
    private final VBox messages = new VBox(3);
    private final Label flywayBanner = new Label();
    private final Button applyBtn = new Button("Aplicar no banco");
    private final Button migrationBtn = new Button("Salvar como migração…");
    private final Button copyBtn = new Button("Copiar SQL");
    private final Button discardBtn = new Button("Descartar alterações");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));

    private SqlCompletion.Catalog catalog = SqlCompletion.Catalog.empty();
    private Set<String> existingTables = Set.of();
    private Meta.TableInfo flywayTable;
    private List<FlywayInfo.Migration> flywayMigrations = List.of();
    private BiConsumer<String, String> onApplied = (a, b) -> { };
    private boolean building;

    /**
     * @param table null para criar uma tabela nova no schema informado
     */
    public TableDesignerTab(DbSession session, String schema, String table) {
        this.session = session;
        this.casing = SqlCompletion.casingOf(session.config());
        this.defaultSchema = casing == SqlCompletion.Casing.LOWER ? "public" : "PUBLIC";
        setGraphic(Icons.design());
        setText(table == null ? "Nova tabela" : "Estrutura: " + table);
        setTooltip(new Tooltip(session.displayName()));

        debounce.setOnFinished(e -> regenerate());
        setContent(buildLayout());
        setDisabledAll(true);

        Fx.then(session.submit(c -> {
            TableDesign d = table == null ? TableDesign.newTable(schema, casing) : TableDesign.load(c, schema, table);
            SqlCompletion.Catalog cat = SqlCompletion.loadCatalog(c, casing);
            Meta.TableInfo fw = FlywayInfo.findHistoryTable(c).orElse(null);
            List<FlywayInfo.Migration> ms = fw == null ? List.of() : FlywayInfo.read(c, fw);
            return new Object[]{d, cat, fw, ms};
        }), r -> {
            design = (TableDesign) r[0];
            catalog = (SqlCompletion.Catalog) r[1];
            flywayTable = (Meta.TableInfo) r[2];
            @SuppressWarnings("unchecked") List<FlywayInfo.Migration> ms = (List<FlywayInfo.Migration>) r[3];
            flywayMigrations = ms;
            afterLoad();
        }, e -> {
            messages.getChildren().setAll(msg("Não foi possível ler a estrutura: " + Fx.shortMessage(e), "text-error"));
        });
    }

    public void setOnApplied(BiConsumer<String, String> c) {
        onApplied = c == null ? (a, b) -> { } : c;
    }

    @Override
    public DbSession session() {
        return session;
    }

    /** Tabela em edição (nome original), ou null se for nova. */
    public String originalTable() {
        return design == null ? null : design.originalName;
    }

    public String schema() {
        return design == null ? null : design.schema;
    }

    // =====================================================================
    // Montagem
    // =====================================================================

    private Node buildLayout() {
        tableName.setPromptText("NOME_DA_TABELA");
        tableName.setPrefColumnCount(24);
        tableName.textProperty().addListener((o, a, b) -> {
            if (!building) {
                design.name = b;
                changed();
            }
        });
        tableName.focusedProperty().addListener((o, was, now) -> {
            if (!now && design != null && !Objects.equals(design.name, design.originalName)) {
                tableName.setText(DdlBuilder.normalizeName(tableName.getText(), casing));
            }
        });
        tableComment.setPromptText("Descrição da tabela (opcional)");
        tableComment.textProperty().addListener((o, a, b) -> {
            if (!building) {
                design.comment = b;
                changed();
            }
        });
        HBox.setHgrow(tableComment, Priority.ALWAYS);
        schemaLabel.getStyleClass().add("muted");
        Label tl = new Label("Tabela:");
        tl.getStyleClass().add("section-title");
        HBox header = new HBox(8, tl, tableName, schemaLabel, new Label("  Comentário:"), tableComment);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 10, 6, 10));

        flywayBanner.getStyleClass().addAll("banner", "banner-info");
        flywayBanner.setWrapText(true);
        flywayBanner.setMaxWidth(Double.MAX_VALUE);
        flywayBanner.setVisible(false);
        flywayBanner.setManaged(false);

        Button addCol = new Button("+ Coluna");
        addCol.setOnAction(e -> addColumn());
        Tab colTab = new Tab("Colunas", section(columnsGrid, addCol,
                "Obrigatório = NOT NULL. Padrão é uma expressão SQL: 0, TRUE, 'texto', CURRENT_TIMESTAMP."));
        Button addIdx = new Button("+ Índice");
        addIdx.setOnAction(e -> addIndex());
        Tab idxTab = new Tab("Índices", section(indexGrid, addIdx,
                "Índices automáticos (da chave primária e das chaves estrangeiras) aparecem só para consulta."));
        Button addFk = new Button("+ Chave estrangeira");
        addFk.setOnAction(e -> addForeignKey());
        Tab fkTab = new Tab("Chaves estrangeiras", section(fkGrid, addFk,
                "Liga colunas desta tabela à chave de outra tabela (ex.: VENDA.CLIENTE_ID → CLIENTE.ID)."));
        sqlPreview.setEditable(false);
        sqlPreview.getStyleClass().addAll("mono", "ddl");
        sqlTab.setContent(padded(sqlPreview));
        TabPane tabs = new TabPane(colTab, idxTab, fkTab, sqlTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("inner-tabs");

        applyBtn.getStyleClass().add("primary");
        applyBtn.setOnAction(e -> apply());
        migrationBtn.setTooltip(new Tooltip("Gera um arquivo V…__descricao.sql para o Flyway do sistema"));
        migrationBtn.setOnAction(e -> saveMigration());
        copyBtn.setOnAction(e -> Fx.copy(plan.script()));
        discardBtn.setOnAction(e -> reload());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox buttons = new HBox(8, discardBtn, sp, copyBtn, migrationBtn, applyBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);
        ScrollPane msgScroll = new ScrollPane(messages);
        msgScroll.setFitToWidth(true);
        msgScroll.setPrefHeight(70);
        msgScroll.getStyleClass().add("designer-messages");
        VBox bottom = new VBox(6, msgScroll, buttons);
        bottom.setPadding(new Insets(6, 10, 8, 10));
        bottom.getStyleClass().add("designer-bottom");

        BorderPane root = new BorderPane(tabs);
        root.setTop(new VBox(header, flywayBanner));
        root.setBottom(bottom);
        root.getStyleClass().add("designer");
        return root;
    }

    private static Node section(GridPane grid, Button add, String help) {
        grid.setHgap(6);
        grid.setVgap(4);
        grid.setPadding(new Insets(8));
        Label h = new Label(help);
        h.getStyleClass().add("help");
        HBox bar = new HBox(10, add, h);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6, 8, 0, 8));
        ScrollPane sc = new ScrollPane(grid);
        sc.setFitToWidth(true);
        BorderPane p = new BorderPane(sc);
        p.setTop(bar);
        return p;
    }

    private static Node padded(Node n) {
        BorderPane p = new BorderPane(n);
        p.setPadding(new Insets(8));
        return p;
    }

    private void setDisabledAll(boolean d) {
        tableName.setDisable(d);
        tableComment.setDisable(d);
        applyBtn.setDisable(d);
        migrationBtn.setDisable(d);
        copyBtn.setDisable(d);
    }

    private void afterLoad() {
        building = true;
        tableName.setText(design.name);
        tableComment.setText(design.comment == null ? "" : design.comment);
        building = false;
        schemaLabel.setText("schema " + design.schema);
        existingTables = new HashSet<>();
        for (Meta.TableInfo t : catalog.tables()) {
            if (t.schema().equals(design.schema)) {
                existingTables.add(t.name());
            }
        }
        if (flywayTable != null) {
            String last = flywayMigrations.isEmpty() ? "nenhuma" : "V" + flywayMigrations.get(flywayMigrations.size() - 1).version();
            flywayBanner.setText("Este banco é controlado pelo Flyway do sistema (última migração: " + last + "). "
                    + "Escolha UM caminho: \"Salvar como migração\" (o sistema aplica ao iniciar) ou \"Aplicar no banco\" "
                    + "(direto, sem registro no Flyway). Fazer os dois faz a migração falhar no próximo início do sistema.");
            flywayBanner.setVisible(true);
            flywayBanner.setManaged(true);
        }
        setDisabledAll(false);
        rebuildAll();
        regenerate();
        if (design.isNew()) {
            Platform.runLater(tableName::requestFocus);
        }
    }

    private void reload() {
        if (design == null) {
            return;
        }
        String schema = design.schema;
        String table = design.originalName;
        setDisabledAll(true);
        Fx.then(session.submit(c -> new Object[]{
            table == null ? TableDesign.newTable(schema, casing) : TableDesign.load(c, schema, table),
            SqlCompletion.loadCatalog(c, casing)}), r -> {
                design = (TableDesign) r[0];
                catalog = (SqlCompletion.Catalog) r[1];
                afterLoad();
            }, e -> messages.getChildren().setAll(msg("Erro ao recarregar: " + Fx.shortMessage(e), "text-error")));
    }

    private void changed() {
        debounce.playFromStart();
    }

    private void rebuildAll() {
        rebuildColumns();
        rebuildIndexes();
        rebuildForeignKeys();
    }

    // =====================================================================
    // Colunas
    // =====================================================================

    private static final String[] COL_HEADERS = {"PK", "Nome", "Tipo", "Tamanho", "Decimais", "Obrigatório", "Auto inc.",
        "Padrão", "Comentário", ""};

    private void rebuildColumns() {
        columnsGrid.getChildren().clear();
        columnsGrid.getColumnConstraints().setAll(cc(30), cc(150), cc(175), cc(66), cc(60), cc(78), cc(62), cc(130), grow(110), cc(36));
        header(columnsGrid, COL_HEADERS);
        int r = 1;
        for (Column c : design.columns) {
            addColumnRow(c, r++);
        }
    }

    private void addColumnRow(Column c, int r) {
        CheckBox pk = new CheckBox();
        pk.setSelected(c.primaryKey);
        pk.setTooltip(new Tooltip("Chave primária"));
        TextField name = new TextField(c.name);
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList(
                TableDesign.TYPES.stream().map(TableDesign.TypeInfo::name).toList()));
        type.setEditable(true);
        type.setValue(c.type);
        type.setMaxWidth(Double.MAX_VALUE);
        TextField len = numField(c.length);
        TextField scale = numField(c.scale);
        CheckBox notNull = new CheckBox();
        notNull.setSelected(!c.nullable || c.primaryKey);
        CheckBox identity = new CheckBox();
        identity.setSelected(c.identity);
        identity.setDisable(!c.isNew());
        if (!c.isNew()) {
            identity.setTooltip(new Tooltip("O auto incremento só pode ser definido em colunas novas"));
        }
        TextField def = new TextField(c.defaultExpr == null ? "" : c.defaultExpr);
        def.setPromptText(c.identity ? "(automático)" : "");
        TextField comment = new TextField(c.comment == null ? "" : c.comment);
        Button action = new Button(c.dropped ? "↺" : "✕");
        action.getStyleClass().add("row-action");
        action.setTooltip(new Tooltip(c.dropped ? "Desfazer remoção" : c.isNew() ? "Remover coluna" : "Remover coluna (apaga os dados dela)"));

        Runnable refreshState = () -> {
            TableDesign.TypeInfo ti = TableDesign.typeInfo(c.type);
            len.setDisable(c.dropped || (ti != null && !ti.hasLength()));
            scale.setDisable(c.dropped || ti == null || !ti.hasScale());
            notNull.setDisable(c.dropped || c.primaryKey || c.identity);
            def.setDisable(c.dropped || c.identity);
            for (Node n : List.of(pk, name, type, comment)) {
                n.setDisable(c.dropped);
            }
            identity.setDisable(c.dropped || !c.isNew());
            String state = c.dropped ? "col-dropped" : c.isNew() ? "col-new" : isModified(c) ? "col-modified" : null;
            for (Node n : List.of(name, type, len, scale, def, comment)) {
                n.getStyleClass().removeAll("col-dropped", "col-new", "col-modified");
                if (state != null) {
                    n.getStyleClass().add(state);
                }
            }
        };

        pk.selectedProperty().addListener((o, a, b) -> {
            c.primaryKey = b;
            if (b) {
                notNull.setSelected(true);
            }
            refreshState.run();
            changed();
        });
        final String[] lastName = {c.name};
        name.textProperty().addListener((o, a, b) -> {
            c.name = b;
            refreshState.run();
            changed();
        });
        name.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                boolean renamedOrNew = c.isNew() || !Objects.equals(name.getText(), c.original.name);
                if (renamedOrNew && !name.getText().isBlank()) {
                    name.setText(DdlBuilder.normalizeName(name.getText(), casing));
                }
                renameReferences(lastName[0], c.name);
                lastName[0] = c.name;
            }
        });
        type.valueProperty().addListener((o, a, b) -> {
            c.type = b == null ? "" : b.trim().toUpperCase(Locale.ROOT);
            TableDesign.TypeInfo ti = TableDesign.typeInfo(c.type);
            boolean typeChanged = a != null && !a.trim().equalsIgnoreCase(c.type);
            if (ti != null) {
                // ao trocar de tipo (ex.: VARCHAR(255) → DECIMAL) o tamanho volta ao padrão do novo tipo
                if (ti.hasLength() && (c.length == null || typeChanged)) {
                    len.setText(ti.defaultLength() == null ? "" : String.valueOf(ti.defaultLength()));
                }
                if (!ti.hasLength()) {
                    len.setText("");
                }
                if (ti.hasScale() && (c.scale == null || typeChanged)) {
                    scale.setText(ti.defaultScale() == null ? "" : String.valueOf(ti.defaultScale()));
                }
                if (!ti.hasScale()) {
                    scale.setText("");
                }
            }
            refreshState.run();
            changed();
        });
        // o ComboBox editável só confirma o texto ao sair do campo: atualiza enquanto digita também
        type.getEditor().textProperty().addListener((o, a, b) -> {
            if (type.isFocused()) {
                c.type = b == null ? "" : b.trim().toUpperCase(Locale.ROOT);
                changed();
            }
        });
        len.textProperty().addListener((o, a, b) -> {
            c.length = parseInt(b);
            changed();
        });
        scale.textProperty().addListener((o, a, b) -> {
            c.scale = parseInt(b);
            changed();
        });
        notNull.selectedProperty().addListener((o, a, b) -> {
            c.nullable = !b;
            changed();
        });
        identity.selectedProperty().addListener((o, a, b) -> {
            c.identity = b;
            if (b) {
                notNull.setSelected(true);
                def.setText("");
            }
            def.setPromptText(b ? "(automático)" : "");
            refreshState.run();
            changed();
        });
        def.textProperty().addListener((o, a, b) -> {
            c.defaultExpr = b == null || b.isBlank() ? null : b;
            changed();
        });
        comment.textProperty().addListener((o, a, b) -> {
            c.comment = b == null || b.isBlank() ? null : b;
            changed();
        });
        action.setOnAction(e -> {
            if (c.isNew()) {
                design.columns.remove(c);
                removeReferences(c.name);
            } else {
                c.dropped = !c.dropped;
            }
            rebuildAll();
            changed();
        });
        refreshState.run();
        columnsGrid.addRow(r, center(pk), name, type, len, scale, center(notNull), center(identity), def, comment, action);
    }

    private static boolean isModified(Column c) {
        Column o = c.original;
        return !Objects.equals(c.name, o.name) || !DdlBuilder.typeSql(c).equals(DdlBuilder.typeSql(o))
                || c.nullable != o.nullable || c.primaryKey != o.primaryKey
                || !Objects.equals(blankToNull(c.defaultExpr), blankToNull(o.defaultExpr))
                || !Objects.equals(blankToNull(c.comment), blankToNull(o.comment));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void addColumn() {
        Column c = new Column();
        c.name = "";
        design.columns.add(c);
        rebuildColumns();
        changed();
        // foca o nome da nova coluna
        Platform.runLater(() -> columnsGrid.getChildren().stream()
                .filter(n -> GridPane.getRowIndex(n) != null && GridPane.getRowIndex(n) == design.columns.size()
                        && n instanceof TextField)
                .findFirst().ifPresent(Node::requestFocus));
    }

    /** Coluna renomeada: atualiza índices e chaves que a usam. */
    private void renameReferences(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName) || oldName.isBlank()) {
            return;
        }
        boolean any = false;
        for (Index i : design.indexes) {
            if (i.columns.contains(oldName)) {
                i.columns.replaceAll(n -> n.equals(oldName) ? newName : n);
                any = true;
            }
        }
        for (ForeignKey f : design.foreignKeys) {
            if (f.columns.contains(oldName)) {
                f.columns.replaceAll(n -> n.equals(oldName) ? newName : n);
                any = true;
            }
        }
        if (any) {
            rebuildIndexes();
            rebuildForeignKeys();
            changed();
        }
    }

    private void removeReferences(String name) {
        for (Index i : design.indexes) {
            i.columns.remove(name);
        }
        for (ForeignKey f : design.foreignKeys) {
            int p = f.columns.indexOf(name);
            if (p >= 0) {
                f.columns.remove(p);
                if (p < f.refColumns.size()) {
                    f.refColumns.remove(p);
                }
            }
        }
    }

    // =====================================================================
    // Índices
    // =====================================================================

    private void rebuildIndexes() {
        indexGrid.getChildren().clear();
        indexGrid.getColumnConstraints().setAll(cc(240), grow(260), cc(60), cc(150), cc(40));
        header(indexGrid, new String[]{"Nome", "Colunas", "Único", "", ""});
        int r = 1;
        for (Index i : design.indexes) {
            TextField name = new TextField(i.name);
            MenuButton cols = columnPicker(i.columns, () -> {
                changed();
            });
            CheckBox unique = new CheckBox();
            unique.setSelected(i.unique);
            Label info = new Label(i.automatic ? "automático" : i.dropped ? "será removido" : i.isNew() ? "novo" : "");
            info.getStyleClass().add("muted");
            Button action = new Button(i.dropped ? "↺" : "✕");
            action.getStyleClass().add("row-action");
            name.textProperty().addListener((o, a, b) -> {
                i.name = b;
                changed();
            });
            name.focusedProperty().addListener((o, was, now) -> {
                if (!now && (i.isNew() || !Objects.equals(name.getText(), i.original.name))) {
                    name.setText(DdlBuilder.normalizeName(name.getText(), casing));
                }
            });
            unique.selectedProperty().addListener((o, a, b) -> {
                i.unique = b;
                changed();
            });
            action.setOnAction(e -> {
                if (i.isNew()) {
                    design.indexes.remove(i);
                } else {
                    i.dropped = !i.dropped;
                }
                rebuildIndexes();
                changed();
            });
            boolean locked = i.automatic || i.dropped;
            name.setDisable(locked);
            cols.setDisable(locked);
            unique.setDisable(locked);
            action.setDisable(i.automatic);
            if (i.dropped) {
                name.getStyleClass().add("col-dropped");
            } else if (i.isNew()) {
                name.getStyleClass().add("col-new");
            }
            indexGrid.addRow(r++, name, cols, center(unique), info, action);
        }
        if (design.indexes.isEmpty()) {
            Label none = new Label("Nenhum índice. Índices aceleram buscas e ordenações por uma coluna (ex.: NOME, CPF).");
            none.getStyleClass().add("muted");
            indexGrid.add(none, 0, 1, 5, 1);
        }
    }

    private void addIndex() {
        Index i = new Index();
        long own = design.indexes.stream().filter(x -> !x.automatic).count();
        i.name = DdlBuilder.normalizeName("idx_" + baseName() + "_" + (own + 1), casing);
        design.indexes.add(i);
        rebuildIndexes();
        changed();
    }

    private String baseName() {
        String n = design.name == null || design.name.isBlank() ? "tabela" : design.name.trim();
        return n.toLowerCase(Locale.ROOT);
    }

    /** Botão com a lista de colunas da tabela para marcar (a ordem de marcação é a ordem no índice). */
    private MenuButton columnPicker(List<String> selected, Runnable onChange) {
        MenuButton mb = new MenuButton();
        mb.setMnemonicParsing(false); // nomes com "_" (CLIENTE_ID) não podem virar atalho
        mb.setMaxWidth(Double.MAX_VALUE);
        Runnable updateText = () -> mb.setText(selected.isEmpty() ? "(escolha as colunas)" : String.join(", ", selected));
        for (Column c : design.activeColumns()) {
            if (c.name == null || c.name.isBlank()) {
                continue;
            }
            String n = c.name;
            CheckMenuItem item = new CheckMenuItem(n);
            item.setMnemonicParsing(false);
            item.setSelected(selected.contains(n));
            item.selectedProperty().addListener((o, a, b) -> {
                if (b && !selected.contains(n)) {
                    selected.add(n);
                } else if (!b) {
                    selected.remove(n);
                }
                updateText.run();
                onChange.run();
            });
            mb.getItems().add(item);
        }
        updateText.run();
        return mb;
    }

    // =====================================================================
    // Chaves estrangeiras
    // =====================================================================

    private void rebuildForeignKeys() {
        fkGrid.getChildren().clear();
        fkGrid.getColumnConstraints().setAll(cc(170), grow(140), cc(170), grow(120), cc(110), cc(110), cc(36));
        header(fkGrid, new String[]{"Nome", "Coluna(s) desta tabela", "Tabela referenciada", "Coluna(s) dela",
            "ON DELETE", "ON UPDATE", ""});
        int r = 1;
        List<String> tableChoices = new ArrayList<>();
        for (Meta.TableInfo t : catalog.tables()) {
            if (!t.view()) {
                tableChoices.add(t.schema().equals(defaultSchema) ? t.name() : t.schema() + "." + t.name());
            }
        }
        for (ForeignKey f : design.foreignKeys) {
            TextField name = new TextField(f.name);
            MenuButton cols = columnPicker(f.columns, this::changed);
            ComboBox<String> ref = new ComboBox<>(FXCollections.observableArrayList(tableChoices));
            ref.setMaxWidth(Double.MAX_VALUE);
            if (f.refTable != null) {
                ref.setValue(f.refSchema == null || f.refSchema.equals(defaultSchema) ? f.refTable : f.refSchema + "." + f.refTable);
            }
            MenuButton refCols = new MenuButton();
            refCols.setMnemonicParsing(false);
            refCols.setMaxWidth(Double.MAX_VALUE);
            fillRefColumns(refCols, f);
            ComboBox<String> onDel = new ComboBox<>(FXCollections.observableArrayList(TableDesign.FK_RULES));
            onDel.setValue(f.onDelete == null || f.onDelete.equals("NO ACTION") ? "RESTRICT" : f.onDelete);
            ComboBox<String> onUpd = new ComboBox<>(FXCollections.observableArrayList(TableDesign.FK_RULES));
            onUpd.setValue(f.onUpdate == null || f.onUpdate.equals("NO ACTION") ? "RESTRICT" : f.onUpdate);
            Button action = new Button(f.dropped ? "↺" : "✕");
            action.getStyleClass().add("row-action");
            name.textProperty().addListener((o, a, b) -> {
                f.name = b;
                changed();
            });
            name.focusedProperty().addListener((o, was, now) -> {
                if (!now && (f.isNew() || !Objects.equals(name.getText(), f.original.name))) {
                    name.setText(DdlBuilder.normalizeName(name.getText(), casing));
                }
            });
            ref.valueProperty().addListener((o, a, b) -> {
                if (b == null) {
                    return;
                }
                int dot = b.indexOf('.');
                f.refSchema = dot < 0 ? defaultSchema : b.substring(0, dot);
                f.refTable = dot < 0 ? b : b.substring(dot + 1);
                f.refColumns.clear();
                // escolhe sozinho a chave primária da tabela referenciada
                Fx.then(session.submit(c -> MetadataService.columns(c, f.refSchema, f.refTable)), refColsInfo -> {
                    List<String> pks = refColsInfo.stream().filter(Meta.ColumnInfo::primaryKey).map(Meta.ColumnInfo::name).toList();
                    if (f.refColumns.isEmpty() && pks.size() == Math.max(1, f.columns.size())) {
                        f.refColumns.addAll(pks);
                    }
                    fillRefColumns(refCols, f);
                    changed();
                }, e -> fillRefColumns(refCols, f));
            });
            onDel.valueProperty().addListener((o, a, b) -> {
                f.onDelete = b;
                changed();
            });
            onUpd.valueProperty().addListener((o, a, b) -> {
                f.onUpdate = b;
                changed();
            });
            action.setOnAction(e -> {
                if (f.isNew()) {
                    design.foreignKeys.remove(f);
                } else {
                    f.dropped = !f.dropped;
                }
                rebuildForeignKeys();
                changed();
            });
            for (Node n : List.of(name, cols, ref, refCols, onDel, onUpd)) {
                n.setDisable(f.dropped);
            }
            if (f.dropped) {
                name.getStyleClass().add("col-dropped");
            } else if (f.isNew()) {
                name.getStyleClass().add("col-new");
            }
            fkGrid.addRow(r++, name, cols, ref, refCols, onDel, onUpd, action);
        }
        if (design.foreignKeys.isEmpty()) {
            Label none = new Label("Nenhuma chave estrangeira saindo desta tabela.");
            none.getStyleClass().add("muted");
            fkGrid.add(none, 0, 1, 7, 1);
        }
    }

    private void fillRefColumns(MenuButton mb, ForeignKey f) {
        mb.getItems().clear();
        Runnable updateText = () -> mb.setText(f.refColumns.isEmpty() ? "(escolha)" : String.join(", ", f.refColumns));
        if (f.refTable != null) {
            for (String[] col : catalog.columnsOf(f.refSchema == null ? defaultSchema : f.refSchema, f.refTable)) {
                String n = col[0];
                CheckMenuItem item = new CheckMenuItem(n + "  (" + col[1] + ")");
                item.setMnemonicParsing(false);
                item.setSelected(f.refColumns.contains(n));
                item.selectedProperty().addListener((o, a, b) -> {
                    if (b && !f.refColumns.contains(n)) {
                        f.refColumns.add(n);
                    } else if (!b) {
                        f.refColumns.remove(n);
                    }
                    updateText.run();
                    changed();
                });
                mb.getItems().add(item);
            }
        }
        updateText.run();
    }

    private void addForeignKey() {
        ForeignKey f = new ForeignKey();
        f.name = DdlBuilder.normalizeName("fk_" + baseName() + "_" + (design.foreignKeys.size() + 1), casing);
        design.foreignKeys.add(f);
        rebuildForeignKeys();
        changed();
    }

    // =====================================================================
    // Plano / mensagens
    // =====================================================================

    private void regenerate() {
        if (design == null) {
            return;
        }
        plan = DdlBuilder.build(design, casing, defaultSchema, existingTables);
        if (!plan.errors().isEmpty()) {
            sqlPreview.setText("-- Corrija os erros para ver o SQL:\n-- " + String.join("\n-- ", plan.errors()));
        } else if (!plan.hasChanges()) {
            sqlPreview.setText("-- Nenhuma alteração.");
        } else {
            sqlPreview.setText(plan.script());
        }
        sqlTab.setText(plan.hasChanges() ? "SQL (" + plan.statements().size() + ")" : "SQL");
        messages.getChildren().clear();
        for (String e : plan.errors()) {
            messages.getChildren().add(msg("✕ " + e, "text-error"));
        }
        for (String w : plan.warnings()) {
            messages.getChildren().add(msg("⚠ " + w, "text-warn"));
        }
        if (messages.getChildren().isEmpty()) {
            messages.getChildren().add(msg(plan.hasChanges()
                    ? plan.statements().size() + " comando(s) prontos. Veja a aba SQL antes de aplicar."
                    : design.isNew() ? "Dê um nome à tabela e defina as colunas." : "Nenhuma alteração ainda.", "muted"));
        }
        boolean ok = plan.errors().isEmpty() && plan.hasChanges();
        applyBtn.setDisable(!ok || session.isReadOnly());
        migrationBtn.setDisable(!ok);
        copyBtn.setDisable(!ok);
        discardBtn.setDisable(!plan.hasChanges() && plan.errors().isEmpty());
        if (session.isReadOnly()) {
            applyBtn.setTooltip(new Tooltip("Banco aberto somente leitura: use \"Salvar como migração\" ou \"Copiar SQL\""));
        }
        String title = design.isNew() ? (design.name == null || design.name.isBlank() ? "Nova tabela" : "Nova: " + design.name)
                : "Estrutura: " + design.originalName;
        setText(title + (plan.hasChanges() ? " •" : ""));
        getStyleClass().remove("tab-pending");
        if (plan.hasChanges()) {
            getStyleClass().add("tab-pending");
        }
    }

    private static Label msg(String text, String style) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add(style);
        return l;
    }

    // =====================================================================
    // Aplicar
    // =====================================================================

    private void apply() {
        regenerate();
        if (!plan.errors().isEmpty() || !plan.hasChanges()) {
            return;
        }
        ButtonType run = new ButtonType("Aplicar", ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", run, Fx.CANCEL);
        a.initOwner(window());
        a.setTitle("H2 Studio");
        a.setHeaderText(design.isNew() ? "Criar a tabela " + DdlBuilder.normalizeName(design.name, casing) + "?"
                : "Aplicar " + plan.statements().size() + " alteração(ões) em " + design.originalName + "?");
        TextArea ta = new TextArea(plan.script());
        ta.setEditable(false);
        ta.getStyleClass().add("mono");
        ta.setPrefSize(720, 260);
        VBox box = new VBox(8);
        if (!plan.warnings().isEmpty()) {
            box.getChildren().add(Fx.wrapLabel("⚠ " + String.join("\n⚠ ", plan.warnings())));
        }
        box.getChildren().add(ta);
        String note = "No H2 cada comando de estrutura é gravado na hora: se um falhar, os anteriores já terão sido aplicados.";
        if (AppPreferences.autoBackup()) {
            note += " Um backup do banco será feito antes.";
        }
        if (flywayTable != null) {
            note += "\nEste banco usa Flyway: esta alteração não ficará registrada nas migrações do sistema.";
        }
        box.getChildren().add(Fx.wrapLabel(note));
        a.getDialogPane().setContent(box);
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        if (a.showAndWait().filter(b -> b == run).isEmpty()) {
            return;
        }
        List<String> stmts = plan.statements();
        String oldName = design.originalName;
        String newName = DdlBuilder.normalizeName(design.name, casing);
        if (!design.isNew() && design.name.trim().equals(design.originalName)) {
            newName = design.originalName;
        }
        final String target = newName;
        setDisabledAll(true);
        messages.getChildren().setAll(msg("Aplicando…", "muted"));
        SqlHistory history = SqlHistory.forDatabase(session.config());
        Fx.then(session.submit(c -> {
            String backup = null;
            if (AppPreferences.autoBackup()) {
                backup = BackupService.backup(c, session.config(), AppPreferences.backupsToKeep()).getFileName().toString();
                session.setBackedUp(true);
            }
            int done = 0;
            for (String st : stmts) {
                try (java.sql.Statement s = c.createStatement()) {
                    s.execute(st);
                    history.add(st, true, "designer de tabela");
                    done++;
                } catch (SQLException ex) {
                    history.add(st, false, SqlErrors.friendly(ex));
                    return new Object[]{done, ex, backup};
                }
            }
            return new Object[]{done, null, backup};
        }), r -> {
            int done = (Integer) r[0];
            SQLException err = (SQLException) r[1];
            String backup = (String) r[2];
            if (done > 0) {
                // árvore e abas abertas já se atualizam enquanto a mensagem é exibida
                boolean nowExistsWithNewName = err == null || design.isNew() || renamedFirst();
                onApplied.accept(oldName, nowExistsWithNewName ? target : oldName);
            }
            if (err == null) {
                Fx.info(window(), "Estrutura aplicada", stmts.size() + " comando(s) executado(s) com sucesso."
                        + (backup != null ? "\nBackup feito antes: " + backup : ""));
            } else {
                Fx.error(window(), "O comando " + (done + 1) + " de " + stmts.size() + " falhou",
                        SqlErrors.friendly(err) + "\n\nComando:\n" + stmts.get(done)
                        + (done > 0 ? "\n\nOs " + done + " comando(s) anteriores JÁ foram aplicados." : "\n\nNada foi alterado.")
                        + (backup != null ? "\nBackup feito antes: " + backup : ""), err);
            }
            if (err != null && done == 0) {
                // nada mudou: mantém o que o usuário digitou para corrigir e tentar de novo
                setDisabledAll(false);
                regenerate();
                return;
            }
            // algo foi aplicado: relê a estrutura real (com o nome novo, se criou/renomeou)
            boolean nowExistsWithNewName = err == null || design.isNew() || renamedFirst();
            design.originalName = nowExistsWithNewName ? target : oldName;
            reload();
        }, e -> {
            setDisabledAll(false);
            Fx.error(window(), "Falha ao aplicar", Fx.shortMessage(e), e);
            regenerate();
        });
    }

    /** O primeiro comando do plano é o RENAME da tabela. */
    private boolean renamedFirst() {
        return !plan.statements().isEmpty() && plan.statements().get(0).contains(" RENAME TO ")
                && plan.statements().get(0).startsWith("ALTER TABLE") && !plan.statements().get(0).contains(" ALTER COLUMN ");
    }

    // =====================================================================
    // Migração Flyway
    // =====================================================================

    private void saveMigration() {
        regenerate();
        if (!plan.errors().isEmpty() || !plan.hasChanges()) {
            return;
        }
        String version = FlywayInfo.nextVersion(flywayMigrations);
        String table = DdlBuilder.normalizeName(design.name, casing);
        String desc = (design.isNew() ? "criar tabela " : "alterar tabela ") + table.toLowerCase(Locale.ROOT);
        FileChooser fc = new FileChooser();
        fc.setTitle("Salvar migração do Flyway");
        fc.setInitialFileName(FlywayInfo.fileName(version, desc));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Script SQL (*.sql)", "*.sql"));
        String lastDir = AppPreferences.get("flyway.pasta", null);
        if (lastDir != null && new File(lastDir).isDirectory()) {
            fc.setInitialDirectory(new File(lastDir));
        }
        File f = fc.showSaveDialog(window());
        if (f == null) {
            return;
        }
        AppPreferences.set("flyway.pasta", f.getParent());
        String header = "-- " + desc + "\n-- Gerado pelo H2 Studio em "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + "\n\n";
        try {
            Files.writeString(f.toPath(), header + plan.script(), StandardCharsets.UTF_8);
            Fx.info(window(), "Migração salva", f.getAbsolutePath()
                    + "\n\nColoque o arquivo em src/main/resources/db/migration do sistema (ou na pasta configurada no Flyway)."
                    + " O Flyway vai aplicar quando o sistema iniciar."
                    + (flywayTable != null ? "\n\nNão use \"Aplicar no banco\" para esta mesma alteração, senão a migração falha." : ""));
        } catch (Exception e) {
            Fx.error(window(), "Não foi possível salvar", e.getMessage(), e);
        }
    }

    // =====================================================================
    // Utilidades de layout
    // =====================================================================

    private static ColumnConstraints cc(double w) {
        ColumnConstraints c = new ColumnConstraints(w);
        c.setMinWidth(w);
        return c;
    }

    private static ColumnConstraints grow(double min) {
        ColumnConstraints c = new ColumnConstraints();
        c.setMinWidth(min);
        c.setHgrow(Priority.ALWAYS);
        return c;
    }

    private static void header(GridPane g, String[] titles) {
        for (int i = 0; i < titles.length; i++) {
            Label l = new Label(titles[i]);
            l.getStyleClass().add("grid-header");
            g.add(l, i, 0);
        }
    }

    private static Node center(Node n) {
        GridPane.setHalignment(n, HPos.CENTER);
        return n;
    }

    private static TextField numField(Integer v) {
        TextField t = new TextField(v == null ? "" : String.valueOf(v));
        t.setTextFormatter(new javafx.scene.control.TextFormatter<String>(ch -> ch.getControlNewText().matches("\\d{0,9}") ? ch : null));
        return t;
    }

    private static Integer parseInt(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Window window() {
        return getTabPane() == null || getTabPane().getScene() == null ? null : getTabPane().getScene().getWindow();
    }

    // =====================================================================
    // SessionTab
    // =====================================================================

    @Override
    public boolean hasPendingChanges() {
        return design != null && plan.hasChanges();
    }

    @Override
    public String pendingDescription() {
        return getText().replace(" •", "") + ": alterações de estrutura não aplicadas";
    }

    @Override
    public void refresh() {
        if (!hasPendingChanges()) {
            reload();
        }
    }

    /** Usado pelos testes. */
    TableDesign design() {
        return design;
    }

    DdlBuilder.Plan plan() {
        return plan;
    }
}
