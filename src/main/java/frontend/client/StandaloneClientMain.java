package frontend.client;

import frontend.ui.IsleOfSkyeApp;
import javafx.application.Application;

/**
 * Standalone entry point for the fat client jar.
 */
public final class StandaloneClientMain {
    private StandaloneClientMain() {
    }

    /**
     * Startet die JavaFX-Anwendung für den Standalone-Client.
     *
     * @param args Programmargumente
     */
    public static void main(String[] args) {
        Application.launch(IsleOfSkyeApp.class, args);
    }
}
