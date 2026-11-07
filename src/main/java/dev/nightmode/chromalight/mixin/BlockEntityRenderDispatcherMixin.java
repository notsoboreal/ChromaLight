package dev.nightmode.chromalight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.nightmode.chromalight.EntityTint;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chests, signs, banners, beds and other block entities drawn by their own renderer take the
 * color of the light around them, like entities. Sodium draws block entities through the same
 * dispatcher, so this covers both renderers.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @WrapOperation(method = "setupAndRender", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
    private static void chromalight$tint(BlockEntityRenderer<BlockEntity> renderer, BlockEntity blockEntity, float partialTick,
                                         PoseStack poseStack, MultiBufferSource buffers, int packedLight, int overlay,
                                         Operation<Void> original) {
        original.call(renderer, blockEntity, partialTick, poseStack,
                EntityTint.wrapBlockEntity(buffers, blockEntity, packedLight), packedLight, overlay);
    }
}
