// This runs before material texture, transparency and fog. Sample the live
// lightmap so day/night, weather, gamma and night vision do not get baked.
// source.rgb = sRGB hue, source.a = block-light intensity (1 = unchanged, < 1 = flicker dip).
vec4 chroma_lightmap(sampler2D lightmap, vec2 uv, vec4 source) {
    vec4 nativeLight = texture(lightmap, uv);
    // Keep cached meshes usable while a manual toggle rebuilds terrain jobs.
    if (!ChromaLightingEnabled) return nativeLight;
    bool white = all(greaterThan(source.rgb, vec3(0.995)));
    if (white && source.a > 0.995) {
        return nativeLight;
    }

    // Split the texel into what the sky gives at this sky level (block light 0, same row)
    // and what block light adds on top. Only the block-light part takes the source colour,
    // so outdoor light keeps the full hue instead of washing out to white.
    const float firstTexel = 0.5 / 16.0;
    vec3 skyOnly = texture(lightmap, vec2(firstTexel, uv.y)).rgb;
    vec3 blockPart = max(nativeLight.rgb - skyOnly, vec3(0.0));
    float blockPeak = chroma_peak(blockPart);
    if (blockPeak < 0.0001) {
        return nativeLight;
    }

    vec3 colored = blockPart;
    if (!white) {
        vec3 color = chroma_linear(source.rgb);
        float colorPeak = chroma_peak(color);
        if (colorPeak < 0.0001) {
            colored = blockPart * (1.0 - ChromaTintStrength);
        } else {
            colored = blockPart * mix(vec3(1.0), color / colorPeak, ChromaTintStrength);
            // Keep the brightest channel: saturated colours stay bright instead of turning grey.
            colored *= blockPeak / max(chroma_peak(colored), 0.0001);
        }
    }
    // Flicker dims only the block-light part; sky light and gameplay levels stay native.
    colored *= source.a;
    return vec4(min(skyOnly + colored, vec3(1.0)), nativeLight.a);
}
