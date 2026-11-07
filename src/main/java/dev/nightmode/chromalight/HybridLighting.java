package dev.nightmode.chromalight;

import dev.nightmode.chromalight.core.HybridLightTable;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-thread source registry; immutable snapshots are consumed by meshing workers.
 * Realtime colour lives on channels (colour-table sources). One channel can drive any number
 * of blocks: a single position, every block of a type, or one flicker colour/phase.
 */
public final class HybridLighting {
    public static final HybridLightTable TABLE = new HybridLightTable();
    public record Snapshot(long epoch, Long2IntMap baked, Long2ObjectMap<HybridLightTable.Source> realtime,
                           Reference2ObjectMap<Block, HybridLightTable.Source> types) {}
    private static final Long2IntOpenHashMap BAKED = new Long2IntOpenHashMap();
    private static final Long2ObjectOpenHashMap<HybridLightTable.Source> REALTIME = new Long2ObjectOpenHashMap<>();
    private static final Reference2ObjectOpenHashMap<Block, HybridLightTable.Source> TYPES = new Reference2ObjectOpenHashMap<>();
    private static final Map<HybridLightTable.Source, Rainbow> RAINBOW = new IdentityHashMap<>();
    // Created lazily by meshing workers: key = baked RGB | phase << 24.
    private static final ConcurrentHashMap<Integer, HybridLightTable.Source> FLICKER = new ConcurrentHashMap<>();
    static final int FLICKER_PHASES = 4;
    // Shared cycling-hue channels for block-entity rainbow lights (e.g. Ars Nouveau).
    private static final HybridLightTable.Source[] PROVIDER_RAINBOW = new HybridLightTable.Source[BlockColorProviders.RAINBOW_PHASES];
    private static volatile Snapshot snapshot = new Snapshot(TABLE.epoch(), Long2IntMaps.EMPTY_MAP,
            Long2ObjectMaps.emptyMap(), Reference2ObjectMaps.emptyMap());
    private static ClientLevel world;
    private static long lastFlicker, lastProviderRainbow;
    private record Rainbow(long start, double period) {}
    private HybridLighting() {}

