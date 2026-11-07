package dev.nightmode.chromalight;

/** Live video settings. Shader-pack selection never changes the enabled state. */
public final class LightingControl {
    private static volatile boolean enabled = true;
    private static volatile float intensity = .85f;
    private static volatile float sourceBlend = 1f;
    private static volatile int mixedSources = 4;
    private static volatile int realtimeSources = 32;
    private static volatile boolean flicker = false;
    private static volatile float flickerStrength = .5f;
    private static volatile boolean entityLighting = true;
    private static volatile boolean dynamicLights = true;
    private static volatile boolean skyLight = true;
    private static volatile float skyStrength = .6f;
    private static volatile boolean skyWeather = true;
    private static volatile boolean autoColors = true;
    private static volatile java.util.List<String> lightPacks = java.util.List.of();

    private LightingControl() {}

    public static boolean isEnabled() { return enabled; }
    public static float intensity() { return intensity; }
    public static float sourceBlend() { return sourceBlend; }
    public static int mixedSources() { return mixedSources; }
    /** Realtime colour channels per meshing job, not a global block count. */
    public static int realtimeSources() { return realtimeSources; }
    public static boolean flicker() { return flicker; }
    public static float flickerStrength() { return flickerStrength; }
    public static void setIntensity(float value) { intensity = Math.clamp(value, 0f, 1f); }
    public static void setSourceBlend(float value) { sourceBlend = Math.clamp(value, 0f, 1f); }
    public static void setMixedSources(int value) { mixedSources = Math.clamp(value, 2, 8); }
    public static void setRealtimeSources(int value) { realtimeSources = Math.clamp(value, 0, 32); }
    public static void setFlicker(boolean value) { flicker = value; }
    public static boolean entityLighting() { return entityLighting; }
    public static void setEntityLighting(boolean value) { entityLighting = value; }
    /** Colour LambDynamicLights sources; meshes pick it up when LDL rebuilds them. */
    public static boolean dynamicLights() { return dynamicLights; }
    public static void setDynamicLights(boolean value) { dynamicLights = value; }
    public static void setFlickerStrength(float value) { flickerStrength = Math.clamp(value, 0f, 1f); }
    /** Sky light colour by time of day; the lightmap picks it up on its next update. */
    public static boolean skyLight() { return skyLight; }
    public static void setSkyLight(boolean value) { skyLight = value; }
    public static float skyStrength() { return skyStrength; }
    public static void setSkyStrength(float value) { skyStrength = Math.clamp(value, 0f, 1f); }
    public static boolean skyWeather() { return skyWeather; }
    public static void setSkyWeather(boolean value) { skyWeather = value; }
    /** Texture-derived colours for light sources without a rule. Rebuilds chunks. */
    public static boolean autoColors() { return autoColors; }
    public static void setAutoColors(boolean value) { autoColors = value; }
    /** Selected packs in the chromalight folder, lowest priority first. */
    public static java.util.List<String> lightPacks() { return lightPacks; }
    static void setLightPacks(java.util.List<String> value) { lightPacks = java.util.List.copyOf(value); }

    static void load(LightSettings settings) {
        enabled = settings.enabled;
        intensity = settings.intensity;
        sourceBlend = settings.sourceBlend;
        mixedSources = settings.mixedSources;
        realtimeSources = settings.realtimeSources;
        flicker = settings.flicker;
        flickerStrength = settings.flickerStrength;
        entityLighting = settings.entityLighting;
        dynamicLights = settings.dynamicLights;
        skyLight = settings.skyLight;
        skyStrength = settings.skyStrength;
        skyWeather = settings.skyWeather;
        autoColors = settings.autoColors;
        lightPacks = settings.lightPacks;
        SkyLight.load(settings);
    }

    /** Returns false when the requested state is already active. Client thread only. */
    public static boolean setEnabled(boolean value) {
        if (enabled == value) return false;
        enabled = value;
        return true;
    }
}
