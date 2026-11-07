package dev.nightmode.chromalight.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The light a particle renders with, including overrides for glowing particles. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Invoker("getLightColor") int chromalight$lightColor(float partialTick);
}
