package br.com.marianojunior.h2studio.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Editor de textos longos ou com várias linhas (CLOB, observações...). */
public class TextEditDialog extends Dialog<String> {

    /** Resultado especial: o usuário escolheu NULL. */
    public static final String NULL_CHOSEN = new String("\u0000NULL");

    public TextEditDialog(Window owner, String title, String text, boolean editable, boolean nullable, int maxLength) {
        initOwner(owner);
        setTitle(title);
        setHeaderText(null);
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);

        TextArea area = new TextArea(text == null ? "" : text);
        area.setWrapText(true);
        area.setEditable(editable);
        area.getStyleClass().add("mono");
        area.setPrefSize(720, 420);
        CheckBox wrap = new CheckBox("Quebrar linhas");
        wrap.setSelected(true);
        area.wrapTextProperty().bind(wrap.selectedProperty());
        Label count = new Label();
        count.getStyleClass().add("muted");
        Runnable upd = () -> {
            int n = area.getText().length();
            count.setText(n + " caractere(s)" + (maxLength > 0 && maxLength < Integer.MAX_VALUE ? " de " + maxLength : ""));
            count.getStyleClass().remove("text-error");
            if (maxLength > 0 && n > maxLength) {
                count.getStyleClass().add("text-error");
            }
        };
        area.textProperty().addListener((o, a, b) -> upd.run());
        upd.run();
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bar = new HBox(10, wrap, sp, count);
        VBox box = new VBox(8, area, bar);
        VBox.setVgrow(area, Priority.ALWAYS);
        box.setPadding(new Insets(4, 0, 0, 0));
        getDialogPane().setContent(box);

        ButtonType ok = new ButtonType(editable ? "Aplicar" : "Fechar", ButtonBar.ButtonData.OK_DONE);
        ButtonType setNull = new ButtonType("Definir NULL", ButtonBar.ButtonData.LEFT);
        getDialogPane().getButtonTypes().add(ok);
        if (editable && nullable) {
            getDialogPane().getButtonTypes().add(setNull);
        }
        if (editable) {
            getDialogPane().getButtonTypes().add(Fx.CANCEL);
            getDialogPane().lookupButton(ok).disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                    () -> maxLength > 0 && area.getText().length() > maxLength, area.textProperty()));
        }
        setResultConverter(bt -> {
            if (!editable) {
                return null;
            }
            if (bt == ok) {
                return area.getText();
            }
            if (bt == setNull) {
                return NULL_CHOSEN;
            }
            return null;
        });
        Platform.runLater(area::requestFocus);
    }
}
