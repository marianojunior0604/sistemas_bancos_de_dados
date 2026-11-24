package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.ConnectionFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Utilitários de interface: callbacks na thread do JavaFX e diálogos padrão. */
public final class Fx {

    // Botões com texto em português (os padrões do JavaFX seguem o idioma do sistema)
    public static final ButtonType OK = new ButtonType("OK", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
    public static final ButtonType CANCEL = new ButtonType("Cancelar", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
    public static final ButtonType YES = new ButtonType("Sim", javafx.scene.control.ButtonBar.ButtonData.YES);
    public static final ButtonType NO = new ButtonType("Não", javafx.scene.control.ButtonBar.ButtonData.NO);
    public static final ButtonType CLOSE = new ButtonType("Fechar", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);

    /** Serviços do JavaFX (abrir arquivo/pasta no sistema). Definido pela janela principal. */
    public static javafx.application.HostServices hostServices;

    /** Só para testes automáticos: responde às janelas de escolher arquivo (tipo "abrir"/"salvar", nome sugerido). */
    public static java.util.function.BiFunction<String, String, java.io.File> testFileChooser;

    private Fx() {
    }

    public static java.io.File chooseSave(javafx.stage.Window owner, String title, String initialName, String prefKey,
                                          javafx.stage.FileChooser.ExtensionFilter... filters) {
        if (testFileChooser != null) {
            return testFileChooser.apply("salvar", initialName);
        }
        javafx.stage.FileChooser fc = chooser(title, prefKey, filters);
        fc.setInitialFileName(initialName);
        java.io.File f = fc.showSaveDialog(owner);
        remember(prefKey, f);
        return f;
    }

    public static java.io.File chooseOpen(javafx.stage.Window owner, String title, String prefKey,
                                          javafx.stage.FileChooser.ExtensionFilter... filters) {
        if (testFileChooser != null) {
            return testFileChooser.apply("abrir", null);
        }
        java.io.File f = chooser(title, prefKey, filters).showOpenDialog(owner);
        remember(prefKey, f);
        return f;
    }

    private static javafx.stage.FileChooser chooser(String title, String prefKey, javafx.stage.FileChooser.ExtensionFilter... filters) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().addAll(filters);
        String dir = prefKey == null ? null : br.com.marianojunior.h2studio.core.AppPreferences.get(prefKey, null);
        if (dir != null && new java.io.File(dir).isDirectory()) {
            fc.setInitialDirectory(new java.io.File(dir));
        }
        return fc;
    }

    private static void remember(String prefKey, java.io.File f) {
        if (prefKey != null && f != null && f.getParentFile() != null) {
            br.com.marianojunior.h2studio.core.AppPreferences.set(prefKey, f.getParent());
        }
    }

    /** Abre um arquivo ou pasta com o programa padrão do sistema (Excel, Explorer...). */
    public static void openInSystem(java.nio.file.Path p) {
        if (hostServices != null && p != null) {
            hostServices.showDocument(p.toUri().toString());
        }
    }

    /** Mensagem de sucesso com botões "Abrir arquivo" e "Abrir pasta". */
    public static void infoWithFile(javafx.stage.Window owner, String title, String message, java.nio.file.Path file) {
        ButtonType openFile = new ButtonType("Abrir arquivo", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        ButtonType openDir = new ButtonType("Abrir pasta", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        Alert a = new Alert(Alert.AlertType.INFORMATION, "", openFile, openDir, OK);
        a.initOwner(owner);
        a.setTitle("H2 Studio");
        a.setHeaderText(title);
        a.getDialogPane().setContent(wrapLabel(message));
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        a.showAndWait().ifPresent(b -> {
            if (b == openFile) {
                openInSystem(file);
            } else if (b == openDir) {
                openInSystem(file.getParent());
            }
        });
    }

    /** Executa ok/erro na thread da interface quando o future terminar. */
    public static <T> void then(CompletableFuture<T> future, Consumer<T> ok, Consumer<Throwable> error) {
        future.whenComplete((v, e) -> Platform.runLater(() -> {
            if (e != null) {
                error.accept(unwrap(e));
            } else {
                ok.accept(v);
            }
        }));
    }

    public static Throwable unwrap(Throwable e) {
        while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }

    /** Primeira linha da mensagem de erro (o H2 coloca o SQL nas linhas seguintes). */
    public static String shortMessage(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int i = m.indexOf('\n');
        String s = i > 0 ? m.substring(0, i).trim() : m.trim();
        s = s.replaceFirst(";\\s*SQL statement:?$", "");
        return s.replace("Column \"", "Coluna \"").replace("\" not found", "\" não encontrada")
                .replace("Table \"", "Tabela \"").replace("Syntax error in SQL statement", "Erro de sintaxe no SQL");
    }

    public static void error(Window owner, String title, String message, Throwable details) {
        Alert a = new Alert(Alert.AlertType.ERROR, "", OK);
        a.initOwner(owner);
        a.setTitle("H2 Studio");
        a.setHeaderText(title);
        a.getDialogPane().setContent(wrapLabel(message));
        if (details != null) {
            TextArea ta = new TextArea(ConnectionFactory.sqlDetails(details));
            ta.setEditable(false);
            ta.setWrapText(true);
            ta.setPrefRowCount(8);
            ta.getStyleClass().add("mono");
            a.getDialogPane().setExpandableContent(new VBox(6, new Label("Detalhes técnicos:"), ta));
        }
        a.showAndWait();
    }

    public static void info(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, "", OK);
        a.initOwner(owner);
        a.setTitle("H2 Studio");
        a.setHeaderText(title);
        a.getDialogPane().setContent(wrapLabel(message));
        a.showAndWait();
    }

    public static Label wrapLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(520);
        l.setMinHeight(Label.USE_PREF_SIZE);
        return l;
    }

    public static void copy(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
    }
}
