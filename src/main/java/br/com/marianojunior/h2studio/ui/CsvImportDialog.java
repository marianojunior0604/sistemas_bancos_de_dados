package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.AppPreferences;
import br.com.marianojunior.h2studio.core.BackupService;
import br.com.marianojunior.h2studio.core.CsvImporter;
import br.com.marianojunior.h2studio.core.CsvReader;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.Meta;
import br.com.marianojunior.h2studio.core.MetadataService;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Importa um CSV para uma tabela: escolhe o arquivo, confere o separador/codificação,
 * liga as colunas do arquivo às da tabela, valida tudo e só então grava (numa transação).
 */
public class CsvImportDialog extends Dialog<CsvImporter.Result> {

    private static final String IGNORE = "(ignorar)";
    private static final String AUTO = "Automático";

    private final DbSession session;
    private final Meta.TableInfo table;
    private List<Meta.ColumnInfo> columns = List.of();
    private List<Meta.ForeignKeyInfo> incoming = List.of();
    private CsvReader.Data data;
    private final List<ComboBox<String>> mapBoxes = new ArrayList<>();

    private final TextField fileField = new TextField();
    private final ComboBox<String> charsetBox = new ComboBox<>(FXCollections.observableArrayList(AUTO, "UTF-8", "Windows-1252 (Excel antigo)"));
    private final ComboBox<String> sepBox = new ComboBox<>(FXCollections.observableArrayList(AUTO, "; (ponto e vírgula)", ", (vírgula)", "TAB"));
    private final CheckBox hasHeader = new CheckBox("Primeira linha é o cabeçalho");
    private final Label info = new Label("Escolha o arquivo CSV.");
    private final GridPane mapGrid = new GridPane();
    private final ComboBox<CsvImporter.Mode> mode = new ComboBox<>(FXCollections.observableArrayList(CsvImporter.Mode.values()));
    private final Label modeWarn = new Label();
    private final TableView<String[]> preview = new TableView<>();
    private final ListView<String> errors = new ListView<>();
    private final Label errorsTitle = new Label();
    private final Button importBtn;

    public CsvImportDialog(Window owner, DbSession session, Meta.TableInfo table) {
        this.session = session;
        this.table = table;
        initOwner(owner);
        setTitle("Importar CSV");
        setHeaderText("Importar CSV para " + table.schema() + "." + table.name());
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        ButtonType imp = new ButtonType("Validar e importar", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(imp, Fx.CANCEL);
        importBtn = (Button) getDialogPane().lookupButton(imp);
        importBtn.setDisable(true);
        importBtn.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume(); // o diálogo só fecha depois de importar
            validateAndImport();
        });

