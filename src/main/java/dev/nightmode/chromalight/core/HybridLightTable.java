package dev.nightmode.chromalight.core;

import java.nio.FloatBuffer;
import java.util.*;

/**
 * World-scoped colour indirection. Geometry keeps stable indices; RGB/intensity edits only
 * dirty table entries. A source is a channel: any number of blocks may share one.
 */
public final class HybridLightTable {
    public static final int WIDTH = 256, HEIGHT = 128, CAPACITY = WIDTH * HEIGHT;
    public static final int REALTIME_FLAG = 0x80000000;
    /** Mixture keys are quantized (base in sqrt space, 5 bits; weights 1/32) so neighbouring vertices share entries instead of exhausting the table. */
    static final int BASE_STEPS = 31, WEIGHT_STEPS = 32;

    public static final class Source {
        private final long epoch;
        private final int index;
        private int[] records = new int[4];
        private int recordCount;
        private volatile int rgb;
        private float red, green, blue, intensity = 1;
        private Source(long epoch, int index, int rgb) { this.epoch = epoch; this.index = index; assign(rgb); }
        private void assign(int rgb) {
            this.rgb = rgb & 0xffffff;
            red = NativeLightField.linear(rgb, 16); green = NativeLightField.linear(rgb, 8); blue = NativeLightField.linear(rgb, 0);
        }
        public int rgb() { return rgb; }
        public int index() { return index; }
        public long epoch() { return epoch; }
        /** Current linear RGB and intensity; plain reads for CPU-side tinting (entities). */
        public float red() { return red; }
        public float green() { return green; }
        public float blue() { return blue; }
        public float intensity() { return intensity; }
        private void add(int record) {
            if (recordCount == records.length) records = Arrays.copyOf(records, records.length * 2);
            records[recordCount++] = record;
        }
        private void dirty(BitSet target) { for (int n = 0; n < recordCount; n++) target.set(records[n]); }
    }
    private record Key(long[] words) {
        @Override public int hashCode() { return Arrays.hashCode(words); }
        @Override public boolean equals(Object other) { return other instanceof Key k && Arrays.equals(words, k.words); }
    }
    private record Entry(long base, Source[] sources, int[] weights) {}
    private final Entry[] entries = new Entry[CAPACITY];
    private final Map<Key, Integer> indices = new HashMap<>();
    private final BitSet dirty = new BitSet();
    private volatile long epoch = 1;
    private volatile int size = 1;
    private volatile long revision = 1;
    private long updates, fallbacks;

    public long epoch() { return epoch; }
    public int size() { return size; }
    public long revision() { return revision; }
    public synchronized long updates() { return updates; }
    public synchronized long fallbacks() { return fallbacks; }
    public synchronized void reset() {
        epoch++; Arrays.fill(entries, null); indices.clear(); dirty.clear(); dirty.set(0);
        size = 1; updates = fallbacks = 0; revision++;
    }
    public synchronized Source create(int rgb) {
        if (size == CAPACITY) return null;
        int index = size++;
        Source source = new Source(epoch, index, rgb);
        entries[index] = new Entry(0, new Source[]{source}, new int[]{65535});
        source.add(index); dirty.set(index); revision++;
        return source;
    }
    public synchronized boolean color(Source source, int rgb) {
        if (source.epoch != epoch) return false;
        rgb &= 0xffffff;
        if (source.rgb == rgb) return false;
        source.assign(rgb); source.dirty(dirty); updates++; revision++;
        return true;
    }
    /** Multiplies only the block-light share of this channel's contribution; 1 = unchanged. */
    public synchronized boolean intensity(Source source, float value) {
        if (source.epoch != epoch) return false;
        value = Math.clamp(value, 0f, 1f);
        if (Math.abs(source.intensity - value) < 1f / 512) return false;
        source.intensity = value; source.dirty(dirty); updates++; revision++;
        return true;
    }
    private static int channel(float value) { return Math.clamp(Math.round(value * 65535), 0, 65535); }
    private static int quantizeBase(float linear) { return Math.clamp(Math.round((float) Math.sqrt(Math.max(0, linear)) * BASE_STEPS), 0, BASE_STEPS); }
    private static int dequantizeBase(int q) { float v = q / (float) BASE_STEPS; return channel(v * v); }

