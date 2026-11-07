float chroma_luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

float chroma_peak(vec3 color) {
    return max(color.r, max(color.g, color.b));
}

// Palette colors arrive as sRGB. Mix their channel ratios in linear RGB.
vec3 chroma_linear(vec3 color) {
    vec3 low = color / 12.92;
    vec3 high = pow((color + 0.055) / 1.055, vec3(2.4));
    return mix(low, high, step(vec3(0.04045), color));
}