        fileField.setEditable(false);
        fileField.setPromptText("arquivo .csv");
        Button choose = new Button("Escolher arquivo…");
        choose.setOnAction(e -> chooseFile());
        HBox fileRow = new HBox(6, fileField, choose);
        HBox.setHgrow(fileField, Priority.ALWAYS);
        charsetBox.setValue(AUTO);
        sepBox.setValue(AUTO);
        hasHeader.setSelected(true);
        charsetBox.setOnAction(e -> reread());
        sepBox.setOnAction(e -> reread());
        hasHeader.setOnAction(e -> rebuild());
        javafx.scene.layout.FlowPane opts = new javafx.scene.layout.FlowPane(10, 6, new Label("Codificação:"), charsetBox,
                new Label("Separador:"), sepBox, hasHeader);
        opts.setAlignment(Pos.CENTER_LEFT);
        for (Node n : opts.getChildren()) {
            ((javafx.scene.control.Control) n).setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        info.getStyleClass().add("muted");

        mapGrid.setHgap(8);
        mapGrid.setVgap(4);
        mapGrid.getColumnConstraints().addAll(new ColumnConstraints(190), new ColumnConstraints(210), new ColumnConstraints(210));
        ScrollPane mapScroll = new ScrollPane(mapGrid);
        mapScroll.setFitToWidth(true);
        mapScroll.setPrefHeight(180);
        Label mapTitle = new Label("Ligação das colunas (arquivo → tabela)");
        mapTitle.getStyleClass().add("section-title");

        mode.setValue(CsvImporter.Mode.APPEND);
        mode.setOnAction(e -> updateModeWarning());
        modeWarn.setWrapText(true);
        modeWarn.setMinHeight(Label.USE_PREF_SIZE);
        modeWarn.getStyleClass().add("text-warn");
        HBox modeRow = new HBox(10, new Label("Modo:"), mode);
        modeRow.setAlignment(Pos.CENTER_LEFT);

        Label prevTitle = new Label("Prévia (primeiras 20 linhas)");
        prevTitle.getStyleClass().add("section-title");
        preview.setPrefHeight(150);
        preview.setPlaceholder(new Label("Nenhum arquivo carregado."));

        errorsTitle.getStyleClass().addAll("section-title", "text-error");
        errors.setPrefHeight(110);
        errors.getStyleClass().add("mono");
        showErrors(List.of());

        VBox box = new VBox(8, fileRow, opts, info, mapTitle, mapScroll, modeRow, modeWarn, prevTitle, preview, errorsTitle, errors);
        box.setPadding(new Insets(6, 4, 0, 4));
        box.setPrefWidth(760);
        getDialogPane().setContent(box);

        Fx.then(session.submit(c -> new Object[]{
            MetadataService.columns(c, table.schema(), table.name()),
            MetadataService.foreignKeys(c, table.schema(), table.name()).stream().filter(Meta.ForeignKeyInfo::incoming).toList()}),
                r -> {
                    @SuppressWarnings("unchecked") List<Meta.ColumnInfo> cols = (List<Meta.ColumnInfo>) r[0];
                    @SuppressWarnings("unchecked") List<Meta.ForeignKeyInfo> inc = (List<Meta.ForeignKeyInfo>) r[1];
                    columns = cols;
                    incoming = inc;
                    updateModeWarning();
                    rebuild();
                }, e -> info.setText("Erro ao ler a tabela: " + Fx.shortMessage(e)));
        Platform.runLater(this::chooseFile);
    }

    private void chooseFile() {
        File f = Fx.chooseOpen(getOwner(), "Escolher arquivo CSV", "importar.pasta",
                new FileChooser.ExtensionFilter("CSV e texto (*.csv, *.txt)", "*.csv", "*.txt"),
                new FileChooser.ExtensionFilter("Todos os arquivos", "*.*"));
        if (f != null) {
            fileField.setText(f.getAbsolutePath());
            reread();
        }
    }

    private void reread() {
        if (fileField.getText().isBlank()) {
            return;
        }
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
        try {
            data = CsvReader.read(Path.of(fileField.getText()), cs, sep);
        } catch (Exception e) {
            data = null;
            info.setText("Não foi possível ler o arquivo: " + e.getMessage());
            rebuild();
            return;
        }
        rebuild();
    }

    private int dataRows() {
        return data == null ? 0 : Math.max(0, data.records().size() - (hasHeader.isSelected() ? 1 : 0));
    }

    private String[] header() {
        int n = data.records().stream().mapToInt(r -> r.length).max().orElse(0);
        if (hasHeader.isSelected() && !data.records().isEmpty()) {
            String[] h = data.records().get(0);
            return h.length >= n ? h : Arrays.copyOf(h, Math.max(h.length, 1));
        }
        String[] h = new String[n];
        for (int i = 0; i < n; i++) {
            h[i] = "Coluna " + (i + 1);
        }
        return h;
    }

