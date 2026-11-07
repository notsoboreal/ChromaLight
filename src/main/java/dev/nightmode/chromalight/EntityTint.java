package dev.nightmode.chromalight;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nightmode.chromalight.compat.SodiumTintConsumer;
import dev.nightmode.chromalight.core.HybridLightTable;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Coloured light on entities. Entity vertices are not cached meshes, so the hue is sampled on
 * the client thread at the entity's light probe and applied to the vertex colour, using the same
 * block-light share and tint formula as the terrain shader (against the current lightmap).
 * Works with any render type that multiplies by vertex colour; blacklisted types are untouched.
 */
public final class EntityTint {
    /** Linear baked colour plus live realtime channel weights at one position. */
    public record Hue(float r, float g, float b, HybridLightTable.Source[] channels, float[] weights, boolean lit) {
        /** out = linear r, g, b, intensity with current channel colours. */
        void resolve(float[] out) {
            float red = r, green = g, blue = b, intensity = 1;
            for (int n = 0; n < channels.length; n++) {
                float w = weights[n]; HybridLightTable.Source source = channels[n];
                red += w * source.red(); green += w * source.green(); blue += w * source.blue();
                intensity -= w * (1 - source.intensity());
            }
            out[0] = red; out[1] = green; out[2] = blue; out[3] = Math.clamp(intensity, 0f, 1f);
        }
    }
    private static final int[] LIGHTMAP = new int[256]; // ABGR, x = block, y = sky
    private static boolean lightmapReady;
    private static final Long2ObjectOpenHashMap<Hue> CACHE = new Long2ObjectOpenHashMap<>();
    private static final float[] RESOLVED = new float[4];
    private static int ticks;
    private static long tinted, blacklisted;
    private EntityTint() {}

    public static long tinted() { return tinted; }
    public static long blacklisted() { return blacklisted; }

    /** After every lightmap update (client thread). */
    public static void captureLightmap(NativeImage pixels) {
        for (int sky = 0; sky < 16; sky++) for (int block = 0; block < 16; block++)
            LIGHTMAP[sky * 16 + block] = pixels.getPixelRGBA(block, sky);
        lightmapReady = true;
    }
    /** Positions are re-sampled when blocks change, and every half second for moving dynamic lights. */
    public static void invalidate() { CACHE.clear(); }
    public static void tick() { if (++ticks % 10 == 0) CACHE.clear(); }

    public static MultiBufferSource wrap(MultiBufferSource buffers, Entity entity, int packedLight, float partialTick) {
        if (!lightmapReady || !LightingControl.isEnabled() || !LightingControl.entityLighting()) return buffers;
        if (!(entity.level() instanceof ClientLevel level) || level != Minecraft.getInstance().level) return buffers;
        if (NativeLighting.palette().entityBlacklisted(entity.getType())) { blacklisted++; return buffers; }
        float[] factor = factorAt(level, entity.getLightProbePosition(partialTick), packedLight);
        if (factor == null) return buffers;
        tinted++;
        return new Buffers(buffers, factor[0], factor[1], factor[2]);
    }

    /**
     * Block entities (chests, signs, banners, beds, skulls...): the same tint as entities,
     * sampled at the block. Client thread, inside the block entity render loop.
     */
    public static MultiBufferSource wrapBlockEntity(MultiBufferSource buffers, net.minecraft.world.level.block.entity.BlockEntity blockEntity, int packedLight) {
        if (!lightmapReady || packedLight == LightTexture.FULL_BRIGHT || !LightingControl.isEnabled() || !LightingControl.entityLighting()) return buffers;
        if (!(blockEntity.getLevel() instanceof ClientLevel level) || level != Minecraft.getInstance().level) return buffers;
        BlockPos pos = blockEntity.getBlockPos();
        float[] factor = factorAt(level, new Vec3(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5), packedLight);
        if (factor == null) return buffers;
        tinted++;
        return new Buffers(buffers, factor[0], factor[1], factor[2]);
    }

    /**
     * Particles: the same tint as entities, sampled at the particle. Full-bright (self-lit)
     * particles are left alone. Client thread, inside the particle render loop.
     */
    public static VertexConsumer wrapParticle(VertexConsumer buffer, net.minecraft.client.particle.Particle particle, int packedLight) {
        if (!lightmapReady || packedLight == LightTexture.FULL_BRIGHT || !LightingControl.isEnabled() || !LightingControl.entityLighting()) return buffer;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return buffer;
        float[] factor = factorAt(level, particle.getPos(), packedLight);
        if (factor == null) return buffer;
        return CompatStatus.sodium ? new SodiumTintConsumer(buffer, factor[0], factor[1], factor[2]) : new Consumer(buffer, factor[0], factor[1], factor[2]);
    }

