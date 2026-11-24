package br.com.marianojunior.h2studio.ui;

import br.com.marianojunior.h2studio.core.DbSession;
import br.com.marianojunior.h2studio.core.FlywayInfo;
import br.com.marianojunior.h2studio.core.Meta;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Histórico de migrações do Flyway gravado no banco (flyway_schema_history). */
public class FlywayDialog extends Dialog<Void> {

    public FlywayDialog(Window owner, DbSession session) {
        initOwner(owner);
        setTitle("Histórico do Flyway");
        setHeaderText("Migrações do Flyway — " + session.displayName());
        setResizable(true);
        getDialogPane().getStylesheets().add(MainWindow.CSS);
        getDialogPane().getButtonTypes().add(Fx.CLOSE);

        TableView<FlywayInfo.Migration> table = new TableView<>();
        table.setPrefSize(900, 420);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Carregando…"));
        table.getColumns().add(col("#", m -> m.rank(), 45));
        table.getColumns().add(col("Versão", m -> m.version() == null ? "(repetível)" : m.version(), 80));
        table.getColumns().add(col("Descrição", FlywayInfo.Migration::description, 220));
        table.getColumns().add(col("Tipo", FlywayInfo.Migration::type, 70));
        table.getColumns().add(col("Script", FlywayInfo.Migration::script, 220));
        table.getColumns().add(col("Instalada em", FlywayInfo.Migration::installedOn, 140));
        table.getColumns().add(col("Por", FlywayInfo.Migration::installedBy, 70));
        table.getColumns().add(col("ms", m -> m.executionMs(), 55));
        table.getColumns().add(col("Sucesso", m -> m.success() ? "sim" : "FALHOU", 70));
        table.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(FlywayInfo.Migration m, boolean empty) {
                super.updateItem(m, empty);
                getStyleClass().remove("flyway-failed");
                if (!empty && m != null && !m.success()) {
                    getStyleClass().add("flyway-failed");
                }
            }
        });
        Label info = new Label();
        info.setWrapText(true);
        VBox box = new VBox(8, table, info);
        box.setPadding(new Insets(6, 4, 0, 4));
        getDialogPane().setContent(box);

        Fx.then(session.submit(c -> {
            Meta.TableInfo t = FlywayInfo.findHistoryTable(c).orElse(null);
            return t == null ? null : new Object[]{t, FlywayInfo.read(c, t)};
        }), r -> {
            if (r == null) {
                table.setPlaceholder(new Label("Este banco não tem a tabela flyway_schema_history."));
                info.setText("O sistema não usa Flyway, ou ainda não foi iniciado com este banco.");
                return;
            }
            Meta.TableInfo t = (Meta.TableInfo) r[0];
            @SuppressWarnings("unchecked") List<FlywayInfo.Migration> ms = (List<FlywayInfo.Migration>) r[1];
            table.getItems().setAll(ms);
            table.scrollTo(Math.max(0, ms.size() - 1));
            long failed = ms.stream().filter(m -> !m.success()).count();
            info.setText(ms.size() + " migração(ões) em " + t.schema() + "." + t.name()
                    + (failed > 0 ? " · " + failed + " com FALHA (o sistema não inicia até corrigir: flyway repair)" : "")
                    + " · Próxima versão sugerida: V" + FlywayInfo.nextVersion(ms) + "__descricao.sql");
            if (failed > 0) {
                info.getStyleClass().add("text-error");
            }
        }, e -> table.setPlaceholder(new Label("Erro: " + Fx.shortMessage(e))));
    }

    private static <T> TableColumn<FlywayInfo.Migration, T> col(String title, Function<FlywayInfo.Migration, T> f, double w) {
        TableColumn<FlywayInfo.Migration, T> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setPrefWidth(w);
        return c;
    }
}
