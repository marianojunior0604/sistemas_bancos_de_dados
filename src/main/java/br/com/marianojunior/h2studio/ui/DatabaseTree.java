package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.Meta.TableInfo;
import br.com.marianojunior.h2studio.core.MetadataService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Árvore à esquerda: conexões › schemas › tabelas e views. */
public class DatabaseTree extends VBox {

    /** Tipos de nó da árvore. */
    public sealed interface Node permits ConnNode, SchemaNode, GroupNode, TableNode, MessageNode {
    }

    public record ConnNode(DbSession session) implements Node {
    }

    public record SchemaNode(DbSession session, String schema) implements Node {
    }

    public record GroupNode(DbSession session, String schema, boolean views, int count) implements Node {
    }

    public record TableNode(DbSession session, TableInfo table) implements Node {
    }

    public record MessageNode(String text) implements Node {
    }

    private final TreeView<Node> tree = new TreeView<>();
    private final TreeItem<Node> root = new TreeItem<>();
    private final TextField filter = new TextField();
    /** Estrutura carregada de cada conexão: schema → tabelas. */
    private final Map<DbSession, Map<String, List<TableInfo>>> loaded = new LinkedHashMap<>();
    /** Schema em uso por cada conexão (public, dbo, o banco do MySQL...), aberto na primeira vez. */
    private final Map<DbSession, String> currentSchema = new java.util.HashMap<>();
    /** Schemas sendo carregados sob demanda (servidores com muitos bancos). */
    private final java.util.Set<String> loadingSchemas = new java.util.HashSet<>();
    private boolean showSystemSchemas;

    private Consumer<TableNode> onOpenTable = t -> { };
    private Consumer<DbSession> onCloseSession = s -> { };
    private Consumer<DbSession> onRefreshSession = s -> { };
    private Consumer<DbSession> onNewQuery = s -> { };
    private Consumer<TableNode> onQueryTable = t -> { };
    private java.util.function.BiConsumer<DbSession, String> onNewTable = (s, sc) -> { };
    private Consumer<TableNode> onAlterTable = t -> { };
    private Consumer<TableNode> onDropTable = t -> { };
    private Consumer<DbSession> onFlyway = s -> { };
    /** Ações da Etapa 5: "exportar", "importar" (tabela); "backups", "compactar", "converter", "script" (banco). */
    private java.util.function.BiConsumer<String, TableNode> onTableAction = (a, t) -> { };
    private java.util.function.BiConsumer<String, DbSession> onDbAction = (a, s) -> { };

