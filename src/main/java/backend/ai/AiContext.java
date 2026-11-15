package backend.ai;

import backend.model.tile.Tile;

import java.util.Random;
import java.util.function.IntFunction;

/**
 * Kontextdaten für KI-Entscheidungen.
 *
 * @param myClientId Client-ID der KI
 * @param round aktuelle Runde
 * @param phase aktuelle Phase
 * @param coins aktuelle Münzen
 * @param scoring Scoring-Ansicht
 * @param state Spielzustands-Ansicht
 * @param tiles Tile-Lookup nach ID
 * @param rng Zufallsquelle
 */
public record AiContext(
        int myClientId,
        int round,
        int phase,
        int coins,
        ScoringView scoring,
        GameStateView state,
        IntFunction<Tile> tiles,
        Random rng
) {}
