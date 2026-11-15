package frontend.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javafx.scene.control.Alert;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Chat- und Spiel-Client für den MainServer.
 * Unterstützt Token-basierte Authentifizierung gemäß Protokoll 0.1
 */
public class ChatClient {

    private static final Logger log =
            LogManager.getLogger(ChatClient.class);

    private static final String SERVER = "http://localhost:8080";
    private static final Gson gson = new Gson();
    private static final HttpClient http = HttpClient.newHttpClient();

    /**
     * Startet den CLI-Chat- und Spiel-Client.
     *
     * @param args Programmargumente
     * @throws Exception bei Netzwerk- oder IO-Fehlern
     */
    public static void main(String[] args) throws Exception {
        boolean noUi = "1".equals(System.getenv("ISLE_NO_UI"));
        boolean startUi = "1".equals(System.getenv("ISLE_START_UI"));
        if (!noUi || startUi) {
            frontend.client.UiLauncher.startUi(null);
        }

        Scanner sc = new Scanner(System.in);
        Map<Integer, String> idToName = new ConcurrentHashMap<>();
        Map<String, Integer> nameToId = new ConcurrentHashMap<>();

        log.info("Name: ");
        String userId = sc.nextLine().trim();
        if (userId.isEmpty()) userId = "Spieler" + (System.currentTimeMillis() % 1000);

        log.info("Choose between (1) New room or (2) join");
        String choice = sc.nextLine().trim();

        String roomCode;
        if ("1".equals(choice)) {
            var resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(SERVER + "/api/createRoom"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            roomCode = gson.fromJson(resp.body(), JsonObject.class).get("roomCode").getAsString();
            log.info("Room created: {}", roomCode);
        } else {
            log.info("Room-Code: ");
            roomCode = sc.nextLine().trim().toLowerCase();
        }

        JsonObject joinBody = new JsonObject();
        joinBody.addProperty("roomCode", roomCode);
        joinBody.addProperty("userId", userId);

        var joinResp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(SERVER + "/api/joinRoom"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(joinBody))).build(),
                HttpResponse.BodyHandlers.ofString());

        if (joinResp.statusCode() != 200) {
            log.error("Error while joining: {}", joinResp.body());
            sc.close();
            return;
        }

