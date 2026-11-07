package dev.nightmode.chromalight.compat;

import dev.nightmode.chromalight.AutoColors;
import dev.nightmode.chromalight.EntityColorProviders;
import dev.nightmode.chromalight.LightPalette;
import dev.nightmode.chromalight.LightingControl;
import dev.nightmode.chromalight.NativeLighting;
import dev.nightmode.chromalight.core.NativeLightField;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Dynamic light compatibility: LambDynamicLights and Ars Nouveau's built-in engine. LDL raises the block light of nearby vertices with a
 * spherical falloff ({@code luminance - distance / 7.75 * 15}) and rebuilds those sections.
 * Each client tick we snapshot its entity sources with a colour; meshing mixes them into
 * the same vertices LDL brightens, so its light gets our hue. Reflection only: both are optional.
 */
public final class DynamicLightsCompat {
    public static final int STRIDE = 7; // x, y, z, luminance, linear r, g, b
    public static final double RADIUS = 7.75;
    private static volatile float[] sources = new float[0];
    /** Last tick's sources for /chromalight debug: entity id, backend, luminance, RGB. */
    private static volatile java.util.List<String> described = java.util.List.of();
    public static java.util.List<String> describe() { return described; }
    /** One dynamic light engine whose interface is mixed into entities. */
    private record Backend(String name, Class<?> type, MethodHandle enabled, MethodHandle luminance,
                           MethodHandle x, MethodHandle y, MethodHandle z) {}
    private static Backend[] backends = new Backend[0];
    private static boolean initialized;
    private DynamicLightsCompat() {}

    public static boolean available() { init(); return backends.length > 0; }
    public static float[] snapshot() { return sources; }
    public static int count() { return sources.length / STRIDE; }
    public static String backends() {
        init();
        StringBuilder out = new StringBuilder();
        for (Backend backend : backends) out.append(out.isEmpty() ? "" : "+").append(backend.name());
        return out.isEmpty() ? "none" : out.toString();
    }

    private static void init() {
        if (initialized) return;
        initialized = true;
        java.util.List<Backend> found = new java.util.ArrayList<>();
        // LambDynamicLights 4.x.
        if (ModList.get().isLoaded("lambdynlights") || ModList.get().isLoaded("lambdynlights_runtime"))
            add(found, "LambDynamicLights", "dev.lambdaurora.lambdynlights.engine.source.EntityDynamicLightSource",
                    "isDynamicLightEnabled", "getLuminance", "getDynamicLightX", "getDynamicLightY", "getDynamicLightZ");
        // Ars Nouveau's built-in engine (/ars-light on): spell projectiles, jars of light, starbuncles.
        if (ModList.get().isLoaded("ars_nouveau"))
            add(found, "Ars Nouveau", "com.hollingsworth.arsnouveau.common.light.LambDynamicLight",
                    "ars_nouveau$isDynamicLightEnabled", "ars_nouveau$getLuminance", "ars_nouveau$getDynamicLightX",
                    "ars_nouveau$getDynamicLightY", "ars_nouveau$getDynamicLightZ");
        backends = found.toArray(Backend[]::new);
    }
    private static void add(java.util.List<Backend> out, String name, String type, String enabled, String luminance,
                            String x, String y, String z) {
        try {
            Class<?> source = Class.forName(type);
            var lookup = MethodHandles.publicLookup();
            MethodType asBoolean = MethodType.methodType(boolean.class, Object.class), asInt = MethodType.methodType(int.class, Object.class),
                    asDouble = MethodType.methodType(double.class, Object.class);
            out.add(new Backend(name, source, lookup.unreflect(source.getMethod(enabled)).asType(asBoolean),
                    lookup.unreflect(source.getMethod(luminance)).asType(asInt),
                    lookup.unreflect(source.getMethod(x)).asType(asDouble), lookup.unreflect(source.getMethod(y)).asType(asDouble),
                    lookup.unreflect(source.getMethod(z)).asType(asDouble)));
            System.out.println("[ChromaLight] " + name + " dynamic lights detected: coloured dynamic lights enabled");
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("[ChromaLight] " + name + " dynamic light API changed; its colour is disabled: " + e);
        }
    }

