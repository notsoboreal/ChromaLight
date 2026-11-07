# 1.2.2-alpha

Fixed

- Mobs, held items, chests and other block entities sometimes kept the old light color for up to half a second after a light was placed or broken, then jumped to the new one.

# 1.2.1-alpha

New

- Chests, signs, banners, beds, heads and other blocks with their own renderer take the color of the light around them, like mobs. Uses the Colored light on entities setting.

# 1.2.0-alpha

New

- Sky light color: warm daylight, orange sunrise and sunset, blue moonlight, grey-blue rain and darker thunderstorms. Brightness and light levels stay vanilla. Settings are on the new Sky page; colors are `sky_day`, `sky_dusk`, `sky_night`, `sky_rain`, `sky_thunder` in the config or `/chromalight sky`.
- `/chromalight bloodmoon`: a red moon, sky, clouds, fog and moonlight until you turn it off or leave the world.
- Light pack folder: `.minecraft/chromalight/`, managed in a resource pack style screen (Video Settings > ChromaLight > Light packs, the mod list's Config button, or `/chromalight pack`). Several packs at once, ordering, icons, drag and drop. Edits to active packs apply within a second.
- Automatic colors: light sources without a color rule, usually from other mods, get a color from their texture. Glowing entities (dynamic lights) without a rule use their own texture too, instead of torch light. Can be turned off.
- Particles (block breaking, smoke, drips) take the color of the light around them, with the entity setting.
- Glow lichen light is light blue instead of mint green.
- The settings are split into pages (Lighting, Sky, Fire flicker, Entities, Performance), so they show up in the sidebar of Reese's Sodium Options.

Compatibility

- Checked with Sodium Extra 0.9.5, Chloride 1.8.1, Sodium Shadowy Path Blocks 4.1.0, Lithium 0.15.4, C2ME 0.4.0-alpha, ScalableLux 0.3.0-alpha, Moonrise 0.1.0-beta.15, FerriteCore 7.0.3, ModernFix 5.27.27, ImmediatelyFast 1.6.15 and Entity Culling 1.11.3.

# 1.1.0-alpha

New

- Light packs: resource packs can color blocks from any mod (ids, block states, tags), choose which blocks flicker, color dynamic light items and entities, and blacklist entity types. See `assets/<namespace>/chromalight/lights/*.json` in the README.
- Entities take the color of the light around them. Entity types can be excluded with `entity_blacklist` (`modid:*`, `modid:name`, `#tag`); the list is empty by default.
- LambDynamicLights support: held torches, burning mobs, glowing items and other dynamic lights color the world.
- Ars Nouveau support: light spells, sconces and magelight torches use the color picked in Spell Styles, rainbow included and in sync with the particles. Spell projectiles light the world in their trail color. Ars Nouveau's own dynamic light works too.
- Optional fire flicker for torches, fire, campfires, candles and lanterns. Only brightness changes; light levels stay vanilla.
- Realtime colors per block type (`/chromalight type ...`, `ClientLightSources.setType`). A whole floor of one lamp counts as one animated color, not hundreds.
- `/chromalight debug` shows what ChromaLight sees for the block you look at and lists dynamic lights.
- API: `ClientLightSources.registerColorProvider` for blocks that store their color in block entity data.
- New settings on the Sodium page: fire flicker, flicker strength, colored light on entities, color dynamic lights.

Fixed

- Pale, almost white halo around colored lights outdoors (most visible on soul campfires).
- Visible seams between chunk sections on animated (rainbow) lights.
- Building and mining in the dark no longer rebuilds a large area around the edited block.
- A rare crash when a block changed outside the client thread.

Performance

- Chunk color building is 1.4-3× faster, depending on how many lights are around.
- Sodium copies extra neighboring chunk data only when it contains block light.

Removed

- The old screen-space renderer and its config options. Old options are dropped from existing configs automatically.

# 1.0.0-alpha

First public alpha.

- Colored block light for terrain and fluids, cached with chunk geometry across the full render distance.
- Vanilla renderer and Sodium 0.8.13 support, with a ChromaLight page in Sodium's video settings.
- Realtime colors for selected lights without rebuilding chunks (`/chromalight source ...`, `ClientLightSources`).
- Light from different sources mixes; same-colored lights don't crowd out weaker lights of another color.
- Runs alongside Iris; `/chromalight enable` and `/chromalight disable` to compare.
