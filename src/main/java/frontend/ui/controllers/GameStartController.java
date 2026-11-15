package frontend.ui.controllers;

import backend.model.SingleScoringTile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import frontend.client.WebSocketChatClient;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Point2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;



import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;


import java.io.IOException;
import java.util.*;

/**
 * Controller für den Game-Start-Screen.
 *
 * <p>Dieser Screen stellt die Übergangsphase zwischen Lobby und eigentlichem Spiel dar.</p>
 *
 * <h2>Aufgaben</h2>
 * <ul>
 *   <li>Anzeigen des passenden Spielbretts (abhängig von Spieleranzahl)</li>
 *   <li>Anzeigen der vier zufällig gezogenen Scoring-Tiles</li>
 *   <li>Visualisierung und Animation der Spieler-Token entlang des Rundenpfads</li>
 *   <li>Darstellung des aktuellen Rundenmarkers</li>
 *   <li>Automatischer oder manueller Übergang in den Game-Screen</li>
 * </ul>
 *
 * <p>Der Controller verwaltet außerdem UI-Skalierung, Zustands-Wiederherstellung
 * und die Übergabe von Session-Informationen zwischen den Screens.</p>
 */

public class GameStartController {
    private static final Logger log = LogManager.getLogger(GameStartController.class);

    //Zuordnen: Scoring-Tile zu Bildpfad
    private static final Map<SingleScoringTile, String> TILE_IMAGES = Map.ofEntries(
            Map.entry(SingleScoringTile.SQUARE_OF_FOUR, "/scoring/0.png"),
            Map.entry(SingleScoringTile.COMPLETED_AREA, "/scoring/1.png"),
            Map.entry(SingleScoringTile.COMPLETED_AREA_3, "/scoring/2.png"),
            Map.entry(SingleScoringTile.SHEEP_COUNT, "/scoring/3.png"),
            Map.entry(SingleScoringTile.MOUNTAIN_BROCH, "/scoring/4.png"),
            Map.entry(SingleScoringTile.SET_BROCH_FARM_LIGHTHOUSE, "/scoring/5.png"),
            Map.entry(SingleScoringTile.MOST_BARREL_TILES, "/scoring/6.png"),
            Map.entry(SingleScoringTile.MOST_SHIPS, "/scoring/7.png"),
            Map.entry(SingleScoringTile.SHEEP_CATTLE_ADJACENT_FARM, "/scoring/8.png"),
            Map.entry(SingleScoringTile.MOST_GOLD, "/scoring/9.png"),
            Map.entry(SingleScoringTile.CATTLE_CONNECTED_TO_CASTLE, "/scoring/10.png"),
            Map.entry(SingleScoringTile.WATER_SHIP_LIGHTHOUSE_AREAS, "/scoring/11.png"),
            Map.entry(SingleScoringTile.TILES_CONNECTED_TO_CASTLE, "/scoring/12.png"),
            Map.entry(SingleScoringTile.VERTICAL_LINES_OF_THREE, "/scoring/13.png"),
            Map.entry(SingleScoringTile.LARGEST_COMPLETED_WATER_AREA, "/scoring/14.png"),
            Map.entry(SingleScoringTile.COMPLETED_MOUNTAIN_AREAS, "/scoring/15.png")
    );



    @FXML private ImageView boardImage;
    @FXML private Pane boardOverlayPane;

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;

    @FXML private ImageView scoringTileA;
    @FXML private ImageView scoringTileB;
    @FXML private ImageView scoringTileC;
    @FXML private ImageView scoringTileD;

    @FXML private StackPane prepOverlay;
    @FXML private Button startGameButton;

    @FXML private HBox roundPlatesBox;
    @FXML private Circle roundMarker;

    private int currentRound = 0;

    private Timeline autoStartTimeline;
    private boolean hasSwitched = false;

    private final StringProperty titleText = new SimpleStringProperty("Scoring Tiles were laid");
    private final StringProperty subtitleText = new SimpleStringProperty("");

