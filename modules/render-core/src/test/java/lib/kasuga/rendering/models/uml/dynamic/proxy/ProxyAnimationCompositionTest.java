package lib.kasuga.rendering.models.uml.dynamic.proxy;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackBoundaryPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackCommand;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopedPlaybackCommandApplier;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyAnimationCompositionTest {

    @Test
    void sessionInstallsRootsAndAppliesControllerPlaybackCommands() {
        ProxyRuntime runtime = new ProxyRuntime();
        AtomicReference<List<String>> observed = new AtomicReference<>(List.of());
        RuntimeController<Void> controller = new RuntimeController<>() {
            private boolean started;

            @Override
            public ControlFrame<Void> evaluate(
                    lib.kasuga.rendering.models.uml.dynamic.animation.scope
                            .ControlEvaluationRequest<Void> request
            ) {
                if (started) {
                    return new ControlFrame<>(null, List.of(), List.of());
                }
                started = true;
                return new ControlFrame<>(
                        null,
                        List.of(new PlaybackCommand.Play(
                                "dynamic", Optional.empty(), Optional.of(0.0), 1.0)),
                        List.of()
                );
            }
        };
        AnimationTarget<List<String>> target = new AnimationTarget<>() {
            @Override
            public AnimationTargetRef ref() {
                return AnimationTargetRef.property("observed-playbacks");
            }

            @Override
            public void publish(TargetPublication<List<String>> publication) {
            }

            @Override
            public void sample(
                    TargetPublication<List<String>> publication,
                    RenderSampleContext context
            ) {
            }
        };
        PlaybackBinding binding = new PlaybackBinding(
                0.0, 5.0, 0.0, 5.0, 1.0, PlaybackBoundaryPolicy.HOLD_LAST);
        ProxyComposition<String, Void, Void, Void, Void> composition = ProxyComposition.of(
                LogicalStepPolicy.fixed(0.05, 5),
                ignored -> null,
                new ProxyControllerPort<Void, Void>() {
                    @Override
                    public void prepare(Void input) {
                    }

                    @Override
                    public RuntimeController<Void> controller() {
                        return controller;
                    }

                    @Override
                    public Void output() {
                        return null;
                    }
                },
                (ignored, output) -> {
                },
                ProxyCommandPort.none(),
                ProxyVisualPort.none(),
                List.of(new TargetBinding<>(
                        target,
                        context -> {
                            List<String> ids = context.playbackTimes().stream()
                                    .map(time -> time.definitionId())
                                    .toList();
                            observed.set(ids);
                            return ids;
                        },
                        new TargetPublicationFactory<>()
                )),
                List.of(new ProxyPlaybackRoot("root", binding)),
                ScopedPlaybackCommandApplier.factory(Map.of("dynamic", binding))
        );
        ProxyDefinition<String, Void, Void, Void, Void> definition =
                new ProxyDefinition<>(
                        ProxyKey.external("test", "animation-composition"),
                        ignored -> composition);
        runtime.register(definition);
        ProxySession<String, Void, Void, Void, Void> session =
                runtime.attach(definition, "element", ProxyAttachToken.random()).orElseThrow();

        ProxyTickOutcome outcome = session.tick(1L, 0.05);

        assertTrue(outcome.advanced());
        assertEquals(List.of("root", "dynamic"), observed.get());
        session.close();
    }
}