    private void rebuild() {
        mapGrid.getChildren().clear();
        mapBoxes.clear();
        preview.getColumns().clear();
        preview.getItems().clear();
        showErrors(List.of());
        if (data == null || data.records().isEmpty() || columns.isEmpty()) {
            importBtn.setDisable(true);
            if (data != null && data.records().isEmpty()) {
                info.setText("O arquivo está vazio.");
            }
            return;
        }
        String sepName = data.separator() == ';' ? "ponto e vírgula" : data.separator() == ',' ? "vírgula" : "TAB";
        info.setText(String.format("%,d linha(s) de dados · %s · separador: %s", dataRows(),
                data.charset().name().equals("UTF-8") ? "UTF-8" : "Windows-1252", sepName).replace(',', '.'));
        String[] header = header();
        List<Meta.ColumnInfo> auto = CsvImporter.autoMap(header, columns, hasHeader.isSelected());
        List<String> choices = new ArrayList<>();
        choices.add(IGNORE);
        for (Meta.ColumnInfo c : columns) {
            if (!c.generated()) {
                choices.add(c.name());
            }
        }
        String[] sample = data.records().size() > (hasHeader.isSelected() ? 1 : 0)
                ? data.records().get(hasHeader.isSelected() ? 1 : 0) : new String[0];
        Label h1 = new Label("Coluna do arquivo");
        Label h2 = new Label("Exemplo");
        Label h3 = new Label("Coluna da tabela");
        for (Label l : List.of(h1, h2, h3)) {
            l.getStyleClass().add("grid-header");
        }
        mapGrid.addRow(0, h1, h2, h3);
        for (int i = 0; i < header.length; i++) {
            Label name = new Label(header[i]);
            name.setMnemonicParsing(false);
            String ex = i < sample.length ? sample[i] : "";
            Label exLbl = new Label(ex.length() > 30 ? ex.substring(0, 30) + "…" : ex);
            exLbl.getStyleClass().add("muted");
            ComboBox<String> target = new ComboBox<>(FXCollections.observableArrayList(choices));
            target.setValue(auto.get(i) == null ? IGNORE : auto.get(i).name());
            target.setMaxWidth(Double.MAX_VALUE);
            target.setOnAction(e -> showErrors(List.of()));
            mapBoxes.add(target);
            mapGrid.addRow(i + 1, name, exLbl, target);
        }
        for (int i = 0; i < header.length; i++) {
            final int idx = i;
            TableColumn<String[], String> tc = new TableColumn<>(header[i]);
            tc.setCellValueFactory(cd -> new ReadOnlyStringWrapper(idx < cd.getValue().length ? cd.getValue()[idx] : ""));
            tc.setPrefWidth(110);
            preview.getColumns().add(tc);
        }
        int start = hasHeader.isSelected() ? 1 : 0;
        preview.getItems().setAll(data.records().subList(Math.min(start, data.records().size()),
                Math.min(start + 20, data.records().size())));
        importBtn.setDisable(dataRows() == 0);
    }

    private void updateModeWarning() {
        if (mode.getValue() == CsvImporter.Mode.REPLACE) {
            String w = "Todas as linhas atuais de " + table.name() + " serão APAGADAS antes de importar.";
            List<String> cascade = incoming.stream().filter(f -> f.onDelete().equals("CASCADE"))
                    .map(f -> f.refTable()).distinct().toList();
            if (!cascade.isEmpty()) {
                w += " ATENÇÃO: " + String.join(", ", cascade) + " usa(m) esta tabela com ON DELETE CASCADE: "
                        + "as linhas ligadas nessas tabelas também serão apagadas!";
            } else if (!incoming.isEmpty()) {
                w += " Se outras tabelas usarem estas linhas, a importação será recusada.";
            }
            modeWarn.setText(w);
        } else {
            modeWarn.setText("");
        }
        modeWarn.setVisible(!modeWarn.getText().isEmpty());
        modeWarn.setManaged(modeWarn.isVisible());
    }

