package backend.model.enums;

import com.google.gson.annotations.SerializedName;

/**
 * Welche Symbole auf Tiles liegen - für Einkommen & Scoring.
 */
public enum TileElement {
    @SerializedName("sheep")
    SHEEP,

    @SerializedName(value = "whiskey", alternate = { "barrel" })
    WHISKEY,

    @SerializedName("ship")
    SHIP,

    @SerializedName("ox")
    OX,

    @SerializedName("tower")
    TOWER,              //Round Tower

    @SerializedName("farm")
    FARM,

    @SerializedName("lighthouse")
    LIGHTHOUSE,

    @SerializedName("castle")
    CASTLE,

    @SerializedName("mountain")
    MOUNTAIN,

    @SerializedName("water")
    WATER,

    @SerializedName("field")
    FIELD;

    /**
     * Prüft, ob das Element in der Enumeration enthalten ist.
     *
     * @param element Element
     * @return {@code true}, wenn enthalten
     */
    public static boolean contains(TileElement element) {
        for (TileElement e : TileElement.values()) {
            if (e == element) {
                return true;
            }
        }
        return false;
    }
}
