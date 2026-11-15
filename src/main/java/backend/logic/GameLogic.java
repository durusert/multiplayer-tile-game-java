package backend.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import backend.model.ClanTerritory;
import backend.model.Game;
import backend.model.Market;
import backend.model.tile.Tile;
import backend.model.tile.TileBag;
import backend.model.Player;
import backend.model.ScoringTiles;
import backend.model.SingleScoringTile;
import backend.model.enums.GameStatus;
import backend.model.enums.RoundPhase;
import backend.model.enums.TilePlacementResult;
import backend.model.playeraction.PriceDecision;
import backend.model.playeraction.TilePlacement;

import java.util.*;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Die Klasse {@code GameLogic} steuert den gesamten Ablauf einer Partie Isle of Skye.
 * <p>
 * Sie koordiniert:
 * <ul>
 *     <li>Runden- und Phasenwechsel</li>
 *     <li>Interpretation und Validierung der Spieleraktionen</li>
 *     <li>Kauf- und Verkaufslogik im Markt</li>
 *     <li>Platzierung der Landschaftsplättchen</li>
 *     <li>Punktvergabe über die {@link ScoringLogic}</li>
 * </ul>
 *
 * Der Server ruft diese Methoden auf Basis eingehender Netzwerk-Nachrichten auf.
 */
public class GameLogic {

    private static final Logger log =
            LogManager.getLogger(GameLogic.class);

    private static final int MAX_ROUNDS_2_TO_4 = 6; // bestimmt ob Spiel fertig oder wieder bei Phase 1 starten soll
    private static final int MAX_ROUNDS_5 = 5; // 5 Runden bei 5 Spielern

    private final Game game;
    private final ScoringLogic scoringLogic = new ScoringLogic();
    private final IncomeLogic incomeLogic = new IncomeLogic();
    private final java.util.Set<Player> playersReadyThisPhase = new HashSet<>();

    // Listener, die über Phasenwechsel informiert werden (z.B. GameServer -> PHASE_START Nachricht)
    private final List<Consumer<RoundPhase>> phaseListeners = new ArrayList<>();
    private final List<Consumer<Player>> coinListeners = new ArrayList<>();

    // alle PriceDecisions dieser Runde
    private final Map<Player, PriceDecision> priceDecisions = new HashMap<>();

    private final Map<Player, Integer> drawsThisRound = new HashMap<>();
    private static final int MAX_DRAWS_PER_PLAYER = 3;

    // Listener, die beim Start der Reveal-Phase informiert werden
    private final List<Consumer<Map<Player, PriceDecision>>> revealListeners = new ArrayList<>();

    // Protokoll 1.0: Netzwerk-Callbacks
    private Consumer<Map<Integer, Integer>> scoringUpdateSender;
    private Consumer<List<Integer>> gameOverSender;

    private final List<Consumer<Player>> startPlayerListeners = new ArrayList<>();
    /**
     * Registriert einen Listener für Startspieler-Änderungen.
     *
     * @param listener Listener, der den neuen Startspieler erhält
     */
    public void addStartPlayerListener(Consumer<Player> listener) { if (listener != null) startPlayerListeners.add(listener); }
    /**
     * Benachrichtigt alle Startspieler-Listener.
     */
    private void notifyStartPlayerChanged() {
        Player start = game.getStartPlayer();
        for (Consumer<Player> l : startPlayerListeners) { try { l.accept(start); } catch (Exception e) { e.printStackTrace(); } }
    }

    private final List<Consumer<Player>> currentPlayerListeners = new ArrayList<>();
    /**
     * Registriert einen Listener für Current-Player-Änderungen.
     *
     * @param listener Listener, der den aktuellen Spieler erhält
     */
    public void addCurrentPlayerListener(Consumer<Player> listener) { if (listener != null) currentPlayerListeners.add(listener); }
    /**
     * Benachrichtigt alle Current-Player-Listener.
     */
    private void notifyCurrentPlayerChanged() {
        Player current = game.getCurrentPlayer();
        for (Consumer<Player> l : currentPlayerListeners) { try { l.accept(current); } catch (Exception e) { e.printStackTrace(); } }
    }

    private final List<Consumer<Player>> victoryPointListeners = new ArrayList<>();
    /**
     * Benachrichtigt Listener über aktualisierte Siegpunkte eines Spielers.
     *
     * @param p Spieler mit aktualisierten Punkten
     */
    private void notifyVictoryPointsUpdated(Player p) {
        for (Consumer<Player> l : victoryPointListeners) { try { l.accept(p); } catch (Exception e) { e.printStackTrace(); } }
    }

