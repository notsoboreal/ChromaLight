package dev.nightmode.chromalight.compat;

import dev.nightmode.chromalight.BlockColorProviders;
import dev.nightmode.chromalight.EntityColorProviders;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ars Nouveau colours. Since the Spell Styles update the chosen colour lives in the particle
 * timeline ({@code timeline.onTickEffect.particleOptions().colorProp().color()}); older data
 * only has {@code getColor()}. Spell projectiles carry it in their resolver's projectile timeline.
 * Everything is read by reflection, so Ars Nouveau stays an optional dependency.
 */
public final class ArsNouveauCompat {
    private static final MethodHandle MISSING = MethodHandles.constant(Object.class, null);
    private static final ConcurrentHashMap<String, MethodHandle> MEMBERS = new ConcurrentHashMap<>();
    private ArsNouveauCompat() {}

    public static int register() {
        int count = 0;
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            if (!entry.getKey().location().getNamespace().equals("ars_nouveau")) continue;
            Block block = entry.getValue();
            String path = entry.getKey().location().getPath();
            if (!(block instanceof EntityBlock) || !(path.contains("light") || path.contains("sconce") || path.contains("magelight"))) continue;
            BlockColorProviders.register(block, (level, pos, state) -> {
                BlockEntity tile = level.getBlockEntity(pos);
                return tile == null ? BlockColorProviders.NONE : blockColor(tile);
            });
            count++;
        }
        installRainbowClock();
        try {
            Class<?> projectile = Class.forName("com.hollingsworth.arsnouveau.common.entity.ColoredProjectile");
            EntityColorProviders.register(entity -> projectile.isInstance(entity) ? projectileColor(entity) : EntityColorProviders.NONE);
        } catch (ClassNotFoundException e) {
            System.err.println("[ChromaLight] Ars Nouveau projectile class not found; projectile colours use the default");
        }
        return count;
    }

    static int blockColor(Object tile) {
        Object color = tinted(get(get(tile, "getTimeline"), "onTickEffect"));
        if (color == null) color = get(tile, "getColor");
        // A rainbow light changes every tick: bake nothing, drive a shared realtime channel instead.
        if (color != null && color.getClass().getSimpleName().contains("Rainbow")) {
            Object offset = get(color, "tickOffset");
            int ticks = offset instanceof Integer value ? Math.floorMod(value, RAINBOW_TICKS) : 0;
            return BlockColorProviders.rainbow(ticks * BlockColorProviders.RAINBOW_PHASES / RAINBOW_TICKS);
        }
        return particleColor(color);
    }
    /** Ars Nouveau's Color.rainbowColor cycle, in client ticks. */
    public static final int RAINBOW_TICKS = 1536;

    /** Same clock (ClientInfo.ticksInGame) and formula (Color.rainbowColor) as Ars Nouveau's particles. */
    private static void installRainbowClock() {
        try {
            java.lang.reflect.Field ticks = Class.forName("com.hollingsworth.arsnouveau.client.ClientInfo").getField("ticksInGame");
            int step = RAINBOW_TICKS / BlockColorProviders.RAINBOW_PHASES;
            BlockColorProviders.setRainbowClock(phase -> {
                try { return arsRainbow(phase * step + ticks.getInt(null)); }
                catch (IllegalAccessException e) { return 0xffffff; }
            });
        } catch (ReflectiveOperationException e) {
            System.err.println("[ChromaLight] Ars Nouveau rainbow clock not found; rainbow lights use ChromaLight's own clock");
        }
    }
    static int arsRainbow(int timeStep) {
        int local = Math.abs(timeStep) % RAINBOW_TICKS, progress = local % 256, phase = local / 256;
        return (inPhase(phase + 4, progress) << 16) | (inPhase(phase + 2, progress) << 8) | inPhase(phase, progress);
    }
    private static int inPhase(int phase, int progress) {
        phase %= 6;
        return phase <= 1 ? 0 : phase == 2 ? progress : phase <= 4 ? 255 : 255 - progress;
    }

    private static volatile Object projectileTimelineType;
    private static final java.util.Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Exactly what Ars Nouveau renders: resolver().spell.particleTimeline().get(PROJECTILE_TIMELINE)
     * .trailEffect -> particle colour ("Trail -> Particle -> Color" in Spell Styles).
     */
    static int projectileColor(Object projectile) {
        Object spell = get(get(projectile, "resolver"), "spell");
        Object timelines = get(get(spell, "particleTimeline"), "timelines");
        if (!(timelines instanceof Map<?, ?> map)) return report("timelines", spell, get(projectile, "getParticleColor"));
        Object timeline = map.get(projectileType());
        if (timeline == null) for (Object candidate : map.values()) if (get(candidate, "trailEffect") != null) { timeline = candidate; break; }
        if (timeline == null) return report("projectile timeline", map.keySet(), get(projectile, "getParticleColor"));
        Object trail = get(timeline, "trailEffect");
        Object property = get(get(trail, "particleOptions"), "colorProp");
        Object color = get(property, "particleColor");
        if (color == null) return report("trail colour", trail, get(projectile, "getParticleColor"));
        if (Boolean.TRUE.equals(get(property, "isTintDisabled"))) return 0xffffff; // the trail really renders untinted
        return particleColor(color);
    }
    private static Object projectileType() {
        Object type = projectileTimelineType;
        if (type == null) {
            try {
                Object holder = Class.forName("com.hollingsworth.arsnouveau.api.registry.ParticleTimelineRegistry")
                        .getField("PROJECTILE_TIMELINE").get(null);
                type = holder instanceof java.util.function.Supplier<?> supplier ? supplier.get() : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                type = null;
            }
            projectileTimelineType = type == null ? MISSING : type;
        }
        return type == MISSING ? null : type;
    }
    /** Logs once which step of the colour path is missing, then uses Ars Nouveau's legacy projectile colour. */
    private static int report(String step, Object at, Object fallback) {
        if (REPORTED.add(step))
            System.err.println("[ChromaLight] Ars Nouveau projectile colour: no " + step + " (" + (at == null ? "null" : at.getClass().getName()) + "); using its legacy colour");
        return particleColor(fallback);
    }

    /** The entry's ParticleColor, or null when it is missing or tinting is disabled. */
    private static Object tinted(Object timelineEntry) {
        Object property = get(get(timelineEntry, "particleOptions"), "colorProp");
        if (property == null || Boolean.TRUE.equals(get(property, "isTintDisabled"))) return null;
        return get(property, "particleColor");
    }

    /** ParticleColor -> RGB24 (getRedInt/getGreenInt/getBlueInt), or NONE. Rainbow colours give the current hue. */
    private static int particleColor(Object color) {
        if (color == null) return BlockColorProviders.NONE;
        Object r = get(color, "getRedInt"), g = get(color, "getGreenInt"), b = get(color, "getBlueInt");
        if (!(r instanceof Integer red) || !(g instanceof Integer green) || !(b instanceof Integer blue)) return BlockColorProviders.NONE;
        return (Math.clamp(red, 0, 255) << 16) | (Math.clamp(green, 0, 255) << 8) | Math.clamp(blue, 0, 255);
    }

    /** Public no-arg method, else public field, by name; null when absent or on any failure. */
    static Object get(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        MethodHandle handle = MEMBERS.computeIfAbsent(type.getName() + '#' + name, key -> find(type, name));
        if (handle == MISSING) return null;
        try {
            return handle.invoke(target);
        } catch (Throwable e) {
            return null;
        }
    }
    private static MethodHandle find(Class<?> type, String name) {
        var lookup = MethodHandles.publicLookup();
        MethodType signature = MethodType.methodType(Object.class, Object.class);
        try {
            return lookup.unreflect(type.getMethod(name)).asType(signature);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) { }
        try {
            return lookup.unreflectGetter(type.getField(name)).asType(signature);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return MISSING;
        }
    }
}
