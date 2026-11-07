package dev.nightmode.chromalight.compat;

import dev.nightmode.chromalight.NativeLighting;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexAttributeFormat;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.DefaultChunkMeshAttributes;
import net.caffeinemc.mods.sodium.client.render.vertex.VertexFormatAttribute;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Delegates Sodium's encoding unchanged, appending one normalized RGBA attribute. */
public final class ColoredChunkVertex extends CompactChunkVertex {
    @FunctionalInterface public interface HueSampler { int color(float x, float y, float z); }
    private final HueSampler sampler;
    public ColoredChunkVertex() { this(NativeLighting::vertexLight); }
    public ColoredChunkVertex(HueSampler sampler) { this.sampler = sampler; }
    public static final int COLORED_STRIDE = 24;
    public static final VertexFormatAttribute HUE = new VertexFormatAttribute("CHROMA_LIGHT",
            GlVertexAttributeFormat.UNSIGNED_BYTE, 4, true, false);
    private static final GlVertexFormat FORMAT = GlVertexFormat.builder(COLORED_STRIDE)
            .addElement(DefaultChunkMeshAttributes.POSITION, 0, 0)
            .addElement(DefaultChunkMeshAttributes.COLOR, 1, 8)
            .addElement(DefaultChunkMeshAttributes.TEXTURE, 2, 12)
            .addElement(DefaultChunkMeshAttributes.LIGHT_MATERIAL_INDEX, 3, 16)
            .addElement(HUE, 4, 20).build();

    @Override public GlVertexFormat getVertexFormat() { return FORMAT; }
    @Override public ChunkVertexEncoder getEncoder() {
        ChunkVertexEncoder original = super.getEncoder();
        return (ptr, material, vertices, section) -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long temp = stack.nmalloc(4, 4 * CompactChunkVertex.STRIDE);
                original.write(temp, material, vertices, section);
                for (int i = 0; i < 4; i++) {
                    MemoryUtil.memCopy(temp + (long) i * CompactChunkVertex.STRIDE, ptr, CompactChunkVertex.STRIDE);
                    var v = vertices[i];
                    int rgb = sampler.color(v.x, v.y, v.z);
                    // LWJGL supported desktop architectures are little endian: bytes R, G, B, A.
                    int rgba = rgb < 0 ? rgb & 0x7fff
                            : 0xff000000 | ((rgb & 255) << 16) | (rgb & 0xff00) | ((rgb >>> 16) & 255);
                    MemoryUtil.memPutInt(ptr + 20, rgba);
                    ptr += COLORED_STRIDE;
                }
            }
            return ptr;
        };
    }
}
