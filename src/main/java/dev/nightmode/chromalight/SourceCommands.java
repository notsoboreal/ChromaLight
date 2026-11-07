package dev.nightmode.chromalight;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

/** Client-only test/integration controls. They do not modify blocks or server/world files. */
public final class SourceCommands {
    private SourceCommands() {}

    public static int toggle(boolean enabled) {
        boolean changed = LightingControl.setEnabled(enabled);
        if (changed) {
            NativeLighting.saveSettings();
            var minecraft = Minecraft.getInstance();
            if (minecraft.level != null) minecraft.levelRenderer.allChanged();
        }
        String state = enabled ? "enabled" : "disabled";
        String key = "commands.chromalight." + (changed ? state : "already_" + state);
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable(key), false);
        return 1;
    }

    /** The looked-at emitter only. */
    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("source")
                .then(Commands.literal("baked").then(Commands.argument("rgb", StringArgumentType.greedyString())
                        .executes(c -> set(StringArgumentType.getString(c, "rgb"), false))))
                .then(Commands.literal("realtime").then(Commands.argument("rgb", StringArgumentType.greedyString())
                        .executes(c -> set(StringArgumentType.getString(c, "rgb"), true))))
                .then(Commands.literal("rainbow").executes(c -> rainbow(4))
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(.2, 120))
                                .executes(c -> rainbow(DoubleArgumentType.getDouble(c, "seconds")))))
                .then(Commands.literal("clear").executes(c -> {
                    BlockPos pos = target(false); if (pos == null) return 0;
                    HybridLighting.remove(pos); return message("ChromaLight: source restored to the baked palette");
                }));
    }

    /** Every block of the looked-at emitter's type, anywhere, on one shared channel. */
    public static LiteralArgumentBuilder<CommandSourceStack> typeTree() {
        return Commands.literal("type")
                .then(Commands.literal("realtime").then(Commands.argument("rgb", StringArgumentType.greedyString())
                        .executes(c -> setType(StringArgumentType.getString(c, "rgb")))))
                .then(Commands.literal("rainbow").executes(c -> typeRainbow(4))
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(.2, 120))
                                .executes(c -> typeRainbow(DoubleArgumentType.getDouble(c, "seconds")))))
                .then(Commands.literal("clear").executes(c -> {
                    Block block = targetType(); if (block == null) return 0;
                    return message(HybridLighting.removeType(block) ? "ChromaLight: " + name(block) + " restored to the baked palette"
                            : "ChromaLight: " + name(block) + " has no realtime rule");
                }));
    }
    /** Sky light colours; saved to the config like the video settings. */
    public static LiteralArgumentBuilder<CommandSourceStack> skyTree() {
        var sky = Commands.literal("sky").executes(c -> showSky())
                .then(Commands.literal("reset").executes(c -> {
                    for (SkyLight.Part part : SkyLight.Part.values()) SkyLight.set(part, part.fallback);
                    NativeLighting.saveSettings();
                    return showSky();
                }));
        for (SkyLight.Part part : SkyLight.Part.values()) {
            sky.then(Commands.literal(part.id()).then(Commands.argument("rgb", StringArgumentType.greedyString())
                    .executes(c -> setSky(part, StringArgumentType.getString(c, "rgb")))));
        }
        return sky;
    }
    /** Light packs in the chromalight folder. No argument opens the resource-pack style screen. */
    public static LiteralArgumentBuilder<CommandSourceStack> packTree() {
        return Commands.literal("pack").executes(c -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.tell(() -> mc.setScreen(FolderPacks.screen(null))); // after chat closes
                    return 1;
                })
                .then(Commands.literal("list").executes(c -> {
                    var selected = FolderPacks.selected();
                    message("ChromaLight light packs (highest wins): " + (selected.isEmpty() ? "none" : String.join(" < ", selected)));
                    return 1;
                }))
                .then(Commands.literal("none").executes(c -> { FolderPacks.select(java.util.List.of()); return message("ChromaLight: light packs off"); }))
                .then(Commands.literal("reload").executes(c -> { FolderPacks.reload(true); return message("ChromaLight: light packs reloaded"); }))
                .then(Commands.literal("folder").executes(c -> {
                    FolderPacks.ensureFolder();
                    net.minecraft.Util.getPlatform().openPath(FolderPacks.dir());
                    return 1;
                }));
    }
    public static LiteralArgumentBuilder<CommandSourceStack> bloodMoonTree() {
        return Commands.literal("bloodmoon").executes(c -> bloodMoon(!SkyLight.bloodMoon()))
                .then(Commands.literal("on").executes(c -> bloodMoon(true)))
                .then(Commands.literal("off").executes(c -> bloodMoon(false)));
    }
    private static int showSky() {
        var text = new StringBuilder("ChromaLight sky light: ").append(LightingControl.skyLight() ? "on" : "off");
        for (SkyLight.Part part : SkyLight.Part.values()) text.append(" | ").append(part.id()).append(' ').append(hex(SkyLight.color(part)));
        return message(text.toString());
    }
    private static int setSky(SkyLight.Part part, String text) {
        Integer rgb = rgb(text); if (rgb == null) return 0;
        SkyLight.set(part, rgb);
        NativeLighting.saveSettings();
        return message("ChromaLight: " + part.id() + " sky light " + hex(rgb)
                + (LightingControl.skyLight() ? "" : " (sky light color is off in the settings)"));
    }
    private static int bloodMoon(boolean on) {
        SkyLight.setBloodMoon(on);
        var level = Minecraft.getInstance().level;
        String key = !on ? "commands.chromalight.bloodmoon.off"
                : !LightingControl.isEnabled() ? "commands.chromalight.bloodmoon.disabled"
                : level != null && !SkyLight.isNight(level) ? "commands.chromalight.bloodmoon.day"
                : "commands.chromalight.bloodmoon.on";
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable(key), false);
        return 1;
    }
    private static BlockPos target(boolean emitting) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            message("ChromaLight: point at a block"); return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (emitting && mc.level.getBlockState(pos).getLightEmission(mc.level, pos) <= 0) {
            message("ChromaLight: select a block that emits native light, such as a lantern"); return null;
        }
        return pos;
    }
    private static Block targetType() {
        BlockPos pos = target(true);
        return pos == null ? null : Minecraft.getInstance().level.getBlockState(pos).getBlock();
    }
    private static String name(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }
    private static Integer rgb(String text) {
        text = text.trim(); String hex = text.startsWith("#") ? text.substring(1) : text;
        if (!hex.matches("[0-9a-fA-F]{6}")) { message("ChromaLight: use RRGGBB, for example FF0040"); return null; }
        return Integer.parseInt(hex, 16);
    }
    private static String hex(int rgb) { return String.format(java.util.Locale.ROOT, "#%06X", rgb); }
    private static int set(String text, boolean realtime) {
        Integer rgb = rgb(text); if (rgb == null) return 0;
        BlockPos pos = target(true); if (pos == null) return 0;
        boolean ok = realtime ? HybridLighting.setRealtime(pos, rgb) : HybridLighting.setBaked(pos, rgb);
        return message(ok ? "ChromaLight: " + (realtime ? "realtime " : "baked ") + hex(rgb)
                : "ChromaLight: colour table is full (32767 entries)");
    }
    private static int rainbow(double seconds) {
        BlockPos pos = target(true); if (pos == null) return 0;
        return message(HybridLighting.rainbow(pos, seconds) ? "ChromaLight: realtime RGB, " + seconds + " second cycle"
                : "ChromaLight: colour table is full (32767 entries)");
    }
    private static int setType(String text) {
        Integer rgb = rgb(text); if (rgb == null) return 0;
        Block block = targetType(); if (block == null) return 0;
        return message(HybridLighting.setTypeRealtime(block, rgb) ? "ChromaLight: every " + name(block) + " is realtime " + hex(rgb)
                : "ChromaLight: colour table is full (32767 entries)");
    }
    private static int typeRainbow(double seconds) {
        Block block = targetType(); if (block == null) return 0;
        return message(HybridLighting.typeRainbow(block, seconds) ? "ChromaLight: every " + name(block) + " cycles RGB, " + seconds + " s"
                : "ChromaLight: colour table is full (32767 entries)");
    }
    /** What ChromaLight sees: the looked-at emitter and the current dynamic light sources. */
    public static int debug() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            var state = mc.level.getBlockState(pos);
            int provider = BlockColorProviders.color(mc.level, pos, state);
            message(String.format(java.util.Locale.ROOT, "ChromaLight block %s emission=%d palette=%s provider=%s block light=%d",
                    name(state.getBlock()), state.getLightEmission(mc.level, pos), hex(NativeLighting.paletteColor(state)),
                    provider == BlockColorProviders.NONE ? (NativeLighting.palette().hasColor(state) ? "none" : "none, automatic color") : BlockColorProviders.isRainbow(provider) ? "rainbow phase " + BlockColorProviders.rainbowPhase(provider) : hex(provider),
                    mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos.relative(hit.getDirection()))));
        }
        var sources = dev.nightmode.chromalight.compat.DynamicLightsCompat.describe();
        message("ChromaLight dynamic lights: " + dev.nightmode.chromalight.compat.DynamicLightsCompat.backends() + ", " + sources.size() + " source(s)");
        for (int i = 0; i < Math.min(8, sources.size()); i++) message("  " + sources.get(i));
        return 1;
    }
    private static int message(String text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(text), false);
        return 1;
    }
}
