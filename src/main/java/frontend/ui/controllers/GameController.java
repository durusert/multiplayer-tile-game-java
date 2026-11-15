package frontend.ui.controllers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import frontend.client.WebSocketChatClient;
import frontend.ui.components.TileView;
import frontend.ui.components.ScoringBoardWhileGame;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.beans.binding.Bindings;
import javafx.animation.*;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.animation.ScaleTransition;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.geometry.Point2D;
import javafx.util.Duration;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Slider;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.stage.Modality;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import frontend.ui.audio.BackgroundMusic;
import frontend.ui.audio.SoundEffects;

/**
 * Hauptcontroller fuer den Spielbildschirm von Isle of Skye.
 * 
 * Diese Klasse verwaltet die gesamte Spiellogik auf der Client-Seite, einschliesslich:
 * - Anzeige und Interaktion mit dem Spielbrett (Territorium des Spielers)
 * - Verarbeitung der verschiedenen Spielphasen (DRAW, DISCARD, BUY, BUILD, SCORING)
 * - Kommunikation mit dem Server ueber WebSocket-Nachrichten
 * - Verwaltung der Handkarten, des Marktes und der Punktewertung
 * - Chat-Funktionalitaet (global und private Nachrichten)
 * - Drag-and-Drop-Platzierung von Tiles auf dem Spielbrett
 * 
 * Der Controller ist an GameV2.fxml gebunden und wird nach dem Lobby-Screen geladen,
 * sobald der Server das Spiel startet.
 * 
 * Spielphasen-Uebersicht:
 * - DRAW: Spieler ziehen neue Tiles vom Stapel
 * - DISCARD: Spieler setzen Preise fuer ihre Tiles oder verwerfen eines
 * - BUY: Spieler kaufen reihum Tiles von anderen Spielern
 * - BUILD: Spieler platzieren gekaufte/behaltene Tiles auf ihrem Territorium
 * - SCORING: Punkte werden basierend auf den aktiven Wertungsplaettchen berechnet
 * 
 * @see WebSocketChatClient
 * @see TileView
 * @see ScoringBoardWhileGame
 */
public class GameController {

    private static final Logger log = LogManager.getLogger(GameController.class);

    /** Minimale Koordinate des Spielbretts (x und y). Definiert die linke/untere Grenze. */
    private static final int BOARD_MIN = -10;
    
    /** Maximale Koordinate des Spielbretts (x und y). Definiert die rechte/obere Grenze. */
    private static final int BOARD_MAX = 10;

    // ==================== TILE- UND ZELLGROESSE ====================
    
    /** Groesse eines Tiles in Pixeln. Kann per System Property oder Umgebungsvariable ueberschrieben werden. */
    private static final int TILE_SIZE = resolveTileSize();
    
    /** Gesamtgroesse einer Zelle inkl. Padding (Tile + 8px Rand). */
    private static final int CELL_SIZE = TILE_SIZE + 8;
    
    /** Pfad zum Spielbrett-Hintergrundbild fuer 2-4 Spieler. */
    private static final String BOARD_2_4_PATH = "/images/board-2-4.png";
    
    /** Pfad zum Spielbrett-Hintergrundbild fuer 5 Spieler. */
    private static final String BOARD_5_PATH = "/images/board-5.png";

    // ==================== MUENZEN-ANIMATION ====================
    
    /** Aktuell angezeigte Muenzanzahl (fuer animiertes Hochzaehlen). */
    private Integer displayedCoins = null;
    
    /** Timeline fuer das animierte Zaehlen der Muenzen. */
    private Timeline coinsCountTimeline;
    
    /** Transition fuer das "+X" Delta-Label bei Muenzaenderungen. */
    private ParallelTransition coinsDeltaTransition;

    // ==================== SCORING ANZEIGE-STEUERUNG ====================
    
    /** Zeitpunkt bis zu dem die Scoring-Anzeige "sticky" bleibt. */
    private long scoringStickyUntilMillis = 0;
    
    /** Dauer in ms wie lange die Scoring-Anzeige nach Aktualisierung sichtbar bleibt. */
    private static final long SCORING_STICKY_MS = 5000;
    
    /** Timer zum Zuruecksetzen des Scoring-Overlays nach Ablauf der Sticky-Zeit. */
    private PauseTransition scoringStickyRefresh;
    
    // ==================== WERTUNGSPLAETTCHEN-ZEITPLAENE ====================
    
    /**
     * Wertungsplan fuer 2-4 Spieler.
     * Zeigt welche Wertungsplaettchen (A-D) in welcher Runde aktiv sind.
     * Index = Runde-1, Werte = aktive Slots.
     */
    private static final char[][] SCHEDULE_TWO_TO_FOUR = {
            { 'A' },
            { 'B' },
            { 'A', 'C' },
            { 'B', 'D' },
            { 'D', 'A', 'C' },
            { 'D', 'B', 'C' }
    };
    
    /**
     * Wertungsplan fuer 5 Spieler.
     * Kuerzeres Spiel mit 5 Runden statt 6.
     */
    private static final char[][] SCHEDULE_FIVE_PLAYERS = {
            {'A', 'B'},
            {'A', 'C'},
            {'B', 'D'},
            {'A', 'D', 'C'},
            {'B', 'D', 'C'}
    };
    
    /**
     * Beschreibungen der 16 verschiedenen Wertungsplaettchen.
     * Key = Scoring-ID (0-15), Value = lesbare Beschreibung fuer Tooltips.
     */
    private static final Map<Integer, String> SCORING_DESCRIPTIONS = Map.ofEntries(
            Map.entry(0, "Score 2 points for each 2x2 square of landscape tiles.\nA tile may be part of multiple squares."),
            Map.entry(1, "Score 1 point for each completed area in your territory\n(fields, water, or mountains; roads do not count)."),
            Map.entry(2, "Score 1 point for each sheep in your territory."),
            Map.entry(3, "Score 3 points for each completed area consisting of at least 3 tiles."),
            Map.entry(4, "Score 1 / 3 / 6 points for each mountain area with 1 / 2 / 3 brochs.\nMore than 3 brochs still score 6 points maximum."),
            Map.entry(5, "Score 5 points for each complete set of\n1 broch, 1 farm, and 1 lighthouse.\nEach building may only be used in one set."),
            Map.entry(6, "The player with the most whisky barrel tiles scores 5 points.\nThe player with the second most scores 2 points.\nYou must have at least 1 whisky barrel to score."),
            Map.entry(7, "The player with the most ships scores 5 points.\nThe player with the second most scores 2 points.\nYou must have at least 1 ship to score."),
            Map.entry(8, "Score 1 point for each sheep and each cattle that is\northogonally or diagonally adjacent to a farm, or\nlocated directly on a farm tile."),
            Map.entry(9, "The player with the most gold scores 5 points.\nThe player with the second most scores 2 points.\nYou must have at least 1 gold to score."),
            Map.entry(10, "Score 2 points for each cattle that is connected to your castle via roads."),
            Map.entry(11, "Score 3 points for each water area that contains\nat least 1 ship and at least 1 adjacent lighthouse."),
            Map.entry(12, "Score 1 point for each tile that is connected to your castle via roads."),
            Map.entry(13, "Score 3 points for each vertical line of at least 3 contiguous tiles.\nEach vertical line is scored once, regardless of length."),
            Map.entry(14, "Score 2 points per tile in your largest completed water area."),
            Map.entry(15, "Score 2 points for each completed mountain area.")
    );

    private final java.util.Map<Integer, String> playerNameByClientId = new java.util.HashMap<>();
    private final JsonObject lastGameOverPayload = null;
    private JsonObject lastScoringPayload = null;
    private PauseTransition scoringToGameStartDelay;



    /**
     * Ermittelt die Tile-Groesse aus System Property oder Umgebungsvariable.
     * Fallback ist 120 Pixel wenn keine Konfiguration gefunden wird.
     * 
     * @return Die zu verwendende Tile-Groesse in Pixeln
     */
    private static int resolveTileSize() {
        Integer fromProperty = Integer.getInteger("isle.tileSize");
        if (fromProperty != null && fromProperty > 0) return fromProperty;

        String fromEnv = System.getenv("ISLE_TILE_SIZE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            try {
                int v = Integer.parseInt(fromEnv.trim());
                if (v > 0) return v;
            } catch (Exception ignored) {
            }
        }

