package backend.ai;

import java.util.*;

/**
 * Greedy-Strategie:
 * - Pricing: zufällig
 * - Buying: wählt lokal beste Kaufoption nach (value - priceWeight*price), kauft nur wenn > 0
 * - Building: wählt lokal beste Platzierung nach evaluatePlacement() für das aktuelle Tile
 */
public final class GreedyDecisionStrategy implements AiDecisionStrategy {

    private final double priceWeight; // wie viel Gewichtung Preis gegenüber Value hat

    /**
     * Erstellt eine Greedy-Strategie mit Standardgewichtung.
     */
    public GreedyDecisionStrategy() {
        this(0.65); // gleich wie A*, KI eher bereit mehr für guten Nutzen zu zahlen
    }

    /**
     * Erstellt eine Greedy-Strategie mit angegebener Gewichtung.
     *
     * @param priceWeight Gewichtung des Preises
     */
    public GreedyDecisionStrategy(double priceWeight) {
        this.priceWeight = Math.max(0.0, priceWeight);
    }

    /**
     * PRICING-PHASE: zufällig (Fokus liegt auf Buying & Building)
     */
    @Override
    public List<PriceDecision> choosePrices(AiContext ctx, List<Integer> tileIds) {
        return new RandomDecisionStrategy(ctx.rng()).choosePrices(ctx, tileIds);
    }

    /**
     * BUYING-PHASE:
     * Wählt die bezahlbare Option mit maximalem lokalen Nutzen:
     * score = value - priceWeight * price
     * Gekauft wird nur, wenn score > 0 (besser als Pass).
     */
    @Override
    public Optional<Integer> chooseBuyTileId(AiContext ctx, List<BuyOption> options) {
        if (options == null || options.isEmpty()) return Optional.empty();

        double bestScore = 0.0; // 0 = PASS
        Integer bestTileId = null;

        for (BuyOption o : options) {
            Integer price = o.price();
            if (price == null) continue;
            if (price > ctx.coins()) continue; // diese Tiles kann man sich nicht leisten

            double value = ctx.scoring().tileValueEstimate(o.tileId()); // Value auf Basis der Scoring-Tiles bestimmen
            double score = value - (priceWeight * price);

            if (score > bestScore) {
                bestScore = score;
                bestTileId = o.tileId();
            }
        }
        return bestTileId == null ? Optional.empty() : Optional.of(bestTileId);
    }

    /**
     * BUILDING-PHASE:
     * Für das aktuelle Tile (tileIds[0]) wird der legalMove mit maximalem immediate Reward gewählt
     * WICHTIG: Wenn legalMoves existieren, darf nie Optional.empty() zurückkommen
     */
    @Override
    public Optional<BuildMove> chooseBuildMove(AiContext ctx,
                                               List<Integer> tileIds,
                                               List<int[]> legalMoves) {
        if (tileIds == null || tileIds.isEmpty()) return Optional.empty();

        int tileId = tileIds.get(0);

        // keine legalen Moves
        if (legalMoves == null || legalMoves.isEmpty()) {
            return Optional.of(new BuildMove(tileId, 0, 0, 0));
        }

        double best = Double.NEGATIVE_INFINITY;
        BuildMove bestMove = null;

        for (int[] m : legalMoves) {
            int x = m[0], y = m[1], rot = m[2];

            double score = ctx.state().evaluatePlacement(tileId, x, y, rot);

            if (score > best) {
                best = score;
                bestMove = new BuildMove(tileId, x, y, rot);
            }
        }

        // ersten legalen Move nehmen
        if (bestMove == null) {
            int[] m = legalMoves.get(0);
            bestMove = new BuildMove(tileId, m[0], m[1], m[2]);
        }

        return Optional.of(bestMove);
    }
}
