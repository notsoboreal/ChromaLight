package dev.nightmode.chromalight.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nightmode.chromalight.NativeLighting;

/** Adds hue to chunk UV2 without changing block colours, AO, normals, alpha or scalar lighting. */
public final class LightmapVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private int hue = 0xffffff;
    private LightmapVertexConsumer(VertexConsumer delegate) { this.delegate = delegate; }
    public static VertexConsumer wrap(VertexConsumer delegate) {
        return !NativeLighting.active() || delegate instanceof LightmapVertexConsumer ? delegate : new LightmapVertexConsumer(delegate);
    }
    @Override public VertexConsumer addVertex(float x, float y, float z) {
        hue = NativeLighting.vertexLight(x, y, z);
        delegate.addVertex(x, y, z); return this;
    }
    @Override public VertexConsumer setUv2(int u, int v) {
        int packed = LightCoordinates.encode((u & 65535) | ((v & 65535) << 16), hue);
        delegate.setUv2(packed & 65535, packed >>> 16); return this;
    }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { delegate.setColor(r, g, b, a); return this; }
    @Override public VertexConsumer setUv(float u, float v) { delegate.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { delegate.setNormal(x, y, z); return this; }
}
