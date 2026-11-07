package dev.nightmode.chromalight.compat;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import dev.nightmode.chromalight.EntityTint;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.minecraft.client.renderer.LightTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Entity tint on Sodium's packed-vertex path: scales the colour bytes, then forwards the batch. */
public final class SodiumTintConsumer extends EntityTint.Consumer implements VertexBufferWriter {
    public SodiumTintConsumer(VertexConsumer delegate, float r, float g, float b) { super(delegate, r, g, b); }

    @Override public boolean canUseIntrinsics() { return VertexBufferWriter.tryOf(delegate) != null; }
    @Override public boolean canUseIntrinsics(VertexFormat format) { return VertexBufferWriter.tryOf(delegate, format) != null; }

    @Override
    public void push(MemoryStack stack, long ptr, int count, VertexFormat format) {
        VertexBufferWriter target = VertexBufferWriter.of(delegate);
        if (!format.contains(VertexFormatElement.COLOR)) { target.push(stack, ptr, count, format); return; }
        int stride = format.getVertexSize(), color = format.getOffset(VertexFormatElement.COLOR);
        int light = format.contains(VertexFormatElement.UV2) ? format.getOffset(VertexFormatElement.UV2) : -1;
        // The caller may reuse its buffer: tint a copy, like VertexBufferWriter.copyInto.
        long copy = stack.nmalloc(count * stride);
        MemoryUtil.memCopy(ptr, copy, (long) count * stride);
        for (int i = 0; i < count; i++) {
            long vertex = copy + (long) i * stride;
            if (light >= 0 && MemoryUtil.memGetInt(vertex + light) == LightTexture.FULL_BRIGHT) continue;
            long at = vertex + color; // bytes R, G, B, A
            MemoryUtil.memPutByte(at, (byte) scale(MemoryUtil.memGetByte(at) & 255, r));
            MemoryUtil.memPutByte(at + 1, (byte) scale(MemoryUtil.memGetByte(at + 1) & 255, g));
            MemoryUtil.memPutByte(at + 2, (byte) scale(MemoryUtil.memGetByte(at + 2) & 255, b));
        }
        target.push(stack, copy, count, format);
    }
}
