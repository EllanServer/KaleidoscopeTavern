package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.validation;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Validates generated configuration graphs, assets, catalogs and managed crop/default files. */
public final class GeneratedPackValidator {
  private static final String NS="kaleidoscope_tavern";
  private static final Set<String> VANILLA=Set.of("minecraft:potion","minecraft:honey_bottle","minecraft:dragon_breath","minecraft:experience_bottle");
  private static final Set<String> OBSOLETE=Set.of("minecraft:chain","minecraft:grass");
  private GeneratedPackValidator() {}

  public static Result validate(Path root) throws IOException {
    Path config=root.resolve("src/paper/pack/configuration");
    JsonObject items=load(config,"items.json","items"), renders=load(config,"render-items.json","items");
    JsonObject blocks=load(config,"blocks.json","blocks"), furniture=load(config,"furniture.json","furniture");
    JsonObject recipes=load(config,"recipes.json","recipes"), categories=load(config,"categories.json","categories");
    List<String> publicIds=items.keySet().stream().filter(id->id.startsWith(NS+":" )).toList();
    Set<String> vanilla=new LinkedHashSet<>(items.keySet()); vanilla.removeAll(publicIds);
    req(publicIds.size()==157,"Expected 157 public items, found "+publicIds.size());
    req(vanilla.equals(VANILLA),"Vanilla bottle CE item extensions drifted: "+vanilla);
    req(blocks.size()==44,"Expected 44 grid/state blocks, found "+blocks.size());
    req(furniture.size()==116,"Expected 116 furniture definitions, found "+furniture.size());
    req(renders.size()==414,"Expected 414 private render items, found "+renders.size());
    req(recipes.size()==114,"Expected 114 crafting recipes, found "+recipes.size());
    validateModels(root,items,renders,Set.copyOf(publicIds));
    validateBlocks(items,renders,blocks);
    validateFurniture(items,renders,furniture);
    validateRecipes(items,renders,recipes);
    validateCategory(categories,publicIds);
    Map<String,Integer> catalogs=validateCatalogs(root);
    validateRecipeDefaults(root,catalogs);
    validateStationRecipeMirrors(root);
    validateCustomCrops(root,blocks);
    validatePng(root.resolve("src/paper/pack/resourcepack/assets/"+NS+"/textures/font/shaker/bar.png"),181,18);
    validatePng(root.resolve("src/paper/pack/resourcepack/assets/"+NS+"/textures/font/shaker/pointer.png"),11,14);
    return new Result(publicIds.size(),blocks.size(),furniture.size(),renders.size(),recipes.size(),3,catalogs);
  }

  public record Documents(JsonObject items, JsonObject renderItems, JsonObject blocks,
                         JsonObject furniture, JsonObject recipes, JsonObject categories) {}

  public static Documents documents(Path root) throws IOException {
    Path config=root.resolve("src/paper/pack/configuration");
    return new Documents(load(config,"items.json","items"), load(config,"render-items.json","items"),
            load(config,"blocks.json","blocks"), load(config,"furniture.json","furniture"),
            load(config,"recipes.json","recipes"), load(config,"categories.json","categories"));
  }

