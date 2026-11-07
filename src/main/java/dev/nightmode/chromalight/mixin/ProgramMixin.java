package dev.nightmode.chromalight.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import dev.nightmode.chromalight.LightingControl;
import dev.nightmode.chromalight.render.TerrainShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.io.InputStream;

@Mixin(Program.class)
public abstract class ProgramMixin {
    // Edit the loaded text before preprocessing. Iris redirects process() itself,
    // so taking that same call would make one of the two required hooks fail.
    @ModifyExpressionValue(method = "compileShaderInternal", at = @At(value = "INVOKE",
            target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/InputStream;Ljava/nio/charset/Charset;)Ljava/lang/String;"))
    private static String chromalight$source(String source, Program.Type type, String name,
                                             InputStream input, String pack, GlslPreprocessor processor) {
        return TerrainShaders.vanilla(source, name, type == Program.Type.VERTEX, LightingControl.intensity());
    }
}
