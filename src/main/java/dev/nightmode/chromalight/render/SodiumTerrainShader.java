package dev.nightmode.chromalight.render;

final class SodiumTerrainShader {
    private SodiumTerrainShader() {}

    static String patch(String source, String path, float strength) {
        if (!path.equals("blocks/block_layer_opaque.vsh") || source.contains("a_ChromaLight")) {
            return source;
        }
        String patched = source.replace("texture(u_LightTex, _vert_tex_light_coord)",
                "chroma_lightmap(u_LightTex, _vert_tex_light_coord, chroma_source_hue(a_ChromaLight))");
        if (patched.equals(source)) {
            throw new IllegalStateException("ChromaLight: Sodium terrain lightmap expression changed");
        }
        return ShaderSources.beforeMain(patched, ShaderSources.terrain(strength, true));
    }
}
