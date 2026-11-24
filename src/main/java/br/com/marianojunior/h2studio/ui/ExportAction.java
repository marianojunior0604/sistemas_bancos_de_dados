package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DataExporter;
import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.TableDataService;
import java.io.File;
import java.nio.file.Path;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Exportar dados para Excel (.xlsx) ou CSV: escolhe as opções, o arquivo e grava em segundo plano. */
public final class ExportAction {

    private ExportAction() {
    }

    /**
     * @param filtered consulta com o filtro atual da grade (null quando não há filtro)
     * @param all      consulta de tudo
     */
    public static void show(Window owner, DbSession session, String title, String baseName,
                            TableDataService.Query filtered, TableDataService.Query all) {
        Dialog<ButtonType> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle("Exportar");
        d.setHeaderText("Exportar " + title);
        d.getDialogPane().getStylesheets().add(MainWindow.CSS);
        ButtonType go = new ButtonType("Escolher arquivo e exportar…", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(go, Fx.CANCEL);

        ToggleGroup fmt = new ToggleGroup();
        RadioButton xlsx = new RadioButton("Planilha do Excel (.xlsx): números, datas e filtro prontos");
        RadioButton csv = new RadioButton("CSV (texto): para importar em outro sistema ou banco");
        xlsx.setToggleGroup(fmt);
        csv.setToggleGroup(fmt);
        boolean lastCsv = "CSV".equals(br.com.marianojunior.h2studio.core.AppPreferences.get("exportar.formato", "XLSX"));
        (lastCsv ? csv : xlsx).setSelected(true);
        ComboBox<DataExporter.CsvStyle> style = new ComboBox<>(FXCollections.observableArrayList(DataExporter.CsvStyle.values()));
        style.setValue(DataExporter.CsvStyle.EXCEL_BR);
        style.disableProperty().bind(csv.selectedProperty().not());
        HBox csvRow = new HBox(8, new Label("     Formato do CSV:"), style);
        CheckBox header = new CheckBox("Incluir cabeçalho com os nomes das colunas");
        header.setSelected(true);
        CheckBox onlyFiltered = new CheckBox("Somente as linhas do filtro/busca atual");
        onlyFiltered.setSelected(filtered != null);
        onlyFiltered.setVisible(filtered != null);
        onlyFiltered.setManaged(filtered != null);
        VBox box = new VBox(8, xlsx, csv, csvRow, header, onlyFiltered);
        box.setPadding(new Insets(8, 4, 4, 4));
        d.getDialogPane().setContent(box);
        if (d.showAndWait().filter(b -> b == go).isEmpty()) {
            return;
        }
        DataExporter.Format format = xlsx.isSelected() ? DataExporter.Format.XLSX : DataExporter.Format.CSV;
        br.com.marianojunior.h2studio.core.AppPreferences.set("exportar.formato", format.name());
        File f = Fx.chooseSave(owner, "Salvar exportação", DataExporter.fileName(baseName, format), "exportar.pasta",
                format == DataExporter.Format.XLSX
                        ? new FileChooser.ExtensionFilter("Planilha do Excel (*.xlsx)", "*.xlsx")
                        : new FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
        if (f == null) {
            return;
        }
        Path out = f.toPath();
        String ext = format == DataExporter.Format.XLSX ? ".xlsx" : ".csv";
        String name = out.getFileName().toString();
        if (!name.toLowerCase().endsWith(ext)) {
            // digitou a extensão do outro formato: troca em vez de acrescentar (x.xlsx.csv)
            name = name.replaceFirst("(?i)\\.(xlsx|csv|xls|txt)$", "") + ext;
            out = out.resolveSibling(name);
        }
        TableDataService.Query q = onlyFiltered.isSelected() && filtered != null ? filtered : all;
        DataExporter.Options opt = new DataExporter.Options(format, style.getValue(), header.isSelected(), baseName);
        run(owner, session, q, out, opt);
    }

    public static void run(Window owner, DbSession session, TableDataService.Query q, Path out, DataExporter.Options opt) {
        TaskDialog.run(owner, "Exportando para " + out.getFileName(), true, ctx -> session.submit(c ->
                DataExporter.export(c, q.sql(), q.params(), out, opt, new DataExporter.Progress() {
                    @Override
                    public void rows(long count) {
                        ctx.status(String.format("%,d linhas gravadas…", count).replace(',', '.'));
                    }

                    @Override
                    public boolean cancelled() {
                        return ctx.cancelled();
                    }
                })).get(), r -> {
                    String msg = String.format("%,d linha(s) exportada(s) para:\n%s", r.rows(), out).replace(',', '.');
                    if (!r.warnings().isEmpty()) {
                        msg += "\n\n⚠ " + String.join("\n⚠ ", r.warnings());
                    }
                    Fx.infoWithFile(owner, "Exportação concluída", msg, out);
                }, e -> {
                    if (e instanceof DataExporter.CancelledException) {
                        Fx.info(owner, "Exportação cancelada", "Nenhum arquivo foi gravado.");
                    } else {
                        Fx.error(owner, "Não foi possível exportar", Fx.shortMessage(e), e);
                    }
                });
    }

    /** Consulta de uma tabela inteira (sem filtro). */
    public static TableDataService.Query allOf(String schema, String table) {
        return TableDataService.exportQuery(new TableDataService.PageRequest(schema, table, null, null, null, null,
                true, 0, 0));
    }

    /** Consulta de uma tabela inteira no dialeto do banco. */
    public static TableDataService.Query allOf(br.com.marianojunior.h2studio.core.DbType type, String schema, String table) {
        return TableDataService.exportQuery(new TableDataService.PageRequest(schema, table, null, null, null, null,
                true, 0, 0).withType(type));
    }
}
