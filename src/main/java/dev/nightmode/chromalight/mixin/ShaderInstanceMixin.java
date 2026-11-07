package dev.nightmode.chromalight.mixin;

import dev.nightmode.chromalight.render.RealtimeLightGpu;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceMixin {
    @Inject(method = "apply", at = @At("RETURN"))
    private void chromalight$table(CallbackInfo ci) { RealtimeLightGpu.bind(((ShaderInstance) (Object) this).getId()); }
    @Inject(method = "clear", at = @At("HEAD"))
    private void chromalight$restore(CallbackInfo ci) { RealtimeLightGpu.restore(); }
    @Inject(method = "close", at = @At("HEAD"))
    private void chromalight$forget(CallbackInfo ci) { RealtimeLightGpu.forget(((ShaderInstance) (Object) this).getId()); }
}
