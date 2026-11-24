package br.com.marianojunior.h2studio;

import br.com.marianojunior.h2studio.core.H2FileInspector;
import br.com.marianojunior.h2studio.ui.MainWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.stage.Stage;

public class H2StudioApp extends Application {

    @Override
    public void start(Stage stage) {
        MainWindow w = new MainWindow(stage, getHostServices());
        w.show();
        // permite "Abrir com…" no Windows: h2studio.exe C:\caminho\banco.mv.db
        for (String arg : getParameters().getUnnamed()) {
            Path p = Path.of(arg);
            if (Files.isRegularFile(p) && H2FileInspector.isH2File(p)) {
                w.openFile(p);
            }
        }
    }
}
