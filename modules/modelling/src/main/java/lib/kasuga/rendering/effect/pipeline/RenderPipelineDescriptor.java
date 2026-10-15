package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Immutable scheduling descriptor with an independently reusable draw-state declaration. */
public final class RenderPipelineDescriptor {
    private final ResourceLocation id;
    private final RenderPipelinePlacement placement;
    private final int priority;
    private final RenderDrawState drawState;

    private RenderPipelineDescriptor(Builder builder) {
        id = Objects.requireNonNull(builder.id, "id");
        placement = Objects.requireNonNull(builder.placement, "placement");
        priority = builder.priority;
        drawState = builder.explicitDrawState == null
                ? builder.drawState.build()
                : builder.explicitDrawState;
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    public static Builder builder(ResourceLocation id, RenderPhase phase) {
        return new Builder(id).placement(RenderPipelinePlacement.semantic(phase));
    }

    public static Builder builder(ResourceLocation id, RenderLevelStageEvent.Stage stage) {
        return new Builder(id).placement(RenderPipelinePlacement.nativeStage(stage));
    }

    public ResourceLocation id() { return id; }
    public RenderPipelinePlacement placement() { return placement; }
    public RenderLevelStageEvent.Stage stage() { return placement.nativeStage(); }
    public Optional<RenderPhase> phase() { return placement.semanticPhase(); }
    public int priority() { return priority; }
    public RenderDrawState drawState() { return drawState; }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private RenderPipelinePlacement placement;
        private int priority;
        private RenderDrawState.Builder drawState = RenderDrawState.builder();
        private RenderDrawState explicitDrawState;

        private Builder(ResourceLocation id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        private Builder(RenderPipelineDescriptor descriptor) {
            id = descriptor.id;
            placement = descriptor.placement;
            priority = descriptor.priority;
            explicitDrawState = descriptor.drawState;
            drawState = descriptor.drawState.toBuilder();
        }

        public Builder placement(RenderPipelinePlacement value) {
            placement = Objects.requireNonNull(value, "placement");
            return this;
        }

        public Builder priority(int value) {
            priority = value;
            return this;
        }

        public Builder drawState(RenderDrawState value) {
            explicitDrawState = Objects.requireNonNull(value, "drawState");
            drawState = value.toBuilder();
            return this;
        }

        public Builder draw(Consumer<RenderDrawState.Builder> definition) {
            Objects.requireNonNull(definition, "definition").accept(drawState());
            return this;
        }

        /** Returns the mutable draw-state builder for advanced composition. */
        public RenderDrawState.Builder drawState() {
            explicitDrawState = null;
            return drawState;
        }

        public RenderPipelineDescriptor build() {
            if (placement == null) throw new IllegalStateException("A render placement is required for " + id);
            return new RenderPipelineDescriptor(this);
        }
    }
}
