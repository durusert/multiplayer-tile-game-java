package backend.ai;

import java.util.List;
import java.util.Optional;

/**
 * Schnittstelle für KI-Entscheidungsstrategien.
 */
public interface AiDecisionStrategy {

    /**
     * Kaufoption für die BUY-Phase.
     *
     * @param tileId Tile-ID
     * @param sellerId Verkäufer-ClientId
     * @param price Preis
     */
    record BuyOption(int tileId, int sellerId, Integer price) {}
    /**
     * Preisentscheidung für ein Tile.
     *
     * @param tileId Tile-ID
     * @param price Preis oder {@code null} bei Discard
     * @param discard {@code true} wenn verworfen
     */
    record PriceDecision(int tileId, Integer price, boolean discard) {}
    /**
     * Bauzug für die BUILD-Phase.
     *
     * @param tileId Tile-ID
     * @param x x-Koordinate
     * @param y y-Koordinate
     * @param rotation Rotation in Grad
     */
    record BuildMove(int tileId, int x, int y, int rotation) {}

    /**
     * Wählt Preise für die DISCARD/PRICE-Phase.
     *
     * @param ctx KI-Kontext
     * @param tileIds Tile-IDs in der Hand
     * @return Liste von Preisentscheidungen
     */
    List<PriceDecision> choosePrices(AiContext ctx, List<Integer> tileIds);
    /**
     * Wählt ein Tile zum Kaufen in der BUY-Phase.
     *
     * @param ctx KI-Kontext
     * @param options Kaufoptionen
     * @return Tile-ID oder {@link Optional#empty()}
     */
    Optional<Integer> chooseBuyTileId(AiContext ctx, List<BuyOption> options);
    /**
     * Wählt einen Bauzug in der BUILD-Phase.
     *
     * @param ctx KI-Kontext
     * @param tileIds Tile-IDs in der Hand
     * @param legalMoves legale Züge
     * @return Bauzug oder {@link Optional#empty()}
     */
    Optional<BuildMove> chooseBuildMove(AiContext ctx, List<Integer> tileIds, List<int[]> legalMoves);
}
