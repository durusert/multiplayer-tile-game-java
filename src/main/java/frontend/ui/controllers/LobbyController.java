package frontend.ui.controllers;

import java.util.Optional;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import frontend.client.WebSocketChatClient;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

import frontend.ui.audio.BackgroundMusic;


/**
 * Controller für den Lobby-Screen.
 *
 * <p>Die Lobby stellt den zentralen Treffpunkt vor Spielbeginn dar und bietet:</p>
 * <ul>
 *   <li>Anzeige aller verbundenen Spieler</li>
 *   <li>Öffentlichen und privaten Chat (Threads)</li>
 *   <li>Farb-/Spielfigur-Auswahl</li>
 *   <li>Ready-Status pro Spieler</li>
 *   <li>Hinzufügen und Entfernen von KI-Spielern</li>
 *   <li>Übergang in den Game-Start-Screen</li>
 * </ul>
 *
 * <p>Die Kommunikation mit dem Server erfolgt über einen
 * {@link frontend.client.WebSocketChatClient}.</p>
 */public class LobbyController {

    @FXML private ListView<String> playersList;
    @FXML private Label lobbyInfo;
    @FXML private TextArea chatArea;
    @FXML private TextField messageField;
    @FXML private Button sendButton;
    @FXML private Button readyButton;
    @FXML private Button leaveButton;
    @FXML private javafx.scene.layout.Region colorRedRegion;
    @FXML private javafx.scene.layout.Region colorGreenRegion;
    @FXML private javafx.scene.layout.Region colorBlueRegion;
    @FXML private javafx.scene.layout.Region colorYellowRegion;
    @FXML private javafx.scene.layout.Region colorWhiteRegion;
    @FXML private Button addAiButton;
    @FXML private Button removeAiButton;

    @FXML private Button settingsButton;
    @FXML private HBox settingsPanel;
    @FXML private Slider musicVolumeSlider;
    @FXML private Label musicVolumeValue;

    private Stage primaryStage;
    private WebSocketChatClient client;
    private Consumer<String> inboundListener;
    private frontend.ui.controllers.GameController activeGameController;
    private final Gson gson = new Gson();

    // map of playerId -> color (hex string). Based on server-assigned gamePiece when available.
    private final java.util.Map<String, String> colorMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> pieceByPlayer = new java.util.concurrent.ConcurrentHashMap<>();
    // pieces already taken in this lobby (lowercase: blue/green/red/yellow/white)
    private final java.util.Set<String> takenPieces = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private final java.util.Map<String, Integer> nameToClientId = new java.util.HashMap<>();

    private static final String[] GAME_PIECE_PALETTE = {"#007aff", "#34c759", "#ff3b30", "#ffcc00", "#ffffff"};

    // currently selected game piece color for this local player; default null -> backend picks
    private final StringProperty selectedPiece = new SimpleStringProperty("");

    private final StringProperty username = new SimpleStringProperty("");
    private final StringProperty birthdate = new SimpleStringProperty("");
    private final StringProperty roomCode = new SimpleStringProperty("");
    private final BooleanProperty createRoom = new SimpleBooleanProperty(false);

    private final StringProperty chatText = new SimpleStringProperty("");
    private final StringProperty messageText = new SimpleStringProperty("");

    private final BooleanProperty isReady = new SimpleBooleanProperty(false);
    private final BooleanProperty connected = new SimpleBooleanProperty(false);

    private final ObservableList<String> players = FXCollections.observableArrayList();

    private boolean switchingToGame = false;

    private static final String GLOBAL_THREAD = "All";
    private final java.util.Map<String, java.util.List<String>> threads = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> unreadByPlayer = new java.util.HashMap<>();
    private String activeThread = GLOBAL_THREAD;
    private ContextMenu addAiMenu;

    private final BooleanProperty canReady = new SimpleBooleanProperty(true);

    public LobbyController() {
        threads.put(GLOBAL_THREAD, new java.util.ArrayList<>());
    }

    /**
     * Setzt die primäre Stage für spätere Scene-Wechsel.
     *
     * @param stage
     *        Haupt-Stage der Anwendung
     */
    public void setPrimaryStage(Stage stage) { this.primaryStage = stage; }
    /**
     * Setzt den Benutzernamen des lokalen Spielers.
     *
     * @param username
     *        Spielername
     */
    public void setUsername(String username) { this.username.set(username != null ? username : ""); }
    /**
     * Setzt das Geburtsdatum des Spielers.
     *
     * @param birthdate
     *        Geburtsdatum als String
     */
    public void setBirthdate(String birthdate) { this.birthdate.set(birthdate != null ? birthdate : ""); }
    /**
     * Setzt den Lobby-Code für den Beitritt zu einem bestehenden Raum.
     *
     * @param roomCode
     *        Vierstelliger Lobby-Code
     */
    public void setRoomCode(String roomCode) { this.roomCode.set(roomCode != null ? roomCode : ""); }
    /**
     * Legt fest, ob ein neuer Raum erstellt oder einem bestehenden beigetreten wird.
     *
     * @param createRoom
     *        {@code true} = Raum erstellen,
     *        {@code false} = Raum beitreten
     */
    public void setCreateRoom(boolean createRoom) { this.createRoom.set(createRoom); }


    /**
     * Initialisiert die Lobby nach dem Laden der FXML-Datei.
     *
     * <p>Diese Methode:</p>
     * <ul>
     *   <li>startet die Hintergrundmusik</li>
     *   <li>initialisiert Chat-Threads</li>
     *   <li>konfiguriert UI-Bindings</li>
     *   <li>setzt Cell-Factory für die Spielerliste</li>
     *   <li>initialisiert Farbauswahl und KI-Menü</li>
     * </ul>
     */
    @FXML
    private void initialize() {
        // Loop background music while the Lobby is open.
        BackgroundMusic.playLoop("/music/medieval-happy-music-412790 2.mp3");

        // Preload/warm-up SFX to avoid first-play delay.
        frontend.ui.audio.SoundEffects.warmUp();

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

        // List binding
        if (playersList != null) {
            playersList.setItems(players);
            ensureGlobalEntryPresent();
            playersList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == null) {
                    playersList.getSelectionModel().select(GLOBAL_THREAD);
                    return;
                }
                activateThread(newVal);
            });
        }

        if (lobbyInfo != null) { //lobbyInfo text binding
            lobbyInfo.textProperty().bind(
                    Bindings.concat("Lobby code: ").concat(
                            Bindings.when(roomCode.isEmpty()).then("-").otherwise(roomCode)
                    ));}

        if (chatArea != null) { //chat bind
            chatArea.textProperty().bind(chatText);}

        if (messageField != null) { //messagerField <-> messsageText
            messageText.bindBidirectional(messageField.textProperty());}


        // sendButton & messageField nur aktiv, wenn connected
        if (sendButton != null) { sendButton.disableProperty().bind(connected.not());}
        if (messageField != null) { messageField.disableProperty().bind(connected.not());}

        // Ready-Button Text via Binding
        if (readyButton != null) {
            readyButton.textProperty().bind(
                    Bindings.when(isReady).then("Not Ready").otherwise("Ready")
            );
            readyButton.disableProperty().bind(connected.not().or(canReady.not()));
        }

        // Custom cell factory
        if (playersList != null) {
            playersList.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                        return;
                    }
                    setText(null);
                    HBox box = new HBox(8);
                    Label nameLbl = new Label(item);
                    nameLbl.getStyleClass().add("player-name");
                    HBox.setHgrow(nameLbl, Priority.ALWAYS);
                    nameLbl.setMaxWidth(Double.MAX_VALUE);
                    box.getChildren().add(nameLbl);

                    String threadKey = threadKeyForDisplay(item);
                    int unread = getUnreadCount(threadKey);
                    if (!GLOBAL_THREAD.equals(threadKey) && unread > 0) {
                        Label badge = new Label(String.valueOf(unread));
                        badge.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-padding: 2 6; "
                                + "-fx-background-radius: 10;");
                        box.getChildren().add(badge);
                    }

                    // Button für private Nachricht direkt im Eintrag
                    if (!GLOBAL_THREAD.equals(item)) {
                        String pid = item.endsWith(" (you)") ? item.substring(0, item.length() - 6) : item;

                        // Check if player is a bot
                        boolean isBot = pid.startsWith("Bot_");

                        // Only show private message button for non-bot players
                        if (!isBot) {
                            Button dmButton = new Button("Send private message");
                            dmButton.getStyleClass().add("lobby-dm-button");

                            boolean isSelf = pid.equals(username.get());
                            dmButton.setDisable(isSelf);

                            dmButton.setOnAction(ev -> {
                                playersList.getSelectionModel().select(item);
                                activateThread(item);
                                if (messageField != null) messageField.requestFocus();
                                ev.consume();
                            });

                            box.getChildren().add(dmButton);
                        }
                    }

                    if (!GLOBAL_THREAD.equals(item)) {
                        Region color = new Region();
                        color.setPrefSize(24, 24);
                        String pid = item.endsWith(" (you)") ? item.substring(0, item.length() - 6) : item;

                        String c = colorMap.get(pid);
                        if (c != null && !c.isBlank()) {
                            String piece = pieceByPlayer.get(pid);
                            boolean isSelf = pid.equals(username.get());
                            boolean isSelected = isSelf && piece != null && piece.equalsIgnoreCase(selectedPiece.get());
                            String border = isSelected ? "#D5A649" : "rgba(26,23,28,0.35)";
                            int bw = isSelected ? 3 : 1;
                            color.setStyle("-fx-background-color: " + c + "; -fx-background-radius: 4; "
                                    + "-fx-border-color: " + border + "; -fx-border-width: " + bw + "; -fx-border-radius: 4;");
                            box.getChildren().add(color);
                        }
                    }
                    box.getStyleClass().add("player-cell");
                    setGraphic(box);
                }
            });
            playersList.getSelectionModel().select(GLOBAL_THREAD);
        }

        // Hard fallback styling for the color dots.
        // Some launch paths (e.g., via ChatClient -> UiLauncher) can end up with CSS not applied/reloaded.
        // Inline styles ensure the dots are always visible.
        applyDotStyle(colorRedRegion, "#ff3b30");
        applyDotStyle(colorGreenRegion, "#34c759");
        applyDotStyle(colorBlueRegion, "#007aff");
        applyDotStyle(colorYellowRegion, "#ffcc00");
        applyDotStyle(colorWhiteRegion, "#ffffff");

        if (colorRedRegion != null) {
            colorRedRegion.setOnMouseClicked(e -> selectColor("red"));
        }
        if (colorGreenRegion != null) {
            colorGreenRegion.setOnMouseClicked(e -> selectColor("green"));
        }
        if (colorBlueRegion != null) {
            colorBlueRegion.setOnMouseClicked(e -> selectColor("blue"));
        }
        if (colorYellowRegion != null) {
            colorYellowRegion.setOnMouseClicked(e -> selectColor("yellow"));
        }
        if (colorWhiteRegion != null) {
            colorWhiteRegion.setOnMouseClicked(e -> selectColor("white"));
        }


        // Also ensure we don't visually mark any dot as selected on startup.
        selectedPiece.set("");
        if (colorRedRegion != null)   colorRedRegion.getStyleClass().remove("color-dot-selected");
        if (colorGreenRegion != null) colorGreenRegion.getStyleClass().remove("color-dot-selected");
        if (colorBlueRegion != null)  colorBlueRegion.getStyleClass().remove("color-dot-selected");
        if (colorYellowRegion != null) colorYellowRegion.getStyleClass().remove("color-dot-selected");
        if (colorWhiteRegion != null) colorWhiteRegion.getStyleClass().remove("color-dot-selected");

        refreshColorAvailabilityUI();

        addAiMenu = new ContextMenu();

        MenuItem aStar = new MenuItem("A*");
        MenuItem greedy = new MenuItem("Greedy");
        MenuItem random = new MenuItem("RandomAI");

        aStar.setOnAction(e -> addAiOfType("A_STAR"));
        greedy.setOnAction(e -> addAiOfType("GREEDY"));
        random.setOnAction(e -> addAiOfType("RANDOM"));

        addAiMenu.getItems().addAll(aStar, greedy, random);

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

    private static void applyDotStyle(Region region, String color) {
        if (region == null) return;

        // Ensure it has a size even if computed sizes collapse.
        region.setMinSize(26, 26);
        region.setPrefSize(26, 26);
        region.setMaxSize(26, 26);

        // Fill + circular shape.
        region.setStyle(
                "-fx-background-color: " + color + ";"
                        + "-fx-background-radius: 13;"
                        + "-fx-border-radius: 13;"
                        + "-fx-border-width: 2;"
                        + "-fx-border-color: transparent;"
                        + "-fx-cursor: hand;"
        );
    }

    /**
     * Wählt die angegebene Farbe aus, sorgt dafür dass nur ein Button selected ist,
     * und aktualisiert selectedPiece. Diese Methode prüft NICHT, ob die Farbe bereits
     * von einem anderen Spieler belegt ist – das wird in rememberPlayerPiece/Serverlogik
     * abgebildet.
     */
    private void selectColor(String colorKey) {
        selectedPiece.set(colorKey);

        // Style-Reset für alle Dots
        if (colorRedRegion != null)   colorRedRegion.getStyleClass().remove("color-dot-selected");
        if (colorGreenRegion != null) colorGreenRegion.getStyleClass().remove("color-dot-selected");
        if (colorBlueRegion != null)  colorBlueRegion.getStyleClass().remove("color-dot-selected");
        if (colorYellowRegion != null) colorYellowRegion.getStyleClass().remove("color-dot-selected");
        if (colorWhiteRegion != null) colorWhiteRegion.getStyleClass().remove("color-dot-selected");

        if ("red".equals(colorKey) && colorRedRegion != null) {
            colorRedRegion.getStyleClass().add("color-dot-selected");
        } else if ("green".equals(colorKey) && colorGreenRegion != null) {
            colorGreenRegion.getStyleClass().add("color-dot-selected");
        } else if ("blue".equals(colorKey) && colorBlueRegion != null) {
            colorBlueRegion.getStyleClass().add("color-dot-selected");
        } else if ("yellow".equals(colorKey) && colorYellowRegion != null) {
            colorYellowRegion.getStyleClass().add("color-dot-selected");
        } else if ("white".equals(colorKey) && colorWhiteRegion != null) {
            colorWhiteRegion.getStyleClass().add("color-dot-selected");
        }

        // If we're connected, inform backend about desired game piece.
        // Server enforces uniqueness and broadcasts PlayerAdded with gamePiece to all clients.
        if (client != null) {
            client.setDesiredGamePiece(colorKey);
            // (implemented in WebSocketChatClient) resend the PlayerValues so server broadcasts our new color
            client.resendPlayerValues();
        }

        refreshColorAvailabilityUI();
        refreshPlayersView();
    }

    private void refreshColorAvailabilityUI() {
        setDotAvailability(colorRedRegion, "red");
        setDotAvailability(colorGreenRegion, "green");
        setDotAvailability(colorBlueRegion, "blue");
        setDotAvailability(colorYellowRegion, "yellow");
        setDotAvailability(colorWhiteRegion, "white");
    }

    private void setDotAvailability(Region region, String key) {
        if (region == null) return;
        boolean taken = takenPieces.contains(key) && !key.equalsIgnoreCase(selectedPiece.get());
        region.setOpacity(taken ? 0.35 : 1.0);
        region.setDisable(taken);
    }

    /**
     * Startet den WebSocket-Client und verbindet den Spieler mit der Lobby.
     *
     * <p>Abhängig von der Konfiguration wird:</p>
     * <ul>
     *   <li>ein neuer Raum erstellt oder</li>
     *   <li>einem bestehenden Raum beigetreten</li>
     * </ul>
     *
     * <p>Diese Methode initialisiert außerdem:</p>
     * <ul>
     *   <li>Chat-Threads</li>
     *   <li>Spielerliste</li>
     *   <li>Ready-Status</li>
     * </ul>
     */    public void startClient() {
        resetChatThreads();
        canReady.set(true);
        isReady.set(false);

        players.clear();
        ensureGlobalEntryPresent();
        if(!username.get().isBlank()) {
            players.add(username.get() + " (you)");
        }

        try {
            inboundListener = raw -> Platform.runLater(() -> handleRaw(raw));
            client = new WebSocketChatClient(resolveHost(), resolvePort(), username.get(), inboundListener);
            client.setBirthDate(birthdate.get());

            if (createRoom.get()) {
                client.createRoomAndConnect();
                String code = client.getRoomCode();
                if (code != null) roomCode.set(code);

            } else if (!roomCode.get().isBlank()) {

                String code = roomCode.get().trim().toLowerCase();

                if (!isRoomCodeFormatValid(code)) {
                    Platform.runLater(() -> showInvalidLobbyCodeAlert(code));
                    connected.set(false);
                    return;
                }

                new Thread(() -> {
                    boolean exists = roomExistsOnServer(code);

                    Platform.runLater(() -> {
                        if (!exists) {
                            showInvalidLobbyCodeAlert(code);
                            connected.set(false);
                            return;
                        }

                        try {
                            client.setRoomCode(code);
                            client.connect();
                            connected.set(true);

                            appendChat("System: Welcome to the lobby!\n");
                            if (!birthdate.get().isBlank()) {
                                appendChat("System: Your birthdate: " + birthdate.get() + "\n");
                            }
                        } catch (Exception ex) {
                            connected.set(false);
                            appendChat("System: Could not connect to chat server: " + ex.getMessage() + "\n");
                        }
                    });
                }, "roomcode-check").start();

                return;



        } else {
                client.createRoomAndConnect();
                String code = client.getRoomCode();
                if (code != null) roomCode.set(code);
            }

            connected.set(true);

            appendChat("System: Welcome to the lobby!\n");
            if (!birthdate.get().isBlank()) {
                appendChat("System: Your birthdate: " + birthdate.get() + "\n");
            }

        } catch (Exception ex) {
            connected.set(false);

            String msg = (ex.getMessage() != null) ? ex.getMessage() : "";

            if (msg.contains("GAME_ALREADY_STARTED")) {
                resetChatThreads();
                appendToThread(GLOBAL_THREAD, "Game already started. You can't enter the game!\n");
                return;
            }

            appendChat("System: Could not connect to chat server: " + msg + "\n");
        }
    }

    /**
     * Fügt eine Nachricht dem globalen Chat-Thread hinzu.
     *
     * @param text
     *        Nachrichtentext
     */
    private void appendChat(String text) {
        appendToThread(GLOBAL_THREAD, text);
    }

    /**
     * Verarbeitet rohe JSON-Nachrichten vom Server.
     *
     * <p>Unterstützte Nachrichtentypen:</p>
     * <ul>
     *   <li>Chat- und Direktnachrichten</li>
     *   <li>Spieler beigetreten / verlassen</li>
     *   <li>Statusänderungen (Ready)</li>
     *   <li>Spielstart</li>
     *   <li>Fehlermeldungen</li>
     * </ul>
     *
     * @param raw
     *        JSON-Nachricht als String
     */
    private void handleRaw(String raw) {
        if (activeGameController != null) {
            activeGameController.handleRaw(raw);
            return;
        }
        try {
            JsonObject msg = gson.fromJson(raw, JsonObject.class);
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            if ("chat_message".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                String from = payload.has("from") ? payload.get("from").getAsString() : "?";
                String message = payload.has("message") ? payload.get("message").getAsString() : "";
                appendChat(from + ": " + message + "\n");

            } else if ("dm_message".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                handleDirectMessage(payload);

            } else if ("welcome".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                if (payload != null && payload.has("players")) {
                    JsonObject pieces = (payload.has("playerPieces") && payload.get("playerPieces").isJsonObject())
                            ? payload.getAsJsonObject("playerPieces")
                            : null;

                    players.clear();
                    ensureGlobalEntryPresent();
                    for (var el : payload.getAsJsonArray("players")) {
                        String pid = el.getAsString();
                        if (pieces != null && pieces.has(pid) && pieces.get(pid).isJsonPrimitive()) {
                            rememberPlayerPiece(pid, pieces.get(pid).getAsString());
                        } else {
                            rememberPlayerPiece(pid, null);
                        }
                        players.add(pid.equals(username.get()) ? pid + " (you)" : pid);
                    }
                    appendChat("System: Welcome — players: " + payload.get("players") + "\n");
                }

            } else if ("player_joined".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                if (payload != null && payload.has("playerId")) {
                    String pid = payload.get("playerId").getAsString();
                    if (payload.has("clientID")) {
                        nameToClientId.put(pid, payload.get("clientID").getAsInt());
                    }
                    if (payload.has("gamePiece")) {
                        rememberPlayerPiece(pid, payload.get("gamePiece").getAsString());
                    } else {
                        rememberPlayerPiece(pid, null);
                    }
                    if (!players.contains(pid) && !players.contains(pid + " (you)")) players.add(pid);
                    appendChat("System: " + pid + " joined the lobby\n");
                }

            } else if ("player_updated".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                if (payload != null && payload.has("playerId")) {
                    String pid = payload.get("playerId").getAsString();
                    if (payload.has("clientID")) {
                        nameToClientId.put(pid, payload.get("clientID").getAsInt());
                    }
                    if (payload.has("gamePiece")) {
                        rememberPlayerPiece(pid, payload.get("gamePiece").getAsString());
                    }
                    if (!players.contains(pid) && !players.contains(pid + " (you)")) players.add(pid);
                }

            } else if ("player_left".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                if (payload != null) {
                    String pid = null;

                    if (payload.has("playerId")) {
                        pid = payload.get("playerId").getAsString();
                    } else if (payload.has("clientID")) {
                        int cid = payload.get("clientID").getAsInt();
                        for (var entry : nameToClientId.entrySet()) {
                            if (entry.getValue() != null && entry.getValue() == cid) {
                                pid = entry.getKey();
                                break;
                            }
                        }
                    }

                    if (pid != null) {
                        players.remove(pid);
                        players.remove(pid + " (you)");
                        nameToClientId.remove(pid);
                        colorMap.remove(pid);
                        pieceByPlayer.remove(pid);
                        appendChat("System: " + pid + " left the lobby\n");
                    }
                }

            } else if ("player_status".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                if (payload != null) {
                    int clientID = payload.has("clientID") ? payload.get("clientID").getAsInt() : -1;
                    boolean ready = payload.has("ready") && payload.get("ready").getAsBoolean();
                    appendChat("System: Player " + clientID + " is " + (ready ? "READY" : "NOT READY") + "\n");
                }

            } else if ("game_started".equals(type)) {
                // Server started the game - switch to game screen
                appendChat("System: Game is starting!\n");
                JsonObject payload = msg.getAsJsonObject("payload");
                Platform.runLater(() -> switchToGameStartScreen(payload));

            } else if ("error".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                String code = "";
                String message = "";
                if (payload != null) {
                    if (payload.has("error")) {
                        code = payload.get("error").getAsString();
                    } else if (payload.has("code")) {
                        code = payload.get("code").getAsString();
                    }
                    if (payload.has("message")) {
                        message = payload.get("message").getAsString();
                    }
                }
                if ("GAME_ALREADY_STARTED".equals(code)) {
                    canReady.set(false);
                    isReady.set(false);
                    appendChat("Game is already playing. You can't enter the game\n");
                    return;
                }

                if ("NAME_TAKEN".equals(code)) {
                    if (client != null) {
                        try { client.close(); } catch(Exception ignored){}
                        client = null;
                        connected.set(false);
                    }
                    final String msgFinal = message;
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Name Taken");
                        alert.setHeaderText("Username already occupied");
                        alert.setContentText(msgFinal + "\nReturning to Main Menu...");
                        alert.showAndWait();
                        onLeave();
                    });
                    return;
                }
                appendChat("Error: " + message + "\n");
                if ("INVALID_BIRTHDATE".equals(code)) {
                    handleInvalidBirthdateError(message);
                } else if ("USERNAME_TAKEN".equals(code) || "INVALID_USERNAME".equals(code)) {
                    if (client != null) {
                        client.close();
                        client = null;
                        connected.set(false);
                    }
                    appendChat("System: " + message + "\n");
                    Platform.runLater(this::promptForNewUsername);
                }
            } else if ("add_ai_response".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                boolean success = payload != null && payload.has("success") && payload.get("success").getAsBoolean();
                if (!success) {
                    String code = payload != null && payload.has("errorCode") ? payload.get("errorCode").getAsString() : "UNKNOWN";
                    String message = payload != null && payload.has("errorMessage") ? payload.get("errorMessage").getAsString() : "";
                    appendChat("System: Add AI failed: " + code + " " + message + "\n");
                }
            } else if ("remove_ai_response".equals(type)) {
                JsonObject payload = msg.getAsJsonObject("payload");
                boolean success = payload != null && payload.has("success") && payload.get("success").getAsBoolean();
                if (!success) {
                    String code = payload != null && payload.has("errorCode") ? payload.get("errorCode").getAsString() : "UNKNOWN";
                    String message = payload != null && payload.has("errorMessage") ? payload.get("errorMessage").getAsString() : "";
                    appendChat("System: Remove AI failed: " + code + " " + message + "\n");
                }
            }
        } catch (Exception ex) {
            appendChat("System: Received invalid JSON: " + raw + "\n");
        }
    }

    /**
     * Verarbeitet eingehende private Nachrichten (Direct Messages).
     *
     * @param payload
     *        JSON-Payload der Direktnachricht
     */
    private void handleDirectMessage(JsonObject payload) {
        if (payload == null) return;
        String from = payload.has("from") ? payload.get("from").getAsString() : "?";
        String message = payload.has("message") ? payload.get("message").getAsString() : "";
        String to = payload.has("to") ? payload.get("to").getAsString() : username.get();
        String me = username.get() != null ? username.get() : "";
        boolean outgoing = me.equals(from);
        String other = outgoing ? to : from;
        if (other == null || other.isBlank()) {
            other = outgoing ? to : from;
        }
        if (other == null || other.isBlank()) {
            other = "?";
        }
        ensurePlayerEntryExists(other);
        String line = outgoing
                ? "You -> " + other + ": " + message + "\n"
                : other + " -> You: " + message + "\n";
        appendToThread(other, line);
    }

    /**
     * Sendet eine Chat-Nachricht im aktuellen Thread.
     */
    @FXML
    private void onSend() {
        String text = messageText.get() != null ? messageText.get().trim() : "";
        if (text.isEmpty()) return;

        if (client != null) {
            if (GLOBAL_THREAD.equals(activeThread)) {
                client.sendMessage(text);
            } else {
                client.sendDirectMessage(activeThread, text);
            }
        } else {
            if (GLOBAL_THREAD.equals(activeThread)) {
                appendToThread(GLOBAL_THREAD, username.get() + ": " + text + "\n");
            } else {
                appendToThread(activeThread, "You -> " + activeThread + ": " + text + "\n");
            }
        }

        messageText.set(""); // leert automatisch messageField (binding)
    }

    /**
     * Setzt den Ready-Status für den lokalen Spieler.
     */
    @FXML
    private void onReady() {
        // Require an explicit, currently available color selection.
        String myChoice = selectedPiece.get() != null ? selectedPiece.get().trim().toLowerCase() : "";
        if (myChoice.isBlank()) {
            appendChat("System: Please choose a color before readying up.\n");
            return;
        }
        // If the server has already assigned pieces, ensure our choice isn't taken by someone else.
        // (We allow it if it's our own currently assigned piece.)
        for (var e : pieceByPlayer.entrySet()) {
            String pid = e.getKey();
            String piece = e.getValue();
            if (pid == null || piece == null) continue;
            if (!pid.equals(username.get()) && myChoice.equalsIgnoreCase(piece)) {
                appendChat("System: This color is already taken. Please pick another one.\n");
                refreshColorAvailabilityUI();
                return;
            }
        }

        isReady.set(!isReady.get());
        appendChat("System: You are " + (isReady.get() ? "READY" : "NOT READY") + "\n");
        if (client != null) client.setReady(isReady.get());
    }

    /**
     * Verlässt die Lobby und kehrt ins Hauptmenü zurück.
     */
    @FXML
    private void onLeave() {
        appendChat("System: Leaving lobby...\n");
        if (client != null) client.close();
        connected.set(false);

        // MainMenu will start its own loop on initialize.
        BackgroundMusic.stop();

        if (primaryStage != null) {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainMenu.fxml"));
                Parent root = loader.load();
                Object ctrl = loader.getController();
                if (ctrl instanceof MainMenuController mm) mm.setPrimaryStage(primaryStage);
                primaryStage.setScene(new Scene(root, 800, 600));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Prompt the user for a new username (called when server rejects the chosen name). */
    private void promptForNewUsername() {
        TextInputDialog dlg = new TextInputDialog();
        dlg.setTitle("Username already taken.");
        dlg.setHeaderText("This username is already taken.");
        dlg.setContentText("Please choose other username:");

        Optional<String> result = dlg.showAndWait();
        if (result.isPresent()) {
            String newName = result.get().trim();
            // Validate locally: non-empty and no whitespace
            if (newName.isEmpty() || newName.matches(".*\\s.*")) {
                appendChat("System: Unvalid username. Please try again.\n");
                Platform.runLater(this::promptForNewUsername);
                return;
            }
            username.set(newName);
            players.removeIf(s -> s.endsWith(" (you)"));
            players.add(newName + " (you)");

            try {
                attemptConnectWithUsername(newName);
            } catch (Exception e) {
                appendChat("System: Connection failed: " + e.getMessage() + "\n");
                Platform.runLater(this ::promptForNewUsername);
            }
        } else {
            onLeave();
        }
    }

    private void handleInvalidBirthdateError(String message) {
        if (client != null) {
            client.close();
            client = null;
            connected.set(false);
        }
        final String displayMessage = (message != null && !message.isBlank())
                ? message
                : "Birthdate can`t be in the future.";
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("unvalid birthdate");
            alert.setHeaderText("Please select correct birthdate");
            alert.setContentText(displayMessage + "\nYou will be sent back to the main menu");
            alert.showAndWait();
            onLeave();
        });
    }

    private void attemptConnectWithUsername(String user) throws Exception {
        connected.set(false);
        inboundListener = raw -> Platform.runLater(() -> handleRaw(raw));
        WebSocketChatClient newClient = new WebSocketChatClient(resolveHost(), resolvePort(), user, inboundListener);
        if (!roomCode.get().isBlank()) newClient.setRoomCode(roomCode.get());
        newClient.connect();
        this.client = newClient;
        connected.set(true);
    }

    private void resetChatThreads() {
        threads.clear();
        unreadByPlayer.clear();
        activeThread = GLOBAL_THREAD;
        threads.put(GLOBAL_THREAD, new java.util.ArrayList<>());
        renderActiveThread();
        if (playersList != null) {
            playersList.getSelectionModel().select(GLOBAL_THREAD);
        }
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
            refreshPlayersView();
        }
    }

    private void renderActiveThread() {
        ensureThreadExists(activeThread);
        java.util.List<String> lines = threads.get(activeThread);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line);
        }
        chatText.set(sb.toString());
        if (chatArea != null) {
            chatArea.positionCaret(chatText.get().length());
        }
    }

    private void activateThread(String displayEntry) {
        String key = threadKeyForDisplay(displayEntry);
        if (key == null) key = GLOBAL_THREAD;
        activeThread = key;
        ensureThreadExists(activeThread);
        if (!GLOBAL_THREAD.equals(activeThread)) {
            unreadByPlayer.remove(activeThread);
            refreshPlayersView();
        }
        renderActiveThread();
    }

    private void ensureGlobalEntryPresent() {
        if (!players.contains(GLOBAL_THREAD)) {
            players.add(0, GLOBAL_THREAD);
        } else if (players.indexOf(GLOBAL_THREAD) != 0) {
            players.remove(GLOBAL_THREAD);
            players.add(0, GLOBAL_THREAD);
        }
        if (playersList != null) {
            playersList.getSelectionModel().select(GLOBAL_THREAD);
        }
    }

    private void ensurePlayerEntryExists(String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        String self = username.get() != null ? username.get() : "";
        if (playerName.equals(self)) {
            String display = playerName + " (you)";
            if (!players.contains(display)) {
                players.add(display);
            }
            return;
        }
        if (!players.contains(playerName) && !players.contains(playerName + " (you)")) {
            players.add(playerName);
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

    private void refreshPlayersView() {
        if (playersList != null) {
            playersList.refresh();
        }
    }


    private static String colorForGamePiece(String piece) {
        if (piece == null) return null;
        return switch (piece.trim().toLowerCase()) {
            case "blue" -> "#007aff";
            case "green" -> "#34c759";
            case "red" -> "#ff3b30";
            case "yellow" -> "#ffcc00";
            case "white" -> "#ffffff";
            default -> null;
        };
    }

    private void rememberPlayerPiece(String playerId, String piece) {
        if (playerId == null || playerId.isBlank()) return;
        if (piece != null && !piece.isBlank()) {
            String normalized = piece.trim().toLowerCase();
            pieceByPlayer.put(playerId, normalized);
            String c = colorForGamePiece(normalized);
            if (c != null) {
                colorMap.put(playerId, c);
                recomputeTakenPieces();
                return;
            }
        }
        pieceByPlayer.remove(playerId);
        colorMap.remove(playerId);
        recomputeTakenPieces();
    }

    private void recomputeTakenPieces() {
        takenPieces.clear();
        for (String piece : pieceByPlayer.values()) {
            if (piece != null && !piece.isBlank()) {
                takenPieces.add(piece.trim().toLowerCase());
            }
        }
        refreshColorAvailabilityUI();
        refreshPlayersView();
    }

    /** Called when server sends GameStarted message - switch to game screen */
    private void switchToGameStartScreen(JsonObject gameStartedPayload) {
        if (switchingToGame) return;
        switchingToGame = true;

        if (client != null && inboundListener != null) {
            client.removeMessageListener(inboundListener);
        }

        try {
            if (primaryStage == null) {
                if (sendButton != null && sendButton.getScene() != null) {
                    primaryStage = (Stage) sendButton.getScene().getWindow();
                } else if (playersList != null && playersList.getScene() != null) {
                    primaryStage = (Stage) playersList.getScene().getWindow();
                }
            }
            if (primaryStage == null) {
                if (chatArea != null) chatArea.appendText("System: Could not switch scene (no stage).\n");
                return;
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/GameStart.fxml"));
            Parent root = loader.load();



            Object ctrl = loader.getController();
            if (!(ctrl instanceof GameStartController gsc)) {
                appendChat("System: Failed to load game screen.\n");
                return;
            }
            java.util.List<String> snapshotPlayersOnly = players.stream()
                    .filter(p -> p != null && !p.isBlank())
                    .filter(p -> !p.equals(GLOBAL_THREAD))
                    .map(p -> p.endsWith(" (you)") ? p.substring(0, p.length() - 6) : p)
                    .distinct()
                    .toList();


            gsc.setSession(client, username.get(), new java.util.ArrayList<>(snapshotPlayersOnly));

            if (gameStartedPayload != null && gameStartedPayload.has("scoringIDs")) {
                gsc.showScoringTilesFromIds(gameStartedPayload.getAsJsonArray("scoringIDs"));
            }

            java.util.Map<String, javafx.scene.paint.Color> playerColorsByName =
                    new java.util.LinkedHashMap<>();

            int fallbackIndex = 0;
            for (String pid : snapshotPlayersOnly) {
                String piece = pieceByPlayer.get(pid);
                String hex = colorForGamePiece(piece);
                if (hex == null) {
                    String fallbackHex = colorMap.get(pid);
                    if (fallbackHex != null) hex = fallbackHex;
                }
                if (hex == null) {
                    hex = GAME_PIECE_PALETTE[fallbackIndex % GAME_PIECE_PALETTE.length];
                    fallbackIndex++;
                }

                javafx.scene.paint.Color fxColor = javafx.scene.paint.Color.web(hex);
                playerColorsByName.put(pid, fxColor);
            }

            Scene scene = new Scene(root, 1200, 800);
            var cssUrl = getClass().getResource("/styles/game.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            } else {
                System.err.println("[Lobby] WARNING: /styles/game.css not found on classpath (GameStart will look unstyled)." +
                        " Expected at src/main/resources/styles/game.css");
            }

            java.util.List<String> snapshot = new java.util.ArrayList<>(snapshotPlayersOnly);
            scene.setUserData(new GameStartController.SessionBridge(client, username.get(), snapshot));
            primaryStage.setScene(scene);
            primaryStage.centerOnScreen();

            // Leaving lobby -> stop menu music (game has its own audio, if any).
            BackgroundMusic.stop();

            javafx.application.Platform.runLater(() -> gsc.showPlayersOnFirstField(playerColorsByName));

        } catch (Exception ex) {
            ex.printStackTrace();
            appendChat("System: Failed to open game screen: " + ex.getMessage() + "\n");
        } finally {
            switchingToGame = false;
        }
    }

    /**
     * Öffnet das KI-Auswahlmenü.
     */
    @FXML
    private void onAddAi() {
        if (client == null) {
            appendChat("System: Not connected.\n");
            return;
        }
        if (addAiMenu == null) {
            appendChat("System: AI menu not initialized.\n");
            return;
        }
        if (addAiMenu.isShowing()) {
            addAiMenu.hide();
            return;
        }

        addAiMenu.show(addAiButton, Side.BOTTOM, 0, 6); // 6px Abstand nach unten
    }

    /**
     * Entfernt die zuerst hinzugefügte KI aus der Lobby.
     */
    @FXML
    private void onRemoveAi() {
        if (client == null) {
            appendChat("System: Not connected.\n");
            return;
        }
        client.requestRemoveAiFirst();
    }

    private void addAiOfType(String type) {
        if (addAiMenu != null) addAiMenu.hide();

        if (client != null) {
            client.requestAddAi(type);
        } else {
            appendChat("System: Not connected.\n");
        }
    }

    private static String resolveHost() {
        String host = System.getProperty("isle.server.host");
        if (host == null || host.isBlank()) host = System.getenv("ISLE_SERVER_HOST");
        if (host == null || host.isBlank()) host = "localhost";
        return host.trim();
    }

    private static int resolvePort() {
        String raw = System.getProperty("isle.server.port");
        if (raw == null || raw.isBlank()) raw = System.getenv("ISLE_SERVER_PORT");
        if (raw == null || raw.isBlank()) return 8080;
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 ? port : 8080;
        } catch (NumberFormatException ex) {
            return 8080;
        }
    }

    private boolean isRoomCodeFormatValid(String code) {
        if (code == null) return false;
        String c = code.trim().toLowerCase();
        return c.matches("[a-z]{4}");
    }

    private boolean roomExistsOnServer(String code) {
        try {
            String c = code.trim().toLowerCase();
            String url = "http://" + resolveHost() + ":" + resolvePort() + "/api/room/" + c;

            var http = java.net.http.HttpClient.newHttpClient();
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .build();

            var res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200;

        } catch (Exception e) {
            return false;
        }
    }

    private void showInvalidLobbyCodeAlert(String code) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Invalid Lobby Code");
        alert.setHeaderText("Lobby not found");
        alert.setContentText("The lobby code '" + code + "' does not exist.");
        alert.showAndWait();
    }


}