    /** Bilder für die vier Scoring-Tiles*/
    private final ObjectProperty<Image> tileAImage = new SimpleObjectProperty<>();
    private final ObjectProperty<Image> tileBImage = new SimpleObjectProperty<>();
    private final ObjectProperty<Image> tileCImage = new SimpleObjectProperty<>();
    private final ObjectProperty<Image> tileDImage = new SimpleObjectProperty<>();

    /** Feldberechnung*/
    private static int colsX = 16;
    private static int rowsY = 11;
    /**Randabstand*/
    private static double margin = 40;

    private static final String BOARD_2_4_PATH = "/images/board-2-4.png";
    private static final String BOARD_5_PATH   = "/images/board-5.png";

    private static final double TOKEN_RADIUS = 12.0;
    private frontend.client.WebSocketChatClient client;
    private String username;
    private java.util.List<String> playersSnapshot;

    private List<Point2D> fieldPositions = new ArrayList<>();

    private static final List<Color> DEFAULT_COLORS = List.of(
            Color.RED, Color.DODGERBLUE, Color.LIMEGREEN, Color.GOLD, Color.MEDIUMPURPLE
    );

    private static final Map<String, Color> GLOBAL_COLOR_BY_PLAYER = new LinkedHashMap<>();
    private static final Map<Color, Integer> GLOBAL_FIELD_INDEX_BY_COLOR = new LinkedHashMap<>();


    /**
     * Repräsentiert ein Spieler-Token
     * (Farbe + JavaFX-Node + aktuelle Feldposition)
     */    private static class ColorToken {
        final Color color;
        final String playerName;
        final VBox node;     // (Circle + Label)
        int fieldIndex = 0;

        ColorToken(Color color, String playerName, VBox node) {
            this.color = color;
            this.playerName = playerName;
            this.node = node;
        }
    }
    /** Alle aktuell sichtbaren Tokens, sortiert nach Farbe */
    private final Map<Color, ColorToken> tokensByColor = new LinkedHashMap<>();
    private final Map<String, Color> playerColorsByName = new LinkedHashMap<>();


    /**
     * Wird automatisch nach dem Laden der FXML-Datei aufgerufen.
     *
     * <p>Initialisiert die UI-Bindings, skaliert das Spielbrett fullscreen-fähig,
     * registriert Resize-Listener und liest Session-Informationen aus der Scene.</p>
     *
     * <p>Zusätzlich wird ein Auto-Start-Timer gestartet, der nach einer festen
     * Wartezeit automatisch in den Game-Screen wechselt.</p>
     */
    @FXML
    public void initialize() {
        //fullscreen skalieren
        var parent = boardImage.getParent();
        if (parent instanceof StackPane root) {
            boardImage.fitWidthProperty().bind(root.widthProperty());
            boardImage.fitHeightProperty().bind(root.heightProperty());
            boardImage.setPreserveRatio(false);

            if (boardOverlayPane != null) {
                boardOverlayPane.prefWidthProperty().bind(root.widthProperty());
                boardOverlayPane.prefHeightProperty().bind(root.heightProperty());
                boardOverlayPane.setPickOnBounds(false);

                //neu berechnen bei Resize
                boardOverlayPane.widthProperty().addListener((obs, o, n) -> rebuildFields());
                boardOverlayPane.heightProperty().addListener((obs, o, n) -> rebuildFields());
            }
        }

        //UI-Text binden
            if (titleLabel != null) titleLabel.textProperty().bind(titleText);
            if (subtitleLabel != null) subtitleLabel.textProperty().bind(subtitleText);

            //Scoring-Tile-Bilder binden
            if (scoringTileA != null) scoringTileA.imageProperty().bind(tileAImage);
            if (scoringTileB != null) scoringTileB.imageProperty().bind(tileBImage);
            if (scoringTileC != null) scoringTileC.imageProperty().bind(tileCImage);
            if (scoringTileD != null) scoringTileD.imageProperty().bind(tileDImage);

        if (startGameButton != null) {
            startGameButton.setDisable(true);
            startGameButton.setVisible(false);
            startGameButton.setManaged(false);
        }
            startGameButton.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) return;


