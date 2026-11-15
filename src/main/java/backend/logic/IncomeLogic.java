package backend.logic;

import backend.model.tile.RoadConnectivity;
import backend.model.ClanTerritory;
import backend.model.Player;
import backend.model.Position;
import backend.model.enums.TileElement;
import backend.model.Game;
import backend.model.tile.PlacedTile;

import java.util.Set;

/**
 * Enthält Logik zur Einkommensberechnung.
 */
public class IncomeLogic {

    /**
     * Zählt Whiskey-Fässer, die per Straße mit der Burg verbunden sind.
     *
     * @param game Spiel
     * @param player Spieler
     * @return Anzahl verbundener Fässer
     */
    public static int connectedBarrels(Game game, Player player) {
        ClanTerritory territory = player.getClanTerritory();
        if (territory == null || territory.getTiles().isEmpty()) return 0;

        // alle per Straße mit der Burg verbundenen Positionen
        Set<Position> reachable = RoadConnectivity.connectedToCastle(territory);

        int barrels = 0;
        for (Position pos : reachable) {
            PlacedTile pt = territory.getTileAt(pos.x(), pos.y());
            if (pt != null && pt.tile().hasElement(TileElement.WHISKEY)) {
                barrels++;
            }
        }
        return barrels;
    }

    // Catch-Up je Runde, ab Runde 3 (Index = round-1)
    private static final int[] CATCH_UP_2_TO_4 = {0, 0, 1, 1, 2, 3}; // 6 Runden-Board
    private static final int[] CATCH_UP_5      = {0, 0, 1, 2, 3};    // 5 Runden-Board

    /**
     * Berechnet Aufholmünzen anhand der aktuellen Runde und relativer Siegpunkte.
     *
     * @param game Spiel
     * @param player Spieler
     * @return zusätzliche Münzen für Rückstand
     */
    public static int catchUpCoins(Game game, Player player) {
        int round = game.getCurrentRound();
        int[] table = game.getPlayers().size() == 5 ? CATCH_UP_5 : CATCH_UP_2_TO_4;

        if (round < 1) return 0;

        int idx = Math.min(round - 1, table.length - 1);
        int perPlayerAhead = table[idx];
        if (perPlayerAhead == 0) return 0;

        // berechnet wie viele Spieler mehr VP als man selbst
        int ahead = 0;
        int myVp = player.getVictoryPoints();
        for (Player p : game.getPlayers()) {
            if (p != player && p.getVictoryPoints() > myVp) {
                ahead++;
            }
        }
        return ahead * perPlayerAhead;
    }
}
