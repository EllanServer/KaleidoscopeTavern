package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** Native ports of the legacy block event generators (trellis wax, grapevine shear, incense toggle). */
public final class BlockEvents {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    private BlockEvents() {}

    public static JsonArray trellisWaxEvents() {
        JsonObject particlePosition = new JsonObject();
        particlePosition.addProperty("x", "<arg:position.x> + 0.5");
        particlePosition.addProperty("y", "<arg:position.y> + 0.5");
        particlePosition.addProperty("z", "<arg:position.z> + 0.5");
        particlePosition.addProperty("count", 8);
        particlePosition.addProperty("offset_x", 0.35);
        particlePosition.addProperty("offset_y", 0.35);
        particlePosition.addProperty("offset_z", 0.35);
        JsonArray events = new JsonArray();
        for (String[] spec : new String[][] {
                {"minecraft:honeycomb", "false", "false", "true",
                 "minecraft:item.honeycomb.wax_on", "minecraft:wax_on"},
                {"minecraft:.+_axe", "true", "true", "false",
                 "minecraft:item.axe.wax_off", "minecraft:wax_off"}}) {
            JsonObject matchItem = new JsonObject();
            matchItem.addProperty("type", "match_item");
            matchItem.addProperty("item", spec[0]);
            if (spec[1].equals("true")) matchItem.addProperty("regex", true);
            JsonObject matchProperty = new JsonObject();
            matchProperty.addProperty("type", "match_block_property");
            JsonObject properties = new JsonObject();
            properties.addProperty("waxed", spec[2]);
            matchProperty.add("properties", properties);
            JsonObject hand = new JsonObject();
            hand.addProperty("type", "hand");
            hand.addProperty("hand", "main_hand");
            JsonArray conditions = new JsonArray();
            conditions.add(matchItem);
            conditions.add(matchProperty);
            conditions.add(hand);
            JsonArray functions = new JsonArray();
            JsonObject update = new JsonObject();
            update.addProperty("type", "update_block_property");
            JsonObject updateProperties = new JsonObject();
            updateProperties.addProperty("waxed", spec[3]);
            update.add("properties", updateProperties);
            functions.add(update);
            functions.add(obj("type", "play_sound", "sound", spec[4], "source", "block"));
            JsonObject particle = new JsonObject();
            particle.addProperty("type", "particle");
            particle.addProperty("particle", spec[5]);
            particle.addProperty("x", particlePosition.get("x").getAsString());
            particle.addProperty("y", particlePosition.get("y").getAsString());
            particle.addProperty("z", particlePosition.get("z").getAsString());
            particle.addProperty("count", 8);
            particle.addProperty("offset_x", 0.35);
            particle.addProperty("offset_y", 0.35);
            particle.addProperty("offset_z", 0.35);
            functions.add(particle);
            functions.add(obj("type", "swing_hand"));
            functions.add(obj("type", "cancel_event"));
            JsonObject event = new JsonObject();
            event.addProperty("on", "right_click");
            event.add("conditions", conditions);
            event.add("functions", functions);
            events.add(event);
        }
        return events;
    }

    public static JsonArray wildGrapevineShearEvents() {
        JsonArray events = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("on", "right_click");
        JsonArray firstConditions = new JsonArray();
        firstConditions.add(obj("type", "match_item", "item", "minecraft:shears"));
        JsonObject firstProperty = new JsonObject();
        firstProperty.addProperty("type", "match_block_property");
        JsonObject shearedFalse = new JsonObject();
        shearedFalse.addProperty("sheared", "false");
        firstProperty.add("properties", shearedFalse);
        firstConditions.add(firstProperty);
        first.add("conditions", firstConditions);
        JsonArray firstFunctions = new JsonArray();
        JsonObject update = new JsonObject();
        update.addProperty("type", "update_block_property");
        JsonObject shearedTrue = new JsonObject();
        shearedTrue.addProperty("sheared", "true");
        update.add("properties", shearedTrue);
        firstFunctions.add(update);
        firstFunctions.add(obj("type", "damage_item", "amount", 1));
        firstFunctions.add(obj("type", "play_sound", "sound", "minecraft:entity.sheep.shear",
                "source", "block", "target", "self"));
        firstFunctions.add(obj("type", "swing_hand"));
        firstFunctions.add(obj("type", "cancel_event"));
        first.add("functions", firstFunctions);
        events.add(first);
        JsonObject second = new JsonObject();
        second.addProperty("on", "right_click");
        JsonArray secondConditions = new JsonArray();
        secondConditions.add(obj("type", "match_item", "item", "minecraft:shears"));
        JsonObject secondProperty = new JsonObject();
        secondProperty.addProperty("type", "match_block_property");
        JsonObject shearedTrueProp = new JsonObject();
        shearedTrueProp.addProperty("sheared", "true");
        secondProperty.add("properties", shearedTrueProp);
        secondConditions.add(secondProperty);
        second.add("conditions", secondConditions);
        JsonArray secondFunctions = new JsonArray();
        secondFunctions.add(obj("type", "cancel_event"));
        second.add("functions", secondFunctions);
        events.add(second);
        return events;
    }

