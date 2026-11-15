package backend.model;

import backend.model.enums.GameStatus;
import backend.model.enums.RoundPhase;
import backend.model.tile.TileBag;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Speichert Zustände zu: Spieler, Rundenzähler, aktuelle Phase, Markt, Beutel, ScoringTiles.
 */
public class Game {

    private int currentRound = 0;

    private GameStatus status = GameStatus.LOBBY;
    private RoundPhase roundPhase = RoundPhase.NONE;
    private final List<Player> players = new ArrayList<>();
    private final TileBag tileBag = new TileBag();
    private final Market market = new Market();
    private ScoringTiles scoringTiles = new ScoringTiles();
    private final UUID gameId = UUID.randomUUID();

    private int currentPlayerIndex = 0;
    private int startPlayerIndex = 0;

    // Getter & Setter

    /**
     * Gibt den aktuellen Spielstatus zurück.
     *
     * @return aktueller {@link GameStatus}
     */
    public GameStatus getStatus() {
        return status;
    }

    /**
     * Setzt den aktuellen Spielstatus.
     *
     * @param status neuer {@link GameStatus}
     */
    public void setStatus(GameStatus status) {
        this.status = status;
    }

    /**
     * Gibt die aktuelle Rundenphase zurück.
     *
     * @return aktuelle {@link RoundPhase}
     */
    public RoundPhase getRoundPhase() {
        return roundPhase;
    }

    /**
     * Setzt die aktuelle Rundenphase.
     *
     * @param roundPhase neue {@link RoundPhase}
     */
    public void setRoundPhase(RoundPhase roundPhase) {
        this.roundPhase = roundPhase;
    }

    /**
     * Gibt die aktuelle Rundennummer zurück.
     *
     * @return aktuelle Runde (0-basiert)
     */
    public int getCurrentRound() {
        return currentRound;
    }

    /**
     * Erhöht den Rundenzähler um 1.
     */
    public void advanceRound() {
        currentRound++;
    }

    /**
     * Gibt die Spielerliste zurück.
     *
     * @return Liste der Spieler
     */
    public List<Player> getPlayers() {
        return players;
    }

    /**
     * Fügt einen Spieler hinzu, falls er nicht bereits enthalten ist.
     *
     * @param player Spieler, der hinzugefügt werden soll
     */
    public void addPlayer(Player player) {
        if (player == null) return;
        if (!players.contains(player)) {
            players.add(player);
        }
    }

    /**
     * Gibt den Markt zurück.
     *
     * @return {@link Market}
     */
    public Market getMarket() {
        return market;
    }

    /**
     * Gibt den Beutel der Plättchen zurück.
     *
     * @return {@link TileBag}
     */
    public TileBag getTileBag() {
        return tileBag;
    }

    /**
     * Gibt die aktuellen ScoringTiles zurück.
     *
     * @return {@link ScoringTiles}
     */
    public ScoringTiles getScoringTiles() {
        return scoringTiles;
    }

    /**
     * Setzt die ScoringTiles.
     *
     * @param scoringTiles neue ScoringTiles
     * @return gesetzte {@link ScoringTiles}
     */
    public ScoringTiles setScoringTiles(ScoringTiles scoringTiles) {
        return this.scoringTiles = scoringTiles;
    }

    /**
     * Gibt die eindeutige Spiel-ID zurück.
     *
     * @return {@link UUID} der Partie
     */
    public UUID getGameId() {
        return gameId;
    }

    /**
     * Gibt den aktuell aktiven Spieler zurück.
     *
     * @return aktueller {@link Player} oder {@code null} bei leerer Liste
     */
    public Player getCurrentPlayer() {
        if (players.isEmpty()) { return null; }
        return players.get(currentPlayerIndex);
    }

    /**
     * Wechselt zum nächsten Spieler (zyklisch).
     */
    public void nextPlayer() {
        if (players.isEmpty()) { return; }
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
    }

    /**
     * Setzt den Index des aktuellen Spielers.
     *
     * @param index Index des aktuellen Spielers
     */
    public void setCurrentPlayerIndex(int index) {
        this.currentPlayerIndex = index;
    }

    /**
     * Gibt den Startspieler zurück.
     *
     * @return Startspieler oder {@code null} bei leerer Liste
     */
    public Player getStartPlayer() {
        if (players.isEmpty()) { return null; }
        return players.get(startPlayerIndex);
    }

    /**
     * Setzt den Startspielerindex (zyklisch) und ggf. den aktuellen Spielerindex.
     *
     * @param index gewünschter Startspielerindex
     */
    public void setStartPlayerIndex(int index) {
        if  (players.isEmpty()) {
            this.startPlayerIndex = 0;
            this.currentPlayerIndex = 0;
            return;
        }
        this.startPlayerIndex = Math.floorMod(index, players.size());
    }

    /**
     * Rotiert den Startspieler um eins weiter.
     */
    public void rotateStartPlayer() {
        if (players.isEmpty()) { return; }
        setStartPlayerIndex(startPlayerIndex + 1);
    }

    /**
     * Setzt den aktuellen Spieler auf den Startspieler zurück.
     */
    public void resetCurrentPlayerToStart() {
        setCurrentPlayerIndex(startPlayerIndex);
    }

    /**
     * Ermittelt den Index des jüngsten Spielers anhand des Geburtsdatums.
     *
     * @param players Spielerliste
     * @return Index des jüngsten Spielers (Fallback: 0)
     * @throws IllegalArgumentException wenn die Liste {@code null} oder leer ist
     */
    public int youngestPlayerIndex(List<Player> players) {
        if (players == null || players.isEmpty()) {
            throw new IllegalArgumentException("Spielerliste darf nicht leer sein.");
        }

        int indexOfYoungest = -1;
        LocalDate youngest = null;

        for (int i = 0; i < players.size(); i++) {
            LocalDate bd = players.get(i).getBirthDate();

            // Spieler ohne Geburtsdatum ignorieren
            if (bd == null) {
                continue;
            }

            // Erster Spieler mit Datum ODER jünger als bisheriger jüngster
            if (youngest == null || bd.isAfter(youngest)) {
                youngest = bd;
                indexOfYoungest = i;
            }
        }

        // Kein Spieler hat ein gültiges birthDate:
        // -> Startspieler = erster in der Liste (Join-Reihenfolge)
        if (indexOfYoungest == -1) {
            return 0;
        }
        return indexOfYoungest;
    }
}
