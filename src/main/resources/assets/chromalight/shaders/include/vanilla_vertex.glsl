// UV2 keeps the native block/sky bytes, including fractional AO coordinates.
// The two spare high bytes hold either RGB555 + 1, or bit 15 and a table index.
ivec2 chroma_uv(ivec2 uv) {
    return uv & ivec2(255);
}

vec4 chroma_hue(ivec2 uv) {
    int tag = ((uv.x >> 8) & 255) | (((uv.y >> 8) & 255) << 8);
    if (tag == 0) {
        return vec4(1.0);
    }
    if ((tag & 32768) != 0) {
        return chroma_realtime(tag & 32767);
    }

    int rgb = tag - 1;
    return vec4(vec3((rgb >> 10) & 31, (rgb >> 5) & 31, rgb & 31) / 31.0, 1.0);
}
