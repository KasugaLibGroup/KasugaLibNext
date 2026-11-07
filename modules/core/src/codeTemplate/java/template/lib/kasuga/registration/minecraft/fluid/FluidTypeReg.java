package template.lib.kasuga.registration.minecraft.fluid;

import lib.kasuga.internal.generator.annotations.CodeTemplate;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.common.SoundAction;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Registration of a {@link FluidType}: the object that carries density, viscosity, sounds and
 * the client rendering data of a fluid.
 * <p>
 * Client data (still / flowing / overlay sprites, tint, fog) is stored on this registration and
 * handed to the client through {@code RegisterClientExtensionsEvent}, which only fires on the
 * physical client, so nothing client-only is loaded on a dedicated server.
 *
 * @param <T> the fluid type class.
 */
@CodeTemplate(generator = "Reg")
public class FluidTypeReg<T extends FluidType> extends MinecraftDeferRegistryReg<FluidTypeReg<T>, FluidType, T> {

    private final Function<FluidTypeReg<T>, Function<FluidType.Properties, T>> supplier;
    private final FluidType.Properties properties = FluidType.Properties.create();

    private T createdType;

    private ResourceLocation stillTexture;
    private ResourceLocation flowingTexture;
    private ResourceLocation overlayTexture;
    private int tintColor = 0xFFFFFFFF;
    private float[] fogColor;
    private float fogStart = 1.0F;
    private float fogEnd = 6.0F;

    /**
     * @param name the registration name, also used for the translation key
     *             {@code fluid_type.<namespace>.<name>}.
     */
    public static FluidTypeReg<FluidType> of(String name) {
        return new FluidTypeReg<>(name, reg -> p -> new DefaultFluidType(p));
    }

    public static <T extends FluidType> FluidTypeReg<T> of(String name, Function<FluidType.Properties, T> factory) {
        return new FluidTypeReg<>(name, reg -> factory);
    }

    public FluidTypeReg(String name, Function<FluidTypeReg<T>, Function<FluidType.Properties, T>> supplier) {
        super(name, NeoForgeRegistries.Keys.FLUID_TYPES);
        this.supplier = supplier;
    }

