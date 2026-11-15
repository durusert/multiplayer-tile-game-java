package backend.network;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import backend.ai.AiClient;
import backend.ai.AiClientFactory;
import backend.model.tile.TileBag;
import backend.model.tile.Tile;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import backend.model.enums.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import backend.logic.GameLogic;
import backend.model.Game;
import backend.model.Player;
import backend.model.playeraction.PriceDecision;
import backend.model.playeraction.TilePlacement;
import backend.model.tile.PlacedTile;
import backend.network.protocol.MessageType;
import backend.model.enums.GameStatus;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Zentraler Server für Isle of Skye.
 * Kombiniert WebSocket-Kommunikation mit Spiellogik.
 *
 * HTTP:  POST /api/createRoom, POST /api/joinRoom
 * WebSocket: /game/{roomCode}?userId={userId}
 * 
 * Alle Nachrichten folgen Protokoll 0.1:
 * { "messageType": "...", "messageBody": { ... } }
 */
public class MainServer {

    private static final Logger log =
            LogManager.getLogger(MainServer.class);

    public static final int PORT = 8080;

    // Gson mit serializeNulls() für Protokoll-konforme null-Werte in JSON
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Random random = new Random();
    
    // Room-Verwaltung
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Map<WsContext, String> ctxToRoom = new ConcurrentHashMap<>();
    
    // Token-System: Token -> {roomCode, userId}
    private final Map<String, JoinToken> pendingTokens = new ConcurrentHashMap<>();
    
    // Scheduler für Alive-Heartbeat (Protokoll: alle 5 Sekunden)
    private final ScheduledExecutorService aliveScheduler = Executors.newSingleThreadScheduledExecutor();

    // AI-Scheduler: prüft regelmäßig ob ein Raum mit 1 Spieler existiert
    private final ScheduledExecutorService aiScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Set<String> roomsWithAI = ConcurrentHashMap.newKeySet();

    // Scheduler for INCOME auto-collect (timeouts)
    private final ScheduledExecutorService incomeScheduler = Executors.newScheduledThreadPool(2);

    // Verfügbare Spielfarben
    private static final List<GamePiece> AVAILABLE_COLORS = List.of(
            GamePiece.BLUE, GamePiece.GREEN, GamePiece.RED, GamePiece.YELLOW, GamePiece.WHITE
    );

