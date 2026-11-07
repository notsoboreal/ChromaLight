package dev.nightmode.chromalight.compat;

import dev.nightmode.chromalight.ChromaLight;
import dev.nightmode.chromalight.LightingControl;
import dev.nightmode.chromalight.NativeLighting;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import dev.nightmode.chromalight.FolderPacks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Uses Sodium's page API, shared by the stock screen and Reese's Sodium Options. */
public final class ChromaLightOptions implements ConfigEntryPoint {
    private static final StorageEventHandler SAVE = NativeLighting::saveSettings;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var lighting = builder.createOptionGroup()
                .setName(text("group.lighting"))
                .addOption(builder.createBooleanOption(id("enabled"))
                        .setName(text("enabled")).setTooltip(text("enabled.tooltip"))
                        .setDefaultValue(true).setBinding(LightingControl::setEnabled, LightingControl::isEnabled)
                        .setStorageHandler(SAVE).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD))
                .addOption(integer(builder, "tint_strength", 85, 0, 100)
                        .setBinding(value -> LightingControl.setIntensity(value / 100f),
                                () -> Math.round(LightingControl.intensity() * 100))
                        .setValueFormatter(value -> Component.literal(value + "%")))
                .addOption(integer(builder, "source_blend", 100, 0, 100)
                        .setBinding(value -> LightingControl.setSourceBlend(value / 100f),
                                () -> Math.round(LightingControl.sourceBlend() * 100))
                        .setValueFormatter(value -> Component.literal(value + "%"))
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD).setImpact(OptionImpact.HIGH))
                .addOption(builder.createBooleanOption(id("auto_colors"))
                        .setName(text("auto_colors")).setTooltip(text("auto_colors.tooltip"))
                        .setDefaultValue(true).setBinding(LightingControl::setAutoColors, LightingControl::autoColors)
                        .setStorageHandler(SAVE).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));

        var sky = builder.createOptionGroup()
                .setName(text("group.sky"))
                .addOption(builder.createBooleanOption(id("sky_light"))
                        .setName(text("sky_light")).setTooltip(text("sky_light.tooltip"))
                        .setDefaultValue(true).setBinding(LightingControl::setSkyLight, LightingControl::skyLight)
                        .setStorageHandler(SAVE))
                .addOption(integer(builder, "sky_strength", 60, 0, 100)
                        .setBinding(value -> LightingControl.setSkyStrength(value / 100f),
                                () -> Math.round(LightingControl.skyStrength() * 100))
                        .setValueFormatter(value -> Component.literal(value + "%")))
                .addOption(builder.createBooleanOption(id("sky_weather"))
                        .setName(text("sky_weather")).setTooltip(text("sky_weather.tooltip"))
                        .setDefaultValue(true).setBinding(LightingControl::setSkyWeather, LightingControl::skyWeather)
                        .setStorageHandler(SAVE));

        var compat = builder.createOptionGroup()
                .setName(text("group.entities"))
                .addOption(builder.createBooleanOption(id("entity_lighting"))
                        .setName(text("entity_lighting")).setTooltip(text("entity_lighting.tooltip"))
                        .setDefaultValue(true).setBinding(LightingControl::setEntityLighting, LightingControl::entityLighting)
                        .setStorageHandler(SAVE).setImpact(OptionImpact.LOW))
                .addOption(builder.createBooleanOption(id("dynamic_lights"))
                        .setName(text("dynamic_lights")).setTooltip(text("dynamic_lights.tooltip"))
                        .setDefaultValue(true).setBinding(LightingControl::setDynamicLights, LightingControl::dynamicLights)
                        .setStorageHandler(SAVE).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD).setImpact(OptionImpact.LOW));

        var performance = builder.createOptionGroup()
                .setName(text("group.performance"))
                .addOption(integer(builder, "max_mixed_sources", 4, 2, 8)
                        .setBinding(LightingControl::setMixedSources, LightingControl::mixedSources)
                        .setValueFormatter(value -> Component.literal(Integer.toString(value)))
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD).setImpact(OptionImpact.LOW))
                .addOption(integer(builder, "max_realtime_sources", 32, 0, 32)
                        .setBinding(LightingControl::setRealtimeSources, LightingControl::realtimeSources)
                        .setValueFormatter(value -> Component.literal(Integer.toString(value)))
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD).setImpact(OptionImpact.LOW));

        var flicker = builder.createOptionGroup()
                .setName(text("group.flicker"))
                .addOption(builder.createBooleanOption(id("flicker"))
                        .setName(text("flicker")).setTooltip(text("flicker.tooltip"))
                        .setDefaultValue(false).setBinding(LightingControl::setFlicker, LightingControl::flicker)
                        .setStorageHandler(SAVE).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD).setImpact(OptionImpact.MEDIUM))
                .addOption(integer(builder, "flicker_strength", 50, 0, 100)
                        .setBinding(value -> LightingControl.setFlickerStrength(value / 100f),
                                () -> Math.round(LightingControl.flickerStrength() * 100))
                        .setValueFormatter(value -> Component.literal(value + "%")));

        builder.registerOwnModOptions().setName("ChromaLight").setVersion(ChromaLight.VERSION)
                .setNonTintedIcon(id("textures/gui/icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0xff8e65e8))
                // One page per topic: Reese's Sodium Options collapses a single-page mod to its header.
                .addPage(builder.createOptionPage().setName(text("page.lighting")).addOptionGroup(lighting))
                .addPage(builder.createExternalPage().setName(text("page.packs"))
                        .setScreenConsumer(parent -> Minecraft.getInstance().setScreen(FolderPacks.screen(parent))))
                .addPage(builder.createOptionPage().setName(text("page.sky")).addOptionGroup(sky))
                .addPage(builder.createOptionPage().setName(text("page.flicker")).addOptionGroup(flicker))
                .addPage(builder.createOptionPage().setName(text("page.entities")).addOptionGroup(compat))
                .addPage(builder.createOptionPage().setName(text("page.performance")).addOptionGroup(performance));
    }

    private static IntegerOptionBuilder integer(ConfigBuilder builder, String name, int value, int min, int max) {
        return builder.createIntegerOption(id(name)).setName(text(name)).setTooltip(text(name + ".tooltip"))
                .setDefaultValue(value).setRange(new Range(min, max, 1)).setStorageHandler(SAVE);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ChromaLight.MOD_ID, path);
    }

    private static Component text(String key) {
        return Component.translatable("options.chromalight." + key);
    }
}
