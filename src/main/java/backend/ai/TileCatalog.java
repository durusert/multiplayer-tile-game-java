package backend.ai;

import backend.model.tile.Tile;
import backend.model.tile.TileJsonLoader;

import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Katalog für Tiles, die einmalig aus JSON geladen werden.
 */
public final class TileCatalog implements IntFunction<Tile> {

    private final Map<Integer, Tile> byId;

    /**
     * Lädt alle Tiles und baut eine ID->Tile Map auf.
     */
    public TileCatalog() {
        this.byId = TileJsonLoader.loadTiles().stream()
                .collect(Collectors.toMap(Tile::getTileId, t -> t));
    }

    /**
     * Liefert das Tile für die gegebene ID.
     *
     * @param tileId Tile-ID
     * @return {@link Tile} oder {@code null}
     */
    @Override
    public Tile apply(int tileId) {
        return byId.get(tileId);
    }
}