    // Token für Raumbeitritt (enthält roomCode + userId)
    record JoinToken(String roomCode, String userId, long createdAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > 60_000; // 60 Sekunden gültig
        }
    }

    public static void main(String[] args) {
        new MainServer().start();
    }

    public void start() {
        Javalin app = Javalin.create(cfg ->
                cfg.bundledPlugins.enableCors(cors -> cors.addRule(r -> r.anyHost()))
        );
        
        startAliveHeartbeat();

        // Raum erstellen
        app.post("/api/createRoom", ctx -> {
            String code = generateRoomCode();
            rooms.put(code, new GameRoom());
            ctx.json(Map.of(
                    "roomCode", code,
                    "wsUrl", "ws://localhost:" + PORT + "/game"
            ));
            log.info("Raum erstellt: {}", code);
        });

        // Raum beitreten - gibt Token zurück
        app.post("/api/joinRoom", ctx -> {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String code = body.has("roomCode") ? body.get("roomCode").getAsString().toLowerCase() : "";
            String userId = body.has("userId") ? body.get("userId").getAsString().trim() : "";

            if (!rooms.containsKey(code)) {
                ctx.status(404).json(Map.of("error", "Raum nicht gefunden"));
                return;
            }
            if (userId.isBlank()) {
                ctx.status(400).json(Map.of("error", "userId fehlt"));
                return;
            }

            GameRoom room = rooms.get(code);
            if (room.getGame().getStatus() == GameStatus.RUNNING) {
                ctx.status(409).json(Map.of("error", "GAME_ALREADY_STARTED"));
                return;
            }

            // Token generieren
            String token = generateToken();
            pendingTokens.put(token, new JoinToken(code, userId, System.currentTimeMillis()));

            ctx.json(Map.of(
                    "token", token,
                    "wsUrl", "ws://localhost:" + PORT + "/game?token=" + token
            ));
            log.info("Token created for {} -> room {}", userId, code);
        });

        // Raum-Info abrufen
        app.get("/api/room/{roomCode}", ctx -> {
            String code = ctx.pathParam("roomCode").toLowerCase();
            if (!rooms.containsKey(code)) {
                ctx.status(404).json(Map.of("error", "Raum nicht gefunden"));
                return;
            }
            GameRoom room = rooms.get(code);
            ctx.json(Map.of(
                    "roomCode", code,
                    "playerCount", room.getPlayers().size()
            ));
        });

        // Liste aller offenen Räume
        app.get("/api/rooms", ctx -> {
            var roomList = rooms.entrySet().stream()
                    .map(e -> Map.of(
                            "roomCode", e.getKey(),
                            "playerCount", e.getValue().getPlayers().size()
                    ))
                    .toList();
            ctx.json(Map.of("rooms", roomList));
        });

        app.ws("/game", ws -> {
            ws.onConnect(ctx -> {
                // Erhöhe Idle Timeout auf 5 Minuten
                ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(5));
                onConnect(ctx);
            });
            ws.onMessage(ctx -> onMessage(ctx, ctx.message()));
            ws.onClose(ctx -> onClose(ctx));
            ws.onError(ctx -> log.error("WebSocket error: {}", ctx.error()));
        });

        app.start(PORT);
        log.info("===========================================");
        log.info("MainServer läuft auf http://localhost: {}", PORT);
        log.info("===========================================");
    }
    
    /**
     * Startet den Alive-Heartbeat-Scheduler (Protokoll 0.1: alle 5 Sekunden)
     */
    private void startAliveHeartbeat() {
        aliveScheduler.scheduleAtFixedRate(() -> {
            try {
                JsonObject aliveBody = new JsonObject();
                String aliveMessage = createMessage(MessageType.ALIVE, aliveBody);
                
                for (GameRoom room : rooms.values()) {
                    room.broadcastAll(aliveMessage);
                }
            } catch (Exception e) {
                log.error("Error while sending alive message: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void onConnect(WsContext ctx) {
        String token = ctx.queryParam("token");

        if (token == null || token.isBlank()) {
            ctx.closeSession(1008, "Token fehlt");
            return;
        }

        JoinToken joinToken = pendingTokens.remove(token);
        if (joinToken == null) {
            ctx.closeSession(1008, "Ungültiges Token");
            return;
        }
        if (joinToken.isExpired()) {
            ctx.closeSession(1008, "Token abgelaufen");
            return;
        }

        String code = joinToken.roomCode();
        String userId = joinToken.userId();

        if (!rooms.containsKey(code)) {
            ctx.closeSession(1008, "Raum nicht mehr vorhanden");
            return;
        }

        GameRoom room = rooms.get(code);

        if (room.getGame().getStatus() == GameStatus.RUNNING) {
            ctx.closeSession(1008, "Game already started");
            return;
        }


        int clientId = room.addClient(ctx, userId);
        ctxToRoom.put(ctx, code);


        JsonObject helloBody = new JsonObject();
        helloBody.addProperty("protocol", "Version 2.0");
        sendToClient(ctx, MessageType.HELLO_CLIENT, helloBody);

        // Welcome mit clientID und bestehenden Spielern senden
        JsonObject welcomeBody = new JsonObject();
        welcomeBody.addProperty("clientID", clientId);
        welcomeBody.addProperty("roomCode", code);
        
        JsonArray playersArray = new JsonArray();
        for (var entry : room.getPlayers().entrySet()) {
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("clientID", entry.getKey());
            playerJson.addProperty("name", entry.getValue().getName());
            if (entry.getValue().getGamePiece() != null) {
                playerJson.addProperty("gamePiece", entry.getValue().getGamePiece().name().toLowerCase());
            }
            playersArray.add(playerJson);
        }
        welcomeBody.add("players", playersArray);
        sendToClient(ctx, MessageType.WELCOME, welcomeBody);

        log.info("Client verbunden: {} in Raum {}", userId, code);
    }

    private void onMessage(WsContext ctx, String rawMessage) {
        log.info("SERVER: Received raw message: {}", rawMessage);
        String code = ctxToRoom.get(ctx);
        if (code == null || !rooms.containsKey(code)) return;

        GameRoom room = rooms.get(code);

        try {
            JsonObject msg = gson.fromJson(rawMessage, JsonObject.class);
            String typeStr = msg.has("messageType") ? msg.get("messageType").getAsString() : "";
            JsonObject body = msg.has("messageBody") ? msg.getAsJsonObject("messageBody") : new JsonObject();

            MessageType type = MessageType.fromWireName(typeStr);
            if (type == null) {
                log.error("Unknown messageType: {}", typeStr);
                sendError(ctx, "UNKNOWN_TYPE", "Unbekannter messageType: " + typeStr);
                return;
            }

            int clientId = room.getClientId(ctx);
            log.info("SERVER: Processing messageType={} from clientId={}", typeStr, clientId);

            switch (type) {
                //  Verbindungsaufbau
                case HELLO_SERVER -> handleHelloServer(ctx, body, room, clientId);
                
                //  Lobby
                case PLAYER_VALUES -> handlePlayerValues(ctx, body, room, clientId);
                case SET_STATUS -> handleSetStatus(ctx, body, room, clientId);

                // Chat
                case SEND_CHAT -> handleSendChat(ctx, body, room, clientId);

                // Spiellogik
                case ALIVE -> handleAlive(ctx, room, clientId);
                case DRAW_LANDSCAPE_TILE -> handleDrawLandscapeTile(ctx, body, room, clientId);
                case SET_TILE_PRICES -> handleSetTilePrices(ctx, body, room, clientId);
                case BUY_TILE -> handleBuyTile(ctx, body, room, clientId);
                case BUILD_TILE -> handleBuildTile(ctx, body, room, clientId);
                case BUILD_DONE -> handleBuildDone(ctx, body, room, clientId);
                case COLLECT_INCOME -> handleCollectIncome(ctx, room, clientId);
                case GAME_STARTED_ACK -> handleGameStartedAck(ctx, body, room, clientId);

                //  Manual AI Control
                case ADD_AI_REQUEST -> handleAddAiRequest(ctx, body, room, clientId);
                case REMOVE_AI_REQUEST -> handleRemoveAiRequest(ctx, body, room, clientId);

                default -> {
                    log.warn("MessageType noch nicht implementiert: {}", type.getWireName());
                    sendError(ctx, "NOT_IMPLEMENTED", "MessageType noch nicht implementiert: " + type.getWireName());
                }
            }
        } catch (Exception e) {
            log.error("Fehler beim Verarbeiten der Nachricht", e);
            sendError(ctx, "PARSE_ERROR", "Nachricht konnte nicht verarbeitet werden");
        }
    }


    private void onClose(WsContext ctx) {
        String code = ctxToRoom.remove(ctx);
        if (code != null && rooms.containsKey(code)) {
            GameRoom room = rooms.get(code);
            int clientId = room.getClientId(ctx);
            String userId = room.getUserId(ctx);
            boolean wasRegistered = room.getPlayer(clientId) != null;

            Player leavingPlayer = room.getPlayer(clientId);

            room.removeClient(ctx);

            if (wasRegistered && room.getGame().getStatus() == GameStatus.RUNNING) {
                try {
                    room.getGameLogic().handlePlayerLeave(leavingPlayer);
                    if (room.getGame().getRoundPhase() == RoundPhase.BUY) {
                        sendRevealTilePrices(room);
                        sendAvailableTiles(room);
                    }
                } catch (Exception e) {
                    log.error("Error during player cleanup logic: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            JsonObject cu = new JsonObject();
            cu.addProperty("clientID", clientId);
            cu.addProperty("isConnected", false);
            cu.addProperty("action", "Remove");
            room.broadcastAll(createMessage(MessageType.CONNECTION_UPDATE, cu));

            if (userId != null && wasRegistered) {
                JsonObject leftBody = new JsonObject();
                leftBody.addProperty("clientID", clientId);
                leftBody.addProperty("userId", userId);
                room.broadcastAll(createMessage(MessageType.PLAYER_LEFT, leftBody));

                log.info("Client {} disconnected from room {}", userId, code);
            } else {
                log.warn("Unregistered client {} disconnected from room {}", userId, code);
            }
        }
    }


    /**
     * HelloServer - Client sendet Gruppenidentifikation (Protokoll 0.1)
     * Body: { "group": "...", "isAI": false, "protocol": "Version 2.0" }
     */
    private void handleHelloServer(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        String protocol = body.has("protocol") ? body.get("protocol").getAsString() : "";
        String group = body.has("group") ? body.get("group").getAsString() : "";
        boolean isAI = body.has("isAI") && body.get("isAI").getAsBoolean();

        Integer requestedClientId = body.has("clientID") ? body.get("clientID").getAsInt() : null;

        // Protokollversion prüfen
        if (!protocol.equals("Version 1.0") && !protocol.equals("Version 2.0")) {
            sendError(ctx, "PROTOCOL_MISMATCH", "Nicht unterstützte Protokollversion: " + protocol);
            ctx.closeSession(1008, "Protokollversion nicht unterstützt");
            return;
        }

        if (requestedClientId != null) {
            log.debug(
                    "HelloServer von {} (isAI={}, protocol={}, requestedClientID={})",
                    group, isAI, protocol, requestedClientId
            );
        } else {
            log.debug(
                    "HelloServer von {} (isAI={}, protocol={})",
                    group, isAI, protocol
            );
        }
    }
    
    /**
     * PlayerValues - Spieler setzt Name, Geburtsdatum und Spielsteinfarbe (Protokoll 0.1)
     * Body: { "name": "...", "gamePiece": "blue", "birthDate": "2003-09-07" }
     */
    private void handlePlayerValues(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        String name = body.has("name") ? body.get("name").getAsString().trim() : "";
        String gamePieceStr = body.has("gamePiece") ? body.get("gamePiece").getAsString().toLowerCase() : "";
        String birthDateStr = body.has("birthDate") ? body.get("birthDate").getAsString() : "";

        if (name.isEmpty()) {
            sendError(ctx, "INVALID_NAME", "Name darf nicht leer sein");
            return;
        }
        for (Map.Entry<Integer, Player> entry : room.getPlayers().entrySet()) {
            if (entry.getKey() != clientId) {
                Player otherPlayer = entry.getValue();
                if (otherPlayer != null && otherPlayer.getName() != null) {
                    if (otherPlayer.getName().equalsIgnoreCase(name)) {
                        sendError(ctx, "NAME_TAKEN", "This name is already taken! Please choose another one.");
                        return;
                    }
                }
            }
        }
        GamePiece requestedPiece = null;
        if (!gamePieceStr.isEmpty()) {
            try {
                requestedPiece = GamePiece.valueOf(gamePieceStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendError(ctx, "INVALID_GAME_PIECE", "Ungültige Spielsteinfarbe: " + gamePieceStr);
                return;
            }
        }

        // Bestehenden Spieler laden
        Player existingPlayer = room.getPlayer(clientId);
        GamePiece currentPiece = existingPlayer != null ? existingPlayer.getGamePiece() : null;

        GamePiece finalPiece = currentPiece;

        // Farbe nur setzen/ändern, wenn explizit angefragt.
        // sonst kann es in Race-Conditions/Redundant-Messages zu falschen COLOR_TAKEN Fehlern kommen.
        if (requestedPiece != null && !requestedPiece.equals(currentPiece)) {
            GamePiece chosen = room.chooseColor(clientId, requestedPiece);
            if (chosen == null || !chosen.equals(requestedPiece)) {
                sendError(ctx, "COLOR_TAKEN", "Die gewählte Farbe ist bereits vergeben");
                return;
            }
            finalPiece = chosen;
        }
        
        // Geburtsdatum parsen
        LocalDate birthDate = null;
        if (!birthDateStr.isEmpty()) {
            try {
                birthDate = LocalDate.parse(birthDateStr);
            } catch (Exception e) {
                sendError(ctx, "INVALID_BIRTHDATE", "Ungültiges Geburtsdatum (Format: YYYY-MM-DD)");
                return;
            }
            if (birthDate.isAfter(LocalDate.now())) {
                sendError(ctx, "INVALID_BIRTHDATE", "Geburtsdatum darf nicht in der Zukunft liegen");
                return;
            }
        }
        
        boolean isNewPlayer = existingPlayer == null;

        // Spieler aktualisieren oder neu anlegen.
        // Für neue Spieler wird gamePiece nur gesetzt, wenn explizit gewählt.
        if (!isNewPlayer) {
            existingPlayer.setName(name);
            existingPlayer.setGamePiece(finalPiece);
            existingPlayer.setBirthDate(birthDate);
        } else {
            Player newPlayer = new Player(name, finalPiece, birthDate);
            room.addPlayer(clientId, newPlayer);

            // Track if this is an AI client
            boolean isAI = room.isAI(ctx);
            boolean isBotName = name != null && name.startsWith("Bot_");
            if (isAI || isBotName) {
                room.markClientAsAI(clientId);
                log.info("Client {} ({}) als AI markiert", clientId, name);
            }
        }

        log.debug(
                "PlayerValues: clientId={}, name={}, gamePiece={}, birthDate={}",
                clientId, name, finalPiece, birthDateStr
        );

        // Bei erstmaliger Registrierung: PlayerAdded.
        // Bei späteren Updates (z.B. Farbwechsel): ConnectionUpdate, damit Clients nicht "joined" loggen.
        JsonObject bodyOut = new JsonObject();
        bodyOut.addProperty("clientID", clientId);
        bodyOut.addProperty("name", name);
        if (finalPiece != null) {
            bodyOut.addProperty("gamePiece", finalPiece.name().toLowerCase());
        }
        bodyOut.addProperty("birthDate", birthDateStr);

        if (isNewPlayer) {
            room.broadcastAll(createMessage(MessageType.PLAYER_ADDED, bodyOut));
        } else {
            room.broadcastAll(createMessage(MessageType.CONNECTION_UPDATE, bodyOut));
        }
    }

    /**
     * Alive - Keep-alive Heartbeat (Protokoll: alle 5 Sekunden)
     */
    private void handleAlive(WsContext ctx, GameRoom room, int clientId) {
        // Client antwortet auf Server-Alive - einfach mit Alive zurück antworten
        JsonObject aliveBody = new JsonObject();
        sendToClient(ctx, MessageType.ALIVE, aliveBody);
    }

    /**
     * SetStatus - Spieler setzt Ready-Status
     */
    private void handleSetStatus(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        if (!body.has("ready")) {
            sendError(ctx, "INVALID_MESSAGE", "ready-Feld fehlt");
            return;
        }

        boolean ready = body.get("ready").getAsBoolean();
        room.setReady(clientId, ready);

        log.debug("SetStatus: clientId={} ready={}", clientId, ready);

        // PlayerStatus an alle
        JsonObject statusBody = new JsonObject();
        statusBody.addProperty("clientID", clientId);
        statusBody.addProperty("ready", ready);
        room.broadcastAll(createMessage(MessageType.PLAYER_STATUS, statusBody));

        // Prüfen ob alle bereit sind
        if (room.allPlayersReady() && room.getPlayers().size() >= 2 && room.tryMarkGameStarted()) {
            startGame(room);
        }
    }

    /**
     * SendChat - Chat-Nachricht (Protokoll 0.1)
     * Body: { "message": "...", "to": 42 } (privat) oder { "message": "...", "to": -1 } (öffentlich)
     */
    private void handleSendChat(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        String message = body.has("message") ? body.get("message").getAsString() : "";
        int toClientId = body.has("to") ? body.get("to").getAsInt() : -1;

        // ReceivedChat Format: { "message": "...", "from": clientID, "isPrivate": bool, "to": recipientID }
        JsonObject chatBody = new JsonObject();
        chatBody.addProperty("message", message);
        chatBody.addProperty("from", clientId);

        if (toClientId >= 0) {
            // Private Nachricht
            chatBody.addProperty("isPrivate", true);
            chatBody.addProperty("to", toClientId);

            WsContext targetCtx = room.getContextByClientId(toClientId);
            if (targetCtx != null) {
                sendToClient(targetCtx, MessageType.RECEIVED_CHAT, chatBody);
                sendToClient(ctx, MessageType.RECEIVED_CHAT, chatBody); // Echo an Sender
            } else {
                sendError(ctx, "RECIPIENT_NOT_FOUND", "Empfänger nicht gefunden");
            }
        } else {
            // Öffentliche Nachricht (to = -1)
            chatBody.addProperty("isPrivate", false);
            room.broadcastAll(createMessage(MessageType.RECEIVED_CHAT, chatBody));
        }
    }

    /**
     * DrawLandscapeTile - Spieler zieht ein Landschaftsplättchen (Phase 1)
     * Protokoll: { "drawNumber": 0 } (0-2)
     */
    private void handleDrawLandscapeTile(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        if (room.getGame().getStatus() != GameStatus.RUNNING) {
            sendError(ctx, "GAME_NOT_RUNNING", "Spiel ist beendet oder nicht gestartet.");
            return;
        }
        if (room.getGame().getRoundPhase() != backend.model.enums.RoundPhase.DRAW) {
            sendError(ctx, "INVALID_PHASE", "Nicht in der Draw-Phase.");
            return;
        }
        Player player = room.getPlayer(clientId);
        if (player == null) {
            sendError(ctx, "PLAYER_NOT_FOUND", "Spieler nicht gefunden");
            return;
        }
        if (!body.has("drawNumber")) {
            sendError(ctx, "INVALID_MESSAGE", "drawNumber fehlt");
            return;
        }
        int drawNumber = body.get("drawNumber").getAsInt();

        // Duplicate-Schutz: Prüfen, aber drawNumber erst nach erfolgreichem Zug speichern
        Set<Integer> sent = room.alreadySentDrawNumbers
                .computeIfAbsent(clientId, k -> ConcurrentHashMap.newKeySet());
        if (sent.contains(drawNumber)) {
            sendError(ctx, "DUPLICATE_DRAW", "Dieses drawNumber wurde schon gesendet.");
            return;
        }

        try {
            Tile drawn = room.getGameLogic().handleDrawRequest(player, drawNumber);
            sent.add(drawNumber);
            JsonObject tileDrawnBody = new JsonObject();
            tileDrawnBody.addProperty("clientID", clientId);
            tileDrawnBody.addProperty("drawNumber", drawNumber);
            tileDrawnBody.add("tile", serializeTile(drawn));
            room.broadcastAll(createMessage(MessageType.LANDSCAPE_TILE_DRAWN, tileDrawnBody));
        } catch (IllegalStateException | IllegalArgumentException e) {
            try {
                JsonObject rej = new JsonObject();
                rej.addProperty("clientID", clientId);
                rej.addProperty("reason", e.getMessage());
                rej.addProperty("code", "INVALID_MOVE");
                sendToClient(ctx, backend.network.protocol.MessageType.PRICE_SUBMIT_REJECTED, rej);
            } catch (Exception ex) {
                sendError(ctx, "INVALID_MOVE", e.getMessage());
            }
        }
    }

    /**
         * SetTilePrices - Spieler setzt Preise für gezogene Plättchen
         */
    private void handleSetTilePrices(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        Player player = room.getPlayer(clientId);

        if (room.getGame().getStatus() == backend.model.enums.GameStatus.FINISHED) {
            sendError(ctx, "GAME_FINISHED", "Das Spiel ist beendet.");
            return;
        }

        if (player == null) {
            sendError(ctx, "PLAYER_NOT_FOUND", "Spieler nicht gefunden");
            return;
        }

        if (room.getGame().getStatus() != GameStatus.RUNNING) { // blockiert Aktionen nach Spielende
            sendError(ctx, "GAME_NOT_RUNNING", "Spiel ist beendet oder nicht gestartet.");
            return;
        }

        if (!body.has("tilePrices")) {
            sendError(ctx, "INVALID_MESSAGE", "tilePrices-Array fehlt");
            return;
        }

        JsonArray tilePrices = body.getAsJsonArray("tilePrices");
        PriceDecision decision = new PriceDecision(player);

        for (int i = 0; i < tilePrices.size(); i++) {
            JsonObject t = tilePrices.get(i).getAsJsonObject();
            int tileId = t.get("tileID").getAsInt();
            boolean discard = t.get("discard").getAsBoolean();

            Tile tile = findTileById(player.getDrawnTiles(), tileId);
            if (tile == null) {
                log.warn("TileID {} nicht bei Spieler gefunden", tileId);
                continue;
            }

            if (discard) {
                decision.setDiscardedTile(tile);
            } else if (!t.get("price").isJsonNull()) {
                int price = t.get("price").getAsInt();
                decision.setPrice(tile, price);
            }
        }

        try {
            room.getGameLogic().handlePriceDecision(decision);
            
            // TilePricingCompleted an alle senden (Protokoll 0.1)
            JsonObject completedBody = new JsonObject();
            completedBody.addProperty("clientID", clientId);
            log.info("Broadcasting TilePricingCompleted for clientID={}", clientId);
            room.broadcastAll(createMessage(MessageType.TILE_PRICING_COMPLETED, completedBody));
            
        } catch (IllegalStateException | IllegalArgumentException e) {
            sendError(ctx, "INVALID_MOVE", e.getMessage());
        }
    }


    private void handleBuyTile(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        Player buyer = room.getPlayer(clientId);

        if (room.getGame().getStatus() != GameStatus.RUNNING || room.getGame().getRoundPhase() != RoundPhase.BUY) {
            sendError(ctx, "INVALID_REQUEST", "Cannot proceed.");
            return;
        }

        if (!body.has("tileID") || body.get("tileID").isJsonNull()) {
            try {
                room.getGameLogic().handleTilePurchase(buyer, null, null);

                Player nextPlayer = room.getGame().getCurrentPlayer();
                int nextPlayerId = (nextPlayer != null) ? getClientIdForPlayer(room, nextPlayer) : -1;

                log.info("Player {} passed. Next player: {}", clientId, nextPlayerId);
                JsonObject passBody = new JsonObject();
                passBody.addProperty("buyerID", clientId);
                passBody.add("sellerID", JsonNull.INSTANCE);
                passBody.add("tileID", JsonNull.INSTANCE);
                passBody.add("price", JsonNull.INSTANCE);

                if (nextPlayerId != -1) {
                    passBody.addProperty("nextPlayerID", nextPlayerId);
                }

                room.broadcastAll(createMessage(MessageType.TILE_PURCHASED, passBody));
                if (nextPlayer != null) {
                    sendCurrentPlayer(room, nextPlayer);
                }

            } catch (Exception e) {
                sendError(ctx, "ERROR", "Pass Error: " + e.getMessage());
            }
            return;
        }
        try {
            int tileId = body.get("tileID").getAsInt();
            Player seller = null;
            Tile tile = null;

            for (Player p : room.getGame().getPlayers()) {
                Tile t = findTileById(p.getDrawnTiles(), tileId);
                if (t != null) { seller = p; tile = t; break; }
            }

            if (seller == null || tile == null) {
                sendError(ctx, "TILE_NOT_FOUND", "Tile not found.");
                return;
            }

            Integer price = room.getGame().getMarket().getPrice(tile);
            room.getGameLogic().handleTilePurchase(buyer, seller, tile);

            Player nextPlayer = room.getGame().getCurrentPlayer();
            int nextPlayerId = (nextPlayer != null) ? getClientIdForPlayer(room, nextPlayer) : -1;

            log.info("Buy: Player {} bought tile {}. Next player: {}", clientId, tileId, nextPlayerId);

            JsonObject purchaseBody = new JsonObject();
            purchaseBody.addProperty("buyerID", clientId);
            purchaseBody.addProperty("sellerID", getClientIdForPlayer(room, seller));
            purchaseBody.addProperty("tileID", tileId);
            purchaseBody.addProperty("price", price);

            if (nextPlayerId != -1) {
                purchaseBody.addProperty("nextPlayerID", nextPlayerId);
            }

            room.broadcastAll(createMessage(MessageType.TILE_PURCHASED, purchaseBody));
            if (nextPlayer != null) {
                sendCurrentPlayer(room, nextPlayer);
            }

        } catch (Exception e) {
            sendError(ctx, "INVALID_MOVE", e.getMessage());
        }
    }


    private void handleBuildTile(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        if (room.getGame().getStatus() != GameStatus.RUNNING || room.getGame().getRoundPhase() != RoundPhase.BUILD) {
            sendError(ctx, "INVALID_PHASE", "Wrong Phase for this request.");
            return;
        }

        Player player = room.getPlayer(clientId);
        if (player == null) return;

        if (!body.has("tileID")) { sendError(ctx, "ERROR", "tileID is missing"); return; }

        int tileId = body.get("tileID").getAsInt();
        log.debug("BUILD_REQ: clientId={} tileId={} x={} y={} rot={}",
                clientId,
                tileId,
                body.has("x_position") ? body.get("x_position").getAsInt() : null,
                body.has("y_position") ? body.get("y_position").getAsInt() : null,
                body.has("rotation") ? body.get("rotation").getAsInt() : null);
        Tile tile = findTileById(player.getDrawnTiles(), tileId);

        if (tile == null) {
            log.warn("BUILD_REQ_REJECTED: clientId={} tileId={} reason=NOT_IN_HAND", clientId, tileId);
            sendError(ctx, "ERROR", "This tile doesn't exist in players hand.");
            return;
        }

        try {
            TilePlacement placement = new TilePlacement(player, tile,
                    body.get("x_position").getAsInt(),
                    body.get("y_position").getAsInt(),
                    body.get("rotation").getAsInt());

            TilePlacementResult result = room.getGameLogic().handleTilePlacement(placement);

            if (result == TilePlacementResult.PLACED) {
                log.debug("BUILD_RES: clientId={} tileId={} result=PLACED", clientId, tileId);

                JsonObject out = new JsonObject();
                out.addProperty("clientID", clientId);
                out.addProperty("tileID", tileId);
                out.addProperty("x_position", body.get("x_position").getAsInt());
                out.addProperty("y_position", body.get("y_position").getAsInt());
                out.addProperty("rotation", body.get("rotation").getAsInt());
                room.broadcastAll(createMessage(MessageType.TILE_BUILT, out));

                if (player.getDrawnTiles() != null) {
                    player.getDrawnTiles().removeIf(t -> t != null && t.getTileId() == tileId);
                }

                if (player.getDrawnTiles().isEmpty()) {
                    log.info("PLayer {} build end.", player.getName());
                    int currentRound = room.getGame().getCurrentRound();
                    if (!room.hasDoneBuildThisRound(clientId, currentRound)) {
                        room.markBuildDone(clientId, currentRound);
                    }
                } else {
                    log.info("Player {} placed a tile but still has tile.", player.getName());
                }

                if (room.allPlayersBuildDone() && room.tryAdvanceBuildPhaseOnce()) {
                    room.getGameLogic().nextPhase();
                }
                attemptPendingBuildRetries(room, clientId);
            }
            else if (result == TilePlacementResult.RETURNED) {
                log.debug("BUILD_RES: clientId={} tileId={} result=RETURNED", clientId, tileId);

                JsonObject out = new JsonObject();
                out.addProperty("clientID", clientId);
                out.addProperty("tileID", tileId);
                room.broadcastAll(createMessage(MessageType.TILE_RETURNED, out));

                if (player.getDrawnTiles() != null) {
                    player.getDrawnTiles().removeIf(t -> t != null && t.getTileId() == tileId);
                }

                if (player.getDrawnTiles().isEmpty()) {
                    room.markBuildDone(clientId, room.getGame().getCurrentRound());
                }
                if (room.allPlayersBuildDone() && room.tryAdvanceBuildPhaseOnce()) {
                    room.getGameLogic().nextPhase();
                }
            }
            else {
                log.warn("BUILD_RES: clientId={} tileId={} result=ILLEGAL", clientId, tileId);
                room.storePendingBuildRetry(clientId, new PendingBuildRetry(
                        tileId,
                        body.get("x_position").getAsInt(),
                        body.get("y_position").getAsInt(),
                        body.get("rotation").getAsInt(),
                        room.getGame().getCurrentRound()
                ));
                List<int[]> moves = player.getClanTerritory().getAllLegalPlacements(tile);
                sendErrorWithLegalMoves(ctx, "ILLEGAL", "Invalid placement.", moves);
            }
        } catch (Exception e) {
            log.error("BUILD_RES: clientId={} tileId={} result=ERROR msg={}", clientId, tileId, e.getMessage());
            sendError(ctx, "ERROR", e.getMessage());
        }
    }

    private void attemptPendingBuildRetries(GameRoom room, int clientId) {
        if (room.getGame().getRoundPhase() != RoundPhase.BUILD) return;
        Map<Integer, PendingBuildRetry> pending = room.getPendingBuildRetries(clientId);
        if (pending == null || pending.isEmpty()) return;
        Player player = room.getPlayer(clientId);
        if (player == null) return;

        int currentRound = room.getGame().getCurrentRound();
        java.util.List<PendingBuildRetry> retries = new java.util.ArrayList<>(pending.values());
        for (PendingBuildRetry retry : retries) {
            if (retry == null) continue;
            if (retry.round != currentRound || retry.attempts >= 1) {
                room.removePendingBuildRetry(clientId, retry.tileId);
                sendTileReturned(room, clientId, retry.tileId);
                continue;
            }

            Tile tile = findTileById(player.getDrawnTiles(), retry.tileId);
            if (tile == null) {
                room.removePendingBuildRetry(clientId, retry.tileId);
                sendTileReturned(room, clientId, retry.tileId);
                continue;
            }

            retry.attempts += 1;
            try {
                TilePlacement placement = new TilePlacement(player, tile, retry.x, retry.y, retry.rotation);
                TilePlacementResult result = room.getGameLogic().handleTilePlacement(placement);

                if (result == TilePlacementResult.PLACED) {
                    log.debug("BUILD_RETRY: clientId={} tileId={} result=PLACED", clientId, retry.tileId);

                    JsonObject out = new JsonObject();
                    out.addProperty("clientID", clientId);
                    out.addProperty("tileID", retry.tileId);
                    out.addProperty("x_position", retry.x);
                    out.addProperty("y_position", retry.y);
                    out.addProperty("rotation", retry.rotation);
                    room.broadcastAll(createMessage(MessageType.TILE_BUILT, out));

                    if (player.getDrawnTiles() != null) {
                        player.getDrawnTiles().removeIf(t -> t != null && t.getTileId() == retry.tileId);
                    }

                    if (player.getDrawnTiles().isEmpty()) {
                        int round = room.getGame().getCurrentRound();
                        if (!room.hasDoneBuildThisRound(clientId, round)) {
                            room.markBuildDone(clientId, round);
                        }
                    }

                    room.removePendingBuildRetry(clientId, retry.tileId);

                    if (room.allPlayersBuildDone() && room.tryAdvanceBuildPhaseOnce()) {
                        room.getGameLogic().nextPhase();
                        return;
                    }
                } else {
                    log.warn("BUILD_RETRY: clientId={} tileId={} result=ILLEGAL", clientId, retry.tileId);
                    room.removePendingBuildRetry(clientId, retry.tileId);
                    sendTileReturned(room, clientId, retry.tileId);
                }
            } catch (Exception e) {
                log.error("BUILD_RETRY: clientId={} tileId={} result=ERROR msg={}", clientId, retry.tileId, e.getMessage());
                room.removePendingBuildRetry(clientId, retry.tileId);
                sendTileReturned(room, clientId, retry.tileId);
            }
        }
    }

    private void sendTileReturned(GameRoom room, int clientId, int tileId) {
        JsonObject out = new JsonObject();
        out.addProperty("clientID", clientId);
        out.addProperty("tileID", tileId);
        room.broadcastAll(createMessage(MessageType.TILE_RETURNED, out));
    }

    private static final class PendingBuildRetry {
        private final int tileId;
        private final int x;
        private final int y;
        private final int rotation;
        private final int round;
        private int attempts = 0;

        private PendingBuildRetry(int tileId, int x, int y, int rotation, int round) {
            this.tileId = tileId;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.round = round;
        }
    }

    /**
     * CollectIncome - Spieler sammelt Income in der INCOME-Phase ein.
     * Protokoll 0.1 Body: {} (keine Felder nötig)
     */
    private void handleCollectIncome(WsContext ctx, GameRoom room, int clientId) {
        if (room.getGame().getStatus() != GameStatus.RUNNING) {
            sendError(ctx, "GAME_NOT_RUNNING", "Spiel ist beendet oder nicht gestartet.");
            return;
        }
        if (room.getGame().getRoundPhase() != backend.model.enums.RoundPhase.INCOME) {
            sendError(ctx, "INVALID_PHASE", "CollectIncome ist nur in der INCOME-Phase erlaubt.");
            return;
        }

        Player player = room.getPlayer(clientId);
        if (player == null) {
            sendError(ctx, "PLAYER_NOT_FOUND", "Spieler nicht gefunden");
            return;
        }

        int currentRound = room.getGame().getCurrentRound();
        if (room.hasCollectedIncomeThisRound(clientId, currentRound)) {
            sendCoinUpdate(room, player);
            return;
        }

        room.cancelIncomeTimeout(clientId);

        int income = 5
                + backend.logic.IncomeLogic.connectedBarrels(room.getGame(), player)
                + backend.logic.IncomeLogic.catchUpCoins(room.getGame(), player);

        player.addCoins(income);
        sendCoinUpdate(room, player);

        JsonObject incomeBody = new JsonObject();
        incomeBody.addProperty("clientID", clientId);
        incomeBody.addProperty("income", income);
        incomeBody.addProperty("totalCoins", player.getCoins());
        room.broadcastAll(createMessage(MessageType.INCOME_RECEIVED, incomeBody));

        room.markIncomeCollected(clientId, currentRound);

        if (room.allPlayersCollectedIncome()) {
            room.getGameLogic().nextPhase();
        }
    }

    private void scheduleIncomeTimeouts(GameRoom room) {
        if (room == null) return;
        int currentRound = room.getGame().getCurrentRound();

        for (Integer clientId : room.getPlayers().keySet()) {
            if (clientId == null) continue;

            // One job per player per INCOME phase.
            room.cancelIncomeTimeout(clientId);

            ScheduledFuture<?> f = incomeScheduler.schedule(() -> {
                try {
                    // Abort if phase already changed.
                    if (room.getGame().getRoundPhase() != backend.model.enums.RoundPhase.INCOME) return;

                    // Don't auto-collect if player already collected.
                    if (room.hasCollectedIncomeThisRound(clientId, currentRound)) return;

                    Player player = room.getPlayer(clientId);
                    if (player == null) return;

                    int income = 5
                            + backend.logic.IncomeLogic.connectedBarrels(room.getGame(), player)
                            + backend.logic.IncomeLogic.catchUpCoins(room.getGame(), player);

                    player.addCoins(income);
                    sendCoinUpdate(room, player);

                    JsonObject incomeBody = new JsonObject();
                    incomeBody.addProperty("clientID", clientId);
                    incomeBody.addProperty("income", income);
                    incomeBody.addProperty("totalCoins", player.getCoins());
                    room.broadcastAll(createMessage(MessageType.INCOME_RECEIVED, incomeBody));

                    room.markIncomeCollected(clientId, currentRound);

                    if (room.allPlayersCollectedIncome()) {
                        room.getGameLogic().nextPhase();
                    }
                } catch (Exception ignored) {
                }
            }, 5, TimeUnit.SECONDS);

            room.setIncomeTimeout(clientId, f);
        }
    }


    private void startGame(GameRoom room) {
        log.info("Spiel wird gestartet!");
        room.clearReadyStatus();

        List<Player> players = new ArrayList<>(room.getPlayers().values());

        room.getGameLogic().addCurrentPlayerListener(player -> sendCurrentPlayer(room, player));
        room.getGameLogic().addStartPlayerListener(player -> sendStartPlayer(room, player));

        // Coins/VictoryPoints an Clients pushen
        room.getGameLogic().addCoinListener(p -> sendCoinUpdate(room, p));

        room.getGameLogic().addIncomeListener((player, gained) -> {
            JsonObject incomeBody = new JsonObject();
            incomeBody.addProperty("clientID", getClientIdForPlayer(room, player));
            incomeBody.addProperty("income", gained);              // <-- wichtig: echtes Einkommen (nicht hardcoded 5)
            incomeBody.addProperty("totalCoins", player.getCoins());
            room.broadcastAll(createMessage(MessageType.INCOME_RECEIVED, incomeBody));
        });

        room.getGameLogic().setGameOverSender(winners -> sendGameOver(room, winners));
        room.getGameLogic().setScoringUpdateSender(scores -> sendScoringUpdate(room));

        room.getGameLogic().startGame(players);

        JsonObject startBody = new JsonObject();
        
        JsonArray scoringIds = new JsonArray();
        var scoringTiles = room.getGame().getScoringTiles();
        if (scoringTiles.tileA() != null) scoringIds.add(scoringTiles.tileA().getScoringTileId());
        if (scoringTiles.tileB() != null) scoringIds.add(scoringTiles.tileB().getScoringTileId());
        if (scoringTiles.tileC() != null) scoringIds.add(scoringTiles.tileC().getScoringTileId());
        if (scoringTiles.tileD() != null) scoringIds.add(scoringTiles.tileD().getScoringTileId());
        startBody.add("scoringIDs", scoringIds);
        if (log.isDebugEnabled()) {
            log.debug("Sending scoring tiles to clients: A={} (id={}), B={} (id={}), C={} (id={}), D={} (id={})",
                    scoringTiles.tileA(),
                    scoringTiles.tileA() == null ? "null" : scoringTiles.tileA().getScoringTileId(),
                    scoringTiles.tileB(),
                    scoringTiles.tileB() == null ? "null" : scoringTiles.tileB().getScoringTileId(),
                    scoringTiles.tileC(),
                    scoringTiles.tileC() == null ? "null" : scoringTiles.tileC().getScoringTileId(),
                    scoringTiles.tileD(),
                    scoringTiles.tileD() == null ? "null" : scoringTiles.tileD().getScoringTileId());
        }
        
        startBody.addProperty("boardType", players.size() <= 4 ? "2-4" : "5");

        JsonArray castles = new JsonArray();
        for (var entry : room.getPlayers().entrySet()) {
            JsonObject castle = new JsonObject();
            castle.addProperty("clientID", entry.getKey());

            Player player = entry.getValue();
            if (player != null && player.getName() != null) {
                castle.addProperty("name", player.getName());
            }

            PlacedTile placedCastle = player.getClanTerritory().getTileAt(0, 0);
            if (placedCastle != null && placedCastle.tile() != null) {
                Tile castleTile = placedCastle.tile();
                castle.addProperty("tileID", castleTile.getTileId());
                castle.add("tile", serializeTile(castleTile));
                castle.addProperty("x_position", 0);
                castle.addProperty("y_position", 0);
                castle.addProperty("rotation", placedCastle.rotationDegrees());
            } else {
                int startTileId = getStartTileIdForGamePiece(player.getGamePiece());
                castle.addProperty("tileID", startTileId);
                castle.addProperty("x_position", 0);
                castle.addProperty("y_position", 0);
                castle.addProperty("rotation", 0);
            }
            castles.add(castle);
        }
        startBody.add("castles", castles);

        room.broadcastAll(createMessage(MessageType.GAME_STARTED, startBody));

        room.initGameStartAcks(room.getPlayers().keySet());

        aliveScheduler.schedule(() -> {
            try {
                registerPhaseListenerIfNot(room);
            } catch (Exception ignored) {}
        }, 2500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void sendCurrentPlayer(GameRoom room, Player player) {
        if (room == null || player == null) return;
        int clientId = getClientIdForPlayer(room, player);
        if (clientId < 0) return;

        // Protokoll 0.1: { "clientID": 7 }
        JsonObject body = new JsonObject();
        body.addProperty("clientID", clientId);
        log.debug("Send CurrentPlayer {} (Phase : {} )", clientId, room.getGame().getRoundPhase());
        room.broadcastAll(createMessage(MessageType.CURRENT_PLAYER, body));
    }

    private void sendStartPlayer(GameRoom room, Player player) {
        if (room == null || player == null) return;
        int clientId = getClientIdForPlayer(room, player);
        if (clientId < 0) return;

        // Protokoll 0.1: { "clientID": 42 }
        JsonObject body = new JsonObject();
        body.addProperty("clientID", clientId);
        room.broadcastAll(createMessage(MessageType.START_PLAYER_UPDATE, body));
    }

    /**
     * Register the phase listener for a room if not already registered.
     * This should be called once all clients ack GameStarted, or after a timeout.
     */
    private void registerPhaseListenerIfNot(GameRoom room) {
        if (room == null) return;
        if (!room.markPhaseListenerRegistered()) return;

        room.getGameLogic().addPhaseListener(phase -> {
            handlePhaseChange(room, phase);
        });

        try {
            handlePhaseChange(room, room.getGame().getRoundPhase());
        } catch (Exception ignored) {}
    }

    private void handleGameStartedAck(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        try {
            if (room == null) return;
            boolean allAcked = room.markGameStartAck(clientId);
            log.debug("Received GameStartedAck from client {} allAcked= {}", clientId, allAcked);
            if (allAcked) {
                registerPhaseListenerIfNot(room);
            }
        } catch (Exception e) {
            log.warn("Error handling GameStartedAck: {}", e.getMessage());
        }
    }

    /**
     * RemoveAiRequest - Player requests to remove a specific AI from the room
     * Body: { "clientId": 42 } (clientId of AI to remove)
     * Response: RemoveAiResponse with success/error
     */
    private void handleRemoveAiRequest(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        log.debug(
                "REMOVE AI: aiIds={} players={}",
                room.getAIClientIds(),
                room.getPlayers().entrySet().stream()
                        .map(e -> e.getKey() + ":" + (e.getValue() == null ? "null" : e.getValue().getName()))
                        .toList()
        );

        Integer targetClientId = null;

        if (body.has("clientId")) {
            targetClientId = body.get("clientId").getAsInt();
        }

        java.util.function.IntPredicate isBotClient = id -> {
            Player p = room.getPlayer(id);
            String n = (p != null ? p.getName() : null);
            return n != null && n.startsWith("Bot_");
        };

        java.util.Set<Integer> aiIds = room.getAIClientIds();

        if (targetClientId == null || targetClientId < 0 || (!aiIds.contains(targetClientId) && !isBotClient.test(targetClientId))) {
            if (!aiIds.isEmpty()) {
                targetClientId = aiIds.iterator().next();
            } else {
                targetClientId = null;
                for (var entry : room.getPlayers().entrySet()) {
                    Player p = entry.getValue();
                    if (p != null && p.getName() != null && p.getName().startsWith("Bot_")) {
                        targetClientId = entry.getKey();
                        break;
                    }
                }
            }
        }

        if (targetClientId == null) {
            sendRemoveAiResponse(ctx, false, -1, "NO_AI_IN_ROOM",
                    "No AI players in this room");
            return;
        }

        if (room.getGame().getStatus() == GameStatus.RUNNING) {
            sendRemoveAiResponse(ctx, false, targetClientId, "GAME_ALREADY_STARTED",
                    "Cannot remove AI after game has started");
            return;
        }

        WsContext aiCtx = room.getContextByClientId(targetClientId);
        if (aiCtx == null) {
            sendRemoveAiResponse(ctx, false, targetClientId, "AI_NOT_FOUND",
                    "AI context not found");
            return;
        }

        Player aiPlayer = room.getPlayer(targetClientId);
        String aiName = aiPlayer != null ? aiPlayer.getName() : "Unknown";

        log.debug("Removing AI {} (clientId= {}", aiName, targetClientId);

        JsonObject leftBody = new JsonObject();
        leftBody.addProperty("clientID", targetClientId);
        room.broadcastAll(createMessage(MessageType.PLAYER_LEFT, leftBody));

        room.removeClient(aiCtx);
        room.removeAIClient(targetClientId);

        try {
            aiCtx.closeSession(1000, "Removed by player request");
        } catch (Exception e) {
            log.warn("Error closing AI connection: {}", e.getMessage());
        }

        room.resetManualAIControl();

        if (room.getAIClientIds().isEmpty()) {
            String roomCode = null;
            for (var entry : rooms.entrySet()) {
                if (entry.getValue() == room) {
                    roomCode = entry.getKey();
                    break;
                }
            }
            if (roomCode != null) {
                roomsWithAI.remove(roomCode);
            }
        }

        sendRemoveAiResponse(ctx, true, targetClientId, null, null);
    }

    private void sendRemoveAiResponse(WsContext ctx, boolean success, int aiClientId,
                                       String errorCode, String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("success", success);
        response.addProperty("clientId", aiClientId);

        if (!success) {
            response.addProperty("errorCode", errorCode);
            response.addProperty("errorMessage", errorMessage);
        }

        sendToClient(ctx, MessageType.REMOVE_AI_RESPONSE, response);
    }

    /**
     * AddAiRequest - Player requests to add an AI to the room
     * Body: {} (no parameters needed)
     * Response: AddAiResponse with success/error
     */
    private void handleAddAiRequest(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        // Validation: game not started (check if any player has selected a game piece)
        boolean gameStarted = room.getPlayers().values().stream()
            .anyMatch(p -> p.getGamePiece() != null);

        // Validation: game not running
        if (room.getGame().getStatus() == GameStatus.RUNNING) {
            sendAddAiResponse(ctx, false, -1, null, "GAME_ALREADY_STARTED",
                    "Cannot add AI after game has started");
            return;
        }

        // Validation: max players (5)
        if (room.getTotalPlayerCount() >= 5) {
            sendAddAiResponse(ctx, false, -1, null, "MAX_PLAYERS_REACHED",
                "Room is full (5 players maximum)");
            return;
        }

        // Find room code for this room
        String roomCode = null;
        for (var entry : rooms.entrySet()) {
            if (entry.getValue() == room) {
                roomCode = entry.getKey();
                break;
            }
        }

        if (roomCode == null) {
            sendAddAiResponse(ctx, false, -1, null, "INTERNAL_ERROR",
                "Could not find room code");
            return;
        }

        // Enable manual AI control flag
        room.enableManualAIControl();

        // Mark room as having AI to prevent auto-watcher interference
        final String finalRoomCode = roomCode;
        roomsWithAI.add(roomCode);

        // Spawn AI in background thread (reuse existing pattern from startAIWatcher)
        new Thread(() -> {
            try {
                String aiName = "Bot_" + System.currentTimeMillis() % 1000;
                String algo = body != null && body.has("algorithm") && !body.get("algorithm").isJsonNull()
                        ? body.get("algorithm").getAsString()
                        : "RANDOM";

                AiClient ai = AiClientFactory.create("localhost", PORT, aiName, "2000-01-01", algo);
                ai.connectToRoom(finalRoomCode);

                // Wait a moment for AI to connect and get clientId
                Thread.sleep(500);

                // Success response will be sent when AI sends PlayerValues
                // (existing flow via handlePlayerValues -> PLAYER_ADDED broadcast)
                log.info("AI {} added to room {}", aiName, finalRoomCode);

            } catch (Exception e) {
                log.warn("Error adding AI {}", e.getMessage());
                roomsWithAI.remove(finalRoomCode);
                room.resetManualAIControl();
                // Send error response
                sendAddAiResponse(ctx, false, -1, null, "CONNECTION_ERROR",
                    "Failed to connect AI: " + e.getMessage());
            }
        }, "AI-Manual-Add-" + roomCode).start();

        // Send immediate acknowledgment (actual clientId will come via PLAYER_ADDED)
        sendAddAiResponse(ctx, true, -1, "AI being added...", null, null);
    }

    private void sendAddAiResponse(WsContext ctx, boolean success, int aiClientId,
                                    String aiName, String errorCode, String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("success", success);

        if (success) {
            if (aiClientId > 0) {
                response.addProperty("clientId", aiClientId);
            }
            if (aiName != null) {
                response.addProperty("aiName", aiName);
            }
        } else {
            response.addProperty("errorCode", errorCode);
            response.addProperty("errorMessage", errorMessage);
        }

        sendToClient(ctx, MessageType.ADD_AI_RESPONSE, response);
    }

    /**
     * Konvertiert RoundPhase zu Protokoll 0.1 numerischem Wert:
     * 0 => Einkommen, 1 => Plättchen ziehen, 2 => Preise festlegen,
     * 3 => Preise offenlegen, 4 => Plättchen kaufen, 5 => Bauen,
     * 6 => Rundenende und Wertung, 7 => Spielende und Schlussbewertung
     */
    private int phaseToProtocolNumber(backend.model.enums.RoundPhase phase) {
        return switch (phase) {
            case INCOME -> 0;
            case DRAW -> 1;
            case DISCARD, PRICE -> 2;
            case REVEAL -> 3;
            case BUY -> 4;
            case BUILD -> 5;
            case SCORING, ROUND_END -> 6;
            case NONE -> 7;
        };
    }
    
    /**
     * Wird aufgerufen wenn sich die Spielphase ändert
     */
    private void handlePhaseChange(GameRoom room, backend.model.enums.RoundPhase phase) {
        log.info("Phase geändert zu: {}", phase);
        
        // RoundPhaseUpdate an alle senden (Protokoll 0.1: round beginnt bei 0, phase ist numerisch)
        JsonObject phaseBody = new JsonObject();
        phaseBody.addProperty("round", room.getGame().getCurrentRound() - 1);  // Protokoll: 0-basiert
        phaseBody.addProperty("phase", phaseToProtocolNumber(phase));
        room.broadcastAll(createMessage(MessageType.ROUND_PHASE_UPDATE, phaseBody));
        
        // Phase-spezifische Aktionen
        switch (phase) {

            case INCOME -> { //IncomeReceived wird durch GameLogic per IncomeListener gesendet
                // New income phase: players must explicitly collect.
                room.resetIncomeCollectionForNewIncomePhase(room.getGame().getCurrentRound());

                // Auto-collect after 5 seconds for players who didn't click.
                scheduleIncomeTimeouts(room);
            }

            case DRAW -> {
                room.alreadySentDrawNumbers.clear();
                for (var entry : room.getPlayers().entrySet()) {
                    Player p = entry.getValue();
                    if (p == null) continue;
                    if (p.getDrawnTiles() != null) {
                        int oldSize = p.getDrawnTiles().size();
                        if (oldSize > 0) {
                            log.warn("Player {} still has {} tiles before clearing!",
                                    p.getName(), oldSize);
                        }
                        p.getDrawnTiles().clear();
                    }
                }
            }
            case REVEAL -> {
                sendRevealTilePrices(room);
            }
            case BUY -> {
                // Kaufphase - Preise enthüllen und verfügbare Tiles senden
                log.debug("BUY-PHASE: Send RevealTilePrices and AvailableTiles");
                sendRevealTilePrices(room);
                sendAvailableTiles(room);
            }
            case BUILD -> {
                // Build tracking reset
                room.resetBuildDoneForNewBuildPhase(room.getGame().getCurrentRound());

                // Spieler ohne Tiles sofort als "fertig" markieren
                int currentRound = room.getGame().getCurrentRound();
                for (var entry : room.getPlayers().entrySet()) {
                    int clientId = entry.getKey();
                    Player p = entry.getValue();
                    if (p != null && (p.getDrawnTiles() == null || p.getDrawnTiles().isEmpty())) {
                        room.markBuildDone(clientId, currentRound);
                    }
                }

                // Bauphase - verfügbare Tiles zum Bauen senden
                sendAvailableTilesForBuilding(room);

                // falls jetzt alle fertig sind, direkt weiter
                if (room.allPlayersBuildDone() && room.tryAdvanceBuildPhaseOnce()) {
                    room.getGameLogic().nextPhase();
                }
            }
            case SCORING, ROUND_END -> {
                aliveScheduler.schedule(() -> {
                    try {
                        // only advance if still in scoring
                        if (room.getGame().getRoundPhase() == backend.model.enums.RoundPhase.SCORING
                                || room.getGame().getRoundPhase() == backend.model.enums.RoundPhase.ROUND_END) {
                            room.getGameLogic().nextPhase();
                        }
                    } catch (Exception ignored) {}
                }, 5000, TimeUnit.MILLISECONDS);
                // No special server push needed here beyond the standard updates.
            }
            case NONE -> {
            }
        }
    }
    
    private void sendScoringUpdate(GameRoom room) {
        if (room == null) return;

        JsonArray scores = new JsonArray();
        for (var entry : room.getPlayers().entrySet()) {
            int clientId = entry.getKey();
            Player player = entry.getValue();

            JsonObject o = new JsonObject();
            o.addProperty("clientID", clientId);
            o.addProperty("victoryPoints", player.getVictoryPoints());
            scores.add(o);
        }

        JsonObject body = new JsonObject();
        body.add("scores", scores);

        String payload = body.toString();
        long now = System.currentTimeMillis();

        String last = room.lastScoringUpdatePayload;
        long lastAt = room.lastScoringUpdateAtMs.get();

        // Wenn exakt gleich und innerhalb 1500ms nochmal: nicht senden
        if (payload.equals(last) && (now - lastAt) < 1500) {
            log.warn("Skipping duplicate SCORING_UPDATE (same payload) at {}", now);
            return;
        }

        room.lastScoringUpdatePayload = payload;
        room.lastScoringUpdateAtMs.set(now);

        log.debug("Send SCORING_UPDATE <<< time= {} payload= {}", now, payload);
        room.broadcastAll(createMessage(MessageType.SCORING_UPDATE, body));
    }

    private void sendGameOver(GameRoom room, java.util.List<Integer> winnersFromLogic) {
        int best = Integer.MIN_VALUE;
        java.util.List<Integer> winnerClientIds = new java.util.ArrayList<>();

        for (var entry : room.getPlayers().entrySet()) {
            Integer clientId = entry.getKey();
            Player p = entry.getValue();
            if (clientId == null || p == null) continue;

            int vp = p.getVictoryPoints();
            if (vp > best) {
                best = vp;
                winnerClientIds.clear();
                winnerClientIds.add(clientId);
            } else if (vp == best) {
                winnerClientIds.add(clientId);
            }
        }

        JsonArray winners = new JsonArray();
        JsonArray winnerNames = new JsonArray();

        for (Integer cid : winnerClientIds) {
            winners.add(cid);
            Player p = room.getPlayers().get(cid);
            String name = (p != null && p.getName() != null && !p.getName().isBlank())
                    ? p.getName()
                    : "Unbekannt (" + cid + ")";
            winnerNames.add(name);
        }

        JsonObject body = new JsonObject();
        body.add("winners", winners);
        body.add("winnerNames", winnerNames);
        body.addProperty("maxVictoryPoints", best);

        JsonArray placements = new JsonArray();
        List<Player> allPlayers = new ArrayList<>(room.getPlayers().values());

        allPlayers.sort((p1, p2) -> Integer.compare(p2.getVictoryPoints(), p1.getVictoryPoints()));

        for (Player p : allPlayers) {
            JsonObject place = new JsonObject();
            place.addProperty("name", (p.getName() != null ? p.getName() : "Unknown"));
            place.addProperty("points", p.getVictoryPoints());
            placements.add(place);
        }
        body.add("placements", placements);

        room.broadcastAll(createMessage(MessageType.GAME_OVER, body));

        log.info(
                "GAME_OVER winners={} names={} vp={} (logic winners was {})",
                winners, winnerNames, best, winnersFromLogic
        );
    }

    /**
     * Sendet RevealTilePrices - enthüllt alle Preise zu Beginn der BUY-Phase (Protokoll 1.0)
     * Format: { "tilePrices": [{ "clientID": 42, "prices": [{ "tileID": 25, "price": null, "discard": true }, ...] }, ...] }
     */
    private void sendRevealTilePrices(GameRoom room) {
        JsonObject revealBody = new JsonObject();
        JsonArray playerPrices = new JsonArray();

        Map<Player, PriceDecision> decisions = room.getGameLogic().getPriceDecisionsSnapshot();

        for (var entry : room.getPlayers().entrySet()) {
            int clientId = entry.getKey();
            Player player = entry.getValue();
            PriceDecision decision = decisions.get(player);
            if (decision == null) {
                continue;
            }

            JsonObject playerData = new JsonObject();
            playerData.addProperty("clientID", clientId);

            JsonArray prices = new JsonArray();

            Tile discarded = decision.getDiscardedTile();
            if (discarded != null) {
                JsonObject tilePrice = new JsonObject();
                tilePrice.addProperty("tileID", discarded.getTileId());
                tilePrice.add("price", JsonNull.INSTANCE);
                tilePrice.addProperty("discard", true);
                prices.add(tilePrice);
            }

            for (Map.Entry<Tile, Integer> priceEntry : decision.getPrices().entrySet()) {
                Tile tile = priceEntry.getKey();
                if (tile == null) {
                    continue;
                }
                JsonObject tilePrice = new JsonObject();
                tilePrice.addProperty("tileID", tile.getTileId());

                Integer price = priceEntry.getValue();
                if (price != null) {
                    tilePrice.addProperty("price", price);
                } else {
                    tilePrice.add("price", JsonNull.INSTANCE);
                }
                tilePrice.addProperty("discard", false);
                prices.add(tilePrice);
            }

            playerData.add("prices", prices);
            playerPrices.add(playerData);
        }

        revealBody.add("tilePrices", playerPrices);

        room.broadcastAll(createMessage(MessageType.REVEAL_TILE_PRICES, revealBody));
    }

    private void sendAvailableTiles(GameRoom room) {
        JsonArray tiles = new JsonArray();

        for (var entry : room.getPlayers().entrySet()) {
            int sellerClientId = entry.getKey();
            Player seller = entry.getValue();

            for (Tile tile : seller.getDrawnTiles()) {
                Integer price = room.getGame().getMarket().getPrice(tile);
                if (price == null) continue;

                JsonObject obj = new JsonObject();
                obj.addProperty("tileID", tile.getTileId());
                obj.addProperty("sellerClientID", sellerClientId);
                obj.addProperty("price", price);
                obj.add("tile", serializeTile(tile));

                tiles.add(obj);
            }
        }

        JsonObject body = new JsonObject();
        body.add("tiles", tiles);

        room.broadcastAll(createMessage(MessageType.AVAILABLE_TILES, body));
    }


    private void sendAvailableTilesForBuilding(GameRoom room) {
        for (var entry : room.getPlayers().entrySet()) {
            int clientId = entry.getKey();
            Player player = entry.getValue();

            JsonArray tiles = new JsonArray();
            for (Tile tile : player.getDrawnTiles()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("tileID", tile.getTileId());
                obj.add("tile", serializeTile(tile));
                tiles.add(obj);
            }

            JsonObject body = new JsonObject();
            body.addProperty("clientID", clientId);
            body.add("tiles", tiles);

            WsContext ctx = room.getContextByClientId(clientId);
            if (ctx != null) {
                sendToClient(ctx, MessageType.AVAILABLE_TILES, body);
            }
        }
    }

    private void handleBuildDone(WsContext ctx, JsonObject body, GameRoom room, int clientId) {
        if (room.getGame().getRoundPhase() != RoundPhase.BUILD) {
            sendError(ctx, "INVALID_PHASE", "Wrong Phase for this request.");
            return;
        }
        int currentRound = room.getGame().getCurrentRound();
        if (!room.hasDoneBuildThisRound(clientId, currentRound)) {
            room.markBuildDone(clientId, currentRound);
        }
        if (room.allPlayersBuildDone() && room.tryAdvanceBuildPhaseOnce()) {
            room.getGameLogic().nextPhase();
        }
    }

    /**
     * Gibt die Starttile-ID für eine Spielerfarbe zurück
     */
    private int getStartTileIdForGamePiece(GamePiece piece) {
        return switch (piece) {
            case BLUE -> -1;
            case GREEN -> -2;
            case RED -> -3;
            case YELLOW -> -4;
            case WHITE -> -5;
        };
    }


    private String createMessage(MessageType type, JsonObject body) {
        JsonObject msg = new JsonObject();
        msg.addProperty("messageType", type.getWireName());
        msg.add("messageBody", body);
        try {
            log.debug("[SERVER SEND] {} -> {} body={}",
                    java.time.Instant.now(),
                    type.getWireName(),
                    (body == null ? "null" : body.toString()));
        } catch (Exception ignored) {}
        return gson.toJson(msg);
    }

    /**
     * Serialisiert ein Tile gemäß Protokoll 0.1
     */
    private JsonObject serializeTile(Tile tile) {
        JsonObject tileJson = new JsonObject();
        tileJson.addProperty("id", tile.getTileId());
        tileJson.addProperty("top", tile.getTop() != null ? tile.getTop().name().toLowerCase() : "none");
        tileJson.addProperty("right", tile.getRight() != null ? tile.getRight().name().toLowerCase() : "none");
        tileJson.addProperty("down", tile.getDown() != null ? tile.getDown().name().toLowerCase() : "none");
        tileJson.addProperty("left", tile.getLeft() != null ? tile.getLeft().name().toLowerCase() : "none");
        
        // Roads serialisieren
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
        
        // Elements serialisieren
        JsonArray elementsArray = new JsonArray();
        if (tile.getElements() != null) {
            for (var element : tile.getElements()) {
                elementsArray.add(element.name().toLowerCase());
            }
        }
        tileJson.add("elements", elementsArray);
        
        // Scroll serialisieren
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
            tileJson.add("scroll", JsonNull.INSTANCE);
        }
        
        tileJson.addProperty("spriteX", tile.getSpriteX());
        tileJson.addProperty("spriteY", tile.getSpriteY());
        
        return tileJson;
    }

    private void sendToClient(WsContext ctx, MessageType type, JsonObject body) {
        try {
            ctx.send(createMessage(type, body));
        } catch (Exception e) {
            System.err.println("Fehler beim Senden an Client: " + e.getMessage());
        }
    }

    private void sendError(WsContext ctx, String errorCode, String errorMessage) {
        JsonObject body = new JsonObject();
        body.addProperty("error", errorCode);
        body.addProperty("message", errorMessage);
        sendToClient(ctx, MessageType.ERROR, body);
    }

    /**
     * Sendet einen Fehler mit Liste aller legalen Moves.
     * Format: { "error": "...", "message": "...", "legalMoves": [{"x": 1, "y": 0, "rotation": 90}, ...] }
     */
    private void sendErrorWithLegalMoves(WsContext ctx, String errorCode, String errorMessage, List<int[]> legalMoves) {
        JsonObject body = new JsonObject();
        body.addProperty("error", errorCode);
        body.addProperty("message", errorMessage);
        
        JsonArray movesArray = new JsonArray();
        for (int[] move : legalMoves) {
            JsonObject moveObj = new JsonObject();
            moveObj.addProperty("x", move[0]);
            moveObj.addProperty("y", move[1]);
            moveObj.addProperty("rotation", move[2]);
            movesArray.add(moveObj);
        }
        body.add("legalMoves", movesArray);
        
        sendToClient(ctx, MessageType.ERROR, body);
    }

    /**
     * Sendet CoinUpdate für einen Spieler an alle Clients
     */
    private void sendCoinUpdate(GameRoom room, Player player) {
        int clientId = getClientIdForPlayer(room, player);
        if (clientId < 0) return;
        
        JsonObject coinBody = new JsonObject();
        coinBody.addProperty("clientID", clientId);
        coinBody.addProperty("coins", player.getCoins());
        room.broadcastAll(createMessage(MessageType.COIN_UPDATE, coinBody));
    }

    /**
     * Findet die clientId für einen Player
     */
    private int getClientIdForPlayer(GameRoom room, Player player) {
        for (var entry : room.getPlayers().entrySet()) {
            if (entry.getValue().equals(player)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private String generateRoomCode() {
        String chars = "abcdefghijklmnopqrstuvwxyz";
        String code;
        do {
            StringBuilder sb = new StringBuilder(4);
            for (int i = 0; i < 4; i++) sb.append(chars.charAt(random.nextInt(26)));
            code = sb.toString();
        } while (rooms.containsKey(code));
        return code;
    }

    private String generateToken() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private Tile findTileById(List<Tile> tiles, int tileId) {
        if (tiles == null) return null;
        for (Tile t : tiles) {
            if (t.getTileId() == tileId) return t;
        }
        return null;
    }


    /**
     * Ein Spielraum mit WebSocket-Verbindungen und Spiellogik.
     */
    static class GameRoom {
        private final Set<WsContext> connections = new CopyOnWriteArraySet<>();
        private final Map<WsContext, Integer> ctxToClientId = new ConcurrentHashMap<>();
        private final Map<Integer, WsContext> clientIdToCtx = new ConcurrentHashMap<>();
        private final Map<WsContext, String> ctxToUserId = new ConcurrentHashMap<>();
        private final Map<Integer, Player> players = new ConcurrentHashMap<>();
        private final Set<Integer> readyClients = ConcurrentHashMap.newKeySet();
        private final AtomicInteger nextClientId = new AtomicInteger(1);
        private final AtomicBoolean gameStarted = new AtomicBoolean(false);

        // Manual AI control flag
        private final AtomicBoolean manualAIControl = new AtomicBoolean(false);

        // AI tracking
        private final Set<Integer> aiClientIds = ConcurrentHashMap.newKeySet();
        private final Map<WsContext, Boolean> ctxToIsAI = new ConcurrentHashMap<>();
        private final Map<Integer, Set<Integer>> alreadySentDrawNumbers = new ConcurrentHashMap<>(); //verhindert dass ein Spieler dasselbe gezogene Tile mehrfach anfordert

        // Prevent duplicate scoring_update broadcasts (same payload)
        private volatile String lastScoringUpdatePayload = null;
        private final java.util.concurrent.atomic.AtomicLong lastScoringUpdateAtMs = new java.util.concurrent.atomic.AtomicLong(0);
        // Prevent double nextPhase() from BUILD when both clients finish nearly simultaneously
        private final java.util.concurrent.atomic.AtomicBoolean buildPhaseAdvanced = new java.util.concurrent.atomic.AtomicBoolean(false);


        // Tracks which clients still need to ack the GameStarted message
    private final Set<Integer> awaitingGameStartAcks = ConcurrentHashMap.newKeySet();
    // Ensure phase listener registration happens once
    private final AtomicBoolean phaseListenerRegistered = new AtomicBoolean(false);

    // Income collection (CollectIncome) tracking
    private final Set<Integer> incomeCollectedThisPhase = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Integer> lastIncomeRoundCollectedByClientId = new ConcurrentHashMap<>();

    private final Map<Integer, ScheduledFuture<?>> incomeTimeoutsByClientId = new ConcurrentHashMap<>();


        //Build completion tracking
        private final Set<Integer> buildDoneThisPhase = ConcurrentHashMap.newKeySet();
        private final Map<Integer, Integer> lastBuildRoundDoneByClientId = new ConcurrentHashMap<>();
        private final Map<Integer, Map<Integer, PendingBuildRetry>> pendingBuildRetriesByClientId = new ConcurrentHashMap<>();

        public void resetBuildDoneForNewBuildPhase(int currentRound) {
            buildDoneThisPhase.clear();
            buildPhaseAdvanced.set(false);
        }

        public boolean hasDoneBuildThisRound(int clientId, int currentRound) {
            Integer last = lastBuildRoundDoneByClientId.get(clientId);
            return last != null && last == currentRound;
        }

        public boolean markBuildDone(int clientId, int currentRound) {
            // true nur beim allerersten Mal in dieser Runde
            if (hasDoneBuildThisRound(clientId, currentRound)) return false;
            buildDoneThisPhase.add(clientId);
            lastBuildRoundDoneByClientId.put(clientId, currentRound);
            return true;
        }

        public boolean allPlayersBuildDone() {
            return !players.isEmpty() && buildDoneThisPhase.containsAll(players.keySet());
        }

        public void storePendingBuildRetry(int clientId, PendingBuildRetry retry) {
            if (retry == null) return;
            pendingBuildRetriesByClientId
                    .computeIfAbsent(clientId, k -> new ConcurrentHashMap<>())
                    .put(retry.tileId, retry);
        }

        public Map<Integer, PendingBuildRetry> getPendingBuildRetries(int clientId) {
            return pendingBuildRetriesByClientId.get(clientId);
        }

        public void removePendingBuildRetry(int clientId, int tileId) {
            Map<Integer, PendingBuildRetry> pending = pendingBuildRetriesByClientId.get(clientId);
            if (pending == null) return;
            pending.remove(tileId);
        }



        private final Game game = new Game();
        private final GameLogic gameLogic = new GameLogic(game);

        public void initGameStartAcks(java.util.Set<Integer> ids) {
            awaitingGameStartAcks.clear();
            if (ids == null) return;
            awaitingGameStartAcks.addAll(ids);
        }

        /** Marks a client's GameStarted ack. Returns true if all clients have acked. */
        public boolean markGameStartAck(int clientId) {
            if (clientId >= 0) awaitingGameStartAcks.remove(clientId);
            return awaitingGameStartAcks.isEmpty();
        }

        public boolean isPhaseListenerRegistered() {
            return phaseListenerRegistered.get();
        }

        /** Returns true if this call changed the registration state from false to true. */
        public boolean markPhaseListenerRegistered() {
            return phaseListenerRegistered.compareAndSet(false, true);
        }

        public GameRoom() {
        }

        public int addClient(WsContext ctx, String userId) {
            int clientId = nextClientId.getAndIncrement();
            connections.add(ctx);
            ctxToClientId.put(ctx, clientId);
            clientIdToCtx.put(clientId, ctx);
            ctxToUserId.put(ctx, userId);
            return clientId;
        }

        public void removeClient(WsContext ctx) {
            Integer clientId = ctxToClientId.remove(ctx);
            if (clientId != null) {
                clientIdToCtx.remove(clientId);
                players.remove(clientId);
                readyClients.remove(clientId);
            }
            connections.remove(ctx);
            ctxToUserId.remove(ctx);
            ctxToIsAI.remove(ctx);
        }

        // AI tracking methods

        public boolean isAI(WsContext ctx) {
            return ctxToIsAI.getOrDefault(ctx, false);
        }

        public void markClientAsAI(int clientId) {
            aiClientIds.add(clientId);
        }

        public Set<Integer> getAIClientIds() {
            return new HashSet<>(aiClientIds);
        }

        public void removeAIClient(int clientId) {
            aiClientIds.remove(clientId);
        }

        // Manual AI control methods
        public void enableManualAIControl() {
            manualAIControl.set(true);
        }

        public void resetManualAIControl() {
            if (aiClientIds.isEmpty()) {
                manualAIControl.set(false);
            }
        }

        public int getTotalPlayerCount() {
            return players.size();
        }

        public WsContext getContextByClientId(int clientId) {
            return clientIdToCtx.get(clientId);
        }

        public int getClientId(WsContext ctx) {
            return ctxToClientId.getOrDefault(ctx, -1);
        }

        public String getUserId(WsContext ctx) {
            return ctxToUserId.get(ctx);
        }

        public void addPlayer(int clientId, Player player) {
            players.put(clientId, player);
        }

        public Player getPlayer(int clientId) {
            return players.get(clientId);
        }

        public Map<Integer, Player> getPlayers() {
            return Collections.unmodifiableMap(players);
        }

        public GamePiece chooseColor(GamePiece requested) {
            Set<GamePiece> used = new HashSet<>();
            for (Player p : players.values()) {
                used.add(p.getGamePiece());
            }

            if (requested != null && !used.contains(requested)) {
                return requested;
            }

            for (GamePiece candidate : MainServer.AVAILABLE_COLORS) {
                if (!used.contains(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Choose a color/piece for the given client.
         *
         * Like {@link #chooseColor(GamePiece)} but ignores the current piece of the given clientId
         * when computing "used" colors. This prevents false COLOR_TAKEN errors when a client sends
         * redundant updates or when updates arrive close together.
         */
        public GamePiece chooseColor(int clientId, GamePiece requested) {
            Set<GamePiece> used = new HashSet<>();
            for (Map.Entry<Integer, Player> e : players.entrySet()) {
                if (e.getKey() != null && e.getKey() == clientId) continue;
                Player p = e.getValue();
                if (p != null) used.add(p.getGamePiece());
            }

            if (requested != null && !used.contains(requested)) {
                return requested;
            }

            for (GamePiece candidate : MainServer.AVAILABLE_COLORS) {
                if (!used.contains(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        public void setReady(int clientId, boolean ready) {
            if (ready) {
                readyClients.add(clientId);
            } else {
                readyClients.remove(clientId);
            }
        }

        public boolean allPlayersReady() {
            return !players.isEmpty() && readyClients.containsAll(players.keySet());
        }

        public void clearReadyStatus() {
            readyClients.clear();
        }

        public void resetIncomeCollectionForNewIncomePhase(int currentRound) {
            incomeCollectedThisPhase.clear();
            // Note: lastIncomeRoundCollectedByClientId is retained across rounds (used for duplicate protection)
            // but we still clear phase-local collection state.
        }

        public void setIncomeTimeout(int clientId, ScheduledFuture<?> future) {
            if (future == null) return;
            incomeTimeoutsByClientId.put(clientId, future);
        }

        public void cancelIncomeTimeout(int clientId) {
            ScheduledFuture<?> f = incomeTimeoutsByClientId.remove(clientId);
            if (f != null) {
                try { f.cancel(false); } catch (Exception ignored) {}
            }
        }

        public boolean hasCollectedIncomeThisRound(int clientId, int currentRound) {
            Integer last = lastIncomeRoundCollectedByClientId.get(clientId);
            return last != null && last == currentRound;
        }

        public void markIncomeCollected(int clientId, int currentRound) {
            incomeCollectedThisPhase.add(clientId);
            lastIncomeRoundCollectedByClientId.put(clientId, currentRound);
        }

        public boolean allPlayersCollectedIncome() {
            return !players.isEmpty() && incomeCollectedThisPhase.containsAll(players.keySet());
        }

        public boolean tryMarkGameStarted() {
            return gameStarted.compareAndSet(false, true);
        }

        public Game getGame() {
            return game;
        }

        public GameLogic getGameLogic() {
            return gameLogic;
        }

        public void broadcast(String msg, WsContext sender) {
            connections.stream()
                    .filter(c -> c != sender)
                    .forEach(c -> { try { c.send(msg); } catch (Exception ignored) {} });
        }

        public void broadcastAll(String msg) {
            connections.forEach(c -> { try { c.send(msg); } catch (Exception ignored) {} });
        }

        public boolean tryAdvanceBuildPhaseOnce() {
            return buildPhaseAdvanced.compareAndSet(false, true);
        }
    }

}
