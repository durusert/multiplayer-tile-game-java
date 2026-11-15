package backend.model.tile;

/**
 * Das tatsächlich gelegte Tile.
 */
public record PlacedTile(Tile tile, int rotationDegrees) {

    /**
     * Erzeugt ein platziertes Tile mit Rotation und validiert die Rotation.
     *
     * @param tile            Das zugrundeliegende Tile (Vorlage).
     * @param rotationDegrees Rotation im Uhrzeigersinn (0, 90, 180 oder 270).
     */
    public PlacedTile {
        if (tile == null) {
            throw new IllegalArgumentException("tile must not be null");
        }
        if (!TilePlacementRules.isValidRotation(rotationDegrees)) {
            throw new IllegalArgumentException("Invalid rotation: " + rotationDegrees);
        }
    }
}
