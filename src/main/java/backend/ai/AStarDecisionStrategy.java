package backend.ai;

import java.util.*;

/**
 * Erzeugt eine neue A*-basierte Entscheidungsstrategie mit Standardwerten.
 *
 * <p>Voreinstellungen:</p>
 * <ul>
 *   <li>Lookahead-Tiefe: 3 Tiles</li>
 *   <li>Maximal 4000 expandierte A*-Knoten</li>
 * </ul>
 *
 * <p>Die Strategie trifft Entscheidungen für:</p>
 * <ul>
 *   <li>Pricing-Phase (zufällig)</li>
 *   <li>Buying-Phase (Kosten-Nutzen-Abwägung)</li>
 *   <li>Building-Phase (A*-Suche mit Lookahead)</li>
 * </ul>
 */
public final class AStarDecisionStrategy implements AiDecisionStrategy {


    private final int lookaheadDepth; //wie viele zukünftige Tiles A* im Build vorausplant
    private final int maxExpansions; //max Anazhal an A* Knoten

    /**
     * Standard-Konstruktor:
     * - Lookahead über 3 Tiles
     * - Maximal 4000 expandierte Suchknoten
     */
    public AStarDecisionStrategy() {
    this(3, 4000); //bis zu 3 Tiles und max. 4000 Knoten (Suchraumgröße)
    }

    /**
     * Konfigurierbarer Konstruktor
     *
     * @param lookaheadDepth  maximale Anzahl geplanter Tiles
     * @param maxExpansions   maximale Anzahl expandierter A*-Knoten
     */
    public AStarDecisionStrategy(int lookaheadDepth, int maxExpansions) {
        this.lookaheadDepth = Math.max(1, lookaheadDepth);
        this.maxExpansions = Math.max(200, maxExpansions);
    }

    /**
     * Trifft Preisentscheidungen für die Pricing-Phase.
     *
     * <p>Diese Strategie setzt Preise bewusst zufällig, da der Fokus
     * auf der Buying- und Building-Phase liegt.</p>
     *
     * @param ctx
     *        Aktueller KI-Kontext (Zustand, Zufallsquelle etc.)
     *
     * @param tileIds
     *        Liste der Tile-IDs, für die Preise festgelegt werden sollen
     *
     * @return
     *         Liste von {@link PriceDecision}-Objekten mit zufällig
     *         gewählten Preisen
     */
    @Override
    public List<PriceDecision> choosePrices(AiContext ctx, List<Integer> tileIds) {
        return new RandomDecisionStrategy(ctx.rng()).choosePrices(ctx, tileIds); //Random Wahl, da Fokus auf den anderen Phasen
    }


    /**
     * Entscheidet, ob und welches Tile in der Buying-Phase gekauft wird.
     *
     * <p>Alle bezahlbaren Kaufoptionen werden über eine Kosten-Nutzen-Funktion
     * bewertet. Ein Tile wird nur gekauft, wenn sein berechneter Kostenwert
     * negativ ist und der Kauf damit besser ist als zu passen.</p>
     *
     * <p>Die Bewertung kombiniert:</p>
     * <ul>
     *   <li>den Preis des Tiles (gewichtet)</li>
     *   <li>eine strategische Nutzenabschätzung des Tiles</li>
     * </ul>
     *
     * @param ctx
     *        Aktueller KI-Kontext inklusive Coins und Bewertungsfunktionen
     *
     * @param options
     *        Liste aller verfügbaren Kaufoptionen
     *
     * @return
     *         {@link Optional} mit der Tile-ID des zu kaufenden Tiles,
     *         oder {@link Optional#empty()}, wenn kein Kauf sinnvoll ist
     */
    @Override
    public Optional<Integer> chooseBuyTileId(AiContext ctx, List<BuyOption> options) {
        if (options == null || options.isEmpty()) return Optional.empty(); //wenn keine Kaufoption existiert

        // Optionen bei denen Preis nicht null und Preis <= Coins -> nur realistisch kaufbare Tiles werden betrachtet
        List<BuyOption> affordable = options.stream()
                .filter(o -> o.price() != null && o.price() <= ctx.coins())
                .toList();

        if (affordable.isEmpty()) return Optional.empty(); //Abbruch wenn nichts bezahlbar ist

        final double priceWeight = 0.65; //KI ist eher bereit für guten Nutzen mehr zu zahlen

        enum ActType { PASS, BUY }
        record Action(ActType type, int tileId) {}

        // A*-Node: jeder Node ist bereits ein Ziel (eine Entscheidung)
        final class Node {
            final Action action;
            final double cost;   // f = g, keine Heuristik nötig -> keine Pfadsuche

            Node(Action action, double cost) { //speichert Aktion und deren Kosten
                this.action = action;
                this.cost = cost;
            }
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost)); //enthält alle Entscheidungens-Kandidaten

