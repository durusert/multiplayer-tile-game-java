package backend.logic;

import backend.model.Game;
import backend.model.Player;
import backend.model.enums.GamePiece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IncomeLogicTest {

    @Test
    void catchUpCoins_round1_isZero() {
        Game game = new Game();
        Player a = new Player("A", GamePiece.YELLOW);
        Player b = new Player("B", GamePiece.GREEN);

        game.addPlayer(a);
        game.addPlayer(b);

        game.advanceRound(); // falls es setCurrentRound gibt; sonst via advanceRound()

        a.setVictoryPoints(0);
        b.setVictoryPoints(5);

        assertEquals(0, IncomeLogic.catchUpCoins(game, a));
    }
}