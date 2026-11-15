package frontend.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
/**
 * WebSocket-basierter Client für den MainServer.
 * Verwendet Token-basierte Authentifizierung gemäß Protokoll 0.1
 */
public class WebSocketChatClient {

    private static final Logger log =
            LogManager.getLogger(WebSocketChatClient.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;

    private final String serverHttp;
    private final String serverWs;

    private final String userId;
    private final CopyOnWriteArrayList<Consumer<String>> messageListeners = new CopyOnWriteArrayList<>();
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<Integer, String> idToName = new ConcurrentHashMap<>();
    private final Map<String, Integer> nameToId = new ConcurrentHashMap<>();
    private final Map<Integer, String> idToPiece = new ConcurrentHashMap<>();
    private final Deque<PendingDirectMessage> pendingPrivateMessages = new ArrayDeque<>();

    private WebSocket webSocket;
    private int myClientId = -1;
    private String roomCode;
    private Timer keepAliveTimer;

    private String birthDate;
    private String desiredGamePiece;

    private java.util.function.Consumer<java.util.Map<Integer, String>> onPlayersChanged;

    private volatile JsonObject lastGameStartedPayload;
    private volatile JsonObject lastRoundPhaseUpdatePayload;
    private final Map<Integer, JsonObject> lastCoinUpdateByClientId = new ConcurrentHashMap<>();


    /**
     * Erstellt einen WebSocket-Chat-Client.
     *
     * @param host Server-Host
     * @param port Server-Port
     * @param userId Benutzername
     * @param messageHandler optionaler Listener für eingehende Nachrichten
     */
    public WebSocketChatClient(String host, int port, String userId, Consumer<String> messageHandler) {
        this.userId = userId;
        String resolvedHost = (host == null || host.isBlank()) ? DEFAULT_HOST : host.trim();
        int resolvedPort = port > 0 ? port : DEFAULT_PORT;
        this.serverHttp = "http://" + resolvedHost + ":" + resolvedPort;
        this.serverWs = "ws://" + resolvedHost + ":" + resolvedPort;
        if (messageHandler != null) {
            this.messageListeners.add(messageHandler);
        }
        this.desiredGamePiece = null;
    }

    /**
     * Setzt das Geburtsdatum für den Join-Request.
     *
     * @param birthDate Geburtsdatum
     */
    public void setBirthDate(String birthDate) {
        this.birthDate = birthDate;
    }

    /**
     * Setzt die gewünschte Spielfigur.
     *
     * @param desiredGamePiece gewünschte Farbe
     */
    public void setDesiredGamePiece(String desiredGamePiece) {
        if (desiredGamePiece != null && !desiredGamePiece.isBlank()) {
            this.desiredGamePiece = desiredGamePiece.trim().toLowerCase();
        }
    }

    /**
     * Registriert einen Callback für Spielerlisten-Änderungen.
     *
     * @param cb Callback
     */
    public void setOnPlayersChanged(java.util.function.Consumer<java.util.Map<Integer, String>> cb) {
        this.onPlayersChanged = cb;
    }

    /**
     * Gibt eine Kopie der Spielfiguren-Zuordnung zurück.
     *
     * @return Map ClientId -> Spielfigur
     */
    public java.util.Map<Integer, String> getPlayerPiecesByClientId() {
        return java.util.Map.copyOf(idToPiece);
    }

    /**
     * Gibt eine Kopie der Spielernamen-Zuordnung zurück.
     *
     * @return Map ClientId -> Name
     */
    public java.util.Map<Integer, String> getPlayerNamesByClientId() {
        return java.util.Map.copyOf(idToName);
    }

    private void firePlayersChanged() {
        if (onPlayersChanged == null) return;
        onPlayersChanged.accept(java.util.Map.copyOf(idToName));
    }

    /**
     * Send a lightweight ack to the server indicating GameStarted was processed by the UI.
     */
    public void sendGameStartedAck() {
        try {
            if (webSocket == null) return;
            JsonObject msg = new JsonObject();
            msg.addProperty("messageType", "GameStartedAck");
            msg.add("messageBody", new JsonObject());
            webSocket.sendText(gson.toJson(msg), true);
        } catch (Exception ignored) {
        }
    }

    /**
     * Erstellt einen neuen Raum und verbindet sich
     */
    public void createRoomAndConnect() throws Exception {
        var createResp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(serverHttp + "/api/createRoom"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (createResp.statusCode() != 200) {
            throw new RuntimeException("Raum erstellen fehlgeschlagen: " + createResp.body());
        }

        this.roomCode = gson.fromJson(createResp.body(), JsonObject.class).get("roomCode").getAsString();
        log.info("Raum erstellt: {}", roomCode);

        connectToRoom(roomCode);
    }

    /**
     * Verbindet sich zu einem existierenden Raum
     */
    public void connect() throws Exception {
        if (roomCode == null || roomCode.isBlank()) {
            createRoomAndConnect();
        } else {
            connectToRoom(roomCode);
        }
    }

    /**
     * Setzt den Raumcode für die Verbindung.
     *
     * @param code Raumcode
     */
    public void setRoomCode(String code) {
        this.roomCode = code;
    }

    /**
     * Fügt einen Message-Listener hinzu.
     *
     * @param listener Listener
     */
    public void addMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.addIfAbsent(listener);
        }
    }

