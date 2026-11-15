package backend.model;

import backend.model.tile.Tile;

import java.util.*;

/**
 * Speichert Preise und Kaufabwicklung für angebotene Plättchen.
 */
public class Market {

    private final Map<Player, List<Tile>> offers = new HashMap<>();
    private final Map<Tile, Integer> tilePrices = new HashMap<>();

    /**
     * Fügt ein Angebot für ein Plättchen hinzu.
     *
     * @param player Anbieter
     * @param tile angebotenenes {@link Tile}
     * @param price Preis in Münzen
     */
    public void addOffer(Player player, Tile tile, int price) {
        offers.computeIfAbsent(player, k -> new ArrayList<>()).add(tile);
        tilePrices.put(tile, price);
    }

    /**
     * Entfernt ein bestimmtes Angebot eines Spielers.
     *
     * @param player Anbieter
     * @param tile angebotenes {@link Tile}
     */
    public void removeOffer(Player player, Tile tile) {
        List<Tile> tiles = offers.get(player);
        if (tiles != null)
            tiles.remove(tile);
        tilePrices.remove(tile);
    }
    /**
     * Entfernt alle Angebote eines Spielers.
     *
     * @param player Anbieter
     */
    public void removeAllOffersFrom(Player player) {
        List<Tile> playerTiles = offers.get(player);

        if (playerTiles != null) {
            for (Tile t : playerTiles) {
                tilePrices.remove(t);
            }
            offers.remove(player);
        }
    }

    /**
     * Gibt den Preis eines Plättchens zurück.
     *
     * @param tile {@link Tile}
     * @return Preis oder {@code null}, wenn nicht angeboten
     */
    public Integer getPrice(Tile tile) {
        return tilePrices.get(tile);
    }

    /**
     * Gibt die Angebote eines bestimmten Spielers zurück.
     *
     * @param player Anbieter
     * @return Liste der angebotenen Plättchen (oder {@code null}, falls keine)
     */
    public List<Tile> getOffersForPlayer(Player player) {
        return offers.get(player);
    }

    /**
     * Gibt alle aktuell angebotenen Plättchen zurück.
     *
     * @return Collection der angebotenen {@link Tile}
     */
    public Collection<Tile> getAllOffers() {
        return tilePrices.keySet();
    }

    /**
     * Gibt eine Kopie der Angebote nach Spieler zurück.
     *
     * @return Map Spieler -> Liste angebotener Plättchen
     */
    public Map<Player, List<Tile>> getOffersByPlayer() {
        Map<Player, List<Tile>> copy = new HashMap<>();
        offers.forEach((p, tiles) -> copy.put(p, new ArrayList<>(tiles)));
        return copy;
    }

    /**
     * Löscht alle Angebote und Preise.
     */
    public void clear() {
        offers.clear();
        tilePrices.clear();
    }
}
