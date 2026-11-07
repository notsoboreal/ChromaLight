package dev.nightmode.chromalight.mixin.sodium;

import dev.nightmode.chromalight.LightingControl;
import dev.nightmode.chromalight.render.TerrainShaders;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {
    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true)
    private static void chromalight$source(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        if (name.getNamespace().equals("sodium"))
            cir.setReturnValue(TerrainShaders.sodium(cir.getReturnValue(), name.getPath(), LightingControl.intensity()));
    }
}
