package frontend.ui.controllers;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;

/**
 * Controller für den Game-Over-Screen.
 *
 * <p>Dieser Screen wird nach Spielende angezeigt und stellt:</p>
 * <ul>
 *   <li>den Gewinner des Spiels</li>
 *   <li>eine Rückkehrmöglichkeit ins Hauptmenü</li>
 * </ul>
 *
 * <p>Die Daten werden vom Game-Controller gesetzt,
 * bevor dieser Screen angezeigt wird.</p>
 */
public class GameOverController {

    @FXML private Label winnerLabel;
    @FXML private VBox placementsBox;
    @FXML private Button backToMainMenuButton;

    /**
     * Setzt den Namen des Gewinners und zeigt ihn prominent an.
     *
     * @param name
     *        Name des Spielers mit der höchsten Punktzahl
     */
    public void setWinnerName(String name) {
        winnerLabel.setText("The Winner is: " + name);
    }

    /**
     * Zeigt die Platzierungen der Spieler an.
     *
     * <p>Es werden maximal die ersten drei Platzierungen dargestellt.</p>
     *
     * <p>Falls keine Platzierungen übergeben werden, bleibt die Liste leer.</p>
     *
     * @param placements
     *        Liste der Spieler-Platzierungen, sortiert nach Punkten
     *        (beste Platzierung zuerst)
     */
    public void setPlacements(List<Placement> placements) {
        if (placementsBox == null) return;
        placementsBox.getChildren().clear();
        if (placements == null || placements.isEmpty()) {
            Label info = new Label();
            info.getStyleClass().add("gameover-label");
            placementsBox.getChildren().add(info);
            return;
        }
        int max = Math.min(placements.size(), 3);
        for (int i = 0; i < max; i++) {
            Placement p = placements.get(i);
            Label row = new Label(formatPlacementText(i, p));
            row.getStyleClass().add("gameover-placement");
            placementsBox.getChildren().add(row);
        }
    }

    /**
     * Wird aufgerufen, wenn der Benutzer auf den
     * „Back to Main Menu“-Button klickt.
     *
     * <p>Lädt das Hauptmenü neu und ersetzt die aktuelle Scene.</p>
     */
    @FXML
    private void onBackToMainMenu() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainMenu.fxml"));
            Parent root = loader.load();

            Stage stage = (Stage) backToMainMenuButton.getScene().getWindow();
            Scene scene = new Scene(root, 1200, 800);

            stage.setScene(scene);
            stage.setTitle("Isle of Skye");
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Formatiert einen Platzierungseintrag für die Anzeige.
     *
     * <p>Die Platzierung wird als „1st“, „2nd“, „3rd“ usw. dargestellt.</p>
     *
     * @param index
     *        Index der Platzierung (0-basiert)
     *
     * @param p
     *        Platzierungsdaten des Spielers
     *
     * @return
     *         Formatierter Text für die UI-Anzeige
     */
    private String formatPlacementText(int index, Placement p) {
        String place = switch (index) {
            case 0 -> "1st";
            case 1 -> "2nd";
            case 2 -> "3rd";
            default -> (index + 1) + "th";
        };
        return place + " • " + p.name() + " – " + p.points() + " VP";
    }

    /**
     * Repräsentiert eine Platzierung eines Spielers im Endergebnis.
     *
     * @param name
     *        Name des Spielers
     *
     * @param points
     *        Erreichte Siegpunkte (Victory Points)
     */
    public record Placement(String name, int points) {}
}