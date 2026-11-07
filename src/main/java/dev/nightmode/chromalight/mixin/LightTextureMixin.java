package dev.nightmode.chromalight.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.nightmode.chromalight.EntityTint;
import dev.nightmode.chromalight.SkyLight;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tints the sky share of the lightmap and copies the 16x16 CPU lightmap for entity tinting. */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @Shadow @Final private NativeImage lightPixels;

    /** After gamma, before upload: tints the sky share of the finished lightmap. */
    @Inject(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V"))
    private void chromalight$skyColor(float partialTick, CallbackInfo ci) { SkyLight.tintLightmap(lightPixels); }

    @Inject(method = "updateLightTexture", at = @At("TAIL"))
    private void chromalight$capture(float partialTick, CallbackInfo ci) { EntityTint.captureLightmap(lightPixels); }
}
