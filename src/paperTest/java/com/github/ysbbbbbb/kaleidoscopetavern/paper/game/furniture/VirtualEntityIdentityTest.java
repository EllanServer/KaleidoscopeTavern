package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class VirtualEntityIdentityTest {
    @Test
    void derivesStableDistinctRfcUuidsFromEntityIds() {
        UUID first = VirtualEntityIdentity.fromEntityId(1);
        UUID again = VirtualEntityIdentity.fromEntityId(1);
        UUID second = VirtualEntityIdentity.fromEntityId(2);

        assertEquals(first, again);
        assertNotEquals(first, second);
        assertEquals(4, first.version());
        assertEquals(2, first.variant());
    }

    @Test
    void preservesUnsignedEntityIdBits() {
        assertNotEquals(
                VirtualEntityIdentity.fromEntityId(-1),
                VirtualEntityIdentity.fromEntityId(Integer.MAX_VALUE));
    }
}
