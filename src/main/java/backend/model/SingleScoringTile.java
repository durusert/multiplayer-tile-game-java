package backend.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Alle einzeln moeglichen Rundenwertungen (A-D) laut Spiel/Protokoll.
 * Die IDs (0-15) entsprechen der Reihenfolge in der Anleitung: erste Zeile IDs 0 und 1, zweite Zeile 2 und 3, usw.
 */
public enum SingleScoringTile {

    // Enum-Konstanten (IDs 0-15 gemäß Protokoll 0.1)
    SQUARE_OF_FOUR(0),

    COMPLETED_AREA(1),

    COMPLETED_AREA_3(2),

    SHEEP_COUNT(3),

    MOUNTAIN_BROCH(4),

    SET_BROCH_FARM_LIGHTHOUSE(5),

    MOST_BARREL_TILES(6),

    MOST_SHIPS(7),

    SHEEP_CATTLE_ADJACENT_FARM(8),

    MOST_GOLD(9),

    CATTLE_CONNECTED_TO_CASTLE(10),

    WATER_SHIP_LIGHTHOUSE_AREAS(11),

    TILES_CONNECTED_TO_CASTLE(12),

    VERTICAL_LINES_OF_THREE(13),

    LARGEST_COMPLETED_WATER_AREA(14),

    COMPLETED_MOUNTAIN_AREAS(15);

    private final int scoringTileId;

    SingleScoringTile(int scoringTileId) {
        this.scoringTileId = scoringTileId;
    }

    // GETTER
    /**
     * Gibt die numerische ID des ScoringTiles zurück.
     *
     * @return ID (0-15)
     */
    public int getScoringTileId() {
        return scoringTileId;
    }

    /**
     * Zieht vier zufaellige ScoringTiles und ordnet sie A-D zu.
     *
     * @return zufällige {@link ScoringTiles}
     */
    public static ScoringTiles drawRandomScoringTiles() {
        List<SingleScoringTile> tiles = new ArrayList<>(List.of(values()));
        Collections.shuffle(tiles);

        return new ScoringTiles(tiles.get(0), tiles.get(1), tiles.get(2), tiles.get(3));
    }
}
