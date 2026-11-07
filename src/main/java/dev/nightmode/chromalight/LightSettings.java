package dev.nightmode.chromalight;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Configuration I/O and palette defaults. Meshing never reads or writes these files. */
public final class LightSettings {
    public final float intensity;
    public final int realtimeSources;
    public final boolean enabled;
    public final float sourceBlend;
    public final int mixedSources;
    public final boolean flicker;
    public final float flickerStrength;
    public final String flickerBlocks;
    public final boolean entityLighting, dynamicLights;
    public final String entityBlacklist;
    public final boolean skyLight, skyWeather;
    public final float skyStrength;
    public final boolean autoColors;
    /** Selected folder packs, lowest priority first. */
    public final java.util.List<String> lightPacks;
    /** sRGB, indexed by {@link SkyLight.Part#ordinal()}. */
    public final int[] skyColors = new int[SkyLight.Part.values().length];
    final Properties properties;

    private LightSettings(Properties p) {
        properties = p;
        intensity = decimal(p, "tint_strength", .85f, 0f, 1f);
        realtimeSources = integer(p, "max_realtime_sources", 32, 0, 32);
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
        sourceBlend = decimal(p, "source_blend", 1f, 0f, 1f);
        mixedSources = integer(p, "max_mixed_sources", 4, 2, 8);
        flicker = Boolean.parseBoolean(p.getProperty("flicker", "false"));
        flickerStrength = decimal(p, "flicker_strength", .5f, 0f, 1f);
        flickerBlocks = p.getProperty("flicker_blocks", DEFAULT_FLICKER_BLOCKS);
        entityLighting = Boolean.parseBoolean(p.getProperty("entity_lighting", "true"));
        dynamicLights = Boolean.parseBoolean(p.getProperty("dynamic_lights", "true"));
        entityBlacklist = p.getProperty("entity_blacklist", "");
        skyLight = Boolean.parseBoolean(p.getProperty("sky_light", "true"));
        skyStrength = decimal(p, "sky_strength", .6f, 0f, 1f);
        skyWeather = Boolean.parseBoolean(p.getProperty("sky_weather", "true"));
        autoColors = Boolean.parseBoolean(p.getProperty("auto_colors", "true"));
        lightPacks = packList(p.getProperty("light_packs", ""));
        for (SkyLight.Part part : SkyLight.Part.values()) skyColors[part.ordinal()] = rgb(p, part.key, part.fallback);
    }

    static final String DEFAULT_FLICKER_BLOCKS = "#minecraft:campfires,#minecraft:fire,#minecraft:candles,#minecraft:candle_cakes,"
            + "minecraft:torch,minecraft:wall_torch,minecraft:soul_torch,minecraft:soul_wall_torch,"
            + "minecraft:lantern,minecraft:soul_lantern,minecraft:jack_o_lantern";
    /** Keys of the removed screen-space renderer. They never affected terrain colour. */
    private static final String[] OBSOLETE = {"horizontal_sections", "vertical_sections", "work_budget_ms",
            "max_voxel_samples_per_frame", "max_propagation_steps_per_frame", "brightness_multiplier",
            "section_cache_mb", "artificial_redstone_wire_level", "artificial_redstone_block_level"};

    private static final String[][] RETIRED_DEFAULTS = {{"color.minecraft:glow_lichen", "#B5FFCD"}};

