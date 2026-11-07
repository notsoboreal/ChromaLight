package dev.nightmode.chromalight;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Colours for light sources that no config, light pack or resource pack covers. The colour comes
 * from the block's own textures: the brightest, most saturated pixels are usually the glowing part
 * (a flame, a crystal, a lamp lens). Grey and white textures stay untinted.
 *
 * <p>Computed on the client thread once resources have finished loading, never during a reload:
 * sprite images are freed when the atlas is rebuilt. Meshing threads only read the finished map.
 */
public final class AutoColors {
    private static final int WHITE = 0xffffff;
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static volatile Reference2IntOpenHashMap<BlockState> colors = empty();
    private static volatile boolean stale = true;
    // Client thread only: dynamic-light entities and items, by texture.
    private static final Map<ResourceLocation, Integer> TEXTURES = new HashMap<>();
    private static final Map<ResourceLocation, float[]> ITEM_SPRITES = new HashMap<>();

    private AutoColors() {}

    /** Generated colour, or white when the texture gives no clear hue. */
    public static int color(BlockState state) { return colors.getInt(state); }
    public static boolean has(BlockState state) { return colors.containsKey(state); }
    public static int count() { return colors.size(); }
    /** Textures changed (resource reload); recompute on the next tick after loading ends. */
    public static void invalidate() { stale = true; }