    private static float[] factorAt(ClientLevel level, Vec3 probe, int packedLight) {
        long key = BlockPos.asLong((int) Math.floor(probe.x), (int) Math.floor(probe.y), (int) Math.floor(probe.z));
        Hue hue = CACHE.get(key);
        if (hue == null) {
            hue = NativeLighting.sampleAt(level, Math.floor(probe.x) + .5, Math.floor(probe.y) + .5, Math.floor(probe.z) + .5);
            if (CACHE.size() > 4096) CACHE.clear();
            CACHE.put(key, hue);
        }
        if (!hue.lit()) return null;
        hue.resolve(RESOLVED);
        return factor(RESOLVED, LightTexture.block(packedLight), LightTexture.sky(packedLight), LightingControl.intensity());
    }

    /** Per-channel multiplier of the terrain shader's result over the native lightmap colour, or null for none. */
    static float[] factor(float[] hue, int block, int sky, float strength) {
        boolean white = hue[0] > .99f && hue[1] > .99f && hue[2] > .99f;
        if (white && hue[3] > .995f) return null;
        float[] nativeLight = texel(block, sky), skyOnly = texel(0, sky), part = new float[3];
        for (int c = 0; c < 3; c++) part[c] = Math.max(0, nativeLight[c] - skyOnly[c]);
        float blockPeak = peak(part);
        if (blockPeak < 1e-4f) return null;
        float[] colored = part.clone();
        if (!white) {
            float colorPeak = Math.max(hue[0], Math.max(hue[1], hue[2]));
            if (colorPeak < 1e-4f) for (int c = 0; c < 3; c++) colored[c] = part[c] * (1 - strength);
            else {
                for (int c = 0; c < 3; c++) colored[c] = part[c] * (1 + (hue[c] / colorPeak - 1) * strength);
                float scale = blockPeak / Math.max(peak(colored), 1e-4f);
                for (int c = 0; c < 3; c++) colored[c] *= scale;
            }
        }
        float[] out = new float[3];
        boolean neutral = true;
        for (int c = 0; c < 3; c++) {
            float result = Math.min(1, skyOnly[c] + colored[c] * hue[3]);
            out[c] = nativeLight[c] > 1e-4f ? result / nativeLight[c] : 1;
            if (Math.abs(out[c] - 1) > .004f) neutral = false;
        }
        return neutral ? null : out;
    }
    private static float[] texel(int block, int sky) {
        int abgr = LIGHTMAP[(sky & 15) * 16 + (block & 15)];
        return new float[]{(abgr & 255) / 255f, ((abgr >>> 8) & 255) / 255f, ((abgr >>> 16) & 255) / 255f};
    }
    private static float peak(float[] c) { return Math.max(c[0], Math.max(c[1], c[2])); }

    private record Buffers(MultiBufferSource delegate, float r, float g, float b) implements MultiBufferSource {
        @Override public VertexConsumer getBuffer(RenderType type) {
            VertexConsumer target = delegate.getBuffer(type);
            // Sodium renders entity models through VertexBufferWriter; keep that fast path.
            return CompatStatus.sodium ? new SodiumTintConsumer(target, r, g, b) : new Consumer(target, r, g, b);
        }
    }

    /** Multiplies vertex colour; emissive (full-bright) vertices in bulk paths are left alone. */
    public static class Consumer implements VertexConsumer {
        protected final VertexConsumer delegate;
        protected final float r, g, b;
        public Consumer(VertexConsumer delegate, float r, float g, float b) { this.delegate = delegate; this.r = r; this.g = g; this.b = b; }
        protected static int scale(int channel, float factor) { return Math.clamp(Math.round(channel * factor), 0, 255); }
        @Override public VertexConsumer addVertex(float x, float y, float z) { delegate.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(scale(red, r), scale(green, g), scale(blue, b), alpha); return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { delegate.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                                        float nx, float ny, float nz) {
            if (light != LightTexture.FULL_BRIGHT) {
                color = (color & 0xff000000) | (scale((color >>> 16) & 255, r) << 16)
                        | (scale((color >>> 8) & 255, g) << 8) | scale(color & 255, b);
            }
            delegate.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
        }
    }
}
