package backend.network.protocol;

import backend.model.enums.RoundPhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhaseMessageHelperTest {

    @Test
    void mapDrawPhaseCorrectly() {
        Message msg = PhaseMessageHelper.createRoundPhaseUpdate(0, RoundPhase.DRAW);

        assertEquals("RoundPhaseUpdate", msg.getMessageType());
        assertEquals(0, msg.getMessageBody().get("round").getAsInt());
        assertEquals(1, msg.getMessageBody().get("phase").getAsInt());
    }

    @Test
    void mapBuildPhaseCorrectly() {
        Message msg = PhaseMessageHelper.createRoundPhaseUpdate(2, RoundPhase.BUILD);

        assertEquals(5, msg.getMessageBody().get("phase").getAsInt());
    }
}
