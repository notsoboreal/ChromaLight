package dev.nightmode.chromalight.core;

import java.util.Arrays;

/**
 * Reconstructs terrain color on a chunk meshing worker. Native light still controls
 * brightness. Source blending retains bounded independent paths, including emitters
 * hidden by the scalar maximum. A zero blend keeps the earlier native-gradient path.
 */
public final class NativeLightField {
    public interface View {
        int level(int x, int y, int z);
        /** RGB in the low 24 bits, actual emitted level in bits 24..27. */
        int source(int x, int y, int z);
        int opacity(int x, int y, int z);
        boolean open(int x, int y, int z, int nx, int ny, int nz, int direction);
        /**
         * Job-local realtime channel index (0..31) of the emitter at this position, or -1.
         * Called right after {@link #source} for the same position. Indices may be assigned
         * lazily, in discovery order, but must stay stable for the whole job.
         */
        default int realtimeSource(int x, int y, int z) { return -1; }
    }

    public static final int WHITE = 0xffffff, MAX_REALTIME = 32;
    private static final int LATTICE = 17;
    private static final int SIZE = 48, CELLS = SIZE * SIZE * SIZE;
    private static final int[] DX = {-1, 1, 0, 0, 0, 0}, DY = {0, 0, -1, 1, 0, 0}, DZ = {0, 0, 0, 0, -1, 1};
    private static final int[] LINEAR = new int[256];
    private static final int[] SRGB = new int[65536];
    private static final long LINEAR_WHITE = 0xffffffffffffL;
    static {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            LINEAR[i] = (int) Math.round((v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4)) * 65535);
        }
        for (int i = 0; i < SRGB.length; i++) {
            double v = i / 65535.0;
            SRGB[i] = (int) Math.round((v <= .0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - .055) * 255);
        }
    }

    private final long[] colors = new long[CELLS];
    private final int[] stamps = new int[CELLS];
    private final int[] lightSamples = new int[CELLS];
    private View view;
    private int minX, minY, minZ, generation;
    private long resolved, hits;
    private int realtimeCount;
    // Always maintained: realtime channels are discovered while colours are resolved.
    private final int[] realtimeMasks = new int[CELLS];
    private final long[] bakedColors = new long[CELLS];
    private final float[][] realtimeWeights = new float[MAX_REALTIME][];
    private final int[] latticeStamps = new int[LATTICE * LATTICE * LATTICE], latticeColors = new int[LATTICE * LATTICE * LATTICE];
    private float sampledRed, sampledGreen, sampledBlue;
    private float sampledBakedRed, sampledBakedGreen, sampledBakedBlue, sampledWeight;
    private long solveNanos;
    private MixedLightField mixed;
    private boolean mixedReady;
    private float sourceBlend;
    private int mixedSources;

    public void begin(View view, int sectionX, int sectionY, int sectionZ) {
        begin(view, sectionX, sectionY, sectionZ, 0f, 4);
    }
    /** Legacy overload; realtime channels are now discovered from the view. */
    public void begin(View view, int sectionX, int sectionY, int sectionZ, int ignoredRealtimeCount) {
        begin(view, sectionX, sectionY, sectionZ, 0f, 4);
    }
    /** Legacy overload; realtime channels are now discovered from the view. */
    public void begin(View view, int sectionX, int sectionY, int sectionZ, int ignoredRealtimeCount,
                      float sourceBlend, int mixedSources) {
        begin(view, sectionX, sectionY, sectionZ, sourceBlend, mixedSources);
    }
    public void begin(View view, int sectionX, int sectionY, int sectionZ, float sourceBlend, int mixedSources) {
        this.realtimeCount = 0;
        this.sourceBlend = Math.clamp(sourceBlend, 0f, 1f);
        this.mixedSources = Math.clamp(mixedSources, 2, 8);
        mixedReady = false;
        if (mixed != null) mixed.resetBounds();
        this.view = view;
        minX = sectionX - 16; minY = sectionY - 16; minZ = sectionZ - 16;
        if (++generation == (1 << 27)) {
            Arrays.fill(stamps, 0); Arrays.fill(lightSamples, 0); Arrays.fill(latticeStamps, 0); generation = 1;
        }
        resolved = hits = 0; solveNanos = 0;
    }
    public void end() { view = null; }
    public long resolvedCells() { return resolved; }
    public long cacheHits() { return hits; }
    /** Highest job-local realtime channel index seen so far, plus one. */
    public int realtimeCount() { return realtimeCount; }
    private void ensure(int channel) {
        if (channel < realtimeCount) return;
        for (int n = realtimeCount; n <= channel; n++) if (realtimeWeights[n] == null) realtimeWeights[n] = new float[CELLS];
        realtimeCount = channel + 1;
    }

    private int index(int x, int y, int z) {
        int rx = x - minX, ry = y - minY, rz = z - minZ;
        return rx < 0 || ry < 0 || rz < 0 || rx >= SIZE || ry >= SIZE || rz >= SIZE ? -1 : (ry * SIZE + rz) * SIZE + rx;
    }
    private long color(int x, int y, int z, int level) {
        int i = index(x, y, z);
        if (i < 0 || level <= 0 || view == null) return LINEAR_WHITE;
        if (stamps[i] == generation) { hits++; return colors[i]; }
        if (sourceBlend > 0) return mixedColor(i);
        int source = view.source(x, y, z);
        long result;
        int mask = 0;
        if ((source >>> 24) >= level) {
            result = pack(LINEAR[(source >>> 16) & 255], LINEAR[(source >>> 8) & 255], LINEAR[source & 255]);
            int realtime = view.realtimeSource(x, y, z);
            if (realtime >= 0 && realtime < MAX_REALTIME) {
                ensure(realtime);
                mask = 1 << realtime;
                realtimeWeights[realtime][i] = 1;
            }
            bakedColors[i] = mask == 0 ? result : 0;
        } else {
            long r = 0, g = 0, b = 0;
            long bakedR = 0, bakedG = 0, bakedB = 0;
            int count = 0, opacity = Math.max(1, view.opacity(x, y, z));
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (index(nx, ny, nz) < 0) continue;
                int next = level(nx, ny, nz);
                if (next < level + opacity || !view.open(x, y, z, nx, ny, nz, d)) continue;
                long c = color(nx, ny, nz, next);
                r += c & 65535; g += (c >>> 16) & 65535; b += c >>> 32;
                {
                    int child = index(nx, ny, nz), bits = realtimeMasks[child];
                    long baked = bakedColors[child];
                    bakedR += baked & 65535; bakedG += (baked >>> 16) & 65535; bakedB += baked >>> 32;
                    int fresh = bits & ~mask;
                    while (fresh != 0) { int n = Integer.numberOfTrailingZeros(fresh); realtimeWeights[n][i] = 0; fresh &= fresh - 1; }
                    mask |= bits;
                    while (bits != 0) { int n = Integer.numberOfTrailingZeros(bits); realtimeWeights[n][i] += realtimeWeights[n][child]; bits &= bits - 1; }
                }
                count++;
            }
            result = count == 0 ? LINEAR_WHITE : pack((int) (r / count), (int) (g / count), (int) (b / count));
            bakedColors[i] = count == 0 ? LINEAR_WHITE : pack((int) (bakedR / count), (int) (bakedG / count), (int) (bakedB / count));
            int bits = mask;
            while (bits != 0) { int n = Integer.numberOfTrailingZeros(bits); realtimeWeights[n][i] /= count; bits &= bits - 1; }
        }
        realtimeMasks[i] = mask;
        stamps[i] = generation;
        colors[i] = result;
        resolved++;
        return result;
    }
    private int level(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0 || view == null) return 0;
        if (mixedReady) return mixed.level(i);
        int sample = lightSamples[i];
        if ((sample >>> 4) != generation)
            lightSamples[i] = sample = (generation << 4) | Math.clamp(view.level(x, y, z), 0, 15);
        return sample & 15;
    }

    private long mixedColor(int cell) {
        if (!mixedReady) {
            if (mixed == null) mixed = new MixedLightField();
            long start = System.nanoTime();
            mixed.build(view, minX, minY, minZ, mixedSources);
            solveNanos += System.nanoTime() - start;
            mixedReady = true;
        }
        int strongest = mixed.path(cell, 0) & 15, sum = 0, ties = 0;
        for (int slot = 0; slot < mixedSources; slot++) {
            int light = mixed.path(cell, slot) & 15;
            if (light == 0) break;
            sum += light;
            if (light == strongest) ties++;
        }
        double r = 0, g = 0, b = 0, br = 0, bg = 0, bb = 0;
        int mask = 0;
        for (int slot = 0; slot < mixedSources && sum > 0; slot++) {
            int path = mixed.path(cell, slot), light = path & 15;
            if (light == 0) break;
            double weight = sourceBlend * light / sum;
            if (light == strongest) weight += (1 - sourceBlend) / ties;
            int source = path >>> 4, rgb = mixed.source(source);
            double red = LINEAR[(rgb >>> 16) & 255] * weight;
            double green = LINEAR[(rgb >>> 8) & 255] * weight;
            double blue = LINEAR[rgb & 255] * weight;
            r += red; g += green; b += blue;
            int realtime = mixed.realtime(source);
            if (realtime >= 0 && realtime < MAX_REALTIME) {
                ensure(realtime);
                int bit = 1 << realtime;
                if ((mask & bit) == 0) realtimeWeights[realtime][cell] = 0;
                realtimeWeights[realtime][cell] += (float) weight;
                mask |= bit;
            } else {
                br += red; bg += green; bb += blue;
            }
        }
        long result = sum == 0 ? LINEAR_WHITE : pack((int) Math.round(r), (int) Math.round(g), (int) Math.round(b));
        realtimeMasks[cell] = mask;
        bakedColors[cell] = sum == 0 ? LINEAR_WHITE : pack((int) Math.round(br), (int) Math.round(bg), (int) Math.round(bb));
        colors[cell] = result;
        stamps[cell] = generation;
        resolved++;
        return result;
    }
    public int blockColor(int x, int y, int z) {
        includeMixedBounds(x, y, z, x, y, z);
        return toSrgb(color(x, y, z, level(x, y, z)));
    }

    private void includeMixedBounds(int x0, int y0, int z0, int x1, int y1, int z1) {
        if (sourceBlend <= 0) return;
        if (mixed == null && x0 >= minX + 15 && y0 >= minY + 15 && z0 >= minZ + 15
                && x1 <= minX + 32 && y1 <= minY + 32 && z1 <= minZ + 32) return;
        if (mixed == null) mixed = new MixedLightField();
        boolean expanded = mixed.include(index(x0, y0, z0));
        expanded |= mixed.include(index(x1, y1, z1));
        if (expanded) mixedReady = false;
    }

    /** Vertex positions are section local. Interpolate voxel centres, weighted by native light. */
    public int vertexColor(float localX, float localY, float localZ) {
        // Most vertices sit on the 17^3 block-corner lattice, shared by up to 12 faces.
        int lattice = latticeIndex(localX, localY, localZ);
        if (lattice < 0) return sampleVertex(localX, localY, localZ, null);
        if (latticeStamps[lattice] == generation) return latticeColors[lattice];
        int color = sampleVertex(localX, localY, localZ, null);
        latticeColors[lattice] = color; latticeStamps[lattice] = generation;
        return color;
    }
    /** Index of an integer block-corner position inside the section, or -1. */
    public static int latticeIndex(float x, float y, float z) {
        int ix = (int) x, iy = (int) y, iz = (int) z;
        if (ix != x || iy != y || iz != z || (ix | iy | iz) < 0 || ix >= LATTICE || iy >= LATTICE || iz >= LATTICE) return -1;
        return (iy * LATTICE + iz) * LATTICE + ix;
    }
    /** Returns snapshot RGB plus optional source coefficients; caller owns the reused weights array. */
    public int sampleVertex(float localX, float localY, float localZ, float[] weights) {
        if (weights != null) Arrays.fill(weights, 0, MAX_REALTIME, 0f);
        sampledRed = sampledGreen = sampledBlue = 1;
        sampledBakedRed = sampledBakedGreen = sampledBakedBlue = 1;
        sampledWeight = 0;
        if (view == null) return WHITE;
        double x = (double) minX + 16 + localX - .5, y = (double) minY + 16 + localY - .5, z = (double) minZ + 16 + localZ - .5;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        includeMixedBounds(bx, by, bz, bx + 1, by + 1, bz + 1);
        double fx = x - bx, fy = y - by, fz = z - bz;
        double r = 0, g = 0, b = 0, sum = 0;
        double bakedR = 0, bakedG = 0, bakedB = 0;
        for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
            if (weight <= 0) continue;
            int xx = bx + dx, yy = by + dy, zz = bz + dz, l = level(xx, yy, zz);
            if (l == 0) continue;
            weight *= l * l;
            long c = color(xx, yy, zz, l);
            r += (c & 65535) * weight; g += ((c >>> 16) & 65535) * weight; b += (c >>> 32) * weight;
            {
                int i = index(xx, yy, zz); long baked = i < 0 ? LINEAR_WHITE : bakedColors[i];
                bakedR += (baked & 65535) * weight; bakedG += ((baked >>> 16) & 65535) * weight; bakedB += (baked >>> 32) * weight;
            }
            if (weights != null) {
                int i = index(xx, yy, zz), bits = i < 0 ? 0 : realtimeMasks[i];
                while (bits != 0) { int n = Integer.numberOfTrailingZeros(bits); weights[n] += (float) (realtimeWeights[n][i] * weight); bits &= bits - 1; }
            }
            sum += weight;
        }
        sampledWeight = (float) sum;
        if (sum > 0) {
            sampledRed = (float) (r / (sum * 65535)); sampledGreen = (float) (g / (sum * 65535)); sampledBlue = (float) (b / (sum * 65535));
            sampledBakedRed = (float) (bakedR / (sum * 65535));
            sampledBakedGreen = (float) (bakedG / (sum * 65535));
            sampledBakedBlue = (float) (bakedB / (sum * 65535));
            if (weights != null) for (int n = 0; n < realtimeCount; n++) weights[n] /= (float) sum;
        }
        return sum == 0 ? WHITE : toSrgb(pack((int) (r / sum), (int) (g / sum), (int) (b / sum)));
    }
    public float sampledRed() { return sampledRed; }
    public float sampledGreen() { return sampledGreen; }
    public float sampledBlue() { return sampledBlue; }
    /** Native light weight of the last sample (sum of trilinear weight x level^2); 0 in darkness. */
    public float sampledWeight() { return sampledWeight; }
    /** Time spent in multi-source solves since {@link #begin}. */
    public long solveNanos() { return solveNanos; }
    public float bakedRed() { return sampledBakedRed; }
    public float bakedGreen() { return sampledBakedGreen; }
    public float bakedBlue() { return sampledBakedBlue; }
    public static float linear(int rgb, int shift) { return LINEAR[(rgb >>> shift) & 255] / 65535f; }
    public static float srgb(float linear) { return SRGB[Math.clamp(Math.round(linear * 65535), 0, 65535)] / 255f; }
    private static long pack(int r, int g, int b) { return (long) r | ((long) g << 16) | ((long) b << 32); }
    private static int toSrgb(long rgb) { return (SRGB[(int) rgb & 65535] << 16) | (SRGB[(int) (rgb >>> 16) & 65535] << 8) | SRGB[(int) (rgb >>> 32) & 65535]; }
}
