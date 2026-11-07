package dev.nightmode.chromalight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.Reader;
import java.util.*;

/**
 * Light packs: ordinary client resource packs with {@code assets/<namespace>/chromalight/lights/*.json}.
 * <pre>
 * {
 *   "blocks":   { "mymod:lamp": "#FF8800", "mymod:rgb_lamp[color=red]": "#FF0000", "#mymod:crystals": "#80C0FF" },
 *   "flicker":  [ "mymod:brazier", "#mymod:fires" ],
 *   "items":    { "mymod:glow_wand": "#80FFFF" },         // held/dropped dynamic lights
 *   "entities": { "mymod:fire_spirit": "#FF8040" },       // entity dynamic lights
 *   "entity_blacklist": [ "somemod:*", "othermod:boss" ]  // never tinted by coloured light
 * }
 * </pre>
 * Packs higher in the resource-pack list win. Colours changed in the user config win over packs.
 */
public final class LightPacks extends SimplePreparableReloadListener<List<LightPalette.Layer>> {
    public static final LightPacks INSTANCE = new LightPacks();
    private static final FileToIdConverter FILES = FileToIdConverter.json("chromalight/lights");
    private static volatile List<LightPalette.Layer> packs = List.of();
    private LightPacks() {}

    public static int packFiles() { return packs.size(); }

    @Override
    protected List<LightPalette.Layer> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Integer> order = new HashMap<>();
        int index = 0;
        for (var pack : manager.listPacks().toList()) order.put(pack.packId(), index++);
        record Found(int pack, ResourceLocation id, Resource resource) {}
        List<Found> found = new ArrayList<>();
        for (var stack : FILES.listMatchingResourceStacks(manager).entrySet())
            for (Resource resource : stack.getValue())
                found.add(new Found(order.getOrDefault(resource.sourcePackId(), 0), stack.getKey(), resource));
        found.sort(Comparator.comparingInt(Found::pack).thenComparing(f -> f.id().toString()));
        List<LightPalette.Layer> layers = new ArrayList<>();
        for (Found f : found) {
            String origin = f.resource().sourcePackId() + "/" + f.id();
            try (Reader reader = f.resource().openAsReader()) {
                layers.add(parse(JsonParser.parseReader(reader).getAsJsonObject(), origin));
            } catch (Exception e) {
                System.err.println("[ChromaLight] Skipping light pack file " + origin + ": " + e);
            }
        }
        return List.copyOf(layers);
    }

    @Override
    protected void apply(List<LightPalette.Layer> layers, ResourceManager manager, ProfilerFiller profiler) {
        packs = layers;
        AutoColors.invalidate();
        FolderPacks.reload(false);
        NativeLighting.rebuildPalette();
        System.out.println("[ChromaLight] Light packs: " + layers.size() + " file(s)");
    }

    static LightPalette.Layer parse(JsonObject json, String origin) {
        return new LightPalette.Layer(origin, colors(json, "blocks", origin), strings(json, "flicker"),
                colors(json, "items", origin), colors(json, "entities", origin), strings(json, "entity_blacklist"));
    }

    private static List<Map.Entry<String, Integer>> colors(JsonObject json, String key, String origin) {
        if (!json.has(key)) return List.of();
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        for (var entry : json.getAsJsonObject(key).entrySet()) {
            Integer rgb = color(entry.getValue());
            if (rgb == null) System.err.println("[ChromaLight] Ignoring " + key + "." + entry.getKey() + " in " + origin + ": use \"#RRGGBB\"");
            else out.add(Map.entry(entry.getKey(), rgb));
        }
        return out;
    }
    private static List<String> strings(JsonObject json, String key) {
        if (!json.has(key)) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray(key)) out.add(element.getAsString());
        return out;
    }
    static Integer color(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) return value.getAsInt() & 0xffffff;
        String text = value.getAsString().trim();
        if (text.startsWith("#")) text = text.substring(1);
        return text.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(text, 16) : null;
    }
    static Integer color(String text) {
        text = text.trim();
        if (text.startsWith("#")) text = text.substring(1);
        return text.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(text, 16) : null;
    }

    /** Bundled defaults, resource-pack light packs, the selected folder pack, then the user's own config edits. */
    static List<LightPalette.Layer> layers(LightSettings settings) {
        Properties bundled = LightSettings.defaults();
        List<Map.Entry<String, Integer>> baseBlocks = new ArrayList<>(), userBlocks = new ArrayList<>();
        for (String key : settings.properties.stringPropertyNames()) {
            boolean ominous = key.startsWith("ominous_color.");
            if (!ominous && !key.startsWith("color.")) continue;
            Integer rgb = color(settings.properties.getProperty(key));
            if (rgb == null) { System.err.println("[ChromaLight] Ignoring invalid " + key); continue; }
            String id = key.substring(ominous ? 14 : 6);
            String rule = ominous ? id + "[ominous=true]" : id;
            String original = bundled.getProperty(key);
            Integer bundledRgb = original == null ? null : color(original);
            (rgb.equals(bundledRgb) ? baseBlocks : userBlocks).add(Map.entry(rule, rgb));
        }
        List<String> flicker = split(settings.flickerBlocks);
        boolean defaultFlicker = settings.flickerBlocks.equals(LightSettings.DEFAULT_FLICKER_BLOCKS);
        List<LightPalette.Layer> out = new ArrayList<>();
        out.add(new LightPalette.Layer("bundled defaults", baseBlocks, defaultFlicker ? flicker : List.of(), List.of(), List.of(), List.of()));
        out.addAll(packs);
        out.addAll(FolderPacks.layers());
        out.add(new LightPalette.Layer("config/chromalight.properties", userBlocks, defaultFlicker ? List.of() : flicker,
                List.of(), List.of(), split(settings.entityBlacklist)));
        return out;
    }
    private static List<String> split(String list) {
        List<String> out = new ArrayList<>();
        for (String part : list.split(",")) if (!part.isBlank()) out.add(part.trim());
        return out;
    }
}
