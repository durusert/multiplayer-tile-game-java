package backend.model.tile;

import backend.model.enums.EdgeType;
import backend.model.enums.TileRoadSide;

/**
 * Enthält Regeln rund um die Platzierung von Tiles:
 *  - gültige Rotationen (0/90/180/270)
 *  - welche Kante liegt nach Rotation an welcher Seite
 *  - sind zwei Kanten kompatibel zueinander
 */
public final class TilePlacementRules {

    private TilePlacementRules() {
    }

    /**
     * Prüft, ob eine Rotation gültig ist (0/90/180/270).
     *
     * @param rotationDegrees Rotation in Grad
     * @return {@code true}, wenn gültig
     */
    public static boolean isValidRotation(int rotationDegrees) {
        return rotationDegrees == 0
                || rotationDegrees == 90
                || rotationDegrees == 180
                || rotationDegrees == 270;
    }

    /**
     * Liefert die Kante, die nach Rotation an einer Seite liegt.
     *
     * @param tile Tile
     * @param side Seite
     * @param rotationDegrees Rotation in Grad
     * @return {@link EdgeType} an der Seite
     */
    public static EdgeType getEdgeAtSide(Tile tile,
                                     TileRoadSide side,
                                     int rotationDegrees) {
        if (tile == null) {
            throw new IllegalArgumentException("tile must not be null");
        }
        if (!isValidRotation(rotationDegrees)) {
            throw new IllegalArgumentException("Invalid rotation: " + rotationDegrees);
        }

        EdgeType top = tile.getTop();
        EdgeType right = tile.getRight();
        EdgeType down = tile.getDown();
        EdgeType left = tile.getLeft();

        int steps = (rotationDegrees / 90) % 4;
        // rotationDegrees is defined as clockwise rotation of the tile.
        // To determine which original edge ends up on a given side after rotation,
        // we must rotate the queried side in the opposite direction.
        int stepsCounterClockwise = Math.floorMod(-steps, 4);

        TileRoadSide rotatedSide = rotateSide(side, stepsCounterClockwise);

        return switch (rotatedSide) {
            case TOP   -> top;
            case RIGHT -> right;
            case DOWN  -> down;
            case LEFT  -> left;
        };
    }

    /**
     * Liefert die Kante an einer Seite für ein platziertes Tile.
     *
     * @param placedTile platziertes Tile
     * @param side Seite
     * @return {@link EdgeType}
     */
    public static EdgeType getEdgeAtSide(PlacedTile placedTile,
                                         TileRoadSide side) {
        return getEdgeAtSide(placedTile.tile(), side, placedTile.rotationDegrees());
    }

    /**
     * Dreht eine Tile-Seite um eine Anzahl von 90°-Schritten im Uhrzeigersinn.
     */
    private static TileRoadSide rotateSide(TileRoadSide original, int stepsClockwise) {
        TileRoadSide result = original;
        for (int i = 0; i < stepsClockwise; i++) {
            result = switch (result) {
                case TOP   -> TileRoadSide.RIGHT;
                case RIGHT -> TileRoadSide.DOWN;
                case DOWN  -> TileRoadSide.LEFT;
                case LEFT  -> TileRoadSide.TOP;
            };
        }
        return result;
    }

    /**
     * Prüft, ob zwei Kanten kompatibel sind.
     *
     * @param tileA Tile A
     * @param rotationA Rotation A
     * @param sideA Seite A
     * @param tileB Tile B
     * @param rotationB Rotation B
     * @param sideB Seite B
     * @return {@code true}, wenn kompatibel
     */
    public static boolean edgesAreCompatible(Tile tileA, int rotationA, TileRoadSide sideA,
                                             Tile tileB, int rotationB, TileRoadSide sideB) {
        EdgeType edgeA = getEdgeAtSide(tileA, sideA, rotationA);
        EdgeType edgeB = getEdgeAtSide(tileB, sideB, rotationB);
        return edgeA == edgeB;
    }

    /**
     * Prüft, ob zwei Kanten kompatibel sind (PlacedTile-Variante).
     *
     * @param a platziertes Tile A
     * @param sideA Seite A
     * @param b platziertes Tile B
     * @param sideB Seite B
     * @return {@code true}, wenn kompatibel
     */
    public static boolean edgesAreCompatible(PlacedTile a, TileRoadSide sideA,
                                             PlacedTile b, TileRoadSide sideB) {
        return edgesAreCompatible(
                a.tile(), a.rotationDegrees(), sideA,
                b.tile(), b.rotationDegrees(), sideB
        );
    }
}
