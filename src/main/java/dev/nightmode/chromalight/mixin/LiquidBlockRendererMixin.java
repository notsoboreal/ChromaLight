package dev.nightmode.chromalight.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nightmode.chromalight.render.LightmapVertexConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LiquidBlockRenderer.class)
public abstract class LiquidBlockRendererMixin {
    @ModifyVariable(method = "tesselate", at = @At("HEAD"), argsOnly = true)
    private VertexConsumer chromalight$consumer(VertexConsumer consumer) { return LightmapVertexConsumer.wrap(consumer); }
}
