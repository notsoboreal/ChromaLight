package dev.nightmode.chromalight;

import dev.nightmode.chromalight.compat.DynamicLightsCompat;
import dev.nightmode.chromalight.core.NativeLightField;
import dev.nightmode.chromalight.core.HybridLightTable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Native chunk-light integration. No camera radius, frame flood queue, world writes or disk cache. */
public final class NativeLighting {
    public static final LightSettings SETTINGS = LightSettings.load(
            Minecraft.getInstance().gameDirectory.toPath().resolve("config/chromalight.properties"));
    static { LightingControl.load(SETTINGS); }
    private static volatile LightPalette palette;
    private static final ThreadLocal<Worker> WORKER = ThreadLocal.withInitial(Worker::new);
    private static Worker entityWorker; // client thread only
    private static final LongAdder BUILDS = new LongAdder(), CELLS = new LongAdder(), HITS = new LongAdder();
    private static final LongAdder SKIPPED_EDITS = new LongAdder(), CHANNEL_OVERFLOWS = new LongAdder();
    private static final LongAdder SOLVES = new LongAdder(), SOLVE_NANOS = new LongAdder(), DYNAMIC_JOBS = new LongAdder();
    private static final AtomicLong SOLVE_MAX = new AtomicLong();
    // Client-thread only. Deduplicate bursts of equal-intensity source/occlusion changes.
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
    private static ClientLevel dirtyLevel;
    private static final Direction[] DIRECTIONS = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
    private NativeLighting() {}