    private void showErrors(List<String> list) {
        errors.getItems().setAll(list);
        boolean any = !list.isEmpty();
        errorsTitle.setText(any ? list.size() + (list.size() >= 200 ? "+" : "") + " problema(s) encontrado(s): nada foi importado. Corrija o arquivo ou a ligação das colunas." : "");
        for (Node n : List.of(errors, errorsTitle)) {
            n.setVisible(any);
            n.setManaged(any);
        }
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            getDialogPane().getScene().getWindow().sizeToScene();
        }
    }

    private List<Meta.ColumnInfo> mapping() {
        List<Meta.ColumnInfo> m = new ArrayList<>();
        for (ComboBox<String> b : mapBoxes) {
            String v = b.getValue();
            m.add(v == null || v.equals(IGNORE) ? null : columns.stream().filter(c -> c.name().equals(v)).findFirst().orElse(null));
        }
        return m;
    }

    private void validateAndImport() {
        if (data == null) {
            return;
        }
        List<Meta.ColumnInfo> map = mapping();
        // a mesma coluna da tabela ligada a duas colunas do arquivo
        List<String> dup = map.stream().filter(Objects::nonNull).map(Meta.ColumnInfo::name)
                .filter(n -> map.stream().filter(c -> c != null && c.name().equals(n)).count() > 1).distinct().toList();
        if (!dup.isEmpty()) {
            showErrors(List.of("A coluna " + String.join(", ", dup) + " da tabela está ligada a mais de uma coluna do arquivo."));
            return;
        }
        int start = hasHeader.isSelected() ? 1 : 0;
        List<String[]> recs = data.records().subList(start, data.records().size());
        int[] lines = Arrays.copyOfRange(data.lines(), start, data.lines().length);
        CsvImporter.Prepared prep = CsvImporter.prepare(recs, lines, map, columns);
        if (!prep.errors().isEmpty()) {
            showErrors(prep.errors().stream().map(CsvImporter.RowError::toString).toList());
            return;
        }
        showErrors(List.of());
        CsvImporter.Mode m = mode.getValue();
        String confirm = String.format("Importar %,d linha(s) em %s?", recs.size(), table.name()).replace(',', '.')
                + (m == CsvImporter.Mode.REPLACE ? "\n\nAs linhas atuais serão apagadas antes." : "")
                + (AppPreferences.autoBackup() ? "\nUm backup do banco será feito antes." : "")
                + "\nSe qualquer linha falhar, nada é gravado.";
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION, "", Fx.YES, Fx.NO);
        a.initOwner(getDialogPane().getScene().getWindow());
        a.setTitle("H2 Studio");
        a.setHeaderText("Confirmar importação");
        a.getDialogPane().setContent(Fx.wrapLabel(confirm));
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        if (a.showAndWait().filter(b -> b == Fx.YES).isEmpty()) {
            return;
        }
        Window w = getDialogPane().getScene().getWindow();
        TaskDialog.run(w, "Importando para " + table.name(), true, ctx -> session.submit(c -> {
            if (AppPreferences.autoBackup() && !session.isBackedUp()) {
                ctx.status("Fazendo backup do banco…");
                BackupService.backup(c, session.config(), AppPreferences.backupsToKeep());
                session.setBackedUp(true);
            }
            return CsvImporter.run(c, table.schema(), table.name(), map, prep, m, new CsvImporter.Progress() {
                @Override
                public void rows(int done, int total) {
                    ctx.status(String.format("%,d de %,d linhas…", done, total).replace(',', '.'));
                    ctx.progress(total == 0 ? 1 : (double) done / total);
                }

                @Override
                public boolean cancelled() {
                    return ctx.cancelled();
                }
            });
        }).get(), r -> {
            setResult(r);
            close();
        }, e -> {
            if (e instanceof CsvImporter.CancelledException) {
                showErrors(List.of("Importação cancelada: nada foi gravado."));
            } else {
                showErrors(List.of("Nada foi gravado. " + (e instanceof java.sql.SQLException ? e.getMessage() : Fx.shortMessage(e))));
            }
        });
    }
}
