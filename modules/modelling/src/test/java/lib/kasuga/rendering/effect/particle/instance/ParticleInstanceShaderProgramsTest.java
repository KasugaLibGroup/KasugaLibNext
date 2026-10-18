package lib.kasuga.rendering.effect.particle.instance;

import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleInstanceShaderProgramsTest {

    @Test
    void standardInstanceShaderTranslatesWithBackendSemanticInputs() {
        var bundle = MinecraftGlsl150Backend.generate(
                ParticleInstanceShaderPrograms.colored("test:particle_instance")
        );

        assertTrue(bundle.vertexSource().contains("InstanceModel0"));
        assertTrue(bundle.vertexSource().contains("InstanceColor"));
        assertTrue(bundle.vertexSource().contains("CameraOffset"));
        assertTrue(bundle.fragmentSource().contains("particleColor"));
    }

    @Test
    void smokeInstanceShaderIntegratesDensityThroughTheCubeVolume() {
        var bundle = MinecraftGlsl150Backend.generate(
                ParticleInstanceShaderPrograms.volumetricSmoke("test:smoke_volume_instance")
        );

        assertTrue(bundle.vertexSource().contains("smokeCameraPosition"));
        assertTrue(bundle.fragmentSource().contains("for (int smokeStep = 0; smokeStep < 12"));
        assertTrue(bundle.fragmentSource().contains("smokeDensity ="));
        assertTrue(bundle.fragmentSource().contains("exp("));
    }
}
