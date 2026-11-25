package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.AppPreferences;
import br.com.marianojunior.h2studio.core.BackupService;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.Meta;
import br.com.marianojunior.h2studio.core.SqlUtil;
import br.com.marianojunior.h2studio.core.TableDataService;
import br.com.marianojunior.h2studio.core.TableDataService.PageRequest;
import br.com.marianojunior.h2studio.core.TableDataService.PageResult;
import br.com.marianojunior.h2studio.core.TableEditor;
import br.com.marianojunior.h2studio.core.ValueFormatter;
import br.com.marianojunior.h2studio.core.ValueParser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Grade de dados de uma tabela.
 * <p>
 * Leitura: paginação, ordenação pelo cabeçalho (no banco), busca rápida e filtro SQL.
 * Edição: alterações ficam pendentes (destacadas) até "Salvar", que grava tudo numa
 * transação, com backup automático antes do primeiro salvamento da sessão.
 */
public class DataGrid extends BorderPane {

    private final DbSession session;
    private final Meta.TableInfo table;
    private List<Meta.ColumnInfo> columnsMeta = List.of();

    private final TableView<GridRow> grid = new TableView<>();
    private final TextField quickSearch = new TextField();
    private final TextField where = new TextField();
    private final Label banner = new Label();
    private final Label pageLabel = new Label();
    private final Label infoLabel = new Label();
    private final ComboBox<Integer> pageSize = new ComboBox<>(FXCollections.observableArrayList(50, 100, 250, 500, 1000));
    private final Button first = new Button("«");
    private final Button prev = new Button("‹");
    private final Button next = new Button("›");
    private final Button last = new Button("»");
    private final ProgressIndicator progress = new ProgressIndicator();

    // barra de edição
    private final HBox editBar = new HBox(6);
    private final Button saveButton = new Button("Salvar");
    private final Button discardButton = new Button("Descartar");
    private final Label pendingLabel = new Label();
    private final Label readOnlyLabel = new Label();
    private final Button importCsvButton = new Button("Importar…");

    private long offset;
    private long total;
    private String orderBy;
    private boolean ascending = true;
    private String appliedWhere = "";
    private String appliedSearch = "";
    private List<String> currentColumnNames = List.of();
    private Meta.ColumnInfo[] gridMeta = new Meta.ColumnInfo[0];
    private boolean loading;
    private boolean reloadPending;
    private boolean loadedOnce;
    private boolean suppressSortEvents;
    private Integer lastPageSize = 100;

    // edição
    private boolean editable;
    private List<String> keyColumns = List.of();
    private boolean useRowId;
    /** Caractere digitado que iniciou a edição (vai para o editor). */
    private String typedStart;
    /** Campo de edição ativo (recebe teclas digitadas antes de ganhar o foco). */
    private TextField activeEditor;
    private Path lastBackup;
    private Runnable onPendingChanged = () -> { };

