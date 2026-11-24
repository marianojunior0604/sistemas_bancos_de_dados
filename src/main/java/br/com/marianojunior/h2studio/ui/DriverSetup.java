package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.ConnectionConfig;
import br.com.marianojunior.h2studio.core.JdbcDrivers;
import java.io.File;
import java.util.List;
import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Garante que o driver JDBC da conexão está disponível: baixa (com confirmação) ou deixa escolher um .jar. */
public final class DriverSetup {

    private DriverSetup() {
    }

    /**
     * Chama {@code onReady} quando o driver estiver pronto. Se precisar baixar, pergunta antes e
     * mostra o andamento; se o usuário cancelar, nada acontece.
     */
    public static void ensure(Window owner, ConnectionConfig cfg, Runnable onReady) {
        if (cfg.isH2() || (cfg.getDriverJar() != null && !cfg.getDriverJar().isBlank())) {
            onReady.run();
            return;
        }
        JdbcDrivers.Spec spec = cfg.getType().driver();
        List<JdbcDrivers.Artifact> missing = JdbcDrivers.missing(spec);
        if (missing.isEmpty()) {
            onReady.run();
            return;
        }
        long size = missing.stream().mapToLong(JdbcDrivers.Artifact::approxSize).sum();
        ButtonType download = new ButtonType("Baixar e continuar", ButtonBar.ButtonData.OK_DONE);
        ButtonType local = new ButtonType("Usar um .jar que já tenho…", ButtonBar.ButtonData.OTHER);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", download, local, Fx.CANCEL);
        a.initOwner(owner);
        a.setTitle("H2 Studio");
        a.setHeaderText("Driver " + cfg.getType().label() + " necessário");
        a.getDialogPane().setContent(Fx.wrapLabel("Para conectar em " + cfg.getType().label() + " é preciso o driver "
                + spec.label() + " " + spec.version() + " (cerca de " + JdbcDrivers.size(size) + ").\n\n"
                + "Ele será baixado do Maven Central (" + JdbcDrivers.repository() + "), conferido e guardado em\n"
                + JdbcDrivers.installDir() + "\n\nIsso acontece só na primeira vez."));
        a.getDialogPane().getStylesheets().add(MainWindow.CSS);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isEmpty() || r.get() == Fx.CANCEL) {
            return;
        }
        if (r.get() == local) {
            File f = chooseJar(owner);
            if (f != null) {
                cfg.setDriverJar(f.getAbsolutePath());
                onReady.run();
            }
            return;
        }
        TaskDialog.run(owner, "Baixando driver " + spec.label(), true, ctx -> {
            JdbcDrivers.download(spec, (file, done, total) -> {
                ctx.status(file + " — " + JdbcDrivers.size(done) + " de " + JdbcDrivers.size(total));
                ctx.progress(total > 0 ? (double) done / total : -1);
            }, ctx::cancelled);
            return null;
        }, ok -> onReady.run(), err -> {
            StringBuilder sb = new StringBuilder(Fx.shortMessage(err)).append("\n\nSem internet neste computador? ")
                    .append("Baixe em outro e copie para ").append(JdbcDrivers.installDir()).append(":\n");
            for (JdbcDrivers.Artifact m : missing) {
                sb.append("\n").append(m.url(JdbcDrivers.repository()));
            }
            sb.append("\n\nOu escolha o .jar em \"Avançado › Driver\" na conexão.");
            Fx.error(owner, "Não foi possível baixar o driver", sb.toString(), err);
        });
    }

    public static File chooseJar(Window owner) {
        if (Fx.testFileChooser != null) {
            return Fx.testFileChooser.apply("abrir", null);
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Escolher o driver JDBC (.jar)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Driver JDBC (*.jar)", "*.jar"));
        File dir = JdbcDrivers.installDir().toFile();
        if (dir.isDirectory()) {
            fc.setInitialDirectory(dir);
        }
        return fc.showOpenDialog(owner);
    }
}
