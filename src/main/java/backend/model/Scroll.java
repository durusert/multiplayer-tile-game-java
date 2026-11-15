package backend.model;

import backend.model.enums.TileElement;

import java.util.List;

/**
 * Modelliert eine Schriftrolle auf einem Tile.
 * points = Siegpunkte pro erfuellter Bedingung, elements = benoetigte Symbole pro Set.
 * Diese Version ist JSON-kompatibel.
 */
public class Scroll {

    private int points;
    private List<TileElement> elements;

    /**
     * Leerer Konstruktor für Gson.
     */
    public Scroll() {
    }

    /**
     * Erstellt eine Schriftrolle mit Punkten und Symbolen.
     *
     * @param points Siegpunkte pro erfülltem Set
     * @param elements benötigte {@link TileElement} pro Set
     */
    public Scroll(int points, List<TileElement> elements) {
        this.points = points;
        this.elements = elements;
    }

    /**
     * Gibt die Siegpunkte pro erfülltem Set zurück.
     *
     * @return Punkte
     */
    public int getPoints() {
        return points;
    }

    /**
     * Gibt die benötigten Symbole pro Set zurück.
     *
     * @return Liste der {@link TileElement}
     */
    public List<TileElement> getElements() {
        return elements;
    }

    /**
     * Setzt die Siegpunkte pro erfülltem Set.
     *
     * @param points Punkte
     */
    public void setPoints(int points) {
        this.points = points;
    }

    /**
     * Setzt die benötigten Symbole pro Set.
     *
     * @param elements Liste der {@link TileElement}
     */
    public void setElements(List<TileElement> elements) {
        this.elements = elements;
    }
}
