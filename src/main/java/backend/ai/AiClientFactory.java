package backend.ai;

import java.util.Locale;
import java.util.Random;

/**
 * Factory zum Erzeugen von {@link AiClient}-Instanzen mit Strategieauswahl.
 */
public final class AiClientFactory {
    private AiClientFactory() {}

    /**
     * Erstellt einen {@link AiClient} basierend auf der Algorithmuskennung.
     *
     * @param host Server-Host
     * @param port Server-Port
     * @param name KI-Name
     * @param birthDate Geburtsdatum
     * @param algoWire Algorithmuskennung (z.B. RANDOM, GREEDY, A_STAR)
     * @return neuer {@link AiClient}
     */
    public static AiClient create(String host, int port, String name, String birthDate, String algoWire) {
        String a = (algoWire == null ? "RANDOM" : algoWire).trim().toUpperCase(Locale.ROOT);

        AiDecisionStrategy strategy = switch (a) {
            case "A_STAR", "ASTAR", "A*" -> new AStarDecisionStrategy();
            case "GREEDY" -> new GreedyDecisionStrategy();
            default -> new RandomDecisionStrategy(new Random());
        };

        return new AiClient(host, port, name, birthDate, strategy);
    }
}
