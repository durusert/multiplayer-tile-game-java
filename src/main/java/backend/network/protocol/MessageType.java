package backend.network.protocol;

/**
 * Alle Nachrichtentypen gemäß SEP-Protokoll 0.1
 */
public enum MessageType {

    // Verbindungsaufbau
    HELLO_CLIENT("HelloClient"),
    ALIVE("Alive"),
    HELLO_SERVER("HelloServer"),
    WELCOME("Welcome"),

    // Lobby
    PLAYER_VALUES("PlayerValues"),
    PLAYER_ADDED("PlayerAdded"),
    PLAYER_LEFT("PlayerLeft"),
    SET_STATUS("SetStatus"),
    PLAYER_STATUS("PlayerStatus"),
    GAME_STARTED("GameStarted"),
    GAME_STARTED_ACK("GameStartedAck"),

    // Chat
    SEND_CHAT("SendChat"),
    RECEIVED_CHAT("ReceivedChat"),

    // Spielablauf – allgemeiner Status
    ROUND_PHASE_UPDATE("RoundPhaseUpdate"),
    CURRENT_PLAYER("CurrentPlayer"),
    START_PLAYER_UPDATE("StartPlayerUpdate"),
    COIN_UPDATE("CoinUpdate"),

    // Einkommen (client-triggered)
    COLLECT_INCOME("CollectIncome"),

    // Plättchen ziehen
    DRAW_LANDSCAPE_TILE("DrawLandscapeTile"),
    LANDSCAPE_TILE_DRAWN("LandscapeTileDrawn"),

    // Preise
    SET_TILE_PRICES("SetTilePrices"),
    TILE_PRICING_COMPLETED("TilePricingCompleted"),
    PRICE_SUBMIT_REJECTED("PriceSubmitRejected"),
    REVEAL_TILE_PRICES("RevealTilePrices"),

    // Kaufen
    BUY_TILE("BuyTile"),
    TILE_PURCHASED("TilePurchased"),

    // Anbauen
    AVAILABLE_TILES("AvailableTiles"),
    BUILD_TILE("BuildTile"),
    TILE_BUILT("TileBuilt"),
    TILE_RETURNED("TileReturned"),
    BUILD_DONE("BuildDone"),

    // Scoring & Spielende
    INCOME_RECEIVED("IncomeReceived"),
    VICTORY_POINT_UPDATE("VictoryPointUpdate"),
    GAME_ENDED("GameEnded"),

    // Besondere Nachrichten
    ERROR("Error"),

    // Erweiterungen Protkoll 1.0
    SCORING_UPDATE("ScoringUpdate"),
    GAME_OVER("GameOver"),
    CONNECTION_UPDATE("ConnectionUpdate"),

    // Manual AI Control
    ADD_AI_REQUEST("AddAiRequest"),
    ADD_AI_RESPONSE("AddAiResponse"),
    REMOVE_AI_REQUEST("RemoveAiRequest"),
    REMOVE_AI_RESPONSE("RemoveAiResponse");

    private final String wireName;

    MessageType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * String, der im JSON unter "messageType" steht.
     */
    public String getWireName() {
        return wireName;
    }

    /**
     * Wandelt den JSON-String in einen enum-Wert um.
     */
    public static MessageType fromWireName(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (MessageType t : values()) {
            if (t.wireName.equals(wireName)) {
                return t;
            }
        }
        return null;
    }
}
