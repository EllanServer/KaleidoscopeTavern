package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.grape;

/** Pure climate probability rules copied from the archived Forge registrations. */
final class TrellisTemperatureSemantics {
    private static final String ICE = "kaleidoscope_tavern:ice_grapevine_trellis";
    private static final String GOLD = "kaleidoscope_tavern:gold_grapevine_trellis";

    private TrellisTemperatureSemantics() {
    }

    /** Selected once per CE block definition, never repeatedly by random ticks. */
    static Rule ruleForBlock(String blockId) {
        return switch (blockId) {
            case ICE -> Rule.COLD;
            case GOLD -> Rule.HOT;
            default -> Rule.NONE;
        };
    }

    enum Rule {
        NONE {
            @Override
            float adjust(float baseChance, double temperature) {
                return baseChance;
            }
        },
        COLD {
            @Override
            float adjust(float baseChance, double temperature) {
                return temperature < 0.15F ? Math.max(baseChance, 0.8F) : baseChance;
            }
        },
        HOT {
            @Override
            float adjust(float baseChance, double temperature) {
                return temperature > 1.0F ? Math.max(baseChance, 0.8F) : baseChance;
            }
        };

        abstract float adjust(float baseChance, double temperature);
    }
}
