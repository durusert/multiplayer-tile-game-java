package frontend.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;

/**
 * Application entry that now uses FXML-based screens when available.
 */
public class IsleOfSkyeApp extends Application {
    private static final Logger log = LogManager.getLogger(IsleOfSkyeApp.class);

    @Override
    /**
     * Startet die JavaFX-Anwendung und lädt den passenden FXML-Screen.
     *
     * @param primaryStage primäre Stage der Anwendung
     */
    public void start(Stage primaryStage) {
        try {
            // Fonts are optional in the portfolio edition. JavaFX falls back to
            // system fonts when the original licensed font files are omitted.
            loadOptionalFont("/fonts/Cinzel-VariableFont_wght.ttf");
            loadOptionalFont("/fonts/Inter-Italic-VariableFont_opsz,wght.ttf");

            var params = getParameters();
            var raw = params.getRaw();
            var named = params.getNamed();

            String host = named.get("host");
            if (host != null && !host.isBlank()) {
                System.setProperty("isle.server.host", host.trim());
            }
            String port = named.get("port");
            if (port != null && !port.isBlank()) {
                System.setProperty("isle.server.port", port.trim());
            }

            if (raw.contains("gameover")) {
                log.info("[UI] Loading FXML: /fxml/GameOver.fxml");
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/GameOver.fxml"));
                Parent root = loader.load();

                Scene scene = new Scene(root, 1000, 700);
                primaryStage.setTitle("Isle of Skye – Game Over (TEST)");
                primaryStage.setScene(scene);
                primaryStage.show();
                return;
            }

            if (raw.contains("scoring")) {
                log.info("[UI] Loading FXML: /fxml/Scoring.fxml");
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/Scoring.fxml"));
                Parent root = loader.load();


                Scene scene = new Scene(root, 1000, 700);
                primaryStage.setTitle("Isle of Skye – Scoring (TEST)");
                primaryStage.setScene(scene);
                primaryStage.show();
                return;
            }

            String username = null;
            if (named.containsKey("name") && named.get("name") != null && !named.get("name").isBlank()) {
                username = named.get("name").trim();
            } else if (!raw.isEmpty()) {
                String candidate = raw.get(0);
                if (candidate != null && !candidate.startsWith("--")) {
                    username = candidate;
                }
            }

            if (username != null && !username.isBlank()) {
                log.info("[UI] Loading FXML: /fxml/Lobby.fxml");
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/Lobby.fxml"));
                Parent root = loader.load();
                Object ctrl = loader.getController();
                if (ctrl instanceof frontend.ui.controllers.LobbyController lc) {
                    lc.setPrimaryStage(primaryStage);
                    lc.setUsername(username);
                    lc.setBirthdate(null);
                    lc.startClient();
                }
                Scene scene = new Scene(root, 1000, 700);
                primaryStage.setTitle("Isle of Skye - " + username);
                primaryStage.setScene(scene);
                primaryStage.show();
                return;
            }

            log.info("[UI] Loading FXML: /fxml/MainMenu.fxml");
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainMenu.fxml"));
            Parent root = loader.load();
            Object ctrl = loader.getController();
            if (ctrl instanceof frontend.ui.controllers.MainMenuController) {
                ((frontend.ui.controllers.MainMenuController) ctrl).setPrimaryStage(primaryStage);
            }
            Scene scene = new Scene(root, 1000, 700);
            primaryStage.setTitle("Isle of Skye");
            primaryStage.setScene(scene);
            primaryStage.show();
        } catch (Exception ex) {
            ex.printStackTrace();
            Scene scene = new Scene(new javafx.scene.layout.StackPane(), 1000, 700);
            primaryStage.setTitle("Isle of Skye");
            primaryStage.setScene(scene);
            primaryStage.show();
        }
    }

    /**
     * Startpunkt der Anwendung.
     *
     * @param args Programmargumente
     */
    public static void main(String[] args) {
        launch(args);
    }

    private void loadOptionalFont(String resourcePath) {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream != null) {
                Font.loadFont(stream, 14);
            }
        } catch (Exception exception) {
            log.debug("Optional font could not be loaded: {}", resourcePath, exception);
        }
    }
}
