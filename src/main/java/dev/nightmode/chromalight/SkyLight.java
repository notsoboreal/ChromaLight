package dev.nightmode.chromalight;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Colour of sky light by time of day and weather. Applied to the finished lightmap (after vanilla's
 * gamma, which would otherwise wash full daylight out to white), to the sky share of every texel
 * only, so terrain, entities and particles follow it on both renderers while block light and every
 * light level stay vanilla.
 */
public final class SkyLight {
    /** Day, dusk and night set the hue only. Rain and thunder also darken: their brightness counts. */
    public enum Part {
        DAY("sky_day", 0xFFF4E4), DUSK("sky_dusk", 0xFFB27A), NIGHT("sky_night", 0xB4BECD),
        RAIN("sky_rain", 0xA4AAB2), THUNDER("sky_thunder", 0x9CA1A8);

        public final String key;
        public final int fallback;
        Part(String key, int fallback) { this.key = key; this.fallback = fallback; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    private static final int BLOOD = 0xFF2A1A;
    private static final int[] COLORS = new int[Part.values().length];
    private static final float[][] RGB = new float[Part.values().length][];
    private static final float[] BLOOD_RGB = rgb(BLOOD);
    // Client thread only.
    private static boolean bloodMoon;
    private static float bloodFade;
    private static final float[] TINT = new float[3], HUE = new float[3];

    static { for (Part part : Part.values()) set(part, part.fallback); }

    private SkyLight() {}

    public static int color(Part part) { return COLORS[part.ordinal()]; }
    public static synchronized void set(Part part, int rgb) {
        COLORS[part.ordinal()] = rgb & 0xFFFFFF;
        RGB[part.ordinal()] = rgb(rgb);
    }
    static void load(LightSettings settings) {
        for (Part part : Part.values()) set(part, settings.skyColors[part.ordinal()]);
    }

    public static boolean bloodMoon() { return bloodMoon; }
    public static void setBloodMoon(boolean value) { bloodMoon = value; }
    public static boolean fading() { return bloodFade > 0; }
    /** Blood moon fades in and out over two seconds. */
    static void tick() {
        float target = bloodMoon ? 1f : 0f;
        bloodFade += Math.clamp(target - bloodFade, -.025f, .025f);
    }
    static void disconnect() { bloodMoon = false; bloodFade = 0; }

    /** 1 at noon, 0 at night; vanilla's own day curve for sky colour. */
    static float daylight(ClientLevel level) {
        return Mth.clamp(Mth.cos(level.getTimeOfDay(1f) * Mth.TWO_PI) * 2f + .5f, 0f, 1f);
    }
    public static boolean isNight(ClientLevel level) { return daylight(level) < .5f; }

    /** How much of the blood moon shows now: zero by day and outside overworld-like skies. */
    private static float blood(ClientLevel level) {
        if (bloodFade <= 0 || !LightingControl.isEnabled() || level.effects().skyType() != DimensionSpecialEffects.SkyType.NORMAL) return 0;
        float night = 1f - daylight(level);
        return bloodFade * night * night * (3f - 2f * night);
    }

    /** Per-channel multiplier for the sky share of the lightmap; false when there is nothing to do. */
    private static boolean tint(ClientLevel level, float[] out) {
        if (!LightingControl.isEnabled() || !level.dimensionType().hasSkyLight()) return false;
        float blood = blood(level);
        boolean sky = LightingControl.skyLight();
        if (!sky && blood <= 0) return false;

        out[0] = out[1] = out[2] = 1;
        if (sky) {
            float s = LightingControl.skyStrength(), day = daylight(level), dusk = 4f * day * (1f - day);
            mix(HUE, RGB[Part.NIGHT.ordinal()], RGB[Part.DAY.ordinal()], day);
            mix(HUE, HUE, RGB[Part.DUSK.ordinal()], dusk * .7f);
            float peak = Math.max(HUE[0], Math.max(HUE[1], HUE[2]));
            if (peak > 1e-4f) for (int i = 0; i < 3; i++) out[i] = Mth.lerp(s, 1f, HUE[i] / peak);
            if (LightingControl.skyWeather()) {
                float rain = level.getRainLevel(1f) * s, thunder = level.getThunderLevel(1f) * s;
                for (int i = 0; i < 3; i++)
                    out[i] *= Mth.lerp(rain, 1f, RGB[Part.RAIN.ordinal()][i]) * Mth.lerp(thunder, 1f, RGB[Part.THUNDER.ordinal()][i]);
            }
        }
        if (blood > 0) for (int i = 0; i < 3; i++) out[i] = Mth.lerp(blood, out[i], BLOOD_RGB[i]);
        return Math.abs(out[0] - 1) + Math.abs(out[1] - 1) + Math.abs(out[2] - 1) > .002f;
    }

    /**
     * Before vanilla uploads the lightmap. Texel (0, sky) is what the sky alone gives on that row;
     * the rest of each texel is block light. Only the sky share is multiplied. The terrain shader and
     * entity tint split texels the same way, so block colours on top stay as they were.
     */
    public static void tintLightmap(NativeImage pixels) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !tint(level, TINT)) return;
        for (int sky = 0; sky < 16; sky++) {
            int base = pixels.getPixelRGBA(0, sky);
            int sr = base & 255, sg = (base >> 8) & 255, sb = (base >> 16) & 255;
            int tr = Math.round(sr * TINT[0]), tg = Math.round(sg * TINT[1]), tb = Math.round(sb * TINT[2]);
            // Full sky + full block is LightTexture.FULL_BRIGHT: GUI text, items in menus and
            // emissive rendering use it, and it must stay neutral.
            for (int block = 0; block < (sky == 15 ? 15 : 16); block++) {
                int texel = pixels.getPixelRGBA(block, sky);
                int r = tr + Math.max((texel & 255) - sr, 0);
                int g = tg + Math.max(((texel >> 8) & 255) - sg, 0);
                int b = tb + Math.max(((texel >> 16) & 255) - sb, 0);
                pixels.setPixelRGBA(block, sky, (texel & 0xFF000000) | Math.min(b, 255) << 16 | Math.min(g, 255) << 8 | Math.min(r, 255));
            }
        }
    }

