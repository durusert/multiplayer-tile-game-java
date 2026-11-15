package backend.ai;

import backend.model.Position;
import backend.model.enums.EdgeType;
import backend.model.enums.TileElement;
import backend.model.enums.TileRoadSide;
import backend.model.tile.PlacedTile;
import backend.model.tile.RoadConnectivity;
import backend.model.tile.Tile;
import backend.model.tile.TilePlacementRules;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * AiClient verbindet sich per Websocket mit dem Game-Server und führt
 * abhängig von Server-Nachrichten Aktionen aus.
 *
 * KI ist event-getrieben: handelt primär als Reaktion auf Server-Messages.
 * 
 * Die KI unterstützt alle Spielphasen:
 * - Lobby: PlayerValues setzen, Ready signalisieren
 * - Plättchen ziehen
 * - Preise festlegen
 * - Plättchen kaufen
 * - Plättchen anbauen
 */
public class AiClient {

    /** Logger für Status- und Fehlermeldungen dieses KI-Clients. */
    private static final Logger log =
            LogManager.getLogger(AiClient.class);

    /** Default-Host für lokale Entwicklung. */
    private static final String DEFAULT_HOST = "localhost";
    /** Default-Port für lokale Entwicklung. */
    private static final int DEFAULT_PORT = 8080;

    /** Hostname/IP des Game-Servers. */
    private final String serverHost;
    /** TCP-Port des Game-Servers. */
    private final int serverPort;

    // Lobby-join
    /** Anzeigename der KI; wird als {@code userId} beim {@code joinRoom} verwendet. */
    private final String aiName;
    /** Geburtsdatum (YYYY-MM-DD), wird beim Senden von {@code PlayerValues} übertragen. */
    private final String birthDate;
    /** Ob die KI vom Server als Player erfolgreich registriert wurde. */
    private volatile boolean registeredAsPlayer = false;
    /** Ob {@code SetStatus(ready)} bereits gesendet wurde (verhindert Duplicate-Ready). */
    private volatile boolean readySent = false;
    /** Gewählte Spielfigur/Farbe (z. B. "blue"). */
    private volatile String chosenGamePiece = null;
    /** Alle möglichen Spielfiguren/Farben (für Retry bei {@code COLOR_TAKEN}). */
    private static final String[] ALL_PIECES = { "blue", "green", "red", "yellow", "white" };
    /** Retry-Index für die nächste Farbe bei {@code COLOR_TAKEN}. */
    private int pieceRetryIndex = 0;
    /** Zufallsquelle für Fallback-Entscheidungen und simple Random-Strategien. */
    private final Random random = new Random();

    /** JSON-Serializer/Parser (inkl. Nulls), passend zum Protokoll. */
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    /** HTTP-Client für Room-Endpunkte und WebSocket-Aufbau. */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Strategieobjekt, das Preis-/Kauf-/Build-Entscheidungen kapselt. */
    private final AiDecisionStrategy strategy;

    /** Aktive WebSocket-Verbindung zum Server. */
    private WebSocket webSocket;
    /** Timer für periodische Keep-Alive-Nachrichten. */
    private Timer keepAliveTimer;
    /** Scheduler für verzögertes Ausführen von Aktionen (Rate-Limits/Sequencing). */
    private ScheduledExecutorService actionScheduler;
    /** Client-ID (vom Server in {@code Welcome} vergeben). */
    private int myClientId = -1;
    /** Aktueller Room-Code (Lobby/Matchmaking). */
    private String roomCode;

    // Spielstatus
    /** Aktuelle Phase laut Server (RoundPhaseUpdate). */
    private int currentPhase = -1;
    /** Aktuelle Runde laut Server. */
    private int currentRound = 0;
    /** Ob das Spiel bereits gestartet wurde (GameStarted). */
    private boolean gameStarted = false;
    /** Ob die KI aktuell am Zug ist (CurrentPlayer). */
    private boolean isMyTurn = false;
    /** Aktueller Münzstand der KI (CoinUpdate). */
    private int myCoins = 0;


    /**
     * Lokale Tile-Hand/Tile-Infos als Map (tileId -> JSON-Objekt).
     * <p>
     * Wird in DRAW & BUY aufgebaut, und in BUILD per {@code AvailableTiles} vom Server ersetzt.
     * LinkedHashMap bewahrt Einfüge-Reihenfolge (nützlich für deterministische Iteration).
     * </p>
     */
    private final Map<Integer, JsonObject> myDrawnTiles = new LinkedHashMap<>();
    /** Cache der kaufbaren Tiles in der BUY-Phase. */
    private final List<AvailableTileInfo> allAvailableTiles = new ArrayList<>();

    /** Vom Server gelieferte legale Platzierungen (x,y,rotation) für das aktuelle Build-Problem. */
    private final List<int[]> legalMoves = new ArrayList<>();
    /** Retry-Zähler für das aktuelle Tile in BUILD (bei ILLEGAL_PLACEMENT). */
    private int currentTileRetryCount = 0;

    /** Optionales Callback für UI/Monitoring (Status & Errors). */
    private AIStatusCallback statusCallback;

    /** Puffer für BUILD-AvailableTiles, wenn diese vor Phase 5 eintreffen. */
    private JsonArray pendingBuildAvailableTiles;

    // BUY-Phase Sync
    /** Guard: Buy-Aktion wurde in dieser BUY-Phase bereits gesendet. */
    private boolean buyActionSentThisPhase = false;
    /** Guard: BUY-Optionen (AvailableTiles) wurden in dieser BUY-Phase empfangen. */
    private boolean buyOptionsReceivedThisPhase = false;
    /** Startzeitpunkt der BUY-Phase (für Timeout beim Warten auf AvailableTiles). */
    private long buyPhaseStartMs = 0;
    /** Timeout in ms für BUY: wenn keine Options eintreffen, wird gepasst. */
    private static final long BUY_WAIT_TIMEOUT_MS = 2500;

    /** Runde, in der bereits gepreist wurde (verhindert doppeltes Pricing). */
    private int pricedRound = -1;

    // wenn true, build-loop bereits gestartet -> verhindert mehrere Build-Tasks gleichzeitig
    /** Guard: verhindert, dass in Phase BUILD mehrfach parallel Build-Tasks gestartet werden. */
    private boolean buildScheduledThisPhase = false;

    // verhindert, dass alte scheduled Build-Tasks in späteren Phasen noch ausführen
    /** Epochenzähler für Build-Scheduling; alte Tasks prüfen ihre Epoche und brechen sonst ab. */
    private long buildTasks = 0;

    /** Tile-Katalog/Factory, um tileId -> {@link Tile} zu erhalten. */
    private final TileCatalog tileCatalog = new TileCatalog();

    /** Lokales ClanTerritory der KI für heuristische Placement-Bewertung und Connectivity-Checks. */
    private backend.model.ClanTerritory myTerritory;

    // Draw-Logik
    /** Maximale Anzahl Draws pro Spieler/Runde (Protokoll/Regelannahme). */
    private static final int MAX_DRAWS_PER_PLAYER = 3;
    /** Nächster erwarteter drawNumber für {@code DrawLandscapeTile}. */
    private int nextDrawNumber = 0;
    /** Guard: Es ist bereits ein Draw-Request offen und noch nicht bestätigt. */
    private boolean pendingDrawRequest = false;