    /** Current colour rules; rebuilt on resource reload and tag sync. */
    public static LightPalette palette() {
        LightPalette current = palette;
        if (current == null) {
            synchronized (NativeLighting.class) {
                if (palette == null) palette = new LightPalette(LightPacks.layers(SETTINGS));
                current = palette;
            }
        }
        return current;
    }
    /** Client thread: light packs or tags changed. Rebuilds terrain when a world is loaded. */
    public static void rebuildPalette() {
        synchronized (NativeLighting.class) { palette = new LightPalette(LightPacks.layers(SETTINGS)); }
        EntityTint.invalidate();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && LightingControl.isEnabled()) mc.levelRenderer.allChanged();
    }

    public static void begin(BlockAndTintGetter view, SectionPos section, boolean snapshotLight) {
        if (!LightingControl.isEnabled()) return;
        Worker w = WORKER.get();
        w.begin(view, section, snapshotLight, LightingControl.sourceBlend());
    }
    public static void end() {
        Worker w = WORKER.get();
        if (!w.active) return;
        BUILDS.increment(); CELLS.add(w.field.resolvedCells()); HITS.add(w.field.cacheHits());
        long solve = w.field.solveNanos();
        if (solve > 0) { SOLVES.increment(); SOLVE_NANOS.add(solve); SOLVE_MAX.accumulateAndGet(solve, Math::max); }
        if (w.dynCount > 0) DYNAMIC_JOBS.increment();
        if (w.overflowed) CHANNEL_OVERFLOWS.increment();
        w.release();
    }
    public static boolean active() { return LightingControl.isEnabled() && WORKER.get().active; }
    public static int vertexColor(float x, float y, float z) { return WORKER.get().field.vertexColor(x, y, z); }
    /** Baked RGB24, or REALTIME_FLAG | a stable colour-table index. */
    public static int vertexLight(float x, float y, float z) {
        Worker worker = WORKER.get();
        return LightingControl.isEnabled() && worker.active ? worker.vertex(x, y, z) : 0xffffff;
    }
    public static int paletteColor(BlockState state) { return palette().paletteColor(state); }
    public static void saveSettings() {
        LightSettings.saveRuntime(Minecraft.getInstance().gameDirectory.toPath().resolve("config/chromalight.properties"));
    }

    /**
     * Colour of the light at a world position for CPU-side tinting (entities). Client thread.
     * Uses the fast native-gradient solve; realtime channels stay live through {@link EntityTint.Hue}.
     */
    static EntityTint.Hue sampleAt(ClientLevel level, double x, double y, double z) {
        if (entityWorker == null) entityWorker = new Worker();
        Worker w = entityWorker;
        SectionPos section = SectionPos.of(BlockPos.containing(x, y, z));
        w.begin(level, section, false, 0f);
        try {
            w.sample((float) (x - section.minBlockX()), (float) (y - section.minBlockY()), (float) (z - section.minBlockZ()));
            int count = w.count;
            HybridLightTable.Source[] channels = new HybridLightTable.Source[count];
            float[] weights = new float[count];
            for (int n = 0; n < count; n++) { channels[n] = w.channels[n]; weights[n] = w.weights[n]; }
            return new EntityTint.Hue(w.bakedR, w.bakedG, w.bakedB, channels, weights, w.lit);
        } finally {
            w.release();
        }
    }

    public static void blockChanged(ClientLevel level, int x, int y, int z) {
        if (!LightingControl.isEnabled()) return;
        if (dirtyLevel != level) { DIRTY.clear(); dirtyLevel = level; }
        EntityTint.invalidate();
        // An emitter/occluder can change hue without changing the native scalar light level.
        // The native maximum of 15 bounds this dependency; it does NOT bound render distance.
        for (int sy = (y - 15) >> 4; sy <= (y + 15) >> 4; sy++) {
            if (sy < level.getMinSection() || sy >= level.getMaxSection()) continue;
            for (int sz = (z - 15) >> 4; sz <= (z + 15) >> 4; sz++)
                for (int sx = (x - 15) >> 4; sx <= (x + 15) >> 4; sx++) DIRTY.add(SectionPos.asLong(sx, sy, sz));
        }
    }
    public static void blockChanged(ClientLevel level, BlockPos pos, BlockState before, BlockState after) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return; // Client-thread bookkeeping only; never throw from setBlockState.
        if (level == mc.level && before.getBlock() != after.getBlock()) HybridLighting.remove(pos);
        if (!LightingControl.isEnabled()) return;
        LightPalette colors = palette();
        int oldEmission = before.getLightEmission(level, pos), newEmission = after.getLightEmission(level, pos);
        boolean sourceChanged = oldEmission != newEmission || (oldEmission > 0
                && (colors.paletteColor(before) != colors.paletteColor(after) || BlockColorProviders.has(after.getBlock())));
        if (!sourceChanged) {
            if (before.getLightBlock(level, pos) == after.getLightBlock(level, pos)
                    && !before.useShapeForLightOcclusion() && !after.useShapeForLightOcclusion()) return;
            // An occluder can only reroute a colour path that touches this voxel. The light
            // engine has not applied this change yet, so these are the pre-change levels.
            if (!blockLightAround(level, pos)) { SKIPPED_EDITS.increment(); return; }
        }
        blockChanged(level, pos.getX(), pos.getY(), pos.getZ());
    }
    /** Block-entity data arrived (e.g. an Ars Nouveau light's colour). Client thread. */
    public static void blockEntityChanged(ClientLevel level, BlockPos pos) {
        if (level == null || !LightingControl.isEnabled()) return;
        BlockState state = level.getBlockState(pos);
        if (BlockColorProviders.has(state.getBlock()) && state.getLightEmission(level, pos) > 0)
            blockChanged(level, pos.getX(), pos.getY(), pos.getZ());
    }
    private static boolean blockLightAround(ClientLevel level, BlockPos pos) {
        var light = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        if (light.getLightValue(pos) > 0) return true;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction d : DIRECTIONS) if (light.getLightValue(next.setWithOffset(pos, d)) > 0) return true;
        return false;
    }
    public static void flushDirty() {
        Minecraft mc = Minecraft.getInstance();
        HybridLighting.syncWorld(mc.level);
        if (mc.level != dirtyLevel) { DIRTY.clear(); dirtyLevel = mc.level; }
        var it = DIRTY.iterator();
        while (it.hasNext()) {
            long s = it.nextLong();
            mc.levelRenderer.setSectionDirty(SectionPos.x(s), SectionPos.y(s), SectionPos.z(s));
        }
        DIRTY.clear();
    }
    public static void disconnect() {
        DIRTY.clear(); dirtyLevel = null; BUILDS.reset(); CELLS.reset(); HITS.reset();
        SKIPPED_EDITS.reset(); CHANNEL_OVERFLOWS.reset(); SOLVES.reset(); SOLVE_NANOS.reset(); SOLVE_MAX.set(0);
        DYNAMIC_JOBS.reset();
        HybridLighting.syncWorld(null);
        EntityTint.invalidate();
    }
    public static String statistics() {
        long solves = SOLVES.sum();
        return "ChromaLight " + ChromaLight.VERSION + " | renderer=" + (CompatStatus.sodium ? "Sodium" : "vanilla")
                + " | lighting=" + (LightingControl.isEnabled() ? "enabled" : "disabled")
                + " | blend=" + LightingControl.sourceBlend() + " | paths/voxel=" + LightingControl.mixedSources()
                + " | meshes=" + BUILDS.sum() + " | hue cells=" + CELLS.sum() + " | memo hits=" + HITS.sum()
                + String.format(java.util.Locale.ROOT, " | blend solves=%d avg=%.2fms max=%.2fms", solves,
                        solves == 0 ? 0 : SOLVE_NANOS.sum() / 1e6 / solves, SOLVE_MAX.get() / 1e6)
                + " | skipped dark edits=" + SKIPPED_EDITS.sum()
                + " | realtime blocks=" + HybridLighting.count() + " types=" + HybridLighting.typeCount()
                + " flicker channels=" + HybridLighting.flickerCount()
                + " | channels/section=" + LightingControl.realtimeSources() + " overflows=" + CHANNEL_OVERFLOWS.sum()
                + " | table=" + (HybridLighting.TABLE.size() - 1) + "/32767 fallbacks=" + HybridLighting.TABLE.fallbacks()
                + " | light packs=" + LightPacks.packFiles() + " rules=" + palette().rules() + " providers=" + BlockColorProviders.count()
                + " | dynamic lights=" + DynamicLightsCompat.backends() + " sources=" + DynamicLightsCompat.count() + " jobs=" + DYNAMIC_JOBS.sum()
                + " | entities tinted=" + EntityTint.tinted() + " skipped=" + EntityTint.blacklisted();
    }

    private static final class Worker implements NativeLightField.View {
        private static final int LATTICE = 17 * 17 * 17;
        final NativeLightField field = new NativeLightField();
        final DataLayer[] blockLight = new DataLayer[27];
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), neighbour = new BlockPos.MutableBlockPos();
        BlockAndTintGetter view;
        LightPalette colorsPalette;
        int sx, sy, sz, minHeight, maxHeight;
        boolean snapshotLight, active;
        // Job-local realtime channels, assigned in discovery order. A channel may cover any number of blocks.
        final HybridLightTable.Source[] channels = new HybridLightTable.Source[NativeLightField.MAX_REALTIME];
        final float[] weights = new float[NativeLightField.MAX_REALTIME];
        int channelCount, channelLimit;
        boolean dynamic, flicker, overflowed;
        long lastKey; int lastChannel;
        final Long2IntOpenHashMap vertexMemo = new Long2IntOpenHashMap();
        final int[] latticeStamps = new int[LATTICE], latticePayloads = new int[LATTICE];
        int job;
        HybridLighting.Snapshot colors;
        // LambDynamicLights sources that can reach this section: x, y, z, luminance, linear rgb.
        float[] dyn = new float[0]; int dynCount;
        // Result of sample(): linear snapshot colour, linear baked part, live channel count.
        float mixR, mixG, mixB, bakedR, bakedG, bakedB; int count; boolean lit;

        void begin(BlockAndTintGetter view, SectionPos s, boolean snapshotLight, float blend) {
            this.view = view; this.snapshotLight = snapshotLight;
            sx = s.x(); sy = s.y(); sz = s.z();
            minHeight = view.getMinBuildHeight(); maxHeight = view.getMaxBuildHeight();
            colors = HybridLighting.snapshot();
            colorsPalette = palette();
            channelLimit = LightingControl.realtimeSources();
            flicker = LightingControl.flicker() && channelLimit > 0;
            // Block-colour providers can report a cycling rainbow, which needs a realtime channel.
            dynamic = channelLimit > 0 && (flicker || !colors.realtime().isEmpty() || !colors.types().isEmpty()
                    || BlockColorProviders.count() > 0);
            channelCount = 0; overflowed = false; lastKey = Long.MIN_VALUE; lastChannel = -1;
            vertexMemo.clear(); vertexMemo.defaultReturnValue(0);
            if (++job == Integer.MAX_VALUE) { java.util.Arrays.fill(latticeStamps, 0); job = 1; }
            collectDynamicLights();
            // Vanilla's region captures block states, but delegates light reads to the level.
            // Copy the small native nibble layers once; all subsequent recursion uses snapshots.
            if (snapshotLight) {
                var listener = view.getLightEngine().getLayerListener(LightLayer.BLOCK);
                for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) {
                    DataLayer layer = listener.getDataLayerData(SectionPos.of(sx + x - 1, sy + y - 1, sz + z - 1));
                    blockLight[(y * 3 + z) * 3 + x] = layer == null ? null : layer.copy();
                }
            }
            field.begin(this, sx << 4, sy << 4, sz << 4, blend, LightingControl.mixedSources());
            active = true;
        }
        void release() {
            active = false; view = null; field.end();
            java.util.Arrays.fill(blockLight, null);
            colors = null; java.util.Arrays.fill(channels, null); vertexMemo.clear(); dynCount = 0;
        }
        private void collectDynamicLights() {
            dynCount = 0;
            float[] all = DynamicLightsCompat.snapshot();
            if (all.length == 0) return;
            double minX = (sx << 4) - DynamicLightsCompat.RADIUS, minY = (sy << 4) - DynamicLightsCompat.RADIUS, minZ = (sz << 4) - DynamicLightsCompat.RADIUS;
            double maxX = (sx << 4) + 16 + DynamicLightsCompat.RADIUS, maxY = (sy << 4) + 16 + DynamicLightsCompat.RADIUS, maxZ = (sz << 4) + 16 + DynamicLightsCompat.RADIUS;
            for (int at = 0; at < all.length; at += DynamicLightsCompat.STRIDE) {
                if (all[at] < minX || all[at] > maxX || all[at + 1] < minY || all[at + 1] > maxY || all[at + 2] < minZ || all[at + 2] > maxZ) continue;
                if (dyn.length < (dynCount + 1) * DynamicLightsCompat.STRIDE) dyn = java.util.Arrays.copyOf(dyn, Math.max(28, dyn.length * 2));
                System.arraycopy(all, at, dyn, dynCount * DynamicLightsCompat.STRIDE, DynamicLightsCompat.STRIDE);
                dynCount++;
            }
        }
        @Override public int level(int x, int y, int z) {
            if (y < minHeight || y >= maxHeight) return 0;
            if (!snapshotLight) return view.getBrightness(LightLayer.BLOCK, pos.set(x, y, z));
            int rx = (x >> 4) - sx + 1, ry = (y >> 4) - sy + 1, rz = (z >> 4) - sz + 1;
            if (rx < 0 || ry < 0 || rz < 0 || rx > 2 || ry > 2 || rz > 2) return 0;
            DataLayer layer = blockLight[(ry * 3 + rz) * 3 + rx];
            return layer == null ? 0 : layer.get(x & 15, y & 15, z & 15);
        }
        @Override public int source(int x, int y, int z) {
            if (y < minHeight || y >= maxHeight) return 0;
            BlockState state = view.getBlockState(pos.set(x, y, z));
            int emission = Math.clamp(state.getLightEmission(view, pos), 0, 15);
            long key = pos.asLong();
            lastKey = key; lastChannel = -1;
            if (emission == 0) return 0;
            int baked;
            HybridLightTable.Source rainbow = null;
            if (colors.baked().containsKey(key)) baked = colors.baked().get(key);
            else {
                baked = BlockColorProviders.color(view, pos, state);
                pos.set(x, y, z); // a provider may not mutate it, but stay defensive
                if (BlockColorProviders.isRainbow(baked)) {
                    int phase = BlockColorProviders.rainbowPhase(baked);
                    rainbow = dynamic ? HybridLighting.providerRainbow(phase) : null;
                    baked = BlockColorProviders.rainbowColor(phase);
                } else if (baked < 0) baked = colorsPalette.paletteColor(state);
            }
            if (!dynamic) return (emission << 24) | baked;
            HybridLightTable.Source channel = colors.realtime().get(key);
            if (channel == null) channel = rainbow;
            if (channel == null) channel = colors.types().get(state.getBlock());
            if (channel == null && flicker && colorsPalette.flickers(state)) channel = HybridLighting.flickerChannel(baked, key);
            if (channel == null) return (emission << 24) | baked;
            lastChannel = local(channel);
            return (emission << 24) | channel.rgb();
        }
        private int local(HybridLightTable.Source channel) {
            for (int n = 0; n < channelCount; n++) if (channels[n] == channel) return n;
            // Over the per-job cap the block keeps its current RGB as baked colour until the next rebuild.
            if (channelCount >= channelLimit) { overflowed = true; return -1; }
            channels[channelCount] = channel;
            return channelCount++;
        }
        @Override public int realtimeSource(int x, int y, int z) {
            if (!dynamic) return -1;
            if (BlockPos.asLong(x, y, z) != lastKey) source(x, y, z);
            return lastChannel;
        }

        /** Section-local sample with realtime weights and LambDynamicLights colour mixed in. */
        void sample(float x, float y, float z) {
            field.sampleVertex(x, y, z, weights);
            boolean live = dynamic && colors.epoch() == HybridLighting.TABLE.epoch();
            count = live ? Math.min(channelCount, field.realtimeCount()) : 0;
            mixR = field.sampledRed(); mixG = field.sampledGreen(); mixB = field.sampledBlue();
            bakedR = field.bakedRed(); bakedG = field.bakedGreen(); bakedB = field.bakedBlue();
            float native2 = field.sampledWeight();
            lit = native2 > 0;
            if (dynCount == 0) return;
            double wx = (sx << 4) + x, wy = (sy << 4) + y, wz = (sz << 4) + z;
            float total = 0, r = 0, g = 0, b = 0;
            for (int n = 0, at = 0; n < dynCount; n++, at += DynamicLightsCompat.STRIDE) {
                float level = DynamicLightsCompat.level(dyn, at, wx, wy, wz);
                if (level <= 0) continue;
                float w = level * level; // same level^2 weighting as native corners
                total += w; r += w * dyn[at + 4]; g += w * dyn[at + 5]; b += w * dyn[at + 6];
            }
            if (total <= 0) return;
            float sum = native2 + total, keep = native2 / sum;
            mixR = mixR * keep + r / sum; mixG = mixG * keep + g / sum; mixB = mixB * keep + b / sum;
            bakedR = bakedR * keep + r / sum; bakedG = bakedG * keep + g / sum; bakedB = bakedB * keep + b / sum;
            for (int n = 0; n < count; n++) weights[n] *= keep;
            lit = true;
        }

        int vertex(float x, float y, float z) {
            boolean live = dynamic && colors.epoch() == HybridLighting.TABLE.epoch();
            if (!live && dynCount == 0) return field.vertexColor(x, y, z);
            int lattice = NativeLightField.latticeIndex(x, y, z);
            long key = 0;
            if (lattice >= 0) {
                if (latticeStamps[lattice] == job) return latticePayloads[lattice];
            } else {
                // Model coordinates in the native compact format are bounded to [-8,24]. 1/4096
                // quantization here only deduplicates hue queries; it does not change vertex positions.
                key = (Math.round((x + 8) * 4096) & 0x3ffffL)
                        | ((Math.round((y + 8) * 4096) & 0x3ffffL) << 18) | ((Math.round((z + 8) * 4096) & 0x3ffffL) << 36);
                if (vertexMemo.containsKey(key)) return vertexMemo.get(key);
            }
            sample(x, y, z);
            int payload = !lit ? NativeLightField.WHITE
                    : (srgb(mixR) << 16) | (srgb(mixG) << 8) | srgb(mixB);
            for (int n = 0; n < count; n++) if (weights[n] > 0) {
                int index = HybridLighting.TABLE.blend(colors.epoch(), Math.max(0, bakedR),
                        Math.max(0, bakedG), Math.max(0, bakedB), channels, weights, count);
                if (index > 0) payload = HybridLightTable.REALTIME_FLAG | index;
                break;
            }
            if (lattice >= 0) { latticePayloads[lattice] = payload; latticeStamps[lattice] = job; }
            else vertexMemo.put(key, payload);
            return payload;
        }
        private static int srgb(float linear) { return Math.round(NativeLightField.srgb(linear) * 255); }
        @Override public int opacity(int x, int y, int z) {
            BlockState state = view.getBlockState(pos.set(x, y, z));
            return state.getLightBlock(view, pos);
        }
        @Override public boolean open(int x, int y, int z, int nx, int ny, int nz, int direction) {
            BlockState target = view.getBlockState(pos.set(x, y, z));
            BlockState from = view.getBlockState(neighbour.set(nx, ny, nz));
            // Trace from the brighter neighbour into this voxel, using native face occlusion.
            return LightEngine.getLightBlockInto(view, from, neighbour, target, pos,
                    DIRECTIONS[direction].getOpposite(), 1) < 16;
        }
    }
}