    public static Vec3 skyColor(ClientLevel level, Vec3 color) {
        return toward(color, blood(level) * .85f, .30, .025, .02);
    }
    public static Vec3 cloudColor(ClientLevel level, Vec3 color) {
        return toward(color, blood(level) * .7f, .42, .10, .08);
    }
    /** Fog colour {r, g, b}, in place. Only for open air; water and lava fog stay vanilla. */
    public static void fogColor(ClientLevel level, float[] rgb) {
        float w = blood(level) * .8f;
        if (w <= 0) return;
        rgb[0] = Mth.lerp(w, rgb[0], .26f);
        rgb[1] = Mth.lerp(w, rgb[1], .035f);
        rgb[2] = Mth.lerp(w, rgb[2], .03f);
    }
    /** Called right before vanilla draws the moon; the star pass resets the colour after it. */
    public static void tintMoon() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        float w = blood(level);
        if (w <= 0) return;
        float[] c = RenderSystem.getShaderColor();
        RenderSystem.setShaderColor(c[0], c[1] * (1f - .72f * w), c[2] * (1f - .78f * w), c[3]);
    }

    private static Vec3 toward(Vec3 color, float w, double r, double g, double b) {
        if (w <= 0) return color;
        return new Vec3(Mth.lerp(w, color.x, r), Mth.lerp(w, color.y, g), Mth.lerp(w, color.z, b));
    }
    private static void mix(float[] out, float[] a, float[] b, float t) {
        for (int i = 0; i < 3; i++) out[i] = Mth.lerp(t, a[i], b[i]);
    }
    private static float[] rgb(int rgb) {
        return new float[]{((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f};
    }
}
