package dev.nightmode.chromalight;

import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Colour of an entity's own dynamic light (spell projectiles, glowing mobs). Client thread.
 * Providers return {@link #NONE} for entities they do not handle; the first answer wins.
 */
public final class EntityColorProviders {
    public static final int NONE = -1;
    @FunctionalInterface public interface Provider { int color(Entity entity); }
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    private EntityColorProviders() {}

    public static void register(Provider provider) { PROVIDERS.add(provider); }

    public static int color(Entity entity) {
        for (Provider provider : PROVIDERS) {
            try {
                int rgb = provider.color(entity);
                if (rgb >= 0) return rgb & 0xffffff;
            } catch (RuntimeException ignored) { }
        }
        return NONE;
    }
}
