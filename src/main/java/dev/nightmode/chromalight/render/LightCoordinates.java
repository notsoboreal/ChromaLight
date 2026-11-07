package dev.nightmode.chromalight.render;

/** RGB555 or a realtime table reference in spare UV2 bytes. Native light/AO bytes stay intact. */
public final class LightCoordinates {
    private LightCoordinates() {}
    public static int encode(int light, int rgb) {
        if (rgb < 0) return pack(light, 0x8000 | (rgb & 0x7fff));
        int quantized = ((rgb >>> 19) << 10) | (((rgb >>> 11) & 31) << 5) | ((rgb >>> 3) & 31);
        if (quantized == 32767) return light;
        int tag = quantized + 1; // Zero means an ordinary, unmodified native UV2.
        return pack(light, tag);
    }
    private static int pack(int light, int tag) { return (light & 0x00ff00ff) | ((tag & 255) << 8) | ((tag & 0xff00) << 16); }
    public static int realtimeIndex(int light) { int tag = ((light >>> 8) & 255) | ((light >>> 16) & 0xff00); return (tag & 0x8000) == 0 ? 0 : tag & 0x7fff; }
    public static int decode(int light) {
        int tag = ((light >>> 8) & 255) | ((light >>> 16) & 0xff00);
        if (tag == 0 || (tag & 0x8000) != 0) return 0xffffff;
        int q = tag - 1;
        return (((q >>> 10) * 255 / 31) << 16) | ((((q >>> 5) & 31) * 255 / 31) << 8) | ((q & 31) * 255 / 31);
    }
}
