package br.com.marianojunior.h2studio.ui;

import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/** Janela de progresso para tarefas demoradas (exportar, importar, converter...), com cancelar. */
public final class TaskDialog {

    /** O que a tarefa pode informar enquanto roda. */
    public interface Ctx {
        void status(String text);

        /** 0..1, ou negativo para "sem previsão". */
        void progress(double fraction);

        boolean cancelled();
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Ctx ctx) throws Exception;
    }

    private TaskDialog() {
    }

    public static <T> void run(Window owner, String title, boolean cancellable, Work<T> work,
                               Consumer<T> ok, Consumer<Throwable> error) {
        Stage st = new Stage(StageStyle.UTILITY);
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle("H2 Studio");
        st.setResizable(false);
        st.setOnCloseRequest(e -> e.consume()); // só fecha quando a tarefa termina
        Label head = new Label(title);
        head.getStyleClass().add("section-title");
        Label status = new Label("Iniciando…");
        status.setWrapText(true);
        status.setMinHeight(36);
        ProgressBar bar = new ProgressBar(-1);
        bar.setPrefWidth(440);
        Button cancel = new Button("Cancelar");
        cancel.setVisible(cancellable);
        cancel.setManaged(cancellable);
        HBox buttons = new HBox(cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(10, head, status, bar, buttons);
        box.setPadding(new Insets(16));
        box.setPrefWidth(480);
        Scene sc = new Scene(box);
        sc.getStylesheets().add(MainWindow.CSS);
        st.setScene(sc);

        final boolean[] cancelled = {false};
        cancel.setOnAction(e -> {
            cancelled[0] = true;
            cancel.setDisable(true);
            status.setText("Cancelando…");
        });
        Ctx ctx = new Ctx() {
            @Override
            public void status(String text) {
                Platform.runLater(() -> {
                    if (!cancelled[0]) {
                        status.setText(text);
                    }
                });
            }

            @Override
            public void progress(double fraction) {
                Platform.runLater(() -> bar.setProgress(fraction < 0 ? -1 : Math.min(1, fraction)));
            }

            @Override
            public boolean cancelled() {
                return cancelled[0];
            }
        };
        Thread t = new Thread(() -> {
            try {
                T r = work.run(ctx);
                Platform.runLater(() -> {
                    st.close();
                    ok.accept(r);
                });
            } catch (Throwable ex) {
                Throwable e = Fx.unwrap(ex);
                Platform.runLater(() -> {
                    st.close();
                    error.accept(e);
                });
            }
        }, "h2studio-tarefa");
        t.setDaemon(true);
        st.show();
        t.start();
    }
}
