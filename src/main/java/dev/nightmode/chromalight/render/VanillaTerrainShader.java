package dev.nightmode.chromalight.render;

import java.util.Set;
import java.util.regex.Pattern;

final class VanillaTerrainShader {
    private static final Set<String> TERRAIN = Set.of(
            "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped",
            "rendertype_translucent", "rendertype_translucent_moving_block", "rendertype_tripwire");
    private static final Pattern TEXEL_FETCH = Pattern.compile(
            "texelFetch\\s*\\(\\s*Sampler2\\s*,\\s*UV2\\s*/\\s*16\\s*,\\s*0\\s*\\)");
    private static final Pattern FILTERED_FETCH = Pattern.compile(
            "minecraft_sample_lightmap\\s*\\(\\s*Sampler2\\s*,\\s*UV2\\s*\\)");

    private VanillaTerrainShader() {}

    static String patch(String source, String name, boolean vertex, float strength) {
        if (!vertex || !TERRAIN.contains(name) || source.contains("vec4 chroma_lightmap(")) {
            return source;
        }

        // Strip our tag before sampling. Keep each vanilla path's texel rounding
        // and filtering rules; changing those would also change ambient occlusion.
        String fetched = "chroma_lightmap(Sampler2, (floor(vec2(chroma_uv(UV2)) / 16.0) + vec2(0.5)) / 16.0, chroma_hue(UV2))";
        String filtered = "chroma_lightmap(Sampler2, clamp(vec2(chroma_uv(UV2)) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)), chroma_hue(UV2))";
        String patched = TEXEL_FETCH.matcher(source).replaceAll(fetched);
        patched = FILTERED_FETCH.matcher(patched).replaceAll(filtered);
        if (patched.equals(source)) {
            throw new IllegalStateException("ChromaLight: terrain lightmap expression not found in " + name);
        }
        return ShaderSources.beforeMain(patched, ShaderSources.terrain(strength, false));
    }
}
