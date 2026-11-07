package dev.nightmode.chromalight.render;

import dev.nightmode.chromalight.HybridLighting;
import dev.nightmode.chromalight.LightingControl;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;

/** Binds the color table for a terrain program, then restores the previous owner of the unit. */
public final class RealtimeLightGpu {
    private static final int UNIT = 13;
    private static final ColorTableTexture TEXTURE = new ColorTableTexture();
    private static final Int2IntOpenHashMap LOCATIONS = new Int2IntOpenHashMap();
    private static final Int2IntOpenHashMap ENABLED_LOCATIONS = new Int2IntOpenHashMap();
    private static final Int2IntOpenHashMap STRENGTH_LOCATIONS = new Int2IntOpenHashMap();
    private static int previousTexture;
    private static int previousSampler;
    private static boolean bound;

    static {
        LOCATIONS.defaultReturnValue(Integer.MIN_VALUE);
        ENABLED_LOCATIONS.defaultReturnValue(Integer.MIN_VALUE);
        STRENGTH_LOCATIONS.defaultReturnValue(Integer.MIN_VALUE);
    }

    private RealtimeLightGpu() {}

    public static void bind(int program) {
        int enabledLocation = uniformLocation(program, "ChromaLightingEnabled", ENABLED_LOCATIONS);
        if (enabledLocation >= 0) GL20.glUniform1i(enabledLocation, LightingControl.isEnabled() ? 1 : 0);
        int strengthLocation = uniformLocation(program, "ChromaTintStrength", STRENGTH_LOCATIONS);
        if (strengthLocation >= 0) GL20.glUniform1f(strengthLocation, LightingControl.intensity());
        if (!LightingControl.isEnabled() || HybridLighting.TABLE.size() <= 1) return;
        int location = uniformLocation(program, "ChromaRealtimeTable", LOCATIONS);
        if (location < 0) return;

        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        if (!bound) {
            previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            previousSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            bound = true;
        }
        try {
            TEXTURE.bindAndUpdate(HybridLighting.TABLE);
            GL33.glBindSampler(UNIT, 0);
            GL20.glUniform1i(location, UNIT);
        } finally {
            GL13.glActiveTexture(active);
        }
    }

    public static void restore() {
        if (!bound) return;
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL33.glBindSampler(UNIT, previousSampler);
        GL13.glActiveTexture(active);
        bound = false;
    }

    public static void close() {
        restore();
        TEXTURE.close();
        LOCATIONS.clear();
        ENABLED_LOCATIONS.clear();
        STRENGTH_LOCATIONS.clear();
    }

    public static void forget(int program) {
        LOCATIONS.remove(program);
        ENABLED_LOCATIONS.remove(program);
        STRENGTH_LOCATIONS.remove(program);
    }

    private static int uniformLocation(int program, String name, Int2IntOpenHashMap cache) {
        int location = cache.get(program);
        if (location == Integer.MIN_VALUE) {
            location = GL20.glGetUniformLocation(program, name);
            cache.put(program, location);
        }
        return location;
    }
}