    public DatabaseTree() {
        getStyleClass().add("db-tree");
        filter.setPromptText("Filtrar tabelas…");
        filter.textProperty().addListener((o, a, b) -> rebuildAll());
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                filter.clear();
            }
        });
        HBox top = new HBox(filter);
        HBox.setHgrow(filter, Priority.ALWAYS);
        top.setPadding(new Insets(6));

        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new NodeCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
            }
        });
        Label empty = new Label("Nenhum banco aberto");
        empty.getStyleClass().add("muted");
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().addAll(top, tree);
    }

    public void setOnOpenTable(Consumer<TableNode> c) {
        onOpenTable = c;
    }

    public void setOnCloseSession(Consumer<DbSession> c) {
        onCloseSession = c;
    }

    public void setOnRefreshSession(Consumer<DbSession> c) {
        onRefreshSession = c;
    }

    public void setOnNewQuery(Consumer<DbSession> c) {
        onNewQuery = c;
    }

    public void setOnQueryTable(Consumer<TableNode> c) {
        onQueryTable = c;
    }

    public void setOnNewTable(java.util.function.BiConsumer<DbSession, String> c) {
        onNewTable = c;
    }

    public void setOnAlterTable(Consumer<TableNode> c) {
        onAlterTable = c;
    }

    public void setOnDropTable(Consumer<TableNode> c) {
        onDropTable = c;
    }

    public void setOnFlyway(Consumer<DbSession> c) {
        onFlyway = c;
    }

    public void setOnTableAction(java.util.function.BiConsumer<String, TableNode> c) {
        onTableAction = c;
    }

    public void setOnDbAction(java.util.function.BiConsumer<String, DbSession> c) {
        onDbAction = c;
    }

    /** Schema padrão da conexão (PUBLIC, ou public em bancos com nomes minúsculos). */
    private String defaultSchema(DbSession s) {
        Map<String, List<TableInfo>> m = loaded.get(s);
        if (m != null) {
            for (String sc : m.keySet()) {
                if (sc.equalsIgnoreCase("PUBLIC")) {
                    return sc;
                }
            }
            if (!m.isEmpty()) {
                return m.keySet().iterator().next();
            }
        }
        return "PUBLIC";
    }

    public TreeView<Node> view() {
        return tree;
    }

    public void setShowSystemSchemas(boolean show) {
        this.showSystemSchemas = show;
        for (DbSession s : new ArrayList<>(loaded.keySet())) {
            load(s);
        }
    }

    public List<DbSession> sessions() {
        return new ArrayList<>(loaded.keySet());
    }

    /** Sessão do item selecionado (ou null). */
    public DbSession selectedSession() {
        TreeItem<Node> it = tree.getSelectionModel().getSelectedItem();
        return it == null ? null : sessionOf(it.getValue());
    }

    private static DbSession sessionOf(Node n) {
        return switch (n) {
            case ConnNode c -> c.session();
            case SchemaNode s -> s.session();
            case GroupNode g -> g.session();
            case TableNode t -> t.session();
            case MessageNode m -> null;
        };
    }

    public void addSession(DbSession s) {
        loaded.put(s, new LinkedHashMap<>());
        TreeItem<Node> item = new TreeItem<>(new ConnNode(s));
        item.getChildren().add(new TreeItem<>(new MessageNode("Carregando…")));
        item.setExpanded(true);
        root.getChildren().add(item);
        tree.getSelectionModel().select(item);
        load(s);
    }

    public void removeSession(DbSession s) {
        loaded.remove(s);
        currentSchema.remove(s);
        root.getChildren().removeIf(i -> i.getValue() instanceof ConnNode c && c.session() == s);
    }

    public void select(DbSession s) {
        for (TreeItem<Node> i : root.getChildren()) {
            if (i.getValue() instanceof ConnNode c && c.session() == s) {
                tree.getSelectionModel().select(i);
                tree.scrollTo(tree.getRow(i));
            }
        }
    }

    /**
     * (Re)lê schemas e tabelas da conexão. No H2 lê tudo de uma vez; nos servidores, que podem ter
     * dezenas de bancos/schemas, lê só o schema em uso (e os já abertos) e o resto ao expandir.
     */
    public void load(DbSession s) {
        boolean sys = showSystemSchemas;
        boolean lazy = !s.isH2();
        Map<String, List<TableInfo>> before = loaded.get(s);
        java.util.Set<String> wasLoaded = new java.util.HashSet<>();
        if (before != null) {
            before.forEach((k, v) -> {
                if (v != null) {
                    wasLoaded.add(k);
                }
            });
        }
        Fx.then(s.submit(c -> {
            Map<String, List<TableInfo>> map = new LinkedHashMap<>();
            List<String> schemas = MetadataService.schemas(c, sys);
            String current = MetadataService.currentSchema(c);
            for (String schema : schemas) {
                boolean now = !lazy || schemas.size() <= 3 || schema.equals(current) || wasLoaded.contains(schema);
                map.put(schema, now ? MetadataService.tables(c, schema) : null);
            }
            return new Object[]{map, current};
        }), r -> {
            if (!loaded.containsKey(s)) {
                return; // fechada enquanto carregava
            }
            @SuppressWarnings("unchecked") Map<String, List<TableInfo>> map = (Map<String, List<TableInfo>>) r[0];
            loaded.put(s, map);
            if (r[1] != null) {
                currentSchema.put(s, (String) r[1]);
            }
            rebuild(s);
        }, e -> {
            TreeItem<Node> item = itemOf(s);
            if (item != null) {
                item.getChildren().setAll(List.of(new TreeItem<>(new MessageNode("Erro: " + Fx.shortMessage(e)))));
            }
        });
    }

    /** Carrega as tabelas de um schema ainda não lido (ao expandir). */
    private void loadSchema(DbSession s, String schema) {
        String key = System.identityHashCode(s) + "|" + schema;
        if (!loadingSchemas.add(key)) {
            return;
        }
        Fx.then(s.submit(c -> MetadataService.tables(c, schema)), list -> {
            loadingSchemas.remove(key);
            Map<String, List<TableInfo>> map = loaded.get(s);
            if (map != null && map.containsKey(schema)) {
                map.put(schema, list);
                rebuild(s);
            }
        }, e -> {
            loadingSchemas.remove(key);
            Fx.error(getScene() == null ? null : getScene().getWindow(), "Erro ao ler " + schema, Fx.shortMessage(e), e);
        });
    }

    private TreeItem<Node> itemOf(DbSession s) {
        for (TreeItem<Node> i : root.getChildren()) {
            if (i.getValue() instanceof ConnNode c && c.session() == s) {
                return i;
            }
        }
        return null;
    }

    private void rebuildAll() {
        for (DbSession s : loaded.keySet()) {
            rebuild(s);
        }
    }

    private void rebuild(DbSession s) {
        TreeItem<Node> connItem = itemOf(s);
        if (connItem == null) {
            return;
        }
        // guarda o que estava expandido para não "fechar" a árvore ao atualizar
        java.util.Set<String> expanded = new java.util.HashSet<>();
        for (TreeItem<Node> sch : connItem.getChildren()) {
            if (sch.getValue() instanceof SchemaNode sn) {
                if (sch.isExpanded()) {
                    expanded.add(sn.schema());
                }
                for (TreeItem<Node> g : sch.getChildren()) {
                    if (g.isExpanded() && g.getValue() instanceof GroupNode gn) {
                        expanded.add(sn.schema() + "/" + gn.views());
                    }
                }
            } else if (sch.getValue() instanceof GroupNode gn && sch.isExpanded()) {
                expanded.add(gn.schema() + "/" + gn.views());
            }
        }
        boolean firstBuild = expanded.isEmpty();
        String f = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        String current = currentSchema.get(s);

        List<TreeItem<Node>> schemaItems = new ArrayList<>();
        Map<String, List<TableInfo>> map = loaded.get(s);
        // Firebird / SQLite: não há schemas, as pastas Tabelas e Views ficam direto na conexão
        if (map.size() == 1 && map.containsKey("") && map.get("") != null) {
            List<TableInfo> all = map.get("");
            List<TableInfo> tables = all.stream().filter(t -> !t.view() && matches(t, f)).toList();
            List<TableInfo> views = all.stream().filter(t -> t.view() && matches(t, f)).toList();
            if (!tables.isEmpty() || f.isEmpty()) {
                schemaItems.add(group(s, "", false, tables, f, expanded, firstBuild));
            }
            if (!views.isEmpty()) {
                schemaItems.add(group(s, "", true, views, f, expanded, false));
            }
        } else {
            for (var e : map.entrySet()) {
                String schema = e.getKey();
                boolean isCurrent = schema.equals(current) || (current == null && schema.equalsIgnoreCase("PUBLIC"));
                TreeItem<Node> sItem = new TreeItem<>(new SchemaNode(s, schema));
                if (e.getValue() == null) {
                    // ainda não lido: carrega ao expandir
                    if (!f.isEmpty()) {
                        continue;
                    }
                    sItem.getChildren().add(new TreeItem<>(new MessageNode("Carregando…")));
                    sItem.expandedProperty().addListener((o, was, now) -> {
                        if (now) {
                            loadSchema(s, schema);
                        }
                    });
                    schemaItems.add(sItem);
                    if (expanded.contains(schema)) {
                        loadSchema(s, schema);
                    }
                    continue;
                }
                List<TableInfo> tables = e.getValue().stream().filter(t -> !t.view() && matches(t, f)).toList();
                List<TableInfo> views = e.getValue().stream().filter(t -> t.view() && matches(t, f)).toList();
                if (!f.isEmpty() && tables.isEmpty() && views.isEmpty()) {
                    continue;
                }
                sItem.setExpanded(!f.isEmpty() || expanded.contains(schema) || (firstBuild && isCurrent));
                sItem.getChildren().add(group(s, schema, false, tables, f, expanded, firstBuild));
                if (!views.isEmpty()) {
                    sItem.getChildren().add(group(s, schema, true, views, f, expanded, false));
                }
                schemaItems.add(sItem);
            }
        }
        if (schemaItems.isEmpty()) {
            schemaItems.add(new TreeItem<>(new MessageNode(f.isEmpty() ? "Banco vazio" : "Nada encontrado")));
        }
        connItem.getChildren().setAll(schemaItems);
    }

    private static boolean matches(TableInfo t, String f) {
        return f.isEmpty() || t.name().toLowerCase(Locale.ROOT).contains(f);
    }

    private static TreeItem<Node> group(DbSession s, String schema, boolean views, List<TableInfo> items, String f,
                                        java.util.Set<String> expanded, boolean defaultExpanded) {
        TreeItem<Node> g = new TreeItem<>(new GroupNode(s, schema, views, items.size()));
        for (TableInfo t : items) {
            g.getChildren().add(new TreeItem<>(new TableNode(s, t)));
        }
        g.setExpanded(!f.isEmpty() || expanded.contains(schema + "/" + views) || defaultExpanded);
        return g;
    }

    private void openSelected() {
        TreeItem<Node> it = tree.getSelectionModel().getSelectedItem();
        if (it != null && it.getValue() instanceof TableNode t) {
            onOpenTable.accept(t);
        }
    }

    public void focusFilter() {
        filter.requestFocus();
        filter.selectAll();
    }

    private final class NodeCell extends TreeCell<Node> {
        @Override
        protected void updateItem(Node item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("conn-node", "message-node", "group-node");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setTooltip(null);
                return;
            }
            switch (item) {
                case ConnNode c -> {
                    DbSession s = c.session();
                    setText(s.displayName());
                    HBox g = new HBox(4, Icons.database());
                    if (s.isReadOnly()) {
                        g.getChildren().add(Icons.lock());
                    }
                    if (!s.isH2()) {
                        // nome e, ao lado, o tipo do banco (PostgreSQL, Firebird...)
                        Label kind = new Label(s.type().shortLabel());
                        kind.getStyleClass().add("db-kind");
                        g.getChildren().addAll(new Label(s.displayName()), kind);
                        g.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                        setText(null);
                    }
                    setGraphic(g);
                    getStyleClass().add("conn-node");
                    setTooltip(new javafx.scene.control.Tooltip(!s.isH2()
                            ? s.config().location() + "\n" + s.engineLabel()
                            + (s.isReadOnly() ? "\nSomente leitura" : "")
                            : s.config().getFilePath() + "\nH2 " + s.productVersion()
                            + (s.isCopy() ? "\nCópia de: " + s.config().getCopyOf() : "")
                            + (s.isReadOnly() ? "\nSomente leitura" : "")));
                    setContextMenu(connMenu(s));
                }
                case SchemaNode sn -> {
                    setText(sn.schema());
                    setGraphic(Icons.schema());
                    setTooltip(null);
                    setContextMenu(schemaMenu(sn));
                }
                case GroupNode gn -> {
                    setText((gn.views() ? "Views" : "Tabelas") + " (" + gn.count() + ")");
                    setGraphic(Icons.folder());
                    getStyleClass().add("group-node");
                    setTooltip(null);
                    setContextMenu(null);
                }
                case TableNode tn -> {
                    setText(tn.table().name());
                    setGraphic(tn.table().view() ? Icons.view() : Icons.table());
                    String rem = tn.table().remarks();
                    setTooltip(rem == null || rem.isBlank() ? null : new javafx.scene.control.Tooltip(rem));
                    setContextMenu(tableMenu(tn));
                }
                case MessageNode m -> {
                    setText(m.text());
                    setGraphic(null);
                    getStyleClass().add("message-node");
                    setTooltip(null);
                    setContextMenu(null);
                }
            }
        }

        private ContextMenu connMenu(DbSession s) {
            if (!s.isH2()) {
                MenuItem q = new MenuItem("Nova consulta SQL");
                q.setOnAction(e -> onNewQuery.accept(s));
                MenuItem backups = new MenuItem("Backup e restauração…");
                backups.setOnAction(e -> onDbAction.accept("backups", s));
                MenuItem refresh = new MenuItem("Atualizar");
                refresh.setOnAction(e -> onRefreshSession.accept(s));
                MenuItem copyLoc = new MenuItem(s.type().isFile() ? "Copiar caminho do arquivo" : "Copiar endereço");
                copyLoc.setOnAction(e -> Fx.copy(s.config().location()));
                MenuItem close = new MenuItem("Desconectar");
                close.setOnAction(e -> onCloseSession.accept(s));
                return new ContextMenu(q, backups, new SeparatorMenuItem(), refresh, copyLoc, new SeparatorMenuItem(), close);
            }
            MenuItem query = new MenuItem("Nova consulta SQL");
            query.setOnAction(e -> onNewQuery.accept(s));
            MenuItem newTable = new MenuItem("Nova tabela…");
            newTable.setOnAction(e -> onNewTable.accept(s, defaultSchema(s)));
            newTable.setDisable(s.isReadOnly());
            MenuItem flyway = new MenuItem("Histórico do Flyway…");
            flyway.setOnAction(e -> onFlyway.accept(s));
            MenuItem refresh = new MenuItem("Atualizar");
            refresh.setOnAction(e -> onRefreshSession.accept(s));
            MenuItem copyPath = new MenuItem("Copiar caminho do arquivo");
            copyPath.setOnAction(e -> Fx.copy(s.config().getFilePath()));
            MenuItem close = new MenuItem("Desconectar");
            close.setOnAction(e -> onCloseSession.accept(s));
            MenuItem backups = new MenuItem("Backups e restauração…");
            backups.setOnAction(e -> onDbAction.accept("backups", s));
            MenuItem script = new MenuItem("Gerar script SQL do banco…");
            script.setOnAction(e -> onDbAction.accept("script", s));
            MenuItem compact = new MenuItem("Compactar banco…");
            compact.setOnAction(e -> onDbAction.accept("compactar", s));
            compact.setDisable(s.isCopy());
            MenuItem convert = new MenuItem("Converter versão do H2…");
            convert.setOnAction(e -> onDbAction.accept("converter", s));
            javafx.scene.control.Menu tools = new javafx.scene.control.Menu("Utilitários", null, backups, script,
                    new SeparatorMenuItem(), compact, convert);
            return new ContextMenu(query, newTable, flyway, tools, new SeparatorMenuItem(), refresh, copyPath,
                    new SeparatorMenuItem(), close);
        }

        private ContextMenu schemaMenu(SchemaNode sn) {
            if (!sn.session().isH2()) {
                MenuItem copy = new MenuItem("Copiar nome");
                copy.setOnAction(e -> Fx.copy(sn.schema()));
                ContextMenu m = connMenu(sn.session());
                m.getItems().add(0, copy);
                m.getItems().add(1, new SeparatorMenuItem());
                return m;
            }
            MenuItem newTable = new MenuItem("Nova tabela em " + sn.schema() + "…");
            newTable.setOnAction(e -> onNewTable.accept(sn.session(), sn.schema()));
            newTable.setDisable(sn.session().isReadOnly());
            ContextMenu m = connMenu(sn.session());
            m.getItems().add(0, newTable);
            m.getItems().add(1, new SeparatorMenuItem());
            return m;
        }

        private ContextMenu tableMenu(TableNode t) {
            MenuItem open = new MenuItem("Abrir");
            open.setOnAction(e -> onOpenTable.accept(t));
            if (!t.session().isH2()) {
                var type = t.session().type();
                MenuItem copyName = new MenuItem("Copiar nome");
                copyName.setOnAction(e -> Fx.copy(t.table().name()));
                MenuItem copySelect = new MenuItem("Copiar SELECT");
                copySelect.setOnAction(e -> Fx.copy(type.sampleSelect(t.table().schema(), t.table().name())));
                MenuItem export = new MenuItem("Exportar (Excel/CSV)…");
                export.setOnAction(e -> onTableAction.accept("exportar", t));
                MenuItem query = new MenuItem("Consultar no editor SQL");
                query.setOnAction(e -> onQueryTable.accept(t));
                return new ContextMenu(open, query, new SeparatorMenuItem(), export, new SeparatorMenuItem(), copyName,
                        copySelect);
            }
            MenuItem sql = new MenuItem("Consultar com SQL");
            sql.setOnAction(e -> onQueryTable.accept(t));
            MenuItem copyName = new MenuItem("Copiar nome");
            copyName.setOnAction(e -> Fx.copy(t.table().name()));
            MenuItem copySelect = new MenuItem("Copiar SELECT");
            copySelect.setOnAction(e -> Fx.copy("SELECT * FROM " + t.table().qualified() + ";"));
            MenuItem export = new MenuItem("Exportar (Excel/CSV)…");
            export.setOnAction(e -> onTableAction.accept("exportar", t));
            ContextMenu m = new ContextMenu(open, sql, new SeparatorMenuItem(), export, new SeparatorMenuItem(), copyName, copySelect);
            if (!t.table().view()) {
                MenuItem alter = new MenuItem("Alterar estrutura…");
                alter.setOnAction(e -> onAlterTable.accept(t));
                MenuItem drop = new MenuItem("Excluir tabela…");
                drop.setOnAction(e -> onDropTable.accept(t));
                boolean ro = t.session().isReadOnly();
                drop.setDisable(ro);
                MenuItem imp = new MenuItem("Importar CSV…");
                imp.setOnAction(e -> onTableAction.accept("importar", t));
                imp.setDisable(t.session().isReadOnly() || t.session().isCopy());
                m.getItems().add(m.getItems().indexOf(export) + 1, imp);
                m.getItems().addAll(2, List.of(alter, new SeparatorMenuItem()));
                m.getItems().addAll(new SeparatorMenuItem(), drop);
            }
            return m;
        }
    }
}
