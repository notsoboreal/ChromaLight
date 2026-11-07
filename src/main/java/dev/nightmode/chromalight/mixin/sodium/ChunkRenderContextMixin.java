package dev.nightmode.chromalight.mixin.sodium;

import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import dev.nightmode.chromalight.LightingControl;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(value = ChunkRenderContext.class, remap = false)
public abstract class ChunkRenderContextMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void chromalight$sourceHalo(SectionPos origin, ClonedChunkSection[] sections, BoundingBox volume,
                                       List<?> renderers, CallbackInfo ci) {
        if (!LightingControl.isEnabled() || !chromalight$hasBlockLight(sections)) return;
        // All 27 section snapshots already exist. Unpack all of them, not just Sodium's
        // original two-block AO border: the native emitter may be up to 14 steps away.
        // Without any block light in the halo no colour is ever resolved, so the
        // block states outside Sodium's normal volume are never read.
        volume.encapsulate(new BoundingBox(origin.minBlockX() - 16, origin.minBlockY() - 16, origin.minBlockZ() - 16,
                origin.maxBlockX() + 16, origin.maxBlockY() + 16, origin.maxBlockZ() + 16));
    }

    private static boolean chromalight$hasBlockLight(ClonedChunkSection[] sections) {
        for (ClonedChunkSection section : sections) {
            if (section == null) continue;
            DataLayer light = section.getLightArray(LightLayer.BLOCK);
            if (light != null && !light.isDefinitelyFilledWith(0)) return true;
        }
        return false;
    }
}
