package dev.nightmode.chromalight;

import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-position emitter colours that live in block-entity data (spell lights, dyeable lamps).
 * Providers are called on chunk-meshing threads with the meshing snapshot as {@code level};
 * they must only read, and return {@link #NONE} to fall back to the palette.
 */
public final class BlockColorProviders {
    public static final int NONE = -1;
    /** Flag for "cycling rainbow hue": the low 6 bits pick one of 64 phase-shifted shared channels. */
    public static final int RAINBOW_FLAG = 0x40000000, RAINBOW_PHASES = 64;
    public static int rainbow(int phase) { return RAINBOW_FLAG | (phase & (RAINBOW_PHASES - 1)); }
    public static int rainbowPhase(int value) { return value & (RAINBOW_PHASES - 1); }
    /** Current RGB of a rainbow phase. A mod sets its own clock so light matches its particles. */
    @FunctionalInterface public interface RainbowClock { int rgb(int phase); }
    private static volatile RainbowClock rainbowClock = phase -> hsv((System.nanoTime() / 1e9 / 76.8 + phase / (double) RAINBOW_PHASES) % 1);
    public static void setRainbowClock(RainbowClock clock) { rainbowClock = clock; }
    public static int rainbowColor(int phase) { return rainbowClock.rgb(phase & (RAINBOW_PHASES - 1)) & 0xffffff; }
    private static int hsv(double hue) {
        double h = hue * 6; int sector = (int) h; int f = (int) Math.round((h - sector) * 255);
        return switch (sector) {
            case 0 -> 0xff0000 | (f << 8); case 1 -> ((255 - f) << 16) | 0xff00; case 2 -> 0xff00 | f;
            case 3 -> ((255 - f) << 8) | 255; case 4 -> (f << 16) | 255; default -> 0xff0000 | (255 - f);
        };
    }
    public static boolean isRainbow(int value) { return value >= 0 && (value & RAINBOW_FLAG) != 0; }
    @FunctionalInterface public interface Provider { int color(BlockAndTintGetter level, BlockPos pos, BlockState state); }
    private static volatile Reference2ObjectMap<Block, Provider> providers = Reference2ObjectMaps.emptyMap();
    private BlockColorProviders() {}

    /** Client thread, e.g. during client setup. Replaces an earlier provider for the same block. */
    public static synchronized void register(Block block, Provider provider) {
        var copy = new Reference2ObjectOpenHashMap<>(providers);
        copy.put(block, provider);
        providers = Reference2ObjectMaps.unmodifiable(copy);
    }
    public static boolean has(Block block) { return providers.containsKey(block); }
    public static int count() { return providers.size(); }

    public static int color(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        Provider provider = providers.get(state.getBlock());
        if (provider == null) return NONE;
        try {
            int rgb = provider.color(level, pos, state);
            return rgb < 0 ? NONE : isRainbow(rgb) ? rgb : rgb & 0xffffff;
        } catch (RuntimeException e) {
            return NONE; // a provider must never break chunk meshing
        }
    }
}
