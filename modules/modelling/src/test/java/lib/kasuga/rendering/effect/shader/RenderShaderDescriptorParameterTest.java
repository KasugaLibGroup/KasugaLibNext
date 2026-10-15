package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderProgram;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderShaderDescriptorParameterTest {
    private static final ShaderParameter EXPOSURE = ShaderParameter.floatParameter(
            "Exposure", "Output exposure", 1.0f, 0.0f, 4.0f
    );

    @Test
    void generatedDescriptorCarriesDslParameterSchema() {
        ShaderProgram program = ShaderProgram.fullscreen("test:exposed", shader -> {
            var exposure = shader.exposeFloat(EXPOSURE);
            shader.fragmentColor(shader.vec4(exposure, exposure, exposure, shader.f32(1.0f)));
        });

        RenderShaderDescriptor descriptor = RenderShaderDescriptor.generated(program);

        assertEquals(java.util.List.of(EXPOSURE), descriptor.parameterSchema().parameters());
    }

    @Test
    void resourceDescriptorAcceptsExplicitParameterDeclarations() {
        RenderShaderDescriptor descriptor = RenderShaderDescriptor.builder(
                        ResourceLocation.parse("test:resource_exposed"), DefaultVertexFormat.BLIT_SCREEN
                )
                .resource()
                .expose(EXPOSURE)
                .build();

        assertEquals(RenderShaderDescriptor.SourceKind.RESOURCE, descriptor.sourceKind());
        assertEquals(java.util.List.of(EXPOSURE), descriptor.parameterSchema().parameters());
    }
}
