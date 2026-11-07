package dev.nightmode.chromalight.mixin.sodium;

import dev.nightmode.chromalight.render.RealtimeLightGpu;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlProgram.class, remap = false)
public abstract class GlProgramMixin {
    @Inject(method = "bind", at = @At("RETURN"))
    private void chromalight$table(CallbackInfo ci) { RealtimeLightGpu.bind(((GlProgram<?>) (Object) this).handle()); }
    @Inject(method = "unbind", at = @At("HEAD"))
    private void chromalight$restore(CallbackInfo ci) { RealtimeLightGpu.restore(); }
    @Inject(method = "delete", at = @At("HEAD"))
    private void chromalight$forget(CallbackInfo ci) { RealtimeLightGpu.forget(((GlProgram<?>) (Object) this).handle()); }
}
