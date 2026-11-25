package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.AppPreferences;
import br.com.marianojunior.h2studio.core.BackupService;
import br.com.marianojunior.h2studio.core.CsvImporter;
import br.com.marianojunior.h2studio.core.CsvReader;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DbType;
import br.com.marianojunior.h2studio.core.DdlBuilder;
import br.com.marianojunior.h2studio.core.Meta;
import br.com.marianojunior.h2studio.core.MetadataService;
import br.com.marianojunior.h2studio.core.SheetImport;
import br.com.marianojunior.h2studio.core.SqlCompletion;
import br.com.marianojunior.h2studio.core.TableDesign;
import br.com.marianojunior.h2studio.core.XlsxReader;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
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
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Importa um CSV ou uma aba de planilha .xlsx para qualquer banco: para uma tabela que já existe
 * (liga as colunas pelo nome) ou criando uma tabela nova com os tipos descobertos nos dados.
 * Tudo é validado antes; a gravação é numa transação só (entra tudo ou nada).
 */
public class ImportDialog extends Dialog<ImportDialog.Outcome> {

    /** Resultado: onde gravou e quantas linhas. {@code created} = tabelas criadas. */
    public record Outcome(String schema, List<String> created, String table, int inserted, int deleted) {
    }

    private static final String IGNORE = "(ignorar)";
    private static final String AUTO = "Automático";

    private final DbSession session;
    private final DbType type;
    private final SqlCompletion.Casing casing;
    private final String schema;
    private String defaultSchema;

    // ---------- arquivo ----------
    private Path file;
    private boolean xlsx;
    private List<String[]> records = List.of();
    private int[] lines = new int[0];
    private Charset csvCharset;
    private char csvSeparator;

    private final TextField fileField = new TextField();
    private final ComboBox<String> charsetBox = new ComboBox<>(FXCollections.observableArrayList(AUTO, "UTF-8", "Windows-1252 (Excel antigo)"));
    private final ComboBox<String> sepBox = new ComboBox<>(FXCollections.observableArrayList(AUTO, "; (ponto e vírgula)", ", (vírgula)", "TAB"));
    private final ComboBox<String> sheetBox = new ComboBox<>();
    private final Label sheetLabel = new Label("Aba:");
    private final Label charsetLabel = new Label("Codificação:");
    private final Label sepLabel = new Label("Separador:");
    private final CheckBox hasHeader = new CheckBox("Primeira linha é o cabeçalho");
    private final Label info = new Label("Escolha o arquivo CSV ou a planilha .xlsx.");

    // ---------- destino ----------
    private final ToggleGroup destGroup = new ToggleGroup();
    private final RadioButton toNew = new RadioButton("Tabela nova:");
    private final RadioButton toExisting = new RadioButton("Tabela existente:");
    private final TextField newName = new TextField();
    private final CheckBox addId = new CheckBox("criar coluna id (auto incremento, chave primária)");
    private final ComboBox<String> existingBox = new ComboBox<>();
    private final ComboBox<CsvImporter.Mode> mode = new ComboBox<>(FXCollections.observableArrayList(CsvImporter.Mode.values()));
    private final Label modeWarn = new Label();
    private List<Meta.TableInfo> tables = List.of();
    private List<Meta.ColumnInfo> columns = List.of();
    private List<Meta.ForeignKeyInfo> incoming = List.of();

    // ---------- colunas, prévia e erros ----------
    private final GridPane mapGrid = new GridPane();
    private final List<ComboBox<String>> mapBoxes = new ArrayList<>();
    private final List<CheckBox> newInclude = new ArrayList<>();
    private final List<TextField> newNames = new ArrayList<>();
    private final List<ComboBox<String>> newTypes = new ArrayList<>();
    private final Label mapTitle = new Label();
    private final TableView<String[]> preview = new TableView<>();
    private final ListView<String> errors = new ListView<>();
    private final Label errorsTitle = new Label();
    private final Button importBtn;
    private final Button allSheetsBtn;
    private boolean loading;

