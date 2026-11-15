package backend.network.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageTypeTest {

    @Test
    void fromWireName_valid() {
        assertEquals(MessageType.HELLO_CLIENT,
                MessageType.fromWireName("HelloClient"));
    }

    @Test
    void fromWireName_invalid_returnsNull() {
        assertNull(MessageType.fromWireName("DoesNotExist"));
    }

    @Test
    void wireName_matchesProtocol() {
        assertEquals("BuyTile", MessageType.BUY_TILE.getWireName());
    }
}
