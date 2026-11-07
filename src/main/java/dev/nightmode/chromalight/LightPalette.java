package dev.nightmode.chromalight;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.*;

/**
 * Resolved colour rules. Immutable after construction; safe for concurrent meshing workers.
 * Layers, lowest priority first: bundled config defaults, light packs (resource-pack order),
 * then config entries the user changed away from the bundled default.
 */
public final class LightPalette {
    public static final int NONE = -1;
    private final Reference2IntOpenHashMap<BlockState> stateColors = new Reference2IntOpenHashMap<>();
    private final ReferenceOpenHashSet<BlockState> flickerStates = new ReferenceOpenHashSet<>();
    private final Reference2IntOpenHashMap<Item> itemColors = new Reference2IntOpenHashMap<>();
    private final Reference2IntOpenHashMap<EntityType<?>> entityColors = new Reference2IntOpenHashMap<>();
    private final ReferenceOpenHashSet<EntityType<?>> entityBlacklist = new ReferenceOpenHashSet<>();
    private final int rules;

    /** One source of rules: the bundled/user config or a light-pack JSON file. */
    public record Layer(String origin, List<Map.Entry<String, Integer>> blocks, List<String> flicker,
                        List<Map.Entry<String, Integer>> items, List<Map.Entry<String, Integer>> entities,
                        List<String> entityBlacklist) {}

    public LightPalette(List<Layer> layers) {
        stateColors.defaultReturnValue(NONE);
        itemColors.defaultReturnValue(NONE);
        entityColors.defaultReturnValue(NONE);
        int count = 0;
        for (Layer layer : layers) {
            // Broad rules first so specific ones in the same layer win: tags, blocks, states.
            for (int pass = 0; pass < 3; pass++) for (var rule : layer.blocks()) {
                String key = rule.getKey();
                int kind = key.startsWith("#") ? 0 : key.contains("[") ? 2 : 1;
                if (kind != pass) continue;
                if (applyBlock(key, rule.getValue(), layer.origin())) count++;
            }
            for (String key : layer.flicker()) for (Block block : blocks(key, layer.origin()))
                flickerStates.addAll(block.getStateDefinition().getPossibleStates());
            for (var rule : layer.items()) for (Item item : resolve(BuiltInRegistries.ITEM, Registries.ITEM, rule.getKey(), layer.origin()))
                itemColors.put(item, rule.getValue() & 0xffffff);
            for (var rule : layer.entities()) for (EntityType<?> type : resolve(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE, rule.getKey(), layer.origin()))
                entityColors.put(type, rule.getValue() & 0xffffff);
            for (String key : layer.entityBlacklist()) entityBlacklist.addAll(entityPattern(key, layer.origin()));
        }
        rules = count;
    }

    private boolean applyBlock(String key, int rgb, String origin) {
        rgb &= 0xffffff;
        int bracket = key.indexOf('[');
        if (bracket < 0) {
            List<Block> targets = blocks(key, origin);
            for (Block block : targets) for (BlockState state : block.getStateDefinition().getPossibleStates()) stateColors.put(state, rgb);
            return !targets.isEmpty();
        }
        if (!key.endsWith("]")) { warn(origin, key, "unclosed state block"); return false; }
        List<Block> targets = blocks(key.substring(0, bracket), origin);
        Map<String, String> wanted = new HashMap<>();
        for (String pair : key.substring(bracket + 1, key.length() - 1).split(",")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) { warn(origin, key, "state entries need name=value"); return false; }
            wanted.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        boolean any = false;
        for (Block block : targets) for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            if (matches(state, wanted)) { stateColors.put(state, rgb); any = true; }
        }
        return any;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean matches(BlockState state, Map<String, String> wanted) {
        for (var entry : wanted.entrySet()) {
            Property property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null || !property.getName(state.getValue(property)).equals(entry.getValue())) return false;
        }
        return true;
    }

    private static List<Block> blocks(String key, String origin) {
        return resolve(BuiltInRegistries.BLOCK, Registries.BLOCK, key, origin);
    }

    /** "ns:id" or "#ns:tag". Unknown ids are skipped quietly: packs may target mods that are not installed. */
    private static <T> List<T> resolve(Registry<T> registry, ResourceKey<? extends Registry<T>> key, String raw, String origin) {
        String id = raw.trim();
        try {
            if (id.startsWith("#")) {
                List<T> out = new ArrayList<>();
                for (Holder<T> holder : registry.getTagOrEmpty(TagKey.create(key, ResourceLocation.parse(id.substring(1))))) out.add(holder.value());
                return out;
            }
            ResourceLocation location = ResourceLocation.parse(id);
            return registry.containsKey(location) ? List.of(registry.get(location)) : List.of();
        } catch (RuntimeException e) {
            warn(origin, raw, e.getMessage());
            return List.of();
        }
    }

    /** "modid:*", "modid:name" or "#modid:tag". */
    private static List<EntityType<?>> entityPattern(String raw, String origin) {
        String id = raw.trim();
        if (id.endsWith(":*")) {
            String namespace = id.substring(0, id.length() - 2);
            List<EntityType<?>> out = new ArrayList<>();
            for (var entry : BuiltInRegistries.ENTITY_TYPE.entrySet())
                if (entry.getKey().location().getNamespace().equals(namespace)) out.add(entry.getValue());
            return out;
        }
        return resolve(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE, id, origin);
    }

    private static void warn(String origin, String key, String reason) {
        System.err.println("[ChromaLight] Ignoring light rule '" + key + "' from " + origin + ": " + reason);
    }

    /** Rule colour, else the texture-derived colour, else white (untinted). */
    public int paletteColor(BlockState state) {
        int rgb = stateColors.getInt(state);
        if (rgb != NONE) return rgb;
        return LightingControl.autoColors() ? AutoColors.color(state) : 0xffffff;
    }
    public boolean hasColor(BlockState state) { return stateColors.containsKey(state); }
    /** Fire-like emitters that may receive the optional flicker animation. */
    public boolean flickers(BlockState state) { return flickerStates.contains(state); }
    public int itemColor(Item item) { return itemColors.getInt(item); }
    public int entityColor(EntityType<?> type) { return entityColors.getInt(type); }
    public boolean entityBlacklisted(EntityType<?> type) { return entityBlacklist.contains(type); }
    public int rules() { return rules; }
}