    /**
     * ScoringView = anonyme Klasse
     * Liefert aktive Scoring-Tiles und grobe Tile-Wert-Schätzung.
     */
    private final ScoringView scoringView = new ScoringView() {

        // liefert Scoring-Tile Ids die in Runde aktiv sind (abhängig von Spieleranzahl & Runde)
        /**
         * Berechnet die Liste aktiver Scoring-Tile-IDs (A–D) für die aktuelle Runde.
         * <p>
         * Die Zuordnung hängt von der Spieleranzahl ab (2–4 vs. 5 Spieler) und nutzt den
         * internen Schedule. Der Server sendet in {@code GameStarted} die konkreten Scoring-IDs
         * in Reihenfolge A..D; diese Methode mappt Slot-Buchstaben auf Indizes 0..3.
         * </p>
         *
         * @return Liste aktiver Scoring-Tile-IDs als Strings (z. B. {@code ["12","7"]});
         *         kann leer sein, falls keine IDs bekannt sind oder Runde außerhalb des Schedules liegt.
         */
        @Override public List<String> activeScorings() {
            if (scoringTileIds.isEmpty()) return List.of(); // erstellt immutable Liste

            final char[][] scheduleTwoToFour = {
                    { 'A', 'B' },
                    { 'A', 'C' },
                    { 'B', 'C' },
                    { 'B', 'D' },
                    { 'C', 'D' },
                    { 'A', 'D' }
            };
            final char[][] scheduleFivePlayers = {
                    { 'A', 'B', 'C' },
                    { 'A', 'C', 'D' },
                    { 'A', 'B' },
                    { 'B', 'D' },
                    { 'C', 'D' }
            };

            char[][] schedule = (playerCount >= 5) ? scheduleFivePlayers : scheduleTwoToFour;
            if (currentRound < 0 || currentRound >= schedule.length) return List.of();

            // Server schickt bei "GameStarted" Nachricht eine Liste der ScoringTileIds
            // hier wird jedes Listenelement (Zugriff per Index 0-3) auf den richtigen Buchstaben gemappt
            List<String> out = new ArrayList<>();
            for (char slot : schedule[currentRound]) {
                int idx = switch (slot) {
                    case 'A' -> 0;
                    case 'B' -> 1;
                    case 'C' -> 2;
                    case 'D' -> 3;
                    default -> -1;
                };
                if (idx >= 0 && idx < scoringTileIds.size()) {
                    out.add(String.valueOf(scoringTileIds.get(idx)));
                } else {
                    out.add(String.valueOf(slot));
                }
            }
            return out; // out = ["12", "7"] -> Liste der aktiven ScoringTileIds als String, unter Beachtung Spieleranzahl & aktueller Runde
        }

        /**
         * Schätzt den (relativen) Wert eines Tiles anhand seiner Elemente, Kanten und aktiven Scorings.
         * <p>
         * Diese Heuristik dient primär der Preisfindung und der Placement-Bewertung und ist
         * bewusst "leichtgewichtig" gehalten. Unbekannte Tiles erhalten eine konservative Baseline.
         * </p>
         *
         * @param tileId ID des Tiles (wie vom Server geliefert)
         * @return Heuristische Wertschätzung (größer = wertvoller); Minimum typischerweise um {@code 1.0}.
         */
        @Override public double tileValueEstimate(int tileId) {
            JsonObject tile = myDrawnTiles.get(tileId);
            if (tile == null) {
                return 1.0;
            }

            // Elemente zählen
            Map<String, Integer> counts = new java.util.HashMap<>();
            if (tile.has("elements") && tile.get("elements").isJsonArray()) {
                JsonArray elements = tile.getAsJsonArray("elements");
                for (JsonElement el : elements) {
                    if (el.isJsonPrimitive()) { // stellt sicher dass Liste = primitiver Datentyp
                        String key = el.getAsString();
                        counts.merge(key, 1, Integer::sum);
                    }
                }
            }

            // berechneten Element-count in Variablen speichern
            int totalElements = counts.values().stream().mapToInt(Integer::intValue).sum();
            int sheep = counts.getOrDefault("sheep", 0);
            int ox = counts.getOrDefault("ox", 0);
            int ship = counts.getOrDefault("ship", 0);
            int whiskey = counts.getOrDefault("whiskey", 0);
            int lighthouse = counts.getOrDefault("lighthouse", 0);
            int tower = counts.getOrDefault("tower", 0);
            int farm = counts.getOrDefault("farm", 0);

            // EdgeTypes analysieren
            int waterEdges = 0;
            int mountainEdges = 0;
            for (String edge : new String[]{"top", "right", "down", "left"}) {
                if (!tile.has(edge) || !tile.get(edge).isJsonPrimitive()) continue;
                String v = tile.get(edge).getAsString();
                if ("water".equals(v)) waterEdges++;
                if ("mountain".equals(v)) mountainEdges++;
            }

            // hat das Tile roads?
            boolean hasRoad = tile.has("roads") && tile.get("roads").isJsonArray()
                    && tile.getAsJsonArray("roads").size() > 0;

            double value = 1.0 + totalElements * 0.15;

            // Scroll Bonus (je mehr Pluspunkte -> desto höhere Value)
            if (tile.has("scroll") && tile.get("scroll").isJsonObject()) {
                JsonObject scroll = tile.getAsJsonObject("scroll");
                if (scroll.has("points")) {
                    value += scroll.get("points").getAsInt() * 0.6;
                }
            }

            List<Integer> activeIds = new ArrayList<>();
            for (String s : activeScorings()) {
                try {
                    activeIds.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                }
            }

            /**
             * - geschätze Punktevergabe je nach dem wie viel "Wert" gewisses Kriterium hat in Bezug auf die 15 möglichen Scoring-Tiles
             * - klein gehalten um Punkte relativ stabil zu halten
             */
            for (int id : activeIds) {
                switch (id) {
                    case 0 -> value += 0.2; // 2x2 squares (jede Tile gleich wert)
                    case 1 -> value += 0.2 + (mountainEdges > 0 ? 0.1 : 0.0); // completed Area
                    case 2 -> value += 0.2 + (mountainEdges > 1 ? 0.1 : 0.0); // completed Area 3 Tiles
                    case 3 -> value += sheep * 0.6; // Sheep-count
                    case 4 -> value += tower * 1.0 + mountainEdges * 0.2; // mountain broch
                    case 5 -> value += tower * 0.8 + farm * 0.8 + lighthouse * 0.8; // broch-farm-lighthouse set
                    case 6 -> value += whiskey * 0.7; // most barrel tiles
                    case 7 -> value += ship * 0.7; // most ships
                    case 8 -> value += (sheep + ox) * 0.5 + farm * 0.3; // sheep/ox neben farm
                    case 9 -> {} // meistes gold -> nichts mit Tiles zu tun
                    case 10 -> value += ox * 0.5 + (hasRoad ? 0.2 : 0.0); // cattle connected to castle
                    case 11 -> value += ship * 0.6 + lighthouse * 0.5 + waterEdges * 0.2; // water-ship-lighthouse-areas
                    case 12 -> value += hasRoad ? 0.2 : 0.0; // Tiles connected to castle
                    case 13 -> value += 0.1; // vertical lines of 3
                    case 14 -> value += waterEdges * 0.2; // completed water area
                    case 15 -> value += mountainEdges * 0.2; // completed mountain area
                    default -> { }
                }
            }
            return value; // Basisvalue (1.0) + totalElements-Bonus + Scroll-Bonus + activeScoringTiles-Bonus
        }
    };

    /**
     * GameStateView: bewertet konkrete Placements (x,y,rotation) abhängig von:
     * - Nachbaranzahl
     * - offene Seiten
     * - Road-Mismatches
     * - aktive Scorings
     * - Nähe zum Zentrum etc.
     */
    private final GameStateView gameStateView = new GameStateView() {

        /**
         * Bewertet eine konkrete Platzierung eines Tiles in der Clan-Area.
         * <p>
         * Die Bewertung kombiniert:
         * <ul>
         *   <li>Legality-Check ({@code canPlace}): illegale Züge werden mit {@link Double#NEGATIVE_INFINITY} bestraft</li>
         *   <li>Nachbarschaften, offene Kanten und Road-Mismatches</li>
         *   <li>Scoring-spezifische Heuristiken (z. B. 2x2 Squares, Vertikalreihen, Road-to-Castle)</li>
         *   <li>leichter Zentrum-Bonus</li>
         * </ul>
         * </p>
         *
         * @param tileId ID des zu platzierenden Tiles
         * @param x x-Koordinate im Grid
         * @param y y-Koordinate im Grid
         * @param rotation Rotation in Grad (typisch 0/90/180/270)
         * @return Heuristischer Score (höher = besser); {@link Double#NEGATIVE_INFINITY} für illegale Placements.
         */
        @Override
        public double evaluatePlacement(int tileId, int x, int y, int rotation) {

            // möglichst zentral bauen, falls Territory leer
            if (myTerritory == null) {
                return - (Math.abs(x) + Math.abs(y));
            }

            // unbekannte Tiles schlechter
            Tile tile = tileCatalog.apply(tileId);
            if (tile == null) {
                return - (Math.abs(x) + Math.abs(y));
            }

            // -unendl. wenn tile nicht legal plaziert werden kann
            if (!myTerritory.canPlace(tile, x, y, rotation)) {
                return Double.NEGATIVE_INFINITY;
            }

            Map<Position, PlacedTile> tiles = myTerritory.getTiles();
            Set<Integer> active = parseActiveScorings();

            // Startscore (tileValueEstimate fießt ein)
            double score = 0.0;
            score += scoringView.tileValueEstimate(tileId) * 0.8;

            int neighborCount = 0;
            int openSides = 0;
            int roadMismatches = 0;
            int roadOpen = 0;
            int waterTouch = 0;
            int mountainTouch = 0;
            int fieldTouch = 0;
            int waterOpen = 0;
            int mountainOpen = 0;
            int fieldOpen = 0;

            // Jede Seite checken: ist Nachbar da, Kanten match, Road match
            for (TileRoadSide side : TileRoadSide.values()) {
                Position np = neighborPosition(x, y, side);
                PlacedTile neighbor = tiles.get(np);
                EdgeType edge = TilePlacementRules.getEdgeAtSide(tile, side, rotation);

                if (neighbor != null) {
                    neighborCount++;
                    switch (edge) {
                        case WATER -> waterTouch++;
                        case MOUNTAIN -> mountainTouch++;
                        case FIELD -> fieldTouch++;
                    }

                    // Road-Connection (missmatch wenn road aber kein Nachbar)
                    boolean tileRoad = hasRoadAtSide(tile, rotation, side);
                    boolean neighborRoad = hasRoadAtSide(
                            neighbor.tile(), neighbor.rotationDegrees(), opposite(side));
                    if (tileRoad != neighborRoad) {
                        roadMismatches++;
                    }
                } else {
                    openSides++;
                    switch (edge) {
                        case WATER -> waterOpen++;
                        case MOUNTAIN -> mountainOpen++;
                        case FIELD -> fieldOpen++;
                    }
                    // Road die ins Nichts läuft ist schlecht
                    if (hasRoadAtSide(tile, rotation, side)) {
                        roadOpen++;
                    }
                }
            }

            // Heuristik: viele Nachbarn gut, viele offene Seiten, Roads die nicht passen/ins Nichts führen schlecht
            score += neighborCount * 1.1;
            score -= openSides * 0.25;
            score -= roadMismatches * 0.6;
            score -= roadOpen * 0.3;

            // Bonus: vollständig umschlossen (keine offenen Seiten)
            if (openSides == 0) {
                score += 1.2;
            }

            // Strafe: "Einseitig dranhängen" (ein Nachbar, sonst offen)
            if (neighborCount == 1 && openSides >= 3) {
                score -= 1.0;
            }

            // Bonuspunkte je aktive ScoringTile

            // 2x2 squares um die Position (nutzt nur vorhandene Tiles, das neue Tile zählt implizit)
            int squares = count2x2Squares(tiles, x, y);
            if (squares > 0) {
                score += squares * (active.contains(0) ? 2.0 : 0.7);
            }

            // Vertikale Kettenlänge (für scoring tile 13)
            int verticalRun = verticalRunLength(tiles, x, y);
            if (verticalRun >= 3) {
                score += active.contains(13) ? 1.5 : 0.6;
            }

            // leichter Zentrum-Bonus (weiter weg -> minimal schlechter)
            score -= 0.08 * (Math.abs(x) + Math.abs(y));

            // Elemente zählen (aus dem Tile-Model)
            int sheep = 0;
            int ox = 0;
            int ship = 0;
            int whiskey = 0;
            int tower = 0;
            int farm = 0;
            int lighthouse = 0;

            for (TileElement e : tile.getElements()) {
                switch (e) {
                    case SHEEP -> sheep++;
                    case OX -> ox++;
                    case SHIP -> ship++;
                    case WHISKEY -> whiskey++;
                    case TOWER -> tower++;
                    case FARM -> farm++;
                    case LIGHTHOUSE -> lighthouse++;
                    default -> { }
                }
            }

            boolean hasFarm = farm > 0;
            boolean adjacentFarm = hasFarm || isAdjacentToFarm(tiles, x, y);
            int livestock = sheep + ox;

            if (active.contains(3)) score += sheep * 0.6;
            if (active.contains(6)) score += whiskey * 0.6;
            if (active.contains(7)) score += ship * 0.6;
            if (active.contains(5)) score += (tower + farm + lighthouse) * 0.5;
            if (active.contains(4)) score += tower * 0.8 + mountainTouch * 0.2;

            if (active.contains(8)) {
                if (adjacentFarm && livestock > 0) {
                    score += livestock * 0.7;
                }
                if (hasFarm) {
                    score += countAdjacentLivestock(tiles, x, y) * 0.3;
                }
            }

            if (active.contains(11)) {
                if ((ship + lighthouse) > 0 && (waterTouch + waterOpen) > 0) {
                    score += 0.8 + (ship + lighthouse) * 0.3;
                }
            }

            if (active.contains(14)) {
                score += waterTouch * 0.3;
                if (openSides == 0) score += 0.5;
            }

            if (active.contains(15)) {
                score += mountainTouch * 0.3;
                if (openSides == 0) score += 0.5;
            }

            if (active.contains(1) || active.contains(2)) {
                if (neighborCount >= 3) score += 0.8;
                if (openSides == 0) score += 0.6;
            }

            if (active.contains(10) || active.contains(12)) {
                Set<Position> connected = RoadConnectivity.connectedToCastle(myTerritory);
                if (!connected.isEmpty()) {
                    boolean connectsToCastle = connectsToCastleByRoad(connected, tiles, tile, x, y, rotation);
                    if (connectsToCastle) {
                        if (active.contains(12)) score += 1.0;
                        if (active.contains(10) && ox > 0) score += ox * 1.2;
                    }
                }
            }

            if (active.contains(14) && waterOpen > 0) {
                score += waterOpen * 0.1;
            }
            if (active.contains(15) && mountainOpen > 0) {
                score += mountainOpen * 0.1;
            }
            if (active.contains(0) && fieldTouch > 0) {
                score += fieldTouch * 0.1;
            }

            return score;
        }

        // Wrapper-Methoden damit in anonymer Klasse zugänglich
        /**
         * Delegiert an {@link ScoringView#tileValueEstimate(int)} und dient als Adapter,
         * damit andere Komponenten (z. B. Strategie) Tile-Werte abfragen können.
         *
         * @param tileId Tile-ID
         * @return heuristischer Tile-Wert
         */
        @Override
        public double estimateTileValue(int tileId) {
            return scoringView.tileValueEstimate(tileId);
        }
    };

