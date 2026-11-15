package backend.model.enums;

import com.google.gson.annotations.SerializedName;

/**
 * Seiten eines Tiles für Kanten- und Nachbarschaftslogik.
 */
public enum TileRoadSide {
    @SerializedName("top")
    TOP("top"),

    @SerializedName("right")
    RIGHT("right"),

    @SerializedName("down")
    DOWN("down"),

    @SerializedName("left")
    LEFT("left");

    private final String protocolValue;

    TileRoadSide(String protocolValue) {
        this.protocolValue = protocolValue;
    }

    /**
     * Gibt den Protokollwert der Seite zurück.
     *
     * @return Protokollwert
     */
    public String getProtocolValue() {
        return protocolValue;
    }

    /**
     * Konvertiert einen Protokollwert in eine {@link TileRoadSide}.
     *
     * @param value Protokollwert
     * @return {@link TileRoadSide}
     * @throws IllegalArgumentException bei unbekanntem Wert
     */
    public static TileRoadSide fromProtocolValue(String value) {
        for (TileRoadSide side : TileRoadSide.values()) {
            if (side.getProtocolValue().equalsIgnoreCase(value)) {
                return side;
            }
        }
        throw new IllegalArgumentException("Unknown tile road side: " + value);
    }
}
