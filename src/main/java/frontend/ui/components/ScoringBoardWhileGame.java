package frontend.ui.components;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Point2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overlay für die Anzeige und Animation der Scoring-Tokens während des Spiels.
 */
public class ScoringBoardWhileGame {
    private static final Logger log = LogManager.getLogger(ScoringBoardWhileGame.class);

    private static final int COLS_X = 16;
    private static final int ROWS_Y = 11;
    private static final double TOKEN_RADIUS = 12.0;

    private final StackPane overlayRoot;
    private final ImageView boardImage;
    private final Pane overlayPane;

    private final Map<Integer, ColorToken> tokensByClientId = new LinkedHashMap<>();
    private List<Point2D> fieldPositions = new ArrayList<>();
    private Timeline activeTimeline;

    private static final class ColorToken {
        final Color color;
        final VBox node;
        final Circle circle;
        final javafx.scene.control.Label nameLabel;
        int fieldIndex = 0;

        ColorToken(Color color, VBox node, Circle circle, javafx.scene.control.Label nameLabel) {
            this.color = color;
            this.node = node;
            this.circle = circle;
            this.nameLabel = nameLabel;
        }
    }

    /**
     * Erstellt das Scoring-Overlay.
     *
     * @param overlayRoot Root-Container
     * @param boardImage Hintergrundbild
     * @param overlayPane Overlay-Pane für Tokens
     */
    public ScoringBoardWhileGame(StackPane overlayRoot, ImageView boardImage, Pane overlayPane) {
        this.overlayRoot = overlayRoot;
        this.boardImage = boardImage;
        this.overlayPane = overlayPane;
    }

    /**
     * Initialisiert Bindings und Listener für das Overlay.
     */
    public void initialize() {
        if (overlayRoot == null || overlayPane == null) return;
        overlayRoot.setVisible(false);
        overlayRoot.setManaged(false);

        if (boardImage != null) {
            boardImage.setPreserveRatio(false);
            boardImage.fitWidthProperty().bind(overlayRoot.widthProperty());
            boardImage.fitHeightProperty().bind(overlayRoot.heightProperty());
        }

        overlayPane.prefWidthProperty().bind(overlayRoot.widthProperty());
        overlayPane.prefHeightProperty().bind(overlayRoot.heightProperty());

        overlayRoot.widthProperty().addListener((obs, o, n) -> rebuildFields());
        overlayRoot.heightProperty().addListener((obs, o, n) -> rebuildFields());
    }

    /**
     * Setzt das Hintergrundbild des Boards.
     *
     * @param image Board-Image
     */
    public void setBoardImage(Image image) {
        if (boardImage != null && image != null) {
            boardImage.setImage(image);
        }
    }

    /**
     * Setzt die Token-Farben pro Client-ID.
     *
     * @param colorsByClientId Farben pro Client-ID
     */
    public void setTokens(Map<Integer, Color> colorsByClientId) {
        setTokens(colorsByClientId, null);
    }

    /**
     * Setzt Token-Farben und optionale Namen pro Client-ID.
     *
     * @param colorsByClientId Farben pro Client-ID
     * @param namesByClientId Namen pro Client-ID
     */
    public void setTokens(Map<Integer, Color> colorsByClientId, Map<Integer, String> namesByClientId) {
        if (overlayPane == null || colorsByClientId == null) return;

        // Remove tokens for players who left
        tokensByClientId.keySet().removeIf(id -> !colorsByClientId.containsKey(id));

        for (Map.Entry<Integer, Color> entry : colorsByClientId.entrySet()) {
            int clientId = entry.getKey();
            Color color = entry.getValue();
            String name = (namesByClientId != null) ? namesByClientId.get(clientId) : null;

            if (!tokensByClientId.containsKey(clientId)) {
                // Create new token starting at 0
                ColorToken token = createTokenWithLabel(color, name);
                token.fieldIndex = 0;
                tokensByClientId.put(clientId, token);
                overlayPane.getChildren().add(token.node);
            } else {
                // Update existing token style without resetting position
                ColorToken token = tokensByClientId.get(clientId);
                if (token != null) {
                    token.circle.setFill(color);
                    token.nameLabel.setText(name == null || name.isBlank() ? "Player " + clientId : name);
                }
            }
        }
        relayoutAllTokens();
    }