    /**
     * Registers the managed client extensions for the type this registration produced.
     * <p>
     * Extensions go through {@code RegisterClientExtensionsEvent} rather than
     * {@code FluidType#initializeClient}, so a type built by a custom factory still receives
     * the still / flowing / overlay sprites, tint and fog configured on this registration.
     * NeoForge rejects a type that is registered twice, so this is the only channel.
     */
    public void registerClientExtensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
        T created = getCreatedType();
        if (created == null) {
            return;
        }
        event.registerFluidType(new ClientExtensions(this), created);
    }

    /**
     * @return the {@link FluidType} this registration produced, or {@code null} before it is registered.
     */
    public T getCreatedType() {
        return this.createdType;
    }

    @Override
    protected T createObject(ResourceLocation id) {
        this.properties.descriptionId("fluid_type." + id.getNamespace() + "." + id.getPath());
        this.stillTexture = prefix(id, this.stillTexture);
        this.flowingTexture = prefix(id, this.flowingTexture);
        this.overlayTexture = prefix(id, this.overlayTexture);
        this.createdType = supplier.apply(this).apply(this.properties);
        return this.createdType;
    }

    /* ---------------------------------------------------------------- client rendering data */

    /**
     * @param texturePath the still sprite, relative to the registration namespace.
     */
    public FluidTypeReg<T> still(String texturePath) {
        this.stillTexture = ResourceLocation.parse(texturePath);
        return this;
    }

    public FluidTypeReg<T> flowing(String texturePath) {
        this.flowingTexture = ResourceLocation.parse(texturePath);
        return this;
    }

    public FluidTypeReg<T> overlay(String texturePath) {
        this.overlayTexture = ResourceLocation.parse(texturePath);
        return this;
    }

    public FluidTypeReg<T> tint(int argb) {
        this.tintColor = argb;
        return this;
    }

    public FluidTypeReg<T> tint(int r, int g, int b) {
        this.tintColor = 0xFF000000 | (r << 16) | (g << 8) | b;
        return this;
    }

    public FluidTypeReg<T> fogColor(float r, float g, float b) {
        this.fogColor = new float[]{r, g, b};
        return this;
    }

    public FluidTypeReg<T> fog(float startDistance, float endDistance) {
        this.fogStart = startDistance;
        this.fogEnd = endDistance;
        return this;
    }

    private static ResourceLocation prefix(ResourceLocation id, ResourceLocation path) {
        return path == null ? null : ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path.getPath());
    }

    /* ---------------------------------------------------------------- type properties */

    public FluidTypeReg<T> properties(Consumer<FluidType.Properties> consumer) {
        consumer.accept(this.properties);
        return this;
    }

    public FluidTypeReg<T> lightLevel(int lightLevel) {
        return properties(p -> p.lightLevel(lightLevel));
    }

    public FluidTypeReg<T> density(int density) {
        return properties(p -> p.density(density));
    }

    public FluidTypeReg<T> viscosity(int viscosity) {
        return properties(p -> p.viscosity(viscosity));
    }

    public FluidTypeReg<T> temperature(int temperature) {
        return properties(p -> p.temperature(temperature));
    }

    public FluidTypeReg<T> supportsBoating(boolean supportsBoating) {
        return properties(p -> p.supportsBoating(supportsBoating));
    }

    public FluidTypeReg<T> sound(SoundAction action, SoundEvent sound) {
        return properties(p -> p.sound(action, sound));
    }

    public FluidTypeReg<T> bucketSounds(SoundEvent fill, SoundEvent empty) {
        return sound(SoundActions.BUCKET_FILL, fill).sound(SoundActions.BUCKET_EMPTY, empty);
    }

    public FluidTypeReg<T> defaultBucketSounds() {
        return bucketSounds(SoundEvents.BUCKET_FILL, SoundEvents.BUCKET_EMPTY);
    }

    public FluidTypeReg<T> vaporizeSound(SoundEvent sound) {
        return sound(SoundActions.FLUID_VAPORIZE, sound);
    }

    /* ---------------------------------------------------------------- accessors */

    public ResourceLocation getStillTexturePath() {
        return stillTexture;
    }

    public ResourceLocation getFlowingTexturePath() {
        return flowingTexture;
    }

    public ResourceLocation getOverlayTexturePath() {
        return overlayTexture;
    }

    public int getTintColor() {
        return tintColor;
    }

    public float[] getFogColor() {
        return fogColor;
    }

    public float getFogStart() {
        return fogStart;
    }

    public float getFogEnd() {
        return fogEnd;
    }

    /**
     * Default {@link FluidType} used when the consumer does not supply a factory.
     */
    public static class DefaultFluidType extends FluidType {
        public DefaultFluidType(FluidType.Properties properties) {
            super(properties);
        }
    }

    static final class ClientExtensions implements net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions {
        private final FluidTypeReg<?> reg;

        ClientExtensions(FluidTypeReg<?> reg) {
            this.reg = reg;
        }

        @Override
        public ResourceLocation getStillTexture() {
            ResourceLocation still = reg.getStillTexturePath();
            return still;
        }

        @Override
        public ResourceLocation getFlowingTexture() {
            ResourceLocation flowing = reg.getFlowingTexturePath();
            return flowing;
        }

        @Override
        public ResourceLocation getOverlayTexture() {
            ResourceLocation overlay = reg.getOverlayTexturePath();
            return overlay;
        }

        @Override
        public int getTintColor() {
            return reg.getTintColor();
        }

        @Override
        public org.joml.Vector3f modifyFogColor(net.minecraft.client.Camera camera, float partialTick, net.minecraft.client.multiplayer.ClientLevel level, int renderDistance, float darkenWorldAmount, org.joml.Vector3f fluidFogColor) {
            float[] color = reg.getFogColor();
            return color == null ? fluidFogColor : new org.joml.Vector3f(color[0], color[1], color[2]);
        }

        @Override
        public void modifyFogRender(net.minecraft.client.Camera camera, net.minecraft.client.renderer.FogRenderer.FogMode mode, float renderDistance, float partialTick, float nearDistance, float farDistance, com.mojang.blaze3d.shaders.FogShape shape) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(reg.getFogStart());
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(reg.getFogEnd());
        }
    }
}
