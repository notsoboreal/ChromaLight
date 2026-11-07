package dev.nightmode.chromalight;

import dev.nightmode.chromalight.compat.ArsNouveauCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(modid = ChromaLight.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    private ClientModEvents() {}

    /** Light packs: assets/<namespace>/chromalight/lights/*.json in any resource pack. */
    @SubscribeEvent public static void onReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(LightPacks.INSTANCE);
    }

    @SubscribeEvent public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            FolderPacks.ensureFolder();
            if (ModList.get().isLoaded("ars_nouveau"))
                System.out.println("[ChromaLight] Ars Nouveau: per-light spell colours for " + ArsNouveauCompat.register() + " blocks");
        });
    }
}
