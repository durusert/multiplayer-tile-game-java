package backend.logic;

import backend.model.*;
import backend.model.enums.EdgeType;
import backend.model.enums.TileElement;
import backend.model.tile.PlacedTile;
import backend.model.tile.RoadConnectivity;
import backend.model.tile.Tile;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Berechnet Runden- und Schlusswertung nach den ScoringTiles.
 */
public class ScoringLogic {

    private static final Logger log =
            LogManager.getLogger(ScoringLogic.class);

    /**
     * Führt das Rundenscoring anhand der aktiven ScoringTiles durch.
     *
     * @param game Spiel
     */
    public void scoreRound(Game game) {
        ScoringTiles scoringTiles = game.getScoringTiles();
        if (scoringTiles == null) {
            return;
        }
        int round = game.getCurrentRound();
        boolean five = game.getPlayers().size() == 5;

        // schedule auswahl 5 oder 2-4 Spieler
        char[][] schedule = five ? SCHEDULE_FIVE_PLAYERS : SCHEDULE_TWO_TO_FOUR;
        int index = round - 1;
        if (index < 0 || index >= schedule.length) {
            return;
        }
        for (char slot : schedule[index]) {
            SingleScoringTile tile = getScoringTile(scoringTiles, slot);
            if (log.isDebugEnabled()) {
                log.debug("Round {} slot {} -> scoringTile={} (id={})",
                        round,
                        slot,
                        tile,
                        tile == null ? "null" : tile.getScoringTileId());
            }
            applyScoringTile(game, tile);
        }
    }

    // 2-dimensionale Arrays mit aktiven ScoringTiles pro Runde
    private static final char[][] SCHEDULE_TWO_TO_FOUR = {
            {'A'},
            {'B'},
            {'A', 'C'},
            {'B', 'D'},
            {'C', 'D', 'A'},
            {'B', 'D', 'C'}
    };

    private static final char[][] SCHEDULE_FIVE_PLAYERS = {
            {'A', 'B'},
            {'A', 'C'},
            {'B', 'D'},
            {'A', 'D', 'C'},
            {'B', 'D', 'C'}
    };

    // liefert SingleScoringTile
    /**
     * Liefert das ScoringTile für einen Slot (A-D).
     *
     * @param tiles ScoringTiles
     * @param slot Slot-Kennzeichen
     * @return passendes {@link SingleScoringTile} oder {@code null}
     */
    private SingleScoringTile getScoringTile(ScoringTiles tiles, char slot) {
        return switch (slot) {
            case 'A' -> tiles.tileA();
            case 'B' -> tiles.tileB();
            case 'C' -> tiles.tileC();
            case 'D' -> tiles.tileD();
            default -> null;
        };
    }

    /**
     * Wendet die Wertungslogik eines einzelnen ScoringTiles an.
     *
     * @param game Spiel
     * @param tile ScoringTile
     */
    private void applyScoringTile(Game game, SingleScoringTile tile) {
        if (tile == null) {
            return;
        }

        // switch über Enum SingleScoringTile (mappt Name zu Tile-ID)
        switch (tile) {
            case SQUARE_OF_FOUR -> scoreSquareOfFour(game, 2);
            case COMPLETED_AREA -> scoreCompletedAreas(game, 1, 1);
            case COMPLETED_AREA_3 -> scoreCompletedAreas(game, 3, 3);
            case SHEEP_COUNT -> scorePerElement(game, TileElement.SHEEP, 1);
            case MOUNTAIN_BROCH -> scoreMountainBroch(game);
            case SET_BROCH_FARM_LIGHTHOUSE -> scoreSetBrochFarmLighthouse(game);
            case MOST_BARREL_TILES -> scoreMajorityForElement(game, TileElement.WHISKEY, 5, 2);
            case MOST_SHIPS -> scoreMajorityForElement(game, TileElement.SHIP, 5, 2);
            case SHEEP_CATTLE_ADJACENT_FARM -> scoreSheepCattleAdjacentFarm(game);
            case MOST_GOLD -> scoreMostGold(game);
            case CATTLE_CONNECTED_TO_CASTLE -> scoreCattleConnectedToCastle(game);
            case WATER_SHIP_LIGHTHOUSE_AREAS -> scoreWaterShipLighthouseAreas(game);
            case TILES_CONNECTED_TO_CASTLE -> scoreTilesConnectedToCastle(game);
            case VERTICAL_LINES_OF_THREE -> scoreVerticalLinesOfThree(game);
            case LARGEST_COMPLETED_WATER_AREA -> scoreLargestCompletedWaterArea(game);
            case COMPLETED_MOUNTAIN_AREAS -> scoreCompletedMountainAreas(game);
        }
    }