    public DataGrid(DbSession session, Meta.TableInfo table) {
        this.session = session;
        this.table = table;
        getStyleClass().add("data-grid");

        // ---------- barra de filtros ----------
        quickSearch.setPromptText("Buscar em todas as colunas…");
        quickSearch.setPrefWidth(240);
        where.setPromptText(switch (session.type()) {
            case H2 -> "Filtro SQL (WHERE), ex.: ATIVO = TRUE AND NOME LIKE 'MAR%'";
            case MYSQL, MARIADB -> "Filtro SQL (WHERE), ex.: ativo = 1 AND nome LIKE 'mar%' AND data >= '2025-01-01'";
            case POSTGRESQL -> "Filtro SQL (WHERE), ex.: ativo AND nome ILIKE 'mar%' AND data >= '2025-01-01'";
            case FIREBIRD, FIREBIRD_25 -> "Filtro SQL (WHERE), ex.: NOME CONTAINING 'mar' AND DATA >= '01.01.2025'";
            case SQLSERVER -> "Filtro SQL (WHERE), ex.: ativo = 1 AND nome LIKE 'mar%' AND data >= '2025-01-01'";
            case SQLITE -> "Filtro SQL (WHERE), ex.: nome LIKE 'mar%' AND data >= '2025-01-01'";
        });
        where.getStyleClass().add("mono");
        HBox.setHgrow(where, Priority.ALWAYS);
        Button apply = new Button("Aplicar");
        apply.setOnAction(e -> confirmLeave(this::applyFilters));
        Button clear = new Button("Limpar");
        clear.setOnAction(e -> confirmLeave(() -> {
            quickSearch.clear();
            where.clear();
            applyFilters();
        }));
        Button refresh = new Button();
        refresh.setGraphic(Icons.refresh());
        refresh.setTooltip(new Tooltip("Recarregar (F5)"));
        refresh.setOnAction(e -> reload());
        quickSearch.setOnAction(e -> confirmLeave(this::applyFilters));
        where.setOnAction(e -> confirmLeave(this::applyFilters));
        Button export = new Button("Exportar…");
        export.setTooltip(new Tooltip("Excel ou CSV, com o filtro e a ordenação atuais"));
        export.setOnAction(e -> exportData());
        HBox filters = new HBox(6, quickSearch, where, apply, clear, refresh, export);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.setPadding(new Insets(6, 8, 6, 8));
        filters.getStyleClass().add("filter-bar");

        buildEditBar();

        banner.getStyleClass().add("banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setWrapText(true);
        banner.setVisible(false);
        banner.setManaged(false);
        banner.setOnMouseClicked(e -> hideBanner());

        setTop(new VBox(filters, editBar, banner));

        // ---------- grade ----------
        grid.setPlaceholder(new Label("Nenhum registro."));
        grid.getSelectionModel().setCellSelectionEnabled(true);
        grid.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        grid.setTableMenuButtonVisible(true);
        grid.setSortPolicy(tv -> {
            onSortChanged();
            return true; // a ordenação é feita pelo banco
        });
        grid.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        grid.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        grid.setRowFactory(tv -> new StyledRow());
        ContextMenu menu = new ContextMenu();
        menu.setOnShowing(e -> menu.getItems().setAll(buildContextMenuItems()));
        grid.setContextMenu(menu);

        progress.setMaxSize(48, 48);
        progress.setVisible(false);
        setCenter(new StackPane(grid, progress));

        // ---------- paginação ----------
        pageSize.setValue(100);
        pageSize.valueProperty().addListener((o, a, b) -> {
            if (suppressSortEvents || Objects.equals(b, lastPageSize)) {
                return;
            }
            boolean ok = confirmLeave(() -> {
                lastPageSize = b;
                offset = 0;
                doReload();
            });
            if (!ok) {
                suppressSortEvents = true;
                pageSize.setValue(lastPageSize);
                suppressSortEvents = false;
            }
        });
        first.setOnAction(e -> confirmLeave(() -> goTo(0)));
        prev.setOnAction(e -> confirmLeave(() -> goTo(Math.max(0, offset - pageSize.getValue()))));
        next.setOnAction(e -> confirmLeave(() -> goTo(offset + pageSize.getValue())));
        last.setOnAction(e -> confirmLeave(() -> goTo(lastOffset())));
        for (Button b : List.of(first, prev, next, last)) {
            b.getStyleClass().add("pager-button");
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        infoLabel.getStyleClass().add("muted");
        HBox pager = new HBox(6, first, prev, pageLabel, next, last, new Label("  Linhas por página:"), pageSize,
                spacer, infoLabel);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.setPadding(new Insets(5, 8, 5, 8));
        pager.getStyleClass().add("pager-bar");
        setBottom(pager);
        updatePager();
        updatePendingUi();
    }

    // =====================================================================
    // Configuração
    // =====================================================================

    /** Chamado pela aba quando a estrutura da tabela foi lida. */
    public void loadIfNeeded(List<Meta.ColumnInfo> columns) {
        this.columnsMeta = columns == null ? List.of() : columns;
        configureEditing();
        if (!loadedOnce) {
            loadedOnce = true;
            doReload();
        }
    }

    /** Força recriar as colunas na próxima leitura (depois de alterar a estrutura). */
    public void resetColumns() {
        if (!hasPendingChanges()) {
            currentColumnNames = List.of();
        }
    }

    public void setOnPendingChanged(Runnable r) {
        onPendingChanged = r == null ? () -> { } : r;
    }

    private void configureEditing() {
        keyColumns = columnsMeta.stream().filter(Meta.ColumnInfo::primaryKey).map(Meta.ColumnInfo::name).toList();
        String reason = null;
        if (table.view()) {
            reason = "View: somente consulta.";
        } else if (session.isCopy()) {
            reason = "Cópia do banco (o original estava em uso): somente consulta.";
        } else if (session.isReadOnly()) {
            reason = "Banco aberto em modo somente leitura.";
        } else if (columnsMeta.isEmpty()) {
            reason = "Não foi possível ler a estrutura da tabela.";
        } else if (!session.isH2() && keyColumns.isEmpty()) {
            // sem _ROWID_ fora do H2: sem chave primária não dá para dizer qual linha alterar
            reason = "Tabela sem chave primária: somente consulta (não há como identificar cada linha com segurança).";
        }
        editable = reason == null;
        useRowId = editable && keyColumns.isEmpty() && session.isH2();
        grid.setEditable(editable);
        for (Node n : editBar.getChildren()) {
            boolean show = editable ? n != readOnlyLabel : n == readOnlyLabel;
            n.setVisible(show);
            n.setManaged(show || n instanceof Region && !(n instanceof javafx.scene.control.Control));
        }
        readOnlyLabel.setText(reason == null ? "" : reason);
        if (useRowId) {
            readOnlyLabel.setText("Tabela sem chave primária: as linhas são identificadas pelo _ROWID_ interno do H2.");
            readOnlyLabel.setVisible(true);
            readOnlyLabel.setManaged(true);
        }
    }

    private void buildEditBar() {
        Button add = new Button("Nova linha");
        add.setTooltip(new Tooltip("Inserir nova linha (Insert)"));
        add.setOnAction(e -> addRow());
        Button dup = new Button("Duplicar");
        dup.setTooltip(new Tooltip("Duplicar as linhas selecionadas (Ctrl+D)"));
        dup.setOnAction(e -> duplicateRows());
        Button del = new Button("Excluir");
        del.setTooltip(new Tooltip("Marcar as linhas selecionadas para exclusão (Delete)"));
        del.setOnAction(e -> deleteRows());
        Button importCsv = importCsvButton;
        importCsv.setTooltip(new Tooltip("Adicionar ou substituir linhas a partir de um CSV ou de uma planilha .xlsx"));
        importCsv.setOnAction(e -> confirmLeave(this::importCsv));
        Button setNull = new Button("NULL");
        setNull.setTooltip(new Tooltip("Definir NULL nas células selecionadas (Shift+Delete)"));
        setNull.setOnAction(e -> setNullSelected());
        saveButton.getStyleClass().add("primary");
        saveButton.setTooltip(new Tooltip("Gravar todas as alterações no banco (Ctrl+S)"));
        saveButton.setOnAction(e -> save(null));
        discardButton.setTooltip(new Tooltip("Desfazer todas as alterações ainda não salvas"));
        discardButton.setOnAction(e -> {
            if (askYesNo("Descartar " + pendingCount() + " alteração(ões) não salva(s)?")) {
                discardAll();
            }
        });
        pendingLabel.getStyleClass().add("pending-label");
        readOnlyLabel.getStyleClass().add("muted");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        editBar.getChildren().addAll(add, dup, del, new Separator(javafx.geometry.Orientation.VERTICAL), setNull, importCsv,
                readOnlyLabel, sp, pendingLabel, discardButton, saveButton);
        editBar.setAlignment(Pos.CENTER_LEFT);
        editBar.setPadding(new Insets(0, 8, 6, 8));
        editBar.getStyleClass().add("edit-bar");
    }

    private void exportData() {
        boolean filtered = !appliedWhere.isBlank() || !appliedSearch.isBlank();
        var req = new PageRequest(table.schema(), table.name(), appliedWhere, appliedSearch, columnsMeta, orderBy, ascending, 0, 0)
                .withType(session.type());
        var all = new PageRequest(table.schema(), table.name(), null, null, null, orderBy, ascending, 0, 0)
                .withType(session.type());
        ExportAction.show(window(), session, (table.schema().isEmpty() ? "" : table.schema() + ".") + table.name()
                + (filtered ? String.format(" (%,d linhas no filtro atual)", total).replace(',', '.') : ""),
                table.name(), filtered ? TableDataService.exportQuery(req) : null, TableDataService.exportQuery(all));
    }

    private void importCsv() {
        new ImportDialog(window(), session, table.schema(), table).showAndWait().ifPresent(r -> {
            showOk(String.format("Importadas %,d linha(s)", r.inserted()).replace(',', '.')
                    + (r.deleted() > 0 ? String.format(" (%,d apagadas antes)", r.deleted()).replace(',', '.') : "") + ".");
            doReload();
        });
    }

    public void focusSearch() {
        quickSearch.requestFocus();
    }

    // =====================================================================
    // Leitura
    // =====================================================================

    private void applyFilters() {
        appliedWhere = where.getText() == null ? "" : where.getText().trim();
        appliedSearch = quickSearch.getText() == null ? "" : quickSearch.getText().trim();
        offset = 0;
        doReload();
    }

    private void goTo(long newOffset) {
        offset = Math.max(0, newOffset);
        doReload();
    }

    private long lastOffset() {
        int ps = pageSize.getValue();
        return total == 0 ? 0 : ((total - 1) / ps) * ps;
    }

    /** Recarrega a página (pergunta antes se houver alterações não salvas). */
    public void reload() {
        confirmLeave(this::doReload);
    }

    private void doReload() {
        if (loading) {
            reloadPending = true;
            return;
        }
        loading = true;
        setBusy(true);
        PageRequest req = new PageRequest(table.schema(), table.name(), appliedWhere, appliedSearch, columnsMeta,
                orderBy, ascending, offset, pageSize.getValue(), useRowId, session.type());
        Fx.then(session.submit(c -> TableDataService.fetch(c, req)), this::show, err -> {
            loading = false;
            setBusy(false);
            showError("Erro ao consultar: " + Fx.shortMessage(err));
            runPending();
        });
    }

    private void show(PageResult r) {
        List<String> names = r.columns().stream().map(TableDataService.Column::name).toList();
        if (!names.equals(currentColumnNames)) {
            currentColumnNames = names;
            buildColumns(r.columns());
        }
        total = r.total();
        if (offset > 0 && offset >= total) {
            offset = 0;
            loading = false;
            reloadPending = false;
            doReload();
            return;
        }
        List<GridRow> rows = new ArrayList<>(r.rows().size());
        for (int i = 0; i < r.rows().size(); i++) {
            rows.add(GridRow.loaded(r.rows().get(i), r.rowIds() == null ? null : r.rowIds().get(i)));
        }
        grid.getItems().setAll(rows);
        infoLabel.setText(String.format("%,d registro%s · %d ms", total, total == 1 ? "" : "s", r.elapsedMs())
                .replace(',', '.'));
        infoLabel.setTooltip(new Tooltip(r.sql()));
        updatePager();
        updatePendingUi();
        loading = false;
        setBusy(false);
        runPending();
    }

    private void runPending() {
        if (reloadPending) {
            reloadPending = false;
            doReload();
        }
    }

    private void buildColumns(List<TableDataService.Column> cols) {
        grid.getColumns().clear();
        gridMeta = new Meta.ColumnInfo[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            String n = cols.get(i).name();
            Meta.ColumnInfo m = columnsMeta.stream().filter(c -> c.name().equals(n)).findFirst()
                    .orElseGet(() -> columnsMeta.stream().filter(c -> c.name().equalsIgnoreCase(n)).findFirst().orElse(null));
            gridMeta[i] = m;
        }
        if (editable && !useRowId && !keyColumns.stream().allMatch(currentColumnNames::contains)) {
            editable = false; // não conseguimos identificar as linhas com segurança
            grid.setEditable(false);
            configureEditingReadOnly("Chave primária não encontrada no resultado: somente consulta.");
        }

        TableColumn<GridRow, Object> num = new TableColumn<>("#");
        num.setSortable(false);
        num.setReorderable(false);
        num.setEditable(false);
        num.getStyleClass().add("row-number-column");
        num.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                GridRow row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    setText(null);
                } else if (row.isNew()) {
                    setText("+");
                } else if (row.isDeleted()) {
                    setText("×");
                } else {
                    setText(String.valueOf(offset + getIndex() + 1) + (row.isPending() ? " ✎" : ""));
                }
            }
        });
        num.setPrefWidth(Math.max(52, String.valueOf(offset + 1000).length() * 9 + 26));
        grid.getColumns().add(num);

        for (int i = 0; i < cols.size(); i++) {
            final int idx = i;
            TableDataService.Column col = cols.get(i);
            Meta.ColumnInfo meta = gridMeta[i];
            TableColumn<GridRow, Object> tc = new TableColumn<>();
            tc.setUserData(col.name());
            tc.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue().values[idx]));
            boolean numeric = SqlUtil.isNumeric(col.jdbcType());
            tc.setCellFactory(c -> new EditCell(idx, numeric));
            tc.setEditable(editable && meta != null && !meta.generated());
            Label header = new Label(col.name());
            if (meta != null && meta.primaryKey()) {
                header.setGraphic(Icons.key());
            } else if (meta != null && meta.isForeignKey()) {
                header.setGraphic(Icons.link());
            }
            StringBuilder tip = new StringBuilder(meta != null ? meta.typeDisplay() : col.typeName());
            if (meta != null) {
                if (meta.primaryKey()) {
                    tip.append("\nChave primária");
                }
                if (meta.isForeignKey()) {
                    tip.append("\nReferencia ").append(meta.fkTarget()).append(" (F4 para escolher)");
                }
                if (!meta.nullable()) {
                    tip.append("\nNOT NULL");
                }
                if (meta.autoIncrement()) {
                    tip.append("\nAutomático (identidade)");
                }
                if (meta.generated()) {
                    tip.append("\nColuna calculada: não editável");
                }
                if (meta.remarks() != null && !meta.remarks().isBlank()) {
                    tip.append("\n").append(meta.remarks());
                }
            }
            header.setTooltip(new Tooltip(tip.toString()));
            tc.setGraphic(header);
            tc.setPrefWidth(prefWidth(col, meta));
            grid.getColumns().add(tc);
        }
    }

    private void configureEditingReadOnly(String reason) {
        for (Node n : editBar.getChildren()) {
            n.setVisible(n == readOnlyLabel);
            n.setManaged(n == readOnlyLabel);
        }
        readOnlyLabel.setText(reason);
        readOnlyLabel.setVisible(true);
        readOnlyLabel.setManaged(true);
    }

    private static double prefWidth(TableDataService.Column col, Meta.ColumnInfo meta) {
        int t = col.jdbcType();
        double w;
        if (SqlUtil.isNumeric(t)) {
            w = 90;
        } else if (t == java.sql.Types.DATE) {
            w = 95;
        } else if (t == java.sql.Types.TIMESTAMP || t == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
            w = 150;
        } else if (t == java.sql.Types.BOOLEAN || t == java.sql.Types.BIT) {
            w = 70;
        } else if (meta != null && meta.size() > 0 && meta.size() < 30) {
            w = Math.max(70, meta.size() * 8 + 20);
        } else {
            w = 180;
        }
        return Math.max(w, col.name().length() * 8 + 34);
    }

    private void onSortChanged() {
        if (grid.getColumns().isEmpty() || suppressSortEvents) {
            return;
        }
        String newOrder = null;
        boolean newAsc = true;
        if (!grid.getSortOrder().isEmpty()) {
            TableColumn<GridRow, ?> c = grid.getSortOrder().get(0);
            newOrder = (String) c.getUserData();
            newAsc = c.getSortType() == TableColumn.SortType.ASCENDING;
        }
        if (Objects.equals(newOrder, orderBy) && newAsc == ascending) {
            return;
        }
        final String o = newOrder;
        final boolean asc = newAsc;
        boolean ok = confirmLeave(() -> {
            orderBy = o;
            ascending = asc;
            offset = 0;
            doReload();
        });
        if (!ok) {
            restoreSortIndicator();
        }
    }

    /** Volta a seta de ordenação do cabeçalho para a ordenação atual. */
    private void restoreSortIndicator() {
        suppressSortEvents = true;
        try {
            grid.getSortOrder().clear();
            if (orderBy != null) {
                for (TableColumn<GridRow, ?> c : grid.getColumns()) {
                    if (orderBy.equals(c.getUserData())) {
                        c.setSortType(ascending ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
                        grid.getSortOrder().add(c);
                    }
                }
            }
        } finally {
            suppressSortEvents = false;
        }
    }

    private void updatePager() {
        int ps = pageSize.getValue();
        long pages = Math.max(1, (total + ps - 1) / ps);
        long page = offset / ps + 1;
        pageLabel.setText(String.format("Página %d de %d", page, pages));
        first.setDisable(offset == 0);
        prev.setDisable(offset == 0);
        next.setDisable(offset + ps >= total);
        last.setDisable(offset + ps >= total);
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
        grid.setDisable(busy);
        saveButton.setDisable(busy || pendingCount() == 0);
    }

    // =====================================================================
    // Mensagens
    // =====================================================================

    private void showBanner(String msg, String style) {
        banner.getStyleClass().removeAll("banner-error", "banner-ok", "banner-info");
        banner.getStyleClass().add(style);
        banner.setText(msg + "   (clique para fechar)");
        banner.setVisible(true);
        banner.setManaged(true);
    }

    private void showError(String msg) {
        showBanner(msg, "banner-error");
    }

    private void showOk(String msg) {
        showBanner(msg, "banner-ok");
    }

    private void showInfo(String msg) {
        showBanner(msg, "banner-info");
    }

    private void hideBanner() {
        banner.setVisible(false);
        banner.setManaged(false);
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
        return getScene() == null ? null : getScene().getWindow();
    }

    // =====================================================================
    // Pendências
    // =====================================================================

    public int pendingCount() {
        int n = 0;
        for (GridRow r : grid.getItems()) {
            if (r.isPending()) {
                n++;
            }
        }
        return n;
    }

    public boolean hasPendingChanges() {
        return pendingCount() > 0;
    }

    private void updatePendingUi() {
        int n = pendingCount();
        pendingLabel.setText(n == 0 ? "" : n + (n == 1 ? " linha alterada" : " linhas alteradas") + " (não salvas)");
        saveButton.setText(n == 0 ? "Salvar" : "Salvar (" + n + ")");
        saveButton.setDisable(n == 0 || loading);
        discardButton.setDisable(n == 0);
        onPendingChanged.run();
    }

    private void afterChange() {
        grid.refresh();
        updatePendingUi();
    }

    /**
     * Antes de sair da página atual (paginar, filtrar, ordenar, fechar...):
     * se houver alterações não salvas, pergunta Salvar / Descartar / Cancelar.
     *
     * @return false se o usuário cancelou
     */
    public boolean confirmLeave(Runnable proceed) {
        commitActiveEdit();
        int n = pendingCount();
        if (n == 0) {
            proceed.run();
            return true;
        }
        ButtonType save = new ButtonType("Salvar", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Descartar", ButtonBar.ButtonData.NO);
        Alert a = new Alert(Alert.AlertType.WARNING, "", save, discard, Fx.CANCEL);
        a.initOwner(window());
        a.setTitle("H2 Studio");
        a.setHeaderText(n + (n == 1 ? " linha alterada" : " linhas alteradas") + " em " + table.name()
                + " ainda não " + (n == 1 ? "foi salva" : "foram salvas") + ".");
        a.getDialogPane().setContent(Fx.wrapLabel("Deseja salvar antes de continuar?"));
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() == Fx.CANCEL) {
            return false;
        }
        if (r.get() == discard) {
            discardAll();
            proceed.run();
        } else {
            save(proceed);
        }
        return true;
    }

    private void discardAll() {
        grid.getItems().removeIf(GridRow::isNew);
        for (GridRow r : grid.getItems()) {
            r.revert();
        }
        hideBanner();
        afterChange();
    }

    // =====================================================================
    // Ações de edição
    // =====================================================================

    private Meta.ColumnInfo meta(int idx) {
        return idx >= 0 && idx < gridMeta.length ? gridMeta[idx] : null;
    }

    /** Índice da coluna de dados (sem a coluna "#"), ou -1. */
    private int dataIndex(TableColumn<GridRow, ?> c) {
        int i = grid.getColumns().indexOf(c);
        return i <= 0 ? -1 : i - 1;
    }

    private TableColumn<GridRow, ?> columnOf(int dataIdx) {
        return grid.getColumns().get(dataIdx + 1);
    }

    /** Motivo pelo qual a célula não pode ser editada, ou null se pode. */
    private String cannotEdit(GridRow row, int idx) {
        if (!editable) {
            return readOnlyLabel.getText();
        }
        Meta.ColumnInfo m = meta(idx);
        if (row == null || m == null) {
            return "Coluna desconhecida.";
        }
        if (row.isDeleted()) {
            return "Linha marcada para exclusão (Ctrl+Z desfaz).";
        }
        if (m.generated()) {
            return "A coluna " + m.name() + " é calculada pelo banco.";
        }
        if (m.autoIncrement() && !row.isNew()) {
            return "A coluna " + m.name() + " é gerada automaticamente (identidade).";
        }
        return null;
    }

    private GridRow focusedRow() {
        int r = grid.getFocusModel().getFocusedIndex();
        return r >= 0 && r < grid.getItems().size() ? grid.getItems().get(r) : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int focusedDataIndex() {
        TablePosition p = grid.getFocusModel().getFocusedCell();
        return p == null || p.getTableColumn() == null ? -1 : dataIndex(p.getTableColumn());
    }

    private void editCell(int rowIndex, int dataIdx) {
        editCell(rowIndex, dataIdx, false);
    }

    private void editCell(int rowIndex, int dataIdx, boolean scroll) {
        if (rowIndex < 0 || rowIndex >= grid.getItems().size() || dataIdx < 0 || dataIdx >= gridMeta.length) {
            return;
        }
        GridRow row = grid.getItems().get(rowIndex);
        String why = cannotEdit(row, dataIdx);
        if (why != null) {
            typedStart = null;
            showInfo(why);
            return;
        }
        grid.getSelectionModel().clearAndSelect(rowIndex, columnOf(dataIdx));
        grid.getFocusModel().focus(rowIndex, columnOf(dataIdx));
        if (scroll) {
            // rola primeiro e só edita depois que a grade se redesenhar
            grid.scrollTo(rowIndex);
            Platform.runLater(() -> grid.edit(rowIndex, columnOf(dataIdx)));
        } else {
            grid.edit(rowIndex, columnOf(dataIdx));
        }
    }

    private void addRow() {
        if (!editable) {
            return;
        }
        commitActiveEdit();
        GridRow r = GridRow.created(gridMeta.length);
        grid.getItems().add(r);
        int ri = grid.getItems().size() - 1;
        afterChange();
        // começa editando a primeira coluna que precisa ser preenchida
        int col = 0;
        for (int i = 0; i < gridMeta.length; i++) {
            Meta.ColumnInfo m = gridMeta[i];
            if (m != null && !m.generated() && !m.autoIncrement()) {
                col = i;
                break;
            }
        }
        final int c = col;
        Platform.runLater(() -> editCell(ri, c, true));
    }

    private void duplicateRows() {
        if (!editable) {
            return;
        }
        commitActiveEdit();
        List<GridRow> sel = selectedRows();
        boolean skippedLob = false;
        for (GridRow src : sel) {
            if (src.isDeleted()) {
                continue;
            }
            GridRow r = GridRow.created(gridMeta.length);
            for (int i = 0; i < gridMeta.length; i++) {
                Meta.ColumnInfo m = gridMeta[i];
                Object v = src.values[i];
                if (m == null || m.generated() || m.autoIncrement() || v == TableEditor.DEFAULT) {
                    continue;
                }
                if (v instanceof TableDataService.LongText lt) {
                    if (lt.length() > lt.preview().length()) {
                        skippedLob = true;
                        continue;
                    }
                    v = lt.preview();
                }
                if (v instanceof TableDataService.BinaryValue) {
                    skippedLob = true;
                    continue;
                }
                r.set(i, v);
            }
            grid.getItems().add(r);
        }
        afterChange();
        if (!sel.isEmpty()) {
            grid.scrollTo(grid.getItems().size() - 1);
            showInfo("Linha(s) duplicada(s) no fim da página. Ajuste os campos únicos (como a chave) antes de salvar."
                    + (skippedLob ? " Textos longos e binários não foram copiados." : ""));
        }
    }

    private void deleteRows() {
        if (!editable) {
            return;
        }
        commitActiveEdit();
        List<GridRow> sel = selectedRows();
        for (GridRow r : sel) {
            if (r.isNew()) {
                grid.getItems().remove(r);
            } else {
                r.markDeleted();
            }
        }
        grid.getSelectionModel().clearSelection();
        afterChange();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setNullSelected() {
        if (!editable) {
            return;
        }
        commitActiveEdit();
        List<String> refused = new ArrayList<>();
        for (TablePosition p : new ArrayList<>(grid.getSelectionModel().getSelectedCells())) {
            int idx = dataIndex(p.getTableColumn());
            if (idx < 0) {
                continue;
            }
            GridRow row = grid.getItems().get(p.getRow());
            Meta.ColumnInfo m = meta(idx);
            String why = cannotEdit(row, idx);
            if (why != null) {
                refused.add(why);
            } else if (!m.nullable()) {
                refused.add("A coluna " + m.name() + " não aceita NULL.");
            } else {
                row.set(idx, null);
            }
        }
        afterChange();
        if (!refused.isEmpty()) {
            showInfo(String.join(" ", new java.util.LinkedHashSet<>(refused)));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void restoreSelected() {
        commitActiveEdit();
        for (TablePosition p : new ArrayList<>(grid.getSelectionModel().getSelectedCells())) {
            int idx = dataIndex(p.getTableColumn());
            GridRow row = grid.getItems().get(p.getRow());
            if (row.isDeleted()) {
                row.undelete();
            } else if (idx >= 0) {
                row.restore(idx);
            }
        }
        afterChange();
    }

    private void revertRows() {
        commitActiveEdit();
        for (GridRow r : selectedRows()) {
            if (r.isNew()) {
                grid.getItems().remove(r);
            } else {
                r.revert();
            }
        }
        afterChange();
    }

    private List<GridRow> selectedRows() {
        TreeSet<Integer> idx = new TreeSet<>();
        for (var p : grid.getSelectionModel().getSelectedCells()) {
            idx.add(p.getRow());
        }
        if (idx.isEmpty() && grid.getFocusModel().getFocusedIndex() >= 0) {
            idx.add(grid.getFocusModel().getFocusedIndex());
        }
        List<GridRow> out = new ArrayList<>();
        for (int i : idx) {
            if (i < grid.getItems().size()) {
                out.add(grid.getItems().get(i));
            }
        }
        return out;
    }

    /** Aplica um valor numa célula e atualiza a tela. */
    private void applyValue(GridRow row, int idx, Object value) {
        row.set(idx, value);
        afterChange();
    }

    /** Encerra a edição em andamento (confirma o valor se for válido). */
    private void commitActiveEdit() {
        if (grid.getEditingCell() != null && grid.lookupAll(".table-cell") != null) {
            for (Node n : grid.lookupAll(".edit-cell")) {
                if (n instanceof EditCell ec && ec.isEditing()) {
                    ec.commitFromEditor(false);
                }
            }
        }
    }

    // =====================================================================
    // Editores especiais
    // =====================================================================

    private TableEditor.Key keyOf(GridRow row) {
        if (useRowId) {
            return TableEditor.Key.ofRowId(row.rowId);
        }
        Map<String, Object> k = new LinkedHashMap<>();
        for (String col : keyColumns) {
            k.put(col, row.original[currentColumnNames.indexOf(col)]);
        }
        return TableEditor.Key.ofColumns(k);
    }

    private boolean isLongTextColumn(Meta.ColumnInfo m) {
        int t = m.jdbcType();
        // PostgreSQL "text", varchar sem tamanho e enum informam tamanho "infinito" (2147483647), mas
        // costumam guardar textos curtos: ficam no editor de uma linha (texto com quebra abre a janela)
        boolean unbounded = !session.isH2() && m.size() >= Integer.MAX_VALUE - 1;
        return t == java.sql.Types.CLOB || t == java.sql.Types.NCLOB || t == java.sql.Types.LONGVARCHAR
                || (ValueParser.isTextual(t) && m.size() > 1000 && !unbounded);
    }

    /** Edita texto longo/multilinha numa janela própria (carrega o valor completo do banco). */
    private void editLongText(GridRow row, int idx, boolean editableText) {
        Meta.ColumnInfo m = meta(idx);
        Object v = row.values[idx];
        String title = (m != null ? m.name() : "Valor") + (row.isNew() ? " — nova linha" : "");
        if (v instanceof TableDataService.LongText lt && lt.length() > lt.preview().length() && !row.isNew()
                && !row.changed.get(idx) && !session.isH2() && keyColumns.isEmpty()) {
            showInfo("Tabela sem chave primária: mostrando só os primeiros "
                    + TableDataService.CLOB_PREVIEW + " caracteres.");
            openTextDialog(row, idx, title, lt.preview(), editableText);
            return;
        }
        if (v instanceof TableDataService.LongText lt && lt.length() > lt.preview().length() && !row.isNew()
                && !row.changed.get(idx)) {
            setBusy(true);
            Fx.then(session.submit(c -> TableEditor.readFull(session.type(), c, table.schema(), table.name(), keyOf(row), m.name())),
                    full -> {
                        setBusy(false);
                        openTextDialog(row, idx, title, full == null ? null : String.valueOf(full), editableText);
                    },
                    e -> {
                        setBusy(false);
                        showError("Não foi possível ler o texto completo: " + Fx.shortMessage(e));
                    });
            return;
        }
        String text = v == null || v == TableEditor.DEFAULT ? ""
                : v instanceof TableDataService.LongText lt ? lt.preview() : ValueFormatter.format(v);
        openTextDialog(row, idx, title, text, editableText);
    }

    private void openTextDialog(GridRow row, int idx, String title, String text, boolean editableText) {
        Meta.ColumnInfo m = meta(idx);
        int max = m != null && SqlUtil.hasLength(m.jdbcType()) ? m.size() : 0;
        boolean canEdit = editableText && cannotEdit(row, idx) == null;
        Optional<String> r = new TextEditDialog(window(), title, text, canEdit, m == null || m.nullable(), max).showAndWait();
        if (r.isEmpty() || !canEdit) {
            return;
        }
        if (r.get() == TextEditDialog.NULL_CHOSEN) {
            applyValue(row, idx, null);
        } else if (!r.get().equals(text) || row.isNew()) {
            applyValue(row, idx, r.get());
        }
    }

    private void openLookup(GridRow row, int idx) {
        Meta.ColumnInfo m = meta(idx);
        if (m == null || !m.isForeignKey()) {
            return;
        }
        String why = cannotEdit(row, idx);
        if (why != null) {
            showInfo(why);
            return;
        }
        Object cur = row.values[idx] == TableEditor.DEFAULT ? null : row.values[idx];
        Optional<Object> r = new LookupDialog(window(), session, m, cur).showAndWait();
        r.ifPresent(v -> applyValue(row, idx, v == LookupDialog.NULL_CHOSEN ? null : v));
    }

    private void loadBinaryFromFile(GridRow row, int idx) {
        String why = cannotEdit(row, idx);
        if (why != null) {
            showInfo(why);
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Carregar arquivo em " + meta(idx).name());
        File f = fc.showOpenDialog(window());
        if (f == null) {
            return;
        }
        try {
            long size = Files.size(f.toPath());
            if (size > 100L * 1024 * 1024) {
                showError("Arquivo grande demais (" + ValueFormatter.humanSize(size) + "). Limite: 100 MB.");
                return;
            }
            Meta.ColumnInfo m = meta(idx);
            if (SqlUtil.hasLength(m.jdbcType()) && m.size() > 0 && size > m.size()) {
                showError("O arquivo tem " + size + " bytes; a coluna " + m.name() + " aceita no máximo " + m.size() + ".");
                return;
            }
            applyValue(row, idx, Files.readAllBytes(f.toPath()));
            showInfo("Arquivo " + f.getName() + " (" + ValueFormatter.humanSize(size) + ") carregado. Clique em Salvar para gravar.");
        } catch (Exception e) {
            showError("Não foi possível ler o arquivo: " + e.getMessage());
        }
    }

    private void saveBinaryToFile(GridRow row, int idx) {
        Object v = row.values[idx];
        if (v == null || v == TableEditor.DEFAULT) {
            showInfo("A célula está vazia.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Salvar conteúdo de " + meta(idx).name());
        fc.setInitialFileName(table.name() + "_" + meta(idx).name() + ".bin");
        File f = fc.showSaveDialog(window());
        if (f == null) {
            return;
        }
        if (v instanceof byte[] b) {
            writeFile(f, b);
            return;
        }
        if (row.isNew()) {
            return;
        }
        Fx.then(session.submit(c -> TableEditor.readFull(session.type(), c, table.schema(), table.name(), keyOf(row), meta(idx).name())),
                full -> writeFile(f, full instanceof byte[] b ? b
                        : String.valueOf(full).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                e -> showError("Não foi possível ler o valor: " + Fx.shortMessage(e)));
    }

    private void writeFile(File f, byte[] data) {
        try {
            Files.write(f.toPath(), data);
            showOk("Salvo em " + f.getAbsolutePath() + " (" + ValueFormatter.humanSize(data.length) + ").");
        } catch (Exception e) {
            showError("Não foi possível gravar o arquivo: " + e.getMessage());
        }
    }

    // =====================================================================
    // Salvar
    // =====================================================================

    /** Grava as alterações pendentes; {@code then} roda depois de salvar com sucesso. */
    public void save(Runnable then) {
        if (!editable) {
            return;
        }
        commitActiveEdit();
        List<GridRow> pending = grid.getItems().stream().filter(GridRow::isPending).toList();
        if (pending.isEmpty()) {
            if (then != null) {
                then.run();
            }
            return;
        }
        // validação das linhas novas antes de ir ao banco
        for (GridRow r : pending) {
            if (!r.isNew()) {
                continue;
            }
            for (int i = 0; i < gridMeta.length; i++) {
                Meta.ColumnInfo m = gridMeta[i];
                Object v = r.values[i];
                if (m != null && !m.nullable() && !m.hasAutomaticValue() && (v == null || v == TableEditor.DEFAULT)) {
                    r.error = true;
                    afterChange();
                    selectRow(r, i);
                    showError("Nova linha: preencha a coluna " + m.name() + " (obrigatória). Nada foi salvo.");
                    return;
                }
            }
        }
        List<TableEditor.Change> changes = new ArrayList<>();
        for (GridRow r : pending) {
            switch (r.state) {
                case DELETED -> changes.add(new TableEditor.Delete(keyOf(r), r));
                case MODIFIED -> {
                    Map<String, Object> vals = new LinkedHashMap<>();
                    r.changed.stream().forEach(i -> vals.put(currentColumnNames.get(i), r.values[i]));
                    changes.add(new TableEditor.Update(keyOf(r), vals, r));
                }
                case NEW -> {
                    Map<String, Object> vals = new LinkedHashMap<>();
                    for (int i = 0; i < gridMeta.length; i++) {
                        if (gridMeta[i] != null && !gridMeta[i].generated()) {
                            vals.put(currentColumnNames.get(i), r.values[i]);
                        }
                    }
                    changes.add(new TableEditor.Insert(vals, r));
                }
                default -> { }
            }
        }
        if (!session.isH2()) {
            // bancos de servidor: sem backup automático; avisa uma vez por conexão
            if (!session.isBackedUp()) {
                if (!askYesNo("As alterações vão direto para o banco " + session.type().label() + " ("
                        + session.config().location() + "), numa única transação.\n"
                        + "O H2 Studio não faz backup automático deste tipo de banco. Salvar?")) {
                    return;
                }
                session.setBackedUp(true); // não pergunta de novo nesta conexão
            }
            applyChanges(changes, then);
        } else if (AppPreferences.autoBackup() && !session.isBackedUp()) {
            setBusy(true);
            showInfo("Fazendo backup do banco antes de salvar…");
            Fx.then(session.submit(c -> BackupService.backup(c, session.config(), AppPreferences.backupsToKeep())),
                    path -> {
                        session.setBackedUp(true);
                        lastBackup = path;
                        applyChanges(changes, then);
                    },
                    err -> {
                        setBusy(false);
                        if (askYesNo("Não foi possível fazer o backup automático (" + Fx.shortMessage(err)
                                + "). Salvar mesmo assim?")) {
                            session.setBackedUp(true);
                            applyChanges(changes, then);
                        } else {
                            hideBanner();
                        }
                    });
        } else {
            applyChanges(changes, then);
        }
    }

    private void applyChanges(List<TableEditor.Change> changes, Runnable then) {
        setBusy(true);
        Fx.then(session.submit(c -> TableEditor.apply(session.type(), c, table.schema(), table.name(), changes)), result -> {
            setBusy(false);
            String msg = "Salvo: " + result.summary() + ".";
            if (lastBackup != null) {
                msg += " Backup feito antes: " + lastBackup.getFileName() + " (Ferramentas › Backups).";
                lastBackup = null;
            }
            showOk(msg);
            // recarrega para mostrar valores gerados pelo banco (identidade, padrões...)
            grid.getItems().removeIf(GridRow::isNew);
            for (GridRow r : grid.getItems()) {
                r.revert();
            }
            updatePendingUi();
            doReload();
            if (then != null) {
                then.run();
            }
        }, err -> {
            setBusy(false);
            lastBackup = null;
            if (err instanceof TableEditor.SaveException se) {
                if (se.ref() instanceof GridRow r) {
                    r.error = true;
                    selectRow(r, -1);
                }
                showError("Nada foi salvo. " + se.getMessage());
            } else {
                showError("Nada foi salvo. " + Fx.shortMessage(err));
            }
            afterChange();
        });
    }

    private void selectRow(GridRow r, int dataIdx) {
        int i = grid.getItems().indexOf(r);
        if (i >= 0) {
            grid.scrollTo(i);
            grid.getSelectionModel().clearSelection();
            if (dataIdx >= 0) {
                grid.getSelectionModel().select(i, columnOf(dataIdx));
                grid.getFocusModel().focus(i, columnOf(dataIdx));
            } else {
                grid.getSelectionModel().select(i, columnOf(0));
            }
        }
    }

    // =====================================================================
    // Teclado e menu
    // =====================================================================

    private static final KeyCombination COPY = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination DUP = new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination UNDO = new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination SET_NULL = new KeyCodeCombination(KeyCode.DELETE, KeyCombination.SHIFT_DOWN);

    private void onKeyPressed(KeyEvent e) {
        if (grid.getEditingCell() != null) {
            // o editor ainda não recebeu o foco: entrega as teclas de controle a ele
            TextField ed = activeEditor;
            if (ed != null && !ed.isFocused() && e.getTarget() != ed) {
                switch (e.getCode()) {
                    case ENTER, TAB, ESCAPE, F4 -> {
                        javafx.event.Event.fireEvent(ed, e.copyFor(ed, ed));
                        e.consume();
                    }
                    case BACK_SPACE -> {
                        ed.deletePreviousChar();
                        e.consume();
                    }
                    default -> { }
                }
            }
            return;
        }
        if (COPY.match(e)) {
            copySelection();
            e.consume();
            return;
        }
        if (!editable) {
            if (e.getCode() == KeyCode.ENTER) {
                showFullValue();
                e.consume();
            }
            return;
        }
        if (SET_NULL.match(e)) {
            setNullSelected();
        } else if (e.getCode() == KeyCode.DELETE && !e.isShiftDown()) {
            deleteRows();
        } else if (e.getCode() == KeyCode.INSERT) {
            addRow();
        } else if (DUP.match(e)) {
            duplicateRows();
        } else if (UNDO.match(e)) {
            restoreSelected();
        } else if (e.getCode() == KeyCode.F2 || (e.getCode() == KeyCode.ENTER && !e.isShortcutDown())) {
            editCell(grid.getFocusModel().getFocusedIndex(), focusedDataIndex());
        } else if (e.getCode() == KeyCode.F4) {
            GridRow r = focusedRow();
            if (r != null) {
                openLookup(r, focusedDataIndex());
            }
        } else {
            return;
        }
        e.consume();
    }

    /** Começar a digitar numa célula inicia a edição com o caractere digitado. */
    private void onKeyTyped(KeyEvent e) {
        if (!editable || e.isShortcutDown() || e.isAltDown()) {
            return;
        }
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || Character.isISOControl(ch.charAt(0))) {
            return;
        }
        if (grid.getEditingCell() != null) {
            // digitação rápida: caracteres que chegam antes do editor ganhar o foco
            TextField ed = activeEditor;
            if (ed != null && !ed.isFocused() && e.getTarget() != ed) {
                ed.replaceSelection(ch);
                e.consume();
            }
            return;
        }
        int row = grid.getFocusModel().getFocusedIndex();
        int col = focusedDataIndex();
        if (row < 0 || col < 0) {
            return;
        }
        Meta.ColumnInfo m = meta(col);
        if (m == null || ValueParser.isBoolean(m.jdbcType()) || isLongTextColumn(m) || m.jdbcType() == java.sql.Types.BLOB) {
            return;
        }
        if (cannotEdit(grid.getItems().get(row), col) != null) {
            return;
        }
        typedStart = ch;
        editCell(row, col);
        e.consume();
    }

    private List<MenuItem> buildContextMenuItems() {
        List<MenuItem> items = new ArrayList<>();
        GridRow row = focusedRow();
        int idx = focusedDataIndex();
        Meta.ColumnInfo m = meta(idx);
        if (editable && row != null && m != null) {
            String why = cannotEdit(row, idx);
            MenuItem edit = new MenuItem("Editar célula");
            edit.setAccelerator(new KeyCodeCombination(KeyCode.F2));
            edit.setDisable(why != null);
            edit.setOnAction(e -> editCell(grid.getItems().indexOf(row), idx));
            items.add(edit);
            if (m.isForeignKey()) {
                MenuItem lookup = new MenuItem("Escolher em " + m.fkTable() + "…");
                lookup.setAccelerator(new KeyCodeCombination(KeyCode.F4));
                lookup.setDisable(why != null);
                lookup.setOnAction(e -> openLookup(row, idx));
                items.add(lookup);
            }
            if (ValueParser.isTextual(m.jdbcType())) {
                MenuItem longText = new MenuItem("Editar texto completo…");
                longText.setDisable(why != null);
                longText.setOnAction(e -> editLongText(row, idx, true));
                items.add(longText);
            }
            if (ValueParser.isBinary(m.jdbcType())) {
                MenuItem load = new MenuItem("Carregar arquivo…");
                load.setDisable(why != null);
                load.setOnAction(e -> loadBinaryFromFile(row, idx));
                MenuItem saveF = new MenuItem("Salvar em arquivo…");
                saveF.setOnAction(e -> saveBinaryToFile(row, idx));
                items.add(load);
                items.add(saveF);
            }
            MenuItem nul = new MenuItem("Definir NULL");
            nul.setAccelerator(SET_NULL);
            nul.setDisable(why != null || !m.nullable());
            nul.setOnAction(e -> setNullSelected());
            MenuItem restore = new MenuItem("Restaurar valor original");
            restore.setAccelerator(UNDO);
            restore.setDisable(!row.isPending());
            restore.setOnAction(e -> restoreSelected());
            items.add(nul);
            items.add(restore);
            items.add(new SeparatorMenuItem());
            MenuItem add = new MenuItem("Nova linha");
            add.setAccelerator(new KeyCodeCombination(KeyCode.INSERT));
            add.setOnAction(e -> addRow());
            MenuItem dup = new MenuItem("Duplicar linha(s)");
            dup.setAccelerator(DUP);
            dup.setOnAction(e -> duplicateRows());
            MenuItem del = new MenuItem("Excluir linha(s)");
            del.setAccelerator(new KeyCodeCombination(KeyCode.DELETE));
            del.setOnAction(e -> deleteRows());
            MenuItem revert = new MenuItem("Desfazer alterações da(s) linha(s)");
            revert.setDisable(selectedRows().stream().noneMatch(GridRow::isPending));
            revert.setOnAction(e -> revertRows());
            items.add(add);
            items.add(dup);
            items.add(del);
            items.add(revert);
            items.add(new SeparatorMenuItem());
        }
        MenuItem copyValue = new MenuItem("Copiar");
        copyValue.setAccelerator(COPY);
        copyValue.setOnAction(e -> copySelection());
        MenuItem copyRows = new MenuItem("Copiar linhas inteiras (com cabeçalho)");
        copyRows.setOnAction(e -> copyRows());
        MenuItem view = new MenuItem("Ver valor completo…");
        view.setOnAction(e -> showFullValue());
        MenuItem filterEq = new MenuItem("Filtrar por este valor");
        filterEq.setOnAction(e -> confirmLeave(this::filterByCell));
        items.add(copyValue);
        items.add(copyRows);
        items.add(new SeparatorMenuItem());
        items.add(view);
        items.add(filterEq);
        return items;
    }

    // =====================================================================
    // Cópia e visualização
    // =====================================================================

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void copySelection() {
        List<TablePosition> cells = new ArrayList<>(grid.getSelectionModel().getSelectedCells());
        if (cells.isEmpty()) {
            return;
        }
        cells.sort((a, b) -> a.getRow() != b.getRow() ? Integer.compare(a.getRow(), b.getRow())
                : Integer.compare(grid.getVisibleLeafIndex(a.getTableColumn()), grid.getVisibleLeafIndex(b.getTableColumn())));
        StringBuilder sb = new StringBuilder();
        int row = cells.get(0).getRow();
        boolean firstInRow = true;
        for (TablePosition p : cells) {
            if (p.getRow() != row) {
                sb.append('\n');
                row = p.getRow();
                firstInRow = true;
            }
            if (!firstInRow) {
                sb.append('\t');
            }
            int idx = dataIndex(p.getTableColumn());
            sb.append(idx < 0 ? String.valueOf(offset + p.getRow() + 1) : plain(grid.getItems().get(p.getRow()).values[idx]));
            firstInRow = false;
        }
        Fx.copy(sb.toString());
    }

    private void copyRows() {
        List<GridRow> rows = selectedRows();
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(String.join("\t", currentColumnNames)).append('\n');
        for (GridRow r : rows) {
            for (int i = 0; i < r.values.length; i++) {
                sb.append(i > 0 ? "\t" : "").append(plain(r.values[i]));
            }
            sb.append('\n');
        }
        Fx.copy(sb.toString());
    }

    private static String plain(Object v) {
        return v == null || v == TableEditor.DEFAULT ? "" : ValueFormatter.format(v);
    }

    private void showFullValue() {
        GridRow row = focusedRow();
        int idx = focusedDataIndex();
        if (row == null || idx < 0) {
            return;
        }
        editLongText(row, idx, false);
    }

    private void filterByCell() {
        GridRow row = focusedRow();
        int idx = focusedDataIndex();
        if (row == null || idx < 0) {
            return;
        }
        String colName = currentColumnNames.get(idx);
        Object v = row.isNew() ? null : row.original[idx];
        var type = session.type();
        String q = type == br.com.marianojunior.h2studio.core.DbType.H2 ? SqlUtil.quote(colName) : type.quote(colName);
        // SQLite e SQL Server não aceitam DATE '...'/TIMESTAMP '...': lá a data vai como texto
        boolean typedLiterals = switch (type) {
            case SQLITE, SQLSERVER -> false;
            default -> true;
        };
        String cond;
        if (v == null) {
            cond = q + " IS NULL";
        } else if (v instanceof Boolean b && type == br.com.marianojunior.h2studio.core.DbType.SQLSERVER) {
            cond = q + " = " + (b ? 1 : 0);
        } else if (v instanceof Number || v instanceof Boolean) {
            cond = q + " = " + (v instanceof java.math.BigDecimal bd ? bd.toPlainString() : v);
        } else if (v instanceof TableDataService.BinaryValue || v instanceof TableDataService.LongText) {
            return;
        } else {
            String d = v instanceof java.sql.Date || v instanceof java.time.LocalDate ? String.valueOf(v) : null;
            String ts = v instanceof java.sql.Timestamp t ? String.valueOf(t)
                    : v instanceof java.time.LocalDateTime ldt ? ldt.toString().replace('T', ' ') : null;
            String lit = d != null ? (typedLiterals ? "DATE '" + d + "'" : "'" + d + "'")
                    : ts != null ? (typedLiterals ? "TIMESTAMP '" + ts + "'" : "'" + ts + "'")
                    : "'" + String.valueOf(v).replace("'", "''") + "'";
            cond = q + " = " + lit;
        }
        String cur = where.getText() == null ? "" : where.getText().trim();
        where.setText(cur.isEmpty() ? cond : cur + " AND " + cond);
        applyFilters();
    }

    // =====================================================================
    // Linha e célula
    // =====================================================================

    /** Linha com cor conforme o estado: nova, excluída, com erro. */
    private static final class StyledRow extends TableRow<GridRow> {
        @Override
        protected void updateItem(GridRow item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("row-new", "row-deleted", "row-error");
            if (!empty && item != null) {
                if (item.error) {
                    getStyleClass().add("row-error");
                }
                if (item.isNew()) {
                    getStyleClass().add("row-new");
                } else if (item.isDeleted()) {
                    getStyleClass().add("row-deleted");
                }
            }
        }
    }

    /** Célula com exibição formatada e editor conforme o tipo da coluna. */
    private final class EditCell extends TableCell<GridRow, Object> {
        private final int idx;
        private TextField field;
        private GridRow editingRow;
        private boolean finishing;

        EditCell(int idx, boolean numeric) {
            this.idx = idx;
            getStyleClass().add("edit-cell");
            if (numeric) {
                getStyleClass().add("cell-number");
            }
        }

        private GridRow row() {
            return getTableRow() == null ? null : getTableRow().getItem();
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("cell-null", "cell-binary", "cell-modified", "cell-default", "cell-required");
            if (isEditing()) {
                return;
            }
            setGraphic(null);
            GridRow row = row();
            if (empty || row == null) {
                setText(null);
                return;
            }
            Meta.ColumnInfo m = meta(idx);
            if (item == TableEditor.DEFAULT) {
                if (m != null && (m.autoIncrement() || m.generated())) {
                    setText("(auto)");
                    getStyleClass().add("cell-default");
                } else if (m != null && m.defaultValue() != null) {
                    setText("(padrão)");
                    getStyleClass().add("cell-default");
                } else if (m != null && !m.nullable()) {
                    setText("(obrigatório)");
                    getStyleClass().add("cell-required");
                } else {
                    setText(ValueFormatter.NULL_TEXT);
                    getStyleClass().add("cell-null");
                }
                return;
            }
            String text = ValueFormatter.format(item);
            int nl = text.indexOf('\n');
            setText(nl >= 0 ? text.substring(0, nl) + " ↵…" : text);
            if (item == null) {
                getStyleClass().add("cell-null");
            } else if (item instanceof TableDataService.BinaryValue || item instanceof byte[]) {
                getStyleClass().add("cell-binary");
            }
            if (row.changed.get(idx) && !row.isDeleted()) {
                getStyleClass().add("cell-modified");
            }
        }

        @Override
        public void startEdit() {
            GridRow row = row();
            if (row == null || !editable) {
                return;
            }
            String why = cannotEdit(row, idx);
            if (why != null) {
                showInfo(why);
                return;
            }
            Meta.ColumnInfo m = meta(idx);
            Object v = row.values[idx];
            // tipos que não cabem num campo de uma linha: janela própria
            if (isLongTextColumn(m) || (v instanceof String s && s.contains("\n"))
                    || v instanceof TableDataService.LongText) {
                typedStart = null;
                Platform.runLater(() -> editLongText(row, idx, true));
                return;
            }
            if (m.jdbcType() == java.sql.Types.BLOB || v instanceof TableDataService.BinaryValue bv && bv.size() > 16) {
                typedStart = null;
                showInfo("Colunas binárias: clique com o botão direito › Carregar arquivo… / Salvar em arquivo…");
                return;
            }
            super.startEdit();
            if (!isEditing()) {
                return;
            }
            if (ValueParser.isBoolean(m.jdbcType())) {
                startBooleanEditor(row, m, v);
                return;
            }
            String initial = typedStart != null ? typedStart : editText(v);
            boolean typed = typedStart != null;
            typedStart = null;
            editingRow = row;
            field = new TextField(initial);
            field.getStyleClass().add("cell-editor");
            field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                switch (e.getCode()) {
                    case ENTER -> {
                        commitFromEditor(true);
                        e.consume();
                    }
                    case ESCAPE -> {
                        cancelEdit();
                        e.consume();
                    }
                    case TAB -> {
                        int target = e.isShiftDown() ? idx - 1 : idx + 1;
                        int rowIndex = grid.getItems().indexOf(editingRow);
                        if (commitFromEditor(true) && target >= 0 && target < gridMeta.length) {
                            Platform.runLater(() -> editCell(rowIndex, target));
                        }
                        e.consume();
                    }
                    case F4 -> {
                        if (m.isForeignKey()) {
                            cancelEdit();
                            Platform.runLater(() -> openLookup(row, idx));
                            e.consume();
                        }
                    }
                    default -> { }
                }
            });
            field.setPromptText(promptFor(m));
            Node editor = field;
            if (m.isForeignKey()) {
                Button pick = new Button("…");
                pick.getStyleClass().add("cell-pick");
                pick.setTooltip(new Tooltip("Escolher em " + m.fkTable() + " (F4)"));
                pick.setFocusTraversable(false);
                pick.setOnAction(e -> {
                    finishing = true;
                    cancelEdit();
                    finishing = false;
                    openLookup(row, idx);
                });
                HBox box = new HBox(2, field, pick);
                HBox.setHgrow(field, Priority.ALWAYS);
                box.setAlignment(Pos.CENTER_LEFT);
                editor = box;
            }
            setText(null);
            setGraphic(editor);
            if (typed) {
                field.end();
            } else {
                field.selectAll();
            }
            activeEditor = field;
            // o foco só é pedido depois que a célula foi desenhada; antes disso o JavaFX
            // devolveria o foco à grade e a edição terminaria sozinha
            final TextField f = field;
            Platform.runLater(() -> {
                if (!isEditing() || field != f) {
                    return;
                }
                f.requestFocus();
                if (typed) {
                    f.end();
                }
                f.focusedProperty().addListener((o, was, now) -> {
                    if (was && !now && isEditing() && field == f && !finishing) {
                        if (!commitFromEditor(false)) {
                            cancelEdit();
                        }
                    }
                });
            });
        }

        private void startBooleanEditor(GridRow row, Meta.ColumnInfo m, Object v) {
            ComboBox<String> box = new ComboBox<>();
            box.getItems().addAll("true", "false");
            if (m.nullable() || row.isNew()) {
                box.getItems().add(row.isNew() && m.defaultValue() != null ? "(padrão)" : "NULL");
            }
            box.setValue(v == null ? "NULL" : v == TableEditor.DEFAULT ? null : String.valueOf(v));
            box.setOnAction(e -> {
                String s = box.getValue();
                if (s == null) {
                    return;
                }
                finishing = true;
                Object val = switch (s) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    case "(padrão)" -> TableEditor.DEFAULT;
                    default -> null;
                };
                cancelEdit();
                finishing = false;
                applyValue(row, idx, val);
            });
            box.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                }
            });
            box.setMaxWidth(Double.MAX_VALUE);
            setText(null);
            setGraphic(box);
            box.requestFocus();
            Platform.runLater(box::show);
        }

        /** Converte o texto do editor; se válido grava e sai da edição. */
        boolean commitFromEditor(boolean keepFocus) {
            if (field == null || !isEditing()) {
                return true;
            }
            GridRow row = editingRow != null ? editingRow : row();
            if (row == null) {
                cancelEdit();
                return true;
            }
            Meta.ColumnInfo m = meta(idx);
            String text = field.getText();
            Object value;
            try {
                if (row.isNew() && (text == null || text.isEmpty()) && m.hasAutomaticValue()) {
                    value = TableEditor.DEFAULT;
                } else {
                    value = ValueParser.parse(text, m);
                }
            } catch (ValueParser.InvalidValueException ex) {
                showError(m.name() + ": " + ex.getMessage());
                field.getStyleClass().add("cell-editor-error");
                if (keepFocus) {
                    field.requestFocus();
                }
                return false;
            }
            finishing = true;
            int rowIndex = grid.getItems().indexOf(row);
            cancelEdit();
            finishing = false;
            if (!GridRow.sameValue(row.values[idx], value) || (row.isNew() && value != row.values[idx])) {
                applyValue(row, idx, value);
            }
            hideBannerIfEditorError();
            if (keepFocus && rowIndex >= 0) {
                grid.getSelectionModel().clearAndSelect(rowIndex, columnOf(idx));
                grid.getFocusModel().focus(rowIndex, columnOf(idx));
                grid.requestFocus();
            }
            return true;
        }

        private void hideBannerIfEditorError() {
            if (banner.getStyleClass().contains("banner-error") && banner.getText().startsWith(meta(idx).name() + ":")) {
                hideBanner();
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            if (activeEditor == field) {
                activeEditor = null;
            }
            field = null;
            editingRow = null;
            updateItem(getItem(), isEmpty());
        }

        private String editText(Object v) {
            if (v == null || v == TableEditor.DEFAULT) {
                return "";
            }
            if (v instanceof TableDataService.BinaryValue b) {
                return HexFormat.of().formatHex(b.head());
            }
            if (v instanceof byte[] b) {
                return HexFormat.of().formatHex(b);
            }
            return ValueFormatter.format(v);
        }

        private String promptFor(Meta.ColumnInfo m) {
            return switch (m.jdbcType()) {
                case java.sql.Types.DATE -> "dd/mm/aaaa";
                case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "dd/mm/aaaa hh:mm:ss";
                case java.sql.Types.TIME -> "hh:mm:ss";
                default -> m.hasAutomaticValue() ? "(vazio = padrão)"
                        : ValueParser.isTextual(m.jdbcType()) ? "" : m.nullable() ? "(vazio = NULL)" : "";
            };
        }
    }
}
