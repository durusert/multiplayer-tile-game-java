package backend.network.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Nachrichten gemäß SEP-Protokoll Version 2.0:
 * 
 * {
 *   "messageType": "SomeType",
 *   "messageBody": { ... }
 * }
 */
public class Message {
    private static final Gson GSON = new Gson();

    private String messageType;
    private JsonObject messageBody;

    public Message() {
    }

    // String basierte JSON Deserialisierung
    public Message(String MessageType, JsonObject messageBody) {
        this.messageType = MessageType;
        this.messageBody = messageBody;
    }

    // typsichere Enums verwendet
    public Message(MessageType type, JsonObject messageBody) {
        this.messageType = type.getWireName();
        this.messageBody = messageBody;
    }

    // Getter & Setter

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public JsonObject getMessageBody() {
        return messageBody;
    }

    public void setMessageBody(JsonObject messageBody) {
        this.messageBody = messageBody;
    }

    // ======= Backwards-kompatible Hilfsmethoden =======
    // (damit bestehender Code erst mal weiterkompiliert)

    /** Früher: getType() */
    public String getType() {
        return messageType;
    }

    /** Früher: getPayload() */
    public JsonObject getPayload() {
        return messageBody;
    }


    /**
     * Konvertiert die Nachricht zu einem JSON-String.
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Erstellt eine Message aus einem JSON-String.
     */
    public static Message fromJson(String json) {
        return GSON.fromJson(json, Message.class);
    }

    /**
     * Erzeugt eine Error-Nachricht gemäß Protokoll:
     *
     * {
     *   "messageType": "Error",
     *   "messageBody": {
     *     "error": "Fehlerbeschreibung"
     *   }
     * }
     */
    public static Message createError(String errorText) {
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("error", errorText);
        return new Message(MessageType.ERROR, body);
    }

    /**
     * Erzeugt eine Error-Nachricht mit zusätzlichem Code-Feld.
     *
     * {
     *   "messageType": "Error",
     *   "messageBody": {
     *     "code": "SOME_CODE",
     *     "error": "Fehlerbeschreibung"
     *   }
     * }
     *
     * Das "code"-Feld ist eine interne Hilfe und wird vom Protokoll nicht vorgeschrieben.
     */
    public static Message createError(String code, String errorText) {
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("error", errorText);
        body.addProperty("code", code);
        return new Message(MessageType.ERROR, body);
    }
}