    /**
     * Entfernt einen Message-Listener.
     *
     * @param listener Listener
     */
    public void removeMessageListener(Consumer<String> listener) {
        if (listener != null) {
            messageListeners.remove(listener);
        }
    }

    /**
     * Liefert den zuletzt empfangenen GameStarted-Payload.
     *
     * @return Payload oder {@code null}
     */
    public JsonObject getLastGameStartedPayload() {
        return lastGameStartedPayload;
    }

    /**
     * Liefert den zuletzt empfangenen RoundPhaseUpdate-Payload.
     *
     * @return Payload oder {@code null}
     */
    public JsonObject getLastRoundPhaseUpdatePayload() {
        return lastRoundPhaseUpdatePayload;
    }

    /**
     * Liefert den zuletzt empfangenen CoinUpdate-Payload für einen Client.
     *
     * @param clientId Client-ID
     * @return Payload oder {@code null}
     */
    public JsonObject getLastCoinUpdatePayload(int clientId) {
        return lastCoinUpdateByClientId.get(clientId);
    }

    private void dispatchMessage(String messageJson) {
        if (messageJson == null) return;
        for (Consumer<String> listener : messageListeners) {
            try {
                listener.accept(messageJson);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void captureLastKnownState(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return;

        try {
            JsonObject msg = gson.fromJson(rawJson, JsonObject.class);
            if (msg == null) return;

            String type = null;
            JsonObject payload = null;

            if (msg.has("type")) {
                type = msg.get("type").getAsString();
                if (msg.has("payload") && msg.get("payload").isJsonObject()) {
                    payload = msg.getAsJsonObject("payload");
                }
            } else if (msg.has("messageType")) {
                type = msg.get("messageType").getAsString();
                if (msg.has("messageBody") && msg.get("messageBody").isJsonObject()) {
                    payload = msg.getAsJsonObject("messageBody");
                }
            }

            if (type == null) return;

            String normalized = type.trim();

            if ("GameStarted".equalsIgnoreCase(normalized)
                    || "game_started".equals(normalized)) {

                if (payload != null) {
                    lastGameStartedPayload = payload.deepCopy();
                }
                log.debug(
                        "Cached GameStarted snapshot at {} payload={}",
                        java.time.Instant.now(),
                        payload != null ? payload : "null"
                );
                return;
            }

            if ("RoundPhaseUpdate".equalsIgnoreCase(normalized)
                    || "round_phase_update".equals(normalized)) {
                if (payload != null) {
                    lastRoundPhaseUpdatePayload = payload.deepCopy();
                }
                log.debug(
                        "Cached RoundPhaseUpdate snapshot at {} payload={}",
                        java.time.Instant.now(),
                        payload != null ? payload : "null"
                );
                return;
            }
            if ("CoinUpdate".equals(normalized) || "COIN_UPDATE".equals(normalized) || "coin_update".equals(normalized)) {
                if (payload != null && payload.has("clientID")) {
                    int cid = payload.get("clientID").getAsInt();
                    lastCoinUpdateByClientId.put(cid, payload.deepCopy());
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Verbindet sich zu einem Raum mit Token-Authentifizierung
     */
    private void connectToRoom(String code) throws Exception {
        JsonObject joinBody = new JsonObject();
        joinBody.addProperty("roomCode", code);
        joinBody.addProperty("userId", userId);

        var joinResp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(serverHttp + "/api/joinRoom"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(joinBody)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (joinResp.statusCode() != 200) {
            throw new RuntimeException("Beitreten fehlgeschlagen: " + joinResp.body());
        }

        String token = gson.fromJson(joinResp.body(), JsonObject.class).get("token").getAsString();
        log.info("Token erhalten, verbinde WebSocket...");

        webSocket = http.newWebSocketBuilder()
                .buildAsync(URI.create(serverWs + "/game?token=" + token), new WebSocket.Listener() {

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        handleMessage(data.toString());
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.error("WebSocket Fehler", error);
                        JsonObject errMsg = new JsonObject();
                        errMsg.addProperty("type", "system");
                        JsonObject payload = new JsonObject();
                        payload.addProperty("message", "Connection error: " + error.getMessage());
                        errMsg.add("payload", payload);
                        dispatchMessage(gson.toJson(errMsg));
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        log.info("WebSocket geschlossen: {}", reason);
                        stopKeepAlive();
                        return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                    }
                }).join();

        log.info("WebSocket verbunden");
        startKeepAlive();
    }

    private void startKeepAlive() {
        keepAliveTimer = new Timer("WebSocket-KeepAlive", true);
        keepAliveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (webSocket != null) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("messageType", "Alive");
                    msg.add("messageBody", new JsonObject());
                    webSocket.sendText(gson.toJson(msg), true);
                }
            }
        }, 5000, 5000);
    }

    private void rememberPlayer(int clientId, String name) {
        if (name == null) return;
        idToName.put(clientId, name);
        nameToId.put(name, clientId);
        log.info("PlayerAdded: {} -> {}", clientId, name);
        firePlayersChanged();
    }

    private void forgetPlayer(int clientId, String name) {
        if (clientId >= 0) {
            String removed = idToName.remove(clientId);
            if (removed != null) {
                nameToId.remove(removed);
            }
            idToPiece.remove(clientId);
        }
        if (name != null && !name.isBlank()) {
            nameToId.remove(name);
        }
    }

    private void rememberPlayerPiece(int clientId, String piece) {
        if (clientId < 0 || piece == null || piece.isBlank()) return;
        idToPiece.put(clientId, piece.trim().toLowerCase());
    }

    private void trackPendingPrivateMessage(String targetName, String message) {
        if (targetName == null || targetName.isBlank() || message == null) return;
        synchronized (pendingPrivateMessages) {
            pendingPrivateMessages.addLast(new PendingDirectMessage(targetName));
            while (pendingPrivateMessages.size() > 50) {
                pendingPrivateMessages.removeFirst();
            }
        }
    }

    private PendingDirectMessage consumePendingPrivate() {
        synchronized (pendingPrivateMessages) {
            return pendingPrivateMessages.pollFirst();
        }
    }

    private String normalizePlayerName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.endsWith(" (you)")) {
            trimmed = trimmed.substring(0, trimmed.length() - 6).trim();
        }
        return trimmed;
    }

    private Integer resolveClientId(String playerName) {
        String normalized = normalizePlayerName(playerName);
        if (normalized == null || normalized.isBlank()) return null;
        return nameToId.get(normalized);
    }

    private void stopKeepAlive() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }
    }

    private void handleMessage(String raw) {
        try {
            captureLastKnownState(raw);
            JsonObject msg = gson.fromJson(raw, JsonObject.class);
            String type = msg.has("messageType") ? msg.get("messageType").getAsString() : "";
            JsonObject body = msg.has("messageBody") ? msg.getAsJsonObject("messageBody") : new JsonObject();

            JsonObject converted = new JsonObject();
            JsonObject payload = new JsonObject();

            switch (type) {
                case "HelloClient" -> {
                    sendHelloServer();
                    return;
                }
                case "Welcome" -> {
                    myClientId = body.get("clientID").getAsInt();
                    sendPlayerValues();
                    converted.addProperty("type", "welcome");
                    if (body.has("players")) {
                        var players = body.getAsJsonArray("players");
                        var playerNames = new com.google.gson.JsonArray();
                        JsonObject piecesByName = new JsonObject();
                        for (var p : players) {
                            if (!p.isJsonObject()) continue;
                            JsonObject po = p.getAsJsonObject();
                            if (!po.has("name")) continue;
                            String name = po.get("name").getAsString();
                            playerNames.add(name);
                            if (po.has("clientID")) {
                                int pid = po.get("clientID").getAsInt();
                                rememberPlayer(pid, name);
                            }
                            if (po.has("gamePiece") && po.get("gamePiece").isJsonPrimitive()) {
                                String piece = po.get("gamePiece").getAsString();
                                piecesByName.addProperty(name, piece);
                                if (po.has("clientID")) {
                                    int pid = po.get("clientID").getAsInt();
                                    rememberPlayerPiece(pid, piece);
                                }
                            }
                        }
                        payload.add("players", playerNames);
                        payload.add("playerPieces", piecesByName);
                    }
                    payload.addProperty("clientID", myClientId);
                }
                case "PlayerAdded" -> {
                    converted.addProperty("type", "player_joined");
                    String name = body.get("name").getAsString();
                    int pid = body.get("clientID").getAsInt();
                    rememberPlayer(pid, name);
                    payload.addProperty("playerId", name);
                    payload.addProperty("clientID", pid);
                    if (body.has("gamePiece")) {
                        String piece = body.get("gamePiece").getAsString();
                        payload.addProperty("gamePiece", piece);
                        rememberPlayerPiece(pid, piece);
                    }
                }
                case "ConnectionUpdate" -> {
                    converted.addProperty("type", "player_updated");
                    if (body.has("name")) {
                        String name = body.get("name").getAsString();
                        int pid = body.has("clientID") ? body.get("clientID").getAsInt() : -1;
                        if (pid >= 0) rememberPlayer(pid, name);
                        payload.addProperty("playerId", name);
                        payload.addProperty("clientID", pid);
                    }
                    if (body.has("gamePiece")) {
                        String piece = body.get("gamePiece").getAsString();
                        payload.addProperty("gamePiece", piece);
                        int pid = body.has("clientID") ? body.get("clientID").getAsInt() : -1;
                        if (pid < 0 && body.has("name")) {
                            Integer resolved = resolveClientId(body.get("name").getAsString());
                            pid = (resolved == null) ? -1 : resolved;
                        }
                        if (pid >= 0) {
                            rememberPlayerPiece(pid, piece);
                        }
                    }
                }
                case "PlayerLeft" -> {
                    converted.addProperty("type", "player_left");
                    if (body.has("clientID")) {
                        int pid = body.get("clientID").getAsInt();
                        payload.addProperty("clientID", pid);
                        forgetPlayer(pid, null);
                    }
                    if (body.has("userId")) {
                        String name = body.get("userId").getAsString();
                        payload.addProperty("playerId", name);
                        forgetPlayer(-1, name);
                    } else if (body.has("name")) {
                        String name = body.get("name").getAsString();
                        payload.addProperty("playerId", name);
                        forgetPlayer(-1, name);
                    }
                    firePlayersChanged();
                }
                case "PlayerStatus" -> {
                    converted.addProperty("type", "player_status");
                    payload.addProperty("clientID", body.get("clientID").getAsInt());
                    payload.addProperty("ready", body.get("ready").getAsBoolean());
                }
                case "AddAiResponse" -> {
                    converted.addProperty("type", "add_ai_response");
                    if (body.has("success")) payload.addProperty("success", body.get("success").getAsBoolean());
                    if (body.has("errorCode")) payload.addProperty("errorCode", body.get("errorCode").getAsString());
                    if (body.has("errorMessage")) payload.addProperty("errorMessage", body.get("errorMessage").getAsString());
                }
                case "RemoveAiResponse" -> {
                    converted.addProperty("type", "remove_ai_response");
                    if (body.has("success")) payload.addProperty("success", body.get("success").getAsBoolean());
                    if (body.has("errorCode")) payload.addProperty("errorCode", body.get("errorCode").getAsString());
                    if (body.has("errorMessage")) payload.addProperty("errorMessage", body.get("errorMessage").getAsString());
                }
                case "ReceivedChat" -> {
                    boolean isPrivate = body.has("isPrivate") && body.get("isPrivate").getAsBoolean();
                    converted.addProperty("type", isPrivate ? "dm_message" : "chat_message");

                    String displayName;
                    int fromId = -1;
                    if (body.has("from") && body.get("from").isJsonPrimitive() && body.get("from").getAsJsonPrimitive().isNumber()) {
                        fromId = body.get("from").getAsInt();
                        displayName = idToName.getOrDefault(fromId, "Player " + fromId);
                    } else {
                        displayName = body.get("from").getAsString();
                    }
                    payload.addProperty("from", displayName);

                    String text = body.has("message") ? body.get("message").getAsString() : "";
                    payload.addProperty("message", text);
                    payload.addProperty("isPrivate", isPrivate);

                    if (isPrivate) {
                        String toName = null;
                        if (body.has("to") && body.get("to").isJsonPrimitive()) {
                            int toId = body.get("to").getAsInt();
                            toName = idToName.getOrDefault(toId, "Player " + toId);
                        }
                        if (toName == null && fromId == myClientId) {
                            PendingDirectMessage pending = consumePendingPrivate();
                            if (pending != null) {
                                toName = pending.targetName;
                            }
                        } else if (toName == null) {
                            toName = userId;
                        }

                        if (toName != null) {
                            payload.addProperty("to", toName);
                        }
                    }
                }
                case "GameStarted" -> {
                    converted.addProperty("type", "game_started");
                    payload.add("scoringIDs", body.get("scoringIDs"));
                    if (body.has("boardType")) {
                        payload.add("boardType", body.get("boardType"));
                    }
                    if (body.has("castles")) {
                        payload.add("castles", body.get("castles"));
                    }
                }
                case "RoundPhaseUpdate" -> {
                    converted.addProperty("type", "round_phase_update");
                    payload.addProperty("round", body.get("round").getAsInt());
                    if (body.has("phase") && body.get("phase").isJsonPrimitive() && body.get("phase").getAsJsonPrimitive().isNumber()) {
                        payload.addProperty("phase", mapPhaseToLegacyString(body.get("phase").getAsInt()));
                    } else {
                        payload.addProperty("phase", body.get("phase").getAsString());
                    }
                }
                case "StartPlayerUpdate" -> {
                    converted.addProperty("type", "start_player_update");
                    if (body.has("clientID")) payload.add("clientID", body.get("clientID"));
                    if (body.has("name")) payload.add("name", body.get("name"));
                }
                case "CurrentPlayer" -> {
                    converted.addProperty("type", "current_player");
                    if (body.has("clientID")) payload.add("clientID", body.get("clientID"));
                    if (body.has("name")) payload.add("name", body.get("name"));
                }
                case "LandscapeTileDrawn" -> {
                    converted.addProperty("type", "tile_drawn");
                    payload.addProperty("clientID", body.get("clientID").getAsInt());
                    if (body.has("drawNumber")) {
                        payload.addProperty("drawNumber", body.get("drawNumber").getAsInt());
                    }
                    payload.add("tile", body.get("tile"));
                }
                case "RevealTilePrices" -> {
                    converted.addProperty("type", "reveal_tile_prices");
                    if (body.has("tilePrices")) {
                        payload.add("tilePrices", body.get("tilePrices"));
                    }
                }
                case "PriceSubmitRejected" -> {
                    converted.addProperty("type", "price_submit_rejected");
                    if (body.has("clientID")) payload.add("clientID", body.get("clientID"));
                    if (body.has("reason")) payload.addProperty("reason", body.get("reason").getAsString());
                    if (body.has("code")) payload.addProperty("code", body.get("code").getAsString());
                }
                case "AvailableTiles" -> {
                    converted.addProperty("type", "available_tiles");
                    if (body.has("availableTiles")) {
                        payload.add("availableTiles", body.getAsJsonArray("availableTiles"));
                    }
                    if (body.has("tiles")) {
                        payload.add("tiles", body.getAsJsonArray("tiles"));
                    }
                    if (body.has("clientID")) {
                        payload.add("clientID", body.get("clientID"));
                    }
                }
                case "TilePurchased" -> {
                    converted.addProperty("type", "tile_purchased");
                    if (body.has("buyerID")) {
                        payload.add("buyerClientID", body.get("buyerID"));
                    }
                    if (body.has("sellerID")) {
                        payload.add("sellerClientID", body.get("sellerID"));
                    }
                    if (body.has("tileID")) {
                        payload.add("tileID", body.get("tileID"));
                    }
                    if (body.has("price")) {
                        payload.add("price", body.get("price"));
                    }
                    if (body.has("nextPlayerID")) {
                        payload.add("nextPlayerID", body.get("nextPlayerID"));
                    }
                }
                case "TileBuilt" -> {
                    converted.addProperty("type", "tile_built");
                    for (String key : new String[] { "clientID", "tileID", "x_position", "y_position", "rotation" }) {
                        if (body.has(key)) {
                            payload.add(key, body.get(key));
                        }
                    }
                }
                case "TileReturned" -> {
                    converted.addProperty("type", "tile_returned");
                    for (String key : new String[] { "clientID", "tileID", "reason" }) {
                        if (body.has(key)) {
                            payload.add(key, body.get(key));
                        }
                    }
                }
                case "CoinUpdate" -> {
                    converted.addProperty("type", "coin_update");
                    if (body.has("clientID")) payload.add("clientID", body.get("clientID"));
                    if (body.has("coins")) payload.add("coins", body.get("coins"));
                }
                case "IncomeReceived" -> {
                    converted.addProperty("type", "coin_update");
                    if (body.has("clientID")) payload.add("clientID", body.get("clientID"));
                    if (body.has("totalCoins")) payload.add("coins", body.get("totalCoins"));
                    if (body.has("income")) payload.add("income", body.get("income"));
                }
                case "ScoringUpdate" -> {
                    converted.addProperty("type", "scoring_update");
                    payload.add("scoring", body);

                }
                case "GameOver" -> {
                    converted.addProperty("type", "game_over");
                    payload.add("winners", body.getAsJsonArray("winners"));
                    if (body.has("winnerNames")) payload.add("winnerNames", body.getAsJsonArray("winnerNames"));
                    if (body.has("maxVictoryPoints")) payload.add("maxVictoryPoints", body.get("maxVictoryPoints"));
                }


                case "Error" -> {
                    converted.addProperty("type", "error");
                    if (body.has("error")) {
                        payload.addProperty("code", body.get("error").getAsString());
                    }
                    if (body.has("message")) {
                        payload.addProperty("message", body.get("message").getAsString());
                    }
                }
                default -> {
                    converted.addProperty("type", type.toLowerCase());
                    converted.add("payload", body);
                    dispatchMessage(gson.toJson(converted));
                    return;
                }
            }

            converted.add("payload", payload);
            dispatchMessage(gson.toJson(converted));

        } catch (Exception e) {
            log.error("Error while parsing the message: {}", e.getMessage());
        }
    }

    private void sendProtocolMessage(String messageType, JsonObject body) {
        if (webSocket == null) {
            log.error("WebSocket not connected");
            return;
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("messageType", messageType);
        msg.add("messageBody", body != null ? body : new JsonObject());
        String jsonMessage = gson.toJson(msg);
        log.info("CLIENT: sendProtocolMessage() sending messageType={}, full message: {}", messageType, jsonMessage);
        webSocket.sendText(jsonMessage, true);
    }



    public void sendCollectIncome() {
        sendProtocolMessage("CollectIncome", new JsonObject());
    }

    public void resendPlayerValues() {
        sendPlayerValues();
    }

    private void sendHelloServer() {
        JsonObject body = new JsonObject();
        body.addProperty("group", "TolleTrolle");
        body.addProperty("isAI", false);
        body.addProperty("protocol", "Version 2.0");
        sendProtocolMessage("HelloServer", body);
    }

    private void sendPlayerValues() {
        if (webSocket == null) return;
        JsonObject pv = new JsonObject();
        pv.addProperty("messageType", "PlayerValues");
        JsonObject body = new JsonObject();
        body.addProperty("name", userId);
        if (desiredGamePiece != null) {
            body.addProperty("gamePiece", desiredGamePiece);
        }
        if (birthDate != null && !birthDate.isBlank()) {
            body.addProperty("birthDate", birthDate);
        } else {
            body.add("birthDate", com.google.gson.JsonNull.INSTANCE);
        }
        pv.add("messageBody", body);
        try {
            webSocket.sendText(gson.toJson(pv), true);
        } catch (Exception ignored) {
        }
    }

    private String mapPhaseToLegacyString(int phase) {
        return switch (phase) {
            case 0 -> "INCOME";
            case 1 -> "DRAW";
            case 2 -> "DISCARD";
            case 3 -> "REVEAL";
            case 4 -> "BUY";
            case 5 -> "BUILD";
            case 6 -> "SCORING";
            case 7 -> "NONE";
            default -> String.valueOf(phase);
        };
    }

    public void sendMessage(String message) {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("isPrivate", false);
        body.addProperty("to", -1);
        sendProtocolMessage("SendChat", body);
    }

    public void setReady(boolean ready) {
        JsonObject body = new JsonObject();
        body.addProperty("ready", ready);
        sendProtocolMessage("SetStatus", body);
    }

    public void setTilePrices(JsonArray tilePrices) {
        log.info("CLIENT: setTilePrices() called with {} tiles", tilePrices != null ? tilePrices.size() : 0);
        JsonObject body = new JsonObject();
        body.add("tilePrices", tilePrices != null ? tilePrices : new JsonArray());
        log.info("CLIENT: Sending SetTilePrices message with body: {}", body);
        sendProtocolMessage("SetTilePrices", body);
    }

    public void buyTile(Integer tileId) {
        JsonObject body = new JsonObject();
        if (tileId == null) {
            body.add("tileID", JsonNull.INSTANCE);
        } else {
            body.addProperty("tileID", tileId);
        }
        sendProtocolMessage("BuyTile", body);
    }

    public void buildTile(int tileId, int x, int y, int rotationDegrees) {
        JsonObject body = new JsonObject();
        body.addProperty("tileID", tileId);
        body.addProperty("x_position", x);
        body.addProperty("y_position", y);
        body.addProperty("rotation", rotationDegrees);
        log.debug("CLIENT_SEND BuildTile tileID={} x={} y={} rotation={}", tileId, x, y, rotationDegrees);
        sendProtocolMessage("BuildTile", body);
    }

    public void close() {
        stopKeepAlive();
        if (webSocket != null) {
            webSocket.sendClose(1000, "bye");
        }
    }

    public int getClientId() {
        return myClientId;
    }

    public void sendDrawLandscape(int drawNumber) {
        JsonObject body = new JsonObject();
        body.addProperty("drawNumber", drawNumber);
        sendProtocolMessage("DrawLandscapeTile", body);
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void sendDirectMessage(int targetClientId, String message) {
        if (message == null || message.isBlank()) return;

        if (targetClientId < 0) {
            log.warn("sendDirectMessage: Invalid target ID {}", targetClientId);
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        body.addProperty("to", targetClientId);
        body.addProperty("isPrivate", true);

        String targetName = idToName.getOrDefault(targetClientId, "Client " + targetClientId);
        trackPendingPrivateMessage(normalizePlayerName(targetName), message);

        sendProtocolMessage("SendChat", body);
    }
    public void sendDirectMessage(String targetPlayerName, String message) {
        if (message == null || message.isBlank()) return;

        Integer targetId = resolveClientId(targetPlayerName);

        if (targetId == null) {
            log.warn("sendDirectMessage: unknown player {}", targetPlayerName);
            return;
        }
        sendDirectMessage(targetId, message);
    }

    private record PendingDirectMessage(String targetName) {
    }

    private Consumer<String> activeListener;

    public void setMessageListener(Consumer<String> l) {
        if (activeListener != null) removeMessageListener(activeListener);
        activeListener = l;
        addMessageListener(activeListener);
    }

    public void requestAddAi() {
        sendProtocolMessage("AddAiRequest", new JsonObject());
    }

    public void requestRemoveAiFirst() {
        sendProtocolMessage("RemoveAiRequest", new JsonObject());
    }


    public void requestAddAi(String algorithm) {
        JsonObject body = new JsonObject();
        if (algorithm != null && !algorithm.isBlank()) {
            body.addProperty("algorithm", algorithm);
        }
        sendProtocolMessage("AddAiRequest", body);
    }
}
