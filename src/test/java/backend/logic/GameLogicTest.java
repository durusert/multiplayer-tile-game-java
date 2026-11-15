package backend.logic;

import backend.model.Game;
import backend.model.Market;
import backend.model.Player;
import backend.model.enums.GamePiece;
import backend.model.enums.RoundPhase;
import backend.model.playeraction.PriceDecision;
import backend.model.tile.Tile;
import backend.model.tile.PlacedTile;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameLogicTest {

    private static class TestSetup {
        final Game game = new Game();
        final GameLogic logic = new GameLogic(game);
        final Player p1 = new Player("Alice", GamePiece.BLUE, LocalDate.of(2000, 1, 1));
        final Player p2 = new Player("Bob", GamePiece.RED, LocalDate.of(1999, 1, 1)); // älter => je nach youngest-Logik ggf. nicht Start
    }

    @Test
    void startGame_setsRunningAndStartsInIncomePhase_untilCollected() {
        TestSetup s = new TestSetup();
        s.logic.startGame(List.of(s.p1, s.p2));

        // startGame now begins in INCOME and waits for explicit CollectIncome.
        assertEquals(RoundPhase.INCOME, s.game.getRoundPhase());
        assertTrue(s.game.getPlayers().contains(s.p1));
        assertTrue(s.game.getPlayers().contains(s.p2));

        // No automatic coin grant anymore.
        assertEquals(0, s.p1.getCoins());
        assertEquals(0, s.p2.getCoins());
    }

    @Test
    void startGame_placesColorSpecificStartingCastleInTerritory() {
        TestSetup s = new TestSetup();
        s.logic.startGame(List.of(s.p1, s.p2));

        // BLUE -> -1, RED -> -3 (siehe tiles-v3.json: Burg-Tiles -1..-5)
        PlacedTile c1 = s.p1.getClanTerritory().getTileAt(0, 0);
        PlacedTile c2 = s.p2.getClanTerritory().getTileAt(0, 0);

        assertNotNull(c1, "Player 1 should have a starting castle at (0,0)");
        assertNotNull(c2, "Player 2 should have a starting castle at (0,0)");

        assertEquals(-1, c1.tile().getTileId());
        assertEquals(-3, c2.tile().getTileId());
    }

    @Test
    void drawPhase_enforcesTurnAndDrawNumberAndMax3_thenPhaseBecomesDiscard() {
        TestSetup s = new TestSetup();
        s.logic.startGame(List.of(s.p1, s.p2));
        // Still in INCOME; tests that depend on DRAW must advance to DRAW.
        s.game.setRoundPhase(RoundPhase.DRAW);
        assertEquals(RoundPhase.DRAW, s.game.getRoundPhase());

        // In the real game, players would have collected income before reaching pricing.
        s.p1.addCoins(20);
        s.p2.addCoins(20);

        Player current = s.game.getCurrentPlayer();
        Player other = (current.equals(s.p1)) ? s.p2 : s.p1;

        // falscher drawNumber am Anfang
        assertThrows(IllegalArgumentException.class, () -> s.logic.handleDrawRequest(current, 1));

        // korrekt 0,1,2
        Tile t0 = s.logic.handleDrawRequest(current, 0);
        assertNotNull(t0);
        assertThrows(IllegalArgumentException.class, () -> s.logic.handleDrawRequest(current, 0)); // erwartet 1

        Tile t1 = s.logic.handleDrawRequest(current, 1);
        assertNotNull(t1);

        Tile t2 = s.logic.handleDrawRequest(current, 2);
        assertNotNull(t2);

        assertEquals(3, current.getDrawnTiles().size());

        // Nach 3 Draws ist current "ready", Zug geht zum anderen
        assertEquals(other, s.game.getCurrentPlayer());

        // anderer Spieler zieht auch 0,1,2 => danach sind alle ready => Phase springt zu DISCARD
        s.logic.handleDrawRequest(other, 0);
        s.logic.handleDrawRequest(other, 1);
        s.logic.handleDrawRequest(other, 2);

        assertEquals(3, other.getDrawnTiles().size());
        assertEquals(RoundPhase.DISCARD, s.game.getRoundPhase());
    }

    @Test
    void priceDecision_requiresDiscardAndExactlyTwoPrices_andMovesToBuyPhase() {
        TestSetup s = new TestSetup();
        s.logic.startGame(List.of(s.p1, s.p2));

        // Skip INCOME for this unit test.
        s.game.setRoundPhase(RoundPhase.DRAW);

        // Seed coins (income is no longer auto-granted).
        s.p1.addCoins(20);
        s.p2.addCoins(20);

        // DRAW für beide vollständig
        draw3ForCurrentThen3ForOther(s);

        assertEquals(RoundPhase.DISCARD, s.game.getRoundPhase());

        // Ungültig: kein discard
        PriceDecision invalid = new PriceDecision(s.p1);
        invalid.setPrice(s.p1.getDrawnTiles().get(0), 1);
        invalid.setPrice(s.p1.getDrawnTiles().get(1), 1);
        assertThrows(IllegalStateException.class, () -> s.logic.handlePriceDecision(invalid));

        // Gültig: discard + genau 2 Preise (für 3 Tiles)
        PriceDecision d1 = makeValidDecision(s.p1, 1, 1);
        PriceDecision d2 = makeValidDecision(s.p2, 1, 1);

        s.logic.handlePriceDecision(d1);
        s.logic.handlePriceDecision(d2);

        // PRICE and REVEAL are transient phases in the final flow. Once all
        // decisions are submitted, offers are revealed and BUY starts.
        assertEquals(RoundPhase.BUY, s.game.getRoundPhase());
    }

    @Test
    void reveal_appliesStoredPriceDecisions_removesDiscard_addsOffers_and_deductsCoins() {
        TestSetup s = new TestSetup();
        s.logic.startGame(List.of(s.p1, s.p2));

        // Skip INCOME for this unit test.
        s.game.setRoundPhase(RoundPhase.DRAW);

        // Seed coins (income is no longer auto-granted).
        s.p1.addCoins(20);
        s.p2.addCoins(20);
        int c1Start = s.p1.getCoins();
        int c2Start = s.p2.getCoins();

        // DRAW für beide vollständig => DISCARD
        draw3ForCurrentThen3ForOther(s);
        assertEquals(RoundPhase.DISCARD, s.game.getRoundPhase());

        // 1) Entscheidungen in DISCARD => Phase PRICE
        PriceDecision d1 = makeValidDecision(s.p1, 2, 1); // Summe 3
        PriceDecision d2 = makeValidDecision(s.p2, 1, 2); // Summe 3
        s.logic.handlePriceDecision(d1);
        s.logic.handlePriceDecision(d2);
        assertEquals(RoundPhase.BUY, s.game.getRoundPhase());

        // Jeder hatte 3 Tiles, 1 discard => 2 verbleiben
        assertEquals(2, s.p1.getDrawnTiles().size());
        assertEquals(2, s.p2.getDrawnTiles().size());

        // Coins wurden in applyStoredPriceDecisions um Summe der gesetzten Preise reduziert
        assertEquals(c1Start - 3, s.p1.getCoins());
        assertEquals(c2Start - 3, s.p2.getCoins());

        // Markt enthält die Angebote (2 pro Spieler)
        Market m = s.game.getMarket();
        assertEquals(2, m.getOffersForPlayer(s.p1).size());
        assertEquals(2, m.getOffersForPlayer(s.p2).size());
        assertNotNull(m.getPrice(m.getOffersForPlayer(s.p1).get(0)));
        assertNotNull(m.getPrice(m.getOffersForPlayer(s.p2).get(0)));
    }

    @Test
    void buyPhase_enforcesTurn_pass_isAllowed_noSelfBuy_and_coinTransfer_isCorrect_thenBuildStarts() {
        TestSetup s = new TestSetup();
        s.logic.startGame(List.of(s.p1, s.p2));

        // Skip INCOME for this unit test.
        s.game.setRoundPhase(RoundPhase.DRAW);

        // Seed coins (income is no longer auto-granted).
        s.p1.addCoins(20);
        s.p2.addCoins(20);

        // Bis BUY vorspulen (Draw -> Discard -> Price -> Reveal -> Buy)
        goToBuyPhaseWithOffers(s);

        assertEquals(RoundPhase.BUY, s.game.getRoundPhase());
        Player buyer = s.game.getCurrentPlayer();
        Player seller = buyer.equals(s.p1) ? s.p2 : s.p1;

        // Eigenkauf verboten
        Tile anyOwn = seller.equals(s.p1) ? s.p2.getDrawnTiles().get(0) : s.p1.getDrawnTiles().get(0);
        // (oben nur damit wir irgendein Tile haben; wirklich "Eigenkauf" testen wir sauber mit seller=buyer)
        assertThrows(IllegalArgumentException.class, () -> s.logic.handleTilePurchase(buyer, buyer, anyOwn));

        // Käufer kauft ein echtes Angebot vom Seller
        Market m = s.game.getMarket();
        Tile offered = m.getOffersForPlayer(seller).get(0);
        int price = m.getPrice(offered);

        int buyerCoinsBefore = buyer.getCoins();
        int sellerCoinsBefore = seller.getCoins();
        int buyerHandBefore = buyer.getDrawnTiles().size();
        int sellerHandBefore = seller.getDrawnTiles().size();

        Integer paid = s.logic.handleTilePurchase(buyer, seller, offered);
        assertEquals(price, paid);

        // buyer -price, seller +2*price (weil seller beim Reveal die price schon "hinterlegt" hat)
        assertEquals(buyerCoinsBefore - price, buyer.getCoins());
        assertEquals(sellerCoinsBefore + 2 * price, seller.getCoins());

        // Tile wechselt die Hand
        assertEquals(buyerHandBefore + 1, buyer.getDrawnTiles().size());
        assertEquals(sellerHandBefore - 1, seller.getDrawnTiles().size());

        // Jetzt ist der andere Spieler dran und passt -> danach (2 Spieler) sollte BUILD starten
        assertEquals(seller, s.game.getCurrentPlayer());
        assertNull(s.logic.handleTilePurchase(seller, null, null));

        assertEquals(RoundPhase.BUILD, s.game.getRoundPhase());
    }

    // ----------------- Helper -----------------

    private static void draw3ForCurrentThen3ForOther(TestSetup s) {
        Player current = s.game.getCurrentPlayer();
        Player other = current.equals(s.p1) ? s.p2 : s.p1;

        s.logic.handleDrawRequest(current, 0);
        s.logic.handleDrawRequest(current, 1);
        s.logic.handleDrawRequest(current, 2);

        // currentPlayer wird automatisch weitergeschaltet
        assertEquals(other, s.game.getCurrentPlayer());

        s.logic.handleDrawRequest(other, 0);
        s.logic.handleDrawRequest(other, 1);
        s.logic.handleDrawRequest(other, 2);

        assertEquals(RoundPhase.DISCARD, s.game.getRoundPhase());
    }

    private static PriceDecision makeValidDecision(Player p, int priceA, int priceB) {
        // p hat 3 Tiles: eins discard, zwei bepreisen
        List<Tile> tiles = p.getDrawnTiles();
        assertTrue(tiles.size() >= 3, "Spieler muss 3 Tiles haben");

        Tile discard = tiles.get(0);
        Tile t1 = tiles.get(1);
        Tile t2 = tiles.get(2);

        PriceDecision d = new PriceDecision(p);
        d.setDiscardedTile(discard);
        d.setPrice(t1, priceA);
        d.setPrice(t2, priceB);
        return d;
    }

    private static void goToBuyPhaseWithOffers(TestSetup s) {
        // DRAW -> DISCARD
        draw3ForCurrentThen3ForOther(s);
        assertEquals(RoundPhase.DISCARD, s.game.getRoundPhase());

        // DISCARD -> PRICE
        PriceDecision d1 = makeValidDecision(s.p1, 1, 1);
        PriceDecision d2 = makeValidDecision(s.p2, 1, 1);
        s.logic.handlePriceDecision(d1);
        s.logic.handlePriceDecision(d2);

        // PRICE and REVEAL auto-advance in the final implementation.
        assertEquals(RoundPhase.BUY, s.game.getRoundPhase());
    }
}
