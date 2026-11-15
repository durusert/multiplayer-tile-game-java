package backend.network.protocol;

import com.google.gson.JsonObject;

/**
 * Hilfsmethoden für Chat-Nachrichten gemäß Protokoll 0.1.
 *
 * Client -> Server (senden):
 * {
 *   "messageType": "SendChat",
 *   "messageBody": {
 *     "message": "...",
 *     "to": 42   // oder -1 für alle
 *   }
 * }
 *
 * Server -> Client (verteilen):
 * {
 *   "messageType": "ReceivedChat",
 *   "messageBody": {
 *     "message": "...",
 *     "from": 7,
 *     "isPrivate": true/false
 *   }
 * }
 */
public final class ChatMessageHelper {

    private ChatMessageHelper() {}

    //Client -> Server: SendChat

    public static Message createSendChat(String message, int toClientId) {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("to", toClientId);
        return new Message(MessageType.SEND_CHAT, body);
    }

    // Server -> Client: ReceivedChat

    public static Message createReceivedChat(String message,
                                             int fromClientId,
                                             boolean isPrivate) {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("from", fromClientId);
        body.addProperty("isPrivate", isPrivate);
        return new Message(MessageType.RECEIVED_CHAT, body);
    }

    // Hilfsleser

    public static String getMessage(Message msg) {
        JsonObject body = msg.getMessageBody();
        if (body != null && body.has("message")) {
            return body.get("message").getAsString();
        }
        return null;
    }

    public static Integer getTo(Message msg) {
        JsonObject body = msg.getMessageBody();
        if (body != null && body.has("to") && !body.get("to").isJsonNull()) {
            return body.get("to").getAsInt();
        }
        return null;
    }

    public static Integer getFrom(Message msg) {
        JsonObject body = msg.getMessageBody();
        if (body != null && body.has("from") && !body.get("from").isJsonNull()) {
            return body.get("from").getAsInt();
        }
        return null;
    }
}