    /** Zaehlt 2x2-Quadrate im Territory und vergibt vpPerSquare. */
    private void scoreSquareOfFour(Game game, int vpPerSquare) {
        for (Player player : game.getPlayers()) {
            ClanTerritory territory = player.getClanTerritory();
            if (territory == null) {
                continue;
            }

            Set<Position> positions = territory.getTiles().keySet();
            int squares = 0;

            // schaut für alle Tiles im Territory ob rechts, unten, unten-rechts eine tile liegt
            for (Position p : positions) {
                int x = p.x();
                int y = p.y();

                Position right = new Position(x + 1, y);
                Position down = new Position(x, y + 1);
                Position downRight = new Position(x + 1, y + 1);

                if (positions.contains(right)
                        && positions.contains(down)
                        && positions.contains(downRight)) {
                    squares++;
                }
            }

            if (squares > 0) {
                player.addVictoryPoints(squares * vpPerSquare);
            }
        }
    }

    /**
     * Wertet abgeschlossene Gebiete terrainbasiert; zaehlt nur, wenn geschlossen und minSize erreicht.
     */
    private void scoreCompletedAreas(Game game, int minSize, int vpPerArea) {
        for (Player player : game.getPlayers()) {
            ClanTerritory territory = player.getClanTerritory();
            if (territory == null) {
                continue;
            }

            if (territory.getTiles().isEmpty()) {
                continue;
            }

            int areasThatCount = 0;
            for (EdgeType terrain : EdgeType.values()) {
                for (ClanTerritory.TerritoryArea area : territory.getAreas(terrain)) {
                    if (area.isClosed() && area.getTiles().size() >= minSize) {
                        areasThatCount++;
                    }
                }
            }

            if (areasThatCount > 0) {
                player.addVictoryPoints(areasThatCount * vpPerArea);
            }
        }
    }

    /**
     * Wertet Brochs in Berggebieten gemäß Regelwerk.
     *
     * @param game Spiel
     */
    private void scoreMountainBroch(Game game) {
        for (Player player : game.getPlayers()) {
            ClanTerritory territory = player.getClanTerritory();
            if (territory == null || territory.getTiles().isEmpty()) {
                continue;
            }

            int vp = 0;
            Map<Position, PlacedTile> tiles = territory.getTiles();

            for (ClanTerritory.TerritoryArea area : territory.getAreas(EdgeType.MOUNTAIN)) {
                int brochs = 0;
                for (Position pos : area.getTiles()) {
                    PlacedTile pt = tiles.get(pos);
                    if (pt == null) {
                        continue;
                    }
                    for (TileElement e : pt.tile().getElements()) {
                        if (e == TileElement.TOWER) {
                            brochs++;
                        }
                    }
                }
                if (brochs == 1) {
                    vp += 1;
                } else if (brochs == 2) {
                    vp += 3;
                } else if (brochs >= 3) {
                    vp += 6;
                }
            }

            if (vp > 0) {
                player.addVictoryPoints(vp);
            }
        }
    }

    /** 5 VP pro Set (Broch, Farm, Lighthouse). */
    public void scoreSetBrochFarmLighthouse(Game game) {
        for (Player player : game.getPlayers()) {
            int brochs = ClanTerritory.countElementInTerritory(player, TileElement.TOWER);
            int farms = ClanTerritory.countElementInTerritory(player, TileElement.FARM);
            int lighthouses = ClanTerritory.countElementInTerritory(player, TileElement.LIGHTHOUSE);

            int sets = Math.min(brochs, Math.min(farms, lighthouses));
            if (sets > 0) {
                player.addVictoryPoints(sets * 5);
            }
        }
    }

