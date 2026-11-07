package dev.nightmode.chromalight.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.nightmode.chromalight.SkyLight;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Blood moon sky and clouds. Without it both return vanilla's value untouched. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @ModifyReturnValue(method = "getSkyColor", at = @At("RETURN"))
    private Vec3 chromalight$sky(Vec3 color) { return SkyLight.skyColor((ClientLevel) (Object) this, color); }

    @ModifyReturnValue(method = "getCloudColor", at = @At("RETURN"))
    private Vec3 chromalight$clouds(Vec3 color) { return SkyLight.cloudColor((ClientLevel) (Object) this, color); }
}