    /**
     * Zeigt das Scoring-Board an.
     */
    public void showBoard() {
        if (overlayRoot == null) return;
        overlayRoot.setVisible(true);
        overlayRoot.setManaged(true);
        overlayRoot.toFront();

        overlayRoot.applyCss();
        overlayRoot.layout();

        rebuildFields();
        relayoutAllTokens();
    }

    /**
     * Versteckt das Scoring-Board.
     */
    public void hideBoard() {
        if (overlayRoot == null) return;
        overlayRoot.setVisible(false);
        overlayRoot.setManaged(false);
    }

    /**
     * Animiert Tokens zu den angegebenen Scores.
     *
     * @param scoresByClientId Scores pro Client-ID
     * @param onFinished Callback nach Abschluss
     */
    public void animateToScores(Map<Integer, Integer> scoresByClientId, Runnable onFinished) {
        if (scoresByClientId == null || scoresByClientId.isEmpty()) {
            if (onFinished != null) onFinished.run();
            return;
        }

        if (fieldPositions == null || fieldPositions.isEmpty()) {
            rebuildFields();
        }
        if (fieldPositions == null || fieldPositions.isEmpty()) {
            // Son bir kez layout'u zorla
            if (overlayRoot != null) {
                overlayRoot.applyCss();
                overlayRoot.layout();
                rebuildFields();
            }
            if (fieldPositions == null || fieldPositions.isEmpty()) {
                log.debug("ScoringBoard: Layout not ready yet. Retrying in 100ms...");
                new Timeline(new KeyFrame(Duration.millis(100), e ->
                        animateToScores(scoresByClientId, onFinished)
                )).play();
                return;
            }
        }

        if (activeTimeline != null) {
            activeTimeline.stop();
            activeTimeline = null;
        }

        Iterator<Map.Entry<Integer, Integer>> it = scoresByClientId.entrySet().iterator();
        moveNext(it, onFinished);
    }

    private void moveNext(Iterator<Map.Entry<Integer, Integer>> it, Runnable onFinished) {
        if (!it.hasNext()) {
            relayoutAllTokens(); // Final layout fix after animations
            if (onFinished != null) onFinished.run();
            return;
        }

        Map.Entry<Integer, Integer> entry = it.next();
        ColorToken token = tokensByClientId.get(entry.getKey());

        if (token == null) {
            moveNext(it, onFinished);
            return;
        }

        int total = fieldPositions.size(); // Usually 50
        int score = Math.max(0, entry.getValue());
        int targetIndex = (total > 0) ? (score % total) : 0;

        // Calculate steps needed from current position to target
        int currentIndex = token.fieldIndex;
        int steps = (targetIndex - currentIndex + total) % total;

        if (steps == 0) {
            // Already there
            moveNext(it, onFinished);
        } else {
            animateMove(token, steps, () -> moveNext(it, onFinished));
        }
    }

    private void animateMove(ColorToken token, int steps, Runnable onFinished) {
        int total = fieldPositions.size();

        Timeline tl = new Timeline();
        Duration t = Duration.ZERO;
        Duration stepDur = Duration.millis(200); // Speed per step

        // We must calculate the future index for each frame because
        // token.fieldIndex won't update until the animation plays.
        int simulatingIndex = token.fieldIndex;

        for (int s = 0; s < steps; s++) {
            simulatingIndex = (simulatingIndex + 1) % total;
            Point2D pos = fieldPositions.get(simulatingIndex);
            final int indexForFrame = simulatingIndex;

            t = t.add(stepDur);

            tl.getKeyFrames().add(new KeyFrame(t, ev -> {
                // Update visual position during animation
                token.fieldIndex = indexForFrame;
                token.node.setLayoutX(pos.getX());
                token.node.setLayoutY(pos.getY());
            }));
        }

        tl.setOnFinished(ev -> {
            activeTimeline = null;
            if (onFinished != null) onFinished.run();
        });
        activeTimeline = tl;
        tl.play();
    }