        return 120;
    }

    // ==================== FXML UI-ELEMENTE: STATUS-ANZEIGE ====================
    
    /** Zeigt die aktuelle Rundennummer an (z.B. "Runde 3/6"). */
    @FXML private Label roundLabel;
    
    /** Zeigt die aktuelle Spielphase an (DRAW, DISCARD, BUY, BUILD, SCORING). */
    @FXML private Label phaseLabel;
    
    /** Zeigt den aktuellen Muenzstand des Spielers an. */
    @FXML private Label coinsLabel;
    
    /** Temporaeres Label fuer animierte Muenzaenderungen ("+5" oder "-3"). */
    @FXML private Label coinsDeltaLabel;
    
    /** Zeigt den Namen des aktuell aktiven Spielers an. */
    @FXML private Label activePlayerLabel;
    
    /** Statustext zur aktuellen Phase (z.B. "Warten auf andere Spieler..."). */
    @FXML private Label phaseStatusLabel;
    
    /** Liste aller Spieler im Spiel mit Punktestand und Muenzen. */
    @FXML private ListView<String> playersList;

    // ==================== FXML UI-ELEMENTE: SPIELBRETT ====================
    
    /** Scrollbarer Container fuer das Spielbrett-Grid. */
    @FXML private ScrollPane boardScrollPane;
    
    /** Root-Container des Spielbretts fuer Zoom und Pan. */
    @FXML private StackPane boardRoot;
    
    /** Allgemeines Status-Label fuer verschiedene Meldungen. */
    @FXML private Label statusLabel;
    
    /** Button zum Verlassen des Spiels. */
    @FXML private Button leaveButton;
    
    /** Button zum Beenden der BUILD-Phase. */
    @FXML private Button finishBuildButton;

    // ==================== FXML UI-ELEMENTE: EINSTELLUNGEN ====================
    
    /** Button zum Oeffnen des Einstellungs-Panels. */
    @FXML private Button settingsButton;
    
    /** Container fuer Einstellungs-Optionen (Lautstaerke etc.). */
    @FXML private HBox settingsPanel;
    
    /** Slider zur Regelung der Musiklautstaerke. */
    @FXML private Slider musicVolumeSlider;
    
    /** Anzeige des aktuellen Lautstaerke-Wertes in Prozent. */
    @FXML private Label musicVolumeValue;

    // ==================== FXML UI-ELEMENTE: HANDKARTEN ====================
    
    /** Titel-Label fuer den Handkarten-Bereich. */
    @FXML private Label handTitleLabel;
    
    /** Container fuer die Handkarten-Anzeige. */
    @FXML private FlowPane handPane;
    
    /** Sekundaerer Container fuer zusaetzliche Handkarten. */
    @FXML private FlowPane handPane2;
    
    /** Button zum Ziehen neuer Tiles in der DRAW-Phase. */
    @FXML private Button drawButton;
    
    /** Button zum Abschicken der Preise in der DISCARD-Phase. */
    @FXML private Button submitPricesButton;
    
    /** Button zum Anzeigen des vollstaendigen Spielbretts. */
    @FXML private Button gameboardButton;
    
    /** Button zum Schliessen der Spielbrett-Ansicht. */
    @FXML private Button closeGameboardButton;

    // ==================== FXML UI-ELEMENTE: MARKT ====================
    
    /** Titel-Label fuer den Markt-Bereich. */
    @FXML private Label marketTitleLabel;
    
    /** Container fuer die Markt-Angebote der anderen Spieler. */
    @FXML private FlowPane marketPane;
    
    /** Button zum Kaufen eines ausgewaehlten Angebots. */
    @FXML private Button buyButton;
    
    /** Button zum Passen in der BUY-Phase. */
    @FXML private Button passButton;
    
    /** Container fuer die Buttons in der BUY-Phase. */
    @FXML private VBox buyActionButtonsContainer;
    
    /** Button zum Beenden der BUY-Phase und Weitergehen. */
    @FXML private Button continueButton;

    // ==================== FXML UI-ELEMENTE: WERTUNG ====================
    
    /** Container fuer die Wertungsplaettchen-Anzeige. */
    @FXML private FlowPane scoringTilesPane;
    
    /** Tab-Pane am unteren Bildschirmrand (optional, fuer aeltere Layouts). */
    @FXML private TabPane bottomTabs;

    // ==================== FXML UI-ELEMENTE: CHAT ====================
    
    /** Textbereich fuer Chat-Nachrichten. */
    @FXML private TextArea chatArea;
    
    /** Eingabefeld fuer neue Chat-Nachrichten. */
    @FXML private TextField messageField;
    
    /** Button zum Absenden einer Chat-Nachricht. */
    @FXML private Button sendButton;
    
    // ==================== FXML UI-ELEMENTE: NAVIGATION ====================
    
    /** Button zum Zentrieren des Spielbretts auf das eigene Territorium. */
    @FXML private Button recenterBoardButton;
    
    /** Das eigentliche Territorium-Grid mit allen platzierten Tiles. */
    @FXML private GridPane territoryGrid;
    
    /** Button um zum eigenen Territorium zurueckzukehren. */
    @FXML private Button backToMyTerritoryButton;
    
    /** Das Haupt-Grid des Spielbretts. */
    @FXML private GridPane boardGrid;
    
    // ==================== FXML UI-ELEMENTE: SCORING-OVERLAY ====================
    
    /** Liste der Punktestaende aller Spieler. */
    @FXML private VBox scoringList;
    
    /** Haupt-Dashboard-Layout des Spiels. */
    @FXML private BorderPane gameDashboard;
    
    /** Grid fuer die Wertungsplaettchen-Vorschau. */
    @FXML private GridPane scoringPreviewGrid;
    
    /** Container fuer Wertungsplaettchen A. */
    @FXML private StackPane scoringPreviewSlotA;
    
    /** Container fuer Wertungsplaettchen B. */
    @FXML private StackPane scoringPreviewSlotB;
    
    /** Container fuer Wertungsplaettchen C. */
    @FXML private StackPane scoringPreviewSlotC;
    
    /** Container fuer Wertungsplaettchen D. */
    @FXML private StackPane scoringPreviewSlotD;
    
    /** Bild des Wertungsplaettchens A. */
    @FXML private ImageView scoringPreviewTileA;
    
    /** Bild des Wertungsplaettchens B. */
    @FXML private ImageView scoringPreviewTileB;
    
    /** Bild des Wertungsplaettchens C. */
    @FXML private ImageView scoringPreviewTileC;
    
    /** Bild des Wertungsplaettchens D. */
    @FXML private ImageView scoringPreviewTileD;
    
    /** Overlay fuer das grosse Wertungsbrett. */
    @FXML private StackPane scoringBoardOverlay;
    
    /** Hintergrundbild des Wertungsbretts. */
    @FXML private ImageView scoringBoardImage;
    
    /** Pane fuer Elemente auf dem Wertungsbrett-Overlay. */
    @FXML private Pane scoringBoardOverlayPane;
    
    /** Container fuer die Wertungsplaettchen auf dem grossen Brett. */
    @FXML private HBox scoringBoardTilesBox;
    
    /** Wertungsplaettchen A auf dem grossen Brett. */
    @FXML private ImageView scoringBoardTileA;
    
    /** Wertungsplaettchen B auf dem grossen Brett. */
    @FXML private ImageView scoringBoardTileB;
    
    /** Wertungsplaettchen C auf dem grossen Brett. */
    @FXML private ImageView scoringBoardTileC;
    
    /** Wertungsplaettchen D auf dem grossen Brett. */
    @FXML private ImageView scoringBoardTileD;
    
    /** Container fuer die Rundenmarker. */
    @FXML private HBox scoringBoardRoundPlatesBox;
    
    /** Kreismarker fuer die aktuelle Runde. */
    @FXML private javafx.scene.shape.Circle scoringBoardRoundMarker;
    
    /** Haupt-Layout-Container des Spielbildschirms. */
    @FXML private BorderPane mainLayout;
    
    /** Untere Aktionsleiste mit Buttons. */
    @FXML private HBox bottomBar;
    
    /** Obere Statusleiste. */
    @FXML private HBox topBar;


    // ==================== SPIELER- UND CHAT-VERWALTUNG ====================
    
    /** Liste der Spielernamen fuer die Seitenleiste (Observable fuer automatische UI-Updates). */
    private final ObservableList<String> playerNames = FXCollections.observableArrayList();

    /** Liste der Chat-Threads (Global-Chat plus private Nachrichten). */
    private final ObservableList<String> chatThreads = FXCollections.observableArrayList();
    
    /** Aktueller Runden-Index (0-basiert). */
    private int currentRoundIndex = -1;
    
    /** Zuletzt bekannte Spieleranzahl (fuer Wertungsplan-Auswahl). */
    private Integer lastKnownPlayerCount = null;
    
    /** Referenz zum Wertungsbrett-Widget. */
    private ScoringBoardWhileGame scoringBoardWhileGame;

    // ==================== DATENSTRUKTUREN ====================
    
    /** JSON-Parser fuer Server-Kommunikation. */
    private final Gson gson = new Gson();
    
    /** Mapping von Tile-ID zu Tile-Definition (JSON). Laedt alle Tiles beim Start. */
    private final Map<Integer, JsonObject> tileDefinitions = new LinkedHashMap<>();
    
    /** Mapping von Brett-Koordinate zu UI-Zelle. Fuer schnellen Zugriff auf Zellen. */
    private final Map<Coord, StackPane> boardCells = new LinkedHashMap<>();
    
    /** Mapping von Brett-Koordinate zu platziertem Tile. Speichert den Zustand des eigenen Territoriums. */
    private final Map<Coord, PlacedTileState> placedTiles = new LinkedHashMap<>();
    
    /** Territorien aller Spieler (Client-ID -> Koordinaten -> Tile-Zustand). Fuer Anzeige fremder Territorien. */
    private final java.util.Map<Integer, java.util.Map<Coord, PlacedTileState>> placedTilesByClientId = new java.util.HashMap<>();
    
    /** Aktuell hervorgehobene Zellen (z.B. gueltige Platzierungspositionen). */
    private final java.util.Set<Coord> highlightedCells = new java.util.HashSet<>();
    
    /** Konstante fuer den globalen Chat-Thread. */
    private static final String GLOBAL_THREAD = "All";
    
    /** Chat-Nachrichten pro Thread (Thread-Name -> Liste von Nachrichten). */
    private final java.util.Map<String, java.util.List<String>> threads = new java.util.HashMap<>();
    
    /** Anzahl ungelesener Nachrichten pro Spieler (fuer DM-Benachrichtigungen). */
    private final java.util.Map<String, Integer> unreadByPlayer = new java.util.HashMap<>();
    
    /** Aktuell aktiver Chat-Thread. */
    private String activeThread = GLOBAL_THREAD;
    
    /** Name des letzten Absenders einer privaten Nachricht (fuer schnelle Antwort). */
    private String lastPrivateSenderName = null;

    /** Mapping von Anzeigename (ohne " (you)") zu Client-ID fuer UI-Styling. */
    private final java.util.Map<String, Integer> clientIdByDisplayName = new java.util.HashMap<>();

    // ==================== DISCARD-PHASE STEUERUNG ====================
    
    /** Toggle-Gruppe fuer die Auswahl des zu verwerfenden Tiles. */
    private final ToggleGroup discardToggle = new ToggleGroup();
    
    /** Mapping von Tile-ID zu zugehoeriger Preis-Karte in der DISCARD-Phase. */
    private final Map<Integer, PriceTileCard> priceCards = new LinkedHashMap<>();
    /** Mapping von Tile-ID zu zugehoeriger Hand-Karte. */
    private final Map<Integer, TileCard> handCards = new LinkedHashMap<>();
    
    /** Mapping von Tile-ID zu Markt-Angebot-Karte in der BUY-Phase. */
    private final Map<Integer, MarketOfferCard> marketCards = new LinkedHashMap<>();

    // ==================== NETZWERK UND SESSION ====================
    
    /** WebSocket-Client fuer die Server-Kommunikation. */
    private WebSocketChatClient client;
    
    /** Listener fuer eingehende Server-Nachrichten. */
    private Consumer<String> inboundListener;
    
    /** Benutzername des lokalen Spielers. */
    private String username;
    
    /** Client-ID des lokalen Spielers (vom Server zugewiesen). */
    private int myClientId = -1;
    
    /** ID des angezeigten Territoriums (null = eigenes, sonst fremdes Territorium). */
    private Integer selectedTerritoryClientId = null;
    
    /** Aktuelle Spielphase (DRAW, DISCARD, BUY, BUILD, SCORING, NONE). */
    private String currentPhase = "NONE";
    
    /** Client-ID des Spielers der aktuell am Zug ist. */
    private Integer currentTurnClientId;
    
    /** Zwischengespeicherte verfuegbare Tiles fuer die BUY-Phase. */
    private JsonArray pendingBuyAvailableTiles;
    
    /** Zwischengespeicherte verfuegbare Tiles fuer die BUILD-Phase. */
    private JsonArray pendingBuildAvailableTiles;
    
    // ==================== PHASEN-TRACKING ====================
    
    /** Set der Spieler die ihre Aktion in der aktuellen Phase abgeschlossen haben. */
    private final java.util.Set<Integer> playersReadyInPhase = new java.util.HashSet<>();
    
    /** Zwischenspeicher fuer gezogene Tiles waehrend DRAW-Phase. */
    private final java.util.List<JsonObject> pendingDrawnTilePayloads = new java.util.ArrayList<>();
    
    /** Anzahl der angeforderten Tile-Ziehungen. */
    private int drawsRequested = 0;
    
    /** Flag ob der Draw-Button bereits gedrueckt wurde. */
    private boolean drawButtonPressed = false;
    
    /** ID des zum Verwerfen ausgewaehlten Tiles in DISCARD-Phase. */
    private Integer pendingDiscardTileId = null;
    
    // ==================== TILE-AUSWAHL UND PLATZIERUNG ====================
    
    /** Aktuell ausgewaehlte Hand-Tile-ID fuer Platzierung. */
    private Integer selectedHandTileId;
    
    /** Rotation des ausgewaehlten Tiles (0, 90, 180, 270 Grad). */
    private int selectedRotation = 0;
    
    /** Aktuell ausgewaehlte Markt-Tile-ID fuer Kauf. */
    private Integer selectedMarketTileId;
    
    /** Pending Placements: Tile-ID -> Vorschau-Info fuer geplante Platzierungen. */
    private final java.util.Map<Integer, PreviewInfo> pendingPlacements = new java.util.HashMap<>();
    
    /** Flag ob BUILD-Phase gerade finalisiert wird. */
    private boolean buildFinalizing = false;
    
    /** Flag ob das Board bereits einmal zentriert wurde. */
    private boolean boardCenteredOnce = false;
    
    // ==================== DRAG & DROP ====================
    
    /** Flag ob gerade ein Drag-Vorgang aktiv ist. */
    private boolean dragActive = false;
    
    /** Flag ob das Tile bereits gedroppt wurde. */
    private boolean dragDropped = false;
    
    /** Ziel-Koordinate des Drag-Vorgangs. */
    private Coord dragCoord;
    
    /** TileView das gerade gedraggt wird. */
    private TileView dragTileView;
    
    /** Overlay-Pane fuer Drag-Operationen. */
    private javafx.scene.layout.Pane dragOverlay;
    
    /** Container fuer Rotations-Buttons beim Drag. */
    private javafx.scene.layout.HBox dragRotateButtons;
    
    /** Button zum Linksdrehen beim Drag. */
    private Button dragRotateLeftButton;
    
    /** Button zum Rechtsdrehen beim Drag. */
    private Button dragRotateRightButton;
    
    /** Button zum Bestaetigen der Platzierung beim Drag. */
    private Button dragConfirmButton;
    
    /** Letzte Mausposition X (fuer Drag-Berechnung). */
    private double lastMouseSceneX;
    
    /** Letzte Mausposition Y (fuer Drag-Berechnung). */
    private double lastMouseSceneY;
    
    /** Flag ob eine Mausposition bekannt ist. */
    private boolean hasMousePosition = false;
    
    // ==================== MARKT UND ANGEBOTE ====================
    
    /** Aufgedeckte Angebote anderer Spieler (Tile-ID -> Angebot mit Preis). */
    private final Map<Integer, Offer> revealedOffers = new LinkedHashMap<>();
    
    /** Globaler Cache fuer Angebote (thread-safe fuer Updates aus verschiedenen Quellen). */
    private static final Map<Integer, Offer> GLOBAL_OFFER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    
    /** Callback zum Triggern von UI-Updates aus statischem Kontext. */
    private static Runnable UI_UPDATE_TRIGGER;
    
    /** Globale Tracking-Variable fuer den aktuellen Spielerzug. */
    private static int GLOBAL_CURRENT_TURN_ID = -1;
    
    /** Liste der in der aktuellen Runde aktiven Wertungs-IDs. */
    private final java.util.List<Integer> activeScoringIds = new java.util.ArrayList<>();

    /** Typ der zuletzt empfangenen Server-Nachricht (fuer Debugging). */
    private String lastMessageType;

    // ==================== SCORING UND SPIELENDE ====================
    
    /** Globaler Cache fuer das letzte Scoring-Payload (fuer spaetere Referenz). */
    private static volatile JsonObject GLOBAL_LAST_SCORING_PAYLOAD = null;
    
    /** Name des Gewinners (nach Spielende). */
    private static volatile String GLOBAL_LAST_WINNER_NAME = null;

    /** Letzte bekannte Gesamtpunktzahl pro Client-ID (fuer Delta-Berechnung). */
    private final java.util.Map<Integer, Integer> lastTotalVpByClient = new java.util.HashMap<>();
    
    /** Letzte Punktzahl pro Anzeigename (fuer Spielerkarten-Updates). */
    private final java.util.Map<String, Integer> lastScoreByDisplayName = new java.util.HashMap<>();
    
    /** Letzte Runde fuer die GameStart angezeigt wurde (verhindert Doppel-Anzeige). */
    private int lastGameStartShownRound = -1;
    
    /** Set der Tile-IDs die der Spieler behalten hat (nicht verworfen) in DISCARD. */
    private final java.util.Set<Integer> keptNonDiscardTileIds = new java.util.HashSet<>();


    /**
     * Standard-Konstruktor.
     * Initialisiert den globalen Chat-Thread.
     */
    public GameController() {
        threads.put(GLOBAL_THREAD, new java.util.ArrayList<>());
    }

    // ==================== INNERE DATENKLASSEN ====================
    
    /**
     * Immutable Record fuer 2D-Koordinaten auf dem Spielbrett.
     * Verwendet als Key in Maps fuer Zellen und platzierte Tiles.
     */
    private record Coord(int x, int y) {}

    /**
     * Speichert Vorschau-Informationen fuer eine geplante Tile-Platzierung.
     * Wird verwendet bevor die Platzierung an den Server gesendet wird.
     */
    private record PreviewInfo(int tileId, int x, int y, int rotation, StackPane cell, TileView viewNode) {}
    
    /**
     * Speichert den Zustand eines platzierten Tiles inkl. Rotation.
     * Wird fuer die Darstellung des Territoriums verwendet.
     */
    private record PlacedTileState(JsonObject tileJson, int rotationDegrees) {}
    
    /**
     * Repreasentiert ein Angebot im Markt mit Verkaeufer und Preis.
     */
    private record Offer(int sellerClientId, int price) {}

    /**
     * Enum fuer die vier Seiten eines Tiles (fuer Kantenpruefung beim Platzieren).
     */
    private enum Side {
        TOP,
        RIGHT,
        DOWN,
        LEFT
    }

    /**
     * Basis-Klasse fuer eine Tile-Karte in der Hand oder im Markt.
     * Speichert Tile-ID, JSON-Definition und die View-Komponente.
     */
    private static class TileCard {
        final int tileId;
        final JsonObject tileJson;
        final TileView view;

        private TileCard(int tileId, JsonObject tileJson, TileView view) {
            this.tileId = tileId;
            this.tileJson = tileJson;
            this.view = view;
        }
    }

    /**
     * Erweiterte Tile-Karte fuer die DISCARD-Phase.
     * Enthaelt zusaetzlich einen RadioButton zum Verwerfen und ein Preisfeld.
     */
    private static final class PriceTileCard extends TileCard {
        private final RadioButton discardRadio;
        private final TextField priceField;

        private PriceTileCard(int tileId, JsonObject tileJson, TileView view, RadioButton discardRadio, TextField priceField) {
            super(tileId, tileJson, view);
            this.discardRadio = discardRadio;
            this.priceField = priceField;
        }
    }

    /**
     * Karte fuer ein Markt-Angebot in der BUY-Phase.
     * Zeigt Tile, Verkaeufer, Preis und einen Kauf-Button.
     */
    private static final class MarketOfferCard {
        private final int tileId;
        private final JsonObject tileJson;
        private final int sellerClientId;
        private final int price;
        private final TileView view;
        private final javafx.scene.Node container;
        private final Button buyButton;

        private MarketOfferCard(int tileId, JsonObject tileJson, int sellerClientId, int price, TileView view, javafx.scene.Node container, Button buyButton) {
            this.tileId = tileId;
            this.tileJson = tileJson;
            this.sellerClientId = sellerClientId;
            this.price = price;
            this.view = view;
            this.container = container;
            this.buyButton = buyButton;
        }
    }

    // ==================== INITIALISIERUNG ====================
    
    /**
     * FXML-Initialisierungsmethode. Wird automatisch nach dem Laden der FXML aufgerufen.
     * 
     * Fuehrt folgende Schritte aus:
     * 1. Startet die Hintergrundmusik und laedt Sound-Effekte vor
     * 2. Richtet die Lautstaerke-Steuerung ein
     * 3. Bindet Layout-Properties fuer responsive Darstellung
     * 4. Laedt alle Tile-Definitionen aus JSON
     * 5. Initialisiert das Spielbrett-Grid mit allen Zellen
     * 6. Richtet Drag-and-Drop fuer Tile-Platzierung ein
     * 7. Konfiguriert Scoring-Overlay und Vorschau
     * 8. Initialisiert die Spielerliste mit Custom-CellFactory
     * 9. Registriert Event-Handler fuer Buttons und Chat
     */
    @FXML
    private void initialize() {
        log.debug("territoryGrid={} boardGrid={}", territoryGrid, boardGrid);
        log.debug("scoringList={}", scoringList);
        log.info("[UI] GameController initialized: bottomTabs={}, handPane={}, marketPane={}",
                bottomTabs != null, handPane != null, marketPane != null);

        // Hintergrundmusik starten - verwendet den globalen BackgroundMusic-Helper
        BackgroundMusic.playLoop("/music/tunetank-medieval-background-348171.mp3");

        // Sound-Effekte vorladen um Verzoegerung beim ersten Abspielen zu vermeiden
        SoundEffects.warmUp();

        // Generische Klick-Sounds fuer die meisten Buttons installieren
        installGenericButtonSfx();

        // Lautstaerke-Slider mit globalem State verbinden
        if (musicVolumeSlider != null) {
            musicVolumeSlider.setValue(BackgroundMusic.getVolume());
            if (musicVolumeValue != null) {
                musicVolumeValue.setText(String.valueOf((int) Math.round(BackgroundMusic.getVolume() * 100.0)));
            }
            musicVolumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
                if (newV != null) {
                    double v = newV.doubleValue();
                    BackgroundMusic.setVolume(v);
                    if (musicVolumeValue != null) {
                        musicVolumeValue.setText(String.valueOf((int) Math.round(v * 100.0)));
                    }
                }
            });
        }
        
        // Layout-Bindings fuer responsive Hoehe der unteren Leiste (40% der Gesamthoehe)
        if (mainLayout != null && bottomBar != null) {
            var bottomHeight = mainLayout.heightProperty().multiply(0.40);
            bottomBar.prefHeightProperty().bind(bottomHeight);
            bottomBar.minHeightProperty().bind(bottomHeight);
            bottomBar.maxHeightProperty().bind(bottomHeight);
        }
        
        // Dashboard-Hoehe berechnen (Gesamthoehe minus Top- und Bottom-Bar)
        if (mainLayout != null && gameDashboard != null && topBar != null && bottomBar != null) {
            var rightHeight = mainLayout.heightProperty()
                    .subtract(topBar.heightProperty())
                    .subtract(bottomBar.heightProperty());
            var clampedRightHeight = Bindings.max(0, rightHeight);
            gameDashboard.prefHeightProperty().bind(clampedRightHeight);
            gameDashboard.minHeightProperty().bind(clampedRightHeight);
            gameDashboard.maxHeightProperty().bind(clampedRightHeight);
        }
        
        // Status-Label fuer Textumbruch konfigurieren
        if (statusLabel != null) {
            statusLabel.setWrapText(true);
            statusLabel.setPrefWidth(300);
            statusLabel.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }

        // Tile-Definitionen aus JSON laden
        loadTileDefinitions();

        // Drag-and-Drop-UI initialisieren
        setupDragPlacementUi();

        // Fallback wenn scoringTilesPane nicht in FXML definiert ist
        if (scoringTilesPane == null && handPane2 != null) {
            scoringTilesPane = handPane2;
        }

        // boardGrid als Alias fuer territoryGrid wenn nicht separat definiert
        if (boardGrid == null) {
            boardGrid = territoryGrid;
        }

        // Spielbrett mit allen Zellen aufbauen
        buildBoard();
        int cellCount = (boardGrid != null) ? boardGrid.getChildren().size() : -1;
        setStatus("Waiting for game messages... (board cells=" + cellCount + ")");
        updateUiForPhase();

        // Overlay-Interaktionen einrichten
        setupBoardOverlayUx();
        setupScoringBoardOverlay();
        setupScoringPreviewResize();

        // ScrollPane-Listener fuer Board-Groesse und Zentrierung
        if (boardScrollPane != null) {
            // Grid-Groesse an Viewport anpassen damit keine Scrollbars noetig sind
            boardScrollPane.viewportBoundsProperty().addListener((obs, o, n) -> {
                if (n != null && boardGrid != null) {
                    boardGrid.setPrefWidth(n.getWidth());
                    boardGrid.setPrefHeight(n.getHeight());
                }
                if (!boardCenteredOnce) {
                    boardCenteredOnce = true;
                    centerBoardView();
                }
                updateDroppedDragPosition();
            });
            boardScrollPane.hvalueProperty().addListener((obs, o, n) -> updateDroppedDragPosition());
            boardScrollPane.vvalueProperty().addListener((obs, o, n) -> updateDroppedDragPosition());
            Platform.runLater(() -> {
                if (boardScrollPane.getViewportBounds() != null && boardGrid != null) {
                    var vb = boardScrollPane.getViewportBounds();
                    boardGrid.setPrefWidth(vb.getWidth());
                    boardGrid.setPrefHeight(vb.getHeight());
                }
                if (!boardCenteredOnce) {
                    boardCenteredOnce = true;
                    centerBoardView();
                }
                updateDroppedDragPosition();
            });
        }

        // Spielerliste mit Custom-Zellen fuer Karten-Layout konfigurieren
        if (playersList != null) {
            playersList.setItems(playerNames);
            playersList.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                        getStyleClass().removeAll("player-card-cell", "player-card-cell-self", "player-card-cell-active");
                        setStyle("");
                        return;
                    }

                    // Build a "card" UI instead of a plain text row.
                    setText(null);

                    boolean isSelf = item.equals(username) || item.endsWith(" (you)");

                    String display = item;
                    if (display.endsWith(" (you)")) {
                        display = display.substring(0, display.length() - 6);
                    }

                    // Highlight active player
                    String activeName = extractActivePlayerName();
                    boolean isActive = false;
                    if (activeName != null && !activeName.isBlank()) {
                        isActive = activeName.equals(display) || activeName.equals(item);
                    }

                    // Resolve a consistent clientId for styling color, if we can.
                    Integer clientId = resolveClientIdForDisplayName(display, isSelf);

                    String borderColor = "rgba(255,255,255,0.18)";
                    try {
                        if (clientId != null && client != null) {
                            String piece = client.getPlayerPiecesByClientId().get(clientId);
                            Color c = ScoringBoardWhileGame.colorForGamePiece(piece);
                            if (c != null) {
                                int r = (int) Math.round(c.getRed() * 255);
                                int g = (int) Math.round(c.getGreen() * 255);
                                int b = (int) Math.round(c.getBlue() * 255);
                                borderColor = "rgb(" + r + "," + g + "," + b + ")";
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    HBox card = new HBox(12);
                    card.getStyleClass().add("player-card");
                    card.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

                    // Color stripe as an inner rounded region (prevents border overlap issues).
                    Region stripe = new Region();
                    stripe.getStyleClass().add("player-card-stripe");
                    stripe.setMinWidth(7);
                    stripe.setPrefWidth(7);
                    stripe.setMaxWidth(7);
                    stripe.setMinHeight(30);
                    stripe.setPrefHeight(30);
                    stripe.setMaxHeight(Double.MAX_VALUE);
                    stripe.setStyle("-fx-background-color: " + borderColor + ";");

                    Label nameLabel = new Label(display);
                    nameLabel.getStyleClass().add("player-card-name");

                    // Name + (optional) YOU badge
                    HBox nameRow = new HBox(8);
                    nameRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    nameRow.getChildren().add(nameLabel);

                    if (isSelf) {
                        Label you = new Label("YOU");
                        you.getStyleClass().add("player-card-you-badge");
                        nameRow.getChildren().add(you);
                    }

                    VBox textBox = new VBox(2);
                    textBox.getChildren().add(nameRow);

                    // Add phase status under player name
                    // Show for all players in DISCARD phase, only active player in other phases
                    boolean showStatus = false;
                    if ("DISCARD".equalsIgnoreCase(currentPhase)) {
                        // In DISCARD phase, all players work simultaneously
                        showStatus = true;
                    } else {
                        // In other phases (BUY, BUILD), only show for active player
                        showStatus = isActive;
                    }
                    
                    if (showStatus) {
                        Label statusLabel = new Label();
                        statusLabel.getStyleClass().add("player-card-status-label");
                        
                        // Check if player is ready (completed their action)
                        boolean isReady = playersReadyInPhase.contains(clientId);
                        
                        if (isReady) {
                            // Player has completed their action
                            statusLabel.setText("✓ Ready");
                            statusLabel.getStyleClass().add("player-card-status-ready");
                        } else {
                            // Player is still working on their action
                            switch (currentPhase != null ? currentPhase.toUpperCase() : "") {
                                case "DISCARD" -> {
                                    if (isSelf) {
                                        boolean pricingComplete = isDiscardPricingComplete();
                                        if (pricingComplete) {
                                            statusLabel.setText("✓ Ready to confirm");
                                            statusLabel.getStyleClass().add("player-card-status-ready");
                                        } else {
                                            statusLabel.setText("⏳ Setting Prices");
                                            statusLabel.getStyleClass().add("player-card-status-waiting");
                                        }
                                    } else {
                                        statusLabel.setText("⏳ Setting Prices");
                                        statusLabel.getStyleClass().add("player-card-status-waiting");
                                    }
                                }
                                case "BUY" -> {
                                    if (isSelf && selectedMarketTileId != null) {
                                        statusLabel.setText("✓ Ready to buy");
                                        statusLabel.getStyleClass().add("player-card-status-ready");
                                    } else {
                                        statusLabel.setText("⏳ Buying Tiles");
                                        statusLabel.getStyleClass().add("player-card-status-waiting");
                                    }
                                }
                                case "BUILD" -> {
                                    statusLabel.setText("⏳ Placing Tiles");
                                    statusLabel.getStyleClass().add("player-card-status-waiting");
                                }
                                default -> {
                                    statusLabel.setText("⏳ " + (currentPhase != null ? currentPhase : ""));
                                    statusLabel.getStyleClass().add("player-card-status-waiting");
                                }
                            }
                        }
                        
                        textBox.getChildren().add(statusLabel);
                    }

                    // Score (right aligned)
                    int totalScore = lastScoreByDisplayName.getOrDefault(normalizeDisplayName(display), 0);
                    Label scoreLabel = new Label(String.valueOf(totalScore));
                    scoreLabel.getStyleClass().add("player-card-score");

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    // View Territory button (eye icon) - only for other players
                    StackPane viewButton = null;
                    if (!isSelf) {
                        viewButton = new StackPane();
                        viewButton.getStyleClass().add("view-territory-button");
                        Label eyeIcon = new Label("👁");
                        eyeIcon.setStyle("-fx-font-size: 16px;");
                        viewButton.getChildren().add(eyeIcon);
                        viewButton.setMinSize(32, 32);
                        viewButton.setPrefSize(32, 32);
                        viewButton.setMaxSize(32, 32);
                        
                        // Handle click on view button
                        final String finalDisplay = display;
                        final boolean finalIsSelf = isSelf;
                        viewButton.setOnMouseClicked(e -> {
                            e.consume(); // Prevent list selection

                            // Generic click SFX for viewing other territories.
                            SoundEffects.playUiClick();
                            
                            Integer targetClientId = resolveClientIdForDisplayName(finalDisplay, finalIsSelf);
                            if (targetClientId == null) {
                                targetClientId = clientIdForPlayerName(finalDisplay);
                            }
                            if (targetClientId == null) return;

                            // Toggle: if already viewing this player, go back to own territory
                            if (selectedTerritoryClientId != null && selectedTerritoryClientId.equals(targetClientId)) {
                                setSelectedTerritory(null);
                            } else {
                                setSelectedTerritory(targetClientId);
                            }
                        });
                    }

                    if (viewButton != null) {
                        card.getChildren().addAll(stripe, textBox, spacer, scoreLabel, viewButton);
                    } else {
                        card.getChildren().addAll(stripe, textBox, spacer, scoreLabel);
                    }

                    // Apply per-cell classes so CSS can style selection/spacing.
                    getStyleClass().add("player-card-cell");
                    if (isSelf && !getStyleClass().contains("player-card-cell-self")) {
                        getStyleClass().add("player-card-cell-self");
                    } else if (!isSelf) {
                        getStyleClass().remove("player-card-cell-self");
                    }
                    if (isActive && !getStyleClass().contains("player-card-cell-active")) {
                        getStyleClass().add("player-card-cell-active");
                    } else if (!isActive) {
                        getStyleClass().remove("player-card-cell-active");
                    }

                    // No per-cell border anymore (it caused alignment issues). Style the card itself.
                    if (isSelf) {
                        card.setStyle(
                                "-fx-border-color: rgba(227, 191, 85, 1);"
                                        + "-fx-border-width: 2;"
                                        + "-fx-border-radius: 14;"
                                        + "-fx-background-radius: 14;"
                                        + "-fx-effect: dropshadow(gaussian, rgba(227,191,85,0.28), 18, 0.25, 0, 0), dropshadow(gaussian, rgba(0,0,0,0.55), 16, 0.25, 0, 6);"
                        );
                    } else {
                        card.setStyle("");
                    }
                    setStyle("");
                    setGraphic(card);
                }
            });

            // Refresh player cards when the active player label changes
            // (otherwise ListView cells only update on interaction/scroll).
            if (activePlayerLabel != null) {
                activePlayerLabel.textProperty().addListener((obs, o, n) -> refreshPlayersListView());
            }
        }

        if (backToMyTerritoryButton != null) {
            backToMyTerritoryButton.setOnAction(e -> setSelectedTerritory(null));
            backToMyTerritoryButton.setVisible(false);
            backToMyTerritoryButton.setManaged(false);
        }

        // Chat thread list UI has been removed from the left sidebar (non-functional for now).
        // We keep chat via chatBox/chatArea and default to the global thread.
        ensureGlobalEntryPresent();
        if (handTitleLabel != null) {
            handTitleLabel.setVisible(false);
            handTitleLabel.setManaged(false);
        }
        if (messageField != null) {
            messageField.setPromptText("Type /help for commands");
        }

    }

    private void installGenericButtonSfx() {
        // Exclusions:
        // - drawButton: has its own card SFX
        // - price +/-: handled by mouse handlers in pricing UI
        addClickSfxIfEligible(leaveButton);
        addClickSfxIfEligible(finishBuildButton);
        addClickSfxIfEligible(settingsButton);
        addClickSfxIfEligible(submitPricesButton);
        addClickSfxIfEligible(gameboardButton);
        addClickSfxIfEligible(closeGameboardButton);
        addClickSfxIfEligible(buyButton);
        addClickSfxIfEligible(passButton);
        addClickSfxIfEligible(continueButton);
        addClickSfxIfEligible(sendButton);
        addClickSfxIfEligible(recenterBoardButton);
        addClickSfxIfEligible(backToMyTerritoryButton);

        // Tabs (Your tiles / Buy / Scoring tiles): selection changes aren't ActionEvents, wire explicitly.
        installTabClickSfx();
    }

    private void installTabClickSfx() {
        if (bottomTabs == null || bottomTabs.getSelectionModel() == null) return;
        bottomTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null && newTab != oldTab) {
                SoundEffects.playUiClick();
            }
        });
    }

    private void addClickSfxIfEligible(Button button) {
        if (button == null) return;
        if (button == drawButton) return;
        // Add without clobbering existing onAction handlers.
        button.addEventHandler(javafx.event.ActionEvent.ACTION, e -> SoundEffects.playUiClick());
    }

    private Integer clientIdForPlayerName(String name) {
        if (name == null) return null;
        for (var e : playerNameByClientId.entrySet()) {
            if (name.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    private void setSelectedTerritory(Integer clientId) {
        selectedTerritoryClientId = clientId;

        boolean viewingOther = (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId);
        if (backToMyTerritoryButton != null) {
            backToMyTerritoryButton.setVisible(viewingOther);
            backToMyTerritoryButton.setManaged(viewingOther);
        }

        // Cancel any active placement UI when switching views.
        stopDragPlacement();
        selectedHandTileId = null;
        updateHandSelectionUi();
        updateFinishBuildButton();

        refreshTerritoryView();
    }

    private void refreshTerritoryView() {
        // Clear all TileView nodes from cells.
        for (StackPane cell : boardCells.values()) {
            if (cell == null) continue;
            cell.getChildren().removeIf(n -> n instanceof TileView);
        }

        Integer target = (selectedTerritoryClientId == null) ? myClientId : selectedTerritoryClientId;
        java.util.Map<Coord, PlacedTileState> toRender = placedTilesByClientId.getOrDefault(target, java.util.Map.of());
        boolean viewingOther = (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId);

        for (var e : toRender.entrySet()) {
            Coord c = e.getKey();
            PlacedTileState s = e.getValue();
            if (c == null || s == null) continue;

            Integer tileId = null;
            if (s.tileJson() != null && s.tileJson().has("id") && s.tileJson().get("id").isJsonPrimitive()) {
                tileId = s.tileJson().get("id").getAsInt();
            }
            if (tileId == null) continue;

            if (s.tileJson() != null) {
                tileDefinitions.put(tileId, s.tileJson());
            }

            if (viewingOther) {
                renderTileOnBoardOnly(tileId, c.x, c.y, s.rotationDegrees());
            } else {
                placeTileOnBoard(tileId, c.x, c.y, s.rotationDegrees(), false);
            }
        }

        if (!viewingOther) {
            renderPendingPlacements();
        }

        // While viewing other territory, suppress local placement hints.
        if (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId) {
            clearPlacementHints();
            if (finishBuildButton != null) {
                finishBuildButton.setVisible(false);
                finishBuildButton.setManaged(false);
            }
        } else {
            updatePlacementHints();
            updateFinishBuildButton();
        }
    }

    private void renderTileOnBoardOnly(int tileId, int x, int y, int rotation) {
        StackPane cell = boardCells.get(new Coord(x, y));
        if (cell == null) return;

        cell.getChildren().removeIf(n -> n instanceof TileView);

        JsonObject tileJson = tileDefinitions.get(tileId);
        if (tileJson == null) {
            tileJson = new JsonObject();
            tileJson.addProperty("id", tileId);
            tileJson.addProperty("top", "none");
            tileJson.addProperty("right", "none");
            tileJson.addProperty("down", "none");
            tileJson.addProperty("left", "none");
        }

        TileView view = new TileView(tileJson, TILE_SIZE);
        view.setRotate(rotation);
        view.setMouseTransparent(true);
        cell.getChildren().add(view);
    }

    private void setupScoringPreviewResize() {
        if (scoringPreviewGrid == null) return;
        scoringPreviewGrid.widthProperty().addListener((o, ov, nv) -> updateScoringPreviewTileSize());
        if (gameDashboard != null) {
            gameDashboard.widthProperty().addListener((o, ov, nv) -> updateScoringPreviewTileSize());
            gameDashboard.heightProperty().addListener((o, ov, nv) -> updateScoringPreviewTileSize());
        }
        Platform.runLater(this::updateScoringPreviewTileSize);
    }

    private void updateScoringPreviewTileSize() {
        if (scoringPreviewGrid == null) return;
        if (scoringPreviewTileA == null || scoringPreviewTileB == null
                || scoringPreviewTileC == null || scoringPreviewTileD == null) {
            return;
        }

        double dashboardW = (gameDashboard != null) ? gameDashboard.getWidth() : scoringPreviewGrid.getWidth();
        double dashboardH = (gameDashboard != null) ? gameDashboard.getHeight() : scoringPreviewGrid.getHeight();
        if (dashboardW <= 0 || dashboardH <= 0) return;

        double targetGridH = clamp(dashboardH * 0.5, 260.0, 480.0);
        scoringPreviewGrid.setMinHeight(targetGridH);
        scoringPreviewGrid.setPrefHeight(targetGridH);
        scoringPreviewGrid.setMaxHeight(targetGridH);

        double gridW = scoringPreviewGrid.getWidth() > 0 ? scoringPreviewGrid.getWidth() : (dashboardW - 24.0);
        if (gridW <= 0) return;

        double hgap = scoringPreviewGrid.getHgap();
        double vgap = scoringPreviewGrid.getVgap();
        double cellW = (gridW - hgap) / 2.0;
        double cellH = (targetGridH - vgap) / 2.0;
        double maxSquare = Math.min(cellW, cellH);

        double tileSize = clamp(maxSquare - 8.0, 110.0, 230.0);

        scoringPreviewTileA.setFitWidth(tileSize);
        scoringPreviewTileA.setFitHeight(tileSize);
        scoringPreviewTileB.setFitWidth(tileSize);
        scoringPreviewTileB.setFitHeight(tileSize);
        scoringPreviewTileC.setFitWidth(tileSize);
        scoringPreviewTileC.setFitHeight(tileSize);
        scoringPreviewTileD.setFitWidth(tileSize);
        scoringPreviewTileD.setFitHeight(tileSize);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    

    private void setupBoardOverlayUx() {
    }

    private void setupDragPlacementUi() {
        if (boardRoot == null || dragOverlay != null) return;

        dragOverlay = new Pane();
        dragOverlay.setPickOnBounds(false);
        dragOverlay.setManaged(false);
        dragOverlay.setStyle("-fx-background-color: transparent;");
        boardRoot.getChildren().add(dragOverlay);
        dragOverlay.toFront();

        dragRotateLeftButton = new Button("⟲");
        dragRotateRightButton = new Button("⟳");
        dragConfirmButton = new Button("Confirm");

        String ghostButtonStyle = "-fx-background-color: rgba(30,30,30,0.6);"
                + " -fx-text-fill: white; -fx-background-radius: 6;"
                + " -fx-border-color: rgba(255,255,255,0.2); -fx-border-radius: 6;";
        String confirmStyle = "-fx-background-color: rgba(76,225,138,0.75);"
                + " -fx-text-fill: #0b2216; -fx-background-radius: 6;"
                + " -fx-border-color: rgba(255,255,255,0.25); -fx-border-radius: 6;";

        dragRotateLeftButton.setStyle(ghostButtonStyle);
        dragRotateRightButton.setStyle(ghostButtonStyle);
        dragConfirmButton.setStyle(confirmStyle);

        dragRotateButtons = new HBox(6, dragRotateLeftButton, dragRotateRightButton);
        dragRotateButtons.setManaged(false);
        dragRotateButtons.setVisible(false);
        dragRotateButtons.setOpacity(0.82);

        dragConfirmButton.setManaged(false);
        dragConfirmButton.setVisible(false);
        dragConfirmButton.setOpacity(0.82);

        dragRotateLeftButton.setOnAction(e -> rotateDrag(-90));
        dragRotateRightButton.setOnAction(e -> rotateDrag(90));
        dragConfirmButton.setOnAction(e -> {
            // Clicking confirm while placing a tile should feel like a button click.
            SoundEffects.playUiClick();
            confirmDragPlacement();
        });

        dragOverlay.getChildren().addAll(dragRotateButtons, dragConfirmButton);

        boardRoot.addEventFilter(MouseEvent.MOUSE_MOVED, this::handleDragMouseMove);
        boardRoot.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::handleDragMouseMove);
        boardRoot.addEventFilter(MouseEvent.MOUSE_CLICKED, this::handleDragMouseClick);
    }

    private void handleDragMouseMove(MouseEvent event) {
        hasMousePosition = true;
        lastMouseSceneX = event.getSceneX();
        lastMouseSceneY = event.getSceneY();
        if (dragActive && !dragDropped) {
            positionDragAtScene(event.getSceneX(), event.getSceneY());
        }
    }

    private void handleDragMouseClick(MouseEvent event) {
        if (!dragActive) return;
        if (event.getButton() == MouseButton.SECONDARY) {
            if (dragDropped) {
                dragDropped = false;
                hideDragControls();
                positionDragAtScene(event.getSceneX(), event.getSceneY());
                dragCoord = null;
            } else {
                dropDragAtScene(event.getSceneX(), event.getSceneY());
            }
            event.consume();
        }
    }

    private void positionDragAtScene(double sceneX, double sceneY) {
        if (dragTileView == null || boardRoot == null) return;
        Point2D local = boardRoot.sceneToLocal(sceneX, sceneY);
        dragTileView.relocate(local.getX() - (TILE_SIZE / 2.0), local.getY() - (TILE_SIZE / 2.0));
        updateDragControlPositions();
    }

    private void dropDragAtScene(double sceneX, double sceneY) {
        dragDropped = true;
        dragCoord = findCoordAtScene(sceneX, sceneY);
        if (dragCoord != null) {
            updateDroppedDragPosition();
        } else {
            positionDragAtScene(sceneX, sceneY);
        }
        showDragControls();
        updateDragControlPositions();
    }

    private void updateDragControlPositions() {
        if (dragTileView == null || dragRotateButtons == null || dragConfirmButton == null) return;
        double left = dragTileView.getLayoutX();
        double top = dragTileView.getLayoutY();
        double centerX = left + (TILE_SIZE / 2.0);

        dragRotateButtons.applyCss();
        dragRotateButtons.autosize();
        dragConfirmButton.applyCss();
        dragConfirmButton.autosize();

        double rotateW = dragRotateButtons.getLayoutBounds().getWidth();
        double rotateH = dragRotateButtons.getLayoutBounds().getHeight();
        dragRotateButtons.relocate(centerX - rotateW / 2.0, top - rotateH - 6);

        double confirmW = dragConfirmButton.getLayoutBounds().getWidth();
        dragConfirmButton.relocate(centerX - confirmW / 2.0, top + TILE_SIZE + 6);
        updateDragConfirmVisibility();
    }

    private void updateDroppedDragPosition() {
        if (!dragDropped || dragCoord == null || dragTileView == null) return;
        Point2D center = getCellCenterInBoardRoot(dragCoord);
        if (center != null) {
            dragTileView.relocate(center.getX() - (TILE_SIZE / 2.0), center.getY() - (TILE_SIZE / 2.0));
            updateDragControlPositions();
        }
    }

    private Coord findCoordAtScene(double sceneX, double sceneY) {
        for (Map.Entry<Coord, StackPane> entry : boardCells.entrySet()) {
            StackPane cell = entry.getValue();
            if (cell == null) continue;
            var bounds = cell.localToScene(cell.getBoundsInLocal());
            if (bounds.contains(sceneX, sceneY)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private Point2D getCellCenterInBoardRoot(Coord coord) {
        if (boardRoot == null) return null;
        StackPane cell = boardCells.get(coord);
        if (cell == null) return null;
        var bounds = cell.localToScene(cell.getBoundsInLocal());
        Point2D centerScene = new Point2D(
                (bounds.getMinX() + bounds.getMaxX()) / 2.0,
                (bounds.getMinY() + bounds.getMaxY()) / 2.0
        );
        return boardRoot.sceneToLocal(centerScene);
    }

    private void showDragControls() {
        if (dragRotateButtons != null) dragRotateButtons.setVisible(true);
        updateDragConfirmVisibility();
    }

    private void hideDragControls() {
        if (dragRotateButtons != null) dragRotateButtons.setVisible(false);
        if (dragConfirmButton != null) dragConfirmButton.setVisible(false);
    }

    private void rotateDrag(int delta) {
    if (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId) return;
        if (!"BUILD".equalsIgnoreCase(currentPhase) || !dragActive) return;
        selectedRotation = (selectedRotation + delta + 360) % 360;
        if (dragTileView != null) {
            dragTileView.setRotate(selectedRotation);
        }
        setStatus("Rotation: " + selectedRotation);
        updatePlacementHints();
        updateDragConfirmVisibility();
    }

    private void confirmDragPlacement() {
        if (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId) {
            setStatus("Viewing another player's territory (read-only). Click 'Back to my territory' to place tiles.");
            stopDragPlacement();
            return;
        }
        if (buildFinalizing) return;
        if (!"BUILD".equalsIgnoreCase(currentPhase)) {
            setStatus("Not in BUILD phase.");
            return;
        }
        if (selectedHandTileId == null) {
            setStatus("Select a tile first.");
            return;
        }
        TileCard selected = handCards.get(selectedHandTileId);
        if (selected == null || selected.tileJson == null) {
            setStatus("Select a tile first.");
            return;
        }
        if (dragCoord == null || !canPlaceLocal(selected.tileJson, dragCoord.x, dragCoord.y, selectedRotation)) {
            setStatus("Invalid placement. Choose a green cell.");
            return;
        }
        // Successful placement via confirm.
        SoundEffects.playUiClick();
        placePreviewTile(selectedHandTileId, selected.tileJson, dragCoord.x, dragCoord.y, selectedRotation);
        selectedHandTileId = null;
        updateHandSelectionUi();
        stopDragPlacement();
        updatePlacementHints();
        updateFinishBuildButton();
        setStatus("Tile placed. Place remaining tiles or adjust.");
    }

    private void startDragForSelectedTile() {
        if (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId) {
            setStatus("Viewing another player's territory (read-only). Click 'Back to my territory' to place tiles.");
            return;
        }
        if (buildFinalizing) return;
        if (!"BUILD".equalsIgnoreCase(currentPhase)) return;
        if (selectedHandTileId == null) return;
        TileCard selected = handCards.get(selectedHandTileId);
        if (selected == null || selected.tileJson == null) return;
        if (dragOverlay == null) {
            setupDragPlacementUi();
        }
        if (dragTileView != null && dragOverlay != null) {
            dragOverlay.getChildren().remove(dragTileView);
        }
        dragTileView = new TileView(selected.tileJson, TILE_SIZE);
        dragTileView.setRotate(selectedRotation);
        dragTileView.setMouseTransparent(true);
        dragOverlay.getChildren().add(dragTileView);
        dragOverlay.toFront();

        dragActive = true;
        dragDropped = false;
        dragCoord = null;
        hideDragControls();

        if (hasMousePosition) {
            positionDragAtScene(lastMouseSceneX, lastMouseSceneY);
        } else if (boardRoot != null) {
            dragTileView.relocate(boardRoot.getWidth() / 2.0 - (TILE_SIZE / 2.0),
                    boardRoot.getHeight() / 2.0 - (TILE_SIZE / 2.0));
            updateDragControlPositions();
        }

        setStatus("Right-click to drop the tile, then rotate/confirm.");
    }

    private void syncDragTileWithSelection() {
        if (!dragActive) return;
        if (selectedHandTileId == null) {
            stopDragPlacement();
            return;
        }
        TileCard selected = handCards.get(selectedHandTileId);
        if (selected == null || selected.tileJson == null) {
            stopDragPlacement();
            return;
        }
        if (dragTileView != null && dragTileView.getTileId() == selected.tileId) {
            dragTileView.setRotate(selectedRotation);
            return;
        }
        startDragForSelectedTile();
    }

    private void stopDragPlacement() {
        dragActive = false;
        dragDropped = false;
        dragCoord = null;
        hideDragControls();
        if (dragTileView != null && dragOverlay != null) {
            dragOverlay.getChildren().remove(dragTileView);
        }
        dragTileView = null;
    }

    private void placePreviewTile(int tileId, JsonObject tileJson, int x, int y, int rotation) {
        if (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId) {
            setStatus("Viewing another player's territory (read-only). Click 'Back to my territory' to place tiles.");
            return;
        }
        StackPane cell = boardCells.get(new Coord(x, y));
        if (cell == null) return;

        TileView view = createPreviewTileView(tileId, tileJson, rotation);
        cell.getChildren().add(view);
        pendingPlacements.put(tileId, new PreviewInfo(tileId, x, y, rotation, cell, view));
        renderBuildHandUi();
    }

    private TileView createPreviewTileView(int tileId, JsonObject tileJson, int rotation) {
        TileView view = new TileView(tileJson, TILE_SIZE);
        view.setRotate(rotation);
        view.getStyleClass().add("pending-tile");
        view.setOpacity(0.98);
        view.setOnMouseClicked(e -> {
            if (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId) {
                e.consume();
                setStatus("Viewing another player's territory (read-only). Click 'Back to my territory' to continue.");
                return;
            }
            if (!"BUILD".equalsIgnoreCase(currentPhase) || buildFinalizing) return;
            if (e.getButton() == MouseButton.PRIMARY) {
                // In BUILD phase: selecting a placed-preview tile is a UI click.
                SoundEffects.playUiClick();
                removePreviewTile(tileId);
                revalidatePendingPlacements();
                selectedHandTileId = tileId;
                selectedRotation = rotation;
                renderBuildHandUi();
                updateHandSelectionUi();
                startDragForSelectedTile();
                updatePlacementHints();
                updateFinishBuildButton();
            } else if (e.getButton() == MouseButton.SECONDARY) {
                removePreviewTile(tileId);
                revalidatePendingPlacements();
                renderBuildHandUi();
                updateFinishBuildButton();
            }
        });
        return view;
    }

    private void renderPendingPlacements() {
        if (pendingPlacements.isEmpty()) return;

        java.util.Map<Integer, PreviewInfo> rebuilt = new java.util.LinkedHashMap<>();
        for (PreviewInfo info : pendingPlacements.values()) {
            StackPane cell = boardCells.get(new Coord(info.x, info.y));
            if (cell == null) continue;
            JsonObject tileJson = tileDefinitions.get(info.tileId);
            if (tileJson == null) continue;

            TileView view = createPreviewTileView(info.tileId, tileJson, info.rotation);
            cell.getChildren().add(view);
            rebuilt.put(info.tileId, new PreviewInfo(info.tileId, info.x, info.y, info.rotation, cell, view));
        }
        pendingPlacements.clear();
        pendingPlacements.putAll(rebuilt);
    }

    private void removePreviewTile(int tileId) {
        PreviewInfo info = pendingPlacements.remove(tileId);
        if (info != null && info.cell != null && info.viewNode != null) {
            info.cell.getChildren().remove(info.viewNode);
        }
    }

    private void revalidatePendingPlacements() {
        if (pendingPlacements.isEmpty()) return;

        java.util.List<Integer> invalid = new java.util.ArrayList<>();
        java.util.Set<Coord> connectedPending = getConnectedPendingPlacements();
        for (PreviewInfo info : pendingPlacements.values()) {
            if (!isPreviewPlacementValid(info, connectedPending)) {
                invalid.add(info.tileId);
            }
        }

        if (invalid.isEmpty()) return;

        for (Integer tileId : invalid) {
            PreviewInfo info = pendingPlacements.get(tileId);
            if (info != null && info.cell != null) {
                triggerPlacementErrorAnimation(info.cell);
            }
            removePreviewTile(tileId);
        }

        renderBuildHandUi();
        updateFinishBuildButton();
        updatePlacementHints();
        setStatus("Some tiles are no longer connected. Please place them again.");
    }

    private boolean isPreviewPlacementValid(PreviewInfo info, java.util.Set<Coord> connectedPending) {
        if (info == null) return false;
        JsonObject tileJson = tileDefinitions.get(info.tileId);
        if (tileJson == null) return false;
        if (!isValidRotation(info.rotation)) return false;
        if (info.x < BOARD_MIN || info.x > BOARD_MAX || info.y < BOARD_MIN || info.y > BOARD_MAX) return false;

        Coord c = new Coord(info.x, info.y);
        if (placedTiles.containsKey(c)) return false;
        for (PreviewInfo other : pendingPlacements.values()) {
            if (other.tileId == info.tileId) continue;
            if (other.x == info.x && other.y == info.y) return false;
        }

        boolean noAnchors = placedTiles.isEmpty();
        if (noAnchors) {
            for (PreviewInfo other : pendingPlacements.values()) {
                if (other.tileId != info.tileId) {
                    noAnchors = false;
                    break;
                }
            }
        }
        if (noAnchors) {
            return info.x == 0 && info.y == 0;
        }

        boolean hasNeighbor = false;
        boolean hasAnchorNeighbor = false;
        PlacedTileState up = getEffectiveTileStateExcluding(info.x, info.y + 1, info.tileId);
        if (up != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(info.x, info.y + 1)) ||
                    connectedPending.contains(new Coord(info.x, info.y + 1))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, info.rotation, Side.TOP, up, Side.DOWN)) return false;
        }
        PlacedTileState down = getEffectiveTileStateExcluding(info.x, info.y - 1, info.tileId);
        if (down != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(info.x, info.y - 1)) ||
                    connectedPending.contains(new Coord(info.x, info.y - 1))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, info.rotation, Side.DOWN, down, Side.TOP)) return false;
        }
        PlacedTileState right = getEffectiveTileStateExcluding(info.x + 1, info.y, info.tileId);
        if (right != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(info.x + 1, info.y)) ||
                    connectedPending.contains(new Coord(info.x + 1, info.y))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, info.rotation, Side.RIGHT, right, Side.LEFT)) return false;
        }
        PlacedTileState left = getEffectiveTileStateExcluding(info.x - 1, info.y, info.tileId);
        if (left != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(info.x - 1, info.y)) ||
                    connectedPending.contains(new Coord(info.x - 1, info.y))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, info.rotation, Side.LEFT, left, Side.RIGHT)) return false;
        }

        if (!hasNeighbor) return false;
        if (placedTiles.isEmpty()) {
            return info.x == 0 && info.y == 0;
        }
        return hasAnchorNeighbor;
    }

    private void updateFinishBuildButton() {
        if (finishBuildButton == null) return;
        boolean build = "BUILD".equalsIgnoreCase(currentPhase);
        boolean allPlaced = build && !pendingPlacements.isEmpty() && !hasUnplacedHandTiles();
        finishBuildButton.setVisible(allPlaced);
        finishBuildButton.setManaged(allPlaced);
        finishBuildButton.setDisable(buildFinalizing);
    }

    private boolean hasUnplacedHandTiles() {
        for (Integer id : handCards.keySet()) {
            if (!pendingPlacements.containsKey(id)) return true;
        }
        return false;
    }

    private void updateDragConfirmVisibility() {
        if (dragConfirmButton == null) return;
        boolean show = dragDropped && isDragPlacementValid();
        dragConfirmButton.setVisible(show);
    }

    private boolean isDragPlacementValid() {
        if (dragCoord == null || selectedHandTileId == null) return false;
        TileCard selected = handCards.get(selectedHandTileId);
        if (selected == null || selected.tileJson == null) return false;
        return canPlaceLocal(selected.tileJson, dragCoord.x, dragCoord.y, selectedRotation);
    }

    private void updateDragUiForPhase(boolean buildPhase) {
        if (!buildPhase) {
            stopDragPlacement();
            clearPlacementHints();
            updateFinishBuildButton();
        } else if (dragActive) {
            updateDragControlPositions();
        }
    }

    private void animateSelection(TileView view) {
        if (view == null) return;
        ScaleTransition scale = new ScaleTransition(Duration.millis(120), view);
        scale.setToX(1.08);
        scale.setToY(1.08);
        scale.play();
    }

    /**
     * Zentriert die Spielbrett-Ansicht auf die Mitte (wo das Schloss platziert ist).
     * Wird beim Start und nach Klick auf "Center" aufgerufen.
     */
    private void centerBoardView() {
        if (boardScrollPane == null) return;
        boardScrollPane.setHvalue(0.5);
        boardScrollPane.setVvalue(0.5);
    }

    /**
     * Handler fuer den "Center"-Button.
     * Setzt die Ansicht zurueck auf das Schloss des Spielers.
     */
    @FXML
    private void onRecenterBoard() {
        centerBoardView();
    }

    /**
     * Initialisiert die Spielsitzung mit Client und Spielerliste.
     * Convenience-Methode die attachListener auf true setzt.
     * 
     * @param client Der WebSocket-Client fuer Server-Kommunikation
     * @param username Der Benutzername des lokalen Spielers
     * @param playersSnapshot Snapshot der Spielerliste zum Zeitpunkt des Starts
     */
    public void initSession(WebSocketChatClient client, String username, List<String> playersSnapshot) {
        initSession(client, username, playersSnapshot, true);
    }

    /**
     * Initialisiert die Spielsitzung mit allen notwendigen Parametern.
     * 
     * Diese Methode wird nach dem Laden der FXML aufgerufen und richtet ein:
     * - WebSocket-Client fuer Server-Kommunikation
     * - Spielerliste fuer die Sidebar
     * - Chat-Threads (Global + DMs pro Spieler)
     * - Message-Listener fuer eingehende Server-Nachrichten
     * - Replay von gecachten Payloads (GameStarted, RoundPhaseUpdate, CoinUpdate)
     * 
     * @param client Der WebSocket-Client
     * @param username Eigener Spielername
     * @param playersSnapshot Liste aller Spieler im Spiel
     * @param attachListener Ob der Message-Listener angehaengt werden soll
     */
    public void initSession(WebSocketChatClient client, String username, List<String> playersSnapshot, boolean attachListener) {
        this.client = client;
        this.username = username;
        syncClientId();

        // Spielerliste aufbauen
        playerNames.clear();
        if (playersSnapshot != null && !playersSnapshot.isEmpty()) {
            for (String name : playersSnapshot) {
                if (name == null || name.isBlank()) continue;
                if (!playerNames.contains(name)) playerNames.add(name);
            }
        } else if (username != null && !username.isBlank()) {
            String display = username + " (you)";
            if (!playerNames.contains(display)) playerNames.add(display);
        }

        // Chat-Thread-Liste initialisieren (Global + DMs fuer jeden anderen Spieler)
        chatThreads.clear();
        ensureGlobalEntryPresent();
        for (String n : playerNames) {
            String clean = n.replace(" (you)", "");

            // Nicht sich selbst zur DM-Liste hinzufuegen
            if (username != null && !clean.equals(username)) {
                if (!chatThreads.contains(clean)) chatThreads.add(clean);
            }
        }

        resetChatThreads();

        // Message-Listener registrieren fuer eingehende Server-Nachrichten
        if (client != null && attachListener) {
            inboundListener = raw -> Platform.runLater(() -> handleRaw(raw));
            client.setMessageListener(inboundListener);
        }
        
        // Callback fuer globale UI-Updates (z.B. wenn Angebote sich aendern)
        UI_UPDATE_TRIGGER = () -> {
            Platform.runLater(this::renderWholeMarket);
        };

        if (!GLOBAL_OFFER_CACHE.isEmpty() && ("BUY".equalsIgnoreCase(currentPhase) || "REVEAL".equalsIgnoreCase(currentPhase))) {
            Platform.runLater(this::renderWholeMarket);
        }
        if (client != null) {
            try {
                JsonObject gameStarted = client.getLastGameStartedPayload();
                if (gameStarted != null) {
                    try { log.debug("Applying cached GameStarted at {} payload= {}", java.time.Instant.now(), gameStarted); } catch (Exception ignored) {}
                    handleGameStarted(gameStarted);
                }

                JsonObject phase = client.getLastRoundPhaseUpdatePayload();
                if (phase != null) {
                    try { log.debug("Applying cached RoundPhaseUpdate at {} payload= {}", java.time.Instant.now(), phase);} catch (Exception ignored) {}
                    handleRoundPhaseUpdate(phase);
                }

                if (myClientId >= 0) {
                    JsonObject coin = client.getLastCoinUpdatePayload(myClientId);
                    if (coin != null) {
                        handleCoinUpdate(coin);
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                client.sendGameStartedAck();
            } catch (Exception ignored) {}
        }
        ensureScoringBoardTokens();
    }

    // ==================== TILE-DEFINITIONEN ====================
    
    /**
     * Laedt alle Tile-Definitionen aus der JSON-Datei.
     * Versucht zuerst tiles-v3.json, dann v2, dann die Original-Datei.
     * Die Definitionen werden in tileDefinitions Map gespeichert (ID -> JSON).
     */
    private void loadTileDefinitions() {
        for (String name : new String[] { "tiles-v3.json", "tiles-v2.json", "tiles.json" }) {
            try (InputStream in = getClass().getResourceAsStream("/tiles/" + name)) {
                if (in == null) continue;
                JsonArray arr = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
                if (arr == null) continue;
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    if (!obj.has("id")) continue;
                    tileDefinitions.put(obj.get("id").getAsInt(), obj);
                }
                return;
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== SPIELBRETT-AUFBAU ====================
    
    /**
     * Erstellt das Spielbrett-Grid mit allen Zellen.
     * Das Grid geht von BOARD_MIN bis BOARD_MAX in beide Richtungen.
     * Jede Zelle ist ein StackPane das ein Tile aufnehmen kann.
     */
    private void buildBoard() {
        if (territoryGrid == null) return;

        territoryGrid.getChildren().clear();
        boardCells.clear();
        placedTiles.clear();
        clearPlacementHints();

        // Grid von oben nach unten, links nach rechts fuellen
        for (int y = BOARD_MAX; y >= BOARD_MIN; y--) {
            for (int x = BOARD_MIN; x <= BOARD_MAX; x++) {
                StackPane cell = createBoardCell(x, y);
                int col = x - BOARD_MIN;
                int row = BOARD_MAX - y;
                territoryGrid.add(cell, col, row);
                boardCells.put(new Coord(x, y), cell);
            }
        }
    }

    /**
     * Erstellt eine einzelne Zelle fuer das Spielbrett.
     * Die Zelle hat feste Groesse und reagiert auf Mausklicks fuer Tile-Platzierung.
     * 
     * @param x X-Koordinate auf dem Brett
     * @param y Y-Koordinate auf dem Brett
     * @return Die erstellte Zelle als StackPane
     */
    private StackPane createBoardCell(int x, int y) {
        StackPane cell = new StackPane();
        cell.setMinSize(CELL_SIZE, CELL_SIZE);
        cell.setPrefSize(CELL_SIZE, CELL_SIZE);
        cell.setMaxSize(CELL_SIZE, CELL_SIZE);

        Rectangle bg = new Rectangle(CELL_SIZE, CELL_SIZE);
        // Visual styling moved to CSS. Rectangle receives a style class so
        // the look can be controlled from `game.css` without changing Java code.
        bg.getStyleClass().add("state-cell");

        cell.getChildren().add(bg);
        cell.getProperties().put("bg", bg);
        cell.setOnMouseClicked(e -> onBoardCellClicked(x, y));
        return cell;
    }

    /**
     * Handler fuer Klick auf eine Spielbrett-Zelle.
     * Nur aktiv waehrend der BUILD-Phase. Prueft ob ein Tile ausgewaehlt ist
     * und ob eine legale Platzierung moeglich ist.
     * 
     * @param x X-Koordinate der geklickten Zelle
     * @param y Y-Koordinate der geklickten Zelle
     */
    private void onBoardCellClicked(int x, int y) {
        if (client == null) return;
        syncClientId();
        if (!"BUILD".equalsIgnoreCase(currentPhase)) {
            setStatus("Not in BUILD phase.");
            return;
        }

        // Pruefe ob eine legale Platzierung existiert, sonst Tile zurueckgeben
        if (selectedHandTileId != null) {
            TileCard selected = handCards.get(selectedHandTileId);
            if (selected != null && selected.tileJson != null && !hasAnyLegalPlacementLocal(selected.tileJson)) {
                setStatus("No legal placement. Returning tile to bag...");
                client.buildTile(selected.tileId, 0, 0, 0);
                return;
            }
        }

        if (dragActive) {
            setStatus("Use right-click to drop the tile, then Confirm to place it.");
        }
    }

    // ==================== SERVER-NACHRICHTEN VERARBEITUNG ====================
    
    /**
     * Haupt-Handler fuer eingehende Server-Nachrichten.
     * 
     * Parst die JSON-Nachricht und delegiert basierend auf dem Nachrichtentyp
     * an den entsprechenden spezifischen Handler (handleGameStarted, handleTileDrawn, etc.).
     * 
     * Unterstuetzt zwei Protokoll-Formate:
     * 1. Protocol 0.1: {"messageType":"...", "messageBody":{...}}
     * 2. UI-compat:    {"type":"...",        "payload":{...}}
     * 
     * @param raw Die rohe JSON-Nachricht vom Server
     */
    public void handleRaw(String raw) {

        try {
            syncClientId();
            JsonObject msg = gson.fromJson(raw, JsonObject.class);
            if (msg == null) {
                setStatus("Failed to parse message: null");
                return;
            }
            // Beide Nachrichtenformate akzeptieren
            String type = "";
            JsonObject payload = new JsonObject();
            if (msg != null) {
                if (msg.has("messageType")) {
                    type = msg.get("messageType").getAsString();
                    if (msg.has("messageBody") && msg.get("messageBody").isJsonObject()) {
                        payload = msg.getAsJsonObject("messageBody");
                    }
                    // Log TilePricingCompleted messages
                    if ("TilePricingCompleted".equals(type)) {
                        log.info("Received TilePricingCompleted: {}", payload);
                    }
                } else if (msg.has("type")) {
                    type = msg.get("type").getAsString();
                    if (msg.has("payload") && msg.get("payload").isJsonObject()) {
                        payload = msg.getAsJsonObject("payload");
                    }
                }
            }

            lastMessageType = type;
            switch (type) {
                case "RECEIVED_CHAT" -> handleReceivedChat(payload);
                case "ERROR" -> handleError(payload);
                case "GAME_STARTED", "game_started" -> handleGameStarted(payload);
                case "ROUND_PHASE_UPDATE" -> handleRoundPhaseUpdate(payload);
                case "CURRENT_PLAYER" -> handleCurrentPlayer(payload);
                case "LANDSCAPE_TILE_DRAWN" -> handleTileDrawn(payload);
                case "AVAILABLE_TILES" -> handleAvailableTiles(payload);
                case "TILE_BUILT" -> handleTileBuilt(payload);
                case "TILE_PURCHASED" -> handleTilePurchased(payload);
                case "TILE_RETURNED" -> handleTileReturned(payload);
                case "COIN_UPDATE" -> handleCoinUpdate(payload);
                case "ScoringUpdate", "scoring_update", "SCORING_UPDATE"  ->  handleScoringUpdate(payload);
                case "REVEAL_TILE_PRICES" -> handleRevealTilePrices(payload);
                case "chat_message", "dm_message" -> handleChatMessage(payload);
                case "round_phase_update" -> handleRoundPhaseUpdate(payload);
                case "current_player" -> handleCurrentPlayer(payload);
                case "available_tiles" -> handleAvailableTiles(payload);
                case "tile_drawn" -> handleTileDrawn(payload);
                case "tile_built" -> handleTileBuilt(payload);
                case "tile_purchased" -> handleTilePurchased(payload);
                case "tile_returned" -> handleTileReturned(payload);
                case "coin_update" -> handleCoinUpdate(payload);
                case "reveal_tile_prices" -> handleRevealTilePrices(payload);
                case "RevealTilePrices" -> handleRevealTilePrices(payload);
                case "ReceivedChat" -> handleReceivedChat(payload);
                case "TilePricingCompleted" -> handleTilePricingCompleted(payload);
                case "PriceSubmitRejected", "price_submit_rejected" -> handlePriceSubmitRejected(payload);
                case "StartPlayerUpdate" -> handleStartPlayerUpdate(payload);
                case "GameOver" -> handleGameOver(payload);
                case "Error" -> handleError(payload);
                case "WELCOME", "Welcome" -> handleWelcome(payload);
                case "PLAYER_ADDED", "PlayerAdded" -> handlePlayerAdded(payload);
                case "CONNECTION_UPDATE", "ConnectionUpdate", "player_updated" -> handleConnectionUpdate(payload);
                case "PLAYER_LEFT", "PlayerLeft", "player_left" -> handlePlayerLeft(payload);


                default -> { /* ignore */ }
            }

            if ("SCORING_UPDATE".equals(type) || "ScoringUpdate".equals(type) || "scoring_update".equals(type)) {
                log.debug("CLIENT GOT SCORING_UPDATE raw={}", raw);
            }

        } catch (Exception e) {
            log.error("RAW THAT FAILED = {}", raw);
            e.printStackTrace();
            setStatus("Failed to parse message: " + e.getMessage());
        }
    }

    private static String phaseFromProtocol(int p) {
        return switch (p) {
            case 0 -> "INCOME";
            case 1 -> "DRAW";
            case 2 -> "DISCARD";
            case 3 -> "REVEAL";
            case 4 -> "BUY";
            case 5 -> "BUILD";
            case 6 -> "SCORING";
            case 7 -> "GAME_OVER";
            default -> "UNKNOWN";
        };
    }

    private void syncClientId() {
        if (client == null) return;
        int id = client.getClientId();
        if (id >= 0) {
            myClientId = id;
        }
    }
    private void handleChatMessage(JsonObject payload) {
        String from = payload.has("from") ? payload.get("from").getAsString() : "?";
        String message = payload.has("message") ? payload.get("message").getAsString() : "";

        // Pruefen ob Nachricht privat ist (DM)
        boolean isPrivate = (payload.has("isPrivate") && payload.get("isPrivate").getAsBoolean())
                || (payload.has("to") && payload.get("to").isJsonPrimitive());

        if (isPrivate) {
            String toName = payload.has("to") ? payload.get("to").getAsString() : "Unknown";
            boolean isMe = from.equals(username) || from.equals(username + " (you)");

            // Absender merken fuer schnelle Antwort
            if (!isMe) {
                lastPrivateSenderName = from.replace(" (you)", "").trim();
                log.debug("Reply target set to: {}", lastPrivateSenderName);
            }

            // Nachricht formatieren je nachdem ob gesendet oder empfangen
            String displayLine;
            if (isMe) {
                displayLine = "[PRIVATE to " + toName + "] You: " + message;
            } else {
                displayLine = "[PRIVATE from " + from + "]: " + message;
            }

            appendChat(displayLine);
        } else {
            // Oeffentliche Nachricht
            String line = from + ": " + message;
            appendChat(line);
        }
    }

    /**
     * Verarbeitet Fehlermeldungen vom Server.
     * Zeigt die Fehlermeldung im Status und im Chat an.
     */
    private void handleError(JsonObject payload) {
        String message = payload.has("error") ? payload.get("error").getAsString()
                : payload.has("message") ? payload.get("message").getAsString()
                : "Unknown error";
        setStatus("Error: " + message);
        appendChat("Error: " + message);
        try {
            System.err.println("GameController.handleError: payload=" + payload);
        } catch (Exception ignored) {
        }
    }

    // ==================== SPIEL-LIFECYCLE HANDLER ====================
    
    /**
     * Verarbeitet die "Game Started" Nachricht vom Server.
     * Initialisiert den Spielzustand, platziert das Schloss, aktualisiert die Spielerliste
     * und zeigt optional einen GameStart-Screen mit den Wertungsplaettchen an.
     */
    private void handleGameStarted(JsonObject payload) {
        setStatus("Game started.");
        syncClientId();

        lastKnownPlayerCount = resolvePlayerCount(payload);

        if (payload != null && payload.has("scoringIDs") && payload.get("scoringIDs").isJsonArray()) {
            updateScoringTilesFromIds(payload.getAsJsonArray("scoringIDs"));
        } else {
            renderScoringTiles();
        }
        ensureScoringBoardTokens();
        renderScoringBoardTiles();
        updateScoringBoardRoundMarker();

        if (payload.has("castles") && payload.get("castles").isJsonArray()) {
            JsonArray castles = payload.getAsJsonArray("castles");
            log.debug("Processing castles array, size={}", castles.size());

            for (JsonElement el : castles) {
                if (!el.isJsonObject()) continue;
                JsonObject c = el.getAsJsonObject();
                if (!c.has("clientID")) continue;

                int clientId = c.get("clientID").getAsInt();

                if (c.has("name") && c.get("name").isJsonPrimitive()) {
                    String name = c.get("name").getAsString();
                    if (name != null && !name.isBlank()) {
                        playerNameByClientId.put(clientId, name);
                        log.debug("Stored name for clientId {} = {}", clientId, name);
                    }
                }
            }

            // Store starting castles for ALL players so we can switch territory views later.
            for (JsonElement el : castles) {
                if (!el.isJsonObject()) continue;
                JsonObject c = el.getAsJsonObject();
                if (!c.has("clientID")) continue;

                int clientId = c.get("clientID").getAsInt();
                JsonObject tileJson = (c.has("tile") && c.get("tile").isJsonObject()) ? c.getAsJsonObject("tile") : null;

                Integer tileId = null;
                if (tileJson != null && tileJson.has("id")) {
                    tileId = tileJson.get("id").getAsInt();
                } else if (c.has("tileID")) {
                    tileId = c.get("tileID").getAsInt();
                }
                if (tileId == null) continue;

                int x = c.has("x_position") ? c.get("x_position").getAsInt() : 0;
                int y = c.has("y_position") ? c.get("y_position").getAsInt() : 0;
                int rotation = c.has("rotation") ? c.get("rotation").getAsInt() : 0;

                if (tileJson != null) {
                    tileDefinitions.put(tileId, tileJson);
                }
                placedTilesByClientId
                        .computeIfAbsent(clientId, k -> new java.util.LinkedHashMap<>())
                        .put(new Coord(x, y), new PlacedTileState(tileJson != null ? tileJson : tileDefinitions.get(tileId), rotation));
            }

            // Render our territory from the same per-client store used by spectator mode.
            // This avoids any mismatches where the wrong castle ID is directly placed onto the board.
            if (myClientId >= 0) {
                setSelectedTerritory(null);
            }
        } else {
            // Fallback: try to show some castle tile if present in definitions.
            // IMPORTANT: Do not hardcode -1 (blue). Use my actual start castle if we can.
            Integer fallbackCastleId = null;
            if (payload != null && payload.has("castles") && payload.get("castles").isJsonArray() && myClientId >= 0) {
                for (JsonElement el : payload.getAsJsonArray("castles")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject c = el.getAsJsonObject();
                    if (!c.has("clientID") || c.get("clientID").getAsInt() != myClientId) continue;

                    if (c.has("tile") && c.get("tile").isJsonObject()) {
                        JsonObject t = c.getAsJsonObject("tile");
                        if (t.has("id") && t.get("id").isJsonPrimitive()) {
                            fallbackCastleId = t.get("id").getAsInt();
                        }
                    }
                    if (fallbackCastleId == null && c.has("tileID") && c.get("tileID").isJsonPrimitive()) {
                        fallbackCastleId = c.get("tileID").getAsInt();
                    }
                    break;
                }
            }

            if (fallbackCastleId == null) {
                // last resort: if -1 exists we can at least place *a* castle
                fallbackCastleId = tileDefinitions.containsKey(-1) ? -1 : null;
            }

            if (fallbackCastleId != null) {
                placeTileOnBoard(fallbackCastleId, 0, 0, 0);
            }
        }

        updatePlacementHints();
    }

    private void updateScoringTilesFromIds(JsonArray ids) {
        activeScoringIds.clear();
        if (ids != null) {
            for (JsonElement el : ids) {
                if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                    activeScoringIds.add(el.getAsInt());
                }
            }
        }
        renderScoringTiles();
    }

    private void renderScoringTiles() {

        // 1) Update right-panel 2x2 preview
        updateScoringPreviewBlocks();

        // 2) (Optional) Update old scoring list container if it exists
        if (scoringTilesPane == null) return;
        scoringTilesPane.getChildren().clear();

        if (activeScoringIds.isEmpty()) {
            Label none = new Label("No scoring tile available.");
            none.setStyle("-fx-text-fill: #E9DBB7;");
            scoringTilesPane.getChildren().add(none);
            return;
        }

        VBox list = new VBox(12);
        list.getStyleClass().add("scoring-round-box");

        int index = 0;
        for (Integer id : activeScoringIds) {
            if (id == null) {
                index++;
                continue;
            }

            HBox row = new HBox(14);
            row.getStyleClass().add("scoring-round-row");

            Image img = loadScoringTileImage(id);
            if (img == null) {
                Label fallback = new Label("Missing image for #" + id);
                fallback.setStyle("-fx-text-fill: #E9DBB7;");
                row.getChildren().add(fallback);
                list.getChildren().add(row);
                index++;
                continue;
            }

            ImageView view = new ImageView(img);
            view.setPreserveRatio(true);
            view.setFitWidth(150);
            view.setFitHeight(200);
            view.setSmooth(true);

            VBox textBox = new VBox(6);
            Label desc = new Label(scoringDescriptionForId(id));
            desc.setWrapText(true);
            desc.getStyleClass().add("scoring-round-desc");

            textBox.getChildren().add(desc);
            row.getChildren().addAll(view, textBox);
            list.getChildren().add(row);

            index++;
        }
        scoringTilesPane.getChildren().add(list);
    }

    private void updateScoringPreviewBlocks() {
        // Preview exists only in redesigned HUD.
        if (scoringPreviewTileA == null || scoringPreviewTileB == null
                || scoringPreviewTileC == null || scoringPreviewTileD == null
                || scoringPreviewSlotA == null || scoringPreviewSlotB == null
                || scoringPreviewSlotC == null || scoringPreviewSlotD == null) {
            return;
        }

        Image a = (activeScoringIds.size() > 0) ? loadScoringTileImage(activeScoringIds.get(0)) : null;
        Image b = (activeScoringIds.size() > 1) ? loadScoringTileImage(activeScoringIds.get(1)) : null;
        Image c = (activeScoringIds.size() > 2) ? loadScoringTileImage(activeScoringIds.get(2)) : null;
        Image d = (activeScoringIds.size() > 3) ? loadScoringTileImage(activeScoringIds.get(3)) : null;
        scoringPreviewTileA.setImage(a);
        scoringPreviewTileB.setImage(b);
        scoringPreviewTileC.setImage(c);
        scoringPreviewTileD.setImage(d);

    int playerCount = (lastKnownPlayerCount != null) ? lastKnownPlayerCount : 0;
    boolean[] activeSlots = getActiveScoringSlotsForRound(currentRoundIndex, playerCount);
    try {
        log.debug("ScoringPreview: roundIndex={} playerCount={} activeSlots=[{},{},{},{}] ids={}",
            currentRoundIndex,
            playerCount,
            activeSlots[0], activeSlots[1], activeSlots[2], activeSlots[3],
            activeScoringIds);
    } catch (Exception ignored) {
    }

        applyScoringPreviewSlotState(scoringPreviewSlotA, 'A', activeSlots[0]);
        applyScoringPreviewSlotState(scoringPreviewSlotB, 'B', activeSlots[1]);
        applyScoringPreviewSlotState(scoringPreviewSlotC, 'C', activeSlots[2]);
        applyScoringPreviewSlotState(scoringPreviewSlotD, 'D', activeSlots[3]);

        // Programmatic fallback: ensures dimming + gold border works even if CSS isn't loaded/refreshed.
        applyScoringPreviewVisualFallback(scoringPreviewSlotA, scoringPreviewTileA, activeSlots[0]);
        applyScoringPreviewVisualFallback(scoringPreviewSlotB, scoringPreviewTileB, activeSlots[1]);
        applyScoringPreviewVisualFallback(scoringPreviewSlotC, scoringPreviewTileC, activeSlots[2]);
        applyScoringPreviewVisualFallback(scoringPreviewSlotD, scoringPreviewTileD, activeSlots[3]);
    }

    private void applyScoringPreviewSlotState(StackPane slot, char slotChar, boolean isActive) {
        if (slot == null) return;
        slot.getStyleClass().removeAll("active", "inactive");

        // If we don't know the active slots yet, default to all active so nothing looks broken.
        if (currentRoundIndex < 0) {
            slot.getStyleClass().add("active");
            return;
        }
        slot.getStyleClass().add(isActive ? "active" : "inactive");
    }

    private void applyScoringPreviewVisualFallback(StackPane slot, ImageView imageView, boolean isActive) {
        if (slot == null) return;

        // Border (gold when active, muted when inactive)
        Color borderColor = isActive ? Color.rgb(227, 191, 85, 0.95) : Color.rgb(233, 219, 183, 0.10);
        slot.setBorder(new Border(new BorderStroke(
                borderColor,
                BorderStrokeStyle.SOLID,
                new CornerRadii(12),
                new BorderWidths(2)
        )));

        // Image opacity
        if (imageView != null) {
            imageView.setOpacity(isActive ? 1.0 : 0.35);
        }

        // Subtle glow when active
        if (isActive) {
            slot.setEffect(new DropShadow(18, Color.rgb(227, 191, 85, 0.28)));
        } else {
            slot.setEffect(null);
        }
    }

    /**
     * Mirror of backend ScoringLogic schedule: returns boolean[4] for slots A..D that are active
     * in the provided round (round may be 0-based or 1-based; normalize accordingly).
     */
    private boolean[] getActiveScoringSlotsForRound(int round, int playerCount) {
        // Protocol/UI round is 0-based (0,1,2,...).
        // Backend ScoringLogic uses round numbers starting at 1 and then indexes schedule via (round - 1).
        // So for schedule selection we need: scheduleIndex = (round + 1) - 1 = round.
        int r = round;

    // Default: all active if unknown
    if (r < 0) return new boolean[]{true, true, true, true};

        char[][] scheduleTwoToFour = new char[][]{
                {'A'},
                {'B'},
                {'A','C'},
                {'B','D'},
                {'C','D','A'},
                {'B','D','C'}
        };
        char[][] scheduleFive = new char[][]{
                {'A','B'},
                {'A','C'},
                {'B','D'},
                {'A','D','C'},
                {'B','D','C'}
        };

        char[] slots;
        if (playerCount == 5) {
            if (r >= scheduleFive.length) return new boolean[]{false,false,false,false};
            slots = scheduleFive[r];
        } else {
            if (r >= scheduleTwoToFour.length) return new boolean[]{false,false,false,false};
            slots = scheduleTwoToFour[r];
        }

        boolean[] res = new boolean[4];
        for (char s : slots) {
            switch (s) {
                case 'A' -> res[0] = true;
                case 'B' -> res[1] = true;
                case 'C' -> res[2] = true;
                case 'D' -> res[3] = true;
            }
        }
        return res;
    }

    private Image loadScoringTileImage(int id) {
        String path = "/scoring/" + id + ".png";
        var url = getClass().getResource(path);
        if (url == null) {
            log.warn("Scoring image missing: {}", path);
            return null;
        }
        return new Image(url.toExternalForm());
    }

    private void setupScoringBoardOverlay() {
        if (scoringBoardOverlay == null || scoringBoardImage == null || scoringBoardOverlayPane == null) {
            return;
        }
        scoringBoardWhileGame = new ScoringBoardWhileGame(scoringBoardOverlay, scoringBoardImage, scoringBoardOverlayPane);
        scoringBoardWhileGame.initialize();
        if (mainLayout != null) {
            scoringBoardOverlay.prefWidthProperty().bind(mainLayout.widthProperty());
            scoringBoardOverlay.prefHeightProperty().bind(mainLayout.heightProperty());
            scoringBoardOverlay.minWidthProperty().bind(mainLayout.widthProperty());
            scoringBoardOverlay.minHeightProperty().bind(mainLayout.heightProperty());
            scoringBoardOverlay.maxWidthProperty().bind(mainLayout.widthProperty());
            scoringBoardOverlay.maxHeightProperty().bind(mainLayout.heightProperty());
        }
        setupScoringBoardTilesBindings();
        scoringBoardOverlay.setOnMouseClicked(e -> {
            if (scoringBoardWhileGame != null) scoringBoardWhileGame.hideBoard();
        });
    }

    private void setupScoringBoardTilesBindings() {
        if (scoringBoardOverlay == null || scoringBoardTilesBox == null
                || scoringBoardTileA == null || scoringBoardTileB == null
                || scoringBoardTileC == null || scoringBoardTileD == null) {
            return;
        }
        scoringBoardOverlay.widthProperty().addListener((obs, o, n) -> updateScoringBoardTilesLayout());
        scoringBoardOverlay.heightProperty().addListener((obs, o, n) -> updateScoringBoardTilesLayout());
        updateScoringBoardTilesLayout();
    }

    private void updateScoringBoardTilesLayout() {
        if (scoringBoardOverlay == null || scoringBoardTilesBox == null
                || scoringBoardTileA == null || scoringBoardTileB == null
                || scoringBoardTileC == null || scoringBoardTileD == null) {
            return;
        }
        double w = scoringBoardOverlay.getWidth();
        double h = scoringBoardOverlay.getHeight();
        if (w <= 0 || h <= 0) return;

        double baseW = 1200.0;
        double baseH = 800.0;
        double scale = Math.min(w / baseW, h / baseH);
        scale = Math.max(0.5, Math.min(scale, 1.5));

        double tileSize = 200.0 * scale;
        double spacing = 75.0 * scale;
        double tx = 15.0 * scale;
        double ty = 230.0 * scale;


        int playerCount = (lastKnownPlayerCount != null && lastKnownPlayerCount > 0)
                ? lastKnownPlayerCount
                : playerNames.size();

        double roundTx;
        double roundTy;
        double roundSpacing;
        double roundPlate;
        double roundRadius;
        int expectedRounds;

        if (playerCount == 5) {

            roundTx = 120.0 * scale;
            roundTy = 152.0 * scale;
            roundSpacing = 132.0 * scale;
            roundPlate = 80.0 * scale;
            roundRadius = 22.0 * scale;
            expectedRounds = 5;
        } else {

            roundTx = 150.0 * scale;
            roundTy = 150.0 * scale;
            roundSpacing = 62.0 * scale;
            roundPlate = 80.0 * scale;
            roundRadius = 22.0 * scale;
            expectedRounds = 6;
        }

        scoringBoardTileA.setFitWidth(tileSize);
        scoringBoardTileA.setFitHeight(tileSize);
        scoringBoardTileB.setFitWidth(tileSize);
        scoringBoardTileB.setFitHeight(tileSize);
        scoringBoardTileC.setFitWidth(tileSize);
        scoringBoardTileC.setFitHeight(tileSize);
        scoringBoardTileD.setFitWidth(tileSize);
        scoringBoardTileD.setFitHeight(tileSize);

        scoringBoardTilesBox.setSpacing(spacing);
        scoringBoardTilesBox.setTranslateX(tx);
        scoringBoardTilesBox.setTranslateY(ty);

        if (scoringBoardRoundPlatesBox != null) {

            adjustRoundPlatesCount(expectedRounds);

            scoringBoardRoundPlatesBox.setSpacing(roundSpacing);
            scoringBoardRoundPlatesBox.setTranslateX(roundTx);
            scoringBoardRoundPlatesBox.setTranslateY(roundTy);
            for (javafx.scene.Node n : scoringBoardRoundPlatesBox.getChildren()) {
                if (n instanceof javafx.scene.layout.StackPane sp) {
                    sp.setPrefWidth(roundPlate);
                    sp.setPrefHeight(roundPlate);
                }
            }
        }
        if (scoringBoardRoundMarker != null) {
            scoringBoardRoundMarker.setRadius(roundRadius);
        }
    }
    private void adjustRoundPlatesCount(int expectedCount) {
        if (scoringBoardRoundPlatesBox == null) return;


        while (scoringBoardRoundPlatesBox.getChildren().size() > expectedCount) {
            int lastIndex = scoringBoardRoundPlatesBox.getChildren().size() - 1;
            javafx.scene.Node toRemove = scoringBoardRoundPlatesBox.getChildren().get(lastIndex);

            if (toRemove instanceof StackPane sp && sp.getChildren().contains(scoringBoardRoundMarker)) {
                sp.getChildren().remove(scoringBoardRoundMarker);
            }
            scoringBoardRoundPlatesBox.getChildren().remove(lastIndex);
        }


        while (scoringBoardRoundPlatesBox.getChildren().size() < expectedCount) {
            StackPane newPlate = new StackPane();
            newPlate.setPrefWidth(80);
            newPlate.setPrefHeight(80);
            scoringBoardRoundPlatesBox.getChildren().add(newPlate);
        }
    }

    /**
     * Öffnet oder schließt das Scoring-Board-Overlay.
     */
    @FXML
    private void onShowGameboard() {
        if (scoringBoardWhileGame == null) return;
        if (scoringBoardOverlay != null && scoringBoardOverlay.isVisible()) {
            scoringBoardWhileGame.hideBoard();
            return;
        }
        ensureScoringBoardTokens();
        renderScoringBoardTiles();
        updateScoringBoardRoundMarker();
        scoringBoardWhileGame.showBoard();
    }

    /**
     * Schließt das Scoring-Board-Overlay.
     */
    @FXML
    private void onCloseGameboard() {
        if (scoringBoardWhileGame != null) {
            scoringBoardWhileGame.hideBoard();
        }
    }

    private void triggerScoringBoardAnimation(Map<Integer, Integer> gainedByClient) {
        if (scoringBoardWhileGame == null || gainedByClient == null) return;
        if (gainedByClient.isEmpty()) return;
        ensureScoringBoardTokens();
        scoringBoardWhileGame.showBoard();
        renderScoringBoardTiles();
        updateScoringBoardRoundMarker();
        scoringBoardWhileGame.animateToScores(gainedByClient, () -> {
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
            pause.setOnFinished(ev -> {
                if (scoringBoardWhileGame != null) {
                    scoringBoardWhileGame.hideBoard();
                }
            });
            pause.play();
        });
    }

    private void ensureScoringBoardTokens() {
        if (scoringBoardWhileGame == null) return;
        Map<Integer, String> pieces = (client != null) ? client.getPlayerPiecesByClientId() : Map.of();
        Map<Integer, String> names = (client != null) ? client.getPlayerNamesByClientId() : Map.of();
        if (pieces == null || pieces.isEmpty()) return;

        Map<Integer, Color> colors = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : pieces.entrySet()) {
            colors.put(entry.getKey(), ScoringBoardWhileGame.colorForGamePiece(entry.getValue()));
        }
        scoringBoardWhileGame.setTokens(colors, names);
        scoringBoardWhileGame.setBoardImage(resolveBoardImage());
    }

    private Image resolveBoardImage() {
        int count = (lastKnownPlayerCount != null && lastKnownPlayerCount > 0)
                ? lastKnownPlayerCount
                : playerNames.size();
        String path = (count == 5) ? BOARD_5_PATH : BOARD_2_4_PATH;
        var url = getClass().getResource(path);
        if (url == null) return null;
        return new Image(url.toExternalForm());
    }

    private void renderScoringBoardTiles() {
        if (scoringBoardTileA == null || scoringBoardTileB == null
                || scoringBoardTileC == null || scoringBoardTileD == null) {
            return;
        }
        Image a = (activeScoringIds.size() > 0) ? loadScoringTileImage(activeScoringIds.get(0)) : null;
        Image b = (activeScoringIds.size() > 1) ? loadScoringTileImage(activeScoringIds.get(1)) : null;
        Image c = (activeScoringIds.size() > 2) ? loadScoringTileImage(activeScoringIds.get(2)) : null;
        Image d = (activeScoringIds.size() > 3) ? loadScoringTileImage(activeScoringIds.get(3)) : null;
        scoringBoardTileA.setImage(a);
        scoringBoardTileB.setImage(b);
        scoringBoardTileC.setImage(c);
        scoringBoardTileD.setImage(d);
        if (scoringBoardTilesBox != null) {
            scoringBoardTilesBox.setVisible(true);
            scoringBoardTilesBox.setManaged(true);
        }
    }

    private void updateScoringBoardRoundMarker() {
        if (scoringBoardRoundPlatesBox == null || scoringBoardRoundMarker == null) return;

        int playerCount = (lastKnownPlayerCount != null && lastKnownPlayerCount > 0)
                ? lastKnownPlayerCount
                : playerNames.size();
        int expectedRounds = (playerCount == 5) ? 5 : 6;
        adjustRoundPlatesCount(expectedRounds);

        int idx = currentRoundIndex;
        if (idx < 0) return;
        int max = scoringBoardRoundPlatesBox.getChildren().size();
        if (idx >= max) idx = max - 1;
        if (idx < 0) return;

        if (scoringBoardRoundMarker.getParent() instanceof StackPane currentParent) {
            currentParent.getChildren().remove(scoringBoardRoundMarker);
        }
        if (scoringBoardRoundPlatesBox.getChildren().get(idx) instanceof StackPane nextPlate) {
            nextPlate.getChildren().add(scoringBoardRoundMarker);
        }
    }

    private int resolvePlayerCount(JsonObject payload) {
        if (payload != null && payload.has("castles") && payload.get("castles").isJsonArray()) {
            int size = payload.getAsJsonArray("castles").size();
            if (size > 0) return size;
        }
        if (!playerNames.isEmpty()) {
            return playerNames.size();
        }
        return 0;
    }

    /**
     * Ermittelt die Scoring-ID fuer einen Slot (A, B, C oder D).
     * 
     * @param slot Der Slot-Buchstabe
     * @return Die Scoring-ID oder null wenn nicht gefunden
     */
    private Integer scoringIdForSlot(char slot) {
        int index = switch (slot) {
            case 'A' -> 0;
            case 'B' -> 1;
            case 'C' -> 2;
            case 'D' -> 3;
            default -> -1;
        };
        if (index < 0 || index >= activeScoringIds.size()) return null;
        return activeScoringIds.get(index);
    }

    /**
     * Gibt die Beschreibung fuer ein Wertungsplaettchen zurueck.
     * 
     * @param id Die Scoring-ID
     * @return Die Beschreibung oder ein Fallback-Text
     */
    private String scoringDescriptionForId(int id) {
        String desc = SCORING_DESCRIPTIONS.get(id);
        if (desc == null) {
            return "No description available for scoring tile #" + id + ".";
        }
        return desc;
    }

    // ==================== PHASEN-UPDATE HANDLER ====================
    
    /**
     * Verarbeitet Runden- und Phasenaenderungen vom Server.
     * 
     * Wird bei jedem Phasenwechsel aufgerufen und:
     * - Aktualisiert Runden- und Phasen-Anzeige
     * - Setzt UI-State fuer die neue Phase zurueck
     * - Aktualisiert das Wertungsbrett mit aktuellen Markern
     * - Triggert phasenspezifische UI-Updates
     */
    private void handleRoundPhaseUpdate(JsonObject payload) {
        String previousPhase = currentPhase;
        int round = payload.has("round") ? payload.get("round").getAsInt() : -1;
        String phase = "UNKNOWN";
        if (payload.has("phase")) {
            if (payload.get("phase").isJsonPrimitive() && payload.get("phase").getAsJsonPrimitive().isNumber()) {
                phase = phaseFromProtocol(payload.get("phase").getAsInt());
            } else {
                phase = payload.get("phase").getAsString();
            }
        }

        currentPhase = phase;
        
        // Clear ready status when phase changes
        playersReadyInPhase.clear();
        refreshPlayersListView();
        
        if (!"BUY".equalsIgnoreCase(phase)) {
            currentTurnClientId = null;
        }
    // Protocol round is 0-based (0,1,2,...) in our server logs.
    // Keep currentRoundIndex 0-based so it can be used consistently in the UI.
    currentRoundIndex = round;

        try {
            log.debug("ROUND_PHASE_UPDATE: rawRound={} -> roundIndex={} phase={} payload={}", round, currentRoundIndex, phase, payload);
        } catch (Exception ignored) {
        }
        renderScoringTiles();
        updateScoringBoardRoundMarker();
        if (roundLabel != null && round >= 0) {
            roundLabel.setText("Round " + (round + 1));
        }
        if (phaseLabel != null) {
            phaseLabel.setText("Phase: " + phase);
        }


        currentPhase = phase;
        updateUiForPhase();
        if ("DRAW".equalsIgnoreCase(phase)) {
            log.info("--- NEW ROUND DETECTED (DRAW PHASE) ---");
            if (pendingDiscardTileId != null) {
                log.debug("Clearing pendingDiscardTileId: {}", pendingDiscardTileId);
            }
            drawsRequested = 0;
            drawButtonPressed = false;
            pendingDiscardTileId = null;
            clearHandAndMarket();

            setStatus("Draw Phase: Click 'Draw Tile' to get tiles.");

            keptNonDiscardTileIds.clear();
            renderDrawHandUi();

        } else if ("DISCARD".equalsIgnoreCase(phase)) {

            // Only render if we have tiles, otherwise wait for tiles to arrive
            if (!handCards.isEmpty()) {
                renderDiscardPricingUi();
            } else {
                log.info("DISCARD phase started but no tiles yet - will render when tiles arrive");
            }
            setStatus("Select 1 discard tile and set prices for the other 2.");

        } else if ("REVEAL".equalsIgnoreCase(phase)) {

            if (handTitleLabel != null) {
                handTitleLabel.setText("");
            }
            setStatus("Phase 3: Reveal - Prices set by players are being revealed!");
            if (!revealedOffers.isEmpty()) {
                renderWholeMarket();
            }
            selectBottomTabByText("buy");
            updateUiForPhase();

        } else if ("BUY".equalsIgnoreCase(phase)) {
            if (handTitleLabel != null) {
                handTitleLabel.setText("");
            }
            if (pendingBuyAvailableTiles != null) {
                processBuyAvailableTileIds(pendingBuyAvailableTiles);
                pendingBuyAvailableTiles = null;
            } else if (!revealedOffers.isEmpty()) {
                renderWholeMarket();
            } else {
                marketCards.clear();
                marketPane.getChildren().clear();
                selectedMarketTileId = null;
            }
            setStatus("Phase 4: Buy a tile or pass.");
            updateUiForPhase();

            renderBuildHandUi();
            updateUiForPhase();

        } else if ("BUILD".equalsIgnoreCase(phase)) {

            selectedRotation = 0;
            selectedHandTileId = null;
            pendingPlacements.clear();
            buildFinalizing = false;
            updateFinishBuildButton();

            marketPane.getChildren().clear();
            marketCards.clear();
            selectedMarketTileId = null;

            if (pendingBuildAvailableTiles != null) {
                processBuildAvailableTiles(pendingBuildAvailableTiles);
                pendingBuildAvailableTiles = null;
            } else {
                renderBuildHandUi();
            }
            setStatus("Select a tile and click on the state to place it.");
            updateUiForPhase();
        } else if ("SCORING".equalsIgnoreCase(phase)) {

            scoringStickyUntilMillis = System.currentTimeMillis() + SCORING_STICKY_MS;
            scheduleScoringStickyRefresh();

            setStatus("SCORING: Rundenauswertung");

            if (scoringList != null) {
                scoringList.getChildren().clear();

                Label header = new Label("Round score:");
                header.getStyleClass().add("game-title");
                scoringList.getChildren().addAll(header);

                for (String name : allPlayersForScoringDisplay()) {
                    Label line = new Label(name + ": +0");
                    line.getStyleClass().add("game-text");
                    scoringList.getChildren().add(line);
                }
            }
            updateUiForPhase();



        } else if ("GAME_OVER".equalsIgnoreCase(phase)) {
            JsonObject winnerSource =
                    (lastGameOverPayload != null) ? lastGameOverPayload :
                            (lastScoringPayload != null) ? lastScoringPayload :
                                    (GLOBAL_LAST_SCORING_PAYLOAD != null) ? GLOBAL_LAST_SCORING_PAYLOAD :

                                            payload;

            String winner = determineWinnerFromPayloadOrCache(winnerSource);
            if ((winner == null || winner.isBlank() || "Unbekannt".equalsIgnoreCase(winner))
                    && GLOBAL_LAST_WINNER_NAME != null && !GLOBAL_LAST_WINNER_NAME.isBlank()) {
                winner = GLOBAL_LAST_WINNER_NAME;
            }

            java.util.List<GameOverController.Placement> placements = buildPlacementsForGameOver(winnerSource);
            showGameOverScene(winner, placements);

        } else if ("NONE".equalsIgnoreCase(phase)) {
            if (lastGameOverPayload != null) {
                String winner = determineWinnerFromPayloadOrCache(lastGameOverPayload);
                java.util.List<GameOverController.Placement> placements = buildPlacementsForGameOver(lastGameOverPayload);
                showGameOverScene(winner, placements);
            } else {
                showGameOverScene(determineWinnerFromPayloadOrCache((lastGameOverPayload != null) ? lastGameOverPayload
                        : (lastScoringPayload != null) ? lastScoringPayload
                        : (GLOBAL_LAST_SCORING_PAYLOAD != null) ? GLOBAL_LAST_SCORING_PAYLOAD
                        : payload), java.util.Collections.emptyList());
            }




    } else {
            setStatus("Phase: " + phase);
            updateUiForPhase();
        }
        autoSelectBottomTabForPhase();
        updatePlacementHints();
    }


    private void autoSelectBottomTabForPhase() {
        if (bottomTabs == null || bottomTabs.getTabs() == null || bottomTabs.getTabs().isEmpty()) return;

        if ("BUY".equalsIgnoreCase(currentPhase)) {
            selectBottomTabByText("buy");
        } else if ("DRAW".equalsIgnoreCase(currentPhase)
                || "DISCARD".equalsIgnoreCase(currentPhase)
                || "BUILD".equalsIgnoreCase(currentPhase)) {
            bottomTabs.getSelectionModel().select(0);
        }
    }

    private void selectBottomTabByText(String token) {
        if (bottomTabs == null || token == null) return;
        String needle = token.toLowerCase().trim();
        if (needle.isEmpty()) return;
        for (int i = 0; i < bottomTabs.getTabs().size(); i++) {
            Tab tab = bottomTabs.getTabs().get(i);
            String text = tab == null ? null : tab.getText();
            if (text != null && text.toLowerCase().contains(needle)) {
                bottomTabs.getSelectionModel().select(i);
                return;
            }
        }
    }

    private void handleScoringUpdate(JsonObject payload) {
        scoringStickyUntilMillis = System.currentTimeMillis() + SCORING_STICKY_MS;
        scheduleScoringStickyRefresh();
        lastScoringPayload = payload;

        GLOBAL_LAST_SCORING_PAYLOAD = payload;
        GLOBAL_LAST_WINNER_NAME = determineWinnerFromPayloadOrCache(payload);

        currentPhase = "SCORING";
        updateUiForPhase();

        if (scoringList == null) return;
        scoringList.getChildren().clear();

        if (payload == null) {
            scoringList.getChildren().add(new Label("No scoring data received."));
            return;
        }

        JsonObject scoringObj = payload;
        if (!payload.has("scores") && payload.has("scoring") && payload.get("scoring").isJsonObject()) {
            scoringObj = payload.getAsJsonObject("scoring");
        }

        if (!scoringObj.has("scores") || !scoringObj.get("scores").isJsonArray()) {
            scoringList.getChildren().add(new Label("No scoring data received."));
            return;
        }

        Label header = new Label("Round score");
        header.getStyleClass().add("game-title");
        scoringList.getChildren().add(header);

        JsonArray scores = scoringObj.getAsJsonArray("scores");

        record ScoreLine(int clientId, int gained, int total) {
        }

        java.util.List<ScoreLine> lines = new java.util.ArrayList<>();
        java.util.Map<Integer, Integer> totalByClient = new java.util.HashMap<>();

        for (JsonElement el : scores) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();

            int clientId = s.has("clientID") ? s.get("clientID").getAsInt() : -1;

            int totalVp = s.has("victoryPoints") ? s.get("victoryPoints").getAsInt() : 0;

            int oldTotal = lastTotalVpByClient.getOrDefault(clientId, 0);
            int gainedThisRound = totalVp - oldTotal;
            lastTotalVpByClient.put(clientId, totalVp);

            java.util.Map<String, Integer> gainedByPlayerName = new java.util.HashMap<>();

            lines.add(new ScoreLine(clientId, gainedThisRound, totalVp));
            totalByClient.put(clientId, Math.max(0, totalVp));

            // Cache for player cards + sorting.
            String n = displayNameForClient(clientId);
            lastScoreByDisplayName.put(normalizeDisplayName(n), Math.max(0, totalVp));
        }
        lines.sort((a, b) -> {
            int cmp = Integer.compare(b.gained, a.gained);
            if (cmp != 0) return cmp;
            return displayNameForClient(a.clientId).compareToIgnoreCase(displayNameForClient(b.clientId));

        });

        for (ScoreLine sl : lines) {
            String name = displayNameForClient(sl.clientId);
            String sign = sl.gained >= 0 ? "+" : "";
            Label line = new Label(name + ": " + sign + sl.gained + "   (Total: " + sl.total + ")");
            line.getStyleClass().add("game-text");
            scoringList.getChildren().add(line);
        }
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        spacer.setMinHeight(6);
        scoringList.getChildren().add(spacer);

        triggerScoringBoardAnimation(totalByClient);

        // Spielerliste nach Punktestand sortieren
        resortPlayersByScore();
        refreshPlayersListView();
    }

    /**
     * Sortiert die Spielerliste nach Punktestand (hoechster zuerst).
     * Bei Gleichstand wird alphabetisch sortiert.
     */
    private void resortPlayersByScore() {
        if (playerNames == null || playerNames.isEmpty()) return;
        playerNames.sort((a, b) -> {
            int sa = lastScoreByDisplayName.getOrDefault(normalizeDisplayName(a), 0);
            int sb = lastScoreByDisplayName.getOrDefault(normalizeDisplayName(b), 0);
            int cmp = Integer.compare(sb, sa);
            if (cmp != 0) return cmp;
            return normalizeDisplayName(a).compareToIgnoreCase(normalizeDisplayName(b));
        });
    }

    /**
     * Plant einen Timer zum Zuruecksetzen der Scoring-Anzeige nach Ablauf der Sticky-Zeit.
     * Waehrend der Sticky-Zeit bleibt das Scoring-Overlay sichtbar auch wenn andere UI-Updates kommen.
     */
    private void scheduleScoringStickyRefresh() {
        long now = System.currentTimeMillis();
        long remaining = scoringStickyUntilMillis - now;
        if (remaining <= 0) return;
        if (scoringStickyRefresh != null) {
            scoringStickyRefresh.stop();
        }
        scoringStickyRefresh = new PauseTransition(Duration.millis(remaining));
        scoringStickyRefresh.setOnFinished(e -> {
            if (System.currentTimeMillis() < scoringStickyUntilMillis) {
                scheduleScoringStickyRefresh();
                return;
            }
            updateUiForPhase();
            if ("DRAW".equalsIgnoreCase(currentPhase)) {
                renderDrawHandUi();
            } else if ("DISCARD".equalsIgnoreCase(currentPhase)) {
                if (!handCards.isEmpty()) {
                    renderDiscardPricingUi();
                }
            } else if ("BUILD".equalsIgnoreCase(currentPhase) || "BUY".equalsIgnoreCase(currentPhase)) {
                renderBuildHandUi();
            }
        });
        scoringStickyRefresh.playFromStart();
    }

    // ==================== SPIELER-AKTIONEN HANDLER ====================

    /**
     * Verarbeitet die Nachricht welcher Spieler aktuell am Zug ist.
     * Aktualisiert die Anzeige und hebt den aktiven Spieler hervor.
     */
    private void handleCurrentPlayer(JsonObject payload) {
        if (payload == null || !payload.has("clientID")) return;

        int newCurrentPlayerId = payload.get("clientID").getAsInt();
        GLOBAL_CURRENT_TURN_ID = newCurrentPlayerId;
        this.currentTurnClientId = newCurrentPlayerId;

        Platform.runLater(() -> {
            if (activePlayerLabel != null) {
                String name = displayNameForClient(newCurrentPlayerId);
                activePlayerLabel.setText("Active: " + name);

                // Aktiven Spieler farblich hervorheben (gruen wenn selbst, weiss sonst)
                if (newCurrentPlayerId == myClientId) {
                    activePlayerLabel.setStyle("-fx-text-fill: #4CE18A; -fx-font-weight: bold;");
                } else {
                    activePlayerLabel.setStyle("-fx-text-fill: white;");
                }
            }
            updateUiForPhase();
            updateMarketBuyButtons();
        });
        
        // UI-Update triggern um Buttons freizuschalten
        if (UI_UPDATE_TRIGGER != null &&
                ("BUY".equalsIgnoreCase(currentPhase) || "REVEAL".equalsIgnoreCase(currentPhase))) {
            UI_UPDATE_TRIGGER.run();
        }
        if ("DRAW".equalsIgnoreCase(currentPhase)) {
            Platform.runLater(this::renderDrawHandUi);
        }
    }

    // ==================== TILE HANDLER ====================
    
    /**
     * Verarbeitet gezogene Tiles vom Server.
     * Fuegt das Tile zur Hand hinzu wenn es dem lokalen Spieler gehoert.
     * Ignoriert Tiles die anderen Spielern gehoeren.
     */
    private void handleTileDrawn(JsonObject payload) {
        syncClientId();

        if (!payload.has("clientID") || !payload.has("tile") || !payload.get("tile").isJsonObject()) return;

        int ownerId = payload.get("clientID").getAsInt();
        JsonObject tile = payload.getAsJsonObject("tile");
        int tileId = tile.has("id") ? tile.get("id").getAsInt() : Integer.MIN_VALUE;

        if (tileId == Integer.MIN_VALUE) return;

        log.info("[TILE_DRAWN] ownerId={}, tileId={}, myClientId={}", ownerId, tileId, myClientId);

        // Eigene ID muss bekannt sein
        if (myClientId == -1) {
            log.warn("handleTileDrawn ignored tile {} because myClientId is unknown (-1).", tileId);
            return;
        }

        // Tiles anderer Spieler ignorieren
        if (ownerId != myClientId) {
            log.info("[TILE_DRAWN] Ignoring tile {} - belongs to player {}, not me ({})", tileId, ownerId, myClientId);
            return;
        }
        
        // Tile zur Hand hinzufuegen
        log.info("[TILE_DRAWN] Adding tile {} to my hand. Current hand size before add: {}", tileId, handCards.size());
        addOrUpdateHandTile(tileId, tile);
        log.info("[TILE_DRAWN] Hand size after add: {}", handCards.size());

        // 4. Update UI based on phase
        if ("DISCARD".equalsIgnoreCase(currentPhase)) {
            renderDiscardPricingUi();
        } else if ("DRAW".equalsIgnoreCase(currentPhase)) {
            renderDrawHandUi();
        } else {
            renderBuildHandUi();
        }
    }

    private void handleAvailableTiles(JsonObject payload) {
        if ("DRAW".equalsIgnoreCase(currentPhase) || "DISCARD".equalsIgnoreCase(currentPhase)) {
            log.debug("Ignoring AvailableTiles update during {} phase to prevent duplication.", currentPhase);
            return;
        }
        if (payload == null) return;
        // Accept both "availableTiles" (UI mapping) and backend "tiles" payloads
        JsonArray ids = null;
        if (payload.has("availableTiles") && payload.get("availableTiles").isJsonArray()) {
            ids = payload.getAsJsonArray("availableTiles");
        } else if (payload.has("tiles") && payload.get("tiles").isJsonArray()) {
            ids = payload.getAsJsonArray("tiles");
        }
        if (ids == null) return;

        if (payload.has("clientID") && payload.get("clientID").isJsonPrimitive()) {
            int target = payload.get("clientID").getAsInt();
            log.debug("AvailableTiles BUILD payload: target={} myClientId={} tiles={}", target, myClientId, ids.size());
            // If this available-tiles update is for another client, stash it for later rendering.
            if (myClientId >= 0 && target != myClientId) {
                pendingBuildAvailableTiles = ids;
                return;
            }
            // If we get here, these available tiles are for this client: populate the hand UI.
            processBuildAvailableTiles(ids);
            return;
        }

        // No clientID -> BUY/REVEAL market offers. Do NOT touch the hand.
        JsonArray buyIds = new JsonArray();
        for (JsonElement el : ids) {
            if (el == null) continue;
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                buyIds.add(el.getAsInt());
                continue;
            }
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("tileID") && obj.get("tileID").isJsonPrimitive()) {
                buyIds.add(obj.get("tileID").getAsInt());
            }
            if (obj.has("tile") && obj.get("tile").isJsonObject()) {
                JsonObject tileJson = obj.getAsJsonObject("tile");
                int tileId = tileJson.has("id") ? tileJson.get("id").getAsInt() : Integer.MIN_VALUE;
                if (tileId != Integer.MIN_VALUE) {
                    cacheTileDefinition(tileId, tileJson);
                }
            }
        }
        log.debug("AvailableTiles BUY payload: offers={} ids={}", ids.size(), buyIds.size());
        if (looksLikeBuyNext()) {
            processBuyAvailableTileIds(buyIds);
        } else {
            pendingBuyAvailableTiles = buyIds;
        }
    }

    /**
     * Verarbeitet die Preisoffenbarung vom Server.
     * Speichert alle Angebote (Tile-ID, Verkaeufer, Preis) im globalen Cache
     * und triggert ein UI-Update um den Markt anzuzeigen.
     */
    private void handleRevealTilePrices(JsonObject payload) {
        log.debug("Prices Received (Handled by Ghost or Real Controller)");

        if (payload == null || !payload.has("tilePrices")) return;

        // Cache leeren und neu befuellen
        GLOBAL_OFFER_CACHE.clear();

        JsonArray sellers = payload.getAsJsonArray("tilePrices");
        for (JsonElement sellerEl : sellers) {
            if (!sellerEl.isJsonObject()) continue;
            JsonObject sellerObj = sellerEl.getAsJsonObject();

            if (!sellerObj.has("clientID") || !sellerObj.has("prices")) continue;
            int sellerClientId = sellerObj.get("clientID").getAsInt();

            // Preise des Verkaeufers durchgehen
            JsonArray prices = sellerObj.getAsJsonArray("prices");
            for (JsonElement pEl : prices) {
                if (!pEl.isJsonObject()) continue;
                JsonObject p = pEl.getAsJsonObject();

                int tileId = -1;
                if (p.has("tileID")) tileId = p.get("tileID").getAsInt();
                else if (p.has("TileID")) tileId = p.get("TileID").getAsInt();

                if (tileId == -1) continue;

                // Verworfene Tiles ueberspringen
                boolean discard = p.has("discard") && p.get("discard").getAsBoolean();
                if (discard) continue;

                if (!p.has("price") || p.get("price").isJsonNull()) continue;
                int price = p.get("price").getAsInt();
                GLOBAL_OFFER_CACHE.put(tileId, new Offer(sellerClientId, price));
            }
        }

        // UI aktualisieren
        if (UI_UPDATE_TRIGGER != null) {
            log.debug("Triggering active UI update...");
            UI_UPDATE_TRIGGER.run();
        } else {
            log.error("UI_UPDATE_TRIGGER is null! No active UI linked.");
        }
    }

    // ==================== TILE VERARBEITUNG ====================

    /**
     * Verarbeitet verfuegbare Tiles fuer die BUILD-Phase.
     * Erstellt fuer jedes Tile eine Handkarte mit Klick-Handler.
     */
    private void processBuildAvailableTiles(JsonArray tiles) {
        clearHandAndMarket();
        for (JsonElement el : tiles) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            if (!t.has("tileID") || !t.has("tile")) continue;
            int tileId = t.get("tileID").getAsInt();
            JsonObject tileJson = t.getAsJsonObject("tile");
            addOrUpdateHandTile(tileId, tileJson);
        }
        renderBuildHandUi();
    }

    private void handleTileBuilt(JsonObject payload) {
        if (!payload.has("tileID") || !payload.has("x_position") || !payload.has("y_position")) return;

        int clientID = payload.has("clientID") ? payload.get("clientID").getAsInt() : -1;
        log.debug("handleTileBuilt clientID={} myClientId={} payload={}", clientID, myClientId, payload);

        int tileID = payload.get("tileID").getAsInt();
        int x = payload.get("x_position").getAsInt();
        int y = payload.get("y_position").getAsInt();
        int rotation = payload.has("rotation") ? payload.get("rotation").getAsInt() : 0;

        // Platzierung pro Spieler tracken fuer Anzeige fremder Territorien
        JsonObject tileJson = tileDefinitions.get(tileID);
        if (tileJson == null) {
            tileJson = new JsonObject();
            tileJson.addProperty("id", tileID);
        }
        tileDefinitions.put(tileID, tileJson);
        placedTilesByClientId
                .computeIfAbsent(clientID, k -> new java.util.LinkedHashMap<>())
                .put(new Coord(x, y), new PlacedTileState(tileJson, rotation));

        // Bei Anzeige des Territoriums dieses Spielers neu zeichnen
        if (selectedTerritoryClientId != null && selectedTerritoryClientId.equals(clientID)) {
            Platform.runLater(this::refreshTerritoryView);
        }

        // Nur eigene Builds verwalten (Hand/Drag-Status)
        if (clientID != myClientId) {
            return;
        }

        log.debug("TileBuilt accepted tileID={} x={} y={} rot={} pendingBefore={}",
                tileID, x, y, rotation, pendingPlacements.size());
        if (pendingPlacements.containsKey(tileID)) {
            removePreviewTile(tileID);
            updateFinishBuildButton();
        }
        if (buildFinalizing && pendingPlacements.isEmpty()) {
            buildFinalizing = false;
            updateFinishBuildButton();
            
            // Als fertig markieren wenn alle Tiles platziert sind
            playersReadyInPhase.add(myClientId);
            Platform.runLater(this::refreshPlayersListView);
        }
        placeTileOnBoard(tileID, x, y, rotation);
        removeHandTile(tileID);
        selectedHandTileId = null;
        stopDragPlacement();
        setStatus("Tile built successfully at (" + x + "," + y + ")");
        updatePlacementHints();
    }

    /**
     * Verarbeitet einen Tile-Kauf vom Server.
     * Aktualisiert Markt, entfernt gekauftes Tile, und aktualisiert den naechsten Spieler.
     */
    private void handleTilePurchased(JsonObject payload) {
        if (!payload.has("buyerClientID") || !payload.has("sellerClientID")) return;

        int buyerId = payload.get("buyerClientID").getAsInt();
        int sellerId = payload.get("sellerClientID").getAsInt();
        
        // Kaeufer als fertig markieren
        playersReadyInPhase.add(buyerId);
        Platform.runLater(this::refreshPlayersListView);
        
        // Naechsten Spieler setzen falls vorhanden
        if (payload.has("nextPlayerID") && !payload.get("nextPlayerID").isJsonNull()) {
            int nextId = payload.get("nextPlayerID").getAsInt();
            GLOBAL_CURRENT_TURN_ID = nextId;
            currentTurnClientId = nextId;

            Platform.runLater(() -> {
                updateUiForPhase();
                updateMarketBuyButtons();
            });

            if (UI_UPDATE_TRIGGER != null && "BUY".equalsIgnoreCase(currentPhase)) {
                UI_UPDATE_TRIGGER.run();
            }
        }

        // Gekauftes Tile verarbeiten
        if (payload.has("tileID") && !payload.get("tileID").isJsonNull()) {
            int tileId = payload.get("tileID").getAsInt();

            log.info("Tile {} purchased by {} from {}", tileId, buyerId, sellerId);


            GLOBAL_OFFER_CACHE.remove(tileId);
            MarketOfferCard removed = marketCards.remove(tileId);

            Platform.runLater(() -> {

                if (removed != null && removed.container != null) {
                    marketPane.getChildren().remove(removed.container);
                }

                if (selectedMarketTileId != null && selectedMarketTileId == tileId) {
                    selectedMarketTileId = null;
                    updateMarketSelectionUi();
                }

                if (buyerId == myClientId) {
                    setStatus("You bought tile " + tileId + ".");
                    JsonObject tileDef = tileDefinitions.get(tileId);
                    if (tileDef == null) {
                        tileDef = new JsonObject();
                        tileDef.addProperty("id", tileId);
                    }
                    addOrUpdateHandTile(tileId, tileDef);

                    if ("BUILD".equalsIgnoreCase(currentPhase)) {
                        renderBuildHandUi();
                    }}
                if (sellerId == myClientId) {
                    log.debug("My tile {} was sold. Removing from hand.", tileId);
                    removeHandTile(tileId);
                }
            });

        } else {
            if (buyerId == myClientId) {
                Platform.runLater(() -> setStatus("You passed."));
            }
        }
    }

    private void handleTileReturned(JsonObject payload) {
        if (!payload.has("clientID") || !payload.has("tileID")) return;

        int ownerId = payload.get("clientID").getAsInt();
        int tileId = payload.get("tileID").getAsInt();

        log.debug("handleTileReturned ownerId={} myClientId={} tileId={} pendingBefore={}",
                ownerId, myClientId, tileId, pendingPlacements.size());
        log.debug("Tile {} returned to {}", tileId, ownerId);
        if (ownerId == myClientId && pendingPlacements.containsKey(tileId)) {
            removePreviewTile(tileId);
            renderBuildHandUi();
            updateFinishBuildButton();
        }
        GLOBAL_OFFER_CACHE.remove(tileId);

        Platform.runLater(() -> {
            renderWholeMarket();

            if (ownerId == myClientId) {

                if (pendingDiscardTileId != null && tileId == pendingDiscardTileId.intValue()) {
                    log.debug("Confirmed discard for tile {}. REMOVING from hand.", tileId);
                    removeHandTile(tileId);
                    return;
                }

                boolean isCleanupPhase = "DRAW".equalsIgnoreCase(currentPhase) ||
                        "DISCARD".equalsIgnoreCase(currentPhase) ||
                        "REVEAL".equalsIgnoreCase(currentPhase) ||
                        "SCORING".equalsIgnoreCase(currentPhase) ||
                        "BUILD".equalsIgnoreCase(currentPhase);

                if (isCleanupPhase) {
                    log.info("TILE_RETURNED cleanup: removing tile {} from hand in phase {}",
                            tileId, currentPhase);
                    removeHandTile(tileId);
                } else {
                    log.debug("Unsold tile {} returning to hand.", tileId);
                    JsonObject tileDef = tileDefinitions.get(tileId);
                    if (tileDef == null) {
                        tileDef = new JsonObject();
                        tileDef.addProperty("id", tileId);
                    }
                    addOrUpdateHandTile(tileId, tileDef);

                    if ("BUILD".equalsIgnoreCase(currentPhase)) {
                        renderBuildHandUi();
                    }
                }
            }
        });
    }

    private void handleCoinUpdate(JsonObject payload) {
        if (!payload.has("clientID") || !payload.has("coins")) return;
        int clientId = payload.get("clientID").getAsInt();
        if (clientId != myClientId) return;
        int newCoins = payload.get("coins").getAsInt();
        int fromCoins;

        if (displayedCoins == null) {
            // First update: no animation (avoids weird count-up from 0 on join).
            displayedCoins = newCoins;
            coinsLabel.setText("Coins: " + newCoins);
            updateMarketBuyButtons();
            return;
        }

        fromCoins = displayedCoins;
        int delta = newCoins - fromCoins;
        if (delta == 0) {
            coinsLabel.setText("Coins: " + newCoins);
            displayedCoins = newCoins;
            updateMarketBuyButtons();
            return;
        }

        // Cancel any in-flight animations.
        if (coinsCountTimeline != null) {
            coinsCountTimeline.stop();
        }
        if (coinsDeltaTransition != null) {
            coinsDeltaTransition.stop();
        }

        // Count-up animation (smooth, ~0.6s).
        final int start = fromCoins;
        final int end = newCoins;
        coinsCountTimeline = new Timeline(
                new KeyFrame(Duration.ZERO, e -> coinsLabel.setText("Coins: " + start)),
                new KeyFrame(Duration.millis(600), new KeyValue(coinsLabel.textProperty(), "Münzen: " + end, Interpolator.EASE_BOTH))
        );

        // If we can't reliably tween integer values via textProperty, do an explicit per-tick update.
        coinsCountTimeline.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO),
                new KeyFrame(Duration.millis(600))
        );
        coinsCountTimeline.currentTimeProperty().addListener((obs, oldT, newT) -> {
            double t = Math.min(1.0, Math.max(0.0, newT.toMillis() / 600.0));
            // Ease in/out
            double eased = Interpolator.EASE_BOTH.interpolate(0.0, 1.0, t);
            int v = (int) Math.round(start + (end - start) * eased);
            coinsLabel.setText("Coins: " + v);
        });
        coinsCountTimeline.setOnFinished(e -> {
            displayedCoins = end;
            coinsLabel.setText("Coins: " + end);
            updateMarketBuyButtons();
        });
        coinsCountTimeline.playFromStart();

        // If this update carries an explicit "income" field, show a small pulse animation
        // so the user notices income being granted.
        int income = 0;
        if (payload.has("income") && payload.get("income").isJsonPrimitive()) {
            try {
                income = payload.get("income").getAsInt();
            } catch (Exception ignored) {
            }
        }

        // Floating delta: +N green, -N red, fade + slide up.
        if (coinsDeltaLabel != null) {
            coinsDeltaLabel.setManaged(false);
            coinsDeltaLabel.setVisible(true);
            coinsDeltaLabel.setOpacity(1.0);
            coinsDeltaLabel.setTranslateY(0);
            coinsDeltaLabel.setText((delta > 0 ? "+" : "") + delta);
            coinsDeltaLabel.setStyle(delta > 0
                    ? "-fx-text-fill: #4CE18A;"
                    : "-fx-text-fill: #FF5A5A;");

            FadeTransition fade = new FadeTransition(Duration.millis(850), coinsDeltaLabel);
            fade.setFromValue(1.0);
            fade.setToValue(0.0);

            TranslateTransition slide = new TranslateTransition(Duration.millis(850), coinsDeltaLabel);
            slide.setFromY(0);
            slide.setToY(-14);
            slide.setInterpolator(Interpolator.EASE_OUT);

            coinsDeltaTransition = new ParallelTransition(fade, slide);
            coinsDeltaTransition.setOnFinished(ev -> {
                coinsDeltaLabel.setVisible(false);
                coinsDeltaLabel.setOpacity(1.0);
                coinsDeltaLabel.setTranslateY(0);
            });
            coinsDeltaTransition.playFromStart();
            // If this update carries an explicit "income" field, show a small pulse animation
            // so the user notices income being granted.
            if (income > 0 && coinsLabel != null) {
                DropShadow ds = new DropShadow(14, Color.web("#FFD54A"));
                coinsLabel.setEffect(ds);
                ScaleTransition pulse = new ScaleTransition(Duration.millis(380), coinsLabel);
                pulse.setFromX(1.0);
                pulse.setFromY(1.0);
                pulse.setToX(1.15);
                pulse.setToY(1.15);
                pulse.setAutoReverse(true);
                pulse.setCycleCount(2);
                pulse.setOnFinished(ev -> coinsLabel.setEffect(null));
                pulse.play();
            }
        }
    }
    private boolean looksLikeBuyNext() {
        return "REVEAL".equalsIgnoreCase(currentPhase) || "BUY".equalsIgnoreCase(currentPhase);
    }


    private void processBuyAvailableTileIds(JsonArray ids) {
        if (ids == null) return;

        java.util.Set<Integer> allowed = new java.util.HashSet<>();
        for (JsonElement el : ids) {
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                allowed.add(el.getAsInt());
            }
        }

        marketPane.getChildren().clear();
        marketCards.clear();
        selectedMarketTileId = null;

        if (marketTitleLabel != null) {
            marketTitleLabel.setText("Market • Select a tile, then press Buy");
        }


        for (Map.Entry<Integer, Offer> e : revealedOffers.entrySet()) {
            int tileId = e.getKey();
            Offer offer = e.getValue();

            if (!allowed.contains(tileId)) continue;

            JsonObject tileJson = tileDefinitions.get(tileId);
            if (tileJson == null) {
                tileJson = new JsonObject();
                tileJson.addProperty("id", tileId);
                tileJson.addProperty("top", "none");
                tileJson.addProperty("right", "none");
                tileJson.addProperty("down", "none");
                tileJson.addProperty("left", "none");
                tileDefinitions.put(tileId, tileJson);
            }

            TileView view = new TileView(tileJson, TILE_SIZE);
            String sellerName = displayNameForClient(offer.sellerClientId());
            Label info = new Label("Seller: " + sellerName + "\nPrice: " + offer.price());
            info.setWrapText(true);

            Label hint = new Label("Click to select");
            hint.setStyle("-fx-opacity: 0.7; -fx-font-size: 11;");

            VBox cardBox = new VBox(6, view, info, hint);
            cardBox.setPrefWidth(180);
            cardBox.setStyle("-fx-padding: 8; -fx-background-color: #ffffff; -fx-border-color: #d0d0d0; -fx-border-radius: 8; -fx-background-radius: 8;");
            cardBox.setOnMouseClicked(ev -> selectMarketTile(tileId));

            marketPane.getChildren().add(cardBox);
            marketCards.put(tileId, new MarketOfferCard(tileId, tileJson, offer.sellerClientId(), offer.price(), view, cardBox, null));
        }

        updateMarketSelectionUi();
        updateUiForPhase();
    }

    /**
     * Renders market tiles with Buy button on each card and Continue button on the right.
     */
    private void renderWholeMarket() {
        marketPane.getChildren().clear();
        marketCards.clear();
        selectedMarketTileId = null;

        marketPane.setStyle("-fx-background-color: transparent;");

        // Hide old buy/pass buttons from actions panel
        if (buyButton != null) {
            buyButton.setVisible(false);
            buyButton.setManaged(false);
        }
        if (passButton != null) {
            passButton.setVisible(false);
            passButton.setManaged(false);
        }

        for (Map.Entry<Integer, Offer> entry : GLOBAL_OFFER_CACHE.entrySet()) {
            int tileId = entry.getKey();
            Offer offer = entry.getValue();

            // Skip own tiles
            if (myClientId >= 0 && offer.sellerClientId() == myClientId) {
                continue;
            }
            
            JsonObject tileJson = tileDefinitions.get(tileId);
            if (tileJson == null) {
                tileJson = new JsonObject();
                tileJson.addProperty("id", tileId);
                tileJson.addProperty("top", "field"); tileJson.addProperty("right", "field");
                tileJson.addProperty("down", "field"); tileJson.addProperty("left", "field");
                tileDefinitions.put(tileId, tileJson);
            }
            
            TileView view = new TileView(tileJson, TILE_SIZE);

            // Seller label
            String sellerName = displayNameForClient(offer.sellerClientId());
            Label sellerLabel = new Label("From: " + sellerName);
            sellerLabel.getStyleClass().add("market-tile-seller");

            // Price display with coin icon
            HBox priceBox = new HBox(4);
            priceBox.setAlignment(javafx.geometry.Pos.CENTER);
            Label coinIcon = new Label("\uD83E\uDE99"); // 🪙 coin emoji
            coinIcon.setStyle("-fx-font-size: 16px;");
            Label priceLabel = new Label(String.valueOf(offer.price()));
            priceLabel.getStyleClass().add("market-tile-price");
            priceBox.getChildren().addAll(coinIcon, priceLabel);

            // Green BUY button for this tile
            Button buyTileBtn = new Button("Buy");
            buyTileBtn.getStyleClass().add("buy-tile-button");
            final int finalTileId = tileId;

            // Initial state: disable immediately if it's not our turn or we can't afford it.
            // (displayedCoins may be null early on; in that case we only gate by turn.)
            boolean canAffordNow = (displayedCoins == null) || (offer.price() <= displayedCoins);
            buyTileBtn.setDisable(!(isMyTurnToBuy() && canAffordNow));

            buyTileBtn.setOnAction(e -> {
                selectedMarketTileId = finalTileId;
                onBuy(); // Trigger buy action
            });

            // Card container
            VBox cardBox = new VBox(8);
            cardBox.setAlignment(javafx.geometry.Pos.CENTER);
            cardBox.setPrefWidth(155);
            cardBox.getStyleClass().add("market-tile-card");
            
            // Add margin to prevent clipping on hover
            javafx.geometry.Insets cardMargin = new javafx.geometry.Insets(5, 5, 5, 5);
            javafx.scene.layout.FlowPane.setMargin(cardBox, cardMargin);

            cardBox.getChildren().addAll(view, sellerLabel, priceBox, buyTileBtn);

            marketPane.getChildren().add(cardBox);
            marketCards.put(tileId, new MarketOfferCard(tileId, tileJson, offer.sellerClientId(), offer.price(), view, cardBox, buyTileBtn));
        }

        if (marketPane.getParent() != null) {
            marketPane.getParent().setVisible(true);
        }

        updateMarketBuyButtons();
    }
    
    private void placeTileOnBoard(int tileId, int x, int y, int rotation) {
        placeTileOnBoard(tileId, x, y, rotation, true);
    }

    private void placeTileOnBoard(int tileId, int x, int y, int rotation, boolean updateHints) {
        StackPane cell = boardCells.get(new Coord(x, y));
        if (cell == null) {
            System.err.println("Error: Trying to place tile on invalid cell: " + x + "," + y);
            return;
        }

        cell.getChildren().removeIf(n -> n instanceof TileView);

        JsonObject tileJson = tileDefinitions.get(tileId);
        if (tileJson == null) {
            tileJson = new JsonObject();
            tileJson.addProperty("id", tileId);
            tileJson.addProperty("top", "none");
            tileJson.addProperty("right", "none");
            tileJson.addProperty("down", "none");
            tileJson.addProperty("left", "none");
        }

        TileView view = new TileView(tileJson, TILE_SIZE);
        view.setRotate(rotation);
        view.setMouseTransparent(true);
        cell.getChildren().add(view);
        placedTiles.put(new Coord(x, y), new PlacedTileState(tileJson, rotation));
        if (updateHints) {
            updatePlacementHints();
        }
    }

    private void clearPlacementHints() {
        for (Coord c : highlightedCells) {
            setCellHighlight(c, false);
        }
        highlightedCells.clear();
    }

    /**
     * Aktualisiert die legalen Platzierungshints für das ausgewählte Tile.
     */
    public void updatePlacementHints() {
        // Spectator mode: never show local legal-placement highlights on someone else's territory.
        if (selectedTerritoryClientId != null && selectedTerritoryClientId != myClientId) {
            clearPlacementHints();
            return;
        }

        clearPlacementHints();

        if (!"BUILD".equalsIgnoreCase(currentPhase)) {
            return;
        }
        if (selectedHandTileId == null) {
            return;
        }
        TileCard selected = handCards.get(selectedHandTileId);
        if (selected == null) {
            return;
        }
        JsonObject tileJson = selected.tileJson;
        if (tileJson == null) {
            return;
        }

        java.util.Set<Coord> connectedPending = getConnectedPendingPlacements();
        java.util.Set<Coord> candidates = candidatePositions(connectedPending);
        java.util.Map<Coord, PlacedTileState> effectiveTiles = buildEffectiveTileMap();

        boolean anyLegalAnyRotation = false;
        boolean anyLegalCurrentRotation = false;

        for (Coord pos : candidates) {
            boolean legalCurrentRotation = false;
            for (int rot : new int[]{0, 90, 180, 270}) {
                if (canPlaceLocalCached(tileJson, pos.x, pos.y, rot, connectedPending, effectiveTiles)) {
                    anyLegalAnyRotation = true;
                    if (rot == selectedRotation) {
                        anyLegalCurrentRotation = true;
                        legalCurrentRotation = true;
                    }
                }
            }

            if (legalCurrentRotation) {
                setCellHighlight(pos, true);
                highlightedCells.add(pos);
            }
        }

        if (!anyLegalAnyRotation) {
            setStatus("No legal placement for this tile (click any cell to return it).");
        } else if (!anyLegalCurrentRotation) {
            setStatus("No legal placement for rotation " + selectedRotation + " - try rotating.");
        }
    }

    private void setCellHighlight(Coord coord, boolean highlighted) {
        StackPane cell = boardCells.get(coord);
        if (cell == null) return;
        Object bgObj = cell.getProperties().get("bg");
        if (!(bgObj instanceof Rectangle bg)) return;

        if (highlighted) {
            if (!bg.getStyleClass().contains("state-cell-highlighted")) {
                bg.getStyleClass().add("state-cell-highlighted");
            }
        } else {
            bg.getStyleClass().remove("state-cell-highlighted");
        }
    }

    private java.util.Map<Coord, PreviewInfo> buildPendingIndex() {
        java.util.Map<Coord, PreviewInfo> index = new java.util.HashMap<>();
        for (PreviewInfo info : pendingPlacements.values()) {
            index.put(new Coord(info.x, info.y), info);
        }
        return index;
    }

    private boolean isAdjacentToPlaced(Coord coord) {
        if (coord == null) return false;
        return placedTiles.containsKey(new Coord(coord.x + 1, coord.y))
                || placedTiles.containsKey(new Coord(coord.x - 1, coord.y))
                || placedTiles.containsKey(new Coord(coord.x, coord.y + 1))
                || placedTiles.containsKey(new Coord(coord.x, coord.y - 1));
    }

    private java.util.Set<Coord> getConnectedPendingPlacements() {
        if (pendingPlacements.isEmpty()) return new java.util.HashSet<>();
        java.util.Map<Coord, PreviewInfo> pendingByCoord = buildPendingIndex();
        java.util.Set<Coord> connected = new java.util.HashSet<>();
        java.util.Deque<Coord> queue = new java.util.ArrayDeque<>();

        if (!placedTiles.isEmpty()) {
            for (Coord coord : pendingByCoord.keySet()) {
                if (isAdjacentToPlaced(coord)) {
                    if (connected.add(coord)) {
                        queue.add(coord);
                    }
                }
            }
        } else {
            Coord origin = new Coord(0, 0);
            if (pendingByCoord.containsKey(origin)) {
                connected.add(origin);
                queue.add(origin);
            }
        }

        while (!queue.isEmpty()) {
            Coord current = queue.removeFirst();
            Coord[] neighbors = {
                    new Coord(current.x + 1, current.y),
                    new Coord(current.x - 1, current.y),
                    new Coord(current.x, current.y + 1),
                    new Coord(current.x, current.y - 1)
            };
            for (Coord n : neighbors) {
                if (!pendingByCoord.containsKey(n)) continue;
                if (connected.add(n)) {
                    queue.addLast(n);
                }
            }
        }

        return connected;
    }

    private java.util.Set<Coord> candidatePositions() {
        return candidatePositions(getConnectedPendingPlacements());
    }

    private java.util.Set<Coord> candidatePositions(java.util.Set<Coord> connectedPending) {
        java.util.Set<Coord> spots = new java.util.HashSet<>();
        if (placedTiles.isEmpty() && pendingPlacements.isEmpty()) {
            spots.add(new Coord(0, 0));
            return spots;
        }

        java.util.Set<Coord> anchors = new java.util.HashSet<>(placedTiles.keySet());
        if (connectedPending != null && !connectedPending.isEmpty()) {
            anchors.addAll(connectedPending);
        }

        for (Coord pos : anchors) {
            int x = pos.x;
            int y = pos.y;
            Coord[] neighbors = {
                    new Coord(x, y + 1),
                    new Coord(x, y - 1),
                    new Coord(x + 1, y),
                    new Coord(x - 1, y)
            };
            for (Coord n : neighbors) {
                if (n.x < BOARD_MIN || n.x > BOARD_MAX || n.y < BOARD_MIN || n.y > BOARD_MAX) continue;
                if (placedTiles.containsKey(n)) continue;
                spots.add(n);
            }
        }

        return spots;
    }

    private boolean hasAnyLegalPlacementLocal(JsonObject tileJson) {
        if (tileJson == null) return false;
        for (Coord pos : candidatePositions()) {
            for (int rot : new int[]{0, 90, 180, 270}) {
                if (canPlaceLocal(tileJson, pos.x, pos.y, rot)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canPlaceLocal(JsonObject tileJson, int x, int y, int rotationDegrees) {
        if (tileJson == null) return false;
        if (!isValidRotation(rotationDegrees)) return false;

        if (x < BOARD_MIN || x > BOARD_MAX || y < BOARD_MIN || y > BOARD_MAX) return false;

        if (getEffectiveTileState(x, y) != null) return false;

        if (placedTiles.isEmpty() && pendingPlacements.isEmpty()) {
            return x == 0 && y == 0;
        }

        java.util.Set<Coord> connectedPending = getConnectedPendingPlacements();
        boolean hasNeighbor = false;
        boolean hasAnchorNeighbor = false;

        PlacedTileState up = getEffectiveTileState(x, y + 1);
        if (up != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(x, y + 1)) || connectedPending.contains(new Coord(x, y + 1))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.TOP, up, Side.DOWN)) return false;
        }
        PlacedTileState down = getEffectiveTileState(x, y - 1);
        if (down != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(x, y - 1)) || connectedPending.contains(new Coord(x, y - 1))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.DOWN, down, Side.TOP)) return false;
        }
        PlacedTileState right = getEffectiveTileState(x + 1, y);
        if (right != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(x + 1, y)) || connectedPending.contains(new Coord(x + 1, y))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.RIGHT, right, Side.LEFT)) return false;
        }
        PlacedTileState left = getEffectiveTileState(x - 1, y);
        if (left != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(new Coord(x - 1, y)) || connectedPending.contains(new Coord(x - 1, y))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.LEFT, left, Side.RIGHT)) return false;
        }

        if (!hasNeighbor) return false;
        if (placedTiles.isEmpty()) {
            return x == 0 && y == 0;
        }
        return hasAnchorNeighbor;
    }

    private java.util.Map<Coord, PlacedTileState> buildEffectiveTileMap() {
        java.util.Map<Coord, PlacedTileState> map = new java.util.HashMap<>(placedTiles);
        for (PreviewInfo info : pendingPlacements.values()) {
            JsonObject json = tileDefinitions.get(info.tileId);
            map.put(new Coord(info.x, info.y), new PlacedTileState(json, info.rotation));
        }
        return map;
    }

    private boolean canPlaceLocalCached(JsonObject tileJson,
                                        int x,
                                        int y,
                                        int rotationDegrees,
                                        java.util.Set<Coord> connectedPending,
                                        java.util.Map<Coord, PlacedTileState> effectiveTiles) {
        if (tileJson == null) return false;
        if (!isValidRotation(rotationDegrees)) return false;
        if (x < BOARD_MIN || x > BOARD_MAX || y < BOARD_MIN || y > BOARD_MAX) return false;

        if (effectiveTiles.get(new Coord(x, y)) != null) return false;

        if (placedTiles.isEmpty() && pendingPlacements.isEmpty()) {
            return x == 0 && y == 0;
        }

        boolean hasNeighbor = false;
        boolean hasAnchorNeighbor = false;

        Coord upCoord = new Coord(x, y + 1);
        PlacedTileState up = effectiveTiles.get(upCoord);
        if (up != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(upCoord) || (connectedPending != null && connectedPending.contains(upCoord))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.TOP, up, Side.DOWN)) return false;
        }
        Coord downCoord = new Coord(x, y - 1);
        PlacedTileState down = effectiveTiles.get(downCoord);
        if (down != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(downCoord) || (connectedPending != null && connectedPending.contains(downCoord))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.DOWN, down, Side.TOP)) return false;
        }
        Coord rightCoord = new Coord(x + 1, y);
        PlacedTileState right = effectiveTiles.get(rightCoord);
        if (right != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(rightCoord) || (connectedPending != null && connectedPending.contains(rightCoord))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.RIGHT, right, Side.LEFT)) return false;
        }
        Coord leftCoord = new Coord(x - 1, y);
        PlacedTileState left = effectiveTiles.get(leftCoord);
        if (left != null) {
            hasNeighbor = true;
            if (placedTiles.containsKey(leftCoord) || (connectedPending != null && connectedPending.contains(leftCoord))) {
                hasAnchorNeighbor = true;
            }
            if (!edgesCompatible(tileJson, rotationDegrees, Side.LEFT, left, Side.RIGHT)) return false;
        }

        if (!hasNeighbor) return false;
        if (placedTiles.isEmpty()) {
            return x == 0 && y == 0;
        }
        return hasAnchorNeighbor;
    }

    private static boolean isValidRotation(int rotationDegrees) {
        return rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270;
    }

    private static boolean edgesCompatible(JsonObject tileJson,
                                           int rotationDegrees,
                                           Side side,
                                           PlacedTileState neighbor,
                                           Side neighborSide) {
        if (neighbor == null || neighbor.tileJson == null) return false;

        String a = edgeAt(tileJson, side, rotationDegrees);
        String b = edgeAt(neighbor.tileJson, neighborSide, neighbor.rotationDegrees);
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    private static String edgeAt(JsonObject tileJson, Side side, int rotationDegrees) {
        String top = getString(tileJson, "top");
        String right = getString(tileJson, "right");
        String down = getString(tileJson, "down");
        String left = getString(tileJson, "left");

        int steps = Math.floorMod(rotationDegrees / 90, 4);
        return switch (steps) {
            case 0 -> switch (side) {
                case TOP -> top;
                case RIGHT -> right;
                case DOWN -> down;
                case LEFT -> left;
            };
            case 1 -> switch (side) {
                case TOP -> left;
                case RIGHT -> top;
                case DOWN -> right;
                case LEFT -> down;
            };
            case 2 -> switch (side) {
                case TOP -> down;
                case RIGHT -> left;
                case DOWN -> top;
                case LEFT -> right;
            };
            case 3 -> switch (side) {
                case TOP -> right;
                case RIGHT -> down;
                case DOWN -> left;
                case LEFT -> top;
            };
            default -> null;
        };
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private void clearHandAndMarket() {
        handPane.getChildren().clear();
        marketPane.getChildren().clear();
        priceCards.clear();
        handCards.clear();
        marketCards.clear();
        selectedHandTileId = null;
        selectedMarketTileId = null;
        discardToggle.getToggles().clear();
    }

    private void addOrUpdateHandTile(int tileId, JsonObject tileJson) {
        if (pendingDiscardTileId != null && tileId == pendingDiscardTileId.intValue()) {
            log.debug("BLOCKED re-adding discarded tile: {}", tileId);
            return;
        }
        // Merge incoming partial tile JSON with the loaded tile definitions so we don't
        // accidentally drop sprite coordinates (spriteX/spriteY) when the server
        // sends only a minimal tile object.
        JsonObject existing = tileDefinitions.get(tileId);
        JsonObject normalized = new JsonObject();
        // Start with existing (full) definition if available
        if (existing != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : existing.entrySet()) {
                normalized.add(e.getKey(), e.getValue());
            }
        }
        // Overlay with incoming values (if any)
        if (tileJson != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : tileJson.entrySet()) {
                normalized.add(e.getKey(), e.getValue());
            }
        }
        if (!normalized.has("id")) normalized.addProperty("id", tileId);
        tileDefinitions.put(tileId, normalized);
        handCards.put(tileId, new TileCard(tileId, normalized, new TileView(normalized, TILE_SIZE)));
    }

    private void cacheTileDefinition(int tileId, JsonObject tileJson) {
        JsonObject existing = tileDefinitions.get(tileId);
        JsonObject normalized = new JsonObject();
        if (existing != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : existing.entrySet()) {
                normalized.add(e.getKey(), e.getValue());
            }
        }
        if (tileJson != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : tileJson.entrySet()) {
                normalized.add(e.getKey(), e.getValue());
            }
        }
        if (!normalized.has("id")) normalized.addProperty("id", tileId);
        tileDefinitions.put(tileId, normalized);
    }

    private void removeHandTile(int tileId) {
        handCards.remove(tileId);
        if ("BUILD".equalsIgnoreCase(currentPhase)) {
            renderBuildHandUi();
        } else if ("DISCARD".equalsIgnoreCase(currentPhase)) {
            renderDiscardPricingUi();
        }
        updatePlacementHints();
    }

    private void renderDiscardPricingUi() {
        if (handPane != null) handPane.getChildren().clear();
        priceCards.clear();
        discardToggle.getToggles().clear();


        for (TileCard card : handCards.values()) {
            TileView view = new TileView(card.tileJson, TILE_SIZE);
            
            // === TILE CONTAINER WITH X BUTTON IN CORNER ===
            javafx.scene.layout.StackPane tileWrapper = new javafx.scene.layout.StackPane();
            tileWrapper.getChildren().add(view);
            
            // X button in top-right corner
            javafx.scene.layout.StackPane xButton = new javafx.scene.layout.StackPane();
            xButton.getStyleClass().add("discard-x-button");
            xButton.setMinSize(28, 28);
            xButton.setPrefSize(28, 28);
            xButton.setMaxSize(28, 28);
            javafx.scene.control.Label xIcon = new javafx.scene.control.Label("✕");
            xIcon.getStyleClass().add("discard-x-icon");
            xButton.getChildren().add(xIcon);
            javafx.scene.layout.StackPane.setAlignment(xButton, javafx.geometry.Pos.TOP_RIGHT);
            javafx.scene.layout.StackPane.setMargin(xButton, new javafx.geometry.Insets(-10, 2, 0, 0));
            tileWrapper.getChildren().add(xButton);
            
            // DISCARDED overlay (hidden by default)
            javafx.scene.layout.StackPane discardedOverlay = new javafx.scene.layout.StackPane();
            discardedOverlay.getStyleClass().add("discarded-overlay");
            discardedOverlay.setVisible(false);
            discardedOverlay.setManaged(false);
            discardedOverlay.setMouseTransparent(true); // Allow clicks to pass through to X button
            javafx.scene.control.Label discardedText = new javafx.scene.control.Label("DISCARDED");
            discardedText.getStyleClass().add("discarded-overlay-text");
            discardedOverlay.getChildren().add(discardedText);
            javafx.scene.layout.StackPane.setAlignment(discardedOverlay, javafx.geometry.Pos.CENTER);
            tileWrapper.getChildren().add(discardedOverlay);
            
            // Hidden radio button for toggle group functionality
            RadioButton discard = new RadioButton();
            discard.setToggleGroup(discardToggle);
            discard.setVisible(false);
            discard.setManaged(false);
            
            // === PRICE STEPPER CONTROL ===
            javafx.scene.layout.HBox stepperContainer = new javafx.scene.layout.HBox(4);
            stepperContainer.setAlignment(javafx.geometry.Pos.CENTER);
            stepperContainer.getStyleClass().add("price-stepper-container");
            
            // Price value holder (used internally for binding)
            final int[] priceValue = {0};
            
            // Minus button
            javafx.scene.layout.StackPane minusBtn = new javafx.scene.layout.StackPane();
            minusBtn.getStyleClass().add("price-stepper-button");
            javafx.scene.control.Label minusIcon = new javafx.scene.control.Label("−");
            minusIcon.getStyleClass().add("stepper-icon");
            minusBtn.getChildren().add(minusIcon);
            minusBtn.setDisable(true); // Disabled at start (value is 0)
            
            // Center value display with coin icon and editable text field
            javafx.scene.layout.HBox valueDisplay = new javafx.scene.layout.HBox(4);
            valueDisplay.setAlignment(javafx.geometry.Pos.CENTER);
            valueDisplay.getStyleClass().add("price-stepper-value");
            
            // Coin icon - using a better supported symbol
            javafx.scene.control.Label coinIcon = new javafx.scene.control.Label("🪙");
            coinIcon.setStyle("-fx-font-size: 16px; -fx-text-fill: #D5A649; -fx-font-weight: bold;");
            
            // Editable TextField for manual input
            TextField valueField = new TextField("0");
            valueField.getStyleClass().add("price-stepper-value-field");
            valueField.setPrefWidth(38);
            valueField.setMaxWidth(38);
            valueField.setAlignment(javafx.geometry.Pos.CENTER);
            valueField.setStyle("-fx-text-fill: #D5A649; -fx-font-weight: bold;");
            
            valueDisplay.getChildren().addAll(coinIcon, valueField);
            
            // Plus button
            javafx.scene.layout.StackPane plusBtn = new javafx.scene.layout.StackPane();
            plusBtn.getStyleClass().add("price-stepper-button");
            javafx.scene.control.Label plusIcon = new javafx.scene.control.Label("+");
            plusIcon.getStyleClass().add("stepper-icon");
            plusBtn.getChildren().add(plusIcon);
            
            stepperContainer.getChildren().addAll(minusBtn, valueDisplay, plusBtn);
            
            // Hidden TextField to store price value (for compatibility with existing code)
            TextField priceField = new TextField("0");
            priceField.setVisible(false);
            priceField.setManaged(false);
            
            // Update minus button disabled state based on value
            Runnable updateMinusButton = () -> {
                minusBtn.setDisable(priceValue[0] <= 0 || discard.isSelected());
            };
            
            // Stepper button handlers
            minusBtn.setOnMouseClicked(e -> {
                if (!discard.isSelected() && priceValue[0] > 0) {
                    SoundEffects.playPriceTick();
                    priceValue[0]--;
                    valueField.setText(String.valueOf(priceValue[0]));
                    priceField.setText(String.valueOf(priceValue[0]));
                    updateMinusButton.run();
                }
            });
            
            plusBtn.setOnMouseClicked(e -> {
                if (!discard.isSelected()) {
                    SoundEffects.playPriceTick();
                    priceValue[0]++;
                    valueField.setText(String.valueOf(priceValue[0]));
                    priceField.setText(String.valueOf(priceValue[0]));
                    updateMinusButton.run();
                }
            });
            
            // Manual text input handler
            valueField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == null || newVal.trim().isEmpty()) {
                    priceValue[0] = 0;
                    priceField.setText("0");
                    updateMinusButton.run();
                    return;
                }
                
                try {
                    int value = Integer.parseInt(newVal.trim());
                    if (value < 0) {
                        valueField.setText(oldVal);
                        return;
                    }
                    priceValue[0] = value;
                    priceField.setText(String.valueOf(value));
                    updateMinusButton.run();
                } catch (NumberFormatException ex) {
                    // Invalid input, revert to old value
                    valueField.setText(oldVal);
                }
            });
            
            // Keep valueField in sync when priceField changes (external updates)
            priceField.textProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null && !newVal.equals(valueField.getText())) {
                    valueField.setText(newVal);
                }
            });

            // === CARD CONTAINER WITH PARCHMENT STYLE ===
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, tileWrapper, discard, priceField, stepperContainer);
            box.setPrefWidth(155);
            box.setAlignment(javafx.geometry.Pos.CENTER);
            box.getStyleClass().add("tile-card");
            // Add top margin to prevent clipping when hover effect lifts the card
            javafx.geometry.Insets cardMargin = new javafx.geometry.Insets(5, 0, 0, 0);
            javafx.scene.layout.FlowPane.setMargin(box, cardMargin);

            // === X BUTTON CLICK HANDLER ===
            xButton.setOnMouseClicked(e -> discard.setSelected(!discard.isSelected()));
            
            // === DISCARD STATE CHANGE HANDLER ===
            discard.selectedProperty().addListener((obs, was, is) -> {
                if (is) {
                    // Mark as discarded
                    priceField.setText("");
                    priceValue[0] = 0;
                    valueField.setText("0");
                    
                    // Show DISCARDED overlay
                    discardedOverlay.setVisible(true);
                    discardedOverlay.setManaged(true);
                    
                    // Gray out the tile image
                    javafx.scene.effect.ColorAdjust grayscale = new javafx.scene.effect.ColorAdjust();
                    grayscale.setSaturation(-0.8);
                    grayscale.setBrightness(-0.2);
                    view.setEffect(grayscale);
                    
                    // Update X button to active state
                    xButton.getStyleClass().add("discard-x-button-active");
                    
                    // Disable stepper
                    stepperContainer.getStyleClass().add("price-stepper-container-disabled");
                    minusBtn.setDisable(true);
                    plusBtn.setDisable(true);
                    valueField.setDisable(true);
                    
                    // Update card visual state - red glow
                    box.getStyleClass().remove("tile-card");
                    box.getStyleClass().remove("tile-card-selected");
                    box.getStyleClass().add("tile-card-discard");
                } else {
                    // Unmark discard
                    discardedOverlay.setVisible(false);
                    discardedOverlay.setManaged(false);
                    
                    // Remove grayscale effect from tile
                    view.setEffect(null);
                    
                    // Reset X button state
                    xButton.getStyleClass().remove("discard-x-button-active");
                    
                    // Enable stepper
                    stepperContainer.getStyleClass().remove("price-stepper-container-disabled");
                    minusBtn.setDisable(priceValue[0] <= 0); // Only enable if value > 0
                    plusBtn.setDisable(false);
                    valueField.setDisable(false);
                    
                    // Reset card visual state
                    box.getStyleClass().remove("tile-card-discard");
                    box.getStyleClass().add("tile-card");
                }

                updateUiForPhase();
            });

            priceField.textProperty().addListener((obs, o, n) -> updateUiForPhase());

            // Hover animation - no transform to avoid clipping
            box.setOnMouseEntered(e -> {
                if (!discard.isSelected()) {
                    // Just apply the hover style class, CSS handles the rest
                }
            });
            box.setOnMouseExited(e -> {
                // Remove any hover effects
            });

            handPane.getChildren().add(box);
            priceCards.put(card.tileId, new PriceTileCard(card.tileId, card.tileJson, view, discard, priceField));
        }

        // === ADD CONFIRM BUTTON TO THE FAR RIGHT ===
        Button confirmButton = new Button("Confirm");
        confirmButton.getStyleClass().addAll("confirm-prices-button");
        confirmButton.setMinWidth(140);
        confirmButton.setPrefWidth(140);
        confirmButton.setMinHeight(80);
        confirmButton.setPrefHeight(80);
        
        // Initially disabled
        confirmButton.setDisable(true);
        
        // Click handler
        confirmButton.setOnAction(e -> {
            if (isDiscardPricingComplete()) {
                onSubmitPrices();
            }
        });
        
        // Store reference for updates
        Button finalConfirmButton = confirmButton;
        
        // Update validation state for all price changes
        for (PriceTileCard ptc : priceCards.values()) {
            ptc.priceField.textProperty().addListener((obs, o, n) -> {
                boolean complete = isDiscardPricingComplete();
                finalConfirmButton.setDisable(!complete);
                updatePhaseStatusLabel(complete);
                if (complete) {
                    finalConfirmButton.getStyleClass().remove("confirm-prices-button-disabled");
                    finalConfirmButton.getStyleClass().add("confirm-prices-button-enabled");
                } else {
                    finalConfirmButton.getStyleClass().remove("confirm-prices-button-enabled");
                    finalConfirmButton.getStyleClass().add("confirm-prices-button-disabled");
                }
            });
            
            ptc.discardRadio.selectedProperty().addListener((obs, o, n) -> {
                boolean complete = isDiscardPricingComplete();
                finalConfirmButton.setDisable(!complete);
                updatePhaseStatusLabel(complete);
                if (complete) {
                    finalConfirmButton.getStyleClass().remove("confirm-prices-button-disabled");
                    finalConfirmButton.getStyleClass().add("confirm-prices-button-enabled");
                } else {
                    finalConfirmButton.getStyleClass().remove("confirm-prices-button-enabled");
                    finalConfirmButton.getStyleClass().add("confirm-prices-button-disabled");
                }
            });
        }
        
        // Add large spacer to push button to the far right
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.setMinWidth(40);
        javafx.geometry.Insets buttonMargin = new javafx.geometry.Insets(5, 20, 0, 0);
        javafx.scene.layout.FlowPane.setMargin(spacer, buttonMargin);
        javafx.scene.layout.FlowPane.setMargin(confirmButton, buttonMargin);
        
        handPane.getChildren().addAll(spacer, confirmButton);

        updateUiForPhase();
        updatePhaseStatusLabel(false);
    }

    private void updatePhaseStatusLabel(boolean ready) {
        // Status is now shown in player list, refresh it
        Platform.runLater(() -> {
            refreshPlayersListView();
        });
    }

    private void renderBuildHandUi() {
        Integer previousSelection = selectedHandTileId;
        handPane.getChildren().clear();

        Map<Integer, TileCard> updated = new LinkedHashMap<>();

        boolean filterToKeptTwo =
                ("BUY".equalsIgnoreCase(currentPhase) || "REVEAL".equalsIgnoreCase(currentPhase))
                        && !keptNonDiscardTileIds.isEmpty();

        for (TileCard card : List.copyOf(handCards.values())) {

            if (pendingDiscardTileId != null && card.tileId == pendingDiscardTileId.intValue()) {
                continue;
            }

            if (filterToKeptTwo && !keptNonDiscardTileIds.contains(card.tileId)) {
                continue;
            }

            TileView view = new TileView(card.tileJson, TILE_SIZE);
            view.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    selectHandTile(card.tileId);
                }
            });

            updated.put(card.tileId, new TileCard(card.tileId, card.tileJson, view));

            if (!pendingPlacements.containsKey(card.tileId)) {
                handPane.getChildren().add(view);
            }
        }
        handCards.clear();
        handCards.putAll(updated);

        if (previousSelection != null && handCards.containsKey(previousSelection)) {
            if (!pendingPlacements.containsKey(previousSelection)) {
                selectedHandTileId = previousSelection;
            } else {
                selectedHandTileId = null;
            }
        } else {
            selectedHandTileId = null;
        }

        updateHandSelectionUi();
        syncDragTileWithSelection();
        updatePlacementHints();
        updateFinishBuildButton();
    }

    private void renderDrawHandUi() {
        if (handPane == null) return;
        handPane.getChildren().clear();

        boolean canDraw = "DRAW".equalsIgnoreCase(currentPhase)
                && myClientId >= 0
                && GLOBAL_CURRENT_TURN_ID == myClientId;

        java.util.List<TileCard> ordered = List.copyOf(handCards.values());

        for (int slotIndex = 0; slotIndex < 3; slotIndex++) {
            javafx.scene.layout.StackPane frame = new javafx.scene.layout.StackPane();
            frame.setPrefSize(TILE_SIZE, TILE_SIZE);
            frame.setMinSize(TILE_SIZE, TILE_SIZE);
            frame.setMaxSize(TILE_SIZE, TILE_SIZE);
            frame.setStyle("-fx-border-color: rgba(255,255,255,0.35);"
                    + " -fx-border-radius: 10; -fx-background-radius: 10;"
                    + " -fx-background-color: rgba(0,0,0,0.2);");

            if (slotIndex < ordered.size()) {
                TileCard card = ordered.get(slotIndex);
                TileView view = new TileView(card.tileJson, TILE_SIZE);
                view.setMouseTransparent(true);
                frame.getChildren().add(view);
            } else {
                Button drawSlotButton = new Button("Draw");
                drawSlotButton.getStyleClass().add("game-button");
                drawSlotButton.setDisable(!canDraw || slotIndex != drawsRequested);
                drawSlotButton.setOnAction(e -> onDrawButton());
                frame.getChildren().add(drawSlotButton);
            }

            VBox slotBox = new VBox(8, frame);
            slotBox.setAlignment(javafx.geometry.Pos.CENTER);
            slotBox.setPrefWidth(155);
            slotBox.getStyleClass().add("tile-card");
            javafx.geometry.Insets cardMargin = new javafx.geometry.Insets(5, 0, 0, 0);
            javafx.scene.layout.FlowPane.setMargin(slotBox, cardMargin);

            handPane.getChildren().add(slotBox);
        }
    }


    private void selectHandTile(int tileId) {
        selectedHandTileId = tileId;
        updateHandSelectionUi();
        startDragForSelectedTile();
        updatePlacementHints();
    }

    private void updateHandSelectionUi() {
        for (TileCard card : handCards.values()) {
            boolean selected = selectedHandTileId != null && card.tileId == selectedHandTileId;
            card.view.setSelected(selected);
            if (!selected) {
                card.view.setScaleX(1.0);
                card.view.setScaleY(1.0);
            }
        }
        if (selectedHandTileId != null) {
            TileCard selected = handCards.get(selectedHandTileId);
            if (selected != null) {
                animateSelection(selected.view);
            }
        }
    }

    private void selectMarketTile(int tileId) {
        selectedMarketTileId = tileId;
        updateMarketSelectionUi();
        // Buy button state is handled in updateUiForPhase
        updateUiForPhase();
        refreshPlayersListView(); // Update "Ready to buy" status
    }

    private void updateMarketSelectionUi() {
        for (MarketOfferCard c : marketCards.values()) {
            boolean isSelected = (selectedMarketTileId != null && c.tileId == selectedMarketTileId);

            if (c.view != null) {
                c.view.setSelected(isSelected);
            }

            if (c.container != null && c.container instanceof javafx.scene.layout.VBox cardBox) {
                // Use CSS classes for styling
                cardBox.getStyleClass().removeAll("market-tile-card", "market-tile-card-selected");
                if (isSelected) {
                    cardBox.getStyleClass().add("market-tile-card-selected");
                } else {
                    cardBox.getStyleClass().add("market-tile-card");
                }
            }
        }
    }

    private boolean isMyTurnToBuy() {
        return "BUY".equalsIgnoreCase(currentPhase)
                && myClientId >= 0
                && GLOBAL_CURRENT_TURN_ID == myClientId;
    }

    private void updateMarketBuyButtons() {
        if (marketCards == null || marketCards.isEmpty()) return;
        boolean enable = isMyTurnToBuy();
        for (MarketOfferCard c : marketCards.values()) {
            if (c.buyButton != null) {
                // Disable if it's not our turn, or if we can't afford it.
                // (If coins are unknown yet, keep current behavior and only gate by turn.)
                boolean canAfford = (displayedCoins == null) || (c.price <= displayedCoins);
                c.buyButton.setDisable(!(enable && canAfford));
            }
        }
    }
    /**
     * Updates the UI state based on the current game phase.
     * Handles visibility of buttons, panels, and labels.
     */
    private void updateUiForPhase() {
        boolean draw = "DRAW".equalsIgnoreCase(currentPhase);
        boolean discard = "DISCARD".equalsIgnoreCase(currentPhase);
        boolean buy = "BUY".equalsIgnoreCase(currentPhase);
        boolean build = "BUILD".equalsIgnoreCase(currentPhase);
        boolean reveal = "REVEAL".equalsIgnoreCase(currentPhase);

        boolean scoring = "SCORING".equalsIgnoreCase(currentPhase)
                || System.currentTimeMillis() < scoringStickyUntilMillis;

        boolean myTurnToBuy = isMyTurnToBuy();
        boolean hasKnownBuyer = buy && GLOBAL_CURRENT_TURN_ID >= 0;

        if (statusLabel != null) {
            if (draw) {
                statusLabel.setText("DRAW: Draw 3 tiles.");
            } else if (discard) {
                statusLabel.setText("DISCARD: Choose 1 tile to discard and enter prices for the other 2. Then confirm below.");
            } else if (reveal) {
                statusLabel.setText("REVEAL: Prices revealed! Buying phase starts!");
            } else if (buy) {
                if (myTurnToBuy) {
                    statusLabel.setText("BUY: It's your turn to buy a tile.");
                } else if (hasKnownBuyer) {
                    String buyerName = displayNameForClient(GLOBAL_CURRENT_TURN_ID);
                    statusLabel.setText("BUY: " + buyerName + " is buying now.");
                } else {
                    statusLabel.setText("BUY: Waiting for the current player to buy...");
                }
            } else if (build) {
                statusLabel.setText("BUILD: Select a tile from your hand, rotate if needed, then click a state cell to place it.");
            } else {
                statusLabel.setText("Phase: " + currentPhase);
            }
        }

        if (marketTitleLabel != null) {
            if (buy) {
                marketTitleLabel.setText("Market • Select a tile, then press Buy");
            } else if (reveal) {
                marketTitleLabel.setText("Market • Prices Revealed");
            } else {
                marketTitleLabel.setText("Market");
            }
        }

        updateDragUiForPhase(build);

        // Submit Prices button is now hidden - moved to bottom action bar
        if (submitPricesButton != null) {
            submitPricesButton.setVisible(false);
            submitPricesButton.setManaged(false);
        }

        // Hide old buy/pass buttons from Actions panel - now in bottom market panel
        if (buyButton != null) {
            buyButton.setVisible(false);
            buyButton.setManaged(false);
        }
        if (passButton != null) {
            passButton.setVisible(false);
            passButton.setManaged(false);
        }
        
        // Show/hide new Buy action buttons in bottom panel
        if (buyActionButtonsContainer != null) {
            buyActionButtonsContainer.setVisible(myTurnToBuy);
            buyActionButtonsContainer.setManaged(myTurnToBuy);
        }
        if (continueButton != null) {
            continueButton.setDisable(!myTurnToBuy);
        }

        if (drawButton != null) {
            drawButton.setVisible(false);
            drawButton.setManaged(false);
        }

        boolean showMarket = buy || reveal;
        boolean showHand = draw || discard || build || buy;

        if (scoring && !discard && !build) {
            showHand = false;
            showMarket = false;
        }

        if (handPane != null) {
            handPane.setVisible(showHand);
            handPane.setManaged(showHand);
        }

        if (marketPane != null) {
            marketPane.setVisible(showMarket);
            marketPane.setManaged(showMarket);
        }

        if (marketTitleLabel != null) {
            marketTitleLabel.setVisible(showMarket);
            marketTitleLabel.setManaged(showMarket);
        }

        if (scoringList != null) {
            scoringList.setVisible(scoring);
            scoringList.setManaged(scoring);
            scoringList.toFront();
        }

        if (handPane2 != null) {
            handPane2.setVisible(true);
            handPane2.setManaged(true);
        }
        updateFinishBuildButton();

        updateMarketBuyButtons();
        
        // Update phase status label visibility
        if (!discard) {
            updatePhaseStatusLabel(false);
        }
    }

    /**
     * Handler für den Draw-Button in der DRAW-Phase.
     */
    @FXML
    private void onDrawButton() {
        if (client == null) return;
        syncClientId();

        if (!"DRAW".equalsIgnoreCase(currentPhase)) {
            setStatus("Not in DRAW phase.");
            return;
        }
        if (myClientId < 0 || GLOBAL_CURRENT_TURN_ID != myClientId) {
            setStatus("Not your turn to draw.");
            renderDrawHandUi();
            return;
        }

        if (drawsRequested >= 3) {
            setStatus("No more draws available this round.");
            if (drawButton != null) drawButton.setDisable(true);
            return;
        }

        try {
            // SFX: card sound on each successful draw click (DRAW phase)
            SoundEffects.playDrawCard();
            if (drawsRequested == 0) {
                log.debug("First draw of the round - Force clearing hand.");
                clearHandAndMarket();
            }
            client.sendDrawLandscape(drawsRequested);
            drawsRequested++;
            drawButtonPressed = true;
            setStatus("Draw request sent (drawNumber=" + (drawsRequested - 1) + ")");

            renderDrawHandUi();
            revealPendingDrawnTiles();
        } catch (Exception e) {
            setStatus("Failed to send draw request: " + e.getMessage());
        }
    }

    private void revealPendingDrawnTiles() {
        // Reveal any buffered drawn tiles that belong to this client.
        // We used `handPane2` as a debug area previously; it's optional. Always apply
        // the draw into the main `handPane` when the user has pressed Draw.
        if (handPane2 != null && handPane2 != scoringTilesPane) handPane2.getChildren().clear();
        java.util.Iterator<JsonObject> it = pendingDrawnTilePayloads.iterator();
        while (it.hasNext()) {
            JsonObject p = it.next();
            if (p == null || !p.has("clientID") || !p.has("tile")) continue;
            int cid = p.get("clientID").getAsInt();
            if (myClientId >= 0 && cid != myClientId) continue;
            try {
                JsonObject tile = p.getAsJsonObject("tile");
                // If the player has pressed Draw, place the tile into the normal hand area (Your Tiles)
                    if (drawButtonPressed) {
                        int tileId = tile.has("id") ? tile.get("id").getAsInt() : Integer.MIN_VALUE;
                        if (tileId != Integer.MIN_VALUE) {
                            addOrUpdateHandTile(tileId, tile);
                            if ("DRAW".equalsIgnoreCase(currentPhase)) {
                                renderDrawHandUi();
                            } else {
                                renderBuildHandUi();
                            }
                        }
                    } else {
                    }
                it.remove();
            } catch (Exception e) {
                it.remove();
            }
        }
    }

    private boolean isDiscardPricingComplete() {
        if (!"DISCARD".equalsIgnoreCase(currentPhase)) return false;
        if (priceCards.isEmpty()) return false;

        Integer discardTileId = null;
        for (PriceTileCard c : priceCards.values()) {
            if (c.discardRadio != null && c.discardRadio.isSelected()) {
                discardTileId = c.tileId;
                break;
            }
        }
        if (discardTileId == null) return false;

        int priced = 0;
        for (PriceTileCard c : priceCards.values()) {
            if (c.tileId == discardTileId) continue;
            if (c.priceField == null) return false;
            String raw = c.priceField.getText() == null ? "" : c.priceField.getText().trim();
            if (raw.isEmpty()) return false;
            try {
                int v = Integer.parseInt(raw);
                if (v < 0) return false;
            } catch (Exception e) {
                return false;
            }
            priced++;
        }
        return priced >= 2;
    }

    /**
     * Handler für das Abschicken der Preisentscheidung in der DISCARD-Phase.
     */
    @FXML
    private void onSubmitPrices() {
        if (client == null) return;

        if ("DISCARD".equalsIgnoreCase(currentPhase)) {

            if (priceCards.size() < 3) {
                setStatus("Waiting for 3 tiles...");
                return;
            }

            Integer discardTileId = null;
            for (PriceTileCard c : priceCards.values()) {
                if (c.discardRadio.isSelected()) {
                    discardTileId = c.tileId;
                    break;
                }
            }
            if (discardTileId == null) {
                setStatus("Select exactly one tile to discard.");
                return;
            }
            this.pendingDiscardTileId = discardTileId;
            JsonArray tilePrices = new JsonArray();
            for (PriceTileCard c : priceCards.values()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("tileID", c.tileId);
                boolean discard = c.tileId == discardTileId;
                entry.addProperty("discard", discard);

                if (discard) {
                    entry.add("price", com.google.gson.JsonNull.INSTANCE);
                } else {
                    String rawPrice = (c.priceField.getText() != null) ? c.priceField.getText().trim() : "";
                    if (rawPrice.isEmpty()) {
                        setStatus("Enter prices for the 2 non-discard tiles.");
                        return;
                    }
                    try {
                        int price = Integer.parseInt(rawPrice);
                        if (price < 1) {
                            setStatus("Price must be at least 1.");
                            return;
                        }

                        entry.addProperty("price", price);
                    } catch (NumberFormatException e) {
                        setStatus("Invalid price: " + rawPrice);
                        return;
                    }
                }
                tilePrices.add(entry);
            }

            try {
                int sum = 0;
                for (int i = 0; i < tilePrices.size(); i++) {
                    JsonObject t = tilePrices.get(i).getAsJsonObject();
                    if (t.has("price") && !t.get("price").isJsonNull()) {
                        sum += t.get("price").getAsInt();
                    }
                }
                if (displayedCoins != null && sum > displayedCoins) {
                    setStatus("You don't have enough coins to cover these prices (need " + sum + ", have " + displayedCoins + ").");
                    return;
                }
            } catch (Exception ignored) {}

            client.setTilePrices(tilePrices);
            setStatus("Submitted prices - waiting for other players...");

            // Mark ourselves as ready
            playersReadyInPhase.add(myClientId);
            refreshPlayersListView();
            
            submitPricesButton.setDisable(true);
        }
    }

    /**
     * Handler für den Kauf-Button in der BUY-Phase.
     */
    @FXML
    private void onBuy() {
        if (client == null) return;
        if (!"BUY".equalsIgnoreCase(currentPhase)) return;
        if (!isMyTurnToBuy()) {
            setStatus("Not your turn to buy.");
            updateMarketBuyButtons();
            return;
        }

        if (selectedMarketTileId == null) {
            setStatus("Please select a tile first.");
            return;
        }

        // Guard: don't send a buy request if the selected offer is not affordable.
        // The UI should already disable these buttons, but this keeps behavior consistent
        // if selection is set in other ways.
        Offer offer = GLOBAL_OFFER_CACHE.get(selectedMarketTileId);
        if (offer != null && displayedCoins != null && offer.price() > displayedCoins) {
            setStatus("Not enough coins to buy this tile (need " + offer.price() + ", have " + displayedCoins + ").");
            updateMarketBuyButtons();
            return;
        }

        if (buyButton != null) buyButton.setDisable(true);
        if (passButton != null) passButton.setDisable(true);

    // BUY button specific SFX (purchase success)
    frontend.ui.audio.SoundEffects.playPurchase();

        log.info("[CLIENT] Sending BuyTile Request for ID: {}", selectedMarketTileId);
        client.buyTile(selectedMarketTileId);
        
        setStatus("Purchase request sent...");
    }

    /**
     * Handler für den Pass-Button in der BUY-Phase.
     */
    @FXML
    private void onPass() {
        if (client == null) return;

        if (!"BUY".equalsIgnoreCase(currentPhase)) return;
        if (!isMyTurnToBuy()) {
            setStatus("Not your turn to buy.");
            updateMarketBuyButtons();
            return;
        }

        if (passButton != null) passButton.setDisable(true);
        if (buyButton != null) buyButton.setDisable(true);

        log.info("[CLIENT] Sending Pass Request (BuyTile null)");

        client.buyTile(null);
        
        setStatus("You passed. Waiting for the server...");
    }

    /**
     * Handler für den Continue-Button in der BUY-Phase (entspricht Pass).
     */
    @FXML
    private void onContinueBuyPhase() {
        // In BUY phase, "Continue" should mean: pass / buy nothing and end our buy turn.
        // The server expects a BuyTile message with null (same as the existing Pass button).
        onPass();
    }

    /**
     * Schaltet das Settings-Panel ein oder aus.
     */
    @FXML
    private void onToggleSettings() {
        if (settingsPanel == null) return;
        boolean show = !settingsPanel.isVisible();
        settingsPanel.setVisible(show);
        settingsPanel.setManaged(show);
    }

    /**
     * Sendet die aktuelle Chat-Nachricht oder verarbeitet Slash-Commands.
     */
    @FXML
    private void onSend() {
        if (client == null) return;
        String text = (messageField != null && messageField.getText() != null) ? messageField.getText().trim() : "";
        if (text.isEmpty()) return;

        // 1. HELP COMMAND
        if (text.equalsIgnoreCase("/help")) {
            String helpMessage = """
                Command List:
                /w <Player> <Message> -> Send a private message
                /r <Message>          -> Reply last private message
                /clear                -> Clear current chat history
                /help                 -> Show this list
                """;

            String targetThread = (activeThread != null) ? activeThread : GLOBAL_THREAD;
            appendToThread(targetThread, helpMessage + "\n");

            messageField.clear();
            return;
        }

        // 2. CLEAR COMMAND
        if (text.equalsIgnoreCase("/clear")) {
            String targetThread = (activeThread != null) ? activeThread : GLOBAL_THREAD;
            if (threads.containsKey(targetThread)) {
                threads.get(targetThread).clear();
            }
            if (chatArea != null) {
                chatArea.clear();
            }
            messageField.clear();
            return;
        }
        // 3. REPLY COMMAND
        if (text.equalsIgnoreCase("/r") || text.toLowerCase().startsWith("/r ")) {
            if (lastPrivateSenderName == null) {
                appendChat("[System]: No one to reply to (no private messages received yet).");
                messageField.clear();
                return;
            }

            String replyContent = "";
            if (text.length() > 2) {
                replyContent = text.substring(2).trim();
            }
            if (replyContent.isEmpty()) {
                messageField.setText("/w " + lastPrivateSenderName + " ");
                messageField.positionCaret(messageField.getLength());
                return;
            }
            Integer targetId = resolveClientIdForDisplayName(lastPrivateSenderName, false);
            if (targetId != null) {

                client.sendDirectMessage(targetId, replyContent);
            } else {
                appendChat("[System]: Could not find player ID for " + lastPrivateSenderName);
            }

            messageField.clear();
            return;
        }

        // 4. PRIVATE MESSAGE
        if (text.toLowerCase().startsWith("/w ")) {
            String[] parts = text.split("\\s+", 3);

            if (parts.length < 3) {
                appendChat("[System]: Usage: /w <PlayerName> <Message>");
                return;
            }

            String rawTargetName = parts[1];
            String privateMessage = parts[2];

            Integer targetId = null;

            for (java.util.Map.Entry<Integer, String> entry : playerNameByClientId.entrySet()) {
                String cleanName = entry.getValue().replace(" (you)", "").trim();
                if (cleanName.equalsIgnoreCase(rawTargetName)) {
                    targetId = entry.getKey();
                    break;
                }
            }
            if (targetId == null) {
                appendChat("[System]: Player '" + rawTargetName + "' not found.");
                return;
            }
            if (targetId == myClientId) {
                appendChat("[System]: You cannot whisper to yourself.");
                return;
            }
            client.sendDirectMessage(targetId, privateMessage);
            messageField.clear();
            return;
        }

        // 5. STANDARD MESSAGE
        if (activeThread != null && activeThread.equals(username)) {
            setStatus("You can't send private messages to yourself.");
            activeThread = GLOBAL_THREAD;
            renderActiveThread();
            messageField.clear();
            return;
        }

        if (GLOBAL_THREAD.equals(activeThread)) {
            client.sendMessage(text);
        } else {
            Integer targetId = resolveClientIdForDisplayName(activeThread, false);

            if (targetId != null) {
                client.sendDirectMessage(targetId, text);
            } else {
                appendChat("[System]: Could not find player ID for " + activeThread);
            }
        }

        messageField.clear();
    }

    /**
     * Verlässt den Spielscreen und kehrt ins Hauptmenü zurück.
     */
    @FXML
    private void onLeave() {
        try {
            // Stop in-game background music when leaving the game screen.
            BackgroundMusic.stop();
            if (client != null) {
                client.close();
            }
        } catch (Exception ignored) {
        }

        Stage stage = null;
        if (leaveButton != null && leaveButton.getScene() != null) {
            stage = (Stage) leaveButton.getScene().getWindow();
        } else if (sendButton != null && sendButton.getScene() != null) {
            stage = (Stage) sendButton.getScene().getWindow();
        }
        if (stage == null) return;

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainMenu.fxml"));
            Parent root = loader.load();
            Object ctrl = loader.getController();
            if (ctrl instanceof frontend.ui.controllers.MainMenuController) {
                ((frontend.ui.controllers.MainMenuController) ctrl).setPrimaryStage(stage);
            }
            stage.setScene(new Scene(root, 800, 600));
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Beendet die BUILD-Phase, wenn alle Tiles platziert sind.
     */
    @FXML
    private void onFinishBuild() {
        if (client == null) return;
        syncClientId();
        if (!"BUILD".equalsIgnoreCase(currentPhase)) {
            setStatus("Not in BUILD phase.");
            return;
        }
        if (pendingPlacements.isEmpty()) {
            setStatus("Place tiles before finishing.");
            return;
        }
        if (hasUnplacedHandTiles()) {
            setStatus("Place all tiles before finishing.");
            return;
        }

        buildFinalizing = true;
        updateFinishBuildButton();

        for (PreviewInfo info : pendingPlacements.values()) {
            client.buildTile(info.tileId, info.x, info.y, info.rotation);
        }

        setStatus("Finishing build... sending tiles to server.");
    }

    private void appendChat(String line) {
        appendToThread(GLOBAL_THREAD, line + "\n");
    }

    private void setStatus(String text) {
        if (statusLabel == null) return;
        statusLabel.setText(text != null ? text : "");
    }

    private void resetChatThreads() {
        threads.clear();
        unreadByPlayer.clear();
        activeThread = GLOBAL_THREAD;
        threads.put(GLOBAL_THREAD, new java.util.ArrayList<>());
        renderActiveThread();
    }

    private void ensureThreadExists(String key) {
        if (key == null || key.isBlank()) {
            key = GLOBAL_THREAD;
        }
        threads.computeIfAbsent(key, k -> new java.util.ArrayList<>());
    }
    private void appendToThread(String key, String text) {
        if (text == null) return;
        String thread = (key == null || key.isBlank()) ? GLOBAL_THREAD : key;
        ensureThreadExists(thread);
        threads.get(thread).add(text);
        if (thread.equals(activeThread)) {
            renderActiveThread();
        } else if (!GLOBAL_THREAD.equals(thread)) {
            unreadByPlayer.merge(thread, 1, Integer::sum);
            refreshPlayersListView();
        }
    }

    private void renderActiveThread() {
        ensureThreadExists(activeThread);
        java.util.List<String> lines = threads.get(activeThread);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line);
        }
        if (chatArea != null) {
            chatArea.setText(sb.toString());
            chatArea.positionCaret(chatArea.getLength());
        }
    }

    private void activateThread(String displayEntry) {
        String key = threadKeyForDisplay(displayEntry);
        if (key == null) key = GLOBAL_THREAD;

        if (username != null && username.equals(key)) {
            key = GLOBAL_THREAD;
        }

        activeThread = key;
        ensureThreadExists(activeThread);
        if (!GLOBAL_THREAD.equals(activeThread)) {
            unreadByPlayer.remove(activeThread);
            refreshPlayersListView();
        }
        renderActiveThread();
    }

    private void ensureGlobalEntryPresent() {
        if (!chatThreads.contains(GLOBAL_THREAD)) {
            chatThreads.add(0, GLOBAL_THREAD);
        } else if (chatThreads.indexOf(GLOBAL_THREAD) != 0) {
            chatThreads.remove(GLOBAL_THREAD);
            chatThreads.add(0, GLOBAL_THREAD);
        }
    }

    private String extractActivePlayerName() {
        try {
            if (activePlayerLabel == null) return null;
            String text = activePlayerLabel.getText();
            if (text == null) return null;
            if (!text.startsWith("Active:")) return null;
            return text.substring("Active:".length()).trim();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String normalizeDisplayName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.endsWith(" (you)")) {
            n = n.substring(0, n.length() - 6).trim();
        }
        return n;
    }

    private Integer resolveClientIdForDisplayName(String displayName, boolean isSelf) {
        if (isSelf) return myClientId;

        String key = normalizeDisplayName(displayName);
        if (key == null || key.isBlank()) return null;

        Integer cached = clientIdByDisplayName.get(key);
        if (cached != null) return cached;

        // Find current mapping from protocol state.
        for (var e : playerNameByClientId.entrySet()) {
            if (key.equals(e.getValue())) {
                clientIdByDisplayName.put(key, e.getKey());
                return e.getKey();
            }
        }
        return null;
    }

    private void ensurePlayerEntryExists(String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        if (playerName.equals(username)) {
            String display = playerName + " (you)";
            if (!playerNames.contains(display)) {
                playerNames.add(display);
            }
            return;
        }
        if (!playerNames.contains(playerName) && !playerNames.contains(playerName + " (you)")) {
            playerNames.add(playerName);
        }
    }

    private String threadKeyForDisplay(String display) {
        if (display == null || GLOBAL_THREAD.equals(display)) return GLOBAL_THREAD;
        if (display.endsWith(" (you)")) {
            return display.substring(0, display.length() - 6);
        }
        return display;
    }

    private int getUnreadCount(String threadKey) {
        if (threadKey == null) return 0;
        return unreadByPlayer.getOrDefault(threadKey, 0);
    }


    private void handleReceivedChat(JsonObject body) {
        log.debug("=== DEBUG: handleReceivedChat called ===");
        log.debug("Body: {}", (body != null ? body.toString() : "null"));

        if (body == null) return;

        String message =
                body.has("message") && body.get("message").isJsonPrimitive()
                        ? body.get("message").getAsString()
                        : "";

        int fromId =
                body.has("from") && body.get("from").isJsonPrimitive()
                        ? body.get("from").getAsInt()
                        : -1;

        boolean isPrivate =
                body.has("isPrivate") && body.get("isPrivate").isJsonPrimitive()
                        && body.get("isPrivate").getAsBoolean();

        log.debug("Message: {}", message);
        log.debug("From ID: {}", fromId);
        log.debug("Is Private: {}", isPrivate);

        // Resolve sender name
        String fromName = displayNameForClient(fromId);
        if (fromName == null || fromName.isBlank()) {
            fromName = "Client " + fromId;
        }

        log.debug("Resolved Name: {}", fromName);

        // Remove " (you)" suffix for thread key
        final String displayName = fromName;
        final String threadKey = normalizeDisplayName(fromName);

        log.debug("Display Name: {}", displayName);
        log.debug("Thread Key: {}", threadKey);

        Platform.runLater(() -> {
            log.debug("=== Platform.runLater executing ===");

            if (isPrivate) {
                if (fromId != myClientId) {
                    lastPrivateSenderName = threadKey;
                }
                log.debug("Processing PRIVATE message");

                // Make sure thread exists
                ensureThreadExists(threadKey);
                log.debug("Thread exists: {}", threadKey);

                // Add to chat threads list if not present
                if (!chatThreads.contains(threadKey)) {
                    chatThreads.add(threadKey);
                    log.debug("Added to chatThreads: {}", threadKey);
                }

                // Format message with [PRIVATE] prefix
                String formattedMsg = String.format("[PRIVATE] %s: %s\n", displayName, message);
                log.debug("Formatted message: {}", formattedMsg);

                // Append to the DM thread
                appendToThread(threadKey, formattedMsg);
                log.debug("Appended to thread: {}", threadKey);

                // If not currently viewing this thread, mark as unread
                if (!threadKey.equals(activeThread)) {
                    log.debug("Not active thread, marking as unread");
                    unreadByPlayer.merge(threadKey, 1, Integer::sum);
                    refreshPlayersListView();
                } else {
                    log.debug("This IS the active thread");
                }
            } else {
                log.debug("Processing PUBLIC message");
                // Public message - add to global thread
                String formattedMsg = String.format("%s: %s\n", displayName, message);
                log.debug("Formatted message: {}", formattedMsg);
                appendToThread(GLOBAL_THREAD, formattedMsg);
                log.debug("Appended to GLOBAL_THREAD");
            }
            log.debug("=== Platform.runLater finished ===");
        });
    }
    private void handleTilePricingCompleted(JsonObject payload) {
        // A player has completed pricing/discard phase
        if (payload != null && payload.has("clientID")) {
            int clientId = payload.get("clientID").getAsInt();
            log.info("Player {} completed pricing, marking as ready", clientId);
            playersReadyInPhase.add(clientId);
            refreshPlayersListView();
        } else {
            log.warn("TilePricingCompleted received but no clientID in payload: {}", payload);
        }
    }

    private void handlePriceSubmitRejected(JsonObject payload) {
        if (payload == null) return;
        String reason = payload.has("reason") ? payload.get("reason").getAsString() : "Preisabgabe abgelehnt";
        String code = payload.has("code") ? payload.get("code").getAsString() : null;
        Platform.runLater(() -> {
            if (code != null) {
                setStatus("Preis abgelehnt (" + code + "): " + reason);
            } else {
                setStatus("Preis abgelehnt: " + reason);
            }
        });
    }

    private void handleStartPlayerUpdate(JsonObject payload) {

    }

    private void handleGameOver(JsonObject payload) {
        if (payload == null) return;

        String winnerName = null;
        if (payload.has("winnerName") && !payload.get("winnerName").isJsonNull()) {
            winnerName = payload.get("winnerName").getAsString();
        } else if (payload.has("winner") && !payload.get("winner").isJsonNull()) {
            winnerName = payload.get("winner").getAsString();
        } else if (payload.has("placements") && payload.get("placements").isJsonArray()) {
            try {
                JsonArray placements = payload.getAsJsonArray("placements");
                if (placements.size() > 0 && placements.get(0).isJsonObject()) {
                    JsonObject first = placements.get(0).getAsJsonObject();
                    if (first.has("name") && !first.get("name").isJsonNull()) {
                        winnerName = first.get("name").getAsString();
                    }
                }
            } catch (Exception ignored) {}
        }

        if (winnerName == null || winnerName.isBlank()) {
            winnerName = "Unknown";
        }

        final String finalWinner = winnerName;

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/game_over.fxml"));
                Parent root = loader.load();
                GameOverController controller = loader.getController();
                controller.setWinnerName(finalWinner);

                if (payload.has("placements") && payload.get("placements").isJsonArray()) {
                    JsonArray arr = payload.getAsJsonArray("placements");
                    List<GameOverController.Placement> placements = new ArrayList<>();
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : "?";
                        int points = o.has("points") && !o.get("points").isJsonNull() ? o.get("points").getAsInt() : 0;
                        placements.add(new GameOverController.Placement(name, points));
                    }
                    controller.setPlacements(placements);
                }

                Stage stage = new Stage();
                stage.initModality(Modality.APPLICATION_MODAL);
                stage.setTitle("Game Over");
                stage.setScene(new Scene(root));
                stage.show();
            } catch (Exception e) {
                e.printStackTrace();
                setStatus("Failed to show Game Over dialog: " + e.getMessage());
            }
        });
    }


    /**
     * Delegiert den Pass-Klick auf die BUY-Logik.
     */
    @FXML
    private void onPassClicked() {
        onPass();
    }

    /**
     * Delegiert den Buy-Klick auf die BUY-Logik.
     */
    @FXML
    private void onBuyClicked() {
        onBuy();
    }

    private void refreshPlayersListView() {
        if (playersList != null) {
            log.info("Refreshing players list. Ready players: {}, Current phase: {}", playersReadyInPhase, currentPhase);
            playersList.refresh();
        }
    }

    private String displayNameForClient(int clientId) {
        log.debug("displayNameForClient({}) playerNameByClientId.size={}", clientId, playerNameByClientId.size());
        log.debug("Map contents: {}", playerNameByClientId);
        String n = playerNameByClientId.get(clientId);
        if (n != null && !n.isBlank()) return n;
        if (clientId == myClientId && username != null && !username.isBlank()) return username + " (you)";
        return "Client " + clientId;
    }

    private java.util.List<String> allPlayersForScoringDisplay() {
        java.util.List<String> out = new java.util.ArrayList<>();

        for (String p : playerNames) {
            if (p == null) continue;
            if (GLOBAL_THREAD.equals(p)) continue;
            out.add(p);
        }
        if (out.isEmpty() && username != null && !username.isBlank()) {
            out.add(username + " (you)");
        }
        return out;
    }

    private void showGameOverScene(String winnerName, java.util.List<GameOverController.Placement> placements) {
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/GameOver.fxml"));
                Parent root = loader.load();

                Object ctrl = loader.getController();
                if (ctrl instanceof GameOverController goc) {
                    goc.setWinnerName(winnerName);
                    goc.setPlacements(placements);
                }

                Stage stage = null;
                if (leaveButton != null && leaveButton.getScene() != null) {
                    stage = (Stage) leaveButton.getScene().getWindow();
                } else if (sendButton != null && sendButton.getScene() != null) {
                    stage = (Stage) sendButton.getScene().getWindow();
                }
                if (stage == null) return;

                stage.setScene(new Scene(root, 1200, 800));
                stage.centerOnScreen();

            } catch (Exception e) {
                e.printStackTrace();
                setStatus("Failed to show GameOver screen: " + e.getMessage());
            }
        });
    }

    private String determineWinnerFromPayloadOrCache(JsonObject payload) {
        if (payload == null) return null;

        if (payload.has("winners") && payload.get("winners").isJsonArray()) {
            JsonArray winners = payload.getAsJsonArray("winners");
            if (winners.size() > 0 && winners.get(0).isJsonPrimitive()
                    && winners.get(0).getAsJsonPrimitive().isNumber()) {
                int winnerId = winners.get(0).getAsInt();
                return displayNameForClient(winnerId);
            }
        }

        if (payload.has("winnerName") && payload.get("winnerName").isJsonPrimitive()) {
            String w = payload.get("winnerName").getAsString();
            if (w != null && !w.isBlank()) return w;
        }
        if (payload.has("winner") && payload.get("winner").isJsonPrimitive()) {
            String w = payload.get("winner").getAsString();
            if (w != null && !w.isBlank()) return w;
        }

        for (String k : new String[]{"winnerClientID", "winnerClientId", "winnerID", "winnerId"}) {
            if (payload.has(k) && payload.get(k).isJsonPrimitive() && payload.get(k).getAsJsonPrimitive().isNumber()) {
                int id = payload.get(k).getAsInt();
                return displayNameForClient(id);

            }
        }

        JsonObject scoringObj = payload;
        if (!payload.has("scores") && payload.has("scoring") && payload.get("scoring").isJsonObject()) {
            scoringObj = payload.getAsJsonObject("scoring");
        }

        if (scoringObj.has("scores") && scoringObj.get("scores").isJsonArray()) {
            JsonArray scores = scoringObj.getAsJsonArray("scores");

            int bestId = -1;
            int bestVp = Integer.MIN_VALUE;

            for (JsonElement el : scores) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();

                int cid = s.has("clientID") ? s.get("clientID").getAsInt() : -1;
                int vp = s.has("victoryPoints") ? s.get("victoryPoints").getAsInt() : 0;

                if (vp > bestVp) {
                    bestVp = vp;
                    bestId = cid;
                }
            }

            if (bestId != -1) return displayNameForClient(bestId);
        }

        return null;
    }

    private java.util.List<GameOverController.Placement> buildPlacementsForGameOver(JsonObject payload) {
        java.util.Map<Integer, Integer> totals = new java.util.HashMap<>(lastTotalVpByClient);
        JsonArray scores = extractScoresArray(payload);
        if (scores == null && GLOBAL_LAST_SCORING_PAYLOAD != null) {
            scores = extractScoresArray(GLOBAL_LAST_SCORING_PAYLOAD);
        }
        if (scores != null) {
            for (JsonElement el : scores) {
                if (!el.isJsonObject()) continue;
                JsonObject s = el.getAsJsonObject();
                int clientId = s.has("clientID") ? s.get("clientID").getAsInt() : -1;
                int vp = s.has("victoryPoints") ? s.get("victoryPoints").getAsInt() : 0;
                if (clientId >= 0) {
                    totals.put(clientId, vp);
                }
            }
        }
        if (totals.isEmpty() && payload != null && payload.has("winners") && payload.get("winners").isJsonArray()) {
            for (JsonElement el : payload.getAsJsonArray("winners")) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) continue;
                int id = el.getAsInt();
                totals.putIfAbsent(id, 0);
            }
        }
        if (totals.isEmpty() && !playerNameByClientId.isEmpty()) {
            for (Integer id : playerNameByClientId.keySet()) {
                totals.putIfAbsent(id, 0);
            }
        }
        java.util.List<GameOverController.Placement> list = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> entry : totals.entrySet()) {
            String name = displayNameForClient(entry.getKey());
            list.add(new GameOverController.Placement(name, entry.getValue()));
        }
        list.sort((a, b) -> {
            int cmp = Integer.compare(b.points(), a.points());
            if (cmp != 0) return cmp;
            return a.name().compareToIgnoreCase(b.name());
        });
        return list;
    }

    private JsonArray extractScoresArray(JsonObject payload) {
        if (payload == null) return null;
        if (payload.has("scores") && payload.get("scores").isJsonArray()) {
            return payload.getAsJsonArray("scores");
        }
        if (payload.has("scoring") && payload.get("scoring").isJsonObject()) {
            JsonObject scoringObj = payload.getAsJsonObject("scoring");
            if (scoringObj.has("scores") && scoringObj.get("scores").isJsonArray()) {
                return scoringObj.getAsJsonArray("scores");
            }
        }
        return null;
    }

    private void handleWelcome(JsonObject payload) {
        if (payload == null) return;

        // myClientId kann hier auch drin sein
        if (payload.has("clientID") && payload.get("clientID").isJsonPrimitive()) {
            myClientId = payload.get("clientID").getAsInt();
        }

        if (payload.has("players") && payload.get("players").isJsonArray()) {
            for (JsonElement el : payload.getAsJsonArray("players")) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                if (!p.has("clientID") || !p.has("name")) continue;

                int id = p.get("clientID").getAsInt();
                String name = p.get("name").getAsString();

                if (name != null && !name.isBlank()) {
                    playerNameByClientId.put(id, name);
                    clientIdByDisplayName.put(normalizeDisplayName(name), id);
                }
            }
        }
    }

    private void handlePlayerAdded(JsonObject payload) {
        if (payload == null) return;
        if (!payload.has("clientID") || !payload.has("name")) return;

        int id = payload.get("clientID").getAsInt();
        String name = payload.get("name").getAsString();
        if (name != null && !name.isBlank()) {
            playerNameByClientId.put(id, name);
            clientIdByDisplayName.put(normalizeDisplayName(name), id);
        }
    }

    private void handleConnectionUpdate(JsonObject payload) {
        if (payload == null) return;
        boolean isRemoveAction = payload.has("action") && "Remove".equalsIgnoreCase(payload.get("action").getAsString());
        boolean isDisconnect = payload.has("isConnected") && !payload.get("isConnected").getAsBoolean();

        if (isRemoveAction || isDisconnect) {
            handlePlayerLeft(payload);
            return;
        }
        if (!payload.has("clientID") || !payload.has("name")) return;

        int id = payload.get("clientID").getAsInt();
        String name = payload.get("name").getAsString();
        if (name != null && !name.isBlank()) {
            playerNameByClientId.put(id, name);
            clientIdByDisplayName.put(normalizeDisplayName(name), id);
        }
    }

    private void handlePlayerLeft(JsonObject payload) {
        if (payload == null) return;

        int id = -1;
        if (payload.has("clientID")) id = payload.get("clientID").getAsInt();
        else if (payload.has("clientId")) id = payload.get("clientId").getAsInt();

        if (id == -1) return;

        String removedName = playerNameByClientId.get(id);

        playerNameByClientId.remove(id);
        if (removedName != null) {
            clientIdByDisplayName.remove(normalizeDisplayName(removedName));
        } else {
            removedName = "Player " + id;
        }

        final String finalName = removedName;

        Platform.runLater(() -> {
            log.info("Processing Player Left: {}", finalName);

            playerNames.remove(finalName);
            playerNames.remove(finalName + " (you)");
            refreshPlayersListView();

            appendChat("[System]:  " + finalName + " left the game.");

            int remaining = playerNameByClientId.size();
            if (remaining < 2 && !"NONE".equalsIgnoreCase(currentPhase)) {
                setStatus("⚠ Waiting for players... (" + remaining + "/5)");
            }
        });
    }
    private PlacedTileState getEffectiveTileState(int x, int y) {
        Coord c = new Coord(x, y);

        if (placedTiles.containsKey(c)) {
            return placedTiles.get(c);
        }
        for (PreviewInfo info : pendingPlacements.values()) {
            if (info.x == x && info.y == y) {
                JsonObject json = tileDefinitions.get(info.tileId);
                return new PlacedTileState(json, info.rotation);
            }
        }
        return null;
    }

    private PlacedTileState getEffectiveTileStateExcluding(int x, int y, int excludeTileId) {
        Coord c = new Coord(x, y);
        if (placedTiles.containsKey(c)) {
            return placedTiles.get(c);
        }
        for (PreviewInfo info : pendingPlacements.values()) {
            if (info.tileId == excludeTileId) continue;
            if (info.x == x && info.y == y) {
                JsonObject json = tileDefinitions.get(info.tileId);
                return new PlacedTileState(json, info.rotation);
            }
        }
        return null;
    }
    private void triggerPlacementErrorAnimation(StackPane cell) {
        if (cell == null) return;

        Rectangle flash = new Rectangle(CELL_SIZE, CELL_SIZE, Color.RED);
        flash.setOpacity(0.75);
        flash.setMouseTransparent(true);
        flash.setArcWidth(10);
        flash.setArcHeight(10);

        cell.getChildren().add(flash);

        FadeTransition ft = new FadeTransition(Duration.millis(180), flash);
        ft.setFromValue(0.75);
        ft.setToValue(0.0);
        ft.setAutoReverse(true);
        ft.setCycleCount(6); // 3 blinks (on/off)
        ft.setOnFinished(ev -> {
            cell.getChildren().remove(flash);
        });
        ft.play();
    }
}
