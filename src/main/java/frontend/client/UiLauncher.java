package frontend.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javafx.application.Application;
import frontend.ui.IsleOfSkyeApp;

/**
 * Small helper to spawn the JavaFX UI process exactly once per JVM.
 * Other classes should call UiLauncher.startUi(username) to request the UI.
 */
public final class UiLauncher {

    private static final Logger log =
            LogManager.getLogger(UiLauncher.class);

    private static final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Start the UI process once. If username is non-empty it will be passed as the first arg.
     *
     * @param username optionaler Benutzername
     */
    public static void startUi(String username) {
        if (!started.compareAndSet(false, true)) return;

        Thread t = new Thread(() -> {
            try {
                if (username != null && !username.isBlank()) {
                    Application.launch(IsleOfSkyeApp.class, username);
                } else {
                    Application.launch(IsleOfSkyeApp.class);
                }
            } catch (IllegalStateException ex) {
                log.warn("UI already started or JavaFX runtime already initialized", ex);
            }
        }, "UiLauncher-Starter");
        t.setDaemon(true);
        t.start();
    }
}
