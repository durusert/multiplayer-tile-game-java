package backend.model.tile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TileBagTest {

    @Test
    void drawTiles_removesTilesFromBag() {
        TileBag bag = new TileBag();

        int before = bag.getTiles().size();
        List<Tile> drawn = bag.drawTiles(1);

        assertEquals(1, drawn.size());
        assertNotNull(drawn.get(0));

        int after = bag.getTiles().size();
        assertEquals(before - 1, after);
    }

    @Test
    void drawTiles_neverReturnsSameTileIdTwice_whenDrawingTwo() {
        TileBag bag = new TileBag();

        List<Tile> drawn = bag.drawTiles(2);

        assertEquals(2, drawn.size());
        assertNotEquals(drawn.get(0).getTileId(), drawn.get(1).getTileId(),
                "Zwei gezogene Tiles dürfen nicht dieselbe ID haben");
    }

    @Test
    void drawTiles_moreThanAvailable_returnsAtMostAvailable_withoutCrashing() {
        TileBag bag = new TileBag();

        int available = bag.getTiles().size();
        List<Tile> drawn = bag.drawTiles(available + 999);

        assertEquals(available, drawn.size(), "Es dürfen maximal alle vorhandenen Tiles gezogen werden");
        assertTrue(bag.isEmpty(), "Bag sollte danach leer sein");
    }

    @Test
    void drawCastleTile_returnsTileWithCastleElement() {
        TileBag bag = new TileBag();

        Tile castle = bag.drawCastleTile();

        assertNotNull(castle);
        assertNotNull(castle.getElements(), "Castle tile sollte Elemente enthalten");

        boolean hasCastle = castle.getElements().stream()
                .anyMatch(e -> e.name().equalsIgnoreCase("CASTLE"));

        assertTrue(hasCastle, "drawCastleTile muss ein Tile mit CASTLE-Element liefern");
    }
}

