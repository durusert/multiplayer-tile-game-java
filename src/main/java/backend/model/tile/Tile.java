package backend.model.tile;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.google.gson.annotations.SerializedName;

import backend.model.Scroll;
import backend.model.enums.EdgeType;
import backend.model.enums.TileElement;
import backend.model.enums.TileRoadSide;

/**
 * Modelliert ein Landschafts-Tile inklusive Kanten, Straßen, Elemente und Scroll.
 */
public class Tile {

    // @SerializedName mappt JSON "id" auf Java "tileId"
    @SerializedName("id")
    private int tileId;

    private EdgeType top;
    private EdgeType right;
    private EdgeType down;
    private EdgeType left;

    private List<List<TileRoadSide>> roads;
    private List<TileElement> elements;
    private Scroll scroll;

    private int spriteX;
    private int spriteY;

    /**
     * Leerer Konstruktor für Gson.
     * Gson benutzt Reflexion und ruft diesen, um danach die Felder zu setzen.
     */
    public Tile() {
    }

    /**
     * Dein „normaler“ Konstruktor für Code, der Tiles selbst erzeugt.
     */
    public Tile(int tileId,
                EdgeType top,
                EdgeType right,
                EdgeType down,
                EdgeType left,
                List<List<TileRoadSide>> roads,
                List<TileElement> elements,
                Scroll scroll,
                int spriteX,
                int spriteY) {

        this.tileId = tileId;
        this.top = Objects.requireNonNull(top);
        this.right = Objects.requireNonNull(right);
        this.down = Objects.requireNonNull(down);
        this.left = Objects.requireNonNull(left);

        this.roads = roads;
        this.elements = elements;
        this.scroll = scroll;

        this.spriteX = spriteX;
        this.spriteY = spriteY;
    }

    // Getter

    /**
     * Gibt die Tile-ID zurück.
     *
     * @return Tile-ID
     */
    public int getTileId() {
        return tileId;
    }

    /**
     * Gibt die obere Kante zurück.
     *
     * @return {@link EdgeType}
     */
    public EdgeType getTop() { return top; }
    /**
     * Gibt die rechte Kante zurück.
     *
     * @return {@link EdgeType}
     */
    public EdgeType getRight() { return right; }
    /**
     * Gibt die untere Kante zurück.
     *
     * @return {@link EdgeType}
     */
    public EdgeType getDown() { return down; }
    /**
     * Gibt die linke Kante zurück.
     *
     * @return {@link EdgeType}
     */
    public EdgeType getLeft() { return left; }

    /**
     * Gibt die Straßen als unveränderliche Liste zurück.
     *
     * @return Liste von Straßenverbindungen
     */
    public List<List<TileRoadSide>> getRoads() {
        return roads == null ? List.of() : Collections.unmodifiableList(roads);
    }

    /**
     * Gibt die Elemente des Tiles zurück.
     *
     * @return Liste der {@link TileElement}
     */
    public List<TileElement> getElements() {
        return elements == null ? List.of() : Collections.unmodifiableList(elements);
    }

    /**
     * Prüft, ob das Tile ein bestimmtes Element enthält.
     *
     * @param element {@link TileElement}
     * @return {@code true}, wenn enthalten
     */
    public boolean hasElement(TileElement element) {
        return elements != null && elements.contains(element);
    }

    /**
     * Gibt die Scroll zurück.
     *
     * @return {@link Scroll} oder {@code null}
     */
    public Scroll getScroll() { return scroll; }
    /**
     * Prüft, ob das Tile eine Scroll hat.
     *
     * @return {@code true}, wenn vorhanden
     */
    public boolean hasScroll() { return scroll != null; }

    /**
     * Gibt die Sprite-X-Position zurück.
     *
     * @return Sprite-X
     */
    public int getSpriteX() { return spriteX; }
    /**
     * Gibt die Sprite-Y-Position zurück.
     *
     * @return Sprite-Y
     */
    public int getSpriteY() { return spriteY; }

    /**
     * Setzt die Tile-ID.
     *
     * @param tileId Tile-ID
     */
    public void setTileId(int tileId) { this.tileId = tileId; }
    /**
     * Setzt die obere Kante.
     *
     * @param top {@link EdgeType}
     */
    public void setTop(EdgeType top) { this.top = top; }
    /**
     * Setzt die rechte Kante.
     *
     * @param right {@link EdgeType}
     */
    public void setRight(EdgeType right) { this.right = right; }
    /**
     * Setzt die untere Kante.
     *
     * @param down {@link EdgeType}
     */
    public void setDown(EdgeType down) { this.down = down; }
    /**
     * Setzt die linke Kante.
     *
     * @param left {@link EdgeType}
     */
    public void setLeft(EdgeType left) { this.left = left; }
    /**
     * Setzt die Scroll.
     *
     * @param scroll {@link Scroll}
     */
    public void setScroll(Scroll scroll) { this.scroll = scroll; }
}
