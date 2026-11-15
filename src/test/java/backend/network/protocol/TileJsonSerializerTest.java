package backend.network.protocol;

import backend.model.tile.Tile;
import backend.model.enums.EdgeType;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TileJsonSerializerTest {

    private static Tile tileNoScroll(int id, EdgeType top, EdgeType right, EdgeType down, EdgeType left) {
        return new Tile(
                id,
                top, right, down, left,
                null,   // roads
                null,   // elements
                null,   // scroll
                0, 0    // spriteX, spriteY
        );
    }

    @Test
    void serializeTile_withoutScroll_hasNullScrollField() {
        Tile tile = tileNoScroll(42, EdgeType.FIELD, EdgeType.FIELD, EdgeType.MOUNTAIN, EdgeType.WATER);

        var json = TileJsonSerializer.serialize(tile);

        assertEquals(42, json.get("id").getAsInt());

        // Robust: je nach Implementierung ist "scroll" JsonNull oder (falls gar nicht gesetzt) null
        JsonElement scroll = json.get("scroll");
        assertTrue(scroll == null || scroll.isJsonNull(), "scroll muss null/JsonNull sein, wenn kein Scroll vorhanden ist");
    }
}
