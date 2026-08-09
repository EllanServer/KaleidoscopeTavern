package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.storage;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigValue;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Parsed, content-owned configuration for a multi-slot CraftEngine storage block.
 *
 * <p>The Java behavior is deliberately generic: item rules, click selection,
 * slot transforms, facing transforms, sounds, redstone launching and particles
 * all live in the generated CE block configuration.</p>
 */
public record StorageBlockConfig(
        String dataKey,
        String renderItemPrefix,
        float viewRange,
        List<SlotVisual> slots,
        Selector selector,
        Map<Direction, Orientation> orientations,
        Interaction interaction,
        Launch launch,
        ParticleEffect particle,
        Set<String> refreshProperties
) {
    public static StorageBlockConfig parse(ConfigSection section) {
        String dataKey = section.getNonEmptyString("data_key");
        String renderItemPrefix = section.getString(
                "render_item_prefix", "kaleidoscope_tavern:_render/storage/");
        float viewRange = section.getFloat("view_range", 1.25F);
        if (viewRange <= 0) {
            throw new IllegalArgumentException(
                    "view_range must be positive at " + section.assemblePath("view_range"));
        }

        List<SlotVisual> slots = section.getList(
                "slots", value -> SlotVisual.parse(value.getAsSection()));
        if (slots.isEmpty()) {
            throw new IllegalArgumentException(
                    "Storage slots must not be empty at " + section.assemblePath("slots"));
        }

        ConfigSection selectorSection = section.getNonNullSection("selector");
        Selector selector = Selector.parse(selectorSection);
        if (selector.expectedSlots() != slots.size()) {
            throw new IllegalArgumentException(
                    "Selector at " + selectorSection.path() + " selects "
                            + selector.expectedSlots() + " slots but " + slots.size()
                            + " slot visuals are configured");
        }

        ConfigSection orientationSection = section.getNonNullSection("orientations");
        EnumMap<Direction, Orientation> orientations = new EnumMap<>(Direction.class);
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            ConfigSection configured = orientationSection.getSection(
                    direction.name().toLowerCase(Locale.ROOT));
            if (configured == null) {
                throw new IllegalArgumentException(
                        "Missing " + direction.name().toLowerCase(Locale.ROOT)
                                + " orientation at " + orientationSection.path());
            }
            orientations.put(direction, Orientation.parse(configured));
        }

        Interaction interaction = Interaction.parse(
                section.getNonNullSection("interaction"));
        if (interaction.exclusiveSlot() < 0
                || interaction.exclusiveSlot() >= slots.size()) {
            throw new IllegalArgumentException(
                    "exclusive_slot is outside the configured storage slots at "
                            + section.assemblePath("interaction.exclusive_slot"));
        }

        Launch launch = section.getSection("launch") == null
                ? null : Launch.parse(section.getNonNullSection("launch"));
        ParticleEffect particle = section.getSection("particle") == null
                ? null : ParticleEffect.parse(section.getNonNullSection("particle"));
        Set<String> refresh = new LinkedHashSet<>(
                section.getStringList("refresh_properties"));
        refresh.add("facing");

        return new StorageBlockConfig(
                dataKey,
                renderItemPrefix,
                viewRange,
                List.copyOf(slots),
                selector,
                Map.copyOf(orientations),
                interaction,
                launch,
                particle,
                Set.copyOf(refresh));
    }

    public Orientation orientation(Direction direction) {
        Orientation orientation = orientations.get(direction);
        if (orientation == null) {
            throw new IllegalArgumentException("Unsupported storage facing: " + direction);
        }
        return orientation;
    }

    public boolean isExclusive(Key itemId) {
        return interaction.exclusiveItems.contains(itemId);
    }

    public boolean isAllowed(Key itemId) {
        return interaction.allowedItems.isEmpty()
                || interaction.allowedItems.contains(itemId);
    }

    public boolean isBlocked(Key itemId) {
        return interaction.blockedItems.contains(itemId);
    }

    public record Orientation(
            float positionYaw,
            float modelYaw,
            CoordinateExpression localX,
            CoordinateExpression localZ,
            boolean reverseSlots
    ) {
        private static Orientation parse(ConfigSection section) {
            return new Orientation(
                    section.getFloat("position_yaw", 0),
                    section.getFloat("model_yaw", 0),
                    CoordinateExpression.parse(section.getString("local_x", "x")),
                    CoordinateExpression.parse(section.getString("local_z", "z")),
                    section.getBoolean("reverse_slots", false));
        }

        public double sourceX(double x, double z) {
            return localX.evaluate(x, z);
        }

        public double sourceZ(double x, double z) {
            return localZ.evaluate(x, z);
        }
    }

    public enum CoordinateExpression {
        X,
        ONE_MINUS_X,
        Z,
        ONE_MINUS_Z;

        static CoordinateExpression parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT).replace(" ", "")) {
                case "x" -> X;
                case "1-x", "one_minus_x" -> ONE_MINUS_X;
                case "z" -> Z;
                case "1-z", "one_minus_z" -> ONE_MINUS_Z;
                default -> throw new IllegalArgumentException(
                        "Unsupported storage coordinate expression: " + value);
            };
        }

        double evaluate(double x, double z) {
            return switch (this) {
                case X -> x;
                case ONE_MINUS_X -> 1 - x;
                case Z -> z;
                case ONE_MINUS_Z -> 1 - z;
            };
        }
    }

    public record SlotVisual(
            Vector3f position,
            Vector3f axisXPosition,
            Vector3f exclusivePosition,
            Vector3f exclusiveAxisXPosition,
            float scale,
            float yRotation,
            float xRotation
    ) {
        private static SlotVisual parse(ConfigSection section) {
            Vector3f position = section.getVector3f("position", new Vector3f(0.5F));
            Vector3f axisX = optionalVector(section, "axis_x_position");
            Vector3f exclusive = optionalVector(section, "exclusive_position");
            Vector3f exclusiveAxisX = optionalVector(
                    section, "exclusive_axis_x_position");
            float scale = section.getFloat("scale", 1);
            if (scale <= 0) {
                throw new IllegalArgumentException(
                        "Storage visual scale must be positive at "
                                + section.assemblePath("scale"));
            }
            return new SlotVisual(
                    new Vector3f(position),
                    axisX == null ? null : new Vector3f(axisX),
                    exclusive == null ? null : new Vector3f(exclusive),
                    exclusiveAxisX == null ? null : new Vector3f(exclusiveAxisX),
                    scale,
                    section.getFloat("y_rotation", 0),
                    section.getFloat("x_rotation", 0));
        }

        private static Vector3f optionalVector(ConfigSection section, String key) {
            ConfigValue value = section.getValue(key);
            return value == null ? null : value.getAsVector3f();
        }

        public Vector3f position(boolean axisX, boolean exclusive) {
            if (exclusive) {
                if (axisX && exclusiveAxisXPosition != null) {
                    return exclusiveAxisXPosition;
                }
                if (exclusivePosition != null) {
                    return exclusivePosition;
                }
            }
            return axisX && axisXPosition != null ? axisXPosition : position;
        }
    }

    public record Selector(
            SelectorType type,
            int columns,
            int rows,
            int segments,
            Axis axis,
            boolean reverseX,
            boolean reverseY,
            boolean frontOnly,
            int radialOffset,
            boolean radialClockwise
    ) {
        private static Selector parse(ConfigSection section) {
            Selector selector = new Selector(
                    section.getNonNullEnum("type", SelectorType.class),
                    section.getInt("columns", 1),
                    section.getInt("rows", 1),
                    section.getInt("segments", 1),
                    section.getEnum("axis", Axis.class, Axis.X),
                    section.getBoolean("reverse_x", false),
                    section.getBoolean("reverse_y", false),
                    section.getBoolean("front_only", false),
                    section.getInt("radial_offset", 0),
                    section.getBoolean("radial_clockwise", true));
            if (selector.columns <= 0 || selector.rows <= 0
                    || selector.segments <= 0) {
                throw new IllegalArgumentException(
                        "Storage selector dimensions must be positive at " + section.path());
            }
            return selector;
        }

        int expectedSlots() {
            return switch (type) {
                case SINGLE -> 1;
                case SPLIT, RADIAL -> segments;
                case GRID -> columns * rows;
            };
        }

        public int select(double sourceX, double sourceY, double sourceZ,
                          boolean reverseSlots) {
            if (!inside(sourceX) || !inside(sourceY) || !inside(sourceZ)) {
                return -1;
            }
            int result = switch (type) {
                case SINGLE -> 0;
                case SPLIT -> {
                    double coordinate = axis == Axis.X ? sourceX : sourceZ;
                    int value = Math.min(segments - 1,
                            Math.max(0, (int) Math.floor(coordinate * segments)));
                    if (reverseX) {
                        value = segments - 1 - value;
                    }
                    yield value;
                }
                case GRID -> {
                    int column = Math.min(columns - 1,
                            Math.max(0, (int) Math.floor(sourceX * columns)));
                    int row = Math.min(rows - 1,
                            Math.max(0, (int) Math.floor(sourceY * rows)));
                    if (reverseX) {
                        column = columns - 1 - column;
                    }
                    if (reverseY) {
                        row = rows - 1 - row;
                    }
                    yield column + row * columns;
                }
                case RADIAL -> {
                    double angle = Math.toDegrees(
                            Math.atan2(sourceZ - 0.5, sourceX - 0.5));
                    angle = (angle + 360) % 360;
                    int sector = Math.min(segments - 1,
                            (int) Math.floor(angle / (360.0 / segments)));
                    yield Math.floorMod(
                            radialOffset + (radialClockwise ? -sector : sector),
                            segments);
                }
            };
            return reverseSlots ? expectedSlots() - 1 - result : result;
        }

        private static boolean inside(double coordinate) {
            return coordinate >= -1.0E-3 && coordinate <= 1.001;
        }
    }

    public enum SelectorType {
        SINGLE,
        SPLIT,
        GRID,
        RADIAL
    }

    public enum Axis {
        X,
        Z
    }

    public record Interaction(
            Set<Key> allowedItems,
            Set<Key> blockedItems,
            Set<Key> exclusiveItems,
            int exclusiveSlot,
            boolean fallbackTake,
            boolean fallbackPut,
            boolean consumeInCreative,
            String invalidMessage,
            String blockedMessage,
            InteractionFailure invalidResult,
            InteractionFailure blockedResult,
            ConfiguredSound putSound,
            ConfiguredSound putLastSound,
            ConfiguredSound takeSound
    ) {
        private static Interaction parse(ConfigSection section) {
            ConfigSection sounds = section.getSection("sounds");
            return new Interaction(
                    identifiers(section, "allowed_items"),
                    identifiers(section, "blocked_items"),
                    identifiers(section, "exclusive_items"),
                    section.getInt("exclusive_slot", 0),
                    section.getBoolean("fallback_take", false),
                    section.getBoolean("fallback_put", false),
                    section.getBoolean("consume_in_creative", true),
                    section.getString("invalid_message"),
                    section.getString("blocked_message"),
                    section.getEnum("invalid_result", InteractionFailure.class,
                            InteractionFailure.FAIL),
                    section.getEnum("blocked_result", InteractionFailure.class,
                            InteractionFailure.FAIL),
                    sound(sounds, "put"),
                    sound(sounds, "put_last"),
                    sound(sounds, "take"));
        }
    }

    public enum InteractionFailure {
        PASS,
        FAIL
    }

    public record ConfiguredSound(
            String id,
            float volumeMin,
            float volumeMax,
            float pitchMin,
            float pitchMax
    ) {
        private static ConfiguredSound parse(ConfigSection section) {
            float volumeMin = section.getFloat("volume_min", 1);
            float volumeMax = section.getFloat("volume_max", volumeMin);
            float pitchMin = section.getFloat("pitch_min", 1);
            float pitchMax = section.getFloat("pitch_max", pitchMin);
            return new ConfiguredSound(
                    section.getNonEmptyString("id"),
                    Math.min(volumeMin, volumeMax),
                    Math.max(volumeMin, volumeMax),
                    Math.min(pitchMin, pitchMax),
                    Math.max(pitchMin, pitchMax));
        }

        public float sampleVolume(ThreadLocalRandom random) {
            return sample(random, volumeMin, volumeMax);
        }

        public float samplePitch(ThreadLocalRandom random) {
            return sample(random, pitchMin, pitchMax);
        }
    }

    public record Launch(
            Set<Key> candidateItems,
            Set<Key> projectileItems,
            Set<Key> soundItems,
            double originForward,
            double originY,
            LaunchDirection direction,
            double factorMin,
            double factorMax,
            double verticalFactor,
            ConfiguredSound sound
    ) {
        private static Launch parse(ConfigSection section) {
            double factorMin = section.getDouble("factor_min", 0.5);
            double factorMax = section.getDouble("factor_max", 1.5);
            return new Launch(
                    identifiers(section, "candidate_items"),
                    identifiers(section, "projectile_items"),
                    identifiers(section, "sound_items"),
                    section.getDouble("origin_forward", 0),
                    section.getDouble("origin_y", 0.5),
                    section.getEnum("direction", LaunchDirection.class,
                            LaunchDirection.FACING),
                    Math.min(factorMin, factorMax),
                    Math.max(factorMin, factorMax),
                    section.getDouble("vertical_factor", 0.1),
                    section.getSection("sound") == null
                            ? null : ConfiguredSound.parse(
                                    section.getNonNullSection("sound")));
        }
    }

    public enum LaunchDirection {
        FACING,
        OPPOSITE,
        UP
    }

    public record ParticleEffect(
            String type,
            int chance,
            Range x,
            Range alternateX,
            Range y,
            Range z,
            Range alternateZ,
            double offsetX,
            double offsetY,
            double offsetZ,
            double speed
    ) {
        private static ParticleEffect parse(ConfigSection section) {
            return new ParticleEffect(
                    section.getNonEmptyString("type"),
                    Math.max(1, section.getInt("chance", 1)),
                    Range.parse(section, "min_x", "max_x", 0, 1),
                    Range.optional(section, "alternate_min_x", "alternate_max_x"),
                    Range.parse(section, "min_y", "max_y", 0, 1),
                    Range.parse(section, "min_z", "max_z", 0, 1),
                    Range.optional(section, "alternate_min_z", "alternate_max_z"),
                    section.getDouble("offset_x", 0),
                    section.getDouble("offset_y", 0),
                    section.getDouble("offset_z", 0),
                    section.getDouble("speed", 0));
        }

        public double sampleX(ThreadLocalRandom random) {
            return selectRange(random, x, alternateX).sample(random);
        }

        public double sampleY(ThreadLocalRandom random) {
            return y.sample(random);
        }

        public double sampleZ(ThreadLocalRandom random) {
            return selectRange(random, z, alternateZ).sample(random);
        }

        private static Range selectRange(
                ThreadLocalRandom random, Range primary, Range alternate) {
            return alternate != null && random.nextBoolean() ? alternate : primary;
        }
    }

    public record Range(double minimum, double maximum) {
        private static Range parse(
                ConfigSection section, String minKey, String maxKey,
                double defaultMin, double defaultMax) {
            double minimum = section.getDouble(minKey, defaultMin);
            double maximum = section.getDouble(maxKey, defaultMax);
            return new Range(Math.min(minimum, maximum), Math.max(minimum, maximum));
        }

        private static Range optional(
                ConfigSection section, String minKey, String maxKey) {
            if (!section.containsKey(minKey) && !section.containsKey(maxKey)) {
                return null;
            }
            return parse(section, minKey, maxKey, 0, 1);
        }

        public double sample(ThreadLocalRandom random) {
            return StorageBlockConfig.sample(random, minimum, maximum);
        }
    }

    private static double sample(
            ThreadLocalRandom random, double minimum, double maximum) {
        if (maximum <= minimum) {
            return minimum;
        }
        return minimum + random.nextDouble() * (maximum - minimum);
    }

    private static float sample(
            ThreadLocalRandom random, float minimum, float maximum) {
        if (maximum <= minimum) {
            return minimum;
        }
        return minimum + random.nextFloat() * (maximum - minimum);
    }

    private static Set<Key> identifiers(ConfigSection section, String key) {
        List<Key> values = section.getList(key, ConfigValue::getAsIdentifier);
        return Set.copyOf(values);
    }

    private static ConfiguredSound sound(ConfigSection section, String key) {
        if (section == null || section.getSection(key) == null) {
            return null;
        }
        return ConfiguredSound.parse(section.getNonNullSection(key));
    }
}
