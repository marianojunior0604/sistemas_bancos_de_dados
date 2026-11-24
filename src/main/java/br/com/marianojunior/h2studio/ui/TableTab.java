package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.Meta;
import br.com.marianojunior.h2studio.core.Meta.ColumnInfo;
import br.com.marianojunior.h2studio.core.Meta.ForeignKeyInfo;
import br.com.marianojunior.h2studio.core.Meta.IndexInfo;
import br.com.marianojunior.h2studio.core.MetadataService;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Aba de uma tabela: Dados, Estrutura, Índices e chaves, DDL.
 * Nos bancos que não são H2 (por enquanto) a grade de dados é só de consulta.
 */
public class TableTab extends Tab implements SessionTab {

    private final DbSession session;
    private final Meta.TableInfo table;
    /** null nos bancos que ainda não têm a grade de dados. */
    private final DataGrid dataGrid;
    private final TableView<ColumnInfo> structure = new TableView<>();
    private final TableView<IndexInfo> indexes = new TableView<>();
    private final TableView<ForeignKeyInfo> fks = new TableView<>();
    private final TextArea ddl = new TextArea();
    private boolean metaLoaded;
    private Runnable onAlterStructure = () -> { };
    private boolean ddlLoaded;

    public TableTab(DbSession session, Meta.TableInfo table) {
        this.session = session;
        this.table = table;
        setText(table.name());
        setGraphic(table.view() ? Icons.view() : Icons.table());
        String fullName = table.schema() == null || table.schema().isEmpty() ? table.name() : table.schema() + "." + table.name();
        setTooltip(new javafx.scene.control.Tooltip(session.displayName() + " › " + fullName));

        dataGrid = new DataGrid(session, table);
        if (dataGrid != null) {
            dataGrid.setOnPendingChanged(() -> {
                boolean pending = dataGrid.hasPendingChanges();
                setText(table.name() + (pending ? " •" : ""));
                getStyleClass().remove("tab-pending");
                if (pending) {
                    getStyleClass().add("tab-pending");
                }
            });
        }
        // fechar a aba com alterações não salvas: pergunta antes
        setOnCloseRequest(e -> {
            if (dataGrid != null && dataGrid.hasPendingChanges()) {
                e.consume();
                TabPane owner = getTabPane();
                dataGrid.confirmLeave(() -> {
                    if (owner != null) {
                        owner.getTabs().remove(this);
                    }
                });
            }
        });

        // ---------- cabeçalho ----------
        Label title = new Label(fullName);
        title.getStyleClass().add("table-title");
        HBox header = new HBox(10, table.view() ? Icons.view() : Icons.table(), title);
        header.setAlignment(Pos.CENTER_LEFT);
        if (table.view()) {
            header.getChildren().add(badge("VIEW", "badge-view"));
        }
        if (session.isReadOnly()) {
            header.getChildren().add(badge("SOMENTE LEITURA", "badge-ro"));
        }
        if (session.isCopy()) {
            header.getChildren().add(badge("CÓPIA", "badge-copy"));
        }
        if (table.remarks() != null && !table.remarks().isBlank()) {
            Label rem = new Label("— " + table.remarks());
            rem.getStyleClass().add("muted");
            header.getChildren().add(rem);
        }
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label db = new Label(session.displayName() + (session.isH2() ? "" : " · " + session.engineLabel()));
        db.getStyleClass().add("muted");
        header.getChildren().addAll(sp, db);
        header.setPadding(new Insets(8, 10, 8, 10));
        header.getStyleClass().add("table-header");

        // ---------- sub-abas ----------
        Tab tData = new Tab("Dados", dataGrid);
        Button alter = new Button("Alterar estrutura…");
        alter.setGraphic(Icons.design());
        alter.setOnAction(e -> onAlterStructure.run());
        alter.setDisable(table.view() || session.isReadOnly());
        HBox structBar = new HBox(alter);
        if (!session.isH2()) {
            structBar.getChildren().clear(); // o designer de estrutura ainda é só do H2
        }
        structBar.setPadding(new Insets(0, 0, 6, 0));
        BorderPane structPane = new BorderPane(structure);
        structPane.setTop(structBar);
        structPane.setPadding(new Insets(8));
        Tab tStruct = new Tab("Estrutura", structPane);
        Tab tIdx = new Tab("Índices e chaves", buildKeysPane());
        Tab tDdl = new Tab("DDL", buildDdlPane());
        TabPane inner = new TabPane(tData, tStruct, tIdx, tDdl);
        inner.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        inner.getStyleClass().add("inner-tabs");
        inner.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b == tDdl) {
                loadDdl();
            }
        });

        buildStructureTable();

        BorderPane root = new BorderPane(inner);
        root.setTop(header);
        setContent(root);

        loadMetadata();
    }

    @Override
    public DbSession session() {
        return session;
    }

    public Meta.TableInfo table() {
        return table;
    }

    @Override
    public void refresh() {
        metaLoaded = false;
        ddlLoaded = false;
        if (dataGrid != null) {
            dataGrid.resetColumns(); // a estrutura pode ter mudado (tipos, colunas)
        }
        loadMetadata();
        if (dataGrid != null) {
            dataGrid.reload();
        }
    }

    public void focusSearch() {
        if (dataGrid != null) {
            dataGrid.focusSearch();
        }
    }

    public void setOnAlterStructure(Runnable r) {
        onAlterStructure = r == null ? () -> { } : r;
    }

    /** Ctrl+S */
    public void save() {
        if (dataGrid != null) {
            dataGrid.save(null);
        }
    }

    @Override
    public boolean hasPendingChanges() {
        return dataGrid != null && dataGrid.hasPendingChanges();
    }

    @Override
    public String pendingDescription() {
        return table.name() + ": " + dataGrid.pendingCount() + " linha(s) alterada(s) não salva(s)";
    }

    public int pendingCount() {
        return dataGrid == null ? 0 : dataGrid.pendingCount();
    }

    private static Label badge(String text, String style) {
        Label l = new Label(text);
        l.getStyleClass().addAll("badge", style);
        return l;
    }

    private static Node padded(Node n) {
        BorderPane p = new BorderPane(n);
        p.setPadding(new Insets(8));
        return p;
    }

    private void loadMetadata() {
        String s = table.schema();
        String t = table.name();
        Fx.then(session.submit(c -> new Object[]{
            MetadataService.columns(c, s, t),
            MetadataService.indexes(c, s, t),
            MetadataService.foreignKeys(c, s, t)}),
                r -> {
                    @SuppressWarnings("unchecked") List<ColumnInfo> cols = (List<ColumnInfo>) r[0];
                    @SuppressWarnings("unchecked") List<IndexInfo> idx = (List<IndexInfo>) r[1];
                    @SuppressWarnings("unchecked") List<ForeignKeyInfo> fk = (List<ForeignKeyInfo>) r[2];
                    structure.getItems().setAll(cols);
                    indexes.getItems().setAll(idx);
                    fks.getItems().setAll(fk);
                    metaLoaded = true;
                    if (dataGrid != null) {
                        dataGrid.loadIfNeeded(cols);
                    }
                },
                e -> {
                    structure.setPlaceholder(new Label("Erro ao ler a estrutura: " + Fx.shortMessage(e)));
                    if (dataGrid != null) {
                        dataGrid.loadIfNeeded(List.of());
                    }
                });
    }

    private void loadDdl() {
        if (ddlLoaded) {
            return;
        }
        ddlLoaded = true;
        ddl.setText("-- carregando…");
        Fx.then(session.submit(c -> br.com.marianojunior.h2studio.core.DdlReader.ddl(session.type(), c, table)),
                ddl::setText,
                e -> ddl.setText("-- Erro ao gerar DDL: " + Fx.shortMessage(e)));
    }

    // ---------- Estrutura ----------

    private void buildStructureTable() {
        structure.setPlaceholder(new Label("Carregando…"));
        structure.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ColumnInfo, ColumnInfo> icon = new TableColumn<>("");
        icon.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        icon.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(ColumnInfo item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null
                        : item.primaryKey() ? Icons.key() : item.fkTarget() != null ? Icons.link() : null);
            }
        });
        icon.setMinWidth(30);
        icon.setMaxWidth(30);
        icon.setSortable(false);

        structure.getColumns().add(icon);
        structure.getColumns().add(col("Nº", ColumnInfo::position, 40, 50));
        structure.getColumns().add(col("Coluna", ColumnInfo::name, 140, -1));
        structure.getColumns().add(col("Tipo", ColumnInfo::typeDisplay, 150, -1));
        structure.getColumns().add(col("Nulo", c -> c.nullable() ? "sim" : "NÃO", 50, 70));
        structure.getColumns().add(col("Padrão", ColumnInfo::defaultValue, 120, -1));
        structure.getColumns().add(col("Auto incr.", c -> c.autoIncrement() ? "sim" : "", 85, 100));
        structure.getColumns().add(col("Auto/calc.", c -> c.generated() ? "calculada" : "", 75, 95));
        structure.getColumns().add(col("Chave", c -> c.primaryKey() ? "PRIMÁRIA" : c.fkTarget() != null ? "→ " + c.fkTarget() : "", 120, -1));
        structure.getColumns().add(col("Comentário", ColumnInfo::remarks, 120, -1));
    }

    private Node buildKeysPane() {
        indexes.setPlaceholder(new Label("Nenhum índice."));
        indexes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        indexes.getColumns().add(col("Nome", IndexInfo::name, 160, -1));
        indexes.getColumns().add(col("Colunas", IndexInfo::columns, 160, -1));
        indexes.getColumns().add(col("Único", i -> i.unique() ? "sim" : "", 60, 80));
        indexes.getColumns().add(col("Tipo", i -> i.primaryKey() ? "CHAVE PRIMÁRIA" : i.unique() ? "ÚNICO" : "ÍNDICE", 130, 170));

        fks.setPlaceholder(new Label("Nenhuma chave estrangeira ligada a esta tabela."));
        fks.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        fks.getColumns().add(col("Nome", ForeignKeyInfo::name, 150, -1));
        fks.getColumns().add(col("Direção", f -> f.incoming() ? "← usada por" : "→ referencia", 115, 130));
        fks.getColumns().add(col("Colunas desta tabela", ForeignKeyInfo::columns, 130, -1));
        fks.getColumns().add(col("Outra tabela", f -> f.refSchema() == null || f.refSchema().isEmpty()
                ? f.refTable() : f.refSchema() + "." + f.refTable(), 140, -1));
        fks.getColumns().add(col("Colunas da outra", ForeignKeyInfo::refColumns, 130, -1));
        fks.getColumns().add(col("ON UPDATE", ForeignKeyInfo::onUpdate, 90, 110));
        fks.getColumns().add(col("ON DELETE", ForeignKeyInfo::onDelete, 90, 110));

        Label l1 = new Label("Índices");
        l1.getStyleClass().add("section-title");
        Label l2 = new Label("Chaves estrangeiras");
        l2.getStyleClass().add("section-title");
        VBox box = new VBox(6, l1, indexes, l2, fks);
        VBox.setVgrow(indexes, Priority.ALWAYS);
        VBox.setVgrow(fks, Priority.ALWAYS);
        box.setPadding(new Insets(8));
        return box;
    }

    private Node buildDdlPane() {
        ddl.setEditable(false);
        ddl.getStyleClass().addAll("mono", "ddl");
        Button copy = new Button("Copiar DDL");
        copy.setOnAction(e -> Fx.copy(ddl.getText()));
        HBox bar = new HBox(copy);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(0, 0, 6, 0));
        BorderPane p = new BorderPane(ddl);
        p.setTop(bar);
        p.setPadding(new Insets(8));
        return p;
    }

    private static <S, T> TableColumn<S, T> col(String title, Function<S, T> getter, double min, double max) {
        TableColumn<S, T> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(getter.apply(cd.getValue())));
        c.setMinWidth(min);
        if (max > 0) {
            c.setMaxWidth(max);
        }
        return c;
    }
}
