package backend.model.tile;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Lädt Tiles aus der JSON-Ressource.
 */
public final class TileJsonLoader {

    private static final String TILE_JSON_PATH = "/tiles/tiles-v3.json";
    private static final Gson GSON = new Gson();

    private TileJsonLoader() {}

    /**
     * Lädt alle Tiles aus der JSON-Datei.
     *
     * @return Liste der {@link Tile}
     */
    public static List<Tile> loadTiles() {
        InputStream in = TileJsonLoader.class.getResourceAsStream(TILE_JSON_PATH);
        if (in == null) {
            throw new IllegalStateException("Could not find " + TILE_JSON_PATH);
        }

        try (InputStreamReader reader = new InputStreamReader(in)) {
            Type listType = new TypeToken<ArrayList<Tile>>() {}.getType();
            return GSON.fromJson(reader, listType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load tiles from JSON", e);
        }
    }
}
