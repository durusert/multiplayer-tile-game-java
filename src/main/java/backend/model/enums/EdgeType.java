package backend.model.enums;

import com.google.gson.annotations.SerializedName;

/**
 * Landschaftstyp an Kanten der Tiles - für Platzierungsregeln benötigt.
 */
public enum EdgeType {
    @SerializedName("water")
    WATER("water"),

    @SerializedName("mountain")
    MOUNTAIN("mountain"),

    @SerializedName("field")
    FIELD("field");

    private final String protocolValue;

    EdgeType(String protocolValue) {
        this.protocolValue = protocolValue;
    }

    /**
     * Gibt den Protokollwert des Typs zurück.
     *
     * @return Protokollwert
     */
    public String getProtocolValue() {
        return protocolValue;
    }

    /**
     * Konvertiert einen Protokollwert in einen {@link EdgeType}.
     *
     * @param value Protokollwert
     * @return {@link EdgeType}
     * @throws IllegalArgumentException bei unbekanntem Wert
     */
    public static EdgeType fromProtocolValue(String value) {
        for (EdgeType type : EdgeType.values()) {
            if (type.getProtocolValue().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown landscape type: " + value);
    }
}
