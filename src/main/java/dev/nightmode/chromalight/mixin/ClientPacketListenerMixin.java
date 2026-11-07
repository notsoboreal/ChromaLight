package dev.nightmode.chromalight.mixin;

import dev.nightmode.chromalight.NativeLighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block-entity colour (e.g. Ars Nouveau lights) can arrive after the block itself. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void chromalight$blockEntity(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        NativeLighting.blockEntityChanged(Minecraft.getInstance().level, packet.getPos());
    }
}
