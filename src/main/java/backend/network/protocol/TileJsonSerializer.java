package backend.network.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import backend.model.tile.Tile;

public final class TileJsonSerializer {

    private TileJsonSerializer() {}

    /**
     * Serialisiert ein Tile gemäß Protokoll 0.1
     */
    public static JsonObject serialize(Tile tile) {
        JsonObject tileJson = new JsonObject();
        tileJson.addProperty("id", tile.getTileId());
        tileJson.addProperty("top", tile.getTop() != null ? tile.getTop().name().toLowerCase() : "none");
        tileJson.addProperty("right", tile.getRight() != null ? tile.getRight().name().toLowerCase() : "none");
        tileJson.addProperty("down", tile.getDown() != null ? tile.getDown().name().toLowerCase() : "none");
        tileJson.addProperty("left", tile.getLeft() != null ? tile.getLeft().name().toLowerCase() : "none");

        JsonArray roadsArray = new JsonArray();
        if (tile.getRoads() != null) {
            for (var road : tile.getRoads()) {
                JsonArray roadPart = new JsonArray();
                for (var side : road) {
                    roadPart.add(side.name().toLowerCase());
                }
                roadsArray.add(roadPart);
            }
        }
        tileJson.add("roads", roadsArray);

        JsonArray elementsArray = new JsonArray();
        if (tile.getElements() != null) {
            for (var element : tile.getElements()) {
                elementsArray.add(element.name().toLowerCase());
            }
        }
        tileJson.add("elements", elementsArray);

        if (tile.hasScroll()) {
            JsonObject scrollJson = new JsonObject();
            scrollJson.addProperty("points", tile.getScroll().getPoints());
            JsonArray scrollElements = new JsonArray();
            for (var el : tile.getScroll().getElements()) {
                scrollElements.add(el.name().toLowerCase());
            }
            scrollJson.add("elements", scrollElements);
            tileJson.add("scroll", scrollJson);
        } else {
            tileJson.add("scroll", null);
        }

        tileJson.addProperty("spriteX", tile.getSpriteX());
        tileJson.addProperty("spriteY", tile.getSpriteY());

        return tileJson;
    }
}
