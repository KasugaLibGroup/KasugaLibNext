package lib.kasuga.rendering.models.mc.backend;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import lib.kasuga.KasugaLib;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

import java.io.IOException;

/** Mod-bus registrations for the content-testing-only OIT visual fixture. */
@EventBusSubscriber(modid = KasugaLib.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OITVisualTestModEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private OITVisualTestModEvents() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OITVisualTestScene.TOGGLE_MODE);
        event.register(OITVisualTestScene.TOGGLE_ORDER);
        event.register(OITVisualTestScene.CYCLE_BUFFER);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) {
        try {
            ShaderInstance shader = new ShaderInstance(
                    event.getResourceProvider(), id("ksglib_oit_visual"),
                    DefaultVertexFormat.POSITION_COLOR
            );
            event.registerShader(shader, loaded -> OITVisualTestRenderer.GEOMETRY_SHADER = loaded);
        } catch (IOException exception) {
            LOGGER.warn("OIT visual test geometry shader is unavailable", exception);
        }

        try {
            ShaderInstance shader = new ShaderInstance(
                    event.getResourceProvider(), id("ksglib_oit_visual_composite"),
                    DefaultVertexFormat.BLIT_SCREEN
            );
            event.registerShader(shader, loaded -> OITVisualTestRenderer.COMPOSITE_SHADER = loaded);
        } catch (IOException exception) {
            LOGGER.warn("OIT visual test composite shader is unavailable", exception);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, path);
    }
}
