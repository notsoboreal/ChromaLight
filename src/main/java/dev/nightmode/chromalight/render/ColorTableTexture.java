package dev.nightmode.chromalight.render;

import dev.nightmode.chromalight.core.HybridLightTable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;

/** Owns one world color texture and reuses its staging buffer across RGB updates. */
final class ColorTableTexture {
    private int texture;
    private FloatBuffer pixels;
    private long uploadedEpoch;
    private long uploadedRevision;

    // The caller has selected the texture unit. No GL calls are made if its program
    // has no ChromaRealtimeTable uniform.
    void bindAndUpdate(HybridLightTable table) {
        if (texture != 0 && uploadedEpoch == table.epoch() && uploadedRevision == table.revision()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            return;
        }

        // Other renderers can leave a PBO or a strided unpack layout bound.
        // Direct FloatBuffer uploads require a clean layout, restored afterwards.
        int pbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int swap = GL11.glGetInteger(GL11.GL_UNPACK_SWAP_BYTES);
        int rowLength = GL11.glGetInteger(GL12.GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_PIXELS);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_PIXELS, 0);

            allocateOrBind();
            long epoch = table.epoch();
            boolean newWorld = epoch != uploadedEpoch;
            if (newWorld) {
                // Neutral white at full intensity: stale indices from the previous world stay harmless.
                for (int i = 0; i < pixels.capacity(); i++) pixels.put(i, 1);
            }
            long revision = table.revision();
            long dirtyRange = table.drain(pixels, newWorld);
            uploadRows(newWorld ? HybridLightTable.CAPACITY : dirtyRange); // whole table once per world
            uploadedEpoch = epoch;
            uploadedRevision = revision;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, swap);
            GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_PIXELS, skipPixels);
        }
    }

    private void allocateOrBind() {
        if (texture != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            return;
        }
        texture = GL11.glGenTextures();
        pixels = MemoryUtil.memCallocFloat(HybridLightTable.CAPACITY * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F,
                HybridLightTable.WIDTH, HybridLightTable.HEIGHT, 0,
                GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
    }

    private void uploadRows(long dirtyRange) {
        if (dirtyRange < 0) return;
        int firstRow = (int) (dirtyRange >>> 32) / HybridLightTable.WIDTH;
        int endRow = ((int) dirtyRange + HybridLightTable.WIDTH - 1) / HybridLightTable.WIDTH;
        pixels.position(firstRow * HybridLightTable.WIDTH * 4);
        pixels.limit(endRow * HybridLightTable.WIDTH * 4);
        try {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, firstRow,
                    HybridLightTable.WIDTH, endRow - firstRow, GL11.GL_RGBA, GL11.GL_FLOAT, pixels);
        } finally {
            pixels.clear();
        }
    }

    void close() {
        if (texture != 0) GL11.glDeleteTextures(texture);
        if (pixels != null) MemoryUtil.memFree(pixels);
        texture = 0;
        pixels = null;
        uploadedEpoch = 0;
        uploadedRevision = 0;
    }
}
