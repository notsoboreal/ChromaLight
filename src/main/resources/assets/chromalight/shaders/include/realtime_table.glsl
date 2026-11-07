// RGB/intensity updates replace table entries; the index stored in the chunk stays valid.
// The CPU resolves each cached mixture from its baked RGB and realtime channel weights.
uniform sampler2D ChromaRealtimeTable;
uniform bool ChromaLightingEnabled = true;
uniform float ChromaTintStrength = CHROMA_STRENGTH;

vec4 chroma_realtime(int index) {
    if (!ChromaLightingEnabled) return vec4(1.0);
    ivec2 texel = ivec2(index & 255, index >> 8);
    return texelFetch(ChromaRealtimeTable, texel, 0);
}