        String token = gson.fromJson(joinResp.body(), JsonObject.class).get("token").getAsString();
        log.info("Token received, connect to WebSocket...");

        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:8080/game?token=" + token),
                        new WebSocket.Listener() {
                            private int myClientId = -1;

                            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                                JsonObject msg = gson.fromJson(data.toString(), JsonObject.class);
                                String type = msg.get("messageType").getAsString();
                                JsonObject body = msg.getAsJsonObject("messageBody");

                                // Netzwerkdetails sind i.d.R. Debug (kann sehr häufig sein)
                                log.debug("Message received: type={} body={}", type, body);

                                switch (type) {
                                    case "Welcome" -> {
                                        myClientId = body.get("clientID").getAsInt();
                                        log.info("Connected. clientID={}", myClientId);

                                        // "players" ist meist ein Array – nur loggen, wenn vorhanden
                                        if (body.has("players")) {
                                            log.debug("Players payload: {}", body.get("players"));
                                        }

                                        if (body.has("players") && body.get("players").isJsonArray()) {
                                            for (var entry : body.getAsJsonArray("players")) {
                                                JsonObject player = entry.getAsJsonObject();
                                                if (player.has("clientID") && player.has("name")) {
                                                    int pid = player.get("clientID").getAsInt();
                                                    String pname = player.get("name").getAsString();
                                                    idToName.put(pid, pname);
                                                    nameToId.put(pname.toLowerCase(), pid);
                                                    log.info("PlayerAdded: {} -> {}", pid, pname);
                                                }
                                            }
                                        }
                                    }

                                    case "PlayerAdded" -> {
                                        int pid = body.get("clientID").getAsInt();
                                        String pname = body.get("name").getAsString();

                                        boolean isNew = !idToName.containsKey(pid);
                                        idToName.put(pid, pname);
                                        nameToId.put(pname.toLowerCase(), pid);

                                        if (isNew) {
                                            log.info("PlayerAdded: {} -> {}", pid, pname);
                                            log.info("{} beigetreten", pname);
                                        } else {
                                            log.debug("PlayerUpdate: {} -> {}", pid, pname);
                                        }
                                    }

                                    case "ConnectionUpdate" -> {
                                        // Server uses this for non-join updates (e.g., color change)
                                        int pid = body.has("clientID") ? body.get("clientID").getAsInt() : -1;
                                        String pname = body.has("name") ? body.get("name").getAsString() : "";

                                        if (pid >= 0 && !pname.isBlank()) {
                                            idToName.put(pid, pname);
                                            nameToId.put(pname.toLowerCase(), pid);
                                            log.debug("PlayerUpdate: {} -> {}", pid, pname);
                                        } else {
                                            log.debug("ConnectionUpdate payload: {}", body);
                                        }
                                    }

                                    case "PlayerLeft" -> {
                                        int clientId = body.get("clientID").getAsInt();
                                        String leftName = idToName.remove(clientId);
                                        if (leftName != null) {
                                            nameToId.remove(leftName.toLowerCase());
                                        }
                                        log.info("Client {} hat das Spiel verlassen", clientId);
                                    }

                                    case "PlayerStatus" -> {
                                        int clientId = body.get("clientID").getAsInt();
                                        boolean ready = body.get("ready").getAsBoolean();
                                        log.info("Spieler {} ready={}", clientId, ready);
                                    }

                                    case "ReceivedChat" -> {
                                        String display;
                                        int fromId = -1;

                                        if (body.has("from")
                                                && body.get("from").isJsonPrimitive()
                                                && body.get("from").getAsJsonPrimitive().isNumber()) {

                                            fromId = body.get("from").getAsInt();
                                            display = idToName.getOrDefault(fromId, "Player " + fromId);

                                        } else {
                                            display = body.get("from").getAsString();
                                        }

                                        boolean isPrivate = body.has("isPrivate") && body.get("isPrivate").getAsBoolean();
                                        String prefix = isPrivate ? "[PRIVATE] " : "";

                                        // Debug: technische Auflösung
                                        log.debug("Chat received: fromId={} resolvedName={} isPrivate={}",
                                                fromId >= 0 ? fromId : "unknown",
                                                display, isPrivate);

                                        // Info: eigentliche Nachricht
                                        log.info("{}{}: {}", prefix, display, body.get("message").getAsString());
                                    }

                                    case "GameStarted" -> {
                                        log.info("Spiel gestartet");
                                    }

                                    case "RoundPhaseUpdate" -> {
                                        int round = body.get("round").getAsInt();
                                        // je nach Protokoll kann phase number oder string sein; du nutzt getAsString()
                                        String phase = body.get("phase").getAsString();
                                        log.info("Runde {} Phase {}", round, phase);
                                    }

                                    case "IncomeReceived" -> {
                                        int clientId = body.get("clientID").getAsInt();
                                        int income = body.get("income").getAsInt();
                                        log.info("Spieler {} erhält {} Gold (Income)", clientId, income);
                                    }

                                    case "LandscapeTileDrawn" -> {
                                        int clientId = body.get("clientID").getAsInt();
                                        int drawNumber = body.get("drawNumber").getAsInt();
                                        log.info("Spieler {} zog Tile #{}", clientId, drawNumber);
                                    }

                                    case "TileBuilt" -> {
                                        int clientId = body.get("clientID").getAsInt();
                                        int x = body.get("x_position").getAsInt();
                                        int y = body.get("y_position").getAsInt();
                                        log.info("Spieler {} baute Tile bei ({},{})", clientId, x, y);
                                    }

                                    case "TilePurchased" -> {
                                        // Achtung: bei euch oben stand buyerClientID/sellerClientID; Protokoll nutzt buyerID/sellerID
                                        // Ich lasse deine Feldnamen wie in deinem Code, damit es 1:1 passt.
                                        if (body.get("tileID").isJsonNull()) {
                                            int buyerId = body.get("buyerClientID").getAsInt();
                                            log.info("Spieler {} passt (kauft kein Tile)", buyerId);
                                        } else {
                                            int buyerId = body.get("buyerClientID").getAsInt();
                                            int sellerId = body.get("sellerClientID").getAsInt();
                                            int tileId = body.get("tileID").getAsInt();
                                            log.info("Spieler {} kauft Tile {} von Spieler {}", buyerId, tileId, sellerId);
                                        }
                                    }

                                    case "CoinUpdate" -> {
                                        int clientId = body.get("clientID").getAsInt();
                                        int coins = body.get("coins").getAsInt();
                                        log.debug("CoinUpdate: Spieler {} hat jetzt {} Gold", clientId, coins);
                                    }

                                    case "ScoringUpdate" -> {
                                        // Scores-Objekt kann groß sein -> Debug oder Info je nach Wunsch.
                                        // Für Spielverlauf oft Info ok, aber bei Spam besser Debug.
                                        log.info("ScoringUpdate: {}", body.get("scores"));
                                    }

                                    case "GameEnded" -> {
                                        log.info("Spiel beendet. Scores={}", body.get("scores"));
                                    }

                                    case "Error" -> {
                                        // Protokoll hat oft "error" oder "message". Du nutzt "message".
                                        String errMsg = body.has("message") ? body.get("message").getAsString()
                                                : (body.has("error") ? body.get("error").getAsString() : body.toString());
                                        log.error("Server-Error: {}", errMsg);
                                    }

                                    default -> {
                                        // Unknown message types: Warn
                                        log.warn("Unhandled messageType={} body={}", type, body);
                                    }
                                }

                                return WebSocket.Listener.super.onText(ws, data, last);
                            }


