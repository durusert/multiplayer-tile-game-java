package backend.model.tile;

import backend.model.Scroll;
import backend.model.enums.EdgeType;
import backend.model.enums.TileRoadSide;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TilePlacementRulesTest {

    private static Tile tile(int id, EdgeType top, EdgeType right, EdgeType down, EdgeType left) {
        // roads/elements/scroll sind für diese Tests egal -> null oder leere Listen
        return new Tile(
                id,
                top, right, down, left,
                null,              // roads
                null,              // elements
                null,              // scroll
                0, 0               // spriteX, spriteY
        );
    }

    @Test
    void isValidRotation_acceptsOnly90Steps() {
        assertTrue(TilePlacementRules.isValidRotation(0));
        assertTrue(TilePlacementRules.isValidRotation(90));
        assertTrue(TilePlacementRules.isValidRotation(180));
        assertTrue(TilePlacementRules.isValidRotation(270));

        assertFalse(TilePlacementRules.isValidRotation(45));
        assertFalse(TilePlacementRules.isValidRotation(360));
        assertFalse(TilePlacementRules.isValidRotation(-90));
    }

    @Test
    void getEdgeAtSide_rotation90_clockwise_mapsCorrectly() {
        Tile t = tile(1, EdgeType.FIELD, EdgeType.MOUNTAIN, EdgeType.WATER, EdgeType.FIELD);
        // Original: TOP=FIELD, RIGHT=MOUNTAIN, DOWN=WATER, LEFT=FIELD

        // Nach 90° clockwise:
        // TOP sollte vorher LEFT gewesen sein
        assertEquals(EdgeType.FIELD,
                TilePlacementRules.getEdgeAtSide(t, TileRoadSide.TOP, 90));

        // RIGHT sollte vorher TOP gewesen sein
        assertEquals(EdgeType.FIELD,
                TilePlacementRules.getEdgeAtSide(t, TileRoadSide.RIGHT, 90));

        // DOWN sollte vorher RIGHT gewesen sein
        assertEquals(EdgeType.MOUNTAIN,
                TilePlacementRules.getEdgeAtSide(t, TileRoadSide.DOWN, 90));

        // LEFT sollte vorher DOWN gewesen sein
        assertEquals(EdgeType.WATER,
                TilePlacementRules.getEdgeAtSide(t, TileRoadSide.LEFT, 90));
    }

    @Test
    void edgesAreCompatible_trueWhenSameEdgeTypesOppose() {
        Tile a = tile(1, EdgeType.FIELD, EdgeType.FIELD, EdgeType.FIELD, EdgeType.FIELD);
        Tile b = tile(2, EdgeType.FIELD, EdgeType.MOUNTAIN, EdgeType.WATER, EdgeType.FIELD);

        // a.RIGHT (FIELD) an b.LEFT (FIELD) -> kompatibel
        assertTrue(TilePlacementRules.edgesAreCompatible(
                a, 0, TileRoadSide.RIGHT,
                b, 0, TileRoadSide.LEFT
        ));
    }

    @Test
    void edgesAreCompatible_falseWhenDifferentEdgeTypesOppose() {
        Tile a = tile(1, EdgeType.FIELD, EdgeType.FIELD, EdgeType.FIELD, EdgeType.FIELD);
        Tile b = tile(2, EdgeType.FIELD, EdgeType.MOUNTAIN, EdgeType.WATER, EdgeType.MOUNTAIN);

        // a.RIGHT (FIELD) an b.LEFT (MOUNTAIN) -> nicht kompatibel
        assertFalse(TilePlacementRules.edgesAreCompatible(
                a, 0, TileRoadSide.RIGHT,
                b, 0, TileRoadSide.LEFT
        ));
    }

    @Test
    void getEdgeAtSide_throwsOnInvalidRotation() {
        Tile t = tile(1, EdgeType.FIELD, EdgeType.FIELD, EdgeType.FIELD, EdgeType.FIELD);

        assertThrows(IllegalArgumentException.class, () ->
                TilePlacementRules.getEdgeAtSide(t, TileRoadSide.TOP, 45)
        );
    }
}
