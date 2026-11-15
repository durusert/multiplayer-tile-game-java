package frontend.ui.components;

import com.google.gson.JsonObject;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UI-Helfer für das Platzieren von Tiles im Spielbrett.
 */
public class PlaceTileInGame implements PlaceTileInGameApi {

    /**
     * Koordinate im Board.
     *
     * @param x x-Koordinate
     * @param y y-Koordinate
     */
    public record Coord(int x, int y) {}

    /**
     * Interner Zustand eines platzierten Tiles.
     *
     * @param tileJson Tile-JSON
     * @param rotationDegrees Rotation in Grad
     */
    private record PlacedTileState(JsonObject tileJson, int rotationDegrees) {}

    /**
     * Callback zum Senden eines Build-Befehls.
     */
    public interface BuildSender {
        void send(int tileId, int x, int y, int rotation);
    }

    /**
     * Callback zum Setzen einer Statusmeldung.
     */
    public interface StatusSetter {
        void setStatus(String message);
    }

    /**
     * Callback bei Rotationsänderungen.
     */
    public interface RotationChanged {
        void onRotationChanged(int rotation);
    }

    private final StackPane boardRoot;
    private final GridPane boardGrid;
    private final int boardMin;
    private final int boardMax;
    private final int tileSize;
    private final int cellSize;
    private final BuildSender buildSender;
    private final StatusSetter statusSetter;
    private final RotationChanged rotationChanged;

    private final Map<Coord, StackPane> boardCells = new LinkedHashMap<>();
    private final Map<Coord, PlacedTileState> placedTiles = new LinkedHashMap<>();
    private final Set<Coord> highlightedCells = new HashSet<>();

    private boolean buildActive;
    private Integer selectedHandTileId;
    private JsonObject selectedTileJson;
    private int selectedRotation = 0;

    private Pane buildOverlayPane;
    private TileView dragPreview;
    private Integer dragPreviewTileId;
    private Button rotateLeftButton;
    private Button rotateRightButton;
    private Button confirmPlacementButton;
    private Coord snappedCoord;
    private boolean hasAnyLegalPlacement;

    /**
     * Erstellt den Tile-Platzierungs-Controller.
     *
     * @param boardRoot Root-Container
     * @param boardGrid Grid des Boards
     * @param boardMin minimale Koordinate
     * @param boardMax maximale Koordinate
     * @param tileSize Tile-Größe in Pixel
     * @param cellSize Zellengröße in Pixel
     * @param buildSender Callback für Build-Requests
     * @param statusSetter Callback für Statusmeldungen
     * @param rotationChanged Callback für Rotationsänderungen
     */
    public PlaceTileInGame(StackPane boardRoot,
                           GridPane boardGrid,
                           int boardMin,
                           int boardMax,
                           int tileSize,
                           int cellSize,
                           BuildSender buildSender,
                           StatusSetter statusSetter,
                           RotationChanged rotationChanged) {
        this.boardRoot = boardRoot;
        this.boardGrid = boardGrid;
        this.boardMin = boardMin;
        this.boardMax = boardMax;
        this.tileSize = tileSize;
        this.cellSize = cellSize;
        this.buildSender = buildSender;
        this.statusSetter = statusSetter;
        this.rotationChanged = rotationChanged;
    }

    /**
     * Registriert eine Zelle im Board.
     *
     * @param x x-Koordinate
     * @param y y-Koordinate
     * @param cell Zell-Node
     */
    public void registerCell(int x, int y, StackPane cell) {
        if (cell == null) return;
        boardCells.put(new Coord(x, y), cell);
    }

    /**
     * Leert das Board und interne Zustände.
     */
    public void clearBoard() {
        boardCells.clear();
        placedTiles.clear();
        clearPlacementHints();
    }

    /**
     * Aktiviert oder deaktiviert die Build-Interaktion.
     *
     * @param buildActive {@code true} wenn aktiv
     */
    public void setBuildActive(boolean buildActive) {
        this.buildActive = buildActive;
        if (!buildActive) {
            hideBuildPreview();
        }
    }

    /**
     * Setzt das ausgewählte Hand-Tile.
     *
     * @param tileId Tile-ID
     * @param tileJson Tile-JSON
     */
    public void setSelectedTile(Integer tileId, JsonObject tileJson) {
        this.selectedHandTileId = tileId;
        this.selectedTileJson = tileJson;
        updatePlacementHints();
    }