    private final List<Consumer<List<Player>>> gameFinishedListeners = new ArrayList<>();
    /**
     * Benachrichtigt Listener über Spielende mit einer Snapshot-Spielerliste.
     */
    private void notifyGameFinished() {
        List<Player> snapshot = List.copyOf(game.getPlayers());
        for (Consumer<List<Player>> l : gameFinishedListeners) { try { l.accept(snapshot); } catch (Exception e) { e.printStackTrace(); } }
    }

    private final List<Runnable> scoringListeners = new ArrayList<>();
    /**
     * Registriert einen Listener für Scoring-Ereignisse.
     *
     * @param listener Listener, der beim Scoring ausgelöst wird
     */
    public void addScoringListener(Runnable listener) { if (listener != null) scoringListeners.add(listener); }
    /**
     * Benachrichtigt alle Scoring-Listener.
     */
    private void notifyScoringListeners() {
        for (Runnable r : scoringListeners) { try { r.run(); } catch (Exception e) { e.printStackTrace(); } }
    }

    private final List<BiConsumer<Player, Integer>> incomeListeners = new ArrayList<>();
    /**
     * Registriert einen Listener für Einkommensereignisse.
     *
     * @param l Listener, der Spieler und Einkommen erhält
     */
    public void addIncomeListener(BiConsumer<Player, Integer> l) {
        if (l != null) incomeListeners.add(l);
    }

    /**
     * Setzt den Sender für Scoring-Updates (Netzwerk-Callback).
     *
     * @param sender Sender für Score-Map
     */
    public void setScoringUpdateSender(Consumer<Map<Integer, Integer>> sender) {
        this.scoringUpdateSender = sender;
    }

    /**
     * Setzt den Sender für Game-Over-Benachrichtigungen (Netzwerk-Callback).
     *
     * @param sender Sender für Gewinner-ClientIds
     */
    public void setGameOverSender(Consumer<List<Integer>> sender) {
        this.gameOverSender = sender;
    }

    /**
     * Erstellt eine neue GameLogic-Instanz für ein bestehendes {@link Game}-Objekt.
     *
     * @param game Das Spielmodell, das von dieser Logik gesteuert wird.
     */
    public GameLogic(Game game) {
        this.game = game;
    }

    /**
     * Gibt das zugrunde liegende Spielmodell zurück.
     *
     * @return {@link Game}
     */
    public Game getGame() {
        return game;
    }

    /**
     * Registriert einen Listener für Phasenwechsel.
     *
     * @param listener Listener, der die neue Phase erhält
     */
    public void addPhaseListener(Consumer<RoundPhase> listener) {
        if (listener != null) {
            phaseListeners.add(listener);
        }
    }

