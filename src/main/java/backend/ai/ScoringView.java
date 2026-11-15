package backend.ai;

import java.util.List;

/**
 * Sicht auf aktive Scorings und Tile-Wert-Schätzung.
 */
public interface ScoringView {
    /**
     * Gibt die aktuell aktiven Scoring-IDs zurück.
     *
     * @return Liste aktiver Scorings
     */
    List<String> activeScorings();
    /**
     * Schätzt den Wert eines Tiles.
     *
     * @param tileId Tile-ID
     * @return Schätzwert
     */
    double tileValueEstimate(int tileId);
}