    public static JsonArray grapevineTrellisShearEvents() {
        JsonArray events = new JsonArray();
        JsonObject event = new JsonObject();
        event.addProperty("on", "right_click");
        JsonArray conditions = new JsonArray();
        conditions.add(obj("type", "match_item", "item", "minecraft:shears"));
        event.add("conditions", conditions);
        JsonArray functions = new JsonArray();
        functions.add(obj("type", "transform_block", "block", NAMESPACE + ":trellis"));
        JsonObject lootEntry = obj("type", "item", "item", NAMESPACE + ":grapevine");
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        JsonArray entries = new JsonArray();
        entries.add(lootEntry);
        pool.add("entries", entries);
        JsonObject loot = new JsonObject();
        JsonArray pools = new JsonArray();
        pools.add(pool);
        loot.add("pools", pools);
        functions.add(obj("type", "drop_loot", "loot", loot));
        functions.add(obj("type", "damage_item", "amount", 1));
        functions.add(obj("type", "play_sound", "sound", "minecraft:block.beehive.shear",
                "source", "block", "target", "self"));
        functions.add(obj("type", "swing_hand"));
        functions.add(obj("type", "cancel_event"));
        event.add("functions", functions);
        events.add(event);
        return events;
    }

    public static JsonObject ordinaryBlockUseCondition() {
        JsonObject notSneaking = obj("type", "!equals",
                "value1", "<arg:player.is_sneaking>", "value2", "true");
        JsonObject allOf = new JsonObject();
        allOf.addProperty("type", "all_of");
        JsonArray allTerms = new JsonArray();
        allTerms.add(obj("type", "equals", "value1", "<arg:player.main_hand_item.count>", "value2", "0"));
        allTerms.add(obj("type", "equals", "value1", "<arg:player.off_hand_item.count>", "value2", "0"));
        allOf.add("terms", allTerms);
        JsonObject result = new JsonObject();
        result.addProperty("type", "any_of");
        JsonArray terms = new JsonArray();
        terms.add(notSneaking);
        terms.add(allOf);
        result.add("terms", terms);
        return result;
    }

    public static JsonArray incenseToggleEvents() {
        JsonArray events = new JsonArray();
        for (String[] spec : new String[][] {
                {"false", "true", "minecraft:block.stone_button.click_on"},
                {"true", "false", "minecraft:block.stone_button.click_off"}}) {
            JsonObject event = new JsonObject();
            event.addProperty("on", "right_click");
            JsonArray conditions = new JsonArray();
            conditions.add(ordinaryBlockUseCondition());
            JsonObject matchProperty = new JsonObject();
            matchProperty.addProperty("type", "match_block_property");
            JsonObject properties = new JsonObject();
            properties.addProperty("open", spec[0]);
            matchProperty.add("properties", properties);
            conditions.add(matchProperty);
            conditions.add(obj("type", "test_flag", "flag", "interact"));
            event.add("conditions", conditions);
            JsonArray functions = new JsonArray();
            functions.add(obj("type", "update_interaction_tick"));
            JsonObject update = new JsonObject();
            update.addProperty("type", "update_block_property");
            JsonObject updateProperties = new JsonObject();
            updateProperties.addProperty("open", spec[1]);
            update.add("properties", updateProperties);
            update.addProperty("update_flags", 2);
            functions.add(update);
            functions.add(obj("type", "play_sound", "sound", spec[2], "source", "block"));
            functions.add(obj("type", "swing_hand"));
            functions.add(obj("type", "cancel_event"));
            event.add("functions", functions);
            events.add(event);
        }
        JsonObject protectedEvent = new JsonObject();
        protectedEvent.addProperty("on", "right_click");
        JsonArray protectedConditions = new JsonArray();
        protectedConditions.add(ordinaryBlockUseCondition());
        protectedConditions.add(obj("type", "!test_flag", "flag", "interact"));
        protectedEvent.add("conditions", protectedConditions);
        JsonArray protectedFunctions = new JsonArray();
        protectedFunctions.add(obj("type", "update_interaction_tick"));
        protectedFunctions.add(obj("type", "cancel_event"));
        protectedEvent.add("functions", protectedFunctions);
        events.add(protectedEvent);
        return events;
    }

    private static JsonObject obj(Object... values) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            String key = (String) values[i];
            Object value = values[i + 1];
            if (value instanceof JsonElement element) object.add(key, element);
            else if (value instanceof Boolean bool) object.addProperty(key, bool);
            else if (value instanceof Number number) object.addProperty(key, number);
            else object.addProperty(key, String.valueOf(value));
        }
        return object;
    }
}
