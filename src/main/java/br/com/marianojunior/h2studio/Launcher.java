package br.com.marianojunior.h2studio;

import br.com.marianojunior.h2studio.core.SingleInstance;
import java.util.List;
import javafx.application.Application;

/**
 * Ponto de entrada. Fica separado da classe Application para que o jar
 * "gordo" (com JavaFX dentro) rode com {@code java -jar h2studio.jar}.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        // já aberto? entrega o arquivo (dois cliques num .mv.db) para a janela existente e sai
        if (!Boolean.getBoolean("h2studio.novaJanela") && SingleInstance.handOff(List.of(args))) {
            return;
        }
        Application.launch(H2StudioApp.class, args);
    }
}