    /**
     * Benachrichtigt alle Phasen-Listener.
     */
    private void notifyPhaseChanged() {
        RoundPhase phase = game.getRoundPhase();
        for (Consumer<RoundPhase> listener : phaseListeners) {
            try {
                listener.accept(phase);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Registriert einen Listener für Münzänderungen.
     *
     * @param listener Listener, der den betroffenen Spieler erhält
     */
    public void addCoinListener(Consumer<Player> listener) {
        if (listener != null) coinListeners.add(listener);
    }

    /**
     * Benachrichtigt Listener über geänderte Münzen eines Spielers.
     *
     * @param p Spieler mit aktualisierten Münzen
     */
    private void notifyCoinsUpdated(Player p) {
        for (Consumer<Player> l : coinListeners) {
            try { l.accept(p); } catch (Exception e) { e.printStackTrace(); }
        }
    }

    /**
     * Benachrichtigt Reveal-Listener mit einem Snapshot der Preisentscheidungen.
     */
    private void notifyRevealListeners() {
        // unveränderliche Sicht nach außen geben
        Map<Player, PriceDecision> snapshot = Map.copyOf(priceDecisions);
        for (Consumer<Map<Player, PriceDecision>> listener : revealListeners) {
            listener.accept(snapshot);
        }
    }

    /**
     * Startet das Spiel: Spieler hinzufügen, Spielstatus auf RUNNING,
     * Runde 1 initialisieren, mit INCOME-Phase beginnen.
     *
     * @param players Liste aller teilnehmenden Spieler.
     */
    public void startGame(List<Player> players) {
        players.forEach(game :: addPlayer); // Spieler in Spielerliste (Game) hinzufügen
        game.setStartPlayerIndex(game.youngestPlayerIndex(players));
        notifyStartPlayerChanged();
        game.resetCurrentPlayerToStart();

        setupInitialCastles();

        // ScoringTiles ziehen & speichern
        ScoringTiles scoringTiles = SingleScoringTile.drawRandomScoringTiles();
        game.setScoringTiles(scoringTiles);

        game.setStatus(GameStatus.RUNNING); // Status setzen
        game.advanceRound();// currentRound +1

        enterIncomePhase(); // mit INCOME-Phase beginnen
    }

    /**
     * Verarbeitet die Preisfestlegung eines Spielers in der DRAW_AND_PRICE-Phase.
     * <p>
     * Aufgaben:
     * <ul>
     *     <li>validiert Phase und Spielzugehörigkeit</li>
     *     <li>entfernt das ausgewählte "discarded" Tile und legt es zurück in den Beutel</li>
     *     <li>trägt die gesetzten Preise in den Markt ein</li>
     *     <li>markiert den Spieler als "bereit" für diese Phase</li>
     * </ul>
     *
     * @param decision Die Aktion des Spielers (Prices + Tile zum Entsorgen).
     * @throws IllegalStateException falsche Phase
     * @throws IllegalArgumentException ungültige Preise oder Tiles
     */
    public void handlePriceDecision(PriceDecision decision) {
        if (game.getRoundPhase() != RoundPhase.PRICE && game.getRoundPhase() != RoundPhase.DISCARD) {
            throw new IllegalStateException("Falsche Phase für Preisfestlegung: " + game.getRoundPhase());
        }

        Player player = decision.getPlayer();
        if (!game.getPlayers().contains(player)) {
            throw new IllegalStateException("Player gehört nicht zu diesem Spiel: " + player.getName());
        }
        if (playersReadyThisPhase.contains(player)) {
            throw new IllegalStateException("Dieser Spieler hat die Preisphase bereits abgeschlossen.");
        }

        List<Tile> drawnTiles = player.getDrawnTiles();
        if (drawnTiles == null || drawnTiles.isEmpty()) {
            throw new IllegalStateException("Keine gezogenen Tiles zum Bepreisen vorhanden.");
        }

        Tile discarded = decision.getDiscardedTile();
        if (discarded == null || !drawnTiles.contains(discarded)) {
            throw new IllegalStateException("Karte zum Discarden nicht gefunden.");
        }

        int expectedPrices = drawnTiles.size() - 1;
        if (expectedPrices <= 0) {
            throw new IllegalStateException("Zu wenige Tiles für die Preisphase.");
        }
        if (decision.getPrices().size() != expectedPrices) {
            throw new IllegalArgumentException("Es müssen genau " + expectedPrices + " Preise gesetzt werden.");
        }

        int coinsAvailable = player.getCoins();
        Set<Tile> seenTiles = new HashSet<>();
        for (Map.Entry<Tile, Integer> entry : decision.getPrices().entrySet()) {
            Tile tile = entry.getKey();
            Integer price = entry.getValue();

            if (tile == null) {
                throw new IllegalArgumentException("Tile-Eintrag fehlt.");
            }
            if (!drawnTiles.contains(tile)) {
                throw new IllegalStateException("Tile nicht gefunden.");
            }
            if (tile.equals(discarded)) {
                throw new IllegalArgumentException("Discarded Tile darf keinen Preis erhalten.");
            }
            if (!seenTiles.add(tile)) {
                throw new IllegalArgumentException("Tile wurde mehrfach bepreist.");
            }
            if (price == null || price < 1) {
                throw new IllegalArgumentException("Ungültiger Preis für Tile " + tile.getTileId());
            }
            coinsAvailable -= price;
            if (coinsAvailable < 0) {
                throw new IllegalStateException("Nicht genug Gold, um alle Preise zu hinterlegen.");
            }
        }

        PriceDecision snapshot = new PriceDecision(player);
        snapshot.setDiscardedTile(discarded);
        for (Map.Entry<Tile, Integer> entry : decision.getPrices().entrySet()) {
            snapshot.setPrice(entry.getKey(), entry.getValue());
        }
        priceDecisions.put(player, snapshot);

        markPlayerReadyForCurrentPhase(player);
    }

    /**
     * Verarbeitet einen Kaufversuch während der BUY-Phase.
     * <p>
     * Aufgaben:
     * <ul>
     *     <li>validiert Käufer, Verkäufer, Phase, verfügbares Gold</li>
     *     <li>überträgt Gold zwischen Käufer und Verkäufer</li>
     *     <li>überträgt Tile vom Verkäufer zum Käufer</li>
     *     <li>entfernt Angebot aus dem Markt</li>
     *     <li>markiert Käufer als "bereit", falls er nicht kaufen will oder fertig ist</li>
     * </ul>
     *
     * @param buyer  Spieler, der ein Tile kaufen möchte
     * @param seller Spieler, der dieses Tile anbietet
     * @param tile   Das zu kaufende Tile (null, falls der Spieler passt)
     * @return gezahlter Preis oder {@code null}, falls der Spieler passt
     */
    public Integer handleTilePurchase(Player buyer, Player seller, Tile tile) {
        if (game.getRoundPhase() != RoundPhase.BUY) {
            throw new IllegalStateException("Falsche Phase für Kauf: " + game.getRoundPhase());
        }
        if (game.getRoundPhase() != RoundPhase.BUY) throw new IllegalStateException("Wrong Phase");
        if (!game.getPlayers().contains(buyer)) throw new IllegalArgumentException("Unknown buyer");
        if (!buyer.equals(game.getCurrentPlayer())) throw new IllegalArgumentException("Not your turn!");
        if (playersReadyThisPhase.contains(buyer)) throw new IllegalStateException("Already acted");

        if (tile == null || seller == null) {
            log.info("Player {} PASSED.", buyer.getName());
            advanceBuyTurn(buyer);
            return null;
        }

        if (!game.getPlayers().contains(seller)) throw new IllegalArgumentException("Seller unbekannt.");
        if (buyer.equals(seller)) throw new IllegalArgumentException("Eigenkauf verboten.");

        Market market = game.getMarket();
        List<Tile> offers = market.getOffersForPlayer(seller);
        if (offers == null || !offers.contains(tile)) throw new IllegalStateException("Tile nicht im Angebot.");

        Integer price = market.getPrice(tile);
        if (price == null) throw new IllegalArgumentException("Kein Preis.");
        if (buyer.getCoins() < price) throw new IllegalStateException("Nicht genug Gold.");

        buyer.addCoins(-price);
        notifyCoinsUpdated(buyer);
        seller.addCoins(price * 2);
        notifyCoinsUpdated(seller);

        seller.getDrawnTiles().remove(tile);
        buyer.getDrawnTiles().add(tile);
        market.removeOffer(seller, tile);

        log.info("Player {} bought tile {}", buyer.getName(), tile.getTileId());

        advanceBuyTurn(buyer);
        return price;
    }

    /**
     * Wechselt den Kaufzug weiter oder beendet die BUY-Phase.
     *
     * @param buyer Käufer, der gerade gehandelt hat
     */
    private void advanceBuyTurn(Player buyer) {
        log.info("advanceBuyTurn started for: {})", buyer.getName());

        playersReadyThisPhase.add(buyer);

        if (playersReadyThisPhase.size() >= game.getPlayers().size()) {
            log.error("All players finished BUY Phase. Proceeding to BUILD Phase.");
            cleanupRemainingOffersAfterBuy();
            playersReadyThisPhase.clear();
            nextPhase();
            return;
        }

        Player originalPlayer = game.getCurrentPlayer();
        game.nextPlayer();

        int safetyCount = 0;
        while (playersReadyThisPhase.contains(game.getCurrentPlayer()) && safetyCount < game.getPlayers().size()) {
            game.nextPlayer();
            safetyCount++;
        }

        log.info("New Current Player determined: {}", game.getCurrentPlayer().getName());
        notifyCurrentPlayerChanged();
    }

    /**
     * Entfernt nach der BUY-Phase alle verbleibenden Angebote aus dem Markt.
     */
    private void cleanupRemainingOffersAfterBuy() {
        Market market = game.getMarket();
        for (Map.Entry<Player, List<Tile>> entry : market.getOffersByPlayer().entrySet()) {
            Player seller = entry.getKey();
            for (Tile remaining : entry.getValue()) {
                market.removeOffer(seller, remaining);
            }
        }
        game.resetCurrentPlayerToStart(); // Startspieler für nächste Phase
    }


    /**
     * Verarbeitet die Platzierung eines Tiles während der BUILD-Phase.
     *
     * @param placement Platzierungsaktion
     * @return Ergebnis der Platzierung
     */
    public TilePlacementResult handleTilePlacement(TilePlacement placement) {

        if (game.getRoundPhase() != RoundPhase.BUILD) {
            throw new IllegalStateException("Falsche Phase für Bauen.");
        }

        Player player = placement.player();
        Tile tile = placement.tile();

        if (!game.getPlayers().contains(player)) {
            throw new IllegalArgumentException("Player nicht gefunden.");
        }

        if (!player.getDrawnTiles().contains(tile)) {
            throw new IllegalArgumentException("Player besitzt dieses Tile nicht.");
        }

        int x = placement.x();
        int y = placement.y();
        int rotationDegrees = placement.rotationDegrees();

        ClanTerritory territory = player.getClanTerritory();

        ensureCastlePlaced(territory);

        if (!territory.hasAnyLegalPlacement(tile)) {
            player.getDrawnTiles().remove(tile);
            game.getTileBag().addTile(tile);
            game.getTileBag().shuffle();
            if (player.getDrawnTiles().isEmpty()) {
                markPlayerReadyForCurrentPhase(player);
            }
            return TilePlacementResult.RETURNED;
        }

        boolean placed = territory.placeTile(tile, x, y, rotationDegrees);

        if (placed) {
            player.getDrawnTiles().remove(tile);
        } else {
           // ungültige Position, legale vorhanden -> Spieler darf neu wählen
           return TilePlacementResult.ILLEGAL;
        }

        // Player fertig mit Bauen, wenn keine Tiles mehr in der Hand
        if (player.getDrawnTiles().isEmpty()) {
            markPlayerReadyForCurrentPhase(player);
        }

        return TilePlacementResult.PLACED;
    }

    /**
     * Übergangsmethode, die anhand der aktuellen Phase nächste Phase einleitet.
     *
     * Wird aufgerufen, sobald alle Spieler die aktuelle Phase abgeschlossen haben.
     *
     * @throws IllegalStateException bei unbekannter Phase
     */
    public void nextPhase() {
        RoundPhase phase = game.getRoundPhase();

        switch (phase) {
            case NONE -> enterIncomePhase();
            case INCOME -> enterDrawPhase();
            case DRAW -> enterDiscardPhase();
            case DISCARD -> enterPricePhase();
            case PRICE -> enterRevealPhase();
            case REVEAL -> enterBuyPhase();
            case BUY -> enterBuildPhase();
            case BUILD -> enterScoringPhase();
            case SCORING -> enterRoundEndPhase();
            case ROUND_END -> finishRound();
            default -> throw new IllegalStateException("Unbekannte Phase: " + phase);
        }
    }

    /**
     * Markiert Spieler als "fertig" für aktuelle Phase.
     * Sobald alle Spieler bereit sind: Liste zurücksetzen, nächste Phase starten.</li>
     *
     * @param player Spieler, der seine Aktion abgeschlossen hat.
     */
    private void markPlayerReadyForCurrentPhase(Player player) {
        playersReadyThisPhase.add(player);

        if (playersReadyThisPhase.size() == game.getPlayers().size()) {
            RoundPhase finishedPhase = game.getRoundPhase();
            if (finishedPhase == RoundPhase.BUILD) {
                validateAllTilesPlaced();
            }
            log.info("All Players are ready in phase: {}", finishedPhase);

            playersReadyThisPhase.clear();
            nextPhase();
        }
    }

    /**
     * Markiert alle Spieler als bereit für die aktuelle Phase.
     */
    private void markAllPlayersReady() {
        for (Player player : game.getPlayers()) {
            markPlayerReadyForCurrentPhase(player);
        }
    }


    /**
     * Startet INCOME-Phase.
     * <ul>
     *     <li>setzt Phase im Game-Modell</li>
     *     <li>gibt jedem Spieler das Einkommen</li>
     *     <li>wechselt zur nächsten Phase</li>
     * </ul>
     */
    public void enterIncomePhase() {
        game.setRoundPhase(RoundPhase.INCOME);
        playersReadyThisPhase.clear();
        notifyPhaseChanged();
        // Income is now collected explicitly via the network message CollectIncome.
        // We stay in INCOME until all players have collected.
    }

    /**
     * Startet DRAW-Phase.
     */
    public void enterDrawPhase() {
        game.setRoundPhase(RoundPhase.DRAW);
        playersReadyThisPhase.clear();
        drawsThisRound.clear();
        game.resetCurrentPlayerToStart();
        notifyPhaseChanged();
        notifyCurrentPlayerChanged();
    }

    /**
     * Startet DISCARD-Phase.
     */
    public void enterDiscardPhase() {
        game.setRoundPhase(RoundPhase.DISCARD);
        playersReadyThisPhase.clear();
        priceDecisions.clear();
        notifyPhaseChanged();
    }

    /**
     * Startet PRICE-Phase: Preise setzen/Abwurf wählen.
     */
    public void enterPricePhase() {
        game.setRoundPhase(RoundPhase.PRICE);
        playersReadyThisPhase.clear();
        notifyPhaseChanged();
        // If all players already submitted their price decisions during DISCARD,
        // we should automatically advance to the REVEAL phase to avoid
        // requiring players to submit prices twice (once in DISCARD and again in PRICE).
        if (priceDecisions.size() == game.getPlayers().size()) {
            // All prices already present -> immediately proceed to next phase (REVEAL)
            nextPhase();
        }
    }

    /**
     * Startet die REVEAL-Phase und wendet die gesetzten Preise an.
     */
    private void enterRevealPhase() {
        game.setRoundPhase(RoundPhase.REVEAL);
        playersReadyThisPhase.clear();
        applyStoredPriceDecisions(); // Preise anwenden & Discard ablegen
        notifyRevealListeners();     // Snapshot nach außen (Server kann RevealTilePrices senden)
        notifyPhaseChanged();

        game.getTileBag().shuffle();

        // sofort in BUY, kein "start buying Phase" Button mehr
        nextPhase();
    }

    /**
     * Gibt einen unveränderlichen Snapshot der Preisentscheidungen zurück.
     *
     * @return Map Spieler -> {@link PriceDecision}
     */
    public Map<Player, PriceDecision> getPriceDecisionsSnapshot() {
        return Map.copyOf(priceDecisions);
    }

    /**
     * Bestätigt die Reveal-Phase für einen Spieler.
     *
     * @param player Spieler
     */
    public void confirmRevealPhase(Player player) {
        if (game.getRoundPhase() != RoundPhase.REVEAL) {
            throw new IllegalStateException("Falsche Phase: " + game.getRoundPhase());
        }
        if (!game.getPlayers().contains(player)) {
            throw new IllegalArgumentException("Player nicht gefunden.");
        }
        if (playersReadyThisPhase.contains(player)) {
            throw new IllegalStateException("Player hat die Reveal-Phase bereits bestätigt.");
        }

        markPlayerReadyForCurrentPhase(player);
    }

    /**
     * Startet BUY-Phase.
     * <p>
     * Die eigentliche Logik erfolgt durch {@link #handleTilePurchase(Player, Player, Tile)}.
     */
    public void enterBuyPhase() {
        game.setRoundPhase(RoundPhase.BUY);
        playersReadyThisPhase.clear();
        notifyPhaseChanged();
        game.resetCurrentPlayerToStart();
        notifyCurrentPlayerChanged();
    }

    /**
     * Startet BUILD-Phase.
     * <p>
     * Die Logik erfolgt durch {@link #handleTilePlacement(TilePlacement)}.
     * Alle Spieler bauen gleichzeitig (kein CurrentPlayer nötig).
     */
    private void enterBuildPhase() {
        game.setRoundPhase(RoundPhase.BUILD);
        playersReadyThisPhase.clear();
        setupInitialCastles();
        notifyPhaseChanged();
        
        // Alle Spieler informieren dass sie bauen können
        game.resetCurrentPlayerToStart();
        notifyCurrentPlayerChanged();
    }

    /**
     * Startet SCORING-Phase:
     * <ul>
     *     <li>ruft Rundenscoring der {@link ScoringLogic} auf</li>
     *     <li>leitet automatisch zur nächsten Phase über</li>
     * </ul>
     */
    private int lastScoredRound = -1;

    /**
     * Startet die SCORING-Phase und führt das Rundenscoring aus.
     */
    public void enterScoringPhase() {
        if (game.getCurrentRound() == lastScoredRound) {
            log.warn("Scoring already applied for round {} -> Ignore", lastScoredRound);
            return;
        }
        lastScoredRound = game.getCurrentRound();

        game.setRoundPhase(RoundPhase.SCORING);
        playersReadyThisPhase.clear();
        notifyPhaseChanged();

        notifyScoringListeners();

        for (Player player : game.getPlayers()) {
            notifyVictoryPointsUpdated(player);
        }

        markAllPlayersReady();

        log.warn("Enter SCORING: currentRound= {}", game.getCurrentRound());

        log.info("VP before: ");
        for (Player p : game.getPlayers()) {
            log.info("{} vp= {}", p.getName(), p.getVictoryPoints());
        }
        scoringLogic.scoreRound(game);

        log.info("VP after: ");
        for (Player p : game.getPlayers()) {
            log.info(" {} vp= {}", p.getName(), p.getVictoryPoints());
        }

        Map<Integer,Integer> map = buildScoreMap();
        log.info("Sending score map= {}", map);

        if (scoringUpdateSender != null) {
            scoringUpdateSender.accept(map);
        }

    }

    /**
     * Startet die ROUND_END-Phase.
     */
    private void enterRoundEndPhase() {
        game.setRoundPhase(RoundPhase.ROUND_END);
        playersReadyThisPhase.clear();
        notifyPhaseChanged();
    }

    /**
     * Wendet gespeicherte Preisentscheidungen an und befüllt den Markt.
     */
    private void applyStoredPriceDecisions() {
        if (priceDecisions.size() != game.getPlayers().size()) {
            throw new IllegalStateException("Nicht alle Spieler haben Preise gesetzt.");
        }

        Market market = game.getMarket();
        TileBag bag = game.getTileBag();

        for (Map.Entry<Player, PriceDecision> entry : priceDecisions.entrySet()) {
            Player player = entry.getKey();
            PriceDecision decision = entry.getValue();

            Tile discarded = decision.getDiscardedTile();
            List<Tile> drawnTiles = player.getDrawnTiles();
            if (discarded == null || !drawnTiles.contains(discarded)) {
                throw new IllegalStateException("Discarded Tile fehlt bei Spieler " + player.getName());
            }
            drawnTiles.remove(discarded);
            bag.addTile(discarded);

            for (Map.Entry<Tile, Integer> priceEntry : decision.getPrices().entrySet()) {
                Tile tile = priceEntry.getKey();
                Integer price = priceEntry.getValue();
                if (!drawnTiles.contains(tile)) {
                    throw new IllegalStateException("Tile nicht gefunden bei Spieler " + player.getName());
                }
                market.addOffer(player, tile, price);
                player.addCoins(-price);
                notifyCoinsUpdated(player);
            }
        }
    }

    /**
     * Prüft, ob alle Spieler in der Build-Phase keine Tiles mehr halten.
     */
    private void validateAllTilesPlaced() {
        for (Player p : game.getPlayers()) {
            if (!p.getDrawnTiles().isEmpty()) {
                throw new IllegalStateException("Player " + p.getName() + " hat noch Tiles in der Build-Phase.");
            }
        }
    }

    /**
     * Ermittelt die Gewinner nach Siegpunkt- und Münzgleichstand.
     *
     * @return Liste der Gewinner-ClientIds
     */
    private List<Integer> determineWinnerClientIds() {
        List<Player> players = game.getPlayers();
        int bestVp = players.stream().mapToInt(Player::getVictoryPoints).max().orElse(0);

        List<Player> topVp = players.stream()
                .filter(p -> p.getVictoryPoints() == bestVp)
                .toList();

        if (topVp.size() == 1) {
            return List.of(topVp.get(0).getClientId());
        }

        int bestCoins = topVp.stream().mapToInt(Player::getCoins).max().orElse(0);
        List<Integer> winners = new ArrayList<>();
        for (Player p : topVp) {
            if (p.getCoins() == bestCoins) winners.add(p.getClientId());
        }
        return winners;
    }

    /**
     * Baut die Score-Map für Netzwerk-Updates.
     *
     * @return Map ClientId -> VictoryPoints
     */
    private Map<Integer, Integer> buildScoreMap() {
        Map<Integer, Integer> scores = new LinkedHashMap<>();

        for (Player p : game.getPlayers()) {
            scores.put(
                    p.getClientId(),
                    p.getVictoryPoints()
            );
        }

        return scores;
    }

    /**
     * Beendet die aktuelle Runde oder das Spiel, abhängig von der Rundenzahl.
     */
    public void finishRound() {
        game.advanceRound();

        // Max-Runden abhängig von Spielerzahl (5 Spieler => 5 Runden, sonst 6)
        int maxRounds = game.getPlayers().size() == 5 ? MAX_ROUNDS_5 : MAX_ROUNDS_2_TO_4;

        if (game.getCurrentRound() > maxRounds) {

            scoringLogic.scoreFinal(game);

            for (Player player : game.getPlayers()) {
                notifyVictoryPointsUpdated(player);
            }

            // Spiel beenden
            game.setStatus(GameStatus.FINISHED);
            game.setRoundPhase(RoundPhase.NONE);

            //ScoringUpdate
            if (scoringUpdateSender != null) {
                scoringUpdateSender.accept(buildScoreMap());
            }

            //GameOver
            if (gameOverSender != null) {
                gameOverSender.accept(determineWinnerClientIds());
            }
            notifyGameFinished();
            notifyPhaseChanged();

        } else {
            game.rotateStartPlayer();
            game.resetCurrentPlayerToStart();
            notifyStartPlayerChanged();

            // Markt- / Spielerzustände zwischen Runden bereinigen
            game.getMarket().clear();       // Markt leeren
            priceDecisions.clear();         // gesetzte Preise verwerfen
            playersReadyThisPhase.clear();  // Ready-Flags zurücksetzen
            for (Player p : game.getPlayers()) {
                p.getDrawnTiles().clear();  // Hand am Rundenende leeren
            }

            enterIncomePhase(); // neue Runde starten
        }
    }

    /**
     * Stellt sicher, dass alle Spieler eine Startburg besitzen.
     */
    private void setupInitialCastles() {
        for (Player p : game.getPlayers()) {
            if (!p.getClanTerritory().getTiles().isEmpty()) {
                continue; // falls schon gesetzt
            }
            Tile castle = drawStartingCastleFor(p);
            p.getClanTerritory().placeInitialCastle(castle);
        }
    }

    /**
     * Weist jedem Spieler beim Spielstart eine deterministische Startburg passend zur Spielerfarbe zu.
     * <p>
     * In {@code tiles-v3.json} sind die fünf Burg-Tiles als IDs -1..-5 modelliert.
     * Damit die Burgen später nicht erneut gezogen werden können, werden sie aus dem {@link TileBag}
     * entfernt.
     */
    private Tile drawStartingCastleFor(Player player) {
        if (player == null || player.getGamePiece() == null) {
            return game.getTileBag().drawCastleTile();
        }

        // feste Zuordnung Farbe -> BurgTileId (IDs -1..-5)
        final int castleId;
        switch (player.getGamePiece()) {
            case BLUE -> castleId = -1;
            case GREEN -> castleId = -2;
            case RED -> castleId = -3;
            case YELLOW -> castleId = -4;
            case WHITE -> castleId = -5;
            default -> {
                return game.getTileBag().drawCastleTile();
            }
        }

        try {
            return game.getTileBag().takeTileById(castleId);
        } catch (Exception e) {
            // Robustheits-Fallback: falls TileId fehlt, lieber irgendeine Burg als gar keine.
            return game.getTileBag().drawCastleTile();
        }
    }

    /**
     * Stellt sicher, dass die Burg im Territorium platziert ist.
     *
     * @param territory Territorium
     */
    private void ensureCastlePlaced(ClanTerritory territory) {
        if (!territory.getTiles().isEmpty()) {
            return;
        }
        Player owner = territory.getOwner();
        Tile castle = drawStartingCastleFor(owner);
        territory.placeInitialCastle(castle);
    }

    /**
     * Verarbeitet eine Ziehanfrage in der DRAW-Phase.
     *
     * @param player Spieler
     * @param drawNumber erwartete Ziehnummer (0-basiert)
     * @return gezogenes {@link Tile}
     */
    public Tile handleDrawRequest(Player player, int drawNumber) {
        if (game.getRoundPhase() != RoundPhase.DRAW) {
            throw new IllegalStateException("Nicht in der Draw-Phase.");
        }
        if (!Objects.equals(game.getCurrentPlayer(), player)) {
            throw new IllegalArgumentException("Nicht dein Zug.");
        }

        int already = drawsThisRound.getOrDefault(player, 0);
        if (already >= MAX_DRAWS_PER_PLAYER) {
            throw new IllegalStateException("Maximal 3 Tiles bereits gezogen.");
        }
        if (drawNumber != already) {
            throw new IllegalArgumentException("Falscher drawNumber, erwartet: " + already);
        }

        List<Tile> one = game.getTileBag().drawTiles(1);
        if (one.isEmpty()) throw new IllegalStateException("Keine Plättchen mehr im Beutel.");
        Tile drawn = one.get(0);

        player.addDrawnTile(drawn);
        drawsThisRound.put(player, already + 1);

        boolean finished = already + 1 >= MAX_DRAWS_PER_PLAYER;

        // Wenn Spieler fertig ist -> ready markieren
        if (finished) {
            markPlayerReadyForCurrentPhase(player);
        }

        // Wenn alle Spieler fertig -> nächste Phase
        if (playersReadyThisPhase.size() >= game.getPlayers().size()) {
            enterDiscardPhase();          // oder nextPhase()
            return drawn;
        }

        // Nur nach dem letzten Draw zur nächsten Person wechseln
        if (finished) {
            do {
                game.nextPlayer();
            } while (playersReadyThisPhase.contains(game.getCurrentPlayer()));
            notifyCurrentPlayerChanged();
        }

        return drawn;
    }

    /**
     * Bereinigt Spielzustände, wenn ein Spieler die Partie verlässt.
     *
     * @param player Spieler, der das Spiel verlässt
     */
    public void handlePlayerLeave(Player player) {
        if (player == null) return;

        Player activePlayerBeforeLeave = game.getCurrentPlayer();
        Player startPlayerBeforeLeave = game.getStartPlayer();
        boolean wasCurrentPlayer = player.equals(activePlayerBeforeLeave);

        log.info("Cleaning up logic for left player: {}", player.getName());

        game.getPlayers().remove(player);
        playersReadyThisPhase.remove(player);
        priceDecisions.remove(player);
        drawsThisRound.remove(player);

        if (!game.getPlayers().isEmpty()) {

            if (game.getPlayers().contains(startPlayerBeforeLeave)) {
                game.setStartPlayerIndex(game.getPlayers().indexOf(startPlayerBeforeLeave));
            } else {
                game.setStartPlayerIndex(0);
            }

            if (!wasCurrentPlayer && game.getPlayers().contains(activePlayerBeforeLeave)) {
                log.info("Re-aligning turn pointer to match correct player: {}", activePlayerBeforeLeave.getName());

                game.resetCurrentPlayerToStart();

                while (!game.getCurrentPlayer().equals(activePlayerBeforeLeave)) {
                    game.nextPlayer();
                }
            }
        }

        if (game.getRoundPhase() == RoundPhase.BUY) {
            game.getMarket().removeAllOffersFrom(player);
            if (wasCurrentPlayer || game.getPlayers().isEmpty()) {
                log.info("Current player left during BUY phase. Adjusting turn.");

                if (!game.getPlayers().isEmpty()) {
                    int safety = 0;
                    while (playersReadyThisPhase.contains(game.getCurrentPlayer()) && safety < game.getPlayers().size()) {
                        game.nextPlayer();
                        safety++;
                    }
                }
                notifyCurrentPlayerChanged();
            }
        }
        if (!game.getPlayers().isEmpty() &&
                playersReadyThisPhase.size() >= game.getPlayers().size()) {
            log.info("Remaining players are all ready. Auto-advancing phase.");
            nextPhase();
        }
    }
}
