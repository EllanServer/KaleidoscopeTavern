package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import java.util.UUID;

/** Cheap, session-local UUIDs for CE packet-only entities. */
final class VirtualEntityIdentity {
    // "KTVR" plus RFC-4122 version/variant bits. CE's entity counter is
    // process-wide, so its unsigned value is sufficient to keep every active
    // packet-only entity distinct without initializing UUID's SecureRandom.
    private static final long MOST_SIGNIFICANT_BITS = 0x4b54565200004000L;
    private static final long VARIANT_BITS = 0x8000000000000000L;

    private VirtualEntityIdentity() {
    }

    static UUID fromEntityId(int entityId) {
        return new UUID(MOST_SIGNIFICANT_BITS,
                VARIANT_BITS | Integer.toUnsignedLong(entityId));
    }

    static void prewarm() {
        fromEntityId(0);
    }
}