    /** Client thread, once per tick. */
    public static void tick(ClientLevel level) {
        init();
        if (backends.length == 0 || level == null || !LightingControl.isEnabled() || !LightingControl.dynamicLights()) {
            if (sources.length != 0) sources = new float[0];
            return;
        }
        float[] out = new float[64 * STRIDE];
        int n = 0;
        java.util.List<String> lines = new java.util.ArrayList<>();
        LightPalette palette = NativeLighting.palette();
        for (Entity entity : level.entitiesForRendering()) {
            int lum = 0; double x = 0, y = 0, z = 0; String engine = "";
            for (int b = 0; b < backends.length; b++) {
                Backend backend = backends[b];
                if (backend == null || !backend.type().isInstance(entity)) continue;
                try {
                    if (!(boolean) backend.enabled().invoke((Object) entity)) continue;
                    int value = (int) backend.luminance().invoke((Object) entity);
                    if (value <= lum) continue;
                    lum = value; engine = backend.name();
                    x = (double) backend.x().invoke((Object) entity);
                    y = (double) backend.y().invoke((Object) entity);
                    z = (double) backend.z().invoke((Object) entity);
                } catch (Throwable e) {
                    System.err.println("[ChromaLight] " + backend.name() + " call failed; its dynamic light colour is disabled: " + e);
                    backends[b] = null;
                }
            }
            if (lum <= 0) continue;
            int rgb = colorOf(entity, palette);
            if (lines.size() < 32) lines.add(String.format(java.util.Locale.ROOT, "%s [%s] lum=%d #%06X at %.1f %.1f %.1f",
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), engine, lum, rgb, x, y, z));
            if (n + STRIDE > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            out[n] = (float) x; out[n + 1] = (float) y; out[n + 2] = (float) z; out[n + 3] = Math.min(lum, 15);
            out[n + 4] = NativeLightField.linear(rgb, 16);
            out[n + 5] = NativeLightField.linear(rgb, 8);
            out[n + 6] = NativeLightField.linear(rgb, 0);
            n += STRIDE;
        }
        sources = java.util.Arrays.copyOf(out, n);
        described = java.util.List.copyOf(lines);
    }

    /** Light-pack entity rule, provider, carried/dropped light item, burning, the entity's own texture, then a torch. */
    public static int colorOf(Entity entity, LightPalette palette) {
        int rgb = palette.entityColor(entity.getType());
        if (rgb >= 0) return rgb;
        rgb = EntityColorProviders.color(entity);
        if (rgb >= 0) return rgb;
        if (entity instanceof ItemEntity item) {
            rgb = itemColor(item.getItem(), palette);
            if (rgb < 0 && LightingControl.autoColors()) rgb = AutoColors.item(item.getItem());
            if (rgb >= 0) return rgb;
        }
        if (entity instanceof LivingEntity living) {
            int main = itemColor(living.getMainHandItem(), palette), off = itemColor(living.getOffhandItem(), palette);
            if (main >= 0 && off >= 0) return emission(living.getMainHandItem()) >= emission(living.getOffhandItem()) ? main : off;
            if (main >= 0) return main;
            if (off >= 0) return off;
        }
        if (entity.isOnFire()) return palette.paletteColor(Blocks.FIRE.defaultBlockState());
        if (LightingControl.autoColors() && !(entity instanceof ItemEntity)) {
            rgb = AutoColors.entity(entity);
            if (rgb >= 0) return rgb;
        }
        return palette.paletteColor(Blocks.TORCH.defaultBlockState());
    }
    public static int itemColor(ItemStack stack, LightPalette palette) {
        if (stack.isEmpty()) return LightPalette.NONE;
        int rgb = palette.itemColor(stack.getItem());
        if (rgb >= 0) return rgb;
        if (stack.getItem() instanceof BlockItem block && emission(stack) > 0)
            return palette.paletteColor(block.getBlock().defaultBlockState());
        return LightPalette.NONE;
    }
    @SuppressWarnings("deprecation")
    private static int emission(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block ? block.getBlock().defaultBlockState().getLightEmission() : 0;
    }

    /** LDL's own falloff at a point; 0 outside its 7.75-block radius. */
    public static float level(float[] s, int at, double x, double y, double z) {
        double dx = x - s[at], dy = y - s[at + 1], dz = z - s[at + 2];
        double d2 = dx * dx + dy * dy + dz * dz;
        return d2 > RADIUS * RADIUS ? 0 : (float) Math.max(0, s[at + 3] - Math.sqrt(d2) / RADIUS * 15);
    }
}
