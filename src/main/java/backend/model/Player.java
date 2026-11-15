package backend.model;

import backend.model.enums.GamePiece;
import backend.model.tile.Tile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Spieler mit Name, Gold, Victorypoints, Clanteriitory, gezogene Tiles, Startspieler-Marker.
 */
public class Player {

    private int clientId;
    private String name;
    private LocalDate birthDate;
    private GamePiece gamePiece;
    private int coins;
    private int victoryPoints;


    private final ClanTerritory clanTerritory;
    private final List<Tile> drawnTiles = new ArrayList<>();

    /**
     * Erstellt einen Spieler ohne Geburtsdatum.
     *
     * @param name Spielername
     * @param color Spielfigur/Farbe
     */
    public Player (String name, GamePiece color) {
        this(name, color, null);
    }

    /**
     * Erstellt einen Spieler mit optionalem Geburtsdatum.
     *
     * @param name Spielername
     * @param color Spielfigur/Farbe
     * @param birthDate Geburtsdatum (nullable)
     */
    public Player (String name, GamePiece color, LocalDate birthDate) {
        this.name = name;
        this.clanTerritory = new ClanTerritory(this);
        this.gamePiece = color;
        this.birthDate = birthDate;
    }

    // Getter & Setter
    /**
     * Gibt die Client-ID zurück.
     *
     * @return Client-ID
     */
    public int getClientId() { return clientId; }
    /**
     * Setzt die Client-ID.
     *
     * @param clientId Client-ID
     */
    public void setClientId(int clientId) { this.clientId = clientId; }

    /**
     * Gibt das Geburtsdatum zurück.
     *
     * @return Geburtsdatum oder {@code null}
     */
    public LocalDate getBirthDate() { return birthDate; }
    /**
     * Setzt das Geburtsdatum.
     *
     * @param birthDate Geburtsdatum (nullable)
     */
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }

    /**
     * Gibt den Spielernamen zurück.
     *
     * @return Name
     */
    public String getName() {
        return name;
    }

    /**
     * Setzt den Spielernamen.
     *
     * @param name Name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gibt die Spielfigur zurück.
     *
     * @return {@link GamePiece}
     */
    public GamePiece getGamePiece() { return gamePiece; }

    /**
     * Setzt die Spielfigur.
     *
     * @param gamePiece {@link GamePiece}
     */
    public void setGamePiece(GamePiece gamePiece) {
        this.gamePiece = gamePiece;
    }

    /**
     * Gibt die aktuelle Münzanzahl zurück.
     *
     * @return Münzen
     */
    public int getCoins() {
        return coins;
    }
    /**
     * Setzt die Münzanzahl.
     *
     * @param coins Münzen
     */
    public void setCoins(int coins) { this.coins = coins; }
    /**
     * Erhöht die Münzanzahl.
     *
     * @param coins hinzuzufügende Münzen
     */
    public void addCoins(int coins) {
        this.coins += coins;
    }

    /**
     * Gibt die Siegpunkte zurück.
     *
     * @return Siegpunkte
     */
    public int getVictoryPoints() {
        return victoryPoints;
    }
    /**
     * Setzt die Siegpunkte.
     *
     * @param victoryPoints Siegpunkte
     */
    public void setVictoryPoints(int victoryPoints) { this.victoryPoints = victoryPoints; }
    /**
     * Erhöht die Siegpunkte.
     *
     * @param victoryPoints hinzuzufügende Siegpunkte
     */
    public void addVictoryPoints(int victoryPoints) {
        this.victoryPoints += victoryPoints;
    }

    /**
     * Gibt das Clan-Gebiet des Spielers zurück.
     *
     * @return {@link ClanTerritory}
     */
    public ClanTerritory getClanTerritory() {
        return clanTerritory;
    }

    /**
     * Gibt die gezogenen Plättchen zurück.
     *
     * @return Liste gezogener {@link Tile}
     */
    public List<Tile> getDrawnTiles() {
        return drawnTiles;
    }

    /**
     * Fügt ein gezogenes Plättchen hinzu.
     *
     * @param tile gezogenes {@link Tile}
     */
    public void addDrawnTile(Tile tile) {
        if (tile == null) return;
        this.drawnTiles.add(tile);
    }
}