    public static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("config_version", "9");
        p.setProperty("enabled", "true");
        p.setProperty("tint_strength", "0.85");
        p.setProperty("source_blend", "1.0");
        p.setProperty("max_mixed_sources", "4");
        p.setProperty("max_realtime_sources", "32");
        p.setProperty("flicker", "false");
        p.setProperty("flicker_strength", "0.5");
        p.setProperty("flicker_blocks", DEFAULT_FLICKER_BLOCKS);
        p.setProperty("entity_lighting", "true");
        p.setProperty("dynamic_lights", "true");
        p.setProperty("entity_blacklist", "");
        p.setProperty("sky_light", "true");
        p.setProperty("sky_strength", "0.6");
        p.setProperty("sky_weather", "true");
        p.setProperty("auto_colors", "true");
        p.setProperty("light_packs", "");
        for (SkyLight.Part part : SkyLight.Part.values()) p.setProperty(part.key, hex(part.fallback));
        String resource = "/assets/chromalight/vanilla-colors.properties";
        try (InputStream in = LightSettings.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing " + resource);
            p.load(in);
        } catch (IOException e) { throw new IllegalStateException("Cannot load bundled light palette", e); }
        return p;
    }

    /** Defaults are merged without overwriting user colours, including intentionally white ones. */
    static Properties merge(Properties stored) {
        Properties user = new Properties();
        user.putAll(stored);
        migrate(user, "soul_torch", "soul_torch", "soul_wall_torch");
        migrate(user, "soul_lantern", "soul_lantern");
        migrate(user, "soul_campfire", "soul_campfire", "soul_fire");
        migrate(user, "redstone_torch", "redstone_torch", "redstone_wall_torch");
        migrate(user, "redstone_lamp", "redstone_lamp");
        migrate(user, "redstone_wire", "redstone_wire");
        migrate(user, "redstone_block", "redstone_block");
        for (String key : OBSOLETE) user.remove(key);
        // Bundled colours that changed: a config still holding the old default follows the new one.
        for (String[] retired : RETIRED_DEFAULTS) removeIf(user, retired[0], retired[1]);
        if ("8".equals(user.getProperty("config_version"))) { // sky colours from a dev build, retuned
            removeIf(user, "sky_night", "#9DB4FF");
            removeIf(user, "sky_rain", "#B4C2D6");
            removeIf(user, "sky_thunder", "#9AA4C4");
        }
        Properties merged = defaults();
        merged.putAll(user);
        merged.setProperty("config_version", "9");
        return merged;
    }

    public static LightSettings load(Path path) {
        Properties stored = new Properties();
        boolean existed = Files.exists(path), readable = true;
        try {
            if (existed) try (InputStream in = Files.newInputStream(path)) { stored.load(in); }
        } catch (IOException | IllegalArgumentException e) {
            readable = false; // Never overwrite a damaged or unreadable user config.
            stored.clear();
            System.err.println("[ChromaLight] Config read failed; using defaults without overwriting: " + e);
        }
        Properties merged = merge(stored);
        if (readable && (!existed || !merged.equals(stored))) {
            Path temporary = null;
            try {
                Path parent = path.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                if (existed) {
                    Path backup = path.resolveSibling(path.getFileName() + ".pre-0.3.bak");
                    if (!Files.exists(backup)) Files.copy(path, backup);
                }
                temporary = Files.createTempFile(parent, "chromalight-", ".tmp");
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    merged.store(out, "ChromaLight: native terrain colors. Video settings can be changed in the Sodium options page.");
                }
                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException e) { System.err.println("[ChromaLight] Config write failed; settings still apply in memory: " + e); }
            finally {
                if (temporary != null) try { Files.deleteIfExists(temporary); }
                catch (IOException e) { System.err.println("[ChromaLight] Could not remove temporary config: " + e); }
            }
        }
        return new LightSettings(merged);
    }

    public static void saveRuntime(Path path) {
        Properties stored = new Properties();
        Path temporary = null;
        try {
            if (Files.exists(path)) try (InputStream input = Files.newInputStream(path)) { stored.load(input); }
            Properties merged = merge(stored);
            merged.setProperty("enabled", Boolean.toString(LightingControl.isEnabled()));
            merged.setProperty("tint_strength", Float.toString(LightingControl.intensity()));
            merged.setProperty("source_blend", Float.toString(LightingControl.sourceBlend()));
            merged.setProperty("max_mixed_sources", Integer.toString(LightingControl.mixedSources()));
            merged.setProperty("max_realtime_sources", Integer.toString(LightingControl.realtimeSources()));
            merged.setProperty("flicker", Boolean.toString(LightingControl.flicker()));
            merged.setProperty("flicker_strength", Float.toString(LightingControl.flickerStrength()));
            merged.setProperty("entity_lighting", Boolean.toString(LightingControl.entityLighting()));
            merged.setProperty("dynamic_lights", Boolean.toString(LightingControl.dynamicLights()));
            merged.setProperty("sky_light", Boolean.toString(LightingControl.skyLight()));
            merged.setProperty("sky_strength", Float.toString(LightingControl.skyStrength()));
            merged.setProperty("sky_weather", Boolean.toString(LightingControl.skyWeather()));
            merged.setProperty("auto_colors", Boolean.toString(LightingControl.autoColors()));
            merged.setProperty("light_packs", String.join(",", LightingControl.lightPacks()));
            for (SkyLight.Part part : SkyLight.Part.values()) merged.setProperty(part.key, hex(SkyLight.color(part)));
            Path parent = path.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "chromalight-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                merged.store(output, "ChromaLight video settings and source palette");
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("[ChromaLight] Could not save video settings: " + exception);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static void migrate(Properties p, String old, String... ids) {
        if (!p.containsKey(old)) return;
        String raw = p.getProperty(old).trim();
        int separator = raw.indexOf(':');
        String color = (separator < 0 ? raw : raw.substring(0, separator)).trim();
        if (!color.matches("#?[0-9a-fA-F]{6}")) return;
        for (String id : ids) {
            String key = "color.minecraft:" + id;
            if (!p.containsKey(key)) p.setProperty(key, color);
        }
    }
    private static java.util.List<String> packList(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String name : raw.split(",")) if (!name.isBlank() && !out.contains(name.trim())) out.add(name.trim());
        return java.util.List.copyOf(out);
    }
    private static void removeIf(Properties p, String key, String value) {
        if (value.equalsIgnoreCase(p.getProperty(key, "").trim())) p.remove(key);
    }
    private static int rgb(Properties p, String key, int fallback) {
        String raw = p.getProperty(key, "").trim();
        if (raw.startsWith("#")) raw = raw.substring(1);
        return raw.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(raw, 16) : fallback;
    }
    static String hex(int rgb) { return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF); }
    private static int integer(Properties p, String key, int fallback, int lo, int hi) {
        try { return Math.clamp(Integer.parseInt(p.getProperty(key).trim()), lo, hi); }
        catch (RuntimeException e) { return fallback; }
    }
    private static float decimal(Properties p, String key, float fallback, float lo, float hi) {
        try {
            float v = Float.parseFloat(p.getProperty(key).trim());
            return Float.isFinite(v) ? Math.clamp(v, lo, hi) : fallback;
        } catch (RuntimeException e) { return fallback; }
    }
}
