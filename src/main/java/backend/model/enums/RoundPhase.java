package backend.model.enums;

/**
 * Phasen innerhalb einer Runde.
 */
public enum RoundPhase {
    NONE,  // bevor Spiel startet
    INCOME,
    DRAW,
    DISCARD,
    PRICE,
    REVEAL,
    BUY,
    BUILD,
    SCORING,
    ROUND_END
}
