package backend.model.playeraction;

import backend.model.Player;
import backend.model.tile.Tile;

/**
 * Repräsentiert den Versuch eines Spielers, ein bestimmtes Tile
 * an eine bestimmte Position (x,y) mit einer bestimmten Rotation zu bauen.
 *
 * @param player Spieler
 * @param tile {@link Tile}
 * @param x x-Koordinate
 * @param y y-Koordinate
 * @param rotationDegrees Rotation in Grad
 */
public record TilePlacement(Player player, Tile tile, int x, int y, int rotationDegrees) {
}
