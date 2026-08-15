package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.items;

import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Native Java 25 migration stage for item configuration, drink effects,
 * runtime catalogs, and operator-editable station recipe defaults.
 */
public final class ItemMigrationStage {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public record EffectRow(String item, int level, String effect, int durationTicks, int amplifier, double probability) {}
    public record RuntimeMetrics(int pressing, int barrel, int shaker, int drinkEffectItems,
            int drinkEffectEntries, int tagMemberships, int registryTagMemberships) {}
    public record Result(JsonObject items, List<EffectRow> effectRows, RuntimeMetrics runtimeMetrics) {
        public Result {
            items = Objects.requireNonNull(items, "items").deepCopy();
            effectRows = List.copyOf(effectRows);
            Objects.requireNonNull(runtimeMetrics, "runtimeMetrics");
        }
    }

    /** Immutable migration inputs; list and map encounter order is preserved. */
    public record Input(
            List<String> itemIds,
            Set<String> blockIds,
            Set<String> furnitureIds,
            Map<String, JsonObject> furniturePlacement,
            Map<String, List<String>> tags,
            Map<String, Map<String, List<String>>> registryTags,
            Set<String> languageKeys
    ) {
        public Input {
            itemIds = List.copyOf(itemIds);
            blockIds = Collections.unmodifiableSet(new LinkedHashSet<>(blockIds));
            furnitureIds = Collections.unmodifiableSet(new LinkedHashSet<>(furnitureIds));
            furniturePlacement = copyJsonObjects(furniturePlacement);
            tags = copyLists(tags);
            registryTags = copyNestedLists(registryTags);
            languageKeys = Collections.unmodifiableSet(new LinkedHashSet<>(languageKeys));
            if (new LinkedHashSet<>(itemIds).size() != itemIds.size()) {
                throw new IllegalArgumentException("itemIds contains duplicates");
            }
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> HARMFUL = Set.of("minecraft:bad_omen", "minecraft:blindness", "minecraft:mining_fatigue", "minecraft:nausea");
    private static final Set<String> NEUTRAL = Set.of(NAMESPACE + ":slightly_tipsy", NAMESPACE + ":upside_down");
    private static final String MANAGED_LORE = "kaleidoscope_tavern_managed_lore";
    private static final Set<String> GRAPES = Set.of("grape", "ice_grape", "gold_grape", "green_grape");
    private static final Set<String> PAINTINGS = Set.of("ysbb_painting", "tartaric_acid_painting", "cr019_painting", "unknown_painting", "master_marisa_painting", "son_of_man_painting", "david_painting", "girl_with_pearl_earring_painting", "starry_night_painting", "van_gogh_self_portrait_painting", "father_painting", "great_wave_painting", "mona_lisa_painting", "mondrian_painting");
    private static final Set<String> SOFA_COLORS = Set.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final Map<String,String> SOFA_RGB = Map.ofEntries(
        Map.entry("white","249,255,254"),Map.entry("orange","249,128,29"),Map.entry("magenta","199,78,189"),Map.entry("light_blue","58,179,218"),Map.entry("yellow","254,216,61"),Map.entry("lime","128,199,31"),Map.entry("pink","243,139,170"),Map.entry("gray","71,79,82"),Map.entry("light_gray","157,157,151"),Map.entry("cyan","22,156,156"),Map.entry("purple","137,50,184"),Map.entry("blue","60,68,170"),Map.entry("brown","131,84,50"),Map.entry("green","94,124,22"),Map.entry("red","176,46,38"),Map.entry("black","29,29,33"));
    private static final Set<String> COCKTAILS = Set.of("empty_glassware", "signature_cocktail", "mystery_cocktail", "white_lady", "emerald", "brass_heart", "godfather", "grasshopper", "screwdriver", "mojito", "allium_garden", "depth_charge", "nether_special", "bloody_mary", "sculk_special");
    private static final Set<String> CONSUMABLE_COCKTAILS = difference(COCKTAILS, Set.of("empty_glassware"));
    private static final Set<String> SIMPLE_BOTTLES = Set.of("water_bottle", "honey_bottle", "dragon_breath_bottle", "potion_bottle", "xp_bottle");
    private static final Set<String> SMALL_FURNITURE = Set.of("empty_bottle","empty_glassware","signature_cocktail","mystery_cocktail","white_lady","emerald","brass_heart","godfather","grasshopper","screwdriver","mojito","allium_garden","depth_charge","nether_special","bloody_mary","sculk_special","shaker","molotov","water_bottle","honey_bottle","dragon_breath_bottle","potion_bottle","xp_bottle","wine","champagne","vodka","brandy","carignan","sakura_wine","plum_wine","whiskey","ice_wine","polaris_sweet_white","honey_wine","red_queen","miners_star","rum","riesling_dry_white","sunset_glow","madame_shexiang","sweet_berry_wine","sherry","mother_snow","luminous_bride","glowflower_brew","sauvignon_blanc_dry_white","vinegar","watermelon_juice");
    private static final Set<String> BOTTLE_AND_GLASS = difference(SMALL_FURNITURE, Set.of("shaker"));
    private static final Set<String> SIXTEEN_WAY = union(COCKTAILS, SIMPLE_BOTTLES);
    private static final Set<String> DIRECTIONLESS = Set.of("molotov");
    private static final Map<String,Integer> DRINK_COLORS = drinkColors();
    private static final Map<String,String> GENERIC_NAMES = genericNames();
    private static final Map<String,Map<String,List<String>>> VANILLA_PLACEMENTS = vanillaPlacements();

    private final Path projectRoot;
    private final Path outputRoot;
    public ItemMigrationStage(Path projectRoot, Path outputRoot) {
        this.projectRoot = Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
        this.outputRoot = Objects.requireNonNull(outputRoot).toAbsolutePath().normalize();
    }

    /**
     * Migrates items and catalogs. Registry tags are keyed by registry name (normally block/entity_type),
     * then by full tag id. outputRoot receives configuration/, catalog/ and recipes/.
     */
    public Result migrate(Input input) throws IOException {
        Objects.requireNonNull(input, "input");
        var effects = loadDrinkEffects();
        JsonObject items = buildItems(input.itemIds(), input.blockIds(), input.furnitureIds(),
                input.furniturePlacement(), effects.drinkIds(), effects.rows(), input.tags(), input.languageKeys());
        RuntimeMetrics metrics = buildRuntimeCatalogs(input.tags(), effects.rows(), input.registryTags());
        JsonObject document = new JsonObject(); document.add("items", items);
        writeJson(outputRoot.resolve("src/paper/pack/configuration/items.json"), document);
        return new Result(items, effects.rows, metrics);
    }

    private record Effects(Set<String> drinkIds, List<EffectRow> rows) {}
    private Effects loadDrinkEffects() throws IOException {
        Path root = projectRoot.resolve("src/generated/resources/data/" + NAMESPACE + "/datamap/drink_effect");
        var ids = new LinkedHashSet<String>(); var rows = new ArrayList<EffectRow>();
        for (Path path : sortedJson(root)) {
            JsonObject data = readJson(path); String item = data.get("item").getAsString();
            ids.add(item.substring(item.indexOf(':') + 1)); int level = 0;
            for (JsonElement rawGroup : data.getAsJsonArray("effects")) { level++;
                for (JsonElement raw : rawGroup.getAsJsonArray()) { JsonObject e = raw.getAsJsonObject();
                    rows.add(new EffectRow(item, level, e.get("effect").getAsString(), e.get("duration").getAsInt()*20,
                            e.get("amplifier").getAsInt(), e.get("probability").getAsDouble()));
                }
            }
        }
        return new Effects(Set.copyOf(ids), List.copyOf(rows));
    }

    private JsonObject buildItems(List<String> itemIds, Set<String> blockIds, Set<String> furnitureIds,
            Map<String,JsonObject> placements, Set<String> drinkIds, List<EffectRow> effects,
            Map<String,List<String>> tags, Set<String> languageKeys) throws IOException {
        var memberships = new LinkedHashMap<String,List<String>>();
        for (var tag : tags.entrySet()) for (String member : tag.getValue()) if (member.startsWith(NAMESPACE + ":"))
            memberships.computeIfAbsent(member.substring(NAMESPACE.length()+1), ignored -> new ArrayList<>()).add(tag.getKey());
        Set<String> placeable = union(union(blockIds, furnitureIds), sofaBlocks());
        JsonObject items = new JsonObject();
        for (String id : itemIds) {
            requireItemModel(id);
            JsonObject config = obj("material", materialFor(id, drinkIds), "data", obj("item_name", "<!i><lang:" + itemNameKey(id, placeable, languageKeys) + ">"),
                    "model", obj("type","minecraft:model","path",NAMESPACE + ":item/" + id));
            JsonObject data = config.getAsJsonObject("data");
            if (isSofa(id)) data.addProperty("dyed_color", SOFA_RGB.get(id.substring(0,id.length()-5)));
            if (id.startsWith("string_lights_")) {
                data.add("equippable", obj("slot", "chest"));
            }
            var behaviors = new ArrayList<JsonObject>(); boolean sneakVessel = SMALL_FURNITURE.contains(id);
            if (BOTTLE_AND_GLASS.contains(id) || id.endsWith("_bucket")) components(data).addProperty("minecraft:max_stack_size",16);
            if (isDrink(id, drinkIds)) {
                JsonObject potion = obj("potion","minecraft:mundane"); Integer color = drinkColor(memberships.getOrDefault(id,List.of()));
                if (color != null) potion.addProperty("custom_color",color);
                components(data).add("minecraft:potion_contents",potion);
                configureConsumable(config,obj("consume_seconds",1.6,"animation","drink","sound","minecraft:entity.generic.drink","has_consume_particles",false));
                data.add("custom_name",data.get("item_name").deepCopy()); data.add("hide_tooltip",arr("minecraft:potion_contents"));
            }
            if (id.equals("shaker")) { components(data).addProperty("minecraft:max_stack_size",1);
                configureConsumable(config,obj("consume_seconds",3600.0,"animation","spyglass","has_consume_particles",false));
                config.add("model", shakerModel()); }
            if (id.equals("molotov")) { configureConsumable(config,obj("consume_seconds",3600.0,"animation","trident","has_consume_particles",false));
                config.add("model",obj("type","minecraft:condition","property","minecraft:using_item","on_true",model("molotov_charging"),"on_false",model("molotov"))); }
            List<String> loreKeys = new ArrayList<>();
            if (id.equals("grapevine")) for(int i=1;i<=3;i++) loreKeys.add("tooltip."+NAMESPACE+".grapevine."+i);
            else if(id.equals("trellis")) for(int i=1;i<=2;i++) loreKeys.add("tooltip."+NAMESPACE+".trellis."+i);
            else if(GRAPES.contains(id)||id.endsWith("_bucket")||PAINTINGS.contains(id)) loreKeys.add("tooltip."+NAMESPACE+"."+id);
            if(!loreKeys.isEmpty()) { JsonArray lore=new JsonArray(); for(String key:loreKeys) lore.add("<!i><gray><lang:"+key+">"); data.add("lore",lore); }
            else if(CONSUMABLE_COCKTAILS.contains(id)) { JsonArray lore=fixedLore(id,effects); if(!lore.isEmpty()) data.add("lore",lore); }
            if(GRAPES.contains(id)) { data.add("food",obj("nutrition",2,"saturation",2.0,"can_always_eat",true)); configureConsumable(config,obj("consume_seconds",1.6,"animation","eat")); }
            if(isSofa(id)) behaviors.add(obj("type","block_item","block",NAMESPACE+":_internal/sofa"));
            else if(furnitureIds.contains(id)) {
                if(id.equals("shaker")) behaviors.add(obj("type",NAMESPACE+":shaker_item"));
                JsonObject p=placements.get(id); if(p==null) throw new IllegalArgumentException("Missing furniture placement for "+id);
                JsonObject b=obj("type",sneakVessel?NAMESPACE+":sneak_place_drink":"furniture_item","furniture",NAMESPACE+":"+id,"rules",p.deepCopy());
                if(id.startsWith("string_lights_")||PAINTINGS.contains(id)) b.addProperty("ignore_placer",true); behaviors.add(b);
            } else if(id.equals("pressing_tub")) {
                behaviors.add(obj("type","ground_block_item","block",NAMESPACE+":pressing_tub")); behaviors.add(obj("type","ceiling_block_item","block",NAMESPACE+":pressing_tub"));
                behaviors.add(obj("type","furniture_item","furniture",NAMESPACE+":_internal/wall_pressing_tub","rules",obj("wall",obj("rotation","four","alignment","center"))));
            } else if(id.equals("chalkboard")) behaviors.add(obj("type","double_high_block_item","block",NAMESPACE+":chalkboard"));
            else if(blockIds.contains(id)) behaviors.add(obj("type","block_item","block",NAMESPACE+":"+id));
            else if(id.equals("grapevine")) { behaviors.add(obj("type",NAMESPACE+":grapevine_item")); behaviors.add(obj("type","block_item","block",NAMESPACE+":wild_grapevine")); }
            Double compost = null;
            if (id.equals("grapevine")) compost = 0.25;
            else if (GRAPES.contains(id)) compost = 0.5;
            if(compost!=null) behaviors.add(obj("type","compostable_item","chance",compost));
            if(behaviors.size()==1) config.add("behavior",behaviors.getFirst()); else if(!behaviors.isEmpty()) config.add("behaviors",arrayOf(behaviors));
            List<String> itemTags=new ArrayList<>(new LinkedHashSet<>(memberships.getOrDefault(id,List.of()))); Collections.sort(itemTags);
            if(!itemTags.isEmpty()) settings(config).add("tags",strings(itemTags)); if(compost!=null) settings(config).addProperty("compost_probability",compost);
            if(id.equals("grapevine")) settings(config).addProperty("fuel_time",200);
            if(id.endsWith("_bucket")) { settings(config).addProperty("consume_replacement","minecraft:bucket"); settings(config).addProperty("craft_remainder","minecraft:bucket"); }
            if(CONSUMABLE_COCKTAILS.contains(id)) settings(config).addProperty("consume_replacement",NAMESPACE+":empty_glassware");
            else if(isDrink(id,drinkIds)) { settings(config).addProperty("consume_replacement",NAMESPACE+":empty_bottle"); settings(config).addProperty("craft_remainder",NAMESPACE+":empty_bottle"); }
            items.add(NAMESPACE+":"+id,config);
        }
        for(var vanilla:VANILLA_PLACEMENTS.entrySet()) { JsonObject ps=new JsonObject();
            for(var route:vanilla.getValue().entrySet()) { String furniture=route.getValue().get(0);
                ps.add(route.getKey(),obj("furniture",NAMESPACE+":"+furniture,"rules",obj("ground",obj("rotation",rotationRule(furniture),"alignment","center")),"config",route.getValue().get(1))); }
            items.add(vanilla.getKey(),obj("behavior",obj("type",NAMESPACE+":sneak_place_vanilla_bottle","placements",ps)));
        }
        return items;
    }

    private RuntimeMetrics buildRuntimeCatalogs(Map<String,List<String>> tags, List<EffectRow> effects,
            Map<String,Map<String,List<String>>> registryTags) throws IOException {
        var pressing=new ArrayList<List<Object>>(); var barrel=new ArrayList<List<Object>>(); var shaker=new ArrayList<List<Object>>();
        Path recipes=projectRoot.resolve("src/generated/resources/data/"+NAMESPACE+"/recipes");
        for(Path p:sortedJson(recipes.resolve("pressing_tub"))) { JsonObject d=readJson(p); String stem=stem(p);
            pressing.add(List.of(NAMESPACE+":"+stem,selector(d.getAsJsonObject("ingredient")),d.get("fluid").getAsString(),d.get("fluid_amount").getAsInt(),NAMESPACE+":"+stem)); }
        for(Path p:sortedJson(recipes.resolve("barrel"))) { JsonObject d=readJson(p); String result=d.getAsJsonObject("result").get("item").getAsString(); Integer color=configuredColor(tags,result);
            var row=new ArrayList<Object>(); row.add(NAMESPACE+":"+stem(p));row.add(result);row.add(color==null?"":String.format(Locale.ROOT,"#%06X",color));row.add(selector(d.getAsJsonObject("carrier")));row.add(d.get("fluid").getAsString());row.add(joinSelectors(d.getAsJsonArray("ingredients")));row.add(d.get("unit_time").getAsInt());barrel.add(row); }
        for(Path p:sortedJson(recipes.resolve("shaker"))) { JsonObject d=readJson(p); shaker.add(List.of(NAMESPACE+":"+stem(p),d.getAsJsonObject("result").get("item").getAsString(),joinSelectors(d.getAsJsonArray("ingredients")))); }
        var tagRows=new ArrayList<List<Object>>(); tags.keySet().stream().sorted().forEach(tag->tags.get(tag).forEach(member->tagRows.add(List.of(tag,member))));
        var registryRows=new ArrayList<List<Object>>();
        for(String registry:List.of("block","entity_type")) { Map<String,List<String>> values=registryTags.getOrDefault(registry,Map.of()); values.keySet().stream().sorted().forEach(tag->values.get(tag).forEach(member->registryRows.add(List.of(registry,tag,member)))); }
        Path catalog=outputRoot.resolve("src/paper/resources/catalog");
        writeTsv(catalog.resolve("pressing.tsv"),List.of("recipe","ingredient","fluid","amount","bucket"),pressing);
        writeTsv(catalog.resolve("barrel.tsv"),List.of("recipe","result","tap_color","carrier","fluid","ingredients","unit_ticks"),barrel);
        writeTsv(catalog.resolve("shaker.tsv"),List.of("recipe","result","ingredients"),shaker);
        var effectCells=new ArrayList<List<Object>>(); for(EffectRow e:effects) effectCells.add(List.of(e.item,e.level,e.effect,e.durationTicks,e.amplifier,e.probability));
        writeTsv(catalog.resolve("drink-effects.tsv"),List.of("item","level","effect","duration_ticks","amplifier","probability"),effectCells);
        writeTsv(catalog.resolve("tags.tsv"),List.of("tag","item"),tagRows); writeTsv(catalog.resolve("registry-tags.tsv"),List.of("registry","tag","member"),registryRows);
        writeDefaults(barrel,shaker);
        return new RuntimeMetrics(pressing.size(),barrel.size(),shaker.size(),(int)effects.stream().map(EffectRow::item).distinct().count(),effects.size(),tagRows.size(),registryRows.size());
    }

    public static String selector(JsonObject raw) { if(raw.has("item")) return "item="+normalize(raw.get("item").getAsString()); if(raw.has("tag")) return "tag="+normalize(raw.get("tag").getAsString()); throw new IllegalArgumentException("Unsupported station selector: "+raw); }
    private static String normalize(String id) { boolean hash=id.startsWith("#"); String bare=hash?id.substring(1):id; if(bare.equals("minecraft:chain")) bare="minecraft:iron_chain"; else if(bare.equals("minecraft:grass")) bare="minecraft:short_grass"; return (hash?"#":"")+bare; }
    private static String joinSelectors(JsonArray values) { if(values==null)return ""; var result=new ArrayList<String>(); for(JsonElement e:values) result.add(selector(e.getAsJsonObject())); return String.join(";",result); }

    private JsonArray fixedLore(String id,List<EffectRow> rows) { JsonArray lore=new JsonArray(); var attrs=new ArrayList<List<Object>>(); String full=NAMESPACE+":"+id;
        for(EffectRow e:rows) if(e.item.equals(full)&&e.level==1) { String[] parts=e.effect.split(":",2); String color=HARMFUL.contains(e.effect)?"red":NEUTRAL.contains(e.effect)?"gray":"blue"; String effect=lang("effect."+parts[0]+"."+parts[1]);
            if(e.amplifier>0) effect=lang("potion.withAmplifier",effect,lang("potion.potency."+e.amplifier)); if(e.durationTicks>0) effect=lang("potion.withDuration",effect,duration(e.durationTicks));
            String line="<!i><insert:"+MANAGED_LORE+"><"+color+">"+effect; if(e.probability<1) line+=" <dark_gray>"+lang("tooltip."+NAMESPACE+".drink_effect.chance",number(e.probability*100)+"%"); lore.add(line);
            if(e.probability>=1) { if(e.effect.equals("minecraft:speed")) attrs.add(List.of("attribute.name.movement_speed",.2*(e.amplifier+1),1)); if(e.effect.equals("minecraft:strength")) attrs.add(List.of("attribute.name.attack_damage",3.0*(e.amplifier+1),0)); if(e.effect.equals(NAMESPACE+":high_heels")) attrs.add(List.of("attribute.name.step_height",.5*(e.amplifier+1),0)); if(e.effect.equals(NAMESPACE+":long_reach")){attrs.add(List.of("attribute.name.block_interaction_range",3.0*(e.amplifier+1),0));attrs.add(List.of("attribute.name.entity_interaction_range",3.0*(e.amplifier+1),0));} }
        }
        if(!attrs.isEmpty()){lore.add("<!i><insert:"+MANAGED_LORE+">");lore.add("<!i><insert:"+MANAGED_LORE+"><dark_purple>"+lang("potion.whenDrank"));for(List<Object>a:attrs){double amount=(double)a.get(1);int op=(int)a.get(2);String modifier=lang("attribute.modifier."+(amount>=0?"plus":"take")+"."+op,number(Math.abs(amount)*(op==1||op==2?100:1)),lang((String)a.get(0)));lore.add("<!i><insert:"+MANAGED_LORE+"><"+(amount>=0?"blue":"red")+">"+modifier);}}
        return lore;
    }
    private static String duration(int ticks){int sec=Math.max(1,ticks/20),h=sec/3600,m=(sec%3600)/60,s=sec%60;return h>0?String.format(Locale.ROOT,"%d:%02d:%02d",h,m,s):String.format(Locale.ROOT,"%d:%02d",m,s);}
    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
    private static String lang(String key, String... args) {
        StringBuilder result = new StringBuilder("<lang:").append(key);
        for (String argument : args) result.append(':').append(quote(argument));
        return result.append('>').toString();
    }
    private static String number(double d){return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();}

    private void writeDefaults(List<List<Object>> barrel,List<List<Object>> shaker)throws IOException { var b=new ArrayList<>(List.of("# 首次启动时复制到 plugins/KaleidoscopeTavern/recipes/barrel.yml。","# 数据目录中的副本不会被插件升级覆盖；修改后执行 /kt reload。","# selector 支持 item=<命名空间:物品> 与 tag=<命名空间:标签>。","# tap-color 可选，使用 #RRGGBB 指定龙头灌装时的酒液颜色。","config-version: 1","","# 满桶内容没有匹配 recipes 时生成的保底产物。","fallback:","  id: "+yaml(NAMESPACE+":empty"),"  result: "+yaml(NAMESPACE+":vinegar"),"  unit-ticks: 2400","  output: 16","","recipes:"));
        for(var r:barrel){b.add("  - id: "+yaml(r.get(0)));b.add("    result: "+yaml(r.get(1)));if(!r.get(2).toString().isEmpty())b.add("    tap-color: "+yaml(r.get(2)));b.add("    carrier: "+yaml(r.get(3)));b.add("    fluid: "+yaml(r.get(4)));String ing=r.get(5).toString();if(ing.isEmpty())b.add("    ingredients: []");else{b.add("    ingredients:");for(String x:ing.split(";"))b.add("      - "+yaml(x));}b.add("    unit-ticks: "+r.get(6));}
        var s=new ArrayList<>(List.of("# 首次启动时复制到 plugins/KaleidoscopeTavern/recipes/shaker.yml。","# 数据目录中的副本不会被插件升级覆盖；修改后执行 /kt reload。","# 配方按书写顺序匹配；每份配方可使用 1 至 3 个 selector。","config-version: 1","","# 摇动时间进入特殊区间时使用的产物；signature 也用于普通配方未命中时。","special-results:","  mystery: "+yaml(NAMESPACE+":mystery_cocktail"),"  signature: "+yaml(NAMESPACE+":signature_cocktail"),"","recipes:"));for(var r:shaker){s.add("  - id: "+yaml(r.get(0)));s.add("    result: "+yaml(r.get(1)));s.add("    ingredients:");for(String x:r.get(2).toString().split(";"))s.add("      - "+yaml(x));} writeLines(outputRoot.resolve("src/paper/resources/recipes/barrel.yml"),b);writeLines(outputRoot.resolve("src/paper/resources/recipes/shaker.yml"),s); }
    private static String yaml(Object v){return GSON.toJson(String.valueOf(v));}

    /** v0.0.1 shaker item model: GUI/FIXED uses the 2D icon. During use the
     * native spyglass consumable pose holds the raised arm (vanilla -110°, the
     * source SHAKING centre is -112.5°); the shaker itself waves in the hand
     * through CraftEngine's {@code source}-keyed 16-frame use_cycle. First
     * person keeps the exact v0.0.1 swing; third person mirrors the source
     * SHAKING delta per hand (right -45°·sin/-9°, left +45°·sin/+9°). */
    private JsonObject shakerModel(){return obj("type","minecraft:select","property","display_context","cases",arr(obj("when",arr("gui","fixed"),"model",model("shaker"))),"fallback",obj("type","minecraft:condition","property","minecraft:using_item","on_true",obj("type","minecraft:select","property","display_context","cases",arr(obj("when",arr("firstperson_lefthand","firstperson_righthand"),"model",shakerUseCycle(true,true)),obj("when",arr("thirdperson_righthand"),"model",shakerUseCycle(false,true)),obj("when",arr("thirdperson_lefthand"),"model",shakerUseCycle(false,false))),"fallback",model("shaker_3d")),"on_false",model("shaker_3d")));}
    /** First person is the exact v0.0.1 16-frame use_cycle; third person
     * rotates the held shaker in the spyglass-raised hand. {@code rightHand}
     * selects the source SHAKING branch: right ΔxRot = -45°·sin(1.5t)/zRot
     * -9°, left ΔxRot = +45°·sin(1.5t)/zRot +9°. */
    private JsonObject shakerUseCycle(boolean firstPerson, boolean rightHand){JsonArray entries=new JsonArray();double period=Math.PI*2/1.5;for(int i=0;i<16;i++){double cycle=period*i/16,wave=Math.sin(-cycle*1.5);JsonObject frameModel=obj("type","minecraft:model","path",NAMESPACE+":item/shaker_3d");frameModel.add("transformation",firstPerson?shakerTransform(-15,-wave*.15):shakerSwingTransform(cycle,rightHand));entries.add(obj("threshold",round(cycle,6),"model",frameModel));}return obj("type","minecraft:range_dispatch","property","use_cycle","source",round(period,6),"entries",entries,"fallback",entries.get(0).getAsJsonObject().get("model").deepCopy());}
    private static JsonArray shakerTransform(double rotationDeg,double translationY){double a=Math.toRadians(rotationDeg),c=round(Math.cos(a),8),s=round(Math.sin(a),8);return arr(1.0,0.0,0.0,0.0,0.0,c,-s,round(translationY,8),0.0,s,c,0.0,0.0,0.0,0.0,1.0);}
    private static JsonArray shakerSwingTransform(double cycle, boolean rightHand){int direction=rightHand?1:-1;double xDegrees=direction*-45*Math.sin(1.5*cycle);double zDegrees=direction*-9;return composeRotXZ(xDegrees,zDegrees);}
    private static JsonArray composeRotXZ(double xDegrees,double zDegrees){double x=Math.toRadians(xDegrees),cx=round(Math.cos(x),8),sx=round(Math.sin(x),8);double z=Math.toRadians(zDegrees),cz=round(Math.cos(z),8),sz=round(Math.sin(z),8);return arr(cz,-sz,0.0,0.0,round(cx*sz,8),round(cx*cz,8),-sx,0.0,round(sx*sz,8),round(sx*cz,8),cx,0.0,0.0,0.0,0.0,1.0);}
    private static double round(double x,int n){double p=Math.pow(10,n);return Math.rint(x*p)/p;}

    private void requireItemModel(String id)throws IOException {for(String base:List.of("src/generated/resources","src/main/resources"))if(Files.isRegularFile(projectRoot.resolve(base+"/assets/"+NAMESPACE+"/models/item/"+id+".json")))return;throw new FileNotFoundException("No item model for "+id);}
    private static String itemNameKey(String id,Set<String> placeable,Set<String> keys){String generic=GENERIC_NAMES.get(id);if(generic!=null){if(!keys.contains(generic))throw new IllegalArgumentException("Missing generic item-name translation "+generic);return generic;}for(String p:placeable.contains(id)?List.of("block","item"):List.of("item","block")){String key=p+"."+NAMESPACE+"."+id;if(keys.contains(key))return key;}throw new IllegalArgumentException("No item-name translation for "+NAMESPACE+":"+id);}
    private static String materialFor(String id,Set<String> drinks){if(isDrink(id,drinks))return "potion";if(id.endsWith("_bucket"))return "milk_bucket";return "paper";} private static boolean isDrink(String id,Set<String>d){return d.contains(id)||id.equals("watermelon_juice")||id.equals("signature_cocktail");}
    private static Integer drinkColor(List<String> tags){String p=NAMESPACE+":cocktail_ingredient_";for(String t:tags)if(t.startsWith(p))return DRINK_COLORS.get(t.substring(p.length()));return null;} private static Integer configuredColor(Map<String,List<String>>tags,String id){for(var e:DRINK_COLORS.entrySet())if(tags.getOrDefault(NAMESPACE+":cocktail_ingredient_"+e.getKey(),List.of()).contains(id))return e.getValue();return null;}
    private static String rotationRule(String id){return SIXTEEN_WAY.contains(id)||id.endsWith("_sandwich_board")?"sixteen":DIRECTIONLESS.contains(id)?"north":"four";}
    private static void configureConsumable(JsonObject config,JsonObject value){components(config.getAsJsonObject("data")).add("minecraft:consumable",value.deepCopy());JsonObject cbd=config.has("client_bound_data")?config.getAsJsonObject("client_bound_data"):new JsonObject();config.add("client_bound_data",cbd);JsonObject c=cbd.has("components")?cbd.getAsJsonObject("components"):new JsonObject();cbd.add("components",c);c.add("minecraft:consumable",value.deepCopy());}
    private static JsonObject components(JsonObject data){if(!data.has("components"))data.add("components",new JsonObject());return data.getAsJsonObject("components");} private static JsonObject settings(JsonObject c){if(!c.has("settings"))c.add("settings",new JsonObject());return c.getAsJsonObject("settings");}
    private static boolean isSofa(String id){return id.endsWith("_sofa")&&SOFA_COLORS.contains(id.substring(0,id.length()-5));} private static Set<String> sofaBlocks(){var s=new HashSet<String>();for(String c:SOFA_COLORS)s.add(c+"_sofa");return s;}

    private static JsonObject obj(Object... values){JsonObject o=new JsonObject();for(int i=0;i<values.length;i+=2)o.add((String)values[i],json(values[i+1]));return o;} private static JsonElement json(Object v){if(v==null)return JsonNull.INSTANCE;if(v instanceof JsonElement e)return e;if(v instanceof Boolean b)return new JsonPrimitive(b);if(v instanceof Number n)return new JsonPrimitive(n);return new JsonPrimitive(String.valueOf(v));} private static JsonArray arr(Object...v){JsonArray a=new JsonArray();for(Object x:v)a.add(json(x));return a;} private static JsonArray strings(Collection<String>v){JsonArray a=new JsonArray();v.forEach(a::add);return a;} private static JsonArray arrayOf(Collection<? extends JsonElement>v){JsonArray a=new JsonArray();v.forEach(a::add);return a;} private static JsonObject model(String id){return obj("type","minecraft:model","path",NAMESPACE+":item/"+id);}
    private static JsonObject readJson(Path p)throws IOException{String text=Files.readString(p,StandardCharsets.UTF_8);if(!text.isEmpty()&&text.charAt(0)=='\uFEFF')text=text.substring(1);return JsonParser.parseString(text).getAsJsonObject();} private static List<Path> sortedJson(Path dir)throws IOException{if(!Files.isDirectory(dir))return List.of();try(var s=Files.list(dir)){return s.filter(p->p.getFileName().toString().endsWith(".json")).sorted(Comparator.comparing(p->p.getFileName().toString())).toList();}} private static String stem(Path p){String n=p.getFileName().toString();return n.substring(0,n.length()-5);}
    private static void writeJson(Path p,JsonElement e)throws IOException{Files.createDirectories(p.getParent());try(Writer w=Files.newBufferedWriter(p,StandardCharsets.UTF_8)){GSON.toJson(e,w);w.write("\n");}} private static void writeLines(Path p,List<String>lines)throws IOException{Files.createDirectories(p.getParent());Files.writeString(p,String.join("\n",lines)+"\n",StandardCharsets.UTF_8);}
    private static void writeTsv(Path p,List<String>header,List<List<Object>>rows)throws IOException{Files.createDirectories(p.getParent());StringBuilder b=new StringBuilder(String.join("\t",header)).append('\n');for(var row:rows){for(int i=0;i<row.size();i++){if(i>0)b.append('\t');b.append(String.valueOf(row.get(i)).replace('\t',' ').replace('\r',' ').replace('\n',' '));}b.append('\n');}Files.writeString(p,b,StandardCharsets.UTF_8);}
    private static Map<String, JsonObject> copyJsonObjects(Map<String, JsonObject> source) {
        var result = new LinkedHashMap<String, JsonObject>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key), Objects.requireNonNull(value).deepCopy()));
        return Collections.unmodifiableMap(result);
    }
    private static Map<String, List<String>> copyLists(Map<String, List<String>> source) {
        var result = new LinkedHashMap<String, List<String>>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key), List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Map<String, Map<String, List<String>>> copyNestedLists(
            Map<String, Map<String, List<String>>> source) {
        var result = new LinkedHashMap<String, Map<String, List<String>>>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key), copyLists(value)));
        return Collections.unmodifiableMap(result);
    }

    private static <T> Set<T> union(Set<T>a,Set<T>b){var r=new HashSet<T>(a);r.addAll(b);return Set.copyOf(r);} private static <T> Set<T> difference(Set<T>a,Set<T>b){var r=new HashSet<T>(a);r.removeAll(b);return Set.copyOf(r);}
    private static Map<String,Integer> drinkColors(){var m=new LinkedHashMap<String,Integer>();String[]n={"black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white"};int[]v={0,0x0000AA,0x00AA00,0x00AAAA,0xAA0000,0xAA00AA,0xFFAA00,0xAAAAAA,0x555555,0x5555FF,0x55FF55,0x55FFFF,0xFF5555,0xFF55FF,0xFFFF55,0xFFFFFF};for(int i=0;i<n.length;i++)m.put(n[i],v[i]);return Collections.unmodifiableMap(m);}
    private static Map<String,String> genericNames(){var m=new HashMap<String,String>();for(String p:PAINTINGS)m.put(p,"block."+NAMESPACE+".painting");for(String id:List.of("base_sandwich_board","grass_sandwich_board","allium_sandwich_board","azure_bluet_sandwich_board","cornflower_sandwich_board","orchid_sandwich_board","peony_sandwich_board","pink_petals_sandwich_board","pitcher_plant_sandwich_board","poppy_sandwich_board","sunflower_sandwich_board","torchflower_sandwich_board","tulip_sandwich_board","wither_rose_sandwich_board"))m.put(id,"block."+NAMESPACE+".sandwich_board");return Map.copyOf(m);}
    private static Map<String,Map<String,List<String>>> vanillaPlacements(){var m=new LinkedHashMap<String,Map<String,List<String>>>();var potion = new LinkedHashMap<String,List<String>>(); potion.put("water",List.of("water_bottle","bottle-placement.water")); potion.put("potion",List.of("potion_bottle","bottle-placement.potion")); m.put("minecraft:potion",Collections.unmodifiableMap(potion));m.put("minecraft:honey_bottle",Map.of("honey",List.of("honey_bottle","bottle-placement.honey")));m.put("minecraft:dragon_breath",Map.of("dragon_breath",List.of("dragon_breath_bottle","bottle-placement.dragon-breath")));m.put("minecraft:experience_bottle",Map.of("experience",List.of("xp_bottle","bottle-placement.experience")));return Collections.unmodifiableMap(m);}
}
