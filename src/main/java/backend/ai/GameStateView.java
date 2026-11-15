package backend.ai;

import java.util.List;

/**
 * Schnittstelle für eine vereinfachte Spielzustandsansicht für die KI.
 */
public interface GameStateView {

    /**
     * Bewertet eine Platzierung.
     *
     * @param tileId Tile-ID
     * @param x x-Koordinate
     * @param y y-Koordinate
     * @param rotation Rotation in Grad
     * @return Bewertungswert
     */
    double evaluatePlacement(int tileId, int x, int y, int rotation);

    /**
     * Schätzt den Wert eines Tiles (optional).
     *
     * @param tileId Tile-ID
     * @return Schätzwert
     */
    default double estimateTileValue(int tileId) {
        return 0.0;
    }
}
