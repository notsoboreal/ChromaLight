package dev.nightmode.chromalight;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import dev.nightmode.chromalight.render.RealtimeLightGpu;
import dev.nightmode.chromalight.compat.DynamicLightsCompat;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.minecraft.world.level.material.FogType;

@EventBusSubscriber(modid = ChromaLight.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void onTick(ClientTickEvent.Post event) {
        NativeLighting.flushDirty();
        DynamicLightsCompat.tick(Minecraft.getInstance().level);
        EntityTint.tick();
        SkyLight.tick();
        AutoColors.tick();
        FolderPacks.tick();
    }
    /** Blood moon fog, open air only. */
    @SubscribeEvent public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!SkyLight.bloodMoon() && !SkyLight.fading()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || event.getCamera().getFluidInCamera() != FogType.NONE) return;
        float[] rgb = {event.getRed(), event.getGreen(), event.getBlue()};
        SkyLight.fogColor(level, rgb);
        event.setRed(rgb[0]); event.setGreen(rgb[1]); event.setBlue(rgb[2]);
    }
    /** Block tags arrive with the server's data; tag rules in light packs need them. */
    @SubscribeEvent public static void onTags(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED)
            Minecraft.getInstance().execute(NativeLighting::rebuildPalette);
    }
    @SubscribeEvent public static void onFrame(RenderFrameEvent.Pre event) {
        if (LightingControl.isEnabled()) HybridLighting.beforeFrame();
    }
    @SubscribeEvent public static void onFrameEnd(RenderFrameEvent.Post event) { RealtimeLightGpu.restore(); }
    @SubscribeEvent public static void onChunk(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level && level == Minecraft.getInstance().level) HybridLighting.syncWorld(level);
    }
    /** Also available to integrations whose emission changes without a BlockState update. Client thread only. */
    public static void blockChanged(ClientLevel level, int x, int y, int z) {
        NativeLighting.blockChanged(level, x, y, z);
    }
    @SubscribeEvent public static void onCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("chromalight")
                .then(Commands.literal("enable").executes(context -> SourceCommands.toggle(true)))
                .then(Commands.literal("disable").executes(context -> SourceCommands.toggle(false)))
                .then(SourceCommands.tree())
                .then(SourceCommands.typeTree())
                .then(SourceCommands.skyTree())
                .then(SourceCommands.bloodMoonTree())
                .then(SourceCommands.packTree())
                .then(Commands.literal("debug").executes(context -> SourceCommands.debug()))
                .then(Commands.literal("stats").executes(context -> {
                    var player = Minecraft.getInstance().player;
                    if (player != null) player.displayClientMessage(Component.literal(
                            NativeLighting.statistics()), false);
                    return 1;
                })));
    }
    @SubscribeEvent public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        NativeLighting.disconnect();
        SkyLight.disconnect();
        RealtimeLightGpu.close();
    }
}
