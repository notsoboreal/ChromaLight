package dev.nightmode.chromalight;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.api.distmarker.Dist;
import com.mojang.logging.LogUtils;

/** Client rendering is registered separately; the vanilla light engine is not replaced. */
@Mod(value = ChromaLight.MOD_ID, dist = Dist.CLIENT)
public final class ChromaLight {
    public static final String MOD_ID = "chromalight";
    public static final String VERSION = BuildVersion.VERSION;

    public ChromaLight(ModContainer container) {
        // "Config" in the mod list opens the light pack screen; video settings live on Sodium's page.
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> FolderPacks.screen(parent));
        LogUtils.getLogger().info("[ChromaLight] CODE {} loaded: baked terrain + limited realtime RGB, native lightmap, full render distance", VERSION);
    }
}
