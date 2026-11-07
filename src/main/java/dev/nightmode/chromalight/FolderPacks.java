package dev.nightmode.chromalight;

import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Light packs in {@code <game dir>/chromalight/}, managed with Minecraft's own resource pack screen:
 * available and selected columns, ordering, pack.png icons, drag and drop. Each pack is a folder or
 * .zip; every {@code *.json} inside uses the light-pack format and pack.mcmeta is optional.
 * Packs higher in the selected list win. Selected packs are watched and reload by themselves.
 */
public final class FolderPacks {
    public static final String DIR = "chromalight";

    private static volatile List<LightPalette.Layer> layers = List.of();
    private static long fingerprint;
    private static int ticks;

    private FolderPacks() {}

    public static Path dir() { return Minecraft.getInstance().gameDirectory.toPath().resolve(DIR); }
    /** Selected pack names, lowest priority first. */
    public static List<String> selected() { return LightingControl.lightPacks(); }
    static List<LightPalette.Layer> layers() { return layers; }

    /** Creates the folder with a short readme so people know what goes in it. */
    public static void ensureFolder() {
        Path dir = dir();
        try {
            boolean created = !Files.exists(dir);
            Files.createDirectories(dir);
            Path readme = dir.resolve("README.txt");
            if (!Files.exists(readme)) Files.writeString(readme, README, StandardCharsets.UTF_8);
            if (created) { // one sample to start from; never recreated once deleted
                Path example = Files.createDirectories(dir.resolve("Cold fire (example)"));
                Files.writeString(example.resolve("pack.mcmeta"), EXAMPLE_META, StandardCharsets.UTF_8);
                Files.writeString(example.resolve("colors.json"), EXAMPLE_COLORS, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[ChromaLight] Cannot create " + dir + ": " + e);
        }
    }

    /** Minecraft's resource pack screen over the chromalight folder. Applies on Done. */
    public static Screen screen(@Nullable Screen parent) {
        ensureFolder();
        PackRepository repository = new PackRepository(FolderPacks::scan);
        repository.reload();
        repository.setSelected(selected().stream().filter(repository::isAvailable).toList());
        return new PackSelectionScreen(repository, chosen -> {
            select(List.copyOf(chosen.getSelectedIds()));
            Minecraft.getInstance().setScreen(parent);
        }, dir(), Component.translatable("chromalight.packs.title"));
    }

    /** Client thread. Names lowest priority first; an empty list turns folder packs off. */
    public static void select(List<String> names) {
        if (names.equals(selected())) return;
        LightingControl.setLightPacks(names);
        NativeLighting.saveSettings();
        reload(true);
    }

    /** Client thread. Reads the selected packs again; rebuilds terrain when {@code rebuild}. */
    public static void reload(boolean rebuild) {
        List<LightPalette.Layer> next = new ArrayList<>();
        for (String name : selected()) {
            Path path = dir().resolve(name);
            if (!Files.exists(path)) {
                System.err.println("[ChromaLight] Selected light pack is missing: " + path);
                continue;
            }
            try (Source source = Source.open(path)) {
                for (Path file : source.jsonFiles()) {
                    String origin = DIR + "/" + name + "/" + source.root().relativize(file).toString().replace('\\', '/');
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        next.add(LightPacks.parse(JsonParser.parseReader(reader).getAsJsonObject(), origin));
                    } catch (Exception e) {
                        System.err.println("[ChromaLight] Skipping light pack file " + origin + ": " + e);
                        message(Component.translatable("chromalight.packs.bad_file", origin));
                    }
                }
            } catch (IOException e) {
                System.err.println("[ChromaLight] Cannot read light pack " + name + ": " + e);
            }
        }
        layers = List.copyOf(next);
        fingerprint = fingerprint();
        if (rebuild) NativeLighting.rebuildPalette();
    }

    /** Client tick: hot reload of the selected packs, checked once a second. */
    static void tick() {
        if (++ticks % 20 != 0 || selected().isEmpty()) return;
        if (fingerprint() == fingerprint) return;
        reload(true);
        message(Component.translatable("chromalight.packs.reloaded"));
    }

    private static long fingerprint() {
        long hash = 17;
        for (String name : selected()) hash = hash * 31 + fingerprint(dir().resolve(name));
        return hash;
    }

    private static long fingerprint(Path path) {
        if (!Files.exists(path)) return -1;
        if (!Files.isDirectory(path)) {
            try { return Files.getLastModifiedTime(path).toMillis() * 31 + Files.size(path); }
            catch (IOException e) { return -1; }
        }
        long[] hash = {17};
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    hash[0] = hash[0] * 31 + file.toString().hashCode();
                    hash[0] = hash[0] * 31 + attrs.lastModifiedTime().toMillis();
                    hash[0] = hash[0] * 31 + attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) { return -1; }
        return hash[0];
    }

