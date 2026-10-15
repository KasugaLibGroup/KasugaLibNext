package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderParameterSchema;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;
import java.util.Objects;

/** Immutable shader source and preload declaration. Runtime metrics live on its registration. */
public final class RenderShaderDescriptor {
    private final ResourceLocation id;
    private final VertexFormat vertexFormat;
    private final RenderShaderFactory factory;
    private final ShaderProgram generatedProgram;
    private final SourceKind sourceKind;
    private final ShaderPreloadPolicy preloadPolicy;
    private final int preloadPriority;
    private final ShaderFailurePolicy failurePolicy;
    private final ShaderParameterSchema parameterSchema;

    private RenderShaderDescriptor(Builder builder) {
        id = Objects.requireNonNull(builder.id, "id");
        vertexFormat = Objects.requireNonNull(builder.vertexFormat, "vertexFormat");
        factory = builder.factory;
        generatedProgram = builder.generatedProgram;
        sourceKind = Objects.requireNonNull(builder.sourceKind, "sourceKind");
        preloadPolicy = Objects.requireNonNull(builder.preloadPolicy, "preloadPolicy");
        preloadPriority = builder.preloadPriority;
        failurePolicy = Objects.requireNonNull(builder.failurePolicy, "failurePolicy");
        parameterSchema = ShaderParameterSchema.of(builder.exposedParameters);
        if (sourceKind == SourceKind.GENERATED && generatedProgram == null) {
            throw new IllegalStateException("Generated shader requires a ShaderProgram");
        }
        if (sourceKind != SourceKind.GENERATED && factory == null) {
            throw new IllegalStateException("Resource shader requires a factory");
        }
    }

    public static RenderShaderDescriptor standard(ResourceLocation id, VertexFormat vertexFormat) {
        return builder(id, vertexFormat).resource().build();
    }

    public static RenderShaderDescriptor generated(ShaderProgram program, VertexFormat vertexFormat) {
        Objects.requireNonNull(program, "program");
        return new Builder(ResourceLocation.parse(program.id()), vertexFormat)
                .generatedProgram(program)
                .build();
    }

    public static RenderShaderDescriptor generated(ShaderProgram program) {
        Objects.requireNonNull(program, "program");
        if (program.kind() != ShaderProgram.Kind.FULLSCREEN) {
            throw new IllegalArgumentException(
                    "Graphics generated programs require an explicit Minecraft vertex format"
            );
        }
        return generated(program, DefaultVertexFormat.BLIT_SCREEN);
    }

    public static Builder builder(ResourceLocation id, VertexFormat vertexFormat) {
        return new Builder(id, vertexFormat);
    }

    public ResourceLocation id() { return id; }
    public VertexFormat vertexFormat() { return vertexFormat; }
    public SourceKind sourceKind() { return sourceKind; }
    public ShaderPreloadPolicy preloadPolicy() { return preloadPolicy; }
    public int preloadPriority() { return preloadPriority; }
    public ShaderFailurePolicy failurePolicy() { return failurePolicy; }
    public ShaderParameterSchema parameterSchema() { return parameterSchema; }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public RenderShaderDescriptor withPreload(ShaderPreloadPolicy policy, int priority) {
        return toBuilder().preloadPolicy(policy).preloadPriority(priority).build();
    }

    PreparedSource prepareSource() {
        if (sourceKind == SourceKind.GENERATED) {
            GeneratedShaderPreloader.Prepared prepared = GeneratedShaderPreloader.prepare(generatedProgram);
            return new PreparedSource(
                    prepared.resources(), prepared.translationNanos(), prepared.cacheHit()
            );
        }
        return PreparedSource.NONE;
    }

    ShaderInstance create(ResourceProvider resources, PreparedSource prepared) throws IOException {
        Objects.requireNonNull(prepared, "prepared");
        if (sourceKind == SourceKind.GENERATED) {
            if (prepared.generatedResources() == null) {
                throw new IllegalStateException("Generated shader source has not been prepared: " + id);
            }
            return new ShaderInstance(prepared.generatedResources().overlay(resources), id, vertexFormat);
        }
        return factory.create(resources, id, vertexFormat);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final VertexFormat vertexFormat;
        private RenderShaderFactory factory = ShaderInstance::new;
        private ShaderProgram generatedProgram;
        private SourceKind sourceKind = SourceKind.CUSTOM_FACTORY;
        private ShaderPreloadPolicy preloadPolicy = ShaderPreloadPolicy.EAGER;
        private int preloadPriority;
        private ShaderFailurePolicy failurePolicy = ShaderFailurePolicy.DISABLE_PIPELINE;
        private final java.util.List<ShaderParameter> exposedParameters = new java.util.ArrayList<>();

        private Builder(ResourceLocation id, VertexFormat vertexFormat) {
            this.id = Objects.requireNonNull(id, "id");
            this.vertexFormat = Objects.requireNonNull(vertexFormat, "vertexFormat");
        }

        private Builder(RenderShaderDescriptor descriptor) {
            id = descriptor.id;
            vertexFormat = descriptor.vertexFormat;
            factory = descriptor.factory;
            generatedProgram = descriptor.generatedProgram;
            sourceKind = descriptor.sourceKind;
            preloadPolicy = descriptor.preloadPolicy;
            preloadPriority = descriptor.preloadPriority;
            failurePolicy = descriptor.failurePolicy;
            exposedParameters.addAll(descriptor.parameterSchema.parameters());
        }

        public Builder factory(RenderShaderFactory value) {
            factory = Objects.requireNonNull(value, "factory");
            generatedProgram = null;
            sourceKind = SourceKind.CUSTOM_FACTORY;
            return this;
        }

        private Builder generatedProgram(ShaderProgram value) {
            generatedProgram = Objects.requireNonNull(value, "generatedProgram");
            factory = null;
            sourceKind = SourceKind.GENERATED;
            exposedParameters.clear();
            exposedParameters.addAll(value.parameterSchema().parameters());
            return this;
        }

        /** Selects Minecraft resource-pack shader loading with the standard ShaderInstance factory. */
        public Builder resource() {
            generatedProgram = null;
            factory = ShaderInstance::new;
            sourceKind = SourceKind.RESOURCE;
            return this;
        }

        public Builder preloadPolicy(ShaderPreloadPolicy value) {
            preloadPolicy = Objects.requireNonNull(value, "preloadPolicy");
            return this;
        }

        public Builder preloadPriority(int value) {
            preloadPriority = value;
            return this;
        }

        public Builder failurePolicy(ShaderFailurePolicy value) {
            failurePolicy = Objects.requireNonNull(value, "failurePolicy");
            return this;
        }

        /** Declares a user-adjustable uniform for a resource or custom-factory shader. */
        public Builder expose(ShaderParameter parameter) {
            Objects.requireNonNull(parameter, "parameter");
            if (sourceKind == SourceKind.GENERATED) {
                throw new IllegalStateException("Generated shaders expose parameters through the Java DSL");
            }
            exposedParameters.add(parameter);
            return this;
        }

        public RenderShaderDescriptor build() {
            return new RenderShaderDescriptor(this);
        }
    }

    record PreparedSource(
            GeneratedShaderResourceProvider generatedResources,
            long preparationNanos,
            boolean translationCacheHit
    ) {
        private static final PreparedSource NONE = new PreparedSource(null, 0L, false);
    }

    public enum SourceKind {
        RESOURCE,
        GENERATED,
        CUSTOM_FACTORY
    }
}
