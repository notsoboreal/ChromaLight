// Four bytes follow Sodium's original 20-byte vertex. Alpha selects the layout:
// 255 = baked RGB8; 0 = a little-endian table index in the first two bytes.
layout(location = 4) in vec4 a_ChromaLight;

vec4 chroma_source_hue(vec4 data) {
    if (data.a > 0.5) {
        return vec4(data.rgb, 1.0);
    }
    ivec2 bytes = ivec2(round(data.xy * 255.0));
    return chroma_realtime(bytes.x | (bytes.y << 8));
}
