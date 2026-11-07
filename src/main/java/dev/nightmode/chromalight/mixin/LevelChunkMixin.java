package dev.nightmode.chromalight.mixin;

import dev.nightmode.chromalight.NativeLighting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observer only: does not change return values, vanilla light data, or world state. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void chromalight$changed(BlockPos pos, BlockState state, boolean moving,
                                     CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null || cir.getReturnValue() == state) return;
        LevelChunk self = (LevelChunk) (Object) this;
        if (self.getLevel() instanceof ClientLevel level)
            NativeLighting.blockChanged(level, pos, cir.getReturnValue(), state);
    }
}