                            public void onError(WebSocket ws, Throwable error) {
                                log.error("WebSocket Fehler: {}", error.getMessage());
                            }
                        }).join();

        log.info("""
                    Befehle:
                  /ready              - Bereit melden
                  /notready           - Nicht bereit
                  /w <player> <msg>   - Private Nachricht
                  /quit               - Beenden
                  [text]              - Chat-Nachricht senden
                """);


        while (true) {
            String line = sc.nextLine();
            if ("/quit".equalsIgnoreCase(line)) break;
            if (line.isBlank()) continue;

            JsonObject msg = new JsonObject();
            JsonObject body = new JsonObject();

            if ("/ready".equalsIgnoreCase(line)) {
                msg.addProperty("messageType", "SetStatus");
                body.addProperty("ready", true);
            } else if ("/notready".equalsIgnoreCase(line)) {
                msg.addProperty("messageType", "SetStatus");
                body.addProperty("ready", false);
            } else if (line.toLowerCase().startsWith("/w ")) {
                // Private message: /w <playerName> <message>
                String[] parts = line.substring(3).split("\\s+", 2);
                if (parts.length < 2) {
                    log.error("Usage: /w <playerName> <message>");
                    continue;
                }

                String targetName = parts[0];
                String privateMessage = parts[1];

                Integer targetId = nameToId.get(targetName.toLowerCase());
                if (targetId == null) {
                    log.error("Player '{}' not found. Available players: {}",
                            targetName, nameToId.keySet());
                    continue;
                }

                msg.addProperty("messageType", "SendChat");
                body.addProperty("message", privateMessage);
                body.addProperty("isPrivate", true);
                body.addProperty("to", targetId);

                log.debug("Sending private message to {} (ID: {})", targetName, targetId);
            } else {
                // Public chat message
                msg.addProperty("messageType", "SendChat");
                body.addProperty("message", line);
                body.addProperty("isPrivate", false);
                body.addProperty("to", -1);
            }

            msg.add("messageBody", body);
            ws.sendText(gson.toJson(msg), true);
        }

        ws.sendClose(1000, "bye");
        sc.close();
    }

}
