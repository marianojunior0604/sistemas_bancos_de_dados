package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.DriverRegistry;
import br.com.marianojunior.h2studio.core.H2FileInspector;
import br.com.marianojunior.h2studio.core.MaintenanceService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Converte o banco para o formato de uma versão mais nova do H2 (ex.: 1.4 → 2.3). */
public class ConvertDialog extends Dialog<ConvertDialog.Request> {

    /** O que o usuário escolheu. {@code replace} = trocar o original pelo convertido. */
    public record Request(DriverRegistry.DriverJar target, Path dest, boolean replace, boolean openAfter) {
    }

    public ConvertDialog(Window owner, DbSession session) {
        initOwner(owner);
        setTitle("Converter versão do banco");
        setHeaderText("Converter " + session.displayName() + " para outra versão do H2");
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        ButtonType go = new ButtonType("Converter", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(go, Fx.CANCEL);

        DriverRegistry.DriverJar src = session.driver();
        List<DriverRegistry.DriverJar> targets = DriverRegistry.get().list().stream()
                .filter(j -> j.family().ordinal() > src.family().ordinal()).toList();
        Label current = new Label("Arquivo: " + session.config().getFilePath() + "\nFormato atual: " + src.family().label()
                + " (driver " + src.version() + ")");
        current.setWrapText(true);
        current.setMinHeight(Label.USE_PREF_SIZE);
        VBox box = new VBox(10, current);
        box.setPadding(new Insets(6, 4, 0, 4));
        box.setPrefWidth(640);
        getDialogPane().setContent(box);
        if (targets.isEmpty()) {
            Label none = new Label("Este banco já está no formato mais novo dos drivers instalados ("
                    + src.family().label() + "). Nada a converter.");
            none.setWrapText(true);
            box.getChildren().add(none);
            getDialogPane().lookupButton(go).setDisable(true);
            return;
        }
        ComboBox<DriverRegistry.DriverJar> target = new ComboBox<>(FXCollections.observableArrayList(targets));
        target.setCellFactory(v -> new JarCell());
        target.setButtonCell(new JarCell());
        target.setValue(targets.get(0));
        HBox targetRow = new HBox(8, new Label("Converter para:"), target);
        targetRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        TextField dest = new TextField(MaintenanceService.suggestTarget(session.config().file(), targets.get(0)).toString());
        Button browse = new Button("…");
        browse.setOnAction(e -> {
            File f = Fx.chooseSave(getDialogPane().getScene().getWindow(), "Arquivo convertido",
                    Path.of(dest.getText()).getFileName().toString(), null,
                    new FileChooser.ExtensionFilter("Banco H2 (*.mv.db)", "*.mv.db"));
            if (f != null) {
                String p = f.getAbsolutePath();
                dest.setText(p.toLowerCase().endsWith(".mv.db") ? p : p + ".mv.db");
            }
        });
        HBox destRow = new HBox(6, dest, browse);
        HBox.setHgrow(dest, Priority.ALWAYS);

        ToggleGroup how = new ToggleGroup();
        RadioButton newFile = new RadioButton("Criar um arquivo novo (o original não é alterado)");
        RadioButton replace = new RadioButton("Substituir o original (o original vai para os backups)");
        newFile.setToggleGroup(how);
        replace.setToggleGroup(how);
        newFile.setSelected(true);
        replace.setDisable(session.isCopy());
        destRow.disableProperty().bind(replace.selectedProperty());
        CheckBox open = new CheckBox("Abrir o banco convertido ao terminar");
        open.setSelected(true);

        Label warn = new Label();
        warn.setWrapText(true);
        warn.getStyleClass().addAll("banner", "banner-info");
        warn.setMaxWidth(Double.MAX_VALUE);
        warn.setMinHeight(Label.USE_PREF_SIZE);
        Runnable updateWarn = () -> {
            DriverRegistry.DriverJar t = target.getValue();
            warn.setText("Depois de converter, o sistema que usa este banco precisa usar o H2 " + t.major() + "." + t.minor()
                    + " (ex.: com.h2database:h2:" + t.version() + " no pom.xml). Com o H2 " + src.version()
                    + " ele NÃO abre o arquivo convertido. Atualize o sistema antes de trocar o arquivo no cliente."
                    + (replace.isSelected() ? "\n\nPara substituir o original, o sistema precisa estar fechado; "
                    + "as abas deste banco serão fechadas e o banco reaberto já convertido." : ""));
        };
        target.setOnAction(e -> {
            dest.setText(MaintenanceService.suggestTarget(session.config().file(), target.getValue()).toString());
            updateWarn.run();
        });
        how.selectedToggleProperty().addListener((o, a, b) -> {
            updateWarn.run();
            javafx.application.Platform.runLater(() -> {
                if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
                    getDialogPane().getScene().getWindow().sizeToScene();
                }
            });
        });
        getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        updateWarn.run();
        Label process = new Label("Como funciona: o H2 " + src.version() + " exporta tudo em SQL (SCRIPT), o driver novo "
                + "cria o banco e executa o script, e o H2 Studio confere a quantidade de linhas de cada tabela.");
        process.setWrapText(true);
        process.getStyleClass().add("muted");
        process.setMinHeight(Label.USE_PREF_SIZE);

        box.getChildren().addAll(targetRow, newFile, destRow, replace, open, warn, process);
        getDialogPane().lookupButton(go).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            Path d = Path.of(dest.getText().trim());
            if (!replace.isSelected() && Files.exists(d)) {
                e.consume();
                Fx.error(getDialogPane().getScene().getWindow(), "O arquivo já existe",
                        d + "\n\nEscolha outro nome ou apague o arquivo.", null);
            }
        });
        setResultConverter(bt -> {
            if (bt != go) {
                return null;
            }
            DriverRegistry.DriverJar t = target.getValue();
            Path d = replace.isSelected()
                    ? Path.of(H2FileInspector.basePath(session.config().file()) + "_convertendo" + H2FileInspector.MV_EXT)
                    : Path.of(dest.getText().trim());
            return new Request(t, d, replace.isSelected(), open.isSelected());
        });
    }

    private static final class JarCell extends ListCell<DriverRegistry.DriverJar> {
        @Override
        protected void updateItem(DriverRegistry.DriverJar j, boolean empty) {
            super.updateItem(j, empty);
            setText(empty || j == null ? null : "H2 " + j.version() + " — " + j.family().label());
        }
    }
}
