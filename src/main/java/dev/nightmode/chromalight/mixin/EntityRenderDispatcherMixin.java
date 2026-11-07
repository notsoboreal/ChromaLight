package dev.nightmode.chromalight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.nightmode.chromalight.EntityTint;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Wraps the buffers of one entity render call; vertices keep their native light coordinates. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource chromalight$tint(MultiBufferSource buffers, @Local(argsOnly = true) Entity entity,
                                               @Local(argsOnly = true, ordinal = 1) float partialTick,
                                               @Local(argsOnly = true) int packedLight) {
        return EntityTint.wrap(buffers, entity, packedLight, partialTick);
    }
}