    /**
     * Gibt die aktuelle Rotation zurück.
     *
     * @return Rotation in Grad
     */
    public int getSelectedRotation() {
        return selectedRotation;
    }

    /**
     * Setzt die Rotation auf 0 zurück.
     */
    public void resetRotation() {
        selectedRotation = 0;
        rotationChanged.onRotationChanged(selectedRotation);
        updatePlacementHints();
        updateDragPreviewRotation();
    }

    /**
     * Mouse-Move-Handler für Drag-Preview und Snapping.
     *
     * @param e Mouse-Event
     */
    public void handleMouseMoved(MouseEvent e) {
        if (!buildActive || selectedHandTileId == null) {
            hideBuildPreview();
            return;
        }
        ensureDragPreview();
        if (dragPreview == null) return;

        if (isHoveringPlacementControls()) {
            return;
        }

        if (!hasAnyLegalPlacement) {
            snappedCoord = null;
            hidePlacementControls();
            followMouseWithPreview(e.getX(), e.getY());
            showRotationControlsOnly();
            return;
        }

        if (snappedCoord != null && isWithinSnapRadius(e.getSceneX(), e.getSceneY())) {
            showPlacementControls();
            return;
        }

        Coord coord = coordFromMouse(e);
        if (coord != null && highlightedCells.contains(coord)) {
            snappedCoord = coord;
            snapPreviewToCell(coord);
            showPlacementControls();
        } else {
            snappedCoord = null;
            hidePlacementControls();
            followMouseWithPreview(e.getX(), e.getY());
        }
    }

    /**
     * Mouse-Exit-Handler für das Board.
     */
    public void handleMouseExited() {
        hideBuildPreview();
    }

    /**
     * Handler für Klick auf eine Board-Zelle.
     *
     * @param x x-Koordinate
     * @param y y-Koordinate
     */
    public void onBoardCellClicked(int x, int y) {
        if (!buildActive) {
            statusSetter.setStatus("Not in BUILD phase.");
            return;
        }
        if (selectedHandTileId == null || selectedTileJson == null) {
            statusSetter.setStatus("Select a tile first.");
            return;
        }
        StackPane cell = boardCells.get(new Coord(x, y));
        if (cell == null) return;
        if (cell.getChildren().stream().anyMatch(n -> n instanceof TileView)) {
            statusSetter.setStatus("Cell is already occupied.");
            return;
        }

        boolean anyLegal = hasAnyLegalPlacementLocal();
        boolean localLegal = canPlaceLocal(selectedTileJson, x, y, selectedRotation);
        if (anyLegal && !localLegal) {
            statusSetter.setStatus("Attempting placement (not highlighted) - server will validate.");
        }

        buildSender.send(selectedHandTileId, x, y, selectedRotation);
        statusSetter.setStatus("BuildTile sent: tile " + selectedHandTileId + " at (" + x + "," + y + ") rot=" + selectedRotation);
    }

    /**
     * Platziert ein Tile visuell im Board.
     *
     * @param tileId Tile-ID
     * @param x x-Koordinate
     * @param y y-Koordinate
     * @param rotation Rotation in Grad
     * @param tileJson Tile-JSON
     */
    public void placeTileOnBoard(int tileId, int x, int y, int rotation, JsonObject tileJson) {
        StackPane cell = boardCells.get(new Coord(x, y));
        if (cell == null) {
            System.err.println("Error: Trying to place tile on invalid cell: " + x + "," + y);
            return;
        }

        cell.getChildren().removeIf(n -> n instanceof TileView);

        JsonObject resolved = tileJson;
        if (resolved == null) {
            resolved = new JsonObject();
            resolved.addProperty("id", tileId);
            resolved.addProperty("top", "none");
            resolved.addProperty("right", "none");
            resolved.addProperty("down", "none");
            resolved.addProperty("left", "none");
        }

        TileView view = new TileView(resolved, tileSize);
        view.setRotate(rotation);
        view.setMouseTransparent(true);
        cell.getChildren().add(view);
        placedTiles.put(new Coord(x, y), new PlacedTileState(resolved, rotation));
        updatePlacementHints();
    }

