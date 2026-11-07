package dev.nightmode.chromalight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nightmode.chromalight.EntityTint;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Coloured light on particles (block breaking, smoke, drips...): one buffer wrapper per particle. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    // NeoForge has two overloads; the short one only delegates to the one with frustum + predicate.
    @WrapOperation(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
    private void chromalight$tint(Particle particle, VertexConsumer buffer, Camera camera, float partialTick, Operation<Void> original) {
        int light = ((ParticleAccessor) particle).chromalight$lightColor(partialTick);
        original.call(particle, EntityTint.wrapParticle(buffer, particle, light), camera, partialTick);
    }
}
