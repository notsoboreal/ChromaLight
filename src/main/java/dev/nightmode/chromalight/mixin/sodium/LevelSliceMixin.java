package dev.nightmode.chromalight.mixin.sodium;

import dev.nightmode.chromalight.NativeLighting;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceMixin {
    @Inject(method = "copyData", at = @At("RETURN"))
    private void chromalight$begin(ChunkRenderContext context, CallbackInfo ci) {
        NativeLighting.begin((LevelSlice) (Object) this, context.getOrigin(), false);
    }
    @Inject(method = "reset", at = @At("HEAD"))
    private void chromalight$end(CallbackInfo ci) { NativeLighting.end(); }
}
