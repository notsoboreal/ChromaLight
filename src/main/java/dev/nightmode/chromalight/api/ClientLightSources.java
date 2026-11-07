package dev.nightmode.chromalight.api;

import dev.nightmode.chromalight.BlockColorProviders;
import dev.nightmode.chromalight.HybridLighting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Client-only RGB integration API. Call on the client thread with a synchronized block colour.
 * Realtime colour lives on channels: one per registered position, and one per registered block
 * type that covers every block of that type. Changing a channel's RGB never rebuilds chunks.
 */
public final class ClientLightSources {
    public enum Mode { BAKED, REALTIME }
    private ClientLightSources() {}
    /** Native emission comes from the block. Returns false without a client world or on colour-table exhaustion. */
    public static boolean set(BlockPos position, int rgb, Mode mode) {
        if (mode == null) throw new NullPointerException("mode");
        return mode == Mode.BAKED ? HybridLighting.setBaked(position, rgb) : HybridLighting.setRealtime(position, rgb);
    }
    /** Restores the normal palette. Removing/switching modes rebuilds dependent meshes once. */
    public static boolean remove(BlockPos position) { return HybridLighting.remove(position); }
    /**
     * Every block of this type shares one realtime channel, regardless of how many are placed.
     * The first call rebuilds terrain once; later calls only update the colour table.
     */
    public static boolean setType(Block block, int rgb) { return HybridLighting.setTypeRealtime(block, rgb); }
    /** Restores the palette colour for this block type and rebuilds terrain once. */
    public static boolean removeType(Block block) { return HybridLighting.removeType(block); }
    /**
     * Per-position colour from block-entity (or other) data, e.g. a dyeable lamp. Called on
     * chunk-meshing threads with the meshing snapshot as the level; read only, return -1 for
     * "use the palette". After the data changes on the client, call
     * {@code ClientEvents.blockChanged(level, x, y, z)} (block-entity data packets are handled automatically).
     */
    public static void registerColorProvider(Block block, BlockColorProviders.Provider provider) { BlockColorProviders.register(block, provider); }
}
