package dev.nightmode.chromalight.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;
import dev.nightmode.chromalight.CompatStatus;

/** Test bytecode availability without loading optional renderer classes during mixin discovery. */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
    private boolean available;
    @Override public void onLoad(String mixinPackage) {
        available = getClass().getClassLoader().getResource("net/caffeinemc/mods/sodium/client/world/LevelSlice.class") != null;
        CompatStatus.sodium = available;
        System.out.println("[ChromaLight] Native chunk lightmap: " + (available ? "Sodium integration enabled" : "vanilla renderer"));
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return available; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
