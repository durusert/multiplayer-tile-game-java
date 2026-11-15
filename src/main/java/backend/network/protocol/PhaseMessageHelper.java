package backend.network.protocol;

import backend.model.enums.RoundPhase;
import com.google.gson.JsonObject;

/**
 * Hilfsmethoden zum Erstellen der RoundPhaseUpdate-Nachricht
 * gemäß Protokoll Version 0.1.
 */
public final class PhaseMessageHelper {

    private PhaseMessageHelper() {}

    /**
     * Erzeugt eine RoundPhaseUpdate-Nachricht.
     *
     * @param round Spielrunde (Protokoll: 0-basiert!)
     * @param phase interne Phase aus dem Modell
     */
    public static Message createRoundPhaseUpdate(int round, RoundPhase phase) {
        JsonObject body = new JsonObject();
        body.addProperty("round", round);
        body.addProperty("phase", mapPhase(phase));
        return new Message(MessageType.ROUND_PHASE_UPDATE, body);
    }

    private static int mapPhase(RoundPhase phase) {
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
}