    /** Every folder and .zip in the chromalight folder, with or without pack.mcmeta. */
    private static void scan(Consumer<Pack> out) {
        try (Stream<Path> entries = Files.list(dir())) {
            for (Path path : entries.sorted().toList()) {
                String name = path.getFileName().toString();
                boolean directory = Files.isDirectory(path);
                if (!directory && !name.toLowerCase(Locale.ROOT).endsWith(".zip")) continue;
                var location = new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty());
                Pack.ResourcesSupplier resources = directory ? new PathPackResources.PathResourcesSupplier(path)
                        : new FilePackResources.FileResourcesSupplier(path);
                var metadata = new Pack.Metadata(describe(path), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
                out.accept(new Pack(location, resources, metadata, new PackSelectionConfig(false, Pack.Position.TOP, false)));
            }
        } catch (IOException ignored) {
            // No folder yet: nothing to list.
        }
    }

    private static Component describe(Path path) {
        try (Source source = Source.open(path)) {
            Path meta = source.root().resolve("pack.mcmeta");
            if (Files.exists(meta)) {
                try (Reader reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                    var pack = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("pack");
                    if (pack != null && pack.has("description")) {
                        Component description = Component.Serializer.fromJson(pack.get("description"), Minecraft.getInstance().level != null
                                ? Minecraft.getInstance().level.registryAccess() : net.minecraft.core.RegistryAccess.EMPTY);
                        if (description != null) return description;
                    }
                }
            }
            return Component.translatable("chromalight.packs.files", source.jsonFiles().size());
        } catch (Exception e) {
            return Component.translatable("chromalight.packs.unreadable");
        }
    }

    private static void message(Component text) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(text, true);
    }

    /** A pack folder, or a .zip opened as a file system. */
    private record Source(Path root, FileSystem zip) implements AutoCloseable {
        static Source open(Path path) throws IOException {
            if (Files.isDirectory(path)) return new Source(path, null);
            FileSystem zip = FileSystems.newFileSystem(path);
            return new Source(zip.getPath("/"), zip);
        }
        List<Path> jsonFiles() throws IOException {
            try (Stream<Path> files = Files.walk(root, 16)) {
                return files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted(Comparator.comparing(p -> root.relativize(p).toString())).toList();
            }
        }
        @Override public void close() throws IOException { if (zip != null) zip.close(); }
    }

    private static final String EXAMPLE_META = """
            { "pack": { "pack_format": 34, "description": "Torches, lanterns and campfires burn cold white-blue" } }
            """;
    private static final String EXAMPLE_COLORS = """
            {
              "blocks": {
                "minecraft:torch": "#BFE4FF", "minecraft:wall_torch": "#BFE4FF",
                "minecraft:lantern": "#A8D8FF", "minecraft:campfire": "#9CCBFF",
                "minecraft:fire": "#9CCBFF", "#minecraft:candles": "#D0EAFF"
              }
            }
            """;

    private static final String README = """
            ChromaLight light packs
            =======================

            Put light packs here: one folder or .zip per pack. Turn them on in Video Settings >
            ChromaLight > Light packs (or with /chromalight pack). It works like the resource
            pack screen: several packs can be on at once, and the higher one wins. Edits to an
            active pack apply by themselves within a second.

            Every .json file inside a pack (any subfolder) is read. Format:

            {
              "blocks":   { "minecraft:torch": "#FF9040", "mymod:lamp[lit=true]": "#80C0FF", "#minecraft:candles": "#FFD080" },
              "flicker":  [ "mymod:brazier" ],
              "items":    { "mymod:glow_wand": "#80FFFF" },
              "entities": { "mymod:fire_spirit": "#FF8040" },
              "entity_blacklist": [ "somebossmod:*" ]
            }

            "blocks" takes block ids, ids with block states, or #tags. Blocks no rule covers get a
            color picked from their texture. These packs win over resource packs; colors you
            changed in config/chromalight.properties win over them.

            Optional: pack.png is the icon, pack.mcmeta { "pack": { "description": "..." } } the
            description, the same as in a resource pack.
            """;
}