    /** Client tick. */
    static void tick() {
        if (!stale) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) return; // still reloading
        stale = false;
        TEXTURES.clear();
        ITEM_SPRITES.clear();
        long start = System.nanoTime();
        Reference2IntOpenHashMap<BlockState> next = compute(mc);
        boolean changed = !next.equals(colors);
        colors = next;
        System.out.printf("[ChromaLight] Automatic colors for %d light source states in %.1f ms%n", next.size(), (System.nanoTime() - start) / 1e6);
        if (changed && mc.level != null) NativeLighting.rebuildPalette();
    }

    /**
     * Client thread. Colour of a glowing entity from its own texture (white when the texture has no
     * clear hue), or {@link LightPalette#NONE} when it cannot be read.
     */
    public static int entity(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (stale || mc.getOverlay() != null) return LightPalette.NONE;
        ResourceLocation texture;
        try {
            texture = mc.getEntityRenderDispatcher().getRenderer(entity).getTextureLocation(entity);
        } catch (RuntimeException e) {
            return LightPalette.NONE;
        }
        if (texture == null) return LightPalette.NONE;
        return TEXTURES.computeIfAbsent(texture, location -> {
            try (var input = mc.getResourceManager().open(location); NativeImage image = NativeImage.read(input)) {
                return finish(sample(image, 0, 0, image.getWidth(), image.getHeight()));
            } catch (Exception e) {
                return LightPalette.NONE; // generated or remote textures: use the fallback
            }
        });
    }

    /** Client thread. Colour of an item from its model's icon, or {@link LightPalette#NONE}. */
    public static int item(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (stack.isEmpty() || stale || mc.getOverlay() != null) return LightPalette.NONE;
        try {
            float[] sum = newSum();
            add(sum, ITEM_SPRITES, mc.getItemRenderer().getModel(stack, mc.level, null, 0).getParticleIcon(), -1);
            return empty(sum) ? LightPalette.NONE : finish(sum);
        } catch (RuntimeException e) {
            return LightPalette.NONE;
        }
    }

    private static Reference2IntOpenHashMap<BlockState> compute(Minecraft mc) {
        Reference2IntOpenHashMap<BlockState> out = empty();
        Map<ResourceLocation, float[]> sprites = new HashMap<>();
        var atlas = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        RandomSource random = RandomSource.create();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            try {
                if (state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) <= 0) continue;
                float[] sum = newSum();
                Set<TextureAtlasSprite> seen = new HashSet<>();
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    var extensions = IClientFluidTypeExtensions.of(fluid);
                    add(sum, sprites, atlas.apply(extensions.getStillTexture()), extensions.getTintColor());
                }
                var model = mc.getBlockRenderer().getBlockModel(state);
                for (Direction side : SIDES) {
                    random.setSeed(42);
                    for (BakedQuad quad : model.getQuads(state, side, random)) {
                        if (seen.add(quad.getSprite())) add(sum, sprites, quad.getSprite(), -1);
                    }
                }
                if (seen.isEmpty() && fluid.isEmpty()) add(sum, sprites, model.getParticleIcon(), -1);
                int rgb = finish(sum);
                if (rgb != WHITE) out.put(state, rgb);
            } catch (RuntimeException e) {
                // A broken model or texture must not stop the rest; that block just stays white.
            }
        }
        return out;
    }

    private static void add(float[] sum, Map<ResourceLocation, float[]> cache, TextureAtlasSprite sprite, int tint) {
        var contents = sprite.contents();
        if (contents.name().equals(MissingTextureAtlasSprite.getLocation())) return;
        float[] s = cache.computeIfAbsent(contents.name(), name -> sample(sprite));
        float tr = 1, tg = 1, tb = 1;
        if (tint != -1) { tr = linear(((tint >> 16) & 255) / 255f); tg = linear(((tint >> 8) & 255) / 255f); tb = linear((tint & 255) / 255f); }
        for (int i = 0; i < SUM; i += 4) {
            sum[i] += s[i] * tr; sum[i + 1] += s[i + 1] * tg; sum[i + 2] += s[i + 2] * tb; sum[i + 3] += s[i + 3];
        }
    }

    /** Hue bins of 30 degrees, plus one for grey pixels; each holds a weighted linear r, g, b, weight. */
    private static final int BINS = 12, GREY = BINS, SUM = (BINS + 1) * 4;
    /** Below this saturation a pixel counts as grey. */
    private static final float MIN_SATURATION = .2f;
    /** The winning hue must carry this share of the texture's weight, or the light stays white. */
    private static final float MIN_SHARE = .3f;
    private static float[] newSum() { return new float[SUM]; }
    private static boolean empty(float[] sum) {
        for (int i = 3; i < SUM; i += 4) if (sum[i] > 0) return false;
        return true;
    }

    /** Weighted linear RGB sums of the first frame by hue: bright, saturated pixels dominate. */
    private static float[] sample(TextureAtlasSprite sprite) {
        var contents = sprite.contents();
        var image = contents.getOriginalImage();
        return sample(image, 0, 0, Math.min(contents.width(), image.getWidth()), Math.min(contents.height(), image.getHeight()));
    }

    private static float[] sample(NativeImage image, int x0, int y0, int width, int height) {
        float[] s = newSum();
        for (int y = y0; y < y0 + height; y++) for (int x = x0; x < x0 + width; x++) {
            int abgr = image.getPixelRGBA(x, y);
            int alpha = abgr >>> 24;
            if (alpha < 100) continue;
            float r = (abgr & 255) / 255f, g = ((abgr >> 8) & 255) / 255f, b = ((abgr >> 16) & 255) / 255f;
            float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            if (max < .05f) continue;
            float saturation = (max - min) / max;
            float value2 = max * max, weight = value2 * value2 * (.2f + saturation) * alpha / 255f;
            int bin = saturation < MIN_SATURATION ? GREY : hueBin(r, g, b, max, min);
            int i = bin * 4;
            s[i] += linear(r) * weight; s[i + 1] += linear(g) * weight; s[i + 2] += linear(b) * weight; s[i + 3] += weight;
        }
        return s;
    }

    private static int hueBin(float r, float g, float b, float max, float min) {
        float delta = max - min, hue;
        if (max == r) hue = ((g - b) / delta + 6) % 6;
        else if (max == g) hue = (b - r) / delta + 2;
        else hue = (r - g) / delta + 4;
        return Math.min((int) (hue * 2), BINS - 1); // 6 sextants -> 12 bins
    }

    /**
     * The strongest hue wins, averaged with its two neighbours, so a texture with two colors
     * (a cyan Starbuncle with orange ears) takes the main one instead of a grey mix of both.
     */
    private static int finish(float[] sums) {
        float total = 0;
        int peak = 0;
        for (int bin = 0; bin <= GREY; bin++) total += sums[bin * 4 + 3];
        for (int bin = 1; bin < BINS; bin++) if (sums[bin * 4 + 3] > sums[peak * 4 + 3]) peak = bin;
        float[] sum = new float[4];
        for (int d = -1; d <= 1; d++) {
            int i = Math.floorMod(peak + d, BINS) * 4;
            for (int c = 0; c < 4; c++) sum[c] += sums[i + c];
        }
        if (sum[3] <= 1e-6f || sum[3] < MIN_SHARE * total) return WHITE;
        float r = srgb(sum[0] / sum[3]), g = srgb(sum[1] / sum[3]), b = srgb(sum[2] / sum[3]);
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max <= 1e-4f || (max - min) / max < .12f) return WHITE;
        // Light reads paler than the surface it comes from; keep some white in it.
        r = .8f * r / max + .2f; g = .8f * g / max + .2f; b = .8f * b / max + .2f;
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    private static float linear(float c) { return c <= .04045f ? c / 12.92f : (float) Math.pow((c + .055f) / 1.055f, 2.4f); }
    private static float srgb(float c) { return c <= .0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1 / 2.4) - .055); }

    private static Reference2IntOpenHashMap<BlockState> empty() {
        var map = new Reference2IntOpenHashMap<BlockState>();
        map.defaultReturnValue(WHITE);
        return map;
    }
}
