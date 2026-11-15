package backend.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Map;
import java.util.Set;

/**
 * Zufallsbasierte Entscheidungsstrategie.
 */
public final class RandomDecisionStrategy implements AiDecisionStrategy {
    private final Random rnd;

    /**
     * Erstellt eine Random-Strategie mit der angegebenen Zufallsquelle.
     *
     * @param rnd Zufallsquelle
     */
    public RandomDecisionStrategy(Random rnd) {
        this.rnd = rnd;
    }

    /**
     * Trifft Preisentscheidungen zufällig.
     *
     * @param ctx KI-Kontext
     * @param tileIds Tile-IDs
     * @return Liste von Preisentscheidungen
     */
    @Override
    public List<PriceDecision> choosePrices(AiContext ctx, List<Integer> tileIds) {
        List<PriceDecision> out = new ArrayList<>();
        if (tileIds == null || tileIds.isEmpty()) return out;

        int n = tileIds.size();
        int coins = ctx.coins();

        // wenn nur 1 Tile vorhanden -> discarden
        if (n == 1) {
            out.add(new PriceDecision(tileIds.get(0), null, true));
            return out;
        }

        // wenn nicht genug coins um alle mit mind. 1 zu bepreisen -> mehr Tiles discarden
        int discardCount = 1;
        while (coins < (n - discardCount)) {
            discardCount++;
            if (discardCount >= n) break;
        }

        // Liste mit Tiles geshuffelt
        List<Integer> ids = new ArrayList<>(tileIds);
        java.util.Collections.shuffle(ids, rnd);

        // so viele discarded wie discardCount (normal: 1)
        Set<Integer> discardIds = new java.util.HashSet<>();
        for (int i = 0; i < discardCount && i < ids.size(); i++) {
            discardIds.add(ids.get(i));
        }

        // auf nicht discardete Tiles 1 coin setzen
        int remaining = coins;
        Map<Integer, Integer> prices = new java.util.HashMap<>();

        for (int id : tileIds) {
            if (!discardIds.contains(id)) {
                prices.put(id, 1);
                remaining -= 1;
            }
        }

        // verteilt alle restliches Coins auf random Tiles (max. Preis pro Tile = 3)
        // heißt auch, dass ab coins >= 6 -> 2 Tiles immer 3 coins bepreist
        List<Integer> pricedIds = new ArrayList<>(prices.keySet());
        while (remaining > 0 && !pricedIds.isEmpty()) {
            int pick = pricedIds.get(rnd.nextInt(pricedIds.size()));
            int current = prices.get(pick);
            if (current >= 3) {
                pricedIds.remove((Integer) pick);
                continue;
            }
            prices.put(pick, current + 1);
            remaining--;
        }

        // Ergebnisse in out speichern
        for (int id : tileIds) {
            if (discardIds.contains(id)) {
                out.add(new PriceDecision(id, null, true));
            } else {
                out.add(new PriceDecision(id, prices.get(id), false));
            }
        }

        return out;
    }

    /**
     * Wählt zufällig ein kaufbares Tile oder passt.
     *
     * @param ctx KI-Kontext
     * @param options Kaufoptionen
     * @return Tile-ID oder {@link Optional#empty()}
     */
    @Override
    public Optional<Integer> chooseBuyTileId(AiContext ctx, List<BuyOption> options) {
        // keine kaufbaren Tiles -> passen
        if (options == null || options.isEmpty()) return Optional.empty();

        // liefert Liste mit Tiles die man sich leisten kann
        List<BuyOption> affordable = options.stream()
                .filter(o -> o.price() != null && o.price() <= ctx.coins())
                .toList();

        // keine kaufbaren Tiles -> passen
        // sonst 50/50 passen (weil random True/False)
        if (affordable.isEmpty() || rnd.nextBoolean()) return Optional.empty();

        // random Index von affordableTiles zugreifen & kaufen
        return Optional.of(affordable.get(rnd.nextInt(affordable.size())).tileId());
    }

    /**
     * Wählt zufällig einen legalen Build-Move.
     *
     * @param ctx KI-Kontext
     * @param tileIds Tile-IDs
     * @param legalMoves legale Züge
     * @return Build-Move oder {@link Optional#empty()}
     */
    @Override
    public Optional<BuildMove> chooseBuildMove(AiContext ctx, List<Integer> tileIds, List<int[]> legalMoves) {
        if (tileIds == null || tileIds.isEmpty()) return Optional.empty();

        int tileId = tileIds.get(0);

        // nimmt random 3 Idizes von legalMoves Liste (legalMoves liefert x, y, rotation)
        if (legalMoves != null && !legalMoves.isEmpty()) {
            int[] move = legalMoves.get(rnd.nextInt(legalMoves.size()));
            return Optional.of(new BuildMove(tileId, move[0], move[1], move[2]));
        }

        // falls keine legalMoves vorhanden -> selbst versuchen mit random
        // falls dort illegal Move rauskommt -> Error ILLEGAL_PLACEMENT -> retry
        int[] possibleOffsets = { -1, 0, 1, 2 };
        int x = possibleOffsets[rnd.nextInt(possibleOffsets.length)];
        int y = possibleOffsets[rnd.nextInt(possibleOffsets.length)];
        if (x == 0 && y == 0) x = 1;

        int[] rotations = { 0, 90, 180, 270 };
        int rotation = rotations[rnd.nextInt(rotations.length)];

        return Optional.of(new BuildMove(tileId, x, y, rotation));
    }
}
