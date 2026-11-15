package backend.network.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

public final class JsonHelper {

    private JsonHelper() {}

    public static JsonObject json(Object... kv) {
        if (kv == null || kv.length == 0) {
            return new JsonObject();
        }
        if ((kv.length % 2) != 0) {
            throw new IllegalArgumentException("json(...) requires key/value pairs");
        }

        JsonObject obj = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];

            if (value == null) {
                obj.add(key, JsonNull.INSTANCE);
            } else if (value instanceof JsonElement je) {
                obj.add(key, je);
            } else if (value instanceof Number n) {
                obj.addProperty(key, n);
            } else if (value instanceof Boolean b) {
                obj.addProperty(key, b);
            } else if (value instanceof Character c) {
                obj.addProperty(key, c);
            } else {
                obj.addProperty(key, String.valueOf(value));
            }
        }
        return obj;
    }
}
