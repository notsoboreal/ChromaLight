package dev.nightmode.chromalight.render;

/** Patches only terrain vertex shaders, keeping native resource-pack materials and fragment shaders. */
public final class TerrainShaders {
    private TerrainShaders() {}

    public static String vanilla(String source, String name, boolean vertex, float strength) {
        return VanillaTerrainShader.patch(source, name, vertex, strength);
    }

    public static String sodium(String source, String path, float strength) {
        return SodiumTerrainShader.patch(source, path, strength);
    }
}