    /**
     * Callback-Interface zur Ausgabe von Statusmeldungen/Fehlern an eine UI oder Logik-Schicht.
     * <p>
     * Optional: kann über {@link #setStatusCallback(AIStatusCallback)} registriert werden.
     * </p>
     */
    public interface AIStatusCallback {
        /**
         * Wird bei „normalen“ Statusänderungen/Events aufgerufen (z. B. Verbindungsstatus, Phasenwechsel).
         *
         * @param message Statusmeldung (menschenlesbar)
         */
        void onStatusChange(String message);

        /**
         * Wird bei Fehlern aufgerufen (z. B. Protokollfehler, WebSocket-Probleme).
         *
         * @param error Fehlermeldung (menschenlesbar)
         */
        void onError(String error);
    }

    /**
     * Datenklasse für ein in der BUY-Phase kaufbares Tile.
     * <p>
     * Enthält Tile-ID, Verkäufer (Client-ID) und den Preis (kann {@code null} sein).
     * </p>
     */
    private static class AvailableTileInfo {
        /** ID des kaufbaren Tiles. */
        int tileId;
        /** Client-ID des Besitzers/Verkäufers. */
        int ownerClientId;
        /** Preis in Coins; {@code null} falls kein Preis gesetzt wurde. */
        Integer price;

        /**
         * Erstellt eine neue Tile-Info für die BUY-Phase.
         *
         * @param tileId Tile-ID
         * @param ownerClientId Verkäufer/Owner Client-ID
         * @param price Preis (nullable)
         */
        AvailableTileInfo(int tileId, int ownerClientId, Integer price) {
            this.tileId = tileId;
            this.ownerClientId = ownerClientId;
            this.price = price;
        }
    }

    /**
     * Erstellt eine neue RandomAI mit Standard-Server-Einstellungen.
     * 
     * @param aiName Name der KI
     */
    public AiClient(String aiName) {
        this(DEFAULT_HOST, DEFAULT_PORT, aiName, "2000-01-01");
    }

    /**
     * Erstellt eine neue KI-Instanz mit konfigurierbarem Server und Entscheidungsstrategie.
     * <p>
     * Wird keine Strategie übergeben, wird eine {@link RandomDecisionStrategy} als Default verwendet.
     * </p>
     *
     * @param serverHost Server-Host
     * @param serverPort Server-Port
     * @param aiName     Name der KI
     * @param birthDate  Geburtsdatum im Format YYYY-MM-DD
     * @param strategy   Entscheidungsstrategie (nullable)
     */
    public AiClient(String serverHost, int serverPort, String aiName, String birthDate, AiDecisionStrategy strategy) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.aiName = aiName;
        this.birthDate = birthDate;