    /**
     * Aktualisiert die Platzierungshinweise für das ausgewählte Tile.
     */
    public void updatePlacementHints() {
        clearPlacementHints();
        hasAnyLegalPlacement = false;
        if (!buildActive) return;
        if (selectedHandTileId == null || selectedTileJson == null) return;

        Set<Coord> candidates = candidatePositions();
        boolean anyLegalAnyRotation = false;
        boolean anyLegalCurrentRotation = false;

        for (Coord pos : candidates) {
            for (int rot : new int[]{0, 90, 180, 270}) {
                if (canPlaceLocal(selectedTileJson, pos.x, pos.y, rot)) {
                    anyLegalAnyRotation = true;
                    if (rot == selectedRotation) {
                        anyLegalCurrentRotation = true;
                    }
                }
            }

            if (canPlaceLocal(selectedTileJson, pos.x, pos.y, selectedRotation)) {
                setCellHighlight(pos, true);
                highlightedCells.add(pos);
            }
        }

        if (!anyLegalAnyRotation) {
            statusSetter.setStatus("No legal placement for this tile (click any cell to return it).");
        } else if (!anyLegalCurrentRotation) {
            statusSetter.setStatus("No legal placement for rotation " + selectedRotation + " - try rotating.");
        }
        hasAnyLegalPlacement = anyLegalAnyRotation;
    }

    public void rotateLeft() {
        if (!buildActive) return;
        selectedRotation = (selectedRotation + 270) % 360;
        rotationChanged.onRotationChanged(selectedRotation);
        updatePlacementHints();
        updateDragPreviewRotation();
    }

    public void rotateRight() {
        if (!buildActive) return;
        selectedRotation = (selectedRotation + 90) % 360;
        rotationChanged.onRotationChanged(selectedRotation);
        updatePlacementHints();
        updateDragPreviewRotation();
    }

    public void autoPlace() {
        if (!buildActive) return;
        if (selectedHandTileId == null || selectedTileJson == null) {
            statusSetter.setStatus("Select a tile first.");
            return;
        }
        List<Coord> candidates = new java.util.ArrayList<>(candidatePositions());
        candidates.sort(java.util.Comparator
                .comparingInt((Coord c) -> Math.abs(c.x) + Math.abs(c.y))
                .thenComparingInt(c -> c.y)
                .thenComparingInt(c -> c.x));

        int[] rotations = new int[]{selectedRotation, 0, 90, 180, 270};
        for (int rot : rotations) {
            if (!isValidRotation(rot)) continue;
            for (Coord pos : candidates) {
                if (canPlaceLocal(selectedTileJson, pos.x, pos.y, rot)) {
                    selectedRotation = rot;
                    rotationChanged.onRotationChanged(selectedRotation);
                    buildSender.send(selectedHandTileId, pos.x, pos.y, rot);
                    statusSetter.setStatus("AutoPlace: tile " + selectedHandTileId + " at (" + pos.x + "," + pos.y + ") rot=" + rot);
                    return;
                }
            }
        }

        Coord fallback = candidates.isEmpty() ? new Coord(0, 0) : candidates.get(0);
        buildSender.send(selectedHandTileId, fallback.x, fallback.y, selectedRotation);
        statusSetter.setStatus("AutoPlace: no legal spot found - sent attempt for server validation.");
    }

