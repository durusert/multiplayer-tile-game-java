package backend.network.protocol;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void toJson_and_fromJson_roundtrip() {
        JsonObject body = new JsonObject();
        body.addProperty("x", 5);

        Message msg = new Message(MessageType.ALIVE, body);
        String json = msg.toJson();

        Message parsed = Message.fromJson(json);

        assertEquals("Alive", parsed.getMessageType());
        assertEquals(5, parsed.getMessageBody().get("x").getAsInt());
    }

    @Test
    void createError_containsErrorField() {
        Message err = Message.createError("TEST", "Fehler");

        assertEquals("Error", err.getMessageType());
        assertEquals("TEST", err.getMessageBody().get("code").getAsString());
        assertEquals("Fehler", err.getMessageBody().get("error").getAsString());
    }
}
