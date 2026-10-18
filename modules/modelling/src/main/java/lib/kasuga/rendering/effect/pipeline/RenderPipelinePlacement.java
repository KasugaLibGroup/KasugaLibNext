package lib.kasuga.rendering.effect.pipeline;

import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Objects;
import java.util.Optional;

/** Explicit placement of a pipeline in either Kasuga's semantic phases or a native stage. */
public sealed interface RenderPipelinePlacement
        permits RenderPipelinePlacement.Semantic, RenderPipelinePlacement.Native {

    RenderLevelStageEvent.Stage nativeStage();

    Optional<RenderPhase> semanticPhase();

    static RenderPipelinePlacement semantic(RenderPhase phase) {
        return new Semantic(phase);
    }

    static RenderPipelinePlacement nativeStage(RenderLevelStageEvent.Stage stage) {
        return new Native(stage);
    }

    record Semantic(RenderPhase phase) implements RenderPipelinePlacement {
        public Semantic {
            Objects.requireNonNull(phase, "phase");
        }

        @Override
        public RenderLevelStageEvent.Stage nativeStage() {
            return phase.nativeStage();
        }

        @Override
        public Optional<RenderPhase> semanticPhase() {
            return Optional.of(phase);
        }
    }

    record Native(RenderLevelStageEvent.Stage stage) implements RenderPipelinePlacement {
        public Native {
            Objects.requireNonNull(stage, "stage");
        }

        @Override
        public RenderLevelStageEvent.Stage nativeStage() {
            return stage;
        }

        @Override
        public Optional<RenderPhase> semanticPhase() {
            return Optional.empty();
        }
    }
}