    private void rebuildFields() {
        if (overlayRoot == null) return;
        double w = overlayRoot.getWidth();
        double h = overlayRoot.getHeight();
        if (w <= 0 || h <= 0) return;

        // FIXED: Removed 'MARGIN' from the arguments
        fieldPositions = computePerimeterPositions(w, h, COLS_X, ROWS_Y);
        relayoutAllTokens();
    }

    private List<Point2D> computePerimeterPositions(double w, double h, int colsX, int rowsY) {
        // Dynamic margin: Center of the first tile is approx at w/32 and h/22
        double marginX = w / 32.0;
        double marginY = h / 22.0;

        double stepX = (w - 2 * marginX) / (colsX - 1);
        double stepY = (h - 2 * marginY) / (rowsY - 1);

        double xMin = marginX;
        double xMax = w - marginX;
        double yMin = marginY;
        double yMax = h - marginY;

        List<Point2D> pts = new ArrayList<>();

        // 1. Top Edge (Left to Right)
        for (int x = 0; x < colsX; x++) pts.add(new Point2D(xMin + x * stepX, yMin));

        // 2. Right Edge (Top to Bottom)
        for (int y = 1; y < rowsY; y++) pts.add(new Point2D(xMax, yMin + y * stepY));

        // 3. Bottom Edge (Right to Left)
        for (int x = colsX - 2; x >= 0; x--) pts.add(new Point2D(xMin + x * stepX, yMax));

        // 4. Left Edge (Bottom to Top)
        for (int y = rowsY - 2; y >= 1; y--) pts.add(new Point2D(xMin, yMin + y * stepY));

        return pts;
    }

    private ColorToken createTokenWithLabel(Color color, String name) {
        Circle circle = new Circle(TOKEN_RADIUS);
        circle.setFill(color);
        circle.setStroke(Color.WHITE);
        circle.setStrokeWidth(2.5);

        javafx.scene.control.Label label = new javafx.scene.control.Label(
                name == null || name.isBlank() ? "?" : name
        );
        label.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-text-fill: white; "
                + "-fx-padding: 2 6; -fx-background-radius: 6; -fx-font-size: 11px;");

        VBox box = new VBox(4);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.getChildren().addAll(circle, label);
        return new ColorToken(color, box, circle, label);
    }

    private void relayoutAllTokens() {
        if (fieldPositions == null || fieldPositions.isEmpty()) return;

        Map<Integer, List<ColorToken>> byField = new HashMap<>();
        for (ColorToken t : tokensByClientId.values()) {
            byField.computeIfAbsent(t.fieldIndex, k -> new ArrayList<>()).add(t);
        }

        for (Map.Entry<Integer, List<ColorToken>> e : byField.entrySet()) {
            int fieldIdx = e.getKey();
            List<ColorToken> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                placeTokenOnField(list.get(i), fieldIdx, i);
            }
        }
    }

    private void placeTokenOnField(ColorToken token, int fieldIndex, int stackIndex) {
        if (fieldIndex >= fieldPositions.size()) fieldIndex = 0;
        Point2D base = fieldPositions.get(fieldIndex);

        // If multiple tokens are on the same field, spread them out slightly
        int n = Math.max(1, tokensByClientId.size());
        double r = (stackIndex == 0) ? 0 : 20; // First token in center, others around it
        double angle = (2 * Math.PI * stackIndex) / n;

        if (stackIndex > 0) {
            double dx = Math.cos(angle) * r;
            double dy = Math.sin(angle) * r;
            token.node.setLayoutX(base.getX() + dx);
            token.node.setLayoutY(base.getY() + dy);
        } else {
            token.node.setLayoutX(base.getX());
            token.node.setLayoutY(base.getY());
        }

        token.node.toFront();
    }


    public static Color colorForGamePiece(String piece) {
        if (piece == null) return Color.web("#b0b0b0");
        return switch (piece.trim().toLowerCase()) {
            case "red" -> Color.web("#ff3b30");
            case "green" -> Color.web("#34c759");
            case "blue" -> Color.web("#007aff");
            case "yellow" -> Color.web("#ffcc00");
            case "white" -> Color.web("#ffffff");
            default -> Color.web("#b0b0b0");
        };
    }
}
