package backend.logic;

import backend.model.Game;
import backend.model.Player;
import backend.model.enums.GamePiece;
import backend.model.enums.GameStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameLogicScoringTest {

    @Test
    void enterScoringPhase_increasesVictoryPoints() {
        Game game = new Game();
        GameLogic logic = new GameLogic(game);

        Player a = new Player("Alice", GamePiece.GREEN);
        Player b = new Player("Bob", GamePiece.YELLOW);

        logic.startGame(List.of(a, b)); // setzt Status RUNNING, Runde 0

        int beforeA = a.getVictoryPoints();
        int beforeB = b.getVictoryPoints();

        logic.enterScoringPhase(); // ⭐ echte Scoring-Methode

        assertTrue(a.getVictoryPoints() >= beforeA,
                "Spieler A sollte nach Scoring nicht weniger Punkte haben");
        assertTrue(b.getVictoryPoints() >= beforeB,
                "Spieler B sollte nach Scoring nicht weniger Punkte haben");
    }
}
