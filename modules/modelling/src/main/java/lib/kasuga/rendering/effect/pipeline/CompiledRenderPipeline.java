package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Lazily compiled and cached RenderType variants for one immutable pipeline descriptor. */
public final class CompiledRenderPipeline {
    private final RenderPipelineDescriptor descriptor;
    private final List<Map<ResourceLocation, RenderType>> textureVariants = List.of(
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>()
    );
    private final Map<String, RenderType> nativeVariants = new ConcurrentHashMap<>();
    private volatile RenderType defaultRenderType;

    public CompiledRenderPipeline(RenderPipelineDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public RenderPipelineDescriptor descriptor() {
        return descriptor;
    }

    public RenderType renderType() {
        RenderType cached = defaultRenderType;
        if (cached != null) return cached;
        synchronized (this) {
            cached = defaultRenderType;
            if (cached == null) {
                cached = RenderTypeFactory.create(
                        descriptor.id(), descriptor.drawState(), descriptor.drawState().textureState(), null
                );
                defaultRenderType = cached;
            }
        }
        return cached;
    }

    public RenderType renderType(ResourceLocation texture, boolean blur, boolean mipmap) {
        Objects.requireNonNull(texture, "texture");
        int index = (blur ? 1 : 0) | (mipmap ? 2 : 0);
        Map<ResourceLocation, RenderType> variants = textureVariants.get(index);
        RenderType cached = variants.get(texture);
        if (cached != null) return cached;
        synchronized (this) {
            cached = variants.get(texture);
            if (cached == null) {
                cached = RenderTypeFactory.create(
                        descriptor.id(), descriptor.drawState(),
                        new RenderStateShard.TextureStateShard(texture, blur, mipmap), texture.toString()
                );
                variants.put(texture, cached);
            }
        }
        return cached;
    }

    /** Advanced native-state variant. The variant name must uniquely identify the state. */
    public RenderType renderType(
            String variant,
            RenderStateShard.EmptyTextureStateShard textureState
    ) {
        if (variant == null || variant.isBlank()) throw new IllegalArgumentException("variant cannot be blank");
        Objects.requireNonNull(textureState, "textureState");
        RenderType cached = nativeVariants.get(variant);
        if (cached != null) return cached;
        synchronized (this) {
            cached = nativeVariants.get(variant);
            if (cached == null) {
                cached = RenderTypeFactory.create(
                        descriptor.id(), descriptor.drawState(), textureState, variant
                );
                nativeVariants.put(variant, cached);
            }
        }
        return cached;
    }

    public int cachedVariantCount() {
        int textureCount = 0;
        for (Map<ResourceLocation, RenderType> variants : textureVariants) {
            textureCount += variants.size();
        }
        return textureCount + nativeVariants.size() + (defaultRenderType == null ? 0 : 1);
    }
}