    /**
     * @param schema schema/banco onde importar (null = o da conexão)
     * @param table  tabela de destino já escolhida (null = começa em "tabela nova")
     */
    public ImportDialog(Window owner, DbSession session, String schema, Meta.TableInfo table) {
        this.session = session;
        this.type = session.type();
        this.casing = SqlCompletion.casingOf(session.config());
        this.schema = schema != null ? schema : session.isH2() ? (casing == SqlCompletion.Casing.LOWER ? "public" : "PUBLIC")
                : currentSchema(session);
        initOwner(owner);
        setTitle("Importar CSV ou planilha");
        setHeaderText("Importar para " + session.displayName() + " (" + session.engineLabel() + ")"
                + (this.schema == null || this.schema.isEmpty() ? "" : " · " + this.schema));
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        ButtonType imp = new ButtonType("Validar e importar", ButtonBar.ButtonData.OK_DONE);
        ButtonType all = new ButtonType("Importar todas as abas…", ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().addAll(all, imp, Fx.CANCEL);
        importBtn = (Button) getDialogPane().lookupButton(imp);
        importBtn.setDisable(true);
        importBtn.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume(); // o diálogo só fecha depois de importar
            validateAndImport();
        });
        allSheetsBtn = (Button) getDialogPane().lookupButton(all);
        allSheetsBtn.setVisible(false);
        allSheetsBtn.setManaged(false);
        allSheetsBtn.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            importAllSheets();
        });

        // ---------- arquivo ----------
        fileField.setEditable(false);
        fileField.setPromptText("arquivo .xlsx ou .csv");
        Button choose = new Button("Escolher arquivo…");
        choose.setOnAction(e -> chooseFile());
        HBox fileRow = new HBox(6, fileField, choose);
        HBox.setHgrow(fileField, Priority.ALWAYS);
        charsetBox.setValue(AUTO);
        sepBox.setValue(AUTO);
        hasHeader.setSelected(true);
        charsetBox.setOnAction(e -> reread());
        sepBox.setOnAction(e -> reread());
        sheetBox.setOnAction(e -> {
            if (!loading) {
                reread();
            }
        });
        hasHeader.setOnAction(e -> rebuild());
        FlowPane opts = new FlowPane(10, 6, sheetLabel, sheetBox, charsetLabel, charsetBox, sepLabel, sepBox, hasHeader);
        opts.setAlignment(Pos.CENTER_LEFT);
        for (Node n : opts.getChildren()) {
            ((javafx.scene.control.Control) n).setMinWidth(Region.USE_PREF_SIZE);
        }
        sheetBox.setPrefWidth(200);
        info.getStyleClass().add("muted");
        info.setWrapText(true);

        // ---------- destino ----------
        toNew.setToggleGroup(destGroup);
        toExisting.setToggleGroup(destGroup);
        newName.setPrefColumnCount(22);
        newName.setOnKeyTyped(e -> newName.setUserData(null)); // digitou: para de sugerir pelo nome da aba
        addId.setSelected(true);
        mode.setValue(CsvImporter.Mode.APPEND);
        mode.setOnAction(e -> updateModeWarning());
        existingBox.setPrefWidth(240);
        existingBox.setOnAction(e -> {
            if (!loading) {
                loadExisting();
            }
        });
        modeWarn.setWrapText(true);
        modeWarn.setMinHeight(Region.USE_PREF_SIZE);
        modeWarn.getStyleClass().add("text-warn");
        GridPane dest = new GridPane();
        dest.setHgap(8);
        dest.setVgap(6);
        dest.addRow(0, toNew, new HBox(8, newName, addId));
        dest.addRow(1, toExisting, new HBox(8, existingBox, new Label("Modo:"), mode));
        dest.add(modeWarn, 1, 2);
        for (Node n : List.of(toNew, toExisting)) {
            ((javafx.scene.control.Control) n).setMinWidth(Region.USE_PREF_SIZE);
        }
        destGroup.selectedToggleProperty().addListener((o, a, b) -> {
            updateDest();
            rebuild();
        });
        Label destTitle = new Label("Destino");
        destTitle.getStyleClass().add("section-title");

        // ---------- colunas ----------
        mapGrid.setHgap(8);
        mapGrid.setVgap(4);
        ScrollPane mapScroll = new ScrollPane(mapGrid);
        mapScroll.setFitToWidth(true);
        mapScroll.setPrefHeight(200);
        mapTitle.getStyleClass().add("section-title");

        Label prevTitle = new Label("Prévia (primeiras 20 linhas)");
        prevTitle.getStyleClass().add("section-title");
        preview.setPrefHeight(150);
        preview.setPlaceholder(new Label("Nenhum arquivo carregado."));

        errorsTitle.getStyleClass().addAll("section-title", "text-error");
        errors.setPrefHeight(110);
        errors.getStyleClass().add("mono");

        VBox box = new VBox(8, fileRow, opts, info, destTitle, dest, mapTitle, mapScroll, prevTitle, preview, errorsTitle, errors);
        box.setPadding(new Insets(6, 4, 0, 4));
        box.setPrefWidth(900);
        getDialogPane().setContent(box);
        showErrors(List.of());
        updateFileKind();

        boolean readOnly = session.isReadOnly();
        toNew.setDisable(readOnly);
        destGroup.selectToggle(table != null || readOnly ? toExisting : toNew);
        updateDest();
        Fx.then(session.submit(c -> {
            String def = session.isH2() ? null : MetadataService.currentSchema(c);
            return new Object[]{MetadataService.tables(c, this.schema == null ? "" : this.schema), def};
        }), r -> {
            @SuppressWarnings("unchecked") List<Meta.TableInfo> list = (List<Meta.TableInfo>) r[0];
            tables = list.stream().filter(t -> !t.view()).toList();
            defaultSchema = session.isH2() ? (casing == SqlCompletion.Casing.LOWER ? "public" : "PUBLIC")
                    : r[1] == null ? "" : (String) r[1];
            loading = true;
            existingBox.setItems(FXCollections.observableArrayList(tables.stream().map(Meta.TableInfo::name).toList()));
            if (table != null) {
                existingBox.setValue(table.name());
            }
            loading = false;
            if (table != null) {
                loadExisting();
            }
        }, e -> info.setText("Erro ao ler as tabelas: " + Fx.shortMessage(e)));
        Platform.runLater(this::chooseFile);
    }

    private static String currentSchema(DbSession s) {
        try {
            String sch = s.submit(MetadataService::currentSchema).get(5, java.util.concurrent.TimeUnit.SECONDS);
            return sch == null ? "" : sch;
        } catch (Exception e) {
            return "";
        }
    }

    private Window window() {
        return getDialogPane().getScene() == null ? getOwner() : getDialogPane().getScene().getWindow();
    }

    // =====================================================================
    // Arquivo
    // =====================================================================

    private void chooseFile() {
        File f = Fx.chooseOpen(getOwner(), "Escolher planilha ou CSV", "importar.pasta",
                new FileChooser.ExtensionFilter("Planilhas e CSV (*.xlsx, *.csv, *.txt)", "*.xlsx", "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"),
                new FileChooser.ExtensionFilter("CSV e texto (*.csv, *.txt)", "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*"));
        if (f == null) {
            return;
        }
        file = f.toPath();
        fileField.setText(f.getAbsolutePath());
        xlsx = isZip(file);
        updateFileKind();
        if (xlsx) {
            try {
                List<String> names = XlsxReader.sheetNames(file);
                loading = true;
                sheetBox.setItems(FXCollections.observableArrayList(names));
                sheetBox.setValue(names.isEmpty() ? null : names.get(0));
                loading = false;
                allSheetsBtn.setVisible(names.size() > 1 && !session.isReadOnly());
                allSheetsBtn.setManaged(allSheetsBtn.isVisible());
            } catch (Exception e) {
                records = List.of();
                info.setText("Não foi possível ler a planilha: " + e.getMessage());
                rebuild();
                return;
            }
        } else {
            allSheetsBtn.setVisible(false);
            allSheetsBtn.setManaged(false);
        }
        reread();
    }

    private static boolean isZip(Path p) {
        try (var in = java.nio.file.Files.newInputStream(p)) {
            byte[] b = in.readNBytes(4);
            return b.length == 4 && b[0] == 'P' && b[1] == 'K';
        } catch (Exception e) {
            return p.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx");
        }
    }

    private void updateFileKind() {
        for (Node n : List.of(sheetLabel, sheetBox)) {
            n.setVisible(xlsx);
            n.setManaged(xlsx);
        }
        for (Node n : List.of(charsetLabel, charsetBox, sepLabel, sepBox)) {
            n.setVisible(!xlsx);
            n.setManaged(!xlsx);
        }
    }

    private void reread() {
        if (file == null) {
            return;
        }
        try {
            if (xlsx) {
                String sheet = sheetBox.getValue();
                if (sheet == null) {
                    records = List.of();
                    info.setText("A planilha não tem abas visíveis.");
                    rebuild();
                    return;
                }
                XlsxReader.Sheet s = XlsxReader.read(file, sheet);
                records = s.records();
                lines = s.lines();
            } else {
                Charset cs = switch (charsetBox.getValue()) {
                    case "UTF-8" -> StandardCharsets.UTF_8;
                    case AUTO -> null;
                    default -> Charset.forName("windows-1252");
                };
                char sep = switch (sepBox.getValue()) {
                    case AUTO -> 0;
                    case "TAB" -> '\t';
                    default -> sepBox.getValue().charAt(0);
                };
                CsvReader.Data d = CsvReader.read(file, cs, sep);
                records = d.records();
                lines = d.lines();
                csvCharset = d.charset();
                csvSeparator = d.separator();
            }
        } catch (Exception e) {
            records = List.of();
            info.setText("Não foi possível ler o arquivo: " + e.getMessage());
            rebuild();
            return;
        }
        if (newName.getText().isBlank() || newName.getUserData() != null) {
            String base = xlsx && sheetBox.getValue() != null && !sheetBox.getValue().matches("(?i)(planilha|sheet|plan|folha)\\s*\\d*")
                    ? sheetBox.getValue() : file.getFileName().toString();
            newName.setText(SheetImport.tableName(base, type, casing));
            newName.setUserData("auto"); // continua sugerindo enquanto o usuário não digitar
        }
        rebuild();
    }

    private int start() {
        return hasHeader.isSelected() ? 1 : 0;
    }

    private List<String[]> dataRecords() {
        return records.subList(Math.min(start(), records.size()), records.size());
    }

    private int[] dataLines() {
        return Arrays.copyOfRange(lines, Math.min(start(), lines.length), lines.length);
    }

    private String[] header() {
        int n = records.stream().mapToInt(r -> r.length).max().orElse(0);
        String[] h = new String[n];
        String[] first = hasHeader.isSelected() && !records.isEmpty() ? records.get(0) : new String[0];
        for (int i = 0; i < n; i++) {
            h[i] = i < first.length && !first[i].isBlank() ? first[i].trim() : "Coluna " + (i + 1);
        }
        return h;
    }

    // =====================================================================
    // Destino
    // =====================================================================

    private boolean isNew() {
        return destGroup.getSelectedToggle() == toNew;
    }

    private void updateDest() {
        boolean nw = isNew();
        newName.setDisable(!nw);
        addId.setDisable(!nw);
        existingBox.setDisable(nw);
        mode.setDisable(nw);
        importBtn.setText(nw ? "Criar tabela e importar" : "Validar e importar");
        updateModeWarning();
    }

    private void loadExisting() {
        String name = existingBox.getValue();
        if (name == null) {
            return;
        }
        Meta.TableInfo t = tables.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
        if (t == null) {
            return;
        }
        Fx.then(session.submit(c -> new Object[]{
            MetadataService.columns(c, t.schema(), t.name()),
            MetadataService.foreignKeys(c, t.schema(), t.name()).stream().filter(Meta.ForeignKeyInfo::incoming).toList()}),
                r -> {
                    @SuppressWarnings("unchecked") List<Meta.ColumnInfo> cols = (List<Meta.ColumnInfo>) r[0];
                    @SuppressWarnings("unchecked") List<Meta.ForeignKeyInfo> inc = (List<Meta.ForeignKeyInfo>) r[1];
                    columns = cols;
                    incoming = inc;
                    updateModeWarning();
                    rebuild();
                }, e -> info.setText("Erro ao ler a tabela: " + Fx.shortMessage(e)));
    }

    private void updateModeWarning() {
        String w = "";
        if (!isNew() && mode.getValue() == CsvImporter.Mode.REPLACE && existingBox.getValue() != null) {
            w = "Todas as linhas atuais de " + existingBox.getValue() + " serão APAGADAS antes de importar.";
            List<String> cascade = incoming.stream().filter(f -> "CASCADE".equals(f.onDelete()))
                    .map(Meta.ForeignKeyInfo::refTable).distinct().toList();
            if (!cascade.isEmpty()) {
                w += " ATENÇÃO: " + String.join(", ", cascade) + " usa(m) esta tabela com ON DELETE CASCADE: "
                        + "as linhas ligadas nessas tabelas também serão apagadas!";
            } else if (!incoming.isEmpty()) {
                w += " Se outras tabelas usarem estas linhas, a importação será recusada.";
            }
        }
        modeWarn.setText(w);
        modeWarn.setVisible(!w.isEmpty());
        modeWarn.setManaged(!w.isEmpty());
    }

    // =====================================================================
    // Colunas e prévia
    // =====================================================================

    private void rebuild() {
        mapGrid.getChildren().clear();
        mapGrid.getColumnConstraints().clear();
        mapBoxes.clear();
        newInclude.clear();
        newNames.clear();
        newTypes.clear();
        preview.getColumns().clear();
        preview.getItems().clear();
        showErrors(List.of());
        if (records.isEmpty()) {
            importBtn.setDisable(true);
            mapTitle.setText("Colunas");
            if (file != null && info.getText().startsWith("Escolha")) {
                info.setText("O arquivo está vazio.");
            }
            return;
        }
        int rows = dataRecords().size();
        String where = xlsx ? "aba \"" + sheetBox.getValue() + "\"" : String.format("%s · separador: %s",
                csvCharset != null && csvCharset.name().equals("UTF-8") ? "UTF-8" : "Windows-1252",
                csvSeparator == ';' ? "ponto e vírgula" : csvSeparator == ',' ? "vírgula" : "TAB");
        info.setText(String.format("%,d linha(s) de dados · ", rows).replace(',', '.') + where);
        String[] header = header();
        List<String[]> data = dataRecords();
        if (isNew()) {
            buildNewGrid(header, data);
        } else {
            buildMapGrid(header, data);
        }
        for (int i = 0; i < header.length; i++) {
            final int idx = i;
            TableColumn<String[], String> tc = new TableColumn<>(header[i]);
            tc.setCellValueFactory(cd -> new ReadOnlyStringWrapper(idx < cd.getValue().length ? cd.getValue()[idx] : ""));
            tc.setPrefWidth(110);
            preview.getColumns().add(tc);
        }
        preview.getItems().setAll(data.subList(0, Math.min(20, data.size())));
        importBtn.setDisable(rows == 0 || (!isNew() && columns.isEmpty()));
    }

    private static Label head(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("grid-header");
        return l;
    }

    /** Tabela existente: coluna do arquivo → coluna da tabela. */
    private void buildMapGrid(String[] header, List<String[]> data) {
        mapTitle.setText("Ligação das colunas (arquivo → tabela)");
        mapGrid.getColumnConstraints().addAll(new ColumnConstraints(200), new ColumnConstraints(220), new ColumnConstraints(230));
        mapGrid.addRow(0, head("Coluna do arquivo"), head("Exemplo"), head("Coluna da tabela"));
        if (columns.isEmpty()) {
            mapGrid.add(new Label(existingBox.getValue() == null ? "Escolha a tabela." : "Lendo a tabela…"), 0, 1, 3, 1);
            return;
        }
        List<Meta.ColumnInfo> auto = CsvImporter.autoMap(header, columns, hasHeader.isSelected());
        List<String> choices = new ArrayList<>();
        choices.add(IGNORE);
        columns.stream().filter(CsvImporter::writable).forEach(c -> choices.add(c.name()));
        for (int i = 0; i < header.length; i++) {
            Label name = new Label(header[i]);
            name.setMnemonicParsing(false);
            Label ex = new Label(sample(data, i));
            ex.getStyleClass().add("muted");
            ComboBox<String> target = new ComboBox<>(FXCollections.observableArrayList(choices));
            target.setValue(auto.get(i) == null ? IGNORE : auto.get(i).name());
            target.setMaxWidth(Double.MAX_VALUE);
            target.setOnAction(e -> showErrors(List.of()));
            mapBoxes.add(target);
            mapGrid.addRow(i + 1, name, ex, target);
        }
    }

    /** Tabela nova: incluir? nome e tipo (descoberto nos dados) de cada coluna. */
    private void buildNewGrid(String[] header, List<String[]> data) {
        mapTitle.setText("Colunas da tabela nova (tipos descobertos nos dados; dá para mudar)");
        mapGrid.getColumnConstraints().addAll(new ColumnConstraints(28), new ColumnConstraints(180), new ColumnConstraints(170),
                new ColumnConstraints(180), new ColumnConstraints(170), new ColumnConstraints(150));
        mapGrid.addRow(0, head(""), head("Coluna do arquivo"), head("Exemplo"), head("Nome na tabela"), head("Tipo"), head(""));
        List<SheetImport.Guess> guesses = SheetImport.guess(data, header);
        List<String> names = SheetImport.columnNames(header, type, casing);
        List<String> typeChoices = typeChoices();
        for (int i = 0; i < header.length; i++) {
            SheetImport.Guess g = guesses.get(i);
            CheckBox inc = new CheckBox();
            inc.setSelected(true);
            Label name = new Label(header[i]);
            name.setMnemonicParsing(false);
            Label ex = new Label(g.sample.isEmpty() ? "(vazia)" : g.sample.length() > 26 ? g.sample.substring(0, 26) + "…" : g.sample);
            ex.getStyleClass().add("muted");
            TextField nm = new TextField(names.get(i));
            String suggested = SheetImport.typeFor(g, type);
            List<String> items = new ArrayList<>();
            items.add(suggested);
            typeChoices.stream().filter(t -> !t.equalsIgnoreCase(suggested)).forEach(items::add);
            ComboBox<String> tp = new ComboBox<>(FXCollections.observableArrayList(items));
            tp.setEditable(true);
            tp.setValue(suggested);
            tp.setMaxWidth(Double.MAX_VALUE);
            Label why = new Label(g.reason == null ? "" : "ⓘ " + (g.reason.startsWith("tem zeros") ? "zeros à esquerda" : g.reason));
            why.getStyleClass().add("help");
            if (g.reason != null) {
                why.setTooltip(new Tooltip(g.reason));
            }
            inc.selectedProperty().addListener((o, a, b) -> {
                nm.setDisable(!b);
                tp.setDisable(!b);
            });
            newInclude.add(inc);
            newNames.add(nm);
            newTypes.add(tp);
            mapGrid.addRow(i + 1, inc, name, ex, nm, tp, why);
        }
        boolean hasIdColumn = names.stream().anyMatch(n -> n.equalsIgnoreCase("id"));
        addId.setSelected(!hasIdColumn);
    }

    private List<String> typeChoices() {
        List<String> out = new ArrayList<>();
        for (TableDesign.TypeInfo t : TableDesign.typesFor(type)) {
            if (t.hasLength() && t.defaultLength() != null) {
                out.add(t.name() + "(" + t.defaultLength() + (t.hasScale() && t.defaultScale() != null ? ", " + t.defaultScale() : "") + ")");
            } else {
                out.add(t.name());
            }
        }
        return out;
    }

    private static String sample(List<String[]> data, int col) {
        for (String[] r : data) {
            if (col < r.length && !r[col].isBlank()) {
                String s = r[col];
                return s.length() > 30 ? s.substring(0, 30) + "…" : s;
            }
        }
        return "";
    }

    private void showErrors(List<String> list) {
        errors.getItems().setAll(list);
        boolean any = !list.isEmpty();
        errorsTitle.setText(any ? list.size() + (list.size() >= 200 ? "+" : "") + " problema(s) encontrado(s): nada foi importado. "
                + "Corrija o arquivo, os tipos ou a ligação das colunas." : "");
        for (Node n : List.of(errors, errorsTitle)) {
            n.setVisible(any);
            n.setManaged(any);
        }
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            getDialogPane().getScene().getWindow().sizeToScene();
        }
    }

    // =====================================================================
    // Importar
    // =====================================================================

    private boolean confirm(String header, String text, String details) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", Fx.YES, Fx.NO);
        a.initOwner(window());
        a.setTitle("H2 Studio");
        a.setHeaderText(header);
        VBox box = new VBox(8, Fx.wrapLabel(text));
        if (details != null) {
            TextArea ta = new TextArea(details);
            ta.setEditable(false);
            ta.getStyleClass().add("mono");
            ta.setPrefSize(640, 180);
            box.getChildren().add(ta);
        }
        a.getDialogPane().setContent(box);
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        a.setResizable(true);
        return a.showAndWait().filter(b -> b == Fx.YES).isPresent();
    }

    private CsvImporter.Progress progress(TaskDialog.Ctx ctx) {
        return new CsvImporter.Progress() {
            @Override
            public void rows(int done, int total) {
                ctx.status(String.format("%,d de %,d linhas…", done, total).replace(',', '.'));
                ctx.progress(total == 0 ? 1 : (double) done / total);
            }

            @Override
            public boolean cancelled() {
                return ctx.cancelled();
            }
        };
    }

    private void validateAndImport() {
        if (records.isEmpty()) {
            return;
        }
        if (isNew()) {
            importNew();
        } else {
            importExisting();
        }
    }

    private void importExisting() {
        String tableName = existingBox.getValue();
        if (tableName == null || columns.isEmpty()) {
            showErrors(List.of("Escolha a tabela de destino."));
            return;
        }
        List<Meta.ColumnInfo> map = new ArrayList<>();
        for (ComboBox<String> b : mapBoxes) {
            String v = b.getValue();
            map.add(v == null || v.equals(IGNORE) ? null : columns.stream().filter(c -> c.name().equals(v)).findFirst().orElse(null));
        }
        List<String> dup = map.stream().filter(Objects::nonNull).map(Meta.ColumnInfo::name)
                .filter(n -> map.stream().filter(c -> c != null && c.name().equals(n)).count() > 1).distinct().toList();
        if (!dup.isEmpty()) {
            showErrors(List.of("A coluna " + String.join(", ", dup) + " da tabela está ligada a mais de uma coluna do arquivo."));
            return;
        }
        List<String[]> recs = dataRecords();
        CsvImporter.Prepared prep = CsvImporter.prepare(recs, dataLines(), map, columns);
        if (!prep.errors().isEmpty()) {
            showErrors(prep.errors().stream().map(CsvImporter.RowError::toString).toList());
            return;
        }
        showErrors(List.of());
        CsvImporter.Mode m = mode.getValue();
        boolean backup = session.isH2() && AppPreferences.autoBackup();
        String text = String.format("Importar %,d linha(s) em %s?", recs.size(), tableName).replace(',', '.')
                + (m == CsvImporter.Mode.REPLACE ? "\n\nAs linhas atuais serão apagadas antes." : "")
                + (backup ? "\nUm backup do banco será feito antes." : "")
                + "\nSe qualquer linha falhar, nada é gravado.";
        if (!confirm("Confirmar importação", text, null)) {
            return;
        }
        Meta.TableInfo t = tables.stream().filter(x -> x.name().equals(tableName)).findFirst().orElseThrow();
        TaskDialog.run(window(), "Importando para " + tableName, true, ctx -> session.submit(c -> {
            if (backup && !session.isBackedUp()) {
                ctx.status("Fazendo backup do banco…");
                BackupService.backup(c, session.config(), AppPreferences.backupsToKeep());
                session.setBackedUp(true);
            }
            return CsvImporter.run(c, type, t.schema(), t.name(), map, prep, m, progress(ctx));
        }).get(), r -> {
            setResult(new Outcome(t.schema(), List.of(), t.name(), r.inserted(), r.deleted()));
            close();
        }, this::failed);
    }

    private void failed(Throwable e) {
        if (e instanceof CsvImporter.CancelledException) {
            showErrors(List.of("Importação cancelada: nada foi gravado."));
        } else {
            showErrors(List.of("Nada foi gravado. " + (e instanceof java.sql.SQLException ? e.getMessage() : Fx.shortMessage(e))));
        }
    }

    /** Uma tabela nova a criar: nome, colunas (com a de id, se houver) e de onde vem cada uma. */
    private record NewTable(String name, List<TableDesign.Column> columns, List<String> fileColumnTarget,
                            DdlBuilder.Plan plan) {
    }

    private NewTable newTableFromGrid(List<String> problems) {
        String name = SheetImport.tableName(newName.getText(), type, casing); // "Clientes 2024" → clientes_2024
        List<TableDesign.Column> cols = new ArrayList<>();
        List<String> target = new ArrayList<>();
        if (addId.isSelected()) {
            cols.add(SheetImport.idColumn(type, casing));
        }
        for (int i = 0; i < newInclude.size(); i++) {
            if (!newInclude.get(i).isSelected()) {
                target.add(null);
                continue;
            }
            String n = SheetImport.columnName(newNames.get(i).getText(), i, type, casing);
            String t = newTypes.get(i).getEditor().getText();
            if (t == null || t.isBlank()) {
                t = newTypes.get(i).getValue();
            }
            cols.add(SheetImport.column(n, t));
            target.add(n);
        }
        if (target.stream().allMatch(Objects::isNull)) {
            problems.add("Marque pelo menos uma coluna do arquivo.");
            return null;
        }
        Set<String> existing = new HashSet<>();
        tables.forEach(x -> existing.add(x.name()));
        DdlBuilder.Plan plan = SheetImport.createPlan(schema, defaultSchema, name, cols, type, casing, existing);
        problems.addAll(plan.errors());
        return new NewTable(name, cols, target, plan);
    }

    private void importNew() {
        List<String> problems = new ArrayList<>();
        NewTable nt = newTableFromGrid(problems);
        if (!problems.isEmpty() || nt == null) {
            showErrors(problems);
            return;
        }
        showErrors(List.of());
        List<String[]> recs = dataRecords();
        int[] lns = dataLines();
        String text = String.format("Criar a tabela %s e importar %,d linha(s)?", nt.name(), recs.size()).replace(',', '.')
                + "\nTudo é conferido antes de gravar; se alguma linha não servir, a tabela nova é apagada e nada fica pela metade.";
        if (!confirm("Criar tabela e importar", text, nt.plan().script(type))) {
            return;
        }
        String sch = schema == null ? "" : schema;
        TaskDialog.run(window(), "Importando para " + nt.name(), true,
                ctx -> session.submit(c -> createAndImport(c, sch, nt, recs, lns, progress(ctx))).get(), out -> {
                    if (out instanceof Outcome o) {
                        setResult(o);
                        close();
                    } else {
                        @SuppressWarnings("unchecked") List<String> errs = (List<String>) out;
                        showErrors(errs);
                    }
                }, this::failed);
    }

    /** Cria a tabela, confere as linhas e importa; se algo der errado, apaga a tabela criada. */
    private Object createAndImport(Connection c, String sch, NewTable nt, List<String[]> recs, int[] lns,
                                   CsvImporter.Progress progress) throws Exception {
        SheetImport.NewTableResult r = SheetImport.createAndImport(c, type, sch, nt.name(), nt.plan(),
                nt.fileColumnTarget(), recs, lns, progress);
        if (!r.ok()) {
            return r.errors();
        }
        return new Outcome(sch, List.of(r.table()), r.table(), r.result().inserted(), 0);
    }

    // =====================================================================
    // Todas as abas
    // =====================================================================

    private void importAllSheets() {
        List<String> sheets = sheetBox.getItems();
        if (file == null || sheets.size() < 2) {
            return;
        }
        Set<String> existing = new HashSet<>();
        tables.forEach(x -> existing.add(x.name().toUpperCase(Locale.ROOT)));
        List<String> plan = new ArrayList<>();
        Set<String> used = new HashSet<>(existing);
        List<String[]> pairs = new ArrayList<>();
        for (String s : sheets) {
            String base = SheetImport.tableName(s, type, casing);
            String n = base;
            for (int k = 2; used.contains(n.toUpperCase(Locale.ROOT)); k++) {
                n = base + "_" + k;
            }
            used.add(n.toUpperCase(Locale.ROOT));
            pairs.add(new String[]{s, n});
            plan.add("aba \"" + s + "\" → tabela " + n);
        }
        boolean header = hasHeader.isSelected();
        boolean id = addId.isSelected();
        if (!confirm("Importar todas as abas", "Cada aba vira uma tabela nova, com os tipos descobertos nos dados"
                + (header ? " e a primeira linha como cabeçalho" : "") + (id ? " e uma coluna id" : "") + ".\n"
                + "Abas vazias são puladas. Uma aba com problema não impede as outras.", String.join("\n", plan))) {
            return;
        }
        String sch = schema == null ? "" : schema;
        Path f = file;
        TaskDialog.run(window(), "Importando " + sheets.size() + " abas", true, ctx -> session.submit(c -> {
            List<String> report = new ArrayList<>();
            List<String> created = new ArrayList<>();
            int total = 0;
            for (String[] p : pairs) {
                if (ctx.cancelled()) {
                    report.add("Cancelado antes da aba \"" + p[0] + "\".");
                    break;
                }
                ctx.status("Aba \"" + p[0] + "\"…");
                XlsxReader.Sheet s = XlsxReader.read(f, p[0]);
                List<String[]> recs = s.records().subList(Math.min(header ? 1 : 0, s.records().size()), s.records().size());
                if (recs.isEmpty()) {
                    report.add("– " + p[0] + ": vazia, pulada");
                    continue;
                }
                int n = s.records().stream().mapToInt(r -> r.length).max().orElse(0);
                String[] h = new String[n];
                String[] first = header ? s.records().get(0) : new String[0];
                for (int i = 0; i < n; i++) {
                    h[i] = i < first.length && !first[i].isBlank() ? first[i].trim() : "Coluna " + (i + 1);
                }
                List<SheetImport.Guess> guesses = SheetImport.guess(recs, h);
                List<String> names = SheetImport.columnNames(h, type, casing);
                List<TableDesign.Column> cols = new ArrayList<>();
                boolean hasId = names.stream().anyMatch(x -> x.equalsIgnoreCase("id"));
                if (id && !hasId) {
                    cols.add(SheetImport.idColumn(type, casing));
                }
                for (int i = 0; i < n; i++) {
                    cols.add(SheetImport.column(names.get(i), SheetImport.typeFor(guesses.get(i), type)));
                }
                DdlBuilder.Plan pl = SheetImport.createPlan(sch, defaultSchema, p[1], cols, type, casing, Set.of());
                if (!pl.errors().isEmpty()) {
                    report.add("✕ " + p[0] + ": " + String.join(" ", pl.errors()));
                    continue;
                }
                int[] lns = Arrays.copyOfRange(s.lines(), Math.min(header ? 1 : 0, s.lines().length), s.lines().length);
                try {
                    Object out = createAndImport(c, sch, new NewTable(p[1], cols, names, pl), recs, lns, progress(ctx));
                    if (out instanceof Outcome o) {
                        created.add(o.table());
                        total += o.inserted();
                        report.add(String.format("✓ %s → %s: %,d linha(s)", p[0], o.table(), o.inserted()).replace(',', '.'));
                    } else {
                        @SuppressWarnings("unchecked") List<String> errs = (List<String>) out;
                        report.add("✕ " + p[0] + ": " + errs.size() + " problema(s), nada importado. Primeiro: " + errs.get(0));
                    }
                } catch (Exception e) {
                    report.add("✕ " + p[0] + ": " + Fx.shortMessage(e));
                }
            }
            return new Object[]{report, created, total};
        }).get(), r -> {
            @SuppressWarnings("unchecked") List<String> report = (List<String>) r[0];
            @SuppressWarnings("unchecked") List<String> created = (List<String>) r[1];
            Fx.info(window(), created.size() + " tabela(s) criada(s)", String.join("\n", report));
            if (!created.isEmpty()) {
                setResult(new Outcome(sch, created, created.get(0), (Integer) r[2], 0));
                close();
            }
        }, this::failed);
    }
}
