package frontend.ui.controllers;

import java.io.IOException;
import java.time.LocalDate;

import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.stage.Stage;


/**
 * Controller für das Main-Menu der Anwendung.
 *
 * <p>Der Main-Menu-Screen dient als Einstiegspunkt in das Spiel und ermöglicht:</p>
 * <ul>
 *   <li>Eingabe des Spielernamens</li>
 *   <li>Auswahl des Geburtsdatums (keine zukünftigen Daten erlaubt)</li>
 *   <li>Erstellen eines neuen Spielraums</li>
 *   <li>Beitritt zu einem bestehenden Spielraum über Raumcode</li>
 * </ul>
 *
 * <p>Die Eingaben werden über JavaFX-Properties validiert und per
 * Data Binding mit den UI-Elementen verknüpft.</p>
 */public class MainMenuController {

    @FXML private TextField nameField;
    @FXML private DatePicker birthDatePicker;
    @FXML private TextField roomCodeField;
    @FXML private Button createRoomButton;
    @FXML private Button joinRoomButton;

    private Stage primaryStage;

    /** Spielername (ViewModel-Property für Data Binding) */
    private final StringProperty name = new SimpleStringProperty("");
    /** Geburtsdatum des Spielers (ViewModel-Property) */
    private final ObjectProperty<LocalDate> birthdate = new SimpleObjectProperty<>(null);

    private BooleanBinding nameValid;
    private BooleanBinding birthdateValid;
    private BooleanBinding roomCodeValid;

    /**
     * Setzt die primäre {@link Stage} der Anwendung.
     *
     * <p>Diese Stage wird später für Scene-Wechsel
     * (z. B. ins Lobby-Fenster) wiederverwendet.</p>
     *
     * @param stage
     *        Primäre JavaFX-Stage der Anwendung
     */
    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;}


    /**
     * Initialisiert den Main-Menu-Screen nach dem Laden der FXML.
     *
     * <p>Aufgaben dieser Methode:</p>
     * <ul>
     *   <li>Konfiguration des DatePickers (keine zukünftigen Daten)</li>
     *   <li>Bidirektionales Data Binding zwischen View und ViewModel</li>
     *   <li>Definition von Validierungsregeln für Eingabefelder</li>
     *   <li>Aktivieren/Deaktivieren der Buttons abhängig von der Eingabe</li>
     * </ul>
     */
    @FXML
    private void initialize() {

        birthDatePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                boolean futureDate = !empty && item != null && item.isAfter(LocalDate.now());
                setDisable(empty || futureDate);
                setOpacity((empty || futureDate) ? 0.5 : 1.0);
            }
        });

        birthDatePicker.valueProperty().addListener((obs, oldDate, newDate) -> {
            if (newDate != null && newDate.isAfter(LocalDate.now())) {
                if (oldDate != null && !oldDate.isAfter(LocalDate.now())) {
                    birthDatePicker.setValue(oldDate);
                } else {
                    birthDatePicker.setValue(LocalDate.now());
                }
            }
        });

        name.bindBidirectional (nameField.textProperty());
        birthdate.bindBidirectional (birthDatePicker.valueProperty());

        birthdateValid = new BooleanBinding() {
            { super.bind(birthdate); }
            @Override
            protected boolean computeValue() {
                LocalDate date = birthdate.get();
                return date != null && !date.isAfter(LocalDate.now());
            }
        };
        roomCodeValid = roomCodeField.textProperty().isNotEmpty();

        nameValid = new BooleanBinding() {
            { super.bind(name); }
            @Override protected boolean computeValue() {
                String n = name.get();
                return n != null && !n.trim().isEmpty();
            }
    };
        createRoomButton.disableProperty().bind(
                nameValid.not().or(birthdateValid.not())
        );

        joinRoomButton.disableProperty().bind(
                nameValid.not()
                        .or(birthdateValid.not())
                        .or(roomCodeValid.not())
        );
    }

    /**
     * Wird aufgerufen, wenn der Benutzer einen neuen Spielraum erstellen möchte.
     *
     * <p>Die Eingaben werden validiert und anschließend
     * der Lobby-Screen im „Create Room“-Modus geöffnet.</p>
     */
    @FXML
    private void onCreateRoom() {
        openLobby(name.get().trim(), birthdate.get().toString(), null, true);
    }

    /**
     * Wird aufgerufen, wenn der Benutzer einem bestehenden Spielraum beitreten möchte.
     *
     * <p>Der Raumcode muss gesetzt sein, andernfalls bleibt der Button deaktiviert.</p>
     */
    @FXML
    private void onJoinRoom() {
        openLobby(name.get().trim(), birthdate.get().toString(), roomCodeField.getText().trim(), false);
    }

    /**
     * Öffnet den Lobby-Screen und übergibt die benötigten
     * Session-Informationen an den {@link LobbyController}.
     *
     * <p>Je nach Parameter wird entweder ein neuer Raum erstellt
     * oder einem bestehenden Raum beigetreten.</p>
     *
     * @param name
     *        Benutzername des Spielers
     *
     * @param birthdate
     *        Geburtsdatum des Spielers als String
     *
     * @param roomCode
     *        Raumcode zum Beitreten (oder {@code null} beim Erstellen)
     *
     * @param createRoom
     *        {@code true}, um einen neuen Raum zu erstellen,
     *        {@code false} zum Beitreten
     */
    private void openLobby(String name, String birthdate, String roomCode, boolean createRoom) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/Lobby.fxml"));
            Parent root = loader.load();
            // set controller params
            Object ctrl = loader.getController();
            if (ctrl instanceof LobbyController lc) {
                // Stage bestimmen, falls nicht gesetzt
                if (primaryStage == null) {
                    primaryStage = (Stage) createRoomButton.getScene().getWindow();
                }

                lc.setPrimaryStage(primaryStage);
                lc.setUsername(name);
                lc.setBirthdate(birthdate);
                lc.setRoomCode(roomCode);
                lc.setCreateRoom(createRoom);
                lc.startClient();
            }

            primaryStage.setScene(new Scene(root, 1000, 700));
            primaryStage.centerOnScreen();
        }catch (IOException e) {
            e.printStackTrace();
        }

        }}
