package br.com.marianojunior.h2studio;

import javafx.application.Application;

/**
 * Ponto de entrada. Fica separado da classe Application para que o jar
 * "gordo" (com JavaFX dentro) rode com {@code java -jar h2studio.jar}.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(H2StudioApp.class, args);
    }
}
