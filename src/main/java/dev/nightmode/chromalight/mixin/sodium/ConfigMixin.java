package dev.nightmode.chromalight.mixin.sodium;

import dev.nightmode.chromalight.ChromaLight;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;
import java.util.List;

@Mixin(value = Config.class, remap = false)
public abstract class ConfigMixin {
    @Unique private List<ModOptions> chromalight$ordered;

    @Inject(method = "getModOptions", at = @At("RETURN"), cancellable = true)
    private void chromalight$first(CallbackInfoReturnable<List<ModOptions>> callback) {
        if (chromalight$ordered == null) {
            var ordered = new ArrayList<>(callback.getReturnValue());
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).configId().equals(ChromaLight.MOD_ID)) {
                    ordered.addFirst(ordered.remove(i));
                    break;
                }
            }
            chromalight$ordered = List.copyOf(ordered);
        }
        callback.setReturnValue(chromalight$ordered);
    }
}