    public boolean hasAnyLegalPlacementLocal() {
        if (selectedTileJson == null) return false;
        for (Coord pos : candidatePositions()) {
            for (int rot : new int[]{0, 90, 180, 270}) {
                if (canPlaceLocal(selectedTileJson, pos.x, pos.y, rot)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setupBuildOverlayControls() {
        if (boardRoot == null) return;
        if (buildOverlayPane == null) {
            buildOverlayPane = new Pane();
            buildOverlayPane.setPickOnBounds(false);
            boardRoot.getChildren().add(buildOverlayPane);
        }

        if (rotateLeftButton == null) {
            rotateLeftButton = new Button("⟲");
            rotateLeftButton.getStyleClass().add("game-button");
            rotateLeftButton.setStyle("-fx-background-color: rgba(255,255,255,0.15); -fx-text-fill: white;");
            rotateLeftButton.setOnAction(e -> rotateLeft());
            buildOverlayPane.getChildren().add(rotateLeftButton);
        }
        if (rotateRightButton == null) {
            rotateRightButton = new Button("⟳");
            rotateRightButton.getStyleClass().add("game-button");
            rotateRightButton.setStyle("-fx-background-color: rgba(255,255,255,0.15); -fx-text-fill: white;");
            rotateRightButton.setOnAction(e -> rotateRight());
            buildOverlayPane.getChildren().add(rotateRightButton);
        }
        if (confirmPlacementButton == null) {
            confirmPlacementButton = new Button("Confirm");
            confirmPlacementButton.setStyle("-fx-background-color: rgba(255,255,255,0.25); "
                    + "-fx-text-fill: white; -fx-border-color: rgba(255,255,255,0.8); "
                    + "-fx-border-radius: 10; -fx-background-radius: 10;");
            confirmPlacementButton.setOnAction(e -> confirmPlacement());
            buildOverlayPane.getChildren().add(confirmPlacementButton);
        }

        rotateLeftButton.setVisible(false);
        rotateLeftButton.setManaged(false);
        rotateRightButton.setVisible(false);
        rotateRightButton.setManaged(false);
        confirmPlacementButton.setVisible(false);
        confirmPlacementButton.setManaged(false);
    }

    private void confirmPlacement() {
        if (!buildActive) {
            statusSetter.setStatus("Not in BUILD phase.");
            return;
        }
        if (selectedHandTileId == null || snappedCoord == null) {
            statusSetter.setStatus("Move the tile onto a valid spot first.");
            return;
        }
        buildSender.send(selectedHandTileId, snappedCoord.x, snappedCoord.y, selectedRotation);
        statusSetter.setStatus("BuildTile sent: tile " + selectedHandTileId + " at (" + snappedCoord.x + "," + snappedCoord.y + ") rot=" + selectedRotation);
    }

    private void ensureDragPreview() {
        if (!buildActive) return;
        if (selectedHandTileId == null || selectedTileJson == null) return;

        setupBuildOverlayControls();

        if (dragPreview == null || dragPreviewTileId == null || !dragPreviewTileId.equals(selectedHandTileId)) {
            if (dragPreview != null && buildOverlayPane != null) {
                buildOverlayPane.getChildren().remove(dragPreview);
            }
            dragPreview = new TileView(selectedTileJson, tileSize);
            dragPreview.setMouseTransparent(true);
            dragPreviewTileId = selectedHandTileId;
            if (buildOverlayPane != null) {
                buildOverlayPane.getChildren().add(dragPreview);
            }
        }
        dragPreview.setRotate(selectedRotation);
        dragPreview.setVisible(true);
        dragPreview.setManaged(true);
    }

    private void updateDragPreviewRotation() {
        if (dragPreview != null) {
            dragPreview.setRotate(selectedRotation);
        }
        if (snappedCoord != null && !highlightedCells.contains(snappedCoord)) {
            snappedCoord = null;
            hidePlacementControls();
        }
    }

    private Coord coordFromMouse(MouseEvent e) {
        if (boardGrid == null) return null;
        Point2D p = boardGrid.sceneToLocal(e.getSceneX(), e.getSceneY());
        Insets pad = boardGrid.getPadding();
        double x = p.getX() - pad.getLeft();
        double y = p.getY() - pad.getTop();
        if (x < 0 || y < 0) return null;

        double cellSpanX = cellSize + boardGrid.getHgap();
        double cellSpanY = cellSize + boardGrid.getVgap();
        int col = (int) Math.floor(x / cellSpanX);
        int row = (int) Math.floor(y / cellSpanY);
        int size = boardMax - boardMin + 1;
        if (col < 0 || row < 0 || col >= size || row >= size) return null;
        int gridX = col + boardMin;
        int gridY = boardMax - row;
        Coord coord = new Coord(gridX, gridY);
        return boardCells.containsKey(coord) ? coord : null;
    }

    private void snapPreviewToCell(Coord coord) {
        if (dragPreview == null) return;
        StackPane cell = boardCells.get(coord);
        if (cell == null) return;
        Bounds b = cell.localToScene(cell.getBoundsInLocal());
        Point2D p = boardRoot.sceneToLocal(b.getMinX(), b.getMinY());
        double inset = (cellSize - tileSize) / 2.0;
        dragPreview.relocate(p.getX() + inset, p.getY() + inset);
    }

    private void followMouseWithPreview(double x, double y) {
        if (dragPreview == null) return;
        dragPreview.relocate(x - tileSize / 2.0, y - tileSize / 2.0);
    }

    private void showPlacementControls() {
        if (dragPreview == null || rotateLeftButton == null || rotateRightButton == null || confirmPlacementButton == null) {
            return;
        }
        double x = dragPreview.getLayoutX();
        double y = dragPreview.getLayoutY();
        double size = tileSize;
        double btnSize = Math.max(28, size * 0.28);
        double gap = Math.max(6, size * 0.08);

        rotateLeftButton.setPrefSize(btnSize, btnSize);
        rotateRightButton.setPrefSize(btnSize, btnSize);
        rotateLeftButton.relocate(x + size / 2.0 - btnSize - gap / 2.0, y - btnSize - gap);
        rotateRightButton.relocate(x + size / 2.0 + gap / 2.0, y - btnSize - gap);
        rotateLeftButton.setVisible(true);
        rotateLeftButton.setManaged(true);
        rotateRightButton.setVisible(true);
        rotateRightButton.setManaged(true);

        double confirmH = Math.max(24, size * 0.22);
        confirmPlacementButton.setPrefWidth(size);
        confirmPlacementButton.setPrefHeight(confirmH);
        confirmPlacementButton.relocate(x, y + size + gap);
        confirmPlacementButton.setVisible(true);
        confirmPlacementButton.setManaged(true);
    }

    private void showRotationControlsOnly() {
        if (dragPreview == null || rotateLeftButton == null || rotateRightButton == null || confirmPlacementButton == null) {
            return;
        }
        double x = dragPreview.getLayoutX();
        double y = dragPreview.getLayoutY();
        double size = tileSize;
        double btnSize = Math.max(28, size * 0.28);
        double gap = Math.max(6, size * 0.08);

        rotateLeftButton.setPrefSize(btnSize, btnSize);
        rotateRightButton.setPrefSize(btnSize, btnSize);
        rotateLeftButton.relocate(x + size / 2.0 - btnSize - gap / 2.0, y - btnSize - gap);
        rotateRightButton.relocate(x + size / 2.0 + gap / 2.0, y - btnSize - gap);
        rotateLeftButton.setVisible(true);
        rotateLeftButton.setManaged(true);
        rotateRightButton.setVisible(true);
        rotateRightButton.setManaged(true);

        confirmPlacementButton.setVisible(false);
        confirmPlacementButton.setManaged(false);
    }

    private void hidePlacementControls() {
        if (rotateLeftButton != null) {
            rotateLeftButton.setVisible(false);
            rotateLeftButton.setManaged(false);
        }
        if (rotateRightButton != null) {
            rotateRightButton.setVisible(false);
            rotateRightButton.setManaged(false);
        }
        if (confirmPlacementButton != null) {
            confirmPlacementButton.setVisible(false);
            confirmPlacementButton.setManaged(false);
        }
    }

    private boolean isHoveringPlacementControls() {
        return (rotateLeftButton != null && rotateLeftButton.isHover())
                || (rotateRightButton != null && rotateRightButton.isHover())
                || (confirmPlacementButton != null && confirmPlacementButton.isHover());
    }

    private boolean isWithinSnapRadius(double sceneX, double sceneY) {
        if (dragPreview == null) return false;
        double size = tileSize;
        double cx = dragPreview.getLayoutX() + size / 2.0;
        double cy = dragPreview.getLayoutY() + size / 2.0;
        Point2D p = boardRoot.sceneToLocal(sceneX, sceneY);
        double dx = p.getX() - cx;
        double dy = p.getY() - cy;
        double radius = size * 0.9;
        return (dx * dx + dy * dy) <= (radius * radius);
    }

    private void hideBuildPreview() {
        snappedCoord = null;
        if (dragPreview != null) {
            dragPreview.setVisible(false);
            dragPreview.setManaged(false);
        }
        hidePlacementControls();
    }

    private void clearPlacementHints() {
        for (Coord c : highlightedCells) {
            setCellHighlight(c, false);
        }
        highlightedCells.clear();
    }

    private void setCellHighlight(Coord coord, boolean highlighted) {
        StackPane cell = boardCells.get(coord);
        if (cell == null) return;
        Object bgObj = cell.getProperties().get("bg");
        if (!(bgObj instanceof Rectangle bg)) return;

        if (highlighted) {
            bg.setStroke(javafx.scene.paint.Color.web("#2ecc71"));
            bg.setStrokeWidth(3);
        } else {
            bg.setStroke(javafx.scene.paint.Color.web("#d0d0d0"));
            bg.setStrokeWidth(1);
        }
    }

    private Set<Coord> candidatePositions() {
        Set<Coord> spots = new HashSet<>();
        if (placedTiles.isEmpty()) {
            spots.add(new Coord(0, 0));
            return spots;
        }

        for (Coord pos : placedTiles.keySet()) {
            int x = pos.x;
            int y = pos.y;
            Coord[] neighbors = {
                    new Coord(x, y + 1),
                    new Coord(x, y - 1),
                    new Coord(x + 1, y),
                    new Coord(x - 1, y)
            };
            for (Coord n : neighbors) {
                if (n.x < boardMin || n.x > boardMax || n.y < boardMin || n.y > boardMax) continue;
                if (placedTiles.containsKey(n)) continue;
                spots.add(n);
            }
        }

        return spots;
    }

    private boolean canPlaceLocal(JsonObject tileJson, int x, int y, int rotationDegrees) {
        if (tileJson == null) return false;
        if (!isValidRotation(rotationDegrees)) return false;
        if (x < boardMin || x > boardMax || y < boardMin || y > boardMax) return false;

        Coord wanted = new Coord(x, y);
        if (placedTiles.containsKey(wanted)) return false;

        if (placedTiles.isEmpty()) {
            return x == 0 && y == 0;
        }

        boolean hasNeighbor = false;

        PlacedTileState up = placedTiles.get(new Coord(x, y + 1));
        if (up != null) {
            hasNeighbor = true;
            if (!edgesCompatible(tileJson, rotationDegrees, Side.TOP, up, Side.DOWN)) return false;
        }

        PlacedTileState down = placedTiles.get(new Coord(x, y - 1));
        if (down != null) {
            hasNeighbor = true;
            if (!edgesCompatible(tileJson, rotationDegrees, Side.DOWN, down, Side.TOP)) return false;
        }

        PlacedTileState right = placedTiles.get(new Coord(x + 1, y));
        if (right != null) {
            hasNeighbor = true;
            if (!edgesCompatible(tileJson, rotationDegrees, Side.RIGHT, right, Side.LEFT)) return false;
        }

        PlacedTileState left = placedTiles.get(new Coord(x - 1, y));
        if (left != null) {
            hasNeighbor = true;
            if (!edgesCompatible(tileJson, rotationDegrees, Side.LEFT, left, Side.RIGHT)) return false;
        }

        return hasNeighbor;
    }

    private static boolean isValidRotation(int rotationDegrees) {
        return rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270;
    }

    private static boolean edgesCompatible(JsonObject tileJson,
                                           int rotationDegrees,
                                           Side side,
                                           PlacedTileState neighbor,
                                           Side neighborSide) {
        if (neighbor == null || neighbor.tileJson == null) return false;

        String a = edgeAt(tileJson, side, rotationDegrees);
        String b = edgeAt(neighbor.tileJson, neighborSide, neighbor.rotationDegrees);
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private enum Side { TOP, RIGHT, DOWN, LEFT }

    private static String edgeAt(JsonObject tileJson, Side side, int rotationDegrees) {
        String top = getString(tileJson, "top");
        String right = getString(tileJson, "right");
        String down = getString(tileJson, "down");
        String left = getString(tileJson, "left");

        int steps = Math.floorMod(rotationDegrees / 90, 4);
        return switch (steps) {
            case 0 -> switch (side) {
                case TOP -> top;
                case RIGHT -> right;
                case DOWN -> down;
                case LEFT -> left;
            };
            case 1 -> switch (side) { // 90° clockwise
                case TOP -> left;
                case RIGHT -> top;
                case DOWN -> right;
                case LEFT -> down;
            };
            case 2 -> switch (side) {
                case TOP -> down;
                case RIGHT -> left;
                case DOWN -> top;
                case LEFT -> right;
            };
            case 3 -> switch (side) {
                case TOP -> right;
                case RIGHT -> down;
                case DOWN -> left;
                case LEFT -> top;
            };
            default -> null;
        };
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }
}
