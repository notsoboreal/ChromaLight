package dev.nightmode.chromalight.mixin.sodium;

import dev.nightmode.chromalight.compat.ColoredChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ChunkMeshFormats.class, remap = false)
public abstract class ChunkMeshFormatsMixin {
    @Redirect(method = "<clinit>", at = @At(value = "NEW", target = "net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/impl/CompactChunkVertex"))
    private static CompactChunkVertex chromalight$format() { return new ColoredChunkVertex(); }
}
