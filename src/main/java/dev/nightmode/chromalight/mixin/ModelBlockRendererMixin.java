package dev.nightmode.chromalight.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.nightmode.chromalight.render.LightmapVertexConsumer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    // Both vanilla and NeoForge overloads are safe; wrapping is idempotent.
    @ModifyVariable(method = "tesselateBlock", at = @At("HEAD"), argsOnly = true)
    private VertexConsumer chromalight$consumer(VertexConsumer consumer) { return LightmapVertexConsumer.wrap(consumer); }
}
