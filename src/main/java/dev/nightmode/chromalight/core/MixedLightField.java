package dev.nightmode.chromalight.core;

import java.util.Arrays;

/** Keeps a bounded set of source paths per voxel instead of discarding every weaker light. */
final class MixedLightField {
    static final int SIZE = 48;
    private static final int PLANE = SIZE * SIZE;
    private static final int CELLS = SIZE * PLANE;
    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};
    private static final int[] STEP = {-1, 1, -PLANE, PLANE, -SIZE, SIZE};

    private final byte[] levels = new byte[CELLS];
    private final byte[] opacity = new byte[CELLS];
    private final byte[] checkedFaces = new byte[CELLS];
    private final byte[] openFaces = new byte[CELLS];
    private final int[] sources = new int[CELLS];
    private final byte[] realtime = new byte[CELLS];
    private final byte[] distanceToTarget = new byte[CELLS];
    private final int[] xDistances = new int[SIZE], yDistances = new int[SIZE], zDistances = new int[SIZE];
    private final Bucket[] buckets = new Bucket[16];
    private int[] paths = new int[0];
    private int[] groupKeys = new int[128], groupValues = new int[128];
    private int groupCount;
    private int width;
    private int targetMinX, targetMinY, targetMinZ, targetMaxX, targetMaxY, targetMaxZ;

    MixedLightField() {
        for (int i = 1; i < buckets.length; i++) buckets[i] = new Bucket();
        resetBounds();
    }

    void resetBounds() {
        targetMinX = targetMinY = targetMinZ = 15;
        targetMaxX = targetMaxY = targetMaxZ = 32;
    }

    boolean include(int cell) {
        if (cell < 0) return false;
        int x = cell % SIZE, z = (cell / SIZE) % SIZE, y = cell / PLANE;
        if (x >= targetMinX && x <= targetMaxX && y >= targetMinY && y <= targetMaxY && z >= targetMinZ && z <= targetMaxZ) return false;
        targetMinX = Math.min(targetMinX, x); targetMaxX = Math.max(targetMaxX, x);
        targetMinY = Math.min(targetMinY, y); targetMaxY = Math.max(targetMaxY, y);
        targetMinZ = Math.min(targetMinZ, z); targetMaxZ = Math.max(targetMaxZ, z);
        return true;
    }

    void build(NativeLightField.View view, int minX, int minY, int minZ, int width) {
        this.width = width;
        if (paths.length < CELLS * width) paths = new int[CELLS * width];
        Arrays.fill(paths, 0, CELLS * width, 0);
        Arrays.fill(opacity, (byte) 0);
        Arrays.fill(checkedFaces, (byte) 0);
        Arrays.fill(openFaces, (byte) 0);
        Arrays.fill(groupKeys, 0);
        groupCount = 0;
        for (int i = 1; i < buckets.length; i++) buckets[i].clear();
        for (int i = 0; i < SIZE; i++) {
            xDistances[i] = distance(i, targetMinX, targetMaxX);
            yDistances[i] = distance(i, targetMinY, targetMaxY);
            zDistances[i] = distance(i, targetMinZ, targetMaxZ);
        }

        // Sources must be found independently of the scalar maximum. A level-10
        // campfire still emits blue when another source raises its native voxel to 14.
        for (int y = 0; y < SIZE; y++) for (int z = 0; z < SIZE; z++) {
            int row = (y * SIZE + z) * SIZE, rowDistance = yDistances[y] + zDistances[z];
            if (rowDistance >= 15) { Arrays.fill(levels, row, row + SIZE, (byte) 0); continue; }
            for (int x = 0; x < SIZE; x++) {
            int cell = row + x;
            int distance = xDistances[x] + rowDistance;
            distanceToTarget[cell] = (byte) Math.min(distance, 15);
            if (distance >= 15) { levels[cell] = 0; continue; }
            int xx = minX + x, yy = minY + y, zz = minZ + z;
            int nativeLevel = Math.clamp(view.level(xx, yy, zz), 0, 15);
            levels[cell] = (byte) nativeLevel;
            // An emitter's own native level is at least its emission, so a cell whose level
            // cannot outlast the distance to the section cannot hold a relevant emitter.
            if (nativeLevel <= distance) continue;
            int source = view.source(xx, yy, zz);
            int emission = (source >>> 24) & 15;
            if (emission <= distance) continue;
            sources[cell] = source;
            int dynamic = view.realtimeSource(xx, yy, zz);
            realtime[cell] = (byte) (dynamic >= 0 && dynamic < NativeLightField.MAX_REALTIME ? dynamic : -1);
            // Repeated lamps of one baked color share a channel. They must not
            // fill every slot and evict a nearby source of a different color.
            int key = realtime[cell] < 0 ? source & 0xffffff : 0x01000000 | realtime[cell];
            offer(cell, group(key, cell), emission);
            }
        }

        // Every edge costs at least one level. Descending buckets give each
        // source its strongest path; duplicate routes never add more energy.
        for (int light = 15; light > 1; light--) {
            Bucket bucket = buckets[light];
            while (bucket.hasNext()) {
                long work = bucket.next();
                int cell = (int) (work >>> 32), code = (int) work;
                if (!contains(cell, code)) continue;
                int x = cell % SIZE, z = (cell / SIZE) % SIZE, y = cell / PLANE;
                for (int d = 0; d < 6; d++) {
                    int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= SIZE || ny >= SIZE || nz >= SIZE) continue;
                    int target = cell + STEP[d];
                    if (levels[target] == 0) continue;
                    int attenuation = opacity[target] & 255;
                    if (attenuation == 0) {
                        attenuation = Math.max(1, Math.clamp(view.opacity(minX + nx, minY + ny, minZ + nz), 0, 15));
                        opacity[target] = (byte) attenuation;
                    }
                    int remaining = light - attenuation;
                    if (remaining <= distanceToTarget[target] || !canImprove(target, code >>> 4, remaining)) continue;
                    int bit = 1 << (d ^ 1);
                    if ((checkedFaces[target] & bit) == 0) {
                        checkedFaces[target] |= (byte) bit;
                        if (view.open(minX + nx, minY + ny, minZ + nz,
                                minX + x, minY + y, minZ + z, d ^ 1)) openFaces[target] |= (byte) bit;
                    }
                    if ((openFaces[target] & bit) != 0) offer(target, code >>> 4, remaining);
                }
            }
        }
    }

    int level(int cell) { return levels[cell] & 15; }
    int path(int cell, int slot) { return paths[cell * width + slot]; }
    int source(int cell) { return sources[cell]; }
    int realtime(int cell) { return realtime[cell]; }

    private static int distance(int value, int min, int max) {
        return value < min ? min - value : Math.max(0, value - max);
    }

    private static int slot(int key, int[] keys) {
        int hash = key * 0x9e3779b9;
        int slot = (hash ^ (hash >>> 16)) & (keys.length - 1);
        while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
        return slot;
    }

    private int group(int key, int source) {
        key++;
        int slot = slot(key, groupKeys);
        if (groupKeys[slot] != 0) return groupValues[slot];
        if (groupCount * 2 >= groupKeys.length) {
            int[] oldKeys = groupKeys, oldValues = groupValues;
            groupKeys = new int[oldKeys.length * 2]; groupValues = new int[groupKeys.length];
            for (int i = 0; i < oldKeys.length; i++) if (oldKeys[i] != 0) {
                int target = slot(oldKeys[i], groupKeys);
                groupKeys[target] = oldKeys[i]; groupValues[target] = oldValues[i];
            }
            slot = slot(key, groupKeys);
        }
        groupCount++;
        groupKeys[slot] = key; groupValues[slot] = source;
        return source;
    }

    private boolean contains(int cell, int code) {
        int start = cell * width;
        for (int i = 0; i < width; i++) if (paths[start + i] == code) return true;
        return false;
    }

    private boolean canImprove(int cell, int source, int light) {
        int start = cell * width;
        for (int i = 0; i < width; i++) {
            int old = paths[start + i];
            if (old != 0 && (old >>> 4) == source) return (old & 15) < light;
        }
        return (paths[start + width - 1] & 15) < light;
    }

    private void offer(int cell, int source, int light) {
        int start = cell * width;
        for (int i = 0; i < width; i++) {
            int old = paths[start + i];
            if (old == 0 || (old >>> 4) != source) continue;
            if ((old & 15) >= light) return;
            System.arraycopy(paths, start + i + 1, paths, start + i, width - i - 1);
            paths[start + width - 1] = 0;
            break;
        }
        int insertion = 0;
        while (insertion < width && (paths[start + insertion] & 15) >= light) insertion++;
        if (insertion == width) return;
        System.arraycopy(paths, start + insertion, paths, start + insertion + 1, width - insertion - 1);
        int code = (source << 4) | light;
        paths[start + insertion] = code;
        buckets[light].add(((long) cell << 32) | (code & 0xffffffffL));
    }

    private static final class Bucket {
        private long[] entries = new long[256];
        private int read, size;
        void clear() { read = size = 0; }
        boolean hasNext() { return read < size; }
        long next() { return entries[read++]; }
        void add(long entry) {
            if (size == entries.length) entries = Arrays.copyOf(entries, size * 2);
            entries[size++] = entry;
        }
    }
}
