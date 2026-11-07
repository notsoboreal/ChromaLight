package dev.nightmode.chromalight;

/** Safe to initialize during mixin discovery, before Minecraft exists. */
public final class CompatStatus {
    public static volatile boolean sodium;
    private CompatStatus() {}
}