        this.strategy = (strategy != null) ? strategy : new RandomDecisionStrategy(random);
    }

    /**
     * Convenience-Konstruktor: nutzt eine neue {@link RandomDecisionStrategy} mit eigener Random-Quelle.
     *
     * @param serverHost Server-Host
     * @param serverPort Server-Port
     * @param aiName     Name der KI
     * @param birthDate  Geburtsdatum im Format YYYY-MM-DD
     */
    public AiClient(String serverHost, int serverPort, String aiName, String birthDate) {
        this(serverHost, serverPort, aiName, birthDate, new RandomDecisionStrategy(new Random()));
    }

    /**
     * Setzt ein optionales Callback, um Status und Fehler an z. B. eine UI weiterzugeben.
     *
     * @param callback Callback-Implementierung (nullable)
     */
    public void setStatusCallback(AIStatusCallback callback) {
        this.statusCallback = callback;
    }

    /**
     * Loggt eine Statusmeldung und leitet sie (falls registriert) an den {@link AIStatusCallback} weiter.
     *
     * @param message menschenlesbare Statusmeldung
     */
    private void logStatus(String message) {
        log.info("[AI {}] {}", aiName, message);
        if (statusCallback != null) {
            statusCallback.onStatusChange(message);
        }
    }

    /**
     * Loggt einen Fehler und leitet ihn (falls registriert) an den {@link AIStatusCallback} weiter.
     *
     * @param error menschenlesbare Fehlermeldung
     */
    private void logError(String error) {
        log.error("[AI {} ERROR] {}", aiName, error);
        if (statusCallback != null) {
            statusCallback.onError(error);
        }
    }

    /**
     * Erstellt einen neuen Raum (HTTP) und verbindet sich anschließend per WebSocket.
     *
     * @return der neu erstellte Room-Code
     * @throws Exception bei HTTP-/WebSocket-Verbindungsfehlern
     */
    public String createRoomAndConnect() throws Exception {
        String serverHttp = "http://" + serverHost + ":" + serverPort;

        // Raum erstellen
        var createResp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(serverHttp + "/api/createRoom"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
                HttpResponse.BodyHandlers.ofString());

        if (createResp.statusCode() != 200) {
            throw new RuntimeException("Raum erstellen fehlgeschlagen: " + createResp.body());
        }

        this.roomCode = gson.fromJson(createResp.body(), JsonObject.class).get("roomCode").getAsString();
        logStatus("Raum erstellt: " + roomCode);

        // Dem Raum beitreten
        connectToRoom(roomCode);
        return roomCode;
    }

    /**
     * Sucht nach einem Raum mit 1 Spieler (wartend auf Mitspieler) und tritt bei.
     * Falls kein solcher Raum existiert, wird ein neuer erstellt.
     * 
     * @return Der Raum-Code des beigetretenen/erstellten Raums
     * @throws Exception bei Verbindungsfehlern
     */
    public String findAndJoinOrCreateRoom() throws Exception {
        String serverHttp = "http://" + serverHost + ":" + serverPort;

        // Liste aller Räume abrufen
        var roomsResp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(serverHttp + "/api/rooms"))
                .GET()
                .build(),
                HttpResponse.BodyHandlers.ofString());

        if (roomsResp.statusCode() == 200) {
            JsonObject response = gson.fromJson(roomsResp.body(), JsonObject.class);
            if (response.has("rooms")) {
                JsonArray rooms = response.getAsJsonArray("rooms");

                // Suche nach Raum mit 1 Spieler (wartend auf Mitspieler)
                for (JsonElement roomElem : rooms) {
                    JsonObject room = roomElem.getAsJsonObject();
                    int playerCount = room.get("playerCount").getAsInt();

                    // Raum mit 1 Spieler gefunden - beitritt
                    if (playerCount >= 1 && playerCount < 2) {
                        String foundRoomCode = room.get("roomCode").getAsString();
                        logStatus("Raum mit " + playerCount + " Spieler gefunden: " + foundRoomCode);
                        connectToRoom(foundRoomCode);
                        return foundRoomCode;
                    }
                }
            }
        }

        // Kein passender Raum gefunden - neuen erstellen
        logStatus("Kein wartender Raum gefunden, erstelle neuen...");
        return createRoomAndConnect();
    }

    /**
     * Verbindet sich zu einem existierenden Raum.
     * 
     * @param roomCode Der Raum-Code
     * @throws Exception bei Verbindungsfehlern
     */
    public void connectToRoom(String roomCode) throws Exception {
        this.roomCode = roomCode;
        String serverHttp = "http://" + serverHost + ":" + serverPort;
        String serverWs = "ws://" + serverHost + ":" + serverPort;

        // Token holen
        JsonObject joinBody = new JsonObject();
        joinBody.addProperty("roomCode", roomCode);
        joinBody.addProperty("userId", aiName);

        var joinResp = httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(serverHttp + "/api/joinRoom"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(joinBody)))
                .build(),
                HttpResponse.BodyHandlers.ofString());

        if (joinResp.statusCode() != 200) {
            throw new RuntimeException("Beitreten fehlgeschlagen: " + joinResp.body());
        }

        String token = gson.fromJson(joinResp.body(), JsonObject.class).get("token").getAsString();
        logStatus("Token erhalten, verbinde WebSocket...");

        // WebSocket verbinden
        webSocket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(serverWs + "/game?token=" + token), new WebSocket.Listener() {

                    private StringBuilder messageBuffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        messageBuffer.append(data);
                        if (last) {
                            handleServerMessage(messageBuffer.toString());
                            messageBuffer = new StringBuilder();
                        }
                        return WebSocket.Listener.super.onText(ws, data, last);
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        logError("WebSocket Fehler: " + error.getMessage());
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        logStatus("WebSocket geschlossen: " + reason);
                        stopKeepAlive();
                        return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                    }
                }).join();

        logStatus("WebSocket verbunden!");
        startKeepAlive();
    }

    /**
     * Startet den Keep-Alive Timer.
     * <p>
     * Sendet alle 5 Sekunden eine {@code Alive}-Nachricht (passend zum Server-Intervall),
     * um Timeouts zu vermeiden.
     * </p>
     */
    private void startKeepAlive() {
        keepAliveTimer = new Timer("AI-KeepAlive-" + aiName, true);
        keepAliveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendAlive();
            }
        }, 5000, 5000);
    }

    /**
     * Stoppt den Keep-Alive Timer (falls aktiv)
     */
    private void stopKeepAlive() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }
    }

    /**
     * Deserialisiert eine rohe JSON-Nachricht, bestimmt {@code messageType} und delegiert an
     * den passenden Handler.
     * <p>
     * Fehler beim Parsen oder Verarbeiten werden geloggt; die KI läuft weiter.
     * </p>
     *
     * @param rawMessage rohe JSON-Nachricht vom Server (Wrapper-Objekt mit messageType/messageBody)
     */
    private void handleServerMessage(String rawMessage) {
        try {
            JsonObject msg = gson.fromJson(rawMessage, JsonObject.class);
            String messageType = msg.has("messageType") ? msg.get("messageType").getAsString() : "";
            JsonObject body = msg.has("messageBody") ? msg.getAsJsonObject("messageBody") : new JsonObject();

            // Alive-Nachrichten nicht loggen
            if (!"Alive".equals(messageType)) {}

            switch (messageType) {
                case "HelloClient" -> handleHelloClient(body);
                case "Welcome" -> handleWelcome(body);
                case "Alive" -> {
                    // Server-ALive nur akzeptieren, nicht zurückschicken
                }
                case "PlayerAdded" -> handlePlayerAdded(body);
                case "PlayerStatus" -> handlePlayerStatus(body);
                case "GameStarted" -> handleGameStarted(body);
                case "RoundPhaseUpdate" -> handleRoundPhaseUpdate(body);
                case "CurrentPlayer" -> handleCurrentPlayer(body);
                case "LandscapeTileDrawn" -> handleLandscapeTileDrawn(body);
                case "RevealTilePrices" -> handleRevealTilePrices(body);
                case "AvailableTiles" -> handleAvailableTiles(body);
                case "PriceSubmitRejected" -> handlePriceSubmitRejected(body);
                case "TilePurchased" -> handleTilePurchased(body);
                case "TileBuilt" -> handleTileBuilt(body);
                case "TileReturned" -> handleTileReturned(body);
                case "CoinUpdate" -> handleCoinUpdate(body);
                case "StartPlayerUpdate" -> handleStartPlayerUpdate(body);
                case "IncomeReceived" -> handleIncomeReceived(body);
                case "TilePricingCompleted" -> handleTilePricingCompleted(body);
                case "ScoringUpdate" -> handleScoringUpdate(body);
                case "GameEnded" -> handleGameEnded(body);
                case "PlayerLeft" -> handlePlayerLeft(body);
                case "Error" -> handleError(body);
                default -> logStatus("Unbekannte Nachricht: " + messageType);
            }
        } catch (Exception e) {
            logError("Fehler beim Verarbeiten der Nachricht: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Message Handler

    /**
     * Handler für {@code HelloClient}.
     * <p>
     * Loggt die Protokollversion und antwortet mit {@code HelloServer}.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleHelloClient(JsonObject body) {
        logStatus("Server verbunden (Protokoll: " + body.get("protocol").getAsString() + ")");
        // HelloServer senden
        sendHelloServer();
    }

    /**
     * Handler für {@code Welcome}.
     * <p>
     * Setzt die eigene {@code clientID}, ermittelt bereits belegte Spielfiguren und sendet anschließend
     * {@code PlayerValues} mit einer freien Farbe.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleWelcome(JsonObject body) {
        myClientId = body.get("clientID").getAsInt();
        logStatus("Willkommen! ClientID: " + myClientId);

        // bereits belegte Farben sammeln
        Set<String> taken = new HashSet<>();
        if (body.has("players") && body.get("players").isJsonArray()) {
            JsonArray players = body.getAsJsonArray("players");
            for (JsonElement el : players) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                if (p.has("gamePiece") && !p.get("gamePiece").isJsonNull()) {
                    taken.add(p.get("gamePiece").getAsString().toLowerCase());
                }
            }
        }

        // 2) Wähle eine freie Farbe (oder fallback)
        chosenGamePiece = pickFreePiece(taken);
        pieceRetryIndex = 0;

        // PlayerValues senden
        sendPlayerValues(chosenGamePiece);
    }

    /**
     * Wählt eine freie Spielfigur/Farbe aus {@link #ALL_PIECES}, die noch nicht belegt ist.
     *
     * @param taken Menge bereits belegter Farben (lowercase)
     * @return eine freie Farbe; falls keine frei ist, ein zufälliger Fallback
     */
    private String pickFreePiece(Set<String> taken) {
        for (String p : ALL_PIECES) {
            if (!taken.contains(p)) return p;
        }
        // Wenn wirklich alle vergeben sind (sollte in 2-5 Spieler selten sein),
        // nimm trotzdem irgendeine (Server wird dann ablehnen -> wir retryen)
        return ALL_PIECES[random.nextInt(ALL_PIECES.length)];
    }

    /**
     * Handler für {@code PlayerAdded}.
     * <p>
     * Sobald die KI selbst hinzugefügt wurde, markiert sie sich als registriert und setzt (einmalig)
     * den Ready-Status per {@code SetStatus}.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handlePlayerAdded(JsonObject body) {
        String name = body.get("name").getAsString();
        logStatus("Spieler hinzugefügt: " + name);

        // Wenn KI selbst hinzugefügt -> registriert -> ready senden
        if (name.equals(aiName)) {
            registeredAsPlayer = true;

            if (!readySent) {
                readySent = true;
                scheduleDelayed(() -> setReady(true), 200); // kleiner Delay ist ok
            }
        }
    }

    /**
     * Handler für {@code PlayerStatus} (Ready/Not-Ready anderer Spieler).
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handlePlayerStatus(JsonObject body) {
        int clientId = body.get("clientID").getAsInt();
        boolean ready = body.get("ready").getAsBoolean();
        logStatus("Spieler " + clientId + " ist " + (ready ? "bereit" : "nicht bereit"));
    }

    /** Liste der ScoringTile-IDs (A..D), wie vom Server in {@code GameStarted} geliefert. */
    private final List<Integer> scoringTileIds = new ArrayList<>();
    /** Spieleranzahl im Match (aus {@code castles}-Payload abgeleitet). */
    private int playerCount = 0;

    /**
     * Handler für {@code GameStarted}.
     * <p>
     * Speichert Scoring-IDs, bestimmt die Spieleranzahl und initialisiert das eigene Territory
     * (Start-Castle platzieren).
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleGameStarted(JsonObject body) {
        gameStarted = true;
        logStatus("Spiel gestartet!");

        // speichert ScoringTiles
        scoringTileIds.clear();
        if (body.has("scoringIDs")) {
            JsonArray ids = body.getAsJsonArray("scoringIDs");
            for (JsonElement el : ids) {
                if (el.isJsonPrimitive()) {
                    scoringTileIds.add(el.getAsInt());
                }
            }
        }
        // bestimmt Spieleranzahl
        if (body.has("castles") && body.get("castles").isJsonArray()) {
            playerCount = body.getAsJsonArray("castles").size();
        }
        // plaziert Start-Castle
        for (JsonElement el : body.getAsJsonArray("castles")) {
            JsonObject c = el.getAsJsonObject();
            int cid = c.get("clientID").getAsInt();
            if (cid == myClientId) {
                int tileId = c.get("tileID").getAsInt();
                backend.model.tile.Tile t = tileCatalog.apply(tileId);
                backend.model.Player p = new backend.model.Player(aiName, backend.model.enums.GamePiece.BLUE);
                myTerritory = p.getClanTerritory();
                if (t != null) myTerritory.placeInitialCastle(t);
                break;
            }
        }
    }

    /**
     * Verarbeitet {@code RoundPhaseUpdate}.
     * <p>
     * Aktualisiert {@link #currentRound} und {@link #currentPhase}, setzt pro neuer Runde
     * bestimmte Guards/States zurück und triggert phasenspezifische Aktionen:
     * <ul>
     *   <li>Phase 0: {@code CollectIncome}</li>
     *   <li>Phase 1: Draw-Requests vorbereiten/triggern (nur wenn {@code isMyTurn})</li>
     *   <li>Phase 2: Pricing (einmalig pro Runde) planen</li>
     *   <li>Phase 4: BUY-Guards/Timeout initialisieren</li>
     *   <li>Phase 5: BUILD-Guards initialisieren und ggf. gepufferte AvailableTiles anwenden</li>
     * </ul>
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private int lastRoundSeen = -1;
    private void handleRoundPhaseUpdate(JsonObject body) {
        currentRound = body.get("round").getAsInt();
        currentPhase = body.get("phase").getAsInt();
        logStatus("=== Runde " + currentRound + ", Phase " + getPhaseDescription(currentPhase) + " ===");

        // Nur bei neuer Runde den State zurücksetzen
        if (currentRound != lastRoundSeen) {
            lastRoundSeen = currentRound;
            pricesSetThisRound = false;
            priceTimerScheduled = false;
            nextDrawNumber = 0;
            pendingDrawRequest = false;
            myDrawnTiles.clear();
            logStatus("Neue Runde - State wird zurueckgesetzt");
        }

        if (currentPhase == 0) {
            // CollectIncome nur in Income-Phase ausführen
            scheduleDelayed(this::collectIncome, 200);
            return;
        }

        if (currentPhase == 1) {
            pendingDrawRequest = false;
            nextDrawNumber = myDrawnTiles.size();
            // Draw erfolgt nur über expliziten Request, aber wir starten ihn wenn wir am Zug sind
            if (isMyTurn) {
                scheduleDelayed(() -> requestNextDrawIfNeeded("phaseUpdate"), 200);
            }
            return;
        }

        // Außerhalb der Draw-Phase kein Pending-Request halten
        pendingDrawRequest = false;

        if (currentPhase == 2) {
            logStatus("DISCARD-Phase gestartet - setze Preise fuer " + myDrawnTiles.size() + " Tiles");
            if (!pricesSetThisRound && myDrawnTiles.size() >= 2) {
                scheduleDelayed(() -> {
                    if (!pricesSetThisRound && !myDrawnTiles.isEmpty()) {
                        setRandomTilePrices();
                    }
                }, 500);
            } else if (!pricesSetThisRound && !myDrawnTiles.isEmpty()) {
                scheduleDelayed(() -> {
                    if (!pricesSetThisRound) {
                        setRandomTilePrices();
                    }
                }, 500);
            }
            return;
        }

        if (currentPhase == 4) {
            log.info("Phase gewechselt: BUY-Phase gestartet");

            logStatus("BUY-Phase gestartet - warte auf AvailableTiles und CurrentPlayer");

            buyActionSentThisPhase = false;
            buyOptionsReceivedThisPhase = false;
            buyPhaseStartMs = System.currentTimeMillis();
            return;
        }

        if (currentPhase == 5) {
            buildTasks++;
            currentTileRetryCount = 0;
            legalMoves.clear();
            buildScheduledThisPhase = false;
            logStatus("BUILD-Phase gestartet - warte auf AvailableTiles");

            if (pendingBuildAvailableTiles != null) {
                applyBuildAvailableTiles(pendingBuildAvailableTiles);
                pendingBuildAvailableTiles = null;
            }
        }
    }

    /**
     * Handler für {@code CurrentPlayer}.
     * <p>
     * Setzt {@link #isMyTurn} und triggert bei eigenem Zug {@link #handleMyTurn()}.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleCurrentPlayer(JsonObject body) {
        int currentClientId = body.get("clientID").getAsInt();
        isMyTurn = (currentClientId == myClientId);

        logStatus("CurrentPlayer empfangen: " + currentClientId + " (ich=" + myClientId + ", isMyTurn=" + isMyTurn + ")");

        if (isMyTurn) {
            logStatus(">>> Ich bin am Zug! Phase: " + getPhaseDescription(currentPhase));
            // Aktion basierend auf Phase ausführen
            handleMyTurn();
        } else {
            pendingDrawRequest = false;
            logStatus("Spieler " + currentClientId + " ist am Zug (Phase: " + getPhaseDescription(currentPhase) + ")");
        }
    }

    /** Guard: Preise wurden in dieser Runde bereits gesendet. */
    private boolean pricesSetThisRound = false;
    /** Guard: verhindert mehrere Pricing-Timer parallel. */
    private boolean priceTimerScheduled = false;

    // nach jeder gezogenen Tile speichern, nochmal ziehen oder in Pricing übergehen
    /**
     * Handler für {@code LandscapeTileDrawn}.
     * <p>
     * Aktualisiert die lokale Hand, aktualisiert {@code nextDrawNumber} anhand {@code drawNumber}
     * und plant ggf. Pricing oder den nächsten Draw-Request.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleLandscapeTileDrawn(JsonObject body) {
        int clientId = body.get("clientID").getAsInt();
        JsonObject tile = body.getAsJsonObject("tile");

        if (clientId == myClientId) {
            pendingDrawRequest = false;
            if (body.has("drawNumber")) {
                int drawNumber = body.get("drawNumber").getAsInt();
                nextDrawNumber = Math.max(nextDrawNumber, drawNumber + 1);
            }
            int tileId = tile.get("id").getAsInt();
            if (!myDrawnTiles.containsKey(tileId)) {
                myDrawnTiles.put(tileId, tile);
                logStatus("Tile gezogen: ID " + tileId + " (Total: " + myDrawnTiles.size() + ")");
                
                // Wenn wir in Phase 2 sind und Tiles haben, Preise setzen
                // Nur einen Timer starten, nicht mehrere
                if (currentPhase == 2 && !pricesSetThisRound && !priceTimerScheduled && myDrawnTiles.size() >= 2) {
                    priceTimerScheduled = true;
                    scheduleDelayed(() -> {
                        if (!pricesSetThisRound && !myDrawnTiles.isEmpty()) {
                            setRandomTilePrices();
                        }
                        priceTimerScheduled = false;
                    }, 1000);
                }
            }

            // Falls wir noch ziehen dürfen, nächste Anfrage auslösen
            if (currentPhase == 1 && isMyTurn) {
                scheduleDelayed(() -> requestNextDrawIfNeeded("tileDrawn"), 150);
            }
        }
    }

    // sammelt alle kaufbaren Tiles der anderen Spieler
    /**
     * Handler für {@code RevealTilePrices}.
     * <p>
     * Baut die Liste kaufbarer Tiles (anderer Spieler) aus der Payload {@code tilePrices} auf.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleRevealTilePrices(JsonObject body) {
        logStatus("Preise werden enthüllt");
        allAvailableTiles.clear();

        // Verfügbare Tiles für den Kauf sammeln
        if (body.has("tilePrices")) {
            JsonArray tilePrices = body.getAsJsonArray("tilePrices");
            for (JsonElement playerPricesElem : tilePrices) {
                JsonObject playerPrices = playerPricesElem.getAsJsonObject();
                int ownerClientId = playerPrices.get("clientID").getAsInt();

                // Eigene Tiles nicht kaufen
                if (ownerClientId == myClientId)
                    continue;

                JsonArray prices = playerPrices.getAsJsonArray("prices");
                for (JsonElement priceElem : prices) {
                    JsonObject priceInfo = priceElem.getAsJsonObject();
                    boolean discard = priceInfo.get("discard").getAsBoolean();

                    if (!discard && priceInfo.has("tileID")) {
                        int tileId = priceInfo.get("tileID").getAsInt();
                        Integer price = priceInfo.get("price").isJsonNull() ? null : priceInfo.get("price").getAsInt();
                        allAvailableTiles.add(new AvailableTileInfo(tileId, ownerClientId, price));
                    }
                }
            }
        }
    }

    // Tiles zum Bauen von Server entgegennehmen
    /**
     * Handler für {@code AvailableTiles}.
     * <p>
     * Je nach Phase unterschiedliche Bedeutung:
     * <ul>
     *   <li>BUILD (Phase 5): Server sendet Hand pro Client (mit clientID). Diese Hand ersetzt den lokalen Stand.</li>
     *   <li>BUY (Phase 4): Server sendet kaufbare Tiles-Liste (ohne clientID pro Hand).</li>
     * </ul>
     * Falls BUILD-AvailableTiles vor Phase 5 kommt, wird sie gepuffert.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleAvailableTiles(JsonObject body) {
        logStatus("AvailableTiles empfangen - Phase: " + currentPhase + ", hat clientID: " + body.has("clientID"));

        if (body.has("clientID") && !body.get("clientID").isJsonNull() && body.has("tiles")) {
            int clientId = body.get("clientID").getAsInt();
            if (clientId != myClientId) return;

            JsonArray tiles = body.getAsJsonArray("tiles");

            // Wenn BUILD noch nicht aktiv ist: puffern (kommt manchmal "zu früh")
            if (currentPhase != 5) {
                pendingBuildAvailableTiles = tiles;
                logStatus("BUILD-AvailableTiles gepuffert (aktuelle Phase: " + currentPhase + ")");
                return;
            }

            // BUILD aktiv -> Hand setzen
            applyBuildAvailableTiles(tiles);
            return;
        }

        if (currentPhase == 5 && (!body.has("clientID") || body.get("clientID").isJsonNull())) {
            logError("BUILD-AvailableTiles ohne clientID erhalten -> ignoriere (Hand-Update braucht clientID).");
            return;
        }

        if (currentPhase == 4 && body.has("tiles")) {
            allAvailableTiles.clear();
            JsonArray tiles = body.getAsJsonArray("tiles");

            for (JsonElement elem : tiles) {
                JsonObject tileEntry = elem.getAsJsonObject();

                int tileId = tileEntry.get("tileID").getAsInt();
                int sellerClientId = tileEntry.get("sellerClientID").getAsInt();
                Integer price = tileEntry.has("price") && !tileEntry.get("price").isJsonNull()
                        ? tileEntry.get("price").getAsInt()
                        : null;

                // Eigene Tiles nicht kaufen
                if (sellerClientId != myClientId) {
                    allAvailableTiles.add(new AvailableTileInfo(tileId, sellerClientId, price));
                }
            }

            logStatus("BUY-Phase: Kaufbare Tiles erhalten: " + allAvailableTiles.size() + " (Runde " + currentRound + ")");
            buyOptionsReceivedThisPhase = true;

            // Wenn KI schon am Zug ist, sofort Entscheidung ausführen
            if (isMyTurn) {
                scheduleDelayed(this::buyRandomTileOrPass, 200);
            }
        }
    }

    /**
     * Handler für {@code PriceSubmitRejected}.
     * <p>
     * Dient hier u. a. als Feedback für Draw-Requests: Bei Ablehnung wird der Pending-Status entfernt
     * und ggf. mit korrigiertem {@code drawNumber} erneut versucht.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handlePriceSubmitRejected(JsonObject body) {
        String code = body.has("code") ? body.get("code").getAsString() : "";
        String reason = body.has("reason") ? body.get("reason").getAsString() : "";
        logError("Server rejected action: " + code + " - " + reason);

        // Wenn in DRAW-Phase etwas abgelehnt wurde, erneut versuchen
        if (currentPhase == 1) {
            pendingDrawRequest = false;
            Integer expected = parseExpectedDrawNumber(reason);
            if (expected != null) {
                nextDrawNumber = expected;
            }
            if (!isMyTurn) return; // nicht spamen
            scheduleDelayed(() -> requestNextDrawIfNeeded("drawRejected"), 300);
        }
    }

    /**
     * Parst aus einer Server-Fehlermeldung eine erwartete {@code drawNumber}.
     * <p>
     * Erwartet ein Fragment wie {@code "erwartet: <zahl>"} und gibt diese Zahl zurück.
     * </p>
     *
     * @param reason Fehlertext des Servers
     * @return erwartete drawNumber oder {@code null} wenn nicht parsbar
     */
    private Integer parseExpectedDrawNumber(String reason) {
        if (reason == null) return null;
        String marker = "erwartet:";
        int idx = reason.indexOf(marker);
        if (idx < 0) return null;
        String tail = reason.substring(idx + marker.length()).trim();
        int end = 0;
        while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
        if (end == 0) return null;
        try {
            return Integer.parseInt(tail.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // verarbeitet wenn Spieler KI-Tiles kauft
    /**
     * Handler für {@code TilePurchased}.
     * <p>
     * Aktualisiert lokale Hand (gekaufte Tiles hinzufügen, verkaufte entfernen) und loggt das Ereignis.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleTilePurchased(JsonObject body) {
        int buyerId = body.has("buyerID") && !body.get("buyerID").isJsonNull()
                ? body.get("buyerID").getAsInt()
                : -1;
        int sellerId = body.has("sellerID") && !body.get("sellerID").isJsonNull()
                ? body.get("sellerID").getAsInt()
                : -1;

        logStatus("TilePurchased empfangen - Buyer: " + buyerId + ", Seller: " + sellerId + ", myID: " + myClientId);

        if (buyerId == myClientId) {
            if (body.has("tileID") && !body.get("tileID").isJsonNull()) {
                int tileId = body.get("tileID").getAsInt();
                logStatus("Ich habe Tile " + tileId + " gekauft!");
                // Gekauftes Tile zu unseren Tiles hinzufügen (falls tile-Daten vorhanden)
                if (body.has("tile") && !body.get("tile").isJsonNull()) {
                    JsonObject tile = body.getAsJsonObject("tile");
                    myDrawnTiles.put(tileId, tile);
                    logStatus("Tile zu myDrawnTiles hinzugefügt, insgesamt: " + myDrawnTiles.size());
                }
            } else {
                logStatus("Ich habe gepasst");
            }
        } else if (buyerId >= 0) {
            logStatus("Spieler " + buyerId + " hat gekauft (nicht ich)");
        }

        // Wenn jemand anderes unser Tile kauft, entfernen
        if (sellerId == myClientId && body.has("tileID") && !body.get("tileID").isJsonNull()) {
            int tileId = body.get("tileID").getAsInt();
            myDrawnTiles.remove(tileId);
            logStatus("Mein Tile " + tileId + " wurde von Spieler " + buyerId + " gekauft, verbleibend: " + myDrawnTiles.size());
        }
    }

    /**
     * Handler für {@code TileBuilt}.
     * <p>
     * Bei eigenem Tile: aktualisiert lokales Territory, entfernt Tile aus Hand, resettet Retries und
     * plant ggf. den Bau weiterer Tiles.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleTileBuilt(JsonObject body) {
        int clientId = body.get("clientID").getAsInt();
        int tileId = body.get("tileID").getAsInt();

        if (clientId == myClientId && myTerritory != null) {
            int x = body.get("x_position").getAsInt();
            int y = body.get("y_position").getAsInt();
            int r = body.get("rotation").getAsInt();
            backend.model.tile.Tile t = tileCatalog.apply(tileId);
            if (t != null) myTerritory.placeTile(t, x, y, r);
        }

        if (clientId == myClientId) {
            logStatus("Tile " + tileId + " erfolgreich gebaut!");
            // Tile aus meiner Liste entfernen
            myDrawnTiles.remove(tileId);

            // Reset retry counter nach erfolgreichem Bau
            currentTileRetryCount = 0;
            legalMoves.clear();

            // Falls noch Tiles da sind und wir in Phase 5 sind, weiterbauen
            if (currentPhase == 5 && !myDrawnTiles.isEmpty()) {
                logStatus("Baue nächstes Tile (" + myDrawnTiles.size() + " verbleibend)");
                scheduleBuild(500);
            } else if (currentPhase == 5) {
                logStatus("Alle Tiles gebaut! Warte auf andere Spieler...");
            }
        }
    }

    // verarbeitet wenn Tiles nicht plaziert werden können -> zurück in TileBag
    /**
     * Handler für {@code TileReturned}.
     * <p>
     * Wird ausgelöst, wenn ein Tile nicht platzierbar ist und in den Bag zurückgeht.
     * Entfernt das Tile aus der Hand, resettet Retries und versucht ggf. das nächste Tile.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleTileReturned(JsonObject body) {
        int clientId = body.get("clientID").getAsInt();
        int tileId = body.get("tileID").getAsInt();

        if (clientId == myClientId) {
            logStatus("Tile " + tileId + " konnte nicht platziert werden und wurde zurückgegeben");
            myDrawnTiles.remove(tileId);

            // Reset retry counter für nächstes Tile
            currentTileRetryCount = 0;
            legalMoves.clear();

            // Falls noch Tiles da sind und wir in Phase 5 sind, weiterbauen
            if (currentPhase == 5 && !myDrawnTiles.isEmpty()) {
                logStatus("Versuche nächstes Tile (" + myDrawnTiles.size() + " verbleibend)");
                scheduleBuild(500);
            } else if (currentPhase == 5) {
                logStatus("Alle Tiles bearbeitet! Warte auf andere Spieler...");
            }
        }
    }

    // aktualisiert eigenen Münzenstand nach Server-Update
    /**
     * Handler für {@code CoinUpdate}.
     * <p>
     * Setzt den lokalen Münzstand auf den vom Server gesendeten absoluten Wert.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleCoinUpdate(JsonObject body) {
        int clientId = body.get("clientID").getAsInt();
        int coins = body.get("coins").getAsInt();

        if (clientId == myClientId) {
            myCoins = coins;
            logStatus("Münzstand aktualisiert: " + coins + " Münzen");
        }
    }

    /** Max. Retry-Anzahl pro Tile in der BUILD-Phase, bevor das Tile übersprungen wird. */
    private static final int MAX_RETRIES_PER_TILE = 10;

    /**
     * Handler für {@code Error}.
     * <p>
     * Verarbeitet serverseitige Fehlercodes und implementiert Recovery-Strategien:
     * <ul>
     *   <li>DRAW: Pending aufheben, expected drawNumber extrahieren, ggf. Retry</li>
     *   <li>Lobby: {@code COLOR_TAKEN} -> neue Farbe und erneut {@code PlayerValues}</li>
     *   <li>Lobby: {@code PLAYER_NOT_FOUND} -> Re-Register</li>
     *   <li>BUILD: {@code ILLEGAL_PLACEMENT} -> legalMoves übernehmen und Placement erneut versuchen</li>
     * </ul>
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleError(JsonObject body) {
        String error = body.has("error") ? body.get("error").getAsString() : "Unbekannter Fehler";
        String message = body.has("message") ? body.get("message").getAsString() : "";
        logError("Server-Fehler: " + error + " - " + message);

        // Draw-Fehler: Pending Request aufheben und ggf. neu versuchen
        if (currentPhase == 1 && pendingDrawRequest) {
            pendingDrawRequest = false;
            Integer expected = parseExpectedDrawNumber(message);
            if (expected != null) {
                nextDrawNumber = expected;
            }
            if (isMyTurn && ("INVALID_MOVE".equals(error) || "INVALID_PHASE".equals(error) || "DUPLICATE_DRAW".equals(error))) {
                scheduleDelayed(() -> requestNextDrawIfNeeded("drawError:" + error), 300);
            }
        }

        // Lobby Join Fehler: Farbe schon vergeben -> neue Farbe probieren
        if ("COLOR_TAKEN".equals(error) && !registeredAsPlayer) {
            // Nächste Farbe probieren (simple round-robin)
            pieceRetryIndex++;
            String next = ALL_PIECES[pieceRetryIndex % ALL_PIECES.length];
            chosenGamePiece = next;

            logStatus("COLOR_TAKEN -> retry PlayerValues mit gamePiece=" + next);

            scheduleDelayed(() -> sendPlayerValues(next), 200);
            return;
        }

        if ("PLAYER_NOT_FOUND".equals(error)) {
            registeredAsPlayer = false;
            readySent = false;

            // nochmal PlayerValues senden
            String piece = (chosenGamePiece != null) ? chosenGamePiece : ALL_PIECES[0];
            logStatus("PLAYER_NOT_FOUND -> versuche Re-Register mit gamePiece=" + piece);
            scheduleDelayed(() -> sendPlayerValues(piece), 300);
            return;
        }

        // Bei Bau-Fehlern: legale Moves speichern und nochmal versuchen
        if (("ILLEGAL_PLACEMENT".equals(error) || "ILLEGAL".equals(error)) && currentPhase == 5) {
            currentTileRetryCount++;

            // Safeguard: Wenn zu viele Versuche, Tile überspringen
            if (currentTileRetryCount > MAX_RETRIES_PER_TILE) {
                logError("Tile kann nach " + MAX_RETRIES_PER_TILE + " Versuchen nicht platziert werden - überspringe");
                currentTileRetryCount = 0;
                legalMoves.clear();

                // Versuche nächstes Tile oder beende
                if (myDrawnTiles.size() > 1) {
                    // Erstes Tile entfernen und zum nächsten wechseln
                    Integer firstTileId = myDrawnTiles.keySet().iterator().next();
                    myDrawnTiles.remove(firstTileId);
                    logStatus("Wechsle zum nächsten Tile");
                    scheduleBuild(500);
                } else {
                    logStatus("Keine weiteren Tiles zum Bauen");
                }
                return;
            }

            legalMoves.clear();
            if (body.has("legalMoves")) {
                JsonArray moves = body.getAsJsonArray("legalMoves");
                for (JsonElement elem : moves) {
                    JsonObject move = elem.getAsJsonObject();
                    int x = move.get("x").getAsInt();
                    int y = move.get("y").getAsInt();
                    int rotation = move.get("rotation").getAsInt();
                    legalMoves.add(new int[]{x, y, rotation});
                }
                logStatus("Server hat " + legalMoves.size() + " legale Moves gesendet (Versuch " + currentTileRetryCount + ")");
            }

            if (legalMoves.isEmpty()) {
                logError("Keine legalen Moves vom Server erhalten, aber ILLEGAL_PLACEMENT - dies sollte nicht passieren!");
            }

            scheduleBuild(500);
        } else if (currentPhase == 5) {
            // Andere Fehler während BUILD-Phase
            logError("Unerwarteter Fehler während BUILD: " + error + " - versuche mit nächstem Tile");
            currentTileRetryCount = 0;
            legalMoves.clear();

            if (!myDrawnTiles.isEmpty()) {
                scheduleBuild(1000);
            }
        }
    }

    /**
     * Handler für {@code StartPlayerUpdate}.
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleStartPlayerUpdate(JsonObject body) {
        int startPlayerId = body.get("clientID").getAsInt();
        logStatus("Startspieler: " + startPlayerId + (startPlayerId == myClientId ? " (Ich!)" : ""));
    }

    /**
     * Handler für {@code IncomeReceived}.
     * <p>
     * Loggt Income; der absolute Münzstand wird separat durch {@code CoinUpdate} gesetzt.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleIncomeReceived(JsonObject body) {
        int clientId = body.get("clientID").getAsInt();
        if (clientId == myClientId && body.has("income")) {
            int income = body.get("income").getAsInt();
            // Nicht addieren - CoinUpdate setzt den korrekten Wert
            logStatus("Einkommen erhalten: " + income + " Münzen");
        }
    }

    /**
     * Handler für {@code TilePricingCompleted}.
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleTilePricingCompleted(JsonObject body) {
        int clientId = body.get("clientID").getAsInt();
        logStatus("Spieler " + clientId + " hat Preise festgelegt");
    }

    /**
     * Handler für {@code ScoringUpdate}.
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleScoringUpdate(JsonObject body) {
        logStatus("Wertung durchgeführt");
    }

    // verarbeitet Spielende, gibt Endpunkte aus
    /**
     * Handler für {@code GameEnded}.
     * <p>
     * Setzt {@link #gameStarted} zurück und loggt Endstände.
     * </p>
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handleGameEnded(JsonObject body) {
        gameStarted = false;
        logStatus("Spiel beendet!");

        if (body.has("scores")) {
            JsonArray scores = body.getAsJsonArray("scores");
            for (JsonElement elem : scores) {
                JsonObject score = elem.getAsJsonObject();
                int clientId = score.get("clientID").getAsInt();
                int points = score.get("victoryPoints").getAsInt();
                logStatus("Spieler " + clientId + ": " + points + " Punkte");
            }
        }
    }

    /**
     * Handler für {@code PlayerLeft}.
     *
     * @param body messageBody der Server-Nachricht
     */
    private void handlePlayerLeft(JsonObject body) {
        int clientId = body.has("clientID") ? body.get("clientID").getAsInt() : -1;
        logStatus("Spieler " + clientId + " hat das Spiel verlassen");
    }

    // Aktions-Handler
    /**
     * Führt Aktionen aus, wenn wir am Zug sind.
     * <p>
     * Die konkrete Aktion hängt von {@link #currentPhase} ab:
     * <ul>
     *   <li>Phase 1: weiteres Tile ziehen</li>
     *   <li>Phase 4: kaufen oder passen</li>
     * </ul>
     * BUILD ist nicht turn-basiert und wird über {@code AvailableTiles} getriggert.
     * </p>
     */
    private void handleMyTurn() {
        switch (currentPhase) {
            case 1 -> scheduleDelayed(() -> requestNextDrawIfNeeded("myTurn"), 200);
            case 4 -> {
                // Phase 4: Tile kaufen oder passen
                scheduleDelayed(() -> buyRandomTileOrPass(), 500);
            }
            // Phase 5 (BUILD) ist nicht rundenbasiert - alle Spieler bauen gleichzeitig
            // Die Baulogik wird durch AvailableTiles-Nachricht getriggert
        }
    }

    // Sende-Methoden
    /**
     * Baut ein Protokoll-Wrapper-Objekt und sendet es über den WebSocket.
     * <p>
     * Das Protokollformat ist:
     * {@code {"messageType":"...", "messageBody":{...}}}
     * </p>
     *
     * @param messageType Nachrichtentyp (Protokoll)
     * @param body messageBody (nullable -> leeres Objekt)
     */
    private void sendProtocolMessage(String messageType, JsonObject body) {
        if (webSocket == null) {
            logError("WebSocket nicht verbunden");
            return;
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("messageType", messageType);
        msg.add("messageBody", body != null ? body : new JsonObject());

        String json = gson.toJson(msg);

        // Alive-Nachrichten nicht loggen (zu viele)
        if (!"Alive".equals(messageType)) {
            logStatus(">>> SENDE: " + messageType + " - " + json);
        }

        webSocket.sendText(json, true);
    }

    /**
     * Sendet eine {@code Alive}-Nachricht an den Server (Keep-Alive).
     */
    private void sendAlive() {
        sendProtocolMessage("Alive", new JsonObject());
    }

    /**
     * Antwortet auf {@code HelloClient} mit {@code HelloServer} inkl. Gruppen-/AI-Informationen.
     */
    private void sendHelloServer() {
        JsonObject body = new JsonObject();
        body.addProperty("group", "RandomAI");
        body.addProperty("isAI", true);
        body.addProperty("protocol", "Version 2.0");
        sendProtocolMessage("HelloServer", body);
    }

    /**
     * Sendet {@code PlayerValues} an den Server (Lobby-Registrierung).
     *
     * @param gamePiece gewünschte Spielfigur/Farbe
     */
    private void sendPlayerValues(String gamePiece) {
        JsonObject body = new JsonObject();
        body.addProperty("name", aiName);
        body.addProperty("gamePiece", gamePiece);
        body.addProperty("birthDate", birthDate);

        sendProtocolMessage("PlayerValues", body);
        logStatus("PlayerValues gesendet mit gamePiece=" + gamePiece);
    }

    /**
     * Setzt den Ready-Status des Spielers über {@code SetStatus}.
     *
     * @param ready {@code true} wenn bereit, sonst {@code false}
     */
    public void setReady(boolean ready) {
        JsonObject body = new JsonObject();
        body.addProperty("ready", ready);
        sendProtocolMessage("SetStatus", body);
        logStatus("Ready-Status gesetzt: " + ready);
    }

    /** Timestamp für Draw-Timeout-Guards (wird in Draw-Flow genutzt). */
    private long lastDrawRequestAtMs = 0;

    /**
     * Fordert (falls nötig und erlaubt) das nächste Landscape-Tile an.
     * <p>
     * Guards:
     * <ul>
     *   <li>nur Phase 1</li>
     *   <li>nur wenn {@code isMyTurn}</li>
     *   <li>kein Pending-Request</li>
     *   <li>{@code nextDrawNumber < MAX_DRAWS_PER_PLAYER}</li>
     * </ul>
     * Zusätzlich existiert ein Timeout-Guard: kommt keine Antwort, wird der Pending-Status
     * nach kurzer Zeit zurückgesetzt und ein Retry angestoßen.
     * </p>
     *
     * @param reason Debug/Logging-Grund (z. B. "myTurn", "tileDrawn")
     */
    private void requestNextDrawIfNeeded(String reason) {
        if (currentPhase != 1) return;
        if (!isMyTurn) return;
        if (pendingDrawRequest) return;
        if (nextDrawNumber >= MAX_DRAWS_PER_PLAYER) return;

        JsonObject body = new JsonObject();
        body.addProperty("drawNumber", nextDrawNumber);
        sendProtocolMessage("DrawLandscapeTile", body);
        pendingDrawRequest = true;

        // Timeout-Guard: falls keine Antwort kommt
        scheduleDelayed(() -> {
            if (currentPhase == 1 && isMyTurn && pendingDrawRequest
                    && System.currentTimeMillis() - lastDrawRequestAtMs > 1000) {
                pendingDrawRequest = false;
                requestNextDrawIfNeeded("drawTimeout");
            }
        }, 1200);

        logStatus("Draw angefordert (drawNumber=" + nextDrawNumber + ", reason=" + reason + ")");
    }

    /**
     * Setzt zufällige Preise für die gezogenen Tiles (Phase 2).
     * <p>
     * Preconditions/Guards:
     * <ul>
     *   <li>nur Phase 2</li>
     *   <li>genau 3 Tiles in der Hand (sonst return)</li>
     *   <li>nur einmal pro Runde ({@code pricedRound})</li>
     * </ul>
     * Die konkrete Preisentscheidung kommt aus {@link #strategy}.
     * </p>
     */
    private void setRandomTilePrices() {
        if (currentPhase != 2) return;
        if (myDrawnTiles.isEmpty()) return;
        if (myDrawnTiles.size() != 3) return;

        // pro Runde nur einmal preisen, auch wenn phase=2 doppelt kommt
        if (pricedRound == currentRound) return;

        List<Integer> tileIds = new ArrayList<>(myDrawnTiles.keySet());

        AiContext ctx = buildContext();
        List<AiDecisionStrategy.PriceDecision> decisions =
                strategy.choosePrices(ctx, tileIds);

        JsonArray tilePrices = new JsonArray();
        for (AiDecisionStrategy.PriceDecision d : decisions) {
            JsonObject priceEntry = new JsonObject();
            priceEntry.addProperty("tileID", d.tileId());
            priceEntry.add("price", d.price() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(d.price()));
            priceEntry.addProperty("discard", d.discard());
            tilePrices.add(priceEntry);
        }

        JsonObject body = new JsonObject();
        body.add("tilePrices", tilePrices);
        sendProtocolMessage("SetTilePrices", body);
        logStatus("Tile Prices: " + tilePrices);

        for (AiDecisionStrategy.PriceDecision d : decisions) {
            if (d.discard()) {
                myDrawnTiles.remove(d.tileId());
            }
        }

        pricedRound = currentRound;
        pricesSetThisRound = true;
    }

    /**
     * Kauft in der BUY-Phase ein Tile oder passt.
     * <p>
     * Guards:
     * <ul>
     *   <li>nur Phase 4</li>
     *   <li>nur bei eigenem Zug</li>
     *   <li>nur einmal pro BUY-Phase</li>
     * </ul>
     * Wartet kurz auf {@code AvailableTiles}; falls Timeout, wird gepasst.
     * Die Entscheidung kommt aus {@link #strategy}.
     * </p>
     */
    private void buyRandomTileOrPass() {
        if (currentPhase != 4) return;
        if (!isMyTurn) return;

        // pro BUY-Phase nur ein Request
        if (buyActionSentThisPhase) return;

        // warten bis AvailableTiles da sind (oder Timeout)
        if (!buyOptionsReceivedThisPhase) {
            long now = System.currentTimeMillis();
            if (now - buyPhaseStartMs < BUY_WAIT_TIMEOUT_MS) {
                scheduleDelayed(this::buyRandomTileOrPass, 200);
                return;
            }
            logStatus("BUY: Timeout beim Warten auf AvailableTiles -> passe");
        }

        List<AiDecisionStrategy.BuyOption> opts = new ArrayList<>();
        for (AvailableTileInfo info : allAvailableTiles) {
            opts.add(new AiDecisionStrategy.BuyOption(info.tileId, info.ownerClientId, info.price));
        }

        AiContext ctx = buildContext();
        var choice = strategy.chooseBuyTileId(ctx, opts);

        JsonObject body = new JsonObject();

        if (choice.isPresent()) {
            body.addProperty("tileID", choice.get());
        } else {
            body.add("tileID", JsonNull.INSTANCE);
        }
        buyActionSentThisPhase = true;
        sendProtocolMessage("BuyTile", body);
    }

    private void buildRandomTile() {
        if (currentPhase != 5) return;

        List<Integer> tileIds = new ArrayList<>(myDrawnTiles.keySet());
        if (tileIds.isEmpty()) {
            logStatus("BUILD: keine Tiles mehr, sende BuildDone");
            sendProtocolMessage("BuildDone", new JsonObject());
            return;
        }

        List<int[]> legalMovesSnapshot = new ArrayList<>(legalMoves);

        AiContext ctx = buildContext();
        var choice = strategy.chooseBuildMove(ctx, tileIds, legalMovesSnapshot);
        if (choice.isEmpty()) {
            choice = new RandomDecisionStrategy(ctx.rng()).chooseBuildMove(ctx, tileIds, legalMovesSnapshot);
        }
        if (choice.isEmpty()) return;

        AiDecisionStrategy.BuildMove move = choice.get();
        JsonObject body = new JsonObject();
        body.addProperty("tileID", move.tileId());
        body.addProperty("x_position", move.x());
        body.addProperty("y_position", move.y());
        body.addProperty("rotation", move.rotation());
        sendProtocolMessage("BuildTile", body);
    }

    // Hilfsmethoden

    private String getPhaseDescription(int phase) {
        return switch (phase) {
            case 0 -> "Einkommen";
            case 1 -> "Plättchen ziehen";
            case 2 -> "Preise festlegen";
            case 3 -> "Preise offenlegen";
            case 4 -> "Plättchen kaufen";
            case 5 -> "Bauen";
            case 6 -> "Wertung";
            case 7 -> "Spielende";
            default -> "Unbekannt (" + phase + ")";
        };
    }

    // führt eine Aktion zeitverzögert in einem eigenen Scheduler-Thread aus
    private void scheduleDelayed(Runnable action, long delayMs) {
        if (actionScheduler == null || actionScheduler.isShutdown()) {
            actionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AI-ActionScheduler-" + aiName);
                t.setDaemon(true);
                return t;
            });
        }
        actionScheduler.schedule(() -> {
            try {
                action.run();
            } catch (Exception e) {
                logError("Fehler bei verzögerter Aktion: " + e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Schließt die Verbindung
     */
    public void close() {
        stopKeepAlive();
        if (actionScheduler != null) {
            actionScheduler.shutdownNow();
            actionScheduler = null;
        }
        if (webSocket != null) {
            webSocket.sendClose(1000, "AI disconnecting");
        }
        logStatus("Verbindung geschlossen");
    }

    private void applyBuildAvailableTiles(JsonArray tiles) {

        // 1) Neue Hand aus Payload aufbauen
        java.util.Map<Integer, JsonObject> freshHand = new java.util.LinkedHashMap<>();

        for (JsonElement elem : tiles) {
            JsonObject tileEntry = elem.getAsJsonObject();
            if (!tileEntry.has("tileID") || tileEntry.get("tileID").isJsonNull()) continue;

            int tileId = tileEntry.get("tileID").getAsInt();

            JsonObject tileObj;
            if (tileEntry.has("tile") && !tileEntry.get("tile").isJsonNull()) {
                tileObj = tileEntry.getAsJsonObject("tile");
            } else {
                // Platzhalter (falls Server "tile" mal nicht mitsendet)
                tileObj = new JsonObject();
                tileObj.addProperty("id", tileId);
            }

            freshHand.put(tileId, tileObj);
        }

        // 2) Hand ersetzen
        myDrawnTiles.clear();
        myDrawnTiles.putAll(freshHand);

        if (myDrawnTiles.isEmpty() && currentPhase == 5) {
            sendProtocolMessage("BuildDone", new JsonObject());
            return;
        }

        logStatus("BUILD-Phase: Tiles zum Bauen erhalten (server-truth): " + myDrawnTiles.size()
                + " -> " + myDrawnTiles.keySet());

        // 3) Build-Loop: nur starten, wenn wirklich Tiles da sind.
        if (myDrawnTiles.isEmpty()) {
            buildScheduledThisPhase = false; // optional: damit nächste Runde sauber neu startet
            logStatus("Keine Tiles zum Bauen - warte auf Rundenwechsel");
            return;
        }

        // 4) Nur einmal pro BUILD-Phase planen
        if (!buildScheduledThisPhase) {
            buildScheduledThisPhase = true;
            scheduleBuild(300);
        }
    }

    private void collectIncome() {
        if (currentPhase != 0) return;
        sendProtocolMessage("CollectIncome", new JsonObject());
    }

    private void scheduleBuild(long delayMs) {
        long epochAtSchedule = buildTasks;
        scheduleDelayed(() -> {
            // wenn inzwischen eine neue BUILD-Phase gestartet wurde oder Phase nicht mehr BUILD ist -> abbrechen
            if (epochAtSchedule != buildTasks) return;
            if (currentPhase != 5) return;
            buildRandomTile();
        }, delayMs);
    }

    private AiContext buildContext() {
        return new AiContext(
                myClientId,
                currentRound,
                currentPhase,
                myCoins,
                scoringView,
                gameStateView,
                tileCatalog,
                random
        );
    }

    // Main-Methode zum Testen

    /**
     * Startet die KI und verbindet sie zu einem existierenden Raum oder erstellt
     * einen neuen.
     * 
     * Verwendung:
     * java RandomAIClient [--auto | roomCode]
     * 
     * Optionen:
     * --auto Automatisch einem Raum mit 1 Spieler beitreten, oder neuen erstellen
     * roomCode Zu einem bestimmten Raum verbinden
     * (keine) Neuen Raum erstellen
     */
    public static void main(String[] args) {
        String aiName = "RandomBot_" + System.currentTimeMillis() % 1000;
        AiClient ai = new AiClient(aiName);

        ai.setStatusCallback(new AIStatusCallback() {
            @Override
            public void onStatusChange(String message) {
                // Wird bereits von logStatus ausgegeben
            }

            @Override
            public void onError(String error) {
                // Wird bereits von logError ausgegeben
            }
        });

        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("--auto")) {
                // Automatisch einem Raum beitreten oder neuen erstellen
                String roomCode = ai.findAndJoinOrCreateRoom();
                log.info("AI verbunden mit Raum: {}", roomCode);
            } else if (args.length > 0) {
                // Zu existierendem Raum verbinden
                ai.connectToRoom(args[0].toLowerCase());
            } else {
                // Neuen Raum erstellen
                String roomCode = ai.createRoomAndConnect();
                log.info("AI wartet auf andere Spieler im Raum: {}", roomCode);
            }

            // Warten bis Enter gedrückt wird
            log.info("Warte  auf Enter zum Beenden");
            System.in.read();
            ai.close();

        } catch (Exception e) {
            log.error("Fehler beim Starten der AI: {}", e.getMessage());
            log.error("Unerwarteter Fehler", e);
        }
    }

    private Set<Integer> parseActiveScorings() {
        Set<Integer> out = new HashSet<>();
        for (String s : scoringView.activeScorings()) {
            try {
                out.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static Position neighborPosition(int x, int y, TileRoadSide side) {
        return switch (side) {
            case TOP -> new Position(x, y + 1);
            case DOWN -> new Position(x, y - 1);
            case RIGHT -> new Position(x + 1, y);
            case LEFT -> new Position(x - 1, y);
        };
    }

    private static TileRoadSide opposite(TileRoadSide side) {
        return switch (side) {
            case TOP -> TileRoadSide.DOWN;
            case DOWN -> TileRoadSide.TOP;
            case RIGHT -> TileRoadSide.LEFT;
            case LEFT -> TileRoadSide.RIGHT;
        };
    }

    private static boolean hasRoadAtSide(Tile tile, int rotation, TileRoadSide side) {
        if (tile.getRoads().isEmpty()) {
            return false;
        }
        for (List<TileRoadSide> road : tile.getRoads()) {
            for (TileRoadSide s : road) {
                if (rotateSide(s, rotation) == side) {
                    return true;
                }
            }
        }
        return false;
    }

    private static TileRoadSide rotateSide(TileRoadSide side, int rotationDegrees) {
        int steps = ((rotationDegrees % 360) + 360) % 360 / 90;
        TileRoadSide s = side;
        for (int i = 0; i < steps; i++) {
            s = switch (s) {
                case TOP -> TileRoadSide.RIGHT;
                case RIGHT -> TileRoadSide.DOWN;
                case DOWN -> TileRoadSide.LEFT;
                case LEFT -> TileRoadSide.TOP;
            };
        }
        return s;
    }

    private static int count2x2Squares(Map<Position, PlacedTile> tiles, int x, int y) {
        int squares = 0;
        Position right = new Position(x + 1, y);
        Position left = new Position(x - 1, y);
        Position up = new Position(x, y + 1);
        Position down = new Position(x, y - 1);

        if (tiles.containsKey(right) && tiles.containsKey(up)
                && tiles.containsKey(new Position(x + 1, y + 1))) squares++;
        if (tiles.containsKey(left) && tiles.containsKey(up)
                && tiles.containsKey(new Position(x - 1, y + 1))) squares++;
        if (tiles.containsKey(right) && tiles.containsKey(down)
                && tiles.containsKey(new Position(x + 1, y - 1))) squares++;
        if (tiles.containsKey(left) && tiles.containsKey(down)
                && tiles.containsKey(new Position(x - 1, y - 1))) squares++;

        return squares;
    }

    private static int verticalRunLength(Map<Position, PlacedTile> tiles, int x, int y) {
        int run = 1;
        for (int dy = 1; tiles.containsKey(new Position(x, y + dy)); dy++) {
            run++;
        }
        for (int dy = 1; tiles.containsKey(new Position(x, y - dy)); dy++) {
            run++;
        }
        return run;
    }

    private static boolean isAdjacentToFarm(Map<Position, PlacedTile> tiles, int x, int y) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                PlacedTile pt = tiles.get(new Position(x + dx, y + dy));
                if (pt != null && pt.tile().hasElement(TileElement.FARM)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countAdjacentLivestock(Map<Position, PlacedTile> tiles, int x, int y) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                PlacedTile pt = tiles.get(new Position(x + dx, y + dy));
                if (pt == null) continue;
                for (TileElement e : pt.tile().getElements()) {
                    if (e == TileElement.SHEEP || e == TileElement.OX) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static boolean connectsToCastleByRoad(Set<Position> connected,
                                                  Map<Position, PlacedTile> tiles,
                                                  Tile tile,
                                                  int x,
                                                  int y,
                                                  int rotation) {
        for (TileRoadSide side : TileRoadSide.values()) {
            Position np = neighborPosition(x, y, side);
            if (!connected.contains(np)) {
                continue;
            }
            PlacedTile neighbor = tiles.get(np);
            if (neighbor == null) {
                continue;
            }
            boolean tileRoad = hasRoadAtSide(tile, rotation, side);
            boolean neighborRoad = hasRoadAtSide(
                    neighbor.tile(), neighbor.rotationDegrees(), opposite(side));
            if (tileRoad && neighborRoad) {
                return true;
            }
        }
        return false;
    }
}