    public static Snapshot snapshot() { return snapshot; }
    public static int count() { return REALTIME.size(); }
    public static int typeCount() { return TYPES.size(); }
    public static int flickerCount() { return FLICKER.size(); }
    /** Meshing-thread safe; null when the colour table is full. */
    static HybridLightTable.Source providerRainbow(int phase) {
        synchronized (PROVIDER_RAINBOW) {
            int index = BlockColorProviders.rainbowPhase(phase);
            HybridLightTable.Source source = PROVIDER_RAINBOW[index];
            if (source == null || source.epoch() != TABLE.epoch()) {
                source = TABLE.create(BlockColorProviders.rainbowColor(index));
                PROVIDER_RAINBOW[index] = source;
            }
            return source;
        }
    }
    public static void syncWorld(ClientLevel level) {
        if (world == level) return;
        world = level; BAKED.clear(); REALTIME.clear(); TYPES.clear(); RAINBOW.clear(); FLICKER.clear();
        synchronized (PROVIDER_RAINBOW) { java.util.Arrays.fill(PROVIDER_RAINBOW, null); }
        TABLE.reset(); publish();
    }
    private static void publish() {
        snapshot = new Snapshot(TABLE.epoch(), Long2IntMaps.unmodifiable(new Long2IntOpenHashMap(BAKED)),
                Long2ObjectMaps.unmodifiable(new Long2ObjectOpenHashMap<>(REALTIME)),
                Reference2ObjectMaps.unmodifiable(new Reference2ObjectOpenHashMap<>(TYPES)));
    }
    /** Null off the client thread or without a world; callers treat that as "not applied". */
    private static ClientLevel client() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return null;
        syncWorld(mc.level); return mc.level;
    }
    private static int palette(ClientLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) ? NativeLighting.paletteColor(level.getBlockState(pos)) : 0xffffff;
    }
    private static void rebuildAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.levelRenderer.allChanged();
    }

    public static boolean setBaked(BlockPos pos, int rgb) {
        ClientLevel level = client(); if (level == null) return false;
        rgb &= 0xffffff; long key = pos.asLong();
        HybridLightTable.Source old = REALTIME.remove(key);
        if (old != null) { RAINBOW.remove(old); TABLE.color(old, rgb); } // Retire without reusing an index still referenced by old VBOs.
        if (old == null && BAKED.containsKey(key) && BAKED.get(key) == rgb) return true;
        BAKED.put(key, rgb); publish(); NativeLighting.blockChanged(level, pos.getX(), pos.getY(), pos.getZ()); return true;
    }
    public static boolean setRealtime(BlockPos pos, int rgb) {
        ClientLevel level = client(); if (level == null) return false;
        long key = pos.asLong();
        HybridLightTable.Source existing = REALTIME.get(key);
        if (existing != null) { RAINBOW.remove(existing); TABLE.color(existing, rgb); return true; }
        HybridLightTable.Source source = TABLE.create(rgb);
        if (source == null) return false;
        REALTIME.put(key, source); BAKED.remove(key); publish();
        NativeLighting.blockChanged(level, pos.getX(), pos.getY(), pos.getZ()); return true;
    }
    public static boolean remove(BlockPos pos) {
        ClientLevel level = client(); if (level == null) return false;
        long key = pos.asLong();
        HybridLightTable.Source old = REALTIME.remove(key);
        boolean changed = BAKED.containsKey(key) || old != null;
        BAKED.remove(key);
        if (old != null) { RAINBOW.remove(old); TABLE.color(old, palette(level, pos)); }
        if (changed) { publish(); NativeLighting.blockChanged(level, pos.getX(), pos.getY(), pos.getZ()); }
        return changed;
    }
    public static boolean rainbow(BlockPos pos, double seconds) {
        if (!validPeriod(seconds) || !setRealtime(pos, 0xff0000)) return false;
        RAINBOW.put(REALTIME.get(pos.asLong()), new Rainbow(System.nanoTime(), seconds)); return true;
    }

    /** Every block of this type, at any position, follows one realtime channel. */
    public static boolean setTypeRealtime(Block block, int rgb) {
        ClientLevel level = client(); if (level == null) return false;
        HybridLightTable.Source existing = TYPES.get(block);
        if (existing != null) { RAINBOW.remove(existing); TABLE.color(existing, rgb); return true; }
        HybridLightTable.Source source = TABLE.create(rgb);
        if (source == null) return false;
        TYPES.put(block, source); publish(); rebuildAll(); return true;
    }
    public static boolean typeRainbow(Block block, double seconds) {
        if (!validPeriod(seconds) || !setTypeRealtime(block, 0xff0000)) return false;
        RAINBOW.put(TYPES.get(block), new Rainbow(System.nanoTime(), seconds)); return true;
    }
    public static boolean removeType(Block block) {
        ClientLevel level = client(); if (level == null) return false;
        HybridLightTable.Source old = TYPES.remove(block);
        if (old == null) return false;
        RAINBOW.remove(old); TABLE.color(old, NativeLighting.paletteColor(block.defaultBlockState()));
        publish(); rebuildAll(); return true;
    }
    private static boolean validPeriod(double seconds) { return Double.isFinite(seconds) && seconds >= .2 && seconds <= 120; }

    /** Meshing-thread safe. Neighbouring fire blocks get different phases so they do not pulse in sync. */
    static HybridLightTable.Source flickerChannel(int rgb, long position) {
        long h = position * 0x9E3779B97F4A7C15L;
        int phase = (int) ((h ^ (h >>> 29)) >>> 61) & (FLICKER_PHASES - 1);
        int key = (rgb & 0xffffff) | (phase << 24);
        HybridLightTable.Source source = FLICKER.get(key);
        if (source != null && source.epoch() == TABLE.epoch()) return source;
        synchronized (FLICKER) {
            source = FLICKER.get(key);
            if (source == null || source.epoch() != TABLE.epoch()) {
                source = TABLE.create(rgb);
                if (source == null) return null;
                FLICKER.put(key, source);
            }
            return source;
        }
    }

    public static void beforeFrame() {
        syncWorld(Minecraft.getInstance().level);
        long now = System.nanoTime();
        for (var entry : RAINBOW.entrySet()) {
            Rainbow rainbow = entry.getValue();
            double hue = ((now - rainbow.start) / (rainbow.period * 1e9)) % 1;
            TABLE.color(entry.getKey(), hsv(hue));
        }
        // Rainbow clocks advance per game tick: 20 table updates per second are enough.
        if (now - lastProviderRainbow > 50_000_000L) synchronized (PROVIDER_RAINBOW) {
            lastProviderRainbow = now;
            for (int phase = 0; phase < PROVIDER_RAINBOW.length; phase++)
                if (PROVIDER_RAINBOW[phase] != null) TABLE.color(PROVIDER_RAINBOW[phase], BlockColorProviders.rainbowColor(phase));
        }
        // Fire flicker: brightness only, about 30 updates per second.
        if (!FLICKER.isEmpty() && now - lastFlicker > 33_000_000L) {
            lastFlicker = now;
            float strength = LightingControl.flicker() ? LightingControl.flickerStrength() : 0;
            double t = now / 1e9;
            for (var entry : FLICKER.entrySet()) {
                int phase = entry.getKey() >>> 24;
                double p = phase * 2.399 + (entry.getKey() & 0xff) * .011;
                // Incommensurate sines read as irregular flame motion, without per-frame randomness.
                double n = .5 * Math.sin(t * 7.1 + p) + .3 * Math.sin(t * 13.7 + 2.1 * p) + .2 * Math.sin(t * 23.3 + 3.7 * p);
                float dip = (float) Math.clamp((n + 1) * .5, 0, 1);
                TABLE.intensity(entry.getValue(), 1 - strength * .55f * dip);
            }
        }
    }
    private static int hsv(double hue) {
        double h = hue * 6; int sector = (int) h; int f = (int) Math.round((h - sector) * 255);
        return switch (sector) {
            case 0 -> 0xff0000 | (f << 8);
            case 1 -> ((255 - f) << 16) | 0xff00;
            case 2 -> 0xff00 | f;
            case 3 -> ((255 - f) << 8) | 255;
            case 4 -> (f << 16) | 255;
            default -> 0xff0000 | (255 - f);
        };
    }
}
