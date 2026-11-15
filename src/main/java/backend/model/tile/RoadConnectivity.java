package backend.model.tile;

import backend.model.ClanTerritory;
import backend.model.Position;
import backend.model.enums.TileRoadSide;
import backend.model.enums.TileElement;

import java.util.*;

/**
 * Ermittelt Straßen-Connectivity im ClanTerritory.
 */
public final class RoadConnectivity {
    private RoadConnectivity() {}

    /**
     * Alle Positionen, die über Straßen mit der Burg verbunden sind.
     *
     * @param territory Territorium
     * @return Menge verbundener Positionen
     */
    public static Set<Position> connectedToCastle(ClanTerritory territory) {
        if (territory == null || territory.getTiles().isEmpty()) {
            return Set.of();
        }

        Map<Position, PlacedTile> tiles = territory.getTiles();

        // Burg-Position finden
        Position castlePos = tiles.entrySet().stream()
                .filter(e -> e.getValue().tile().hasElement(TileElement.CASTLE))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        if (castlePos == null) {
            // kein Castle im Territory (sollte eigentlich nicht vorkommen)
            return Set.of();
        }

        return reachableByRoads(territory, castlePos);
    }

    /**
     * Alle Positionen, die über Straßen von startPos erreichbar sind.
     * Jetzt nur noch intern genutzt.
     *
     * @param territory Territorium
     * @param startPos Startposition
     * @return Menge erreichbarer Positionen
     */
    private static Set<Position> reachableByRoads(ClanTerritory territory, Position startPos) {
        Map<Position, PlacedTile> tiles = territory.getTiles();
        PlacedTile startTile = tiles.get(startPos);
        if (startTile == null) return Set.of();

        Set<Position> reached = new HashSet<>();
        Set<PosSide> seen = new HashSet<>();
        Deque<PosSide> stack = new ArrayDeque<>();

        // Seeds: alle Straßen-Seiten des Start-Tiles
        for (List<TileRoadSide> road : rotatedRoads(startTile)) {
            for (TileRoadSide side : road) {
                stack.push(new PosSide(startPos, side));
            }
        }

        while (!stack.isEmpty()) {
            PosSide cur = stack.pop();
            if (!seen.add(cur)) continue;
            reached.add(cur.pos);

            PlacedTile curTile = tiles.get(cur.pos);
            if (curTile == null) continue;

            // gleiche Straße innerhalb desselben Tiles
            for (List<TileRoadSide> road : rotatedRoads(curTile)) {
                if (road.contains(cur.side)) {
                    for (TileRoadSide sameRoadSide : road) {
                        if (sameRoadSide != cur.side) {
                            stack.push(new PosSide(cur.pos, sameRoadSide));
                        }
                    }
                }
            }

            // Übergang zum Nachbar-Tile
            Position nbPos = neighbor(cur.pos, cur.side);
            PlacedTile nbTile = tiles.get(nbPos);
            if (nbTile != null) {
                TileRoadSide opposite = opposite(cur.side);
                boolean neighborHasRoad = rotatedRoads(nbTile).stream()
                        .anyMatch(r -> r.contains(opposite));
                if (neighborHasRoad) {
                    stack.push(new PosSide(nbPos, opposite));
                }
            }
        }
        return reached;
    }

    /**
     * Gibt die Straßen-Seiten nach Rotation zurück.
     *
     * @param pt {@link PlacedTile}
     * @return Liste rotierter Straßen-Seiten
     */
    private static List<List<TileRoadSide>> rotatedRoads(PlacedTile pt) {
        int rot = pt.rotationDegrees();
        return pt.tile().getRoads().stream()
                .map(road -> road.stream().map(side -> rotate(side, rot)).toList())
                .toList();
    }

    /**
     * Rotiert eine Seite um den angegebenen Winkel.
     *
     * @param side Seite
     * @param degrees Winkel in Grad
     * @return rotierte {@link TileRoadSide}
     */
    private static TileRoadSide rotate(TileRoadSide side, int degrees) {
        int steps = ((degrees % 360) + 360) % 360 / 90;
        TileRoadSide s = side;
        for (int i = 0; i < steps; i++) {
            s = switch (s) {
                case TOP -> TileRoadSide.RIGHT;
                case RIGHT -> TileRoadSide.DOWN;
                case DOWN -> TileRoadSide.LEFT;
                case LEFT -> TileRoadSide.TOP;
            };
        }
        return s;
    }

    /**
     * Ermittelt die Nachbarposition in Richtung einer Seite.
     *
     * @param pos Position
     * @param side Seite
     * @return Nachbarposition
     */
    private static Position neighbor(Position pos, TileRoadSide side) {
        return switch (side) {
            case TOP -> new Position(pos.x(), pos.y() + 1);
            case DOWN -> new Position(pos.x(), pos.y() - 1);
            case RIGHT -> new Position(pos.x() + 1, pos.y());
            case LEFT -> new Position(pos.x() - 1, pos.y());
        };
    }

    /**
     * Gibt die gegenüberliegende Seite zurück.
     *
     * @param side Seite
     * @return gegenüberliegende {@link TileRoadSide}
     */
    private static TileRoadSide opposite(TileRoadSide side) {
        return switch (side) {
            case TOP -> TileRoadSide.DOWN;
            case DOWN -> TileRoadSide.TOP;
            case RIGHT -> TileRoadSide.LEFT;
            case LEFT -> TileRoadSide.RIGHT;
        };
    }

    private record PosSide(Position pos, TileRoadSide side) { }
}