        // PASS-Kandidat
        open.add(new Node(new Action(ActType.PASS, -1), 0.0)); //Kosten Pass=0 -> Kaufen lohtn sich nur, wess es besser ist als nichts zu tun

        // BUY-Kandidaten
        for (BuyOption o : affordable) { //iteriert über alle bezahlbaren Tiles
            double value = ctx.scoring().tileValueEstimate(o.tileId()); //schätzt strategischen Nutzen des Tiles
            double cost = (priceWeight * o.price()) - value; // cost<0-> Tile ist besser als Pass, cost>0-> Tile ist schlechter als Pass
            open.add(new Node(new Action(ActType.BUY, o.tileId()), cost));
        }

        // A*: bestes Ziel ist der erste aus der Queue
        Node best = open.poll();
        if (best == null) return Optional.empty(); //Sicherheitscheck

        // BUY nur wenn sinnvoll (Kosten < 0)
        if (best.action.type == ActType.BUY && best.cost < 0.0) {
            return Optional.of(best.action.tileId);
        }
        return Optional.empty();
    }

    /**
     * Trifft eine Bauentscheidung in der Building-Phase.
     *
     * <p>Die Methode plant mithilfe eines A*-Algorithmus bis zu
     * {@code lookaheadDepth} Tiles im Voraus und wählt den ersten Zug
     * des besten gefundenen Bauplans.</p>
     *
     * <p>Wichtig:</p>
     * <ul>
     *   <li>Bei vorhandenen legalen Zügen wird niemals {@code Optional.empty()}
     *       zurückgegeben, da sonst die Engine hängen bleibt.</li>
     *   <li>Falls die A*-Suche keinen gültigen Plan findet, wird auf einen
     *       heuristischen Fallback zurückgegriffen.</li>
     * </ul>
     *
     * @param ctx
     *        Aktueller KI-Kontext inklusive Spielzustand
     *
     * @param tileIds
     *        Liste der noch zu platzierenden Tile-IDs
     *
     *  * @param legalMoves
     *  *        Liste legaler Platzierungen für das aktuelle Tile
     *  *        (Format: {@code [x, y, rotation]})
     *  *
     *  * @return
     *  *         {@link Optional} mit dem gewählten {@link BuildMove}
     *  */
    @Override
    public Optional<BuildMove> chooseBuildMove(AiContext ctx,
                                               List<Integer> tileIds,
                                               List<int[]> legalMoves) {
        // Wenn es keine Tiles gibt, kann auch nichts gebaut werden
        if (tileIds == null || tileIds.isEmpty()) return Optional.empty();

        // Build darf nicht hängen -> nie Optional.empty() bei vorhandenen Moves
        if (legalMoves == null || legalMoves.isEmpty()) {
            // wenn die Engine wirklich keine Moves liefert, können wir nichts tun
            int tileId = tileIds.get(0);
            return Optional.of(new BuildMove(tileId, 0, 0, 0));
        }
        // Wir schauen maximal so viele Züge voraus wie Tiles vorhanden sind
        final int depthLimit = Math.min(lookaheadDepth, tileIds.size());

        // Open-List für die A*-Suche (nach f = g + h sortiert)
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Map<String, Double> bestG = new HashMap<>();     // Merkt sich den besten bekannten g-Wert pro Zustand


        // Startzustand: noch kein Tile gesetzt
        SearchNode start = new SearchNode(
                0,
                new HashSet<>(),
                0.0,
                heuristic(ctx, tileIds.subList(0, depthLimit), 0),
                null,
                null
        );

        open.add(start);
        bestG.put(start.stateKey(), start.gCost);

        SearchNode goal = null;
        int expansions = 0;

        // Hauptschleife der A*-Suche
        while (!open.isEmpty() && expansions < maxExpansions) {
            SearchNode cur = open.poll();
            expansions++;

            // Wenn wir diesen Zustand schon besser gesehen haben: überspringen
            Double gBest = bestG.get(cur.stateKey());
            if (gBest != null && cur.gCost > gBest + 1e-9) continue;

            // Goal: genug Tiles geplant
            if (cur.tileIndex >= depthLimit) {
                goal = cur;
                break;
            }

            int tileId = tileIds.get(cur.tileIndex); //Tile das wir platziere wollen

            // wir verwenden die vom Engine-Callback gelieferten legalMoves
            // nur für das erste Tile; für Lookahead nutzen wir dieselbe Liste erneut
            List<int[]> moves = legalMoves;

            //mögliche Platzierungen ausprobieren
            for (int[] m : moves) {
                int x = m[0], y = m[1], rot = m[2];

                // Minimal-Belegung: 1 Feld pro Tile
                String occKey = x + "," + y;

                // Kollision mit bereits geplanter Belegung verhindern
                if (cur.plannedOccupied.contains(occKey)) continue;

                double reward = ctx.state().evaluatePlacement(tileId, x, y, rot); //Bewertung der Platzierung
                double stepCost = -reward; // minimieren

                // Neue belegte Felder übernehmen
                HashSet<String> newOcc = new HashSet<>(cur.plannedOccupied);
                newOcc.add(occKey);

                int nextIndex = cur.tileIndex + 1;
                double newG = cur.gCost + stepCost;

                // Heuristik für die restlichen Tiles
                double newH = heuristic(ctx, tileIds.subList(0, depthLimit), nextIndex);

                BuildMove move = new BuildMove(tileId, x, y, rot);

                // Neuen Suchknoten erzeugen
                SearchNode child = new SearchNode(
                        nextIndex,
                        newOcc,
                        newG,
                        newH,
                        cur,
                        move
                );

                String key = child.stateKey();
                Double prev = bestG.get(key);

                // Nur übernehmen, wenn dieser Weg besser ist
                if (prev == null || newG < prev - 1e-9) {
                    bestG.put(key, newG);
                    open.add(child);
                }
            }
        }

        if (goal == null) {
            // Fallback: niemals empty, sonst hängt Engine
            return bestImmediate(ctx, tileIds.get(0), legalMoves);
        }
        // Ersten Move aus dem gefundenen Plan zurückgeben
        return Optional.of(reconstructFirstMove(goal));
    }

    //Erzeugt eindueitgen Schlüssel für eine Platzierung
    private static String stateKey(int x, int y, int rot) {
        return x + "," + y + "," + rot;
    }


    /**
     * Heuristik h:
     * * Schätzt die Restkosten für noch nicht geplante Tiles
     */
    private double heuristic(AiContext ctx, List<Integer> planTiles, int nextIndex) {
        double optimistic = 0.0;
        // Alle noch nicht geplanten Tiles durchgehen
        for (int i = nextIndex; i < planTiles.size(); i++) {
            int tileId = planTiles.get(i);
            // Grobe Bewertung des Tiles ohne konkrete Platzierung
            double v = ctx.state().estimateTileValue(tileId);
            // Schlechte Tiles erhöhen die geschätzten Restkosten,
            // gute Tiles zählen nicht negativ
            optimistic += Math.max(0.0, 5.0 - v);
        }
        return optimistic;
    }

    /**
     * Fallback, falls A* keinen Plan findet: nimm den Move mit maximalem Reward.
     * Wichtig: In der Build-Phase darf niemals Optional.empty() zurückgegeben werden,
     * sonst bleibt die Engine hängen.
     */
    private Optional<BuildMove> bestImmediate(AiContext ctx, int tileId, List<int[]> legalMoves) {
        if (legalMoves == null || legalMoves.isEmpty()) {
            return Optional.empty();
        }

        double best = Double.NEGATIVE_INFINITY;
        BuildMove bestMove = null;

        // Alle legalen Platzierungen bewerten
        for (int[] m : legalMoves) {
            int x = m[0], y = m[1], rot = m[2];

            double score = ctx.state().evaluatePlacement(tileId, x, y, rot);

            // Besten Move merken
            if (score > best) {
                best = score;
                bestMove = new BuildMove(tileId, x, y, rot);
            }
        }
        //Sicherheit
        if (bestMove == null) {
            int[] m = legalMoves.get(0);
            bestMove = new BuildMove(tileId, m[0], m[1], m[2]);
        }
        return Optional.of(bestMove);
    }


    /**
     * Rekonstruiert den ersten Zug des gefundenen A*-Plans
     */
    private BuildMove reconstructFirstMove(SearchNode goal) {
        SearchNode cur = goal;

        // Zurücklaufen, bis wir beim ersten echten Move angekommen sind
        while (cur.parent != null && cur.parent.parent != null) {
            cur = cur.parent;
        }
        return cur.moveFromParent; // Der erste Move, den wir tatsächlich ausführen müssen

    }

  //Interner A*-Suchknoten
    private static final class SearchNode {
        final int tileIndex; //wie viele Tiles bereits im Plan platziert wurden
        final HashSet<String> plannedOccupied; //belegte Koordinaten
        final double gCost; //tatsäcjlcihe Kosten bis zu diesem Knoten
        final double hCost; //geschätze Restkosten (Heursitik)
        final double fCost; //Gesamtkosten

        final SearchNode parent; //vorgängerknoten
        final BuildMove moveFromParent; // Move, der von parent zu diesem Knoten geführt hat


      SearchNode(int tileIndex,
                   HashSet<String> plannedOccupied,
                   double gCost,
                   double hCost,
                   SearchNode parent,
                   BuildMove moveFromParent) {
            this.tileIndex = tileIndex;
            this.plannedOccupied = plannedOccupied;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
            this.parent = parent;
            this.moveFromParent = moveFromParent;
        }

        /**
         * Eindeutiger Schlüssel für Zustandsvergleich (für bestG)
         */
        String stateKey() {
            ArrayList<String> list = new ArrayList<>(plannedOccupied);
            Collections.sort(list);
            return tileIndex + "|" + String.join(";", list);
        }
    }
    }