    /**
     * Wertet Schafe und Rinder auf oder neben Farmen.
     *
     * @param game Spiel
     */
    private void scoreSheepCattleAdjacentFarm(Game game) {
        for (Player player : game.getPlayers()) {
            ClanTerritory t = player.getClanTerritory();
            if (t == null) continue;

            int vp = 0;

            for (Map.Entry<Position, PlacedTile> entry : t.getTiles().entrySet()) {
                Position pos = entry.getKey();
                PlacedTile tile = entry.getValue();

                boolean farmOnTile = tile.tile().hasElement(TileElement.FARM);
                boolean nearFarm = farmOnTile || isAdjacentToFarm(t, pos);
                if (!nearFarm) {
                    continue;
                }

                for (TileElement e : tile.tile().getElements()) {
                    if (e == TileElement.SHEEP || e == TileElement.OX) {
                        vp++;
                    }
                }
            }

            player.addVictoryPoints(vp);
        }
    }

    /**
     * Prüft, ob eine Position an eine Farm angrenzt.
     *
     * @param t Territorium
     * @param pos Position
     * @return {@code true}, wenn angrenzend
     */
    private boolean isAdjacentToFarm(ClanTerritory t, Position pos) {
        int[] d = {-1, 0, 1};
        for (int dx : d) {
            for (int dy : d) {
                if (dx == 0 && dy == 0) continue;
                Position n = new Position(pos.x() + dx, pos.y() + dy);
                PlacedTile pt = t.getTiles().get(n);
                if (pt != null && pt.tile().hasElement(TileElement.FARM)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Wertet Mehrheiten beim Gold (1. und 2. Platz).
     *
     * @param game Spiel
     */
    private void scoreMostGold(Game game) {
        List<Player> players = game.getPlayers();

        int max = players.stream().mapToInt(Player::getCoins).max().orElse(0);
        if (max == 0) return; // "must have at least 1 gold"

        List<Player> first = new ArrayList<>();
        List<Player> second = new ArrayList<>();

        for (Player p : players) {
            if (p.getCoins() == max) first.add(p);
        }

        int secondMax = players.stream()
                .mapToInt(Player::getCoins)
                .filter(c -> c < max)
                .max()
                .orElse(0);

        if (secondMax > 0) {
            for (Player p : players) {
                if (p.getCoins() == secondMax) second.add(p);
            }
        }

        for (Player p : first) p.addVictoryPoints(5);
        if (first.size() > 1) return;

        for (Player p : second) p.addVictoryPoints(2);
    }

    /**
     * Wertet Rinder, die per Straße mit der Burg verbunden sind.
     *
     * @param game Spiel
     */
    private void scoreCattleConnectedToCastle(Game game) {
        for (Player player : game.getPlayers()) {
            ClanTerritory t = player.getClanTerritory();
            if (t == null) continue;

            Set<Position> connected = RoadConnectivity.connectedToCastle(t);

            int vp = 0;
            for (Position pos : connected) {
                PlacedTile pt = t.getTiles().get(pos);
                if (pt == null) continue;

                for (TileElement e : pt.tile().getElements()) {
                    if (e == TileElement.OX) vp += 2;
                }
            }

            player.addVictoryPoints(vp);
        }
    }

    /**
     * Wertet Wassergebiete mit Schiff und angrenzendem Leuchtturm.
     *
     * @param game Spiel
     */
    private void scoreWaterShipLighthouseAreas(Game game) {
        for (Player player : game.getPlayers()) {
            ClanTerritory t = player.getClanTerritory();
            if (t == null) continue;

            int vp = 0;
            Map<Position, PlacedTile> tiles = t.getTiles();

            for (ClanTerritory.TerritoryArea area : t.getAreas(EdgeType.WATER)) {
                boolean hasShip = false;

                for (Position pos : area.getTiles()) {
                    PlacedTile pt = tiles.get(pos);
                    if (pt == null) continue;

                    if (pt.tile().hasElement(TileElement.SHIP)) {
                        hasShip = true;
                    }
                }

                if (hasShip && hasAdjacentLighthouse(tiles, area)) {
                    vp += 3;
                }
            }

            player.addVictoryPoints(vp);
        }
    }

    /**
     * Prüft, ob ein Gebiet einen Leuchtturm enthält oder daran angrenzt.
     *
     * @param tiles Map der platzierten Tiles
     * @param area Gebiet
     * @return {@code true}, wenn ein Leuchtturm angrenzend ist
     */
    private boolean hasAdjacentLighthouse(Map<Position, PlacedTile> tiles,
                                          ClanTerritory.TerritoryArea area) {
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (Position pos : area.getTiles()) {
            PlacedTile current = tiles.get(pos);
            if (current != null && current.tile().hasElement(TileElement.LIGHTHOUSE)) {
                return true;
            }
            for (int[] dir : dirs) {
                Position neighbor = new Position(pos.x() + dir[0], pos.y() + dir[1]);
                PlacedTile neighborTile = tiles.get(neighbor);
                if (neighborTile != null && neighborTile.tile().hasElement(TileElement.LIGHTHOUSE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Wertet die Anzahl der per Straße verbundenen Tiles.
     *
     * @param game Spiel
     */
    private void scoreTilesConnectedToCastle(Game game) {
        for (Player player : game.getPlayers()) {
            Set<Position> connected = RoadConnectivity.connectedToCastle(
                    player.getClanTerritory()
            );

            player.addVictoryPoints(connected.size());
        }
    }

    /**
     * Wertet vertikale Linien von mindestens drei Tiles.
     *
     * @param game Spiel
     */
    private void scoreVerticalLinesOfThree(Game game) {
        for (Player player : game.getPlayers()) {
            ClanTerritory t = player.getClanTerritory();
            if (t == null) continue;

            Map<Position, PlacedTile> tiles = t.getTiles();
            Map<Integer, List<Integer>> columns = new HashMap<>();

            for (Position pos : tiles.keySet()) {
                columns.computeIfAbsent(pos.x(), k -> new ArrayList<>()).add(pos.y());
            }

            int vp = 0;
            for (Map.Entry<Integer, List<Integer>> entry : columns.entrySet()) {
                List<Integer> ys = entry.getValue();
                Collections.sort(ys);

                int runLength = 1;
                for (int i = 1; i < ys.size(); i++) {
                    if (ys.get(i) == ys.get(i - 1) + 1) {
                        runLength++;
                    } else {
                        if (runLength >= 3) {
                            vp += 3;
                        }
                        runLength = 1;
                    }
                }
                if (runLength >= 3) {
                    vp += 3;
                }
            }

            player.addVictoryPoints(vp);
        }
    }

    /**
     * Wertet die größte abgeschlossene Wasserfläche.
     *
     * @param game Spiel
     */
    private void scoreLargestCompletedWaterArea(Game game) {
        for (Player player : game.getPlayers()) {
            ClanTerritory t = player.getClanTerritory();
            if (t == null) continue;

            int maxSize = 0;

            for (ClanTerritory.TerritoryArea area : t.getAreas(EdgeType.WATER)) {
                if (!area.isClosed()) continue;
                maxSize = Math.max(maxSize, area.getTiles().size());
            }

            player.addVictoryPoints(maxSize * 2);
        }
    }

    /**
     * Wertet abgeschlossene Berggebiete.
     *
     * @param game Spiel
     */
    private void scoreCompletedMountainAreas(Game game) {
        for (Player player : game.getPlayers()) {
            ClanTerritory t = player.getClanTerritory();
            if (t == null) continue;

            int vp = 0;

            for (ClanTerritory.TerritoryArea area : t.getAreas(EdgeType.MOUNTAIN)) {
                if (area.isClosed())
                    vp += 2;
            }

            player.addVictoryPoints(vp);
        }
    }

    /** Punkte linear pro Element-Icon. */
    private void scorePerElement(Game game, TileElement element, int pointsPerElement) {
        for (Player player : game.getPlayers()) {
            int count = ClanTerritory.countElementInTerritory(player, element);
            if (count > 0) {
                player.addVictoryPoints(count * pointsPerElement);
            }
        }
    }

    /** Mehrheit fuer Tiles mit dem Element; behandelt Ties regelkonform. */
    private void scoreMajorityForElement(Game game,
                                         TileElement element,
                                         int firstVp,
                                         int secondVp) {

        Map<Player, Integer> counts = new HashMap<>();
        for (Player player : game.getPlayers()) {
            counts.put(player, countElements(player.getClanTerritory(), element));
        }

        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max == 0) {
            return;
        }

        List<Player> first = new ArrayList<>();
        counts.forEach((player, value) -> {
            if (value == max) {
                first.add(player);
            }
        });

        for (Player player : first) {
            player.addVictoryPoints(firstVp);
        }

        if (first.size() > 1) {
            return;
        }

        int second = counts.values().stream()
                .filter(v -> v < max)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);

        if (second > 0) {
            counts.forEach((player, value) -> {
                if (value == second) {
                    player.addVictoryPoints(secondVp);
                }
            });
        }
    }

    /** Wertet alle Scrolls eines Spielers inklusive Verdopplung in abgeschlossenen Gebieten. */
    private int calculateScrollPoints(Player player) {
        int total = 0;

        ClanTerritory territory = player.getClanTerritory();
        if (territory == null) {
            return 0;
        }

        for (Map.Entry<Position, PlacedTile> entry : territory.getTiles().entrySet()) {
            Position position = entry.getKey();
            PlacedTile placedTile = entry.getValue();
            Tile tile = placedTile.tile();

            if (!tile.hasScroll()) {
                continue;
            }

            Scroll scroll = tile.getScroll();
            total += calculateSingleScrollValue(player, position, scroll, territory);
        }

        return total;
    }

    /** Wertet eine Scroll nach Multiplikitaet der Elemente und Verdopplung bei Abschluss. */
    private int calculateSingleScrollValue(Player player,
                                           Position position,
                                           Scroll scroll,
                                           ClanTerritory territory) {
        Map<TileElement, Integer> multiplicity = new HashMap<>();
        for (TileElement e : scroll.getElements()) {
            multiplicity.merge(e, 1, Integer::sum);
        }

        int vp = 0;
        for (Map.Entry<TileElement, Integer> entry : multiplicity.entrySet()) {
            TileElement element = entry.getKey();
            int needed = entry.getValue();
            int count = ClanTerritory.countElementInTerritory(player, element);
            int sets = count / needed;
            vp += sets * scroll.getPoints();
        }

        if (territory.isPositionInClosedArea(position)) {
            vp *= 2;
        }

        return vp;
    }

    /**
     * Zählt ein Element im Territorium.
     *
     * @param territory Territorium
     * @param element Element
     * @return Anzahl Vorkommen
     */
    private int countElements(ClanTerritory territory, TileElement element) {
        if (territory == null || territory.getTiles().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (PlacedTile pt : territory.getTiles().values()) {
            for (TileElement e : pt.tile().getElements()) {
                if (e == element) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Schlusswertung: Scroll-Punkte plus 1 VP je 5 Gold. */
    public void scoreFinal(Game game) {
        List<Player> players = game.getPlayers();

        for (Player player : players) {
            // Scroll-Points
            int scrollPoints = calculateScrollPoints(player);
            player.addVictoryPoints(scrollPoints);

            // 1 VP pro 5 coins
            int goldPoints = player.getCoins() / 5;
            player.addVictoryPoints(goldPoints);
        }
        // Gleichstand-Behandlung
        int bestVp = players.stream().mapToInt(Player::getVictoryPoints).max().orElse(0);
        List<Player> winners = new ArrayList<>();
        for (Player p : players) if (p.getVictoryPoints() == bestVp) winners.add(p);
        if (winners.size() > 1) {
            winners.sort((a, b) -> Integer.compare(b.getCoins(), a.getCoins()));
            Player winner = winners.get(0);
            log.info("Tie-Breaker enschieden durch Gold: Sieger = {}", winner.getName());
        }
    }
}