            Object ud = newScene.getUserData();
        if (ud instanceof SessionBridge sb) {
            this.client = sb.client;
            this.username = sb.username;
            this.playersSnapshot = sb.playersSnapshot;

            int count = (playersSnapshot == null || playersSnapshot.isEmpty())
                    ? 4
                    : playersSnapshot.size();

            applyBoardForPlayerCount(count);
            ensureTokensVisibleAndRestored();
            applyMovesFromUserDataIfPresent(newScene);
        }
        Platform.runLater(this::rebuildFields);

        startAutoStartTimer();
    });
}

    /**
     * Wird aufgerufen, wenn der Benutzer den „Spiel starten“-Button betätigt.
     *
     * <p>Bricht einen eventuell laufenden Auto-Start-Timer ab und
     * wechselt manuell in den eigentlichen Game-Screen.</p>
     */
    @FXML
    private void onStartGame() {
        log.info("Game Start clicked");
        // Manual override: proceed to the actual game screen.
        if (autoStartTimeline != null) {
            autoStartTimeline.stop();
        }
        switchToGameScreenSafely();
    }

    /**
     * Übergibt die aktuelle Spiel-Session an den Controller.
     *
     * <p>Diese Methode wird beim Öffnen des Game-Start-Screens aufgerufen
     * und stellt sicher, dass:</p>
     * <ul>
     *   <li>der WebSocket-Client verfügbar ist</li>
     *   <li>der Benutzername gespeichert wird</li>
     *   <li>die Spieleranzahl korrekt erkannt wird</li>
     *   <li>das passende Spielbrett gewählt wird</li>
     * </ul>
     *
     * @param client
     *        Aktiver {@link WebSocketChatClient} der Spielsession
     * @param username
     *        Benutzername des lokalen Spielers
     * @param playersSnapshot
     *        Momentaufnahme aller Spielernamen
     */
    public void setSession(frontend.client.WebSocketChatClient client, String username, java.util.List<String> playersSnapshot) {
        this.client = client;
        this.username = username;
        this.playersSnapshot = playersSnapshot;

        if (this.client != null) {
            this.client.setOnPlayersChanged(map -> {
                javafx.application.Platform.runLater(() -> {
                    int count = map.size();
                    if (count <= 0) count = 4;

                    applyBoardForPlayerCount(count); //passendes Spielbrett wählen
                    rebuildFields(); //Spielfeld & Token-Position berechnen
                });
            });
        }

        int playerCount = (playersSnapshot == null) ? 0 : playersSnapshot.size();
        if (playerCount <= 0) playerCount = 4; // fallback
        applyBoardForPlayerCount(playerCount); //passendes Spielbrett wählen

        javafx.application.Platform.runLater(this::rebuildFields);

    }

    /** berechnet alle Spielfeld-Positionen neu und
     * ordnet anschließend die Spielerr-Token korrekt an
     */
    private void rebuildFields() {
        if (boardOverlayPane == null) return;
        double w = boardOverlayPane.getWidth(); //aktuelle Größe ermitteln
        double h = boardOverlayPane.getHeight();
        if (w <= 0 || h <= 0) return; //Abbrechen, falls Overlay noch keine gültige Größe hat

        fieldPositions = computePerimeterPositions(w, h, colsX, rowsY, margin); //Spielfeldpositionen entlang des Board-Randes neu berechnen
        relayoutAllTokens(); //Tokens neu anordnen
    }

    /**
     *Berechnung der Felder
     */
    private List<Point2D> computePerimeterPositions(double w, double h, int colsX, int rowsY, double margin) {
        double stepX = (w - 2 * margin) / (colsX - 1);
        double stepY = (h - 2 * margin) / (rowsY - 1);

        double xMin = margin;
        double xMax = w - margin;
        double yMin = margin;
        double yMax = h - margin;

        List<Point2D> pts = new ArrayList<>();

        // oben (16)
        for (int x = 0; x < colsX; x++) pts.add(new Point2D(xMin + x * stepX, yMin));
        // rechts (11-1)
        for (int y = 1; y < rowsY; y++) pts.add(new Point2D(xMax, yMin + y * stepY));
        // unten (16-1)
        for (int x = colsX - 2; x >= 0; x--) pts.add(new Point2D(xMin + x * stepX, yMax));
        // links (11-2)
        for (int y = rowsY - 2; y >= 1; y--) pts.add(new Point2D(xMin, yMin + y * stepY));

        return pts; // 50 Felder
    }


    /**
     * Zeigt alle Spieler-Token auf dem Startfeld (Feldindex 0) an.
     *
     * <p>Vorhandene Tokens werden entfernt und anhand der übergebenen
     * Farbzuordnung neu erzeugt.</p>
     *
     * @param playersByName
     *        Zuordnung von Spielerfarbe zu Anzahl der Spieler
     */
    public void showPlayersOnFirstField(Map<String, Color> playersByName) {
        if (boardOverlayPane == null || playersByName == null || playersByName.isEmpty()) return;
        if (fieldPositions == null || fieldPositions.isEmpty()) return;

        boardOverlayPane.toFront();

        // Alte Token entfernen
        boardOverlayPane.getChildren().removeAll(
                tokensByColor.values().stream().map(t -> t.node).collect(Collectors.toList())
        );
        tokensByColor.clear();
        playerColorsByName.clear();
        playerColorsByName.putAll(playersByName);

        // Token pro Farbe anlegen (auf Feld 0)
        for (Map.Entry<String, Color> entry : playersByName.entrySet()) {
            String name = entry.getKey();
            Color color = entry.getValue();
            if (color == null) continue;

            VBox tokenWithLabel = createTokenWithName(color, name);
            ColorToken token = new ColorToken(color, name, tokenWithLabel);
            token.fieldIndex = 0;

            tokensByColor.put(color, token);
            boardOverlayPane.getChildren().add(tokenWithLabel);

            if (name != null && !name.isBlank()) {
                GLOBAL_COLOR_BY_PLAYER.put(name, color);
            }
            GLOBAL_FIELD_INDEX_BY_COLOR.putIfAbsent(color, 0);
        }
        // Sicherstellen, dass alle Tokens sichtbar über anderen Elementen liegen
        for (var t : tokensByColor.values()){
            t.node.toFront();
        }
        relayoutAllTokens(); //Tokens korrekt anordnen

    }


    private void switchToGameScreen() {

        try {
            String fxml = "/fxml/GameV2.fxml";
            log.info("[UI] Loading game screen FXML: {}", fxml);
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
            Parent root = loader.load();

            Object ctrl = loader.getController();
            if (ctrl instanceof GameController gc) {
                log.info("[UI] GameController loaded from: {}", fxml);
                WebSocketChatClient sessionClient = getClientFromSceneUserData();
                String sessionUser = getUsernameFromSceneUserData();
                java.util.List<String> snapshot = getPlayersSnapshotFromSceneUserData();
                gc.initSession(sessionClient, sessionUser, snapshot);
            }

            // The startGameButton is intentionally hidden; use a stable node (boardImage) to locate the Stage.
            Stage stage = null;
            if (boardImage != null && boardImage.getScene() != null && boardImage.getScene().getWindow() instanceof Stage s) {
                stage = s;
            } else if (startGameButton != null && startGameButton.getScene() != null && startGameButton.getScene().getWindow() instanceof Stage s) {
                stage = s;
            }

            if (stage == null) {
                System.err.println("[GameStart] Cannot switch to game: Stage not available yet.");
                return;
            }

            Scene scene = new Scene(root, 1200, 800);

            // Preserve session bridge across scene switches.
            Object ud = null;
            if (boardImage != null && boardImage.getScene() != null) {
                ud = boardImage.getScene().getUserData();
            } else if (startGameButton != null && startGameButton.getScene() != null) {
                ud = startGameButton.getScene().getUserData();
            }
            if (ud != null) scene.setUserData(ud);

            var cssUrl = getClass().getResource("/styles/game.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            } else {
                System.err.println("[GameStart] WARNING: /styles/game.css not found on classpath (UI will look unstyled)." +
                        " Expected at src/main/resources/styles/game.css");
            }

            log.debug("[GameStart] Game scene stylesheets: {}", scene.getStylesheets());

            stage.setScene(scene);
            stage.centerOnScreen();


            stage.setFullScreenExitHint("");
            stage.setFullScreenExitKeyCombination(null);
            stage.setFullScreen(true);

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * We store session info in Scene.userData while navigating Lobby -> GameStart -> Game.
     * This avoids re-creating sockets and ensures listeners are attached to the correct controller.
     */
    private WebSocketChatClient getClientFromSceneUserData() {
        try {
            Object ud = null;
            if (boardImage != null && boardImage.getScene() != null) {
                ud = boardImage.getScene().getUserData();
            } else if (startGameButton != null && startGameButton.getScene() != null) {
                ud = startGameButton.getScene().getUserData();
            }
            if (ud instanceof SessionBridge sb) return sb.client;
        } catch (Exception ignored) {
        }
        return null;
    }

    private String getUsernameFromSceneUserData() {
        try {
            Object ud = null;
            if (boardImage != null && boardImage.getScene() != null) {
                ud = boardImage.getScene().getUserData();
            } else if (startGameButton != null && startGameButton.getScene() != null) {
                ud = startGameButton.getScene().getUserData();
            }
            if (ud instanceof SessionBridge sb) return sb.username;
        } catch (Exception ignored) {
        }
        return null;
    }

    private java.util.List<String> getPlayersSnapshotFromSceneUserData() {
        try {
            Object ud = null;
            if (boardImage != null && boardImage.getScene() != null) {
                ud = boardImage.getScene().getUserData();
            } else if (startGameButton != null && startGameButton.getScene() != null) {
                ud = startGameButton.getScene().getUserData();
            }
            if (ud instanceof SessionBridge sb) return sb.playersSnapshot;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Datencontainer zur Übergabe von Session-Informationen
     * zwischen verschiedenen JavaFX-Scenes.
     *
     * <p>Wird in {@link javafx.scene.Scene#setUserData(Object)} gespeichert,
     * um WebSocket-Client, Spieler-Snapshot und Rundendaten
     * über Screen-Wechsel hinweg zu erhalten.</p>
     *
     * @param client
     *        Aktiver WebSocket-Client
     * @param username
     *        Benutzername des lokalen Spielers
     * @param playersSnapshot
     *        Liste aller Spielernamen
     * @param gainedPointsByPlayer
     *        In der letzten Runde erzielte Punkte pro Spieler
     * @param roundZeroBased
     *        Aktuelle Runde (nullbasiert)
     */
        public record SessionBridge(WebSocketChatClient client, String username, List<String> playersSnapshot,
                                    Map<String, Integer> gainedPointsByPlayer, int roundZeroBased) {

            public SessionBridge(WebSocketChatClient client, String username) {
                this(client, username, null, null, 0);
            }

            //Konstruktor für den Übergang in den GameStart-Screen
            public SessionBridge(WebSocketChatClient client, String username, List<String> playersSnapshot) {
                this(client, username, playersSnapshot, null, 0);
            }
        }

    /** Erstell Spieler-Token in einer bestimmten Farbe
     */
    private VBox createTokenWithName(Color color, String name) {
        Circle circle = new Circle(TOKEN_RADIUS);
        circle.setFill(color);
        circle.setStroke(Color.WHITE);
        circle.setStrokeWidth(2.5);

        Label nameLabel = new Label(name == null || name.isBlank() ? "?" : name);
        nameLabel.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-text-fill: white; "
                + "-fx-padding: 2 6; -fx-background-radius: 6; -fx-font-size: 11px;");

        VBox box = new VBox(4);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.getChildren().addAll(circle, nameLabel);
        return box;
    }

    /**
     * Ordnet alle Spieler-Tokens neu auf dem Spielfeld an.
     */
    private void relayoutAllTokens() {
        if (fieldPositions == null || fieldPositions.isEmpty()) return;

        Map<Integer, List<ColorToken>> byField = new HashMap<>();
        // Alle Tokens entsprechend ihres aktuellen Feldes einsortieren
        for (ColorToken t : tokensByColor.values()) {
            byField.computeIfAbsent(t.fieldIndex, k -> new ArrayList<>()).add(t);
        }

        for (Map.Entry<Integer, List<ColorToken>> e : byField.entrySet()) {
            int fieldIdx = e.getKey();
            List<ColorToken> list = e.getValue();

            // Tokens auf diesem Feld verteilen
            for (int i = 0; i < list.size(); i++) {
                placeTokenOnField(list.get(i), fieldIdx, i);
            }
        }
    }

    /** Platziert einzlenes Spieler-Token auf einem bestimmten Spielfeld
     * wenn mehrere Tokens auf demselben Feld stehen, werden sie kreisförmig
     * um die Feldmitte angeordnet
     */
    private void placeTokenOnField(ColorToken token, int fieldIndex, int stackIndex) {
        Point2D base = fieldPositions.get(fieldIndex);

        int n = Math.max(1, tokensByColor.size());
        double r = (stackIndex == 0) ? 0 : 20;
        double angle = (2 * Math.PI * stackIndex) / n;

        double x = base.getX();
        double y = base.getY();
        if (stackIndex > 0) {
            double dx = Math.cos(angle) * r;
            double dy = Math.sin(angle) * r;
            x += dx;
            y += dy;
        }

        token.node.setLayoutX(x);
        token.node.setLayoutY(y);
        token.node.toFront();
    }


    /**
     * Bewegt die Spieler-Tokens nach Abschluss einer Runde.
     *
     * <p>Jedes Token wird entsprechend der erzielten Punkte
     * animiert entlang des Spielfeldrands verschoben.</p>
     *
     * @param stepsByColor
     *        Anzahl der Felder, die jedes Token pro Farbe vorgerückt wird
     */
    public void moveTokensAfterRound(Map<Color, Integer> stepsByColor) {
        if (stepsByColor == null || stepsByColor.isEmpty()) return;
        if (fieldPositions == null || fieldPositions.isEmpty()) return;

        Iterator<Map.Entry<Color, Integer>> it = stepsByColor.entrySet().iterator();
        moveNext(it);
    }

    private void moveNext(Iterator<Map.Entry<Color, Integer>> it) {
        if (!it.hasNext()) {
            relayoutAllTokens();
            return;
        }
        Map.Entry<Color, Integer> e = it.next();
        ColorToken token = tokensByColor.get(e.getKey());
        if (token == null) {
            moveNext(it);
            return;
        }
        int steps = Math.max(0, e.getValue());
        animateMove(token, steps, () -> moveNext(it));
    }
    private void animateMove(ColorToken token, int steps, Runnable onFinished) {
        int total = fieldPositions.size();
        Timeline tl = new Timeline();

        Duration t = Duration.ZERO;
        Duration stepDur = Duration.millis(160);

        for (int s = 0; s < steps; s++) {
            int nextIndex = (token.fieldIndex + 1) % total;
            Point2D pos = fieldPositions.get(nextIndex);

            t = t.add(stepDur);
            int finalNextIndex = nextIndex;

            tl.getKeyFrames().add(new KeyFrame(t, ev -> {
                token.fieldIndex = finalNextIndex;
                // während Animation ohne Stack-Offset
                token.node.setLayoutX(pos.getX());
                token.node.setLayoutY(pos.getY());
            }));
        }

        tl.setOnFinished(ev -> onFinished.run());
        tl.play();
    }


    /**
     * Zeigt die vier Scoring-Tiles anhand ihrer IDs an.
     *
     * <p>Die IDs werden vom Server geliefert und den
     * entsprechenden Bildressourcen zugeordnet.</p>
     *
     * @param scoringIDs
     *        JSON-Array mit mindestens vier Scoring-Tile-IDs
     */
    public void showScoringTilesFromIds(JsonArray scoringIDs) {
        if (scoringIDs == null || scoringIDs.size() < 4) return;

        tileAImage.set(loadTileImageById(scoringIDs.get(0)));
        tileBImage.set(loadTileImageById(scoringIDs.get(1)));
        tileCImage.set(loadTileImageById(scoringIDs.get(2)));
        tileDImage.set(loadTileImageById(scoringIDs.get(3)));
    }

    /**Lädt Bilder eines Scoring-Tiles anhand seiner ID
     */
    private Image loadTileImageById(JsonElement idEl) {
        if (idEl == null || !idEl.isJsonPrimitive()) return null;

        int id = idEl.getAsInt();
        String path = "/scoring/" + id + ".png";

        var url = getClass().getResource(path);
        if (url == null) {
            log.warn("Scoring image missing: {}", path);
            return null;
        }
        return new Image(url.toExternalForm());
    }

    /**
     * Verschiebt den Rundenmarker zur nächsten Runde.
     *
     * <p>Der Marker wird aus dem aktuellen Rundenfeld entfernt
     * und im nächsten Runden-Container platziert.</p>
     */    public void advanceRoundMarker() {
        if (currentRound >= 4) {
            return;
        }
        currentRound++;

        StackPane currentParent = (StackPane) roundMarker.getParent();
        currentParent.getChildren().remove(roundMarker);

        StackPane nextPlate =
                (StackPane) roundPlatesBox.getChildren().get(currentRound);

        nextPlate.getChildren().add(roundMarker);
    }

    /**
     * Callback nach Abschluss einer Runde.
     *
     * <p>Aktuell wird ausschließlich der Rundenmarker
     * weitergeschoben.</p>
     */
    public void onRoundFinished() {
        advanceRoundMarker();
    }

    private void applyBoardForPlayerCount(int playerCount) {
        // Standard: 2–4
        String boardPath = BOARD_2_4_PATH;
        colsX = 16;
        rowsY = 11;
        margin = 40;

        // 5 Spieler: anderes Board
        if (playerCount == 5) {
            boardPath = BOARD_5_PATH;

        }

        var url = getClass().getResource(boardPath);
        if (url != null) {
            boardImage.setImage(new Image(url.toExternalForm()));
        } else {
            System.err.println("GameStartController: state image missing on classpath: " + boardPath);
            boardImage.setImage(null);
        }

        Platform.runLater(this::rebuildFields);
    }

    /**
     * Startet einen automatischen Timer, der nach einer festen Wartezeit
     * automatisch vom Startbildschirm in den Spielbildschirm wechselt.
     */
    private void startAutoStartTimer() {
        // Falls bereits ein Timer läuft, diesen zuerst stoppen
        if (autoStartTimeline != null) {
            autoStartTimeline.stop();
        }
        // Nach 5 Sekunden wird sicher in den Game-Screen gewechselt
        autoStartTimeline = new Timeline(
                new KeyFrame(Duration.seconds(5), e -> switchToGameScreenSafely())
        );
        autoStartTimeline.setCycleCount(1);// Timer soll nur einmal ausgelöst werden
        autoStartTimeline.play();
    }

    /**
     * Wechselt sicher in den Game-Screen.
     *
     * <p>Stellt sicher, dass der Wechsel nur einmal erfolgt,
     * selbst wenn mehrere Trigger (Button + Timer) aktiv sind.</p>
     */
    private void switchToGameScreenSafely() {
        if (hasSwitched) return;
        hasSwitched = true;
        switchToGameScreen();
    }


    private void placeRoundMarkerAt(int targetIndex) {
        if (roundPlatesBox == null || roundMarker == null) return;
        if (roundPlatesBox.getChildren() == null || roundPlatesBox.getChildren().isEmpty()) return;
        if (targetIndex < 0 || targetIndex >= roundPlatesBox.getChildren().size()) return;

        var parent = roundMarker.getParent();
        if (parent instanceof Pane pane) {
            pane.getChildren().remove(roundMarker);
        }
        StackPane targetPlate = (StackPane) roundPlatesBox.getChildren().get(targetIndex);
        if (!targetPlate.getChildren().contains(roundMarker)) {
            targetPlate.getChildren().add(roundMarker);
        }
    }


    //Stellt sicher, dass alle Spieler-Tokens sichtbar sind
    private void ensureTokensVisibleAndRestored() {
        if (boardOverlayPane == null) return;   // Abbruch, wenn kein Overlay vorhanden ist


        if (tokensByColor.isEmpty()) {
            Map<String, Color> playersByName = !playerColorsByName.isEmpty()
                    ? new LinkedHashMap<>(playerColorsByName)
                    : buildPlayersWithColors(playersSnapshot);
            showPlayersOnFirstField(playersByName);

            // Gespeicherte Feldpositionen wiederherstellen
            for (var e : GLOBAL_FIELD_INDEX_BY_COLOR.entrySet()) {
                ColorToken t = tokensByColor.get(e.getKey());
                if (t != null) t.fieldIndex = e.getValue();
            }
        }

        relayoutAllTokens();

        boardOverlayPane.setVisible(true);
        boardOverlayPane.toFront();
    }

    private Map<String, Color> buildPlayersWithColors(List<String> playersSnapshot) {
        Map<String, Color> out = new LinkedHashMap<>();
        if (playersSnapshot == null || playersSnapshot.isEmpty()) {
            for (int i = 0; i < 4; i++) out.put("Player " + (i + 1), DEFAULT_COLORS.get(i));
            return out;
        }
        // Alle Spielernamen durchgehen
        for (int i = 0; i < playersSnapshot.size(); i++) {
            String name = playersSnapshot.get(i);
            if (name == null || name.isBlank()) continue;

            // Jedem Spielernamen dauerhaft eine Farbe zuweisen, falls noch keine existiert
            GLOBAL_COLOR_BY_PLAYER.computeIfAbsent(name, n -> DEFAULT_COLORS.get(GLOBAL_COLOR_BY_PLAYER.size() % DEFAULT_COLORS.size()));
            Color c = GLOBAL_COLOR_BY_PLAYER.get(name);

            out.put(name, c);

            GLOBAL_FIELD_INDEX_BY_COLOR.putIfAbsent(c, 0);
        }
        return out;
    }

    private void applyMovesFromUserDataIfPresent(Scene scene) {
        Object ud = (scene != null) ? scene.getUserData() : null;
        if (!(ud instanceof SessionBridge sb)) return;
        if (sb.gainedPointsByPlayer == null || sb.gainedPointsByPlayer.isEmpty()) return;

        Map<Color, Integer> stepsByColor = new LinkedHashMap<>();
        for (var e : sb.gainedPointsByPlayer.entrySet()) {
            String playerName = e.getKey();
            int gained = Math.max(0, e.getValue());

            Color c = GLOBAL_COLOR_BY_PLAYER.get(playerName);
            if (c == null) continue;

            stepsByColor.merge(c, gained, Integer::sum);
        }

        if (!stepsByColor.isEmpty()) {
            moveTokensAfterRound(stepsByColor);
        }
    }
}
