package backend.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import backend.model.enums.EdgeType;
import backend.model.enums.TileElement;
import backend.model.enums.TileRoadSide;
import backend.model.tile.PlacedTile;
import backend.model.tile.Tile;
import backend.model.tile.TilePlacementRules;

/**
 * Repräsentiert das Clan-Territorium eines Spielers samt platzierter Plättchen
 * und analysierbarer Gebietsflächen.
 */
public class ClanTerritory {

    private final Player owner;

    private final Map<Position, PlacedTile> tiles = new HashMap<>();
    private final EnumMap<EdgeType, List<TerritoryArea>> areaCache = new EnumMap<>(EdgeType.class);
    private final Map<Position, List<TerritoryArea>> positionAreas = new HashMap<>();
    private final Set<Position> closedPositions = new HashSet<>();
    private boolean areaCacheDirty = true;

    private static final int MIN_COORD = -15;
    private static final int MAX_COORD = 15;

    private boolean withinBounds(int x, int y) {
        return x >= MIN_COORD && x <= MAX_COORD && y >= MIN_COORD && y <= MAX_COORD;
    }

    /**
     * Erstellt ein Clan-Territorium für den angegebenen Spieler.
     *
     * @param owner Besitzer des Territoriums
     */
    public ClanTerritory(Player owner) {
        this.owner = owner;
    }

    /**
     * Gibt den Besitzer des Territoriums zurück.
     *
     * @return Spieler
     */
    public Player getOwner() {
        return owner;
    }

    /**
     * Gibt eine unveränderliche Ansicht der platzierten Plättchen zurück.
     *
     * @return Map Position -> PlacedTile
     */
    public Map<Position, PlacedTile> getTiles() {
        return Collections.unmodifiableMap(tiles);
    }

    /**
     * Gibt das Plättchen an der angegebenen Position zurück.
     *
     * @param x x-Koordinate
     * @param y y-Koordinate
     * @return {@link PlacedTile} oder {@code null}
     */
    public PlacedTile getTileAt(int x, int y) {
        return tiles.get(new Position(x, y));
    }

    /**
     * Platziert die Burg als Startfeld auf (0,0).
     *
     * @param castleTile Burg-Plättchen
     */
    public void placeInitialCastle(Tile castleTile) {
        Position start = new Position(0, 0);
        tiles.put(start, new PlacedTile(castleTile, 0));
        markAreaCacheDirty();
    }

    /**
     * Prüft, ob ein Tile an (x,y) mit der gegebenen Rotation legal platziert werden kann.
     */
    public boolean canPlace(Tile tile, int x, int y, int rotationDegrees) {
        Position wantedPosition = new Position(x, y);

        if (!TilePlacementRules.isValidRotation(rotationDegrees)) {
            return false;
        }

        if (!withinBounds(x, y)) {
            return false;
        }

        if (tiles.containsKey(wantedPosition)) {
            return false;
        }

        if (tiles.isEmpty()) {
            return x == 0 && y == 0;
        }

        boolean hasAtLeastOneNeighbor = false;
        PlacedTile newPlacedTile = new PlacedTile(tile, rotationDegrees);

        Position up = new Position(x, y + 1);
        PlacedTile neighborUp = tiles.get(up);
        if (neighborUp != null) {
            hasAtLeastOneNeighbor = true;
            boolean ok = TilePlacementRules.edgesAreCompatible(
                    newPlacedTile, TileRoadSide.TOP,
                    neighborUp, TileRoadSide.DOWN
            );
            if (!ok) {
                return false;
            }
        }

        Position down = new Position(x, y - 1);
        PlacedTile neighborDown = tiles.get(down);
        if (neighborDown != null) {
            hasAtLeastOneNeighbor = true;
            boolean ok = TilePlacementRules.edgesAreCompatible(
                    newPlacedTile, TileRoadSide.DOWN,
                    neighborDown, TileRoadSide.TOP
            );
            if (!ok) {
                return false;
            }
        }

        Position right = new Position(x + 1, y);
        PlacedTile neighborRight = tiles.get(right);
        if (neighborRight != null) {
            hasAtLeastOneNeighbor = true;
            boolean ok = TilePlacementRules.edgesAreCompatible(
                    newPlacedTile, TileRoadSide.RIGHT,
                    neighborRight, TileRoadSide.LEFT
            );
            if (!ok) {
                return false;
            }
        }

        Position left = new Position(x - 1, y);
        PlacedTile neighborLeft = tiles.get(left);
        if (neighborLeft != null) {
            hasAtLeastOneNeighbor = true;
            boolean ok = TilePlacementRules.edgesAreCompatible(
                    newPlacedTile, TileRoadSide.LEFT,
                    neighborLeft, TileRoadSide.RIGHT
            );
            if (!ok) {
                return false;
            }
        }
        return hasAtLeastOneNeighbor;
    }