  private static JsonObject load(Path dir,String name,String key)throws IOException{
    Path file=dir.resolve(name); req(Files.isRegularFile(file),"Missing generated configuration: "+file);
    JsonElement e=JsonParser.parseString(text(file)); req(e.isJsonObject(),name+": root must be an object");
    JsonObject root=e.getAsJsonObject(); req(root.size()==1&&root.has(key)&&root.get(key).isJsonObject(),name+": expected only root key '"+key+"'");
    return root.getAsJsonObject(key);
  }
  private static void validateModels(Path root,JsonObject items,JsonObject renders,Set<String> publicIds){
    for(JsonObject source:List.of(items,renders)) for(var en:source.entrySet()){
      if(source==items&&!publicIds.contains(en.getKey()))continue;
      Set<String> models=new LinkedHashSet<>(); collectItemModels(en.getValue().getAsJsonObject().get("model"),models);
      req(!models.isEmpty(),en.getKey()+": item definition has no block-model path");
      for(String model:models) req(asset(root,model,"models",".json"),en.getKey()+": missing model '"+model+"'");
    }
  }
  private static void collectItemModels(JsonElement e,Set<String> out){
    if(e==null)return; if(e.isJsonObject()){JsonObject o=e.getAsJsonObject(); JsonElement t=o.get("type"),p=o.get("path");
      if(t!=null&&p!=null&&p.isJsonPrimitive()&&Set.of("model","minecraft:model").contains(t.getAsString()))out.add(p.getAsString());
      for(JsonElement c:o.asMap().values())collectItemModels(c,out);
    }else if(e.isJsonArray())for(JsonElement c:e.getAsJsonArray())collectItemModels(c,out);
  }
  private static boolean asset(Path root,String id,String folder,String suffix){
    int c=id.indexOf(':'); req(c>0,"Invalid resource id: "+id); Path rel=Path.of(id.substring(0,c),folder,id.substring(c+1)+suffix);
    return List.of("src/paper/pack/resourcepack/assets","src/generated/resources/assets","src/main/resources/assets").stream().anyMatch(p->Files.isRegularFile(root.resolve(p).resolve(rel)));
  }
  private static void validateBlocks(JsonObject items,JsonObject renders,JsonObject blocks){
    for(var en:blocks.entrySet()){JsonObject b=obj(en.getValue(),en.getKey()); JsonObject settings=optionalObj(b,"settings");
      if(settings.has("item"))req(items.has(settings.get("item").getAsString()),en.getKey()+": missing bound item "+settings.get("item"));
      Collection<JsonElement> apps;
      if(b.has("states")){JsonObject s=obj(b.get("states"),en.getKey()+".states");apps=obj(s.get("appearances"),en.getKey()+".appearances").asMap().values();}
      else apps=List.of(b.get("state"));
      for(JsonElement ae:apps){JsonObject a=obj(ae,en.getKey()+" appearance"); JsonElement re=a.get("entity_renderer");
        if(re==null){req(en.getKey().equals(NS+":chalkboard"),en.getKey()+": missing entity renderer");continue;}
        String id=str(obj(re,en.getKey()+" renderer"),"item"); req(renders.has(id),en.getKey()+": missing renderer item "+id);}
    }
  }
  private static void validateFurniture(JsonObject items,JsonObject renders,JsonObject furniture){
    for(var en:furniture.entrySet()){JsonObject f=obj(en.getValue(),en.getKey()),settings=optionalObj(f,"settings");
      if(settings.has("item"))req(items.has(settings.get("item").getAsString()),en.getKey()+": missing bound item "+settings.get("item"));
      JsonObject variants=obj(f.get("variants"),en.getKey()+" variants");req(!variants.isEmpty(),en.getKey()+": has no variants");
      for(var ve:variants.entrySet()){JsonObject v=obj(ve.getValue(),en.getKey()+"/"+ve.getKey());req(v.has("hitboxes")&&v.get("hitboxes").isJsonArray()&&!v.getAsJsonArray("hitboxes").isEmpty(),en.getKey()+"/"+ve.getKey()+": has no hitbox");
        if(v.has("elements"))for(JsonElement ee:v.getAsJsonArray("elements")){String id=str(obj(ee,"element"),"item");req(renders.has(id),en.getKey()+"/"+ve.getKey()+": missing renderer item "+id);}}
    }
  }
  private static void validateRecipes(JsonObject items,JsonObject renders,JsonObject recipes){
    Set<String> known=new HashSet<>(items.keySet());known.addAll(renders.keySet());
    for(var en:recipes.entrySet()){JsonObject r=obj(en.getValue(),en.getKey());String type=str(r,"type");req(type.equals("shaped")||type.equals("shapeless"),en.getKey()+": unsupported standard recipe type '"+type+"'");
      req(r.has("unlock_on_ingredient_obtained")&&r.get("unlock_on_ingredient_obtained").getAsBoolean(),en.getKey()+": must set unlock_on_ingredient_obtained to true");req(!r.has("unlock_on_join"),en.getKey()+": must not use unlock_on_join");
      for(String s:nestedStrings(r))for(String old:OBSOLETE)req(!s.contains(old),en.getKey()+": obsolete vanilla id "+old);
      String result=str(obj(r.get("result"),en.getKey()+" result"),"id");if(result.startsWith(NS+":"))req(known.contains(result),en.getKey()+": unknown result "+result);
    }
  }
  private static void validateCategory(JsonObject categories,List<String> ids){JsonObject all=obj(categories.get(NS+":all"),"all category");JsonArray list=all.getAsJsonArray("list");req(list!=null,"Missing all category list");List<String> got=new ArrayList<>();for(JsonElement e:list)got.add(e.getAsString());req(got.equals(ids),"The CraftEngine category is not in registry order");}
  private static Map<String,Integer> validateCatalogs(Path root)throws IOException{
    Map<String,Integer> expected=new LinkedHashMap<>();expected.put("pressing.tsv",6);expected.put("barrel.tsv",24);expected.put("shaker.tsv",12);expected.put("drink-effects.tsv",null);expected.put("tags.tsv",null);expected.put("registry-tags.tsv",null);
    Map<String,Integer> result=new LinkedHashMap<>();Path dir=root.resolve("src/paper/resources/catalog");
    for(var en:expected.entrySet()){List<String[]> rows=tsv(dir.resolve(en.getKey()));for(String[] row:rows)for(String cell:row)for(String old:OBSOLETE)req(!cell.contains(old),en.getKey()+": obsolete vanilla id "+old);if(en.getValue()!=null)req(rows.size()==en.getValue(),en.getKey()+": expected "+en.getValue()+" rows, found "+rows.size());result.put(en.getKey(),rows.size());}
    List<String[]> effects=tsv(dir.resolve("drink-effects.tsv"));req(effects.stream().map(r->r[0]).distinct().count()==37,"Expected drink effects for 37 items");return Map.copyOf(result);
  }
  private static List<String[]> tsv(Path file)throws IOException{List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);req(!lines.isEmpty(),file.getFileName()+" is empty");int width=lines.get(0).split("\t",-1).length;List<String[]> rows=new ArrayList<>();for(int i=1;i<lines.size();i++)if(!lines.get(i).isEmpty()){String[] r=lines.get(i).split("\t",-1);req(r.length==width,file.getFileName()+" contains a malformed row");rows.add(r);}return rows;}
  private static void validateRecipeDefaults(Path root, Map<String, Integer> counts) throws IOException {
    Path dir = root.resolve("src/paper/resources/recipes");
    String barrel = text(dir.resolve("barrel.yml"));
    String shaker = text(dir.resolve("shaker.yml"));
    for (String token : List.of(
        "config-version: 1",
        "id: \"kaleidoscope_tavern:empty\"",
        "result: \"kaleidoscope_tavern:vinegar\"",
        "unit-ticks: 2400",
        "output: 16")) {
      req(barrel.contains(token), "barrel.yml fallback is missing " + token);
    }
    for (String token : List.of(
        "config-version: 1",
        "mystery: \"kaleidoscope_tavern:mystery_cocktail\"",
        "signature: \"kaleidoscope_tavern:signature_cocktail\"")) {
      req(shaker.contains(token), "shaker.yml special-results is missing " + token);
    }
    req(countPrefix(barrel, "  - id: ") == counts.get("barrel.tsv"),
        "Bundled barrel.yml defaults no longer mirror catalog/barrel.tsv");
    req(countPrefix(shaker, "  - id: ") == counts.get("shaker.tsv"),
        "Bundled shaker.yml defaults no longer mirror catalog/shaker.tsv");
  }
  private static void validateStationRecipeMirrors(Path root)throws IOException{
    Path recipes=root.resolve("src/paper/resources/recipes"),catalog=root.resolve("src/paper/resources/catalog");
    List<Map<String,Object>> expectedBarrel=new ArrayList<>();
    for(String[] row:tsv(catalog.resolve("barrel.tsv"))){Map<String,Object> v=new LinkedHashMap<>();v.put("id",row[0]);v.put("result",row[1]);if(!row[2].isEmpty())v.put("tap-color",row[2]);v.put("carrier",row[3]);v.put("fluid",row[4]);v.put("ingredients",row[5].isEmpty()?List.of():List.of(row[5].split(";",-1)));v.put("unit-ticks",Integer.parseInt(row[6]));expectedBarrel.add(v);}
    List<Map<String,Object>> expectedShaker=new ArrayList<>();
    for(String[] row:tsv(catalog.resolve("shaker.tsv"))){Map<String,Object> v=new LinkedHashMap<>();v.put("id",row[0]);v.put("result",row[1]);v.put("ingredients",List.of(row[2].split(";",-1)));expectedShaker.add(v);}
    req(stationRows(recipes.resolve("barrel.yml")).equals(expectedBarrel),"Bundled barrel.yml defaults no longer mirror catalog/barrel.tsv");
    req(stationRows(recipes.resolve("shaker.yml")).equals(expectedShaker),"Bundled shaker.yml defaults no longer mirror catalog/shaker.tsv");
  }
  private static List<Map<String,Object>> stationRows(Path file)throws IOException{
    List<Map<String,Object>> rows=new ArrayList<>();Map<String,Object> current=null;boolean ingredients=false;
    for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){
      if(line.startsWith("  - id: ")){if(current!=null)rows.add(current);current=new LinkedHashMap<>();current.put("id",quoted(line.substring(8),file));current.put("ingredients",new ArrayList<String>());ingredients=false;continue;}
      if(current==null)continue;if(line.startsWith("    ingredients:")){ingredients=true;continue;}
      if(ingredients&&line.startsWith("      - ")){@SuppressWarnings("unchecked")List<String> values=(List<String>)current.get("ingredients");values.add(quoted(line.substring(8),file));continue;}ingredients=false;
      for(String key:List.of("result","tap-color","carrier","fluid")){String prefix="    "+key+": ";if(line.startsWith(prefix)){current.put(key,quoted(line.substring(prefix.length()),file));break;}}
      if(line.startsWith("    unit-ticks: "))current.put("unit-ticks",Integer.parseInt(line.substring(16)));
    }if(current!=null)rows.add(current);return rows;
  }
  private static String quoted(String value,Path owner){JsonElement e=JsonParser.parseString(value);req(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString(),owner+": expected quoted scalar");return e.getAsString();}
  private static void validateCustomCrops(Path root,JsonObject blocks)throws IOException{String y=text(root.resolve("src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml"));Map<String,String> crops=Map.of("kaleidoscope_tavern_grape","grape_crop","kaleidoscope_tavern_ice_grape","ice_grape_crop","kaleidoscope_tavern_gold_grape","gold_grape_crop");for(var c:crops.entrySet()){req(Pattern.compile("(?m)^"+Pattern.quote(c.getKey())+":$").matcher(y).find(),c.getKey()+": managed CustomCrops section is missing");for(int i=0;i<6;i++){String id=NS+":"+(i==0?c.getValue():"_crop/"+c.getValue()+"/stage_"+i);req(blocks.has(id),c.getKey()+": missing stage block "+id);req(y.contains("model: "+id),c.getKey()+": missing CustomCrops model "+id);JsonObject b=blocks.getAsJsonObject(id);req(!b.has("states"),id+": CustomCrops stages must be addressable by block id");req(nestedStrings(b.get("behavior")).contains(NS+":hanging_grape_crop"),id+": hanging crop survival guard is missing");}}req(countPrefix(y,"  custom-bone-meal:")==3,"Every managed grape crop must delegate bone meal to CustomCrops");}
  private static void validatePng(Path p,int w,int h)throws IOException{byte[] b=Files.readAllBytes(p);req(b.length>=24&&b[0]==(byte)0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G'&&b[12]=='I'&&b[13]=='H'&&b[14]=='D'&&b[15]=='R',p+": missing a valid PNG IHDR header");ByteBuffer bb=ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN);req(bb.getInt(16)==w&&bb.getInt(20)==h,p+": expected "+w+"x"+h+" PNG");}
  private static List<String> nestedStrings(JsonElement e){List<String> out=new ArrayList<>();nested(e,out);return out;}private static void nested(JsonElement e,List<String> out){if(e==null)return;if(e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString())out.add(e.getAsString());else if(e.isJsonArray())for(JsonElement x:e.getAsJsonArray())nested(x,out);else if(e.isJsonObject())for(JsonElement x:e.getAsJsonObject().asMap().values())nested(x,out);}
  private static int countPrefix(String s,String prefix){int n=0;for(String l:s.lines().toList())if(l.startsWith(prefix))n++;return n;}
  private static JsonObject obj(JsonElement e,String owner){req(e!=null&&e.isJsonObject(),owner+": expected object");return e.getAsJsonObject();}private static JsonObject optionalObj(JsonObject o,String k){return o.has(k)?obj(o.get(k),k):new JsonObject();}private static String str(JsonObject o,String k){req(o.has(k)&&o.get(k).isJsonPrimitive(),"expected string "+k);return o.get(k).getAsString();}
  private static String text(Path p)throws IOException{String s=Files.readString(p,StandardCharsets.UTF_8);return s.startsWith("\uFEFF")?s.substring(1):s;}private static void req(boolean b,String m){if(!b)throw new ValidationException(m);}
  public record Result(int items,int blocks,int furniture,int appearances,int recipes,int customCrops,Map<String,Integer> catalogs){}
  public static final class ValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ValidationException(String message) { super(message); }
  }
}
