package backend.model.playeraction;

import backend.model.Player;
import backend.model.tile.Tile;

import java.util.HashMap;
import java.util.Map;

/**
 * Welche der gezogenen Tiles wird verworfen, welche Preise auf die übrigen?
 * Wird als Nachricht von der GameLogic benutzt.
 */
public class PriceDecision {
    private final Player player;
    private final Map<Tile, Integer> prices = new  HashMap<>();
    private Tile discardedTile;

    /**
     * Erstellt eine Preisentscheidung für einen Spieler.
     *
     * @param player Spieler
     */
    public PriceDecision(Player player) {
        this.player = player;
    }

    /**
     * Gibt den zugehörigen Spieler zurück.
     *
     * @return Spieler
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * Gibt die gesetzten Preise zurück.
     *
     * @return Map Tile -> Preis
     */
    public Map<Tile, Integer> getPrices() {
        return prices;
    }

    /**
     * Setzt den Preis für ein Tile.
     *
     * @param tile {@link Tile}
     * @param price Preis
     */
    public void setPrice(Tile tile, int price) {
        prices.put(tile, price);
    }

    /**
     * Gibt das verworfene Tile zurück.
     *
     * @return verworfenes {@link Tile}
     */
    public Tile getDiscardedTile() {
        return discardedTile;
    }

    /**
     * Setzt das verworfene Tile.
     *
     * @param discardedTile verworfenes {@link Tile}
     */
    public void setDiscardedTile(Tile discardedTile) {
        this.discardedTile = discardedTile;
    }


}
