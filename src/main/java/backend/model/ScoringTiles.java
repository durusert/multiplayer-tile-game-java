package backend.model;

/**
 * Enthält die vier ausgelegten Rundenwertungen A-D für ein Spiel.
 *
 * @param tileA ScoringTile für Runde A
 * @param tileB ScoringTile für Runde B
 * @param tileC ScoringTile für Runde C
 * @param tileD ScoringTile für Runde D
 */
public record ScoringTiles(SingleScoringTile tileA, SingleScoringTile tileB, SingleScoringTile tileC,
                           SingleScoringTile tileD) {

    /**
     * Erstellt explizit gesetzte A-D ScoringTiles.
     */
    public ScoringTiles {
    }

    /**
     * Leere Variante (z.B. fuer Lobby/Setup).
     */
    public ScoringTiles() {
        this(null, null, null, null);
    }
}
