package backend.model.tile;

import backend.model.enums.TileElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Beutel für Landschafts- und Burgplättchen.
 */
public class TileBag {

    private final List<Tile> tiles;        // reguläre Landschaftsplättchen
    private final List<Tile> castleTiles;

    /**
     * Erstellt einen TileBag und lädt die Tiles aus JSON.
     */
    public TileBag() {
        this.tiles = new ArrayList<>();
        this.castleTiles = new ArrayList<>();

        for (Tile tile : TileJsonLoader.loadTiles()) {
            if (tile.getElements() != null && tile.getElements().contains(TileElement.CASTLE)) {
                castleTiles.add(tile);
            } else {
                tiles.add(tile);
            }
        }

        Collections.shuffle(this.tiles);
    }

    // falls du zusätzlich manuell Tiles hinzufügen willst:
    /**
     * Fügt ein Tile dem Beutel hinzu.
     *
     * @param tile {@link Tile}
     */
    public void addTile(Tile tile) {
        if (tile == null) {
            return;
        }
        if (tile.getElements() != null && tile.getElements().contains(TileElement.CASTLE)) {
            castleTiles.add(tile);
        } else {
            tiles.add(tile);
        }
    }

    /**
     * Mischt die regulären Tiles im Beutel.
     */
    public void shuffle() {
        Collections.shuffle(tiles);
    }

    /**
     * Zieht eine Anzahl von Tiles aus dem Beutel.
     *
     * @param count Anzahl
     * @return Liste gezogener Tiles
     */
    public List<Tile> drawTiles(int count) {
        List<Tile> drawnTiles = new ArrayList<>();
        for (int i = 0; i < count && !tiles.isEmpty(); i++) {
            drawnTiles.add(tiles.remove(0));
        }
        return drawnTiles;
    }

    /**
     * Zieht ein Burg-Tile aus dem Burgstapel.
     *
     * @return Burg-{@link Tile}
     */
    public Tile drawCastleTile() {
        if (!castleTiles.isEmpty()) {
            return castleTiles.remove(0);
        }
        throw new IllegalStateException("No more castle tile found in TileBag");
    }


    /**
     * Entfernt ein bestimmtes Tile (nach tileId) aus dem Beutel und gibt es zurück.
     * Wird genutzt, um deterministisch Startburgen zuzuweisen.
     */
    public Tile takeTileById(int tileId) {
        for (int i = 0; i < castleTiles.size(); i++) {
            Tile t = castleTiles.get(i);
            if (t.getTileId() == tileId) {
                castleTiles.remove(i);
                return t;
            }
        }
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            if (t.getTileId() == tileId) {
                tiles.remove(i);
                return t;
            }
        }
        throw new IllegalStateException("Tile with id " + tileId + " not found in TileBag");
    }

    public boolean isEmpty() {
        return tiles.isEmpty();
    }

    /**
     * Gibt die Liste der regulären Tiles zurück.
     *
     * @return Liste der Tiles
     */
    public List<Tile> getTiles() {
        return tiles;
    }
}