    private Iterable<Position> candidatePositions() {
        // alle freien Nachbarfelder um bereits gelegte Tiles
        java.util.Set<Position> spots = new java.util.HashSet<>();
        if (tiles.isEmpty()) {
            spots.add(new Position(0, 0));
            return spots;
        }
        for (Position pos : tiles.keySet()) {
            int x = pos.x();
            int y = pos.y();
            Position[] neighbors = {
                    new Position(x, y + 1),
                    new Position(x, y - 1),
                    new Position(x + 1, y),
                    new Position(x - 1, y)
            };
            for (Position n : neighbors) {
                if (withinBounds(n.x(), n.y()) && !tiles.containsKey(n)) {
                    spots.add(n);
                }
            }
        }
        return spots;
    }

    /**
     * Prüft, ob es irgendeine legale Platzierung für das gegebene Tile gibt.
     *
     * @param tile {@link Tile}
     * @return {@code true}, wenn mindestens eine Platzierung möglich ist
     */
    public boolean hasAnyLegalPlacement(Tile tile) {
        for (Position pos : candidatePositions()) {
            int x = pos.x();
            int y = pos.y();
            for (int rot : new int[]{0, 90, 180, 270}) {
                if (canPlace(tile, x, y, rot)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gibt alle legalen Platzierungen für ein Tile zurück.
     * Jedes Element ist ein int[] mit {x, y, rotation}.
     */
    public List<int[]> getAllLegalPlacements(Tile tile) {
        List<int[]> legalMoves = new ArrayList<>();
        for (Position pos : candidatePositions()) {
            int x = pos.x();
            int y = pos.y();
            for (int rot : new int[]{0, 90, 180, 270}) {
                if (canPlace(tile, x, y, rot)) {
                    legalMoves.add(new int[]{x, y, rot});
                }
            }
        }
        return legalMoves;
    }

    /**
     * Versucht, ein Tile wirklich zu platzieren.
     */
    public boolean placeTile(Tile tile, int x, int y, int rotationDegrees) {
        if (!canPlace(tile, x, y, rotationDegrees)) {
            return false;
        }
        Position pos = new Position(x, y);
        tiles.put(pos, new PlacedTile(tile, rotationDegrees));
        markAreaCacheDirty();
        return true;
    }

    /**
     * Zählt, wie oft ein bestimmtes {@link TileElement} im Clan-Territory eines Spielers vorkommt.
     */
    public static int countElementInTerritory(Player player, TileElement element) {
        ClanTerritory territory = player.getClanTerritory();
        if (territory == null) {
            return 0;
        }

        int count = 0;

        // territory.getTiles().values() liefert PlacedTile-Objekte
        for (PlacedTile placedTile : territory.getTiles().values()) {
            Tile tile = placedTile.tile(); // das eigentliche Tile holen

            for (TileElement e : tile.getElements()) {
                if (e == element) {
                    count++;
                }
            }
        }
        return count;
    }

    private void markAreaCacheDirty() {
        areaCacheDirty = true;
    }

    /**
     * Gibt die zusammenhängenden Gebiete eines bestimmten Terrain-Typs zurück.
     *
     * @param terrain {@link EdgeType}
     * @return Liste der Gebiete (unveränderlich)
     */
    public Collection<TerritoryArea> getAreas(EdgeType terrain) {
        ensureAreaCache();
        List<TerritoryArea> areas = areaCache.get(terrain);
        if (areas == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(areas);
    }


    /**
     * Prüft, ob die Position in einem geschlossenen Gebiet liegt.
     *
     * @param position {@link Position}
     * @return {@code true}, wenn die Position zu einem geschlossenen Gebiet gehört
     */
    public boolean isPositionInClosedArea(Position position) {
        ensureAreaCache();
        return closedPositions.contains(position);
    }

    private void ensureAreaCache() {
        if (!areaCacheDirty) {
            return;
        }
        rebuildAreaCache();
    }

    private void rebuildAreaCache() {
        areaCache.clear();
        positionAreas.clear();
        closedPositions.clear();

        for (EdgeType type : EdgeType.values()) {
            areaCache.put(type, new ArrayList<>());
        }

        Set<EdgeRef> visited = new HashSet<>();
        for (Map.Entry<Position, PlacedTile> entry : tiles.entrySet()) {
            Position position = entry.getKey();
            PlacedTile placedTile = entry.getValue();

            for (TileRoadSide side : TileRoadSide.values()) {
                EdgeType terrain = TilePlacementRules.getEdgeAtSide(placedTile, side);
                if (terrain == null) {
                    continue;
                }

                EdgeRef start = new EdgeRef(position, side);
                if (visited.contains(start)) {
                    continue;
                }

                TerritoryArea area = exploreArea(terrain, start, visited);
                areaCache.get(terrain).add(area);
                for (Position areaPos : area.getTiles()) {
                    positionAreas
                            .computeIfAbsent(areaPos, key -> new ArrayList<>())
                            .add(area);
                    if (area.isClosed()) {
                        closedPositions.add(areaPos);
                    }
                }
            }
        }

        areaCacheDirty = false;
    }

    private TerritoryArea exploreArea(EdgeType terrain,
                                      EdgeRef start,
                                      Set<EdgeRef> visited) {
        Set<Position> areaTiles = new HashSet<>();
        Deque<EdgeRef> stack = new ArrayDeque<>();
        stack.push(start);
        boolean closed = true;

        while (!stack.isEmpty()) {
            EdgeRef current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }

            areaTiles.add(current.position);
            PlacedTile tile = tiles.get(current.position);
            if (tile == null) {
                closed = false;
                continue;
            }

            for (TileRoadSide side : TileRoadSide.values()) {
                if (TilePlacementRules.getEdgeAtSide(tile, side) == terrain) {
                    EdgeRef candidate = new EdgeRef(current.position, side);
                    if (!visited.contains(candidate)) {
                        stack.push(candidate);
                    }
                }
            }

            Position neighborPos = neighbor(current.position, current.side);
            PlacedTile neighborTile = tiles.get(neighborPos);
            if (neighborTile == null) {
                closed = false;
                continue;
            }

            TileRoadSide oppositeSide = opposite(current.side);
            EdgeType neighborEdge = TilePlacementRules.getEdgeAtSide(neighborTile, oppositeSide);
            if (neighborEdge == terrain) {
                EdgeRef neighborRef = new EdgeRef(neighborPos, oppositeSide);
                if (!visited.contains(neighborRef)) {
                    stack.push(neighborRef);
                }
            } else {
                closed = false;
            }
        }

        return new TerritoryArea(terrain, areaTiles, closed);
    }

    private Position neighbor(Position pos, TileRoadSide side) {
        return switch (side) {
            case TOP -> new Position(pos.x(), pos.y() + 1);
            case DOWN -> new Position(pos.x(), pos.y() - 1);
            case RIGHT -> new Position(pos.x() + 1, pos.y());
            case LEFT -> new Position(pos.x() - 1, pos.y());
        };
    }

    private TileRoadSide opposite(TileRoadSide side) {
        return switch (side) {
            case TOP -> TileRoadSide.DOWN;
            case DOWN -> TileRoadSide.TOP;
            case LEFT -> TileRoadSide.RIGHT;
            case RIGHT -> TileRoadSide.LEFT;
        };
    }

    private record EdgeRef(Position position, TileRoadSide side) {

        @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof EdgeRef edgeRef)) return false;
                return Objects.equals(position, edgeRef.position) && side == edgeRef.side;
            }

    }

    /**
     * Beschreibt ein zusammenhängendes Gebiet eines Terrain-Typs.
     */
    public static final class TerritoryArea {
        private final EdgeType terrain;
        private final Set<Position> tiles;
        private final boolean closed;

        private TerritoryArea(EdgeType terrain, Set<Position> tiles, boolean closed) {
            this.terrain = terrain;
            this.tiles = Collections.unmodifiableSet(new HashSet<>(tiles));
            this.closed = closed;
        }

        /**
         * Gibt den Terrain-Typ dieses Gebiets zurück.
         *
         * @return {@link EdgeType}
         */
        public EdgeType getTerrain() {
            return terrain;
        }

        /**
         * Gibt die Positionen der zugehörigen Plättchen zurück.
         *
         * @return Set von {@link Position}
         */
        public Set<Position> getTiles() {
            return tiles;
        }

        /**
         * Gibt an, ob das Gebiet geschlossen ist.
         *
         * @return {@code true}, wenn geschlossen
         */
        public boolean isClosed() {
            return closed;
        }
    }
}
