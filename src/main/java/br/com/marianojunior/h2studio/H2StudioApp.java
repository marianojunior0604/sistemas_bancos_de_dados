package br.com.marianojunior.h2studio;

import br.com.marianojunior.h2studio.core.H2FileInspector;
import br.com.marianojunior.h2studio.core.SingleInstance;
import br.com.marianojunior.h2studio.ui.MainWindow;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class H2StudioApp extends Application {

    @Override
    public void start(Stage stage) {
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            InputStream in = H2StudioApp.class.getResourceAsStream("ui/icones/h2studio-" + s + ".png");
            if (in != null) {
                stage.getIcons().add(new Image(in));
            }
        }
        MainWindow w = new MainWindow(stage, getHostServices());
        w.show();
        // "Abrir com…" / dois cliques no Windows: "H2 Studio.exe" C:\caminho\banco.mv.db
        openAll(w, getParameters().getUnnamed());
        SingleInstance.listen(files -> Platform.runLater(() -> {
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            stage.show();
            stage.toFront();
            stage.requestFocus();
            openAll(w, files);
        }));
    }

    @Override
    public void stop() {
        SingleInstance.stop();
    }

    private static void openAll(MainWindow w, List<String> args) {
        for (String arg : args) {
            Path p = Path.of(arg);
            if (Files.isRegularFile(p) && (H2FileInspector.isH2File(p) || isSqlite(p))) {
                w.openFile(p);
            }
        }
    }

    private static boolean isSqlite(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] head = in.readNBytes(16);
            return new String(head, StandardCharsets.ISO_8859_1).startsWith("SQLite format 3");
        } catch (Exception e) {
            return false;
        }
    }
}
