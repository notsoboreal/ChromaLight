package dev.nightmode.chromalight.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads ChromaLight's own GLSL; Minecraft and shader-pack imports stay untouched. */
final class ShaderSources {
    private static final String ROOT = "/assets/chromalight/shaders/";
    private static final Pattern INCLUDE = Pattern.compile("(?m)^#include \"([^\"]+)\"\\h*$");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");
    private static final String COMMON = load("native_light.glsl");
    private static final String VANILLA = load("include/vanilla_vertex.glsl");
    private static final String SODIUM = load("include/sodium_vertex.glsl");

    private ShaderSources() {}

    static String terrain(float strength, boolean sodium) {
        String constants = COMMON.replace("CHROMA_STRENGTH", String.format(Locale.ROOT, "%.8f", strength));
        return constants + "\n" + (sodium ? SODIUM : VANILLA);
    }

    static String beforeMain(String source, String helper) {
        var main = MAIN.matcher(source);
        if (!main.find()) {
            throw new IllegalStateException("ChromaLight: terrain vertex shader has no main");
        }
        return source.substring(0, main.start()) + helper + "\n" + source.substring(main.start());
    }

    private static String load(String path) {
        try {
            return expand(path, new HashSet<>());
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String expand(String path, Set<String> stack) throws IOException {
        if (path.startsWith("/") || path.contains("..") || !stack.add(path)) {
            throw new IOException("Invalid or cyclic ChromaLight shader include: " + path);
        }
        try (var input = ShaderSources.class.getResourceAsStream(ROOT + path)) {
            if (input == null) {
                throw new IOException("Missing ChromaLight shader: " + path);
            }
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var imports = INCLUDE.matcher(source);
            var result = new StringBuilder();
            while (imports.find()) {
                imports.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(expand(imports.group(1), stack)));
            }
            imports.appendTail(result);
            return result.toString();
        } finally {
            stack.remove(path);
        }
    }
}