    /** A negative return means an obsolete world/job or no realtime contribution. */
    public synchronized int blend(long jobEpoch, float r, float g, float b, Source[] sources, float[] weights, int count) {
        if (jobEpoch != epoch) return -1;
        int qr = quantizeBase(r), qg = quantizeBase(g), qb = quantizeBase(b);
        int nonzero = 0, dominant = -1;
        float max = 0;
        long[] words = new long[count + 1];
        for (int n = 0; n < count; n++) {
            Source source = sources[n];
            if (source == null) continue;
            if (source.epoch != epoch) return -1;
            int w = Math.round(Math.clamp(weights[n], 0f, 1f) * WEIGHT_STEPS);
            if (w == 0) continue;
            if (weights[n] > max) { max = weights[n]; dominant = n; }
            words[1 + nonzero++] = ((long) source.index << 16) | w;
        }
        if (nonzero == 0) return -1;
        if (nonzero == 1 && (qr | qg | qb) == 0 && (words[1] & 65535) == WEIGHT_STEPS) return sources[dominant].index;
        words = Arrays.copyOf(words, nonzero + 1);
        Arrays.sort(words, 1, words.length); // canonical regardless of per-job channel discovery order
        words[0] = qr | ((long) qg << 6) | ((long) qb << 12);
        Key key = new Key(words);
        Integer existing = indices.get(key);
        if (existing != null) return existing;
        // Do not reuse an index still referenced by chunk VBOs; the dominant channel is the safe fallback.
        if (size == CAPACITY) { fallbacks++; return sources[dominant].index; }
        Source[] sparse = new Source[nonzero]; int[] coefficients = new int[nonzero];
        for (int n = 0, at = 0; n < count; n++) {
            Source source = sources[n];
            if (source == null) continue;
            int w = Math.round(Math.clamp(weights[n], 0f, 1f) * WEIGHT_STEPS);
            if (w != 0) { sparse[at] = source; coefficients[at++] = Math.round(w * 65535f / WEIGHT_STEPS); }
        }
        long base = (long) dequantizeBase(qr) | ((long) dequantizeBase(qg) << 16) | ((long) dequantizeBase(qb) << 32);
        int index = size++;
        entries[index] = new Entry(base, sparse, coefficients); indices.put(key, index); dirty.set(index);
        for (Source source : sparse) source.add(index);
        revision++;
        return index;
    }
    private void resolve(int index, float[] out) {
        Entry e = entries[index];
        if (e == null) { out[0] = out[1] = out[2] = 1; out[3] = 1; return; }
        float r = (e.base & 65535) / 65535f, g = ((e.base >>> 16) & 65535) / 65535f, b = (e.base >>> 32) / 65535f;
        float alpha = 1;
        for (int n = 0; n < e.sources.length; n++) {
            Source source = e.sources[n]; float w = e.weights[n] / 65535f;
            r += w * source.red; g += w * source.green; b += w * source.blue;
            alpha -= w * (1 - source.intensity);
        }
        out[0] = NativeLightField.srgb(r); out[1] = NativeLightField.srgb(g); out[2] = NativeLightField.srgb(b);
        out[3] = Math.clamp(alpha, 0f, 1f);
    }
    public synchronized float[] colorAt(int index) { float[] out = new float[4]; resolve(index, out); return out; }
    /** Updates a persistent GPU staging buffer and returns the first/end dirty texel indices. */
    public synchronized long drain(FloatBuffer target, boolean full) {
        if (full) dirty.set(0, size);
        int first = dirty.nextSetBit(0);
        if (first < 0) return -1;
        int end = dirty.length(); float[] rgba = new float[4];
        for (int index = first; index >= 0; index = dirty.nextSetBit(index + 1)) {
            resolve(index, rgba);
            for (int c = 0; c < 4; c++) target.put(index * 4 + c, rgba[c]);
        }
        dirty.clear(); return ((long) first << 32) | end;
    }
}
