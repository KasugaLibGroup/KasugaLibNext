package lib.kasuga.rendering.models.mc.backend.context;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface GLContext {

    Supplier<ShaderInstance> enter(RenderType renderType,
                   VertexFormat.Mode mode, Matrix4f modelViewMatrix,
                   Matrix4f projectionMatrix,
                   Consumer<ShaderInstance> beforeShaderApply);

    void exit(ShaderInstance shader, RenderType renderType);

    boolean isGpuSkinning();

    void dispatchSkinning(int numVertices);

    default void setupShaderState(@Nullable ShaderInstance shader, VertexFormat.Mode mode, @Nullable Consumer<ShaderInstance> beforeShaderApply, Matrix4f modelViewMatrix, Matrix4f projectionMatrix, Window window) {
        if (shader != null) {
            if (beforeShaderApply != null) {
                beforeShaderApply.accept(shader);
            }
            shader.setDefaultUniforms(
                    mode,
                    modelViewMatrix,
                    projectionMatrix,
                    window
            );
            shader.apply();
        }
    }

}
