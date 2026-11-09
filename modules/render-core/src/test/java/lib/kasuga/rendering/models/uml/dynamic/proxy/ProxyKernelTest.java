package lib.kasuga.rendering.models.uml.dynamic.proxy;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerCheckpoint;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyKernelTest {

    private static final LogicalStepPolicy POLICY = LogicalStepPolicy.fixed(0.05, 5);

    @Test
    void observationAdvancesOnceThenWritesBackAndFramesDoNotChangeRevision() {
        ProxyRuntime runtime = new ProxyRuntime();
        List<String> order = new ArrayList<>();
        RecordingController controller = new RecordingController(order);
        RecordingVisual visual = new RecordingVisual(order);
        ProxyKey<String> key = ProxyKey.blockEntity("test", "ordered");
        ProxyDefinition<String, String, String, String, String> definition = definition(
                key,
                element -> {
                    order.add("observe");
                    return "input";
                },
                controller,
                (element, output) -> order.add("writeback:" + output),
                visual);
        runtime.register(definition);

        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "block", ProxyAttachToken.random()).orElseThrow();
        session.activate();

        ProxyTickOutcome first = session.tick(10L, 0.05);
        ProxyTickOutcome duplicate = session.tick(10L, 0.05);
        long revisionBeforeFrame = session.revision();
        assertTrue(session.publishFrame("frame"));

        assertTrue(first.advanced());
        assertFalse(duplicate.advanced());
        assertEquals(1, controller.advances.get());
        assertEquals(
                List.of("bind", "observe", "prepare:input", "advance", "tick",
                        "output", "writeback:output"),
                order.subList(0, 7));
        assertEquals(revisionBeforeFrame, session.revision());
        assertEquals(List.of("frame"), visual.frames);
    }

    @Test
    void duplicateTokensAndDuplicateElementOwnersAreRejected() {
        ProxyRuntime runtime = new ProxyRuntime();
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.entity("test", "duplicate"),
                        element -> "input", new RecordingController(new ArrayList<>()),
                        (element, output) -> {
                        }, new RecordingVisual(new ArrayList<>()));
        runtime.register(definition);
        ProxyAttachToken token = ProxyAttachToken.random();
        String element = "entity";

        runtime.attach(definition, element, token).orElseThrow();

        assertThrows(IllegalStateException.class,
                () -> runtime.attach(definition, "other", token));
        assertThrows(IllegalStateException.class,
                () -> runtime.attach(definition, element, ProxyAttachToken.random()));
        assertFalse(runtime.attach(ProxyKey.item("test", "unregistered"), new Object(),
                ProxyAttachToken.random()).isPresent());
    }

    @Test
    void failedAdvanceDoesNotWriteBackOrChangeRevision() {
        ProxyRuntime runtime = new ProxyRuntime();
        AtomicInteger writebacks = new AtomicInteger();
        RecordingController controller = new RecordingController(new ArrayList<>());
        controller.failure = new IllegalStateException("controller failed");
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.item("test", "failure"), element -> "input",
                        controller, (element, output) -> writebacks.incrementAndGet(),
                        new RecordingVisual(new ArrayList<>()));
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "stack", ProxyAttachToken.random()).orElseThrow();

        ProxyTickOutcome outcome = session.tick(1L, 0.05);

        assertTrue(outcome.aborted());
        assertEquals(0L, outcome.revision());
        assertEquals(0, writebacks.get());
    }

    @Test
    void visualTickFailureDoesNotSkipCommittedWriteback() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        RuntimeException failure = new IllegalStateException("visual maintenance failed");
        visual.tickFailure = failure;
        AtomicInteger writebacks = new AtomicInteger();
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.external("test", "visual-failure"), element -> "input",
                        controller, (element, output) -> writebacks.incrementAndGet(), visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", ProxyAttachToken.random()).orElseThrow();

        ProxyTickOutcome outcome = session.tick(1L, 0.05);

        assertTrue(outcome.advanced());
        assertTrue(outcome.aborted());
        assertSame(failure, outcome.failure());
        assertEquals(1L, outcome.revision());
        assertEquals(1, writebacks.get());
    }

    @Test
    void controllerOutputFailureIsReportedWithoutWriteback() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RuntimeException failure = new IllegalStateException("output failed");
        controller.outputFailure = failure;
        AtomicInteger writebacks = new AtomicInteger();
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.external("test", "output-failure"), element -> "input",
                        controller, (element, output) -> writebacks.incrementAndGet(),
                        new RecordingVisual(new ArrayList<>()));
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", ProxyAttachToken.random()).orElseThrow();

        ProxyTickOutcome outcome = session.tick(1L, 0.05);

        assertTrue(outcome.advanced());
        assertTrue(outcome.aborted());
        assertSame(failure, outcome.failure());
        assertEquals(1L, outcome.revision());
        assertEquals(0, writebacks.get());
    }

    @Test
    void partialScopeCommitIsReportedWithoutWritebackAndBlocksSameTimeRetry() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RuntimeException failure = new IllegalStateException("second step failed");
        controller.failure = failure;
        controller.failOnAdvance = 2;
        AtomicInteger writebacks = new AtomicInteger();
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.external("test", "partial-commit"), element -> "input",
                        controller, (element, output) -> writebacks.incrementAndGet(),
                        new RecordingVisual(new ArrayList<>()));
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", ProxyAttachToken.random()).orElseThrow();

        ProxyTickOutcome partial = session.tick(1L, 0.10);
        ProxyTickOutcome duplicate = session.tick(1L, 0.10);

        assertTrue(partial.advanced());
        assertTrue(partial.aborted());
        assertEquals(1L, partial.revision());
        assertSame(failure, partial.failure());
        assertEquals(2, controller.advances.get());
        assertEquals(0, writebacks.get());
        assertFalse(duplicate.advanced());
        assertEquals(2, controller.advances.get());
    }

    @Test
    void failedCommandDoesNotConsumeTheLogicalStepAndCanBeRetried() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        AtomicInteger observations = new AtomicInteger();
        AtomicInteger commandApplications = new AtomicInteger();
        AtomicBoolean failCommand = new AtomicBoolean(true);
        RuntimeException failure = new IllegalStateException("command failed");
        ProxyCommandPort<String, String> commandPort = (element, command) -> {
            commandApplications.incrementAndGet();
            if (failCommand.getAndSet(false)) {
                throw failure;
            }
        };
        ProxyDefinition<String, String, String, String, String> definition =
                new ProxyDefinition<>(
                        ProxyKey.external("test", "command-failure"),
                        element -> composition(
                                ignored -> {
                                    observations.incrementAndGet();
                                    return "input";
                                },
                                controller,
                                (ignored, output) -> {
                                },
                                commandPort,
                                new RecordingVisual(new ArrayList<>())));
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", ProxyAttachToken.random()).orElseThrow();
        session.activate();
        session.submitCommand("cycle");

        ProxyTickOutcome failed = session.tick(1L, 0.05);
        ProxyTickOutcome retried = session.tick(1L, 0.05);

        assertTrue(failed.aborted());
        assertSame(failure, failed.failure());
        assertFalse(failed.advanced());
        assertEquals(0L, failed.revision());
        assertFalse(retried.aborted());
        assertTrue(retried.advanced());
        assertEquals(1, observations.get());
        assertEquals(1, controller.advances.get());
        assertEquals(2, commandApplications.get(),
                "the failing command must be retried before it is removed");
    }

    @Test
    void templateCreationRunsOutsideTheRuntimeLock() throws Exception {
        ProxyRuntime runtime = new ProxyRuntime();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProxyKey<String> key = ProxyKey.external("test", "lock-scope");
            ProxyDefinition<String, String, String, String, String> definition =
                    new ProxyDefinition<>(key, element -> {
                        Future<?> probe = executor.submit(runtime::sessions);
                        try {
                            probe.get(1, TimeUnit.SECONDS);
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("probe interrupted", failure);
                        } catch (ExecutionException | TimeoutException failure) {
                            throw new IllegalStateException(
                                    "template creation must not hold the runtime lock", failure);
                        }
                        return composition(
                                ignored -> "input",
                                new RecordingController(new ArrayList<>()),
                                (ignored, output) -> {
                                },
                                new RecordingVisual(new ArrayList<>()));
                    });
            runtime.register(definition);

            ProxySession<String, String, String, String, String> session =
                    runtime.attach(definition, "external", ProxyAttachToken.random())
                            .orElseThrow();
            session.close();
            ProxySession<?, ?, ?, ?, ?> byKey =
                    runtime.attach(key, "external-by-key", ProxyAttachToken.random())
                            .orElseThrow();
            byKey.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void writebackFailureRunsItsCheckpoint() {
        ProxyRuntime runtime = new ProxyRuntime();
        AtomicBoolean rolledBack = new AtomicBoolean();
        ProxyWritebackPort<String, String> writeback = new ProxyWritebackPort<>() {
            @Override
            public void writeback(String element, String output) {
                throw new IllegalStateException("writeback failed");
            }

            @Override
            public ProxyWritebackCheckpoint beginWriteback(String element) {
                return () -> rolledBack.set(true);
            }
        };
        ProxyDefinition<String, String, String, String, String> definition =
                new ProxyDefinition<>(
                        ProxyKey.external("test", "writeback-failure"),
                        ignored -> composition(element -> "input",
                                new RecordingController(new ArrayList<>()), writeback,
                                new RecordingVisual(new ArrayList<>())));
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", ProxyAttachToken.random()).orElseThrow();

        ProxyTickOutcome outcome = session.tick(1L, 0.05);

        assertTrue(outcome.aborted());
        assertTrue(rolledBack.get());
    }

    @Test
    void failedCompositionCreationDoesNotConsumeTheAttachToken() {
        ProxyRuntime runtime = new ProxyRuntime();
        ProxyKey<String> key = ProxyKey.external("test", "factory-failure");
        ProxyDefinition<String, String, String, String, String> broken =
                new ProxyDefinition<>(key, element -> {
                    throw new IllegalStateException("factory failed");
                });
        runtime.register(broken);
        ProxyAttachToken token = ProxyAttachToken.random();

        assertThrows(IllegalStateException.class,
                () -> runtime.attach(broken, "external", token));

        ProxyDefinition<String, String, String, String, String> good =
                definition(key, element -> "input",
                        new RecordingController(new ArrayList<>()),
                        (element, output) -> {
                        }, new RecordingVisual(new ArrayList<>()));
        runtime.unregister(key);
        runtime.register(good);
        assertTrue(runtime.attach(good, "external", token).isPresent());
    }

    @Test
    void targetsConsumeOneCommittedPublicationAndFramesDoNotPublishAgain() {
        ProxyRuntime runtime = new ProxyRuntime();
        AtomicInteger publications = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AnimationTarget<String> target = new AnimationTarget<>() {
            private final AnimationTargetRef ref = AnimationTargetRef.property("test");

            @Override
            public AnimationTargetRef ref() {
                return ref;
            }

            @Override
            public void publish(TargetPublication<String> publication) {
                publications.incrementAndGet();
            }

            @Override
            public void sample(TargetPublication<String> publication,
                               RenderSampleContext context) {
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        ProxyDefinition<String, String, String, String, String> definition =
                new ProxyDefinition<>(ProxyKey.external("test", "target"),
                        element -> ProxyComposition.of(
                                POLICY,
                                ignored -> "input",
                                new RecordingController(new ArrayList<>()),
                                (ignored, output) -> {
                                },
                                ProxyCommandPort.none(),
                                ProxyVisualPort.none(),
                                List.of(new TargetBinding<>(
                                        target,
                                        context -> "pose",
                                        new TargetPublicationFactory<>()))));
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", ProxyAttachToken.random()).orElseThrow();

        session.tick(1L, 0.05);
        session.publishFrame("frame");

        assertEquals(1, publications.get());
        assertEquals(0, closes.get());
        session.close();
        assertEquals(1, closes.get());
    }

    @Test
    void suspendResumeAndCloseAreExplicitAndIdempotent() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.external("test", "lifecycle"), element -> "input",
                        controller, (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxyAttachToken token = ProxyAttachToken.random();
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", token).orElseThrow();

        session.activate();
        session.tick(1L, 0.05);
        session.suspend();
        session.suspend();
        session.tick(2L, 0.05);
        assertTrue(session.resume());
        assertTrue(session.resume());
        session.tick(3L, 0.05);
        session.close();
        session.close();

        assertEquals(2, controller.advances.get());
        assertEquals(1, visual.suspends.get());
        assertEquals(2, visual.resumes.get());
        assertEquals(1, visual.closes.get());
        assertEquals(ProxyLifecycleState.CLOSED, session.state());
        assertFalse(runtime.session(token).isPresent());
    }

    @Test
    void resourceWaitRebindKeepsTheSameLogicalScope() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.item("test", "resource"), element -> "input",
                        controller, (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "stack", ProxyAttachToken.random()).orElseThrow();
        UUID scopeId = session.animationScope().view().scopeId();
        ProxyTickOutcome beforeReload = session.tick(1L, 0.05);

        assertTrue(beforeReload.advanced(), beforeReload::toString);
        visual.ready = false;
        session.waitForResource();
        assertEquals(ProxyLifecycleState.RESOURCE_WAIT, session.state());

        visual.ready = true;
        assertTrue(session.rebind());
        assertTrue(session.rebind());
        assertEquals(1, visual.rebinds.get());
        assertEquals(scopeId, session.animationScope().view().scopeId());
        long revisionAfterReload = session.revision();
        assertTrue(revisionAfterReload >= beforeReload.revision());
        assertEquals(1, controller.advances.get());
        ProxyTickOutcome afterReload = session.tick(2L, 0.05);
        assertTrue(afterReload.advanced(), afterReload::toString);
        assertTrue(afterReload.revision() > revisionAfterReload);
        assertEquals(2, controller.advances.get());
    }

    @Test
    void suspendedResourceWaitRecoversWithoutResumingTheHost() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.item("test", "suspended-resource"), element -> "input",
                        controller, (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "stack", ProxyAttachToken.random()).orElseThrow();
        session.activate();
        session.suspend();
        session.waitForResource();

        assertTrue(session.rebind());
        assertEquals(ProxyLifecycleState.SUSPENDED, session.state());
        assertFalse(session.tick(1L, 0.05).advanced());
        assertEquals(0, controller.advances.get());

        assertTrue(session.resume());
        assertEquals(ProxyLifecycleState.ACTIVE, session.state());
    }

    @Test
    void suspendDuringResourceWaitPreservesPendingVisualRecovery() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.item("test", "suspend-resource-wait"), element -> "input",
                        controller, (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "stack", ProxyAttachToken.random()).orElseThrow();
        session.activate();
        visual.ready = false;
        session.waitForResource();
        session.suspend();
        session.suspend();

        assertEquals(ProxyLifecycleState.RESOURCE_WAIT, session.state());

        visual.ready = true;
        assertFalse(session.tick(1L, 0.05).advanced());
        assertEquals(ProxyLifecycleState.SUSPENDED, session.state());
        assertEquals(1, visual.rebinds.get());
        assertEquals(0, controller.advances.get());

        assertTrue(session.resume());
        ProxyTickOutcome resumedTick = session.tick(1L, 0.05);
        assertTrue(resumedTick.advanced(), resumedTick::toString);
        assertEquals(1, controller.advances.get());
    }

    @Test
    void activateCannotConsumeASuspendedResourceRecovery() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.item("test", "activate-suspended-resource"),
                        element -> "input", controller, (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "stack", ProxyAttachToken.random()).orElseThrow();
        session.activate();
        visual.ready = false;
        session.waitForResource();
        session.suspend();

        visual.ready = true;
        assertFalse(session.activate());

        assertEquals(ProxyLifecycleState.SUSPENDED, session.state());
        assertFalse(session.tick(1L, 0.05).advanced());
        assertEquals(0, controller.advances.get());
    }

    @Test
    void visualReadinessFailureTransitionsToResourceWaitInsteadOfEscaping() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        visual.readyFailure = new IllegalStateException("readiness failed");
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.item("test", "readiness-failure"), element -> "input",
                        new RecordingController(new ArrayList<>()), (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "stack", ProxyAttachToken.random()).orElseThrow();

        assertFalse(session.activate());
        assertEquals(ProxyLifecycleState.RESOURCE_WAIT, session.state());

        visual.readyFailure = null;
        visual.ready = true;
        assertTrue(session.rebind());
        assertEquals(ProxyLifecycleState.ACTIVE, session.state());
    }

    @Test
    void activeVisualBecomingUnavailablePausesBeforeTheNextLogicalStep() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingController controller = new RecordingController(new ArrayList<>());
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.item("test", "late-resource-wait"), element -> "input",
                        controller, (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "stack", ProxyAttachToken.random()).orElseThrow();

        assertTrue(session.tick(1L, 0.05).advanced());
        visual.ready = false;
        ProxyTickOutcome waiting = session.tick(2L, 0.05);

        assertFalse(waiting.advanced());
        assertEquals(ProxyLifecycleState.RESOURCE_WAIT, session.state());
        assertEquals(1, controller.advances.get());

        visual.ready = true;
        assertTrue(session.tick(3L, 0.05).advanced());
        assertEquals(2, controller.advances.get());
    }

    @Test
    void tickAndSuspendAreSerializedAcrossThreads() throws Exception {
        ProxyRuntime runtime = new ProxyRuntime();
        BlockingInputPort input = new BlockingInputPort();
        RecordingController controller = new RecordingController(
                Collections.synchronizedList(new ArrayList<>()));
        RecordingVisual visual = new RecordingVisual(
                Collections.synchronizedList(new ArrayList<>()));
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.entity("test", "threaded"), input, controller,
                        (element, output) -> visual.order.add("writeback"), visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "entity", ProxyAttachToken.random()).orElseThrow();
        session.activate();

        AtomicReference<ProxyTickOutcome> tickOutcome = new AtomicReference<>();
        Thread tickThread = new Thread(() -> tickOutcome.set(session.tick(1L, 0.05)));
        CountDownLatch suspendStarted = new CountDownLatch(1);
        Thread suspendThread = new Thread(() -> {
            suspendStarted.countDown();
            session.suspend();
        });

        try {
            tickThread.start();
            assertTrue(input.entered.await(2, TimeUnit.SECONDS));
            suspendThread.start();
            assertTrue(suspendStarted.await(2, TimeUnit.SECONDS));
            awaitCondition(() -> suspendThread.getState() == Thread.State.BLOCKED
                    || session.state() == ProxyLifecycleState.SUSPENDED);

            input.release.countDown();
            tickThread.join(2_000L);
            suspendThread.join(2_000L);
        } finally {
            input.release.countDown();
        }

        assertFalse(tickThread.isAlive());
        assertFalse(suspendThread.isAlive());
        assertNotNull(tickOutcome.get());
        assertEquals(ProxyLifecycleState.ACTIVE, tickOutcome.get().state());
        assertEquals(ProxyLifecycleState.SUSPENDED, session.state());
        assertEquals(
                List.of("tick", "writeback", "suspend"),
                visual.order.subList(visual.order.size() - 3, visual.order.size()));
    }

    @Test
    void frameFailureIsContainedWithoutChangingLogicalRevision() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        visual.frameFailure = new IllegalStateException("frame failed");
        ProxyDefinition<String, String, String, String, String> definition =
                definition(ProxyKey.external("test", "frame-failure"), element -> "input",
                        new RecordingController(new ArrayList<>()), (element, output) -> {
                        }, visual);
        runtime.register(definition);
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", ProxyAttachToken.random()).orElseThrow();
        session.tick(1L, 0.05);
        long revision = session.revision();

        assertFalse(session.publishFrame("frame"));
        assertEquals(revision, session.revision());
        assertEquals(ProxyLifecycleState.ACTIVE, session.state());
    }

    @Test
    void closeContinuesVisualReleaseAndDetachAfterTargetCleanupFails() {
        ProxyRuntime runtime = new ProxyRuntime();
        RecordingVisual visual = new RecordingVisual(new ArrayList<>());
        AnimationTarget<String> brokenTarget = new AnimationTarget<>() {
            private final AnimationTargetRef ref = AnimationTargetRef.property("close-failure");

            @Override
            public AnimationTargetRef ref() {
                return ref;
            }

            @Override
            public void publish(TargetPublication<String> publication) {
            }

            @Override
            public void sample(TargetPublication<String> publication,
                               RenderSampleContext context) {
            }

            @Override
            public void close() {
                throw new IllegalStateException("target close failed");
            }
        };
        ProxyDefinition<String, String, String, String, String> definition =
                new ProxyDefinition<>(ProxyKey.external("test", "close-failure"),
                        element -> ProxyComposition.of(
                                POLICY,
                                ignored -> "input",
                                new RecordingController(new ArrayList<>()),
                                (ignored, output) -> {
                                },
                                ProxyCommandPort.none(),
                                visual,
                                List.of(new TargetBinding<>(
                                        brokenTarget,
                                        context -> "pose",
                                        new TargetPublicationFactory<>()))));
        runtime.register(definition);
        ProxyAttachToken token = ProxyAttachToken.random();
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "external", token).orElseThrow();

        assertThrows(IllegalStateException.class, session::close);

        assertEquals(1, visual.closes.get());
        assertEquals(ProxyLifecycleState.CLOSED, session.state());
        assertFalse(runtime.session(token).isPresent());
        session.close();
        assertEquals(1, visual.closes.get());
    }

    @Test
    void allTypedHostKindsUseTheSameRuntimeBoundary() {
        ProxyRuntime runtime = new ProxyRuntime();
        List<ProxyKey<String>> keys = List.of(
                ProxyKey.blockEntity("test", "block"),
                ProxyKey.entity("test", "entity"),
                ProxyKey.item("test", "item"),
                ProxyKey.external("test", "external"));
        for (ProxyKey<String> key : keys) {
            ProxyDefinition<String, String, String, String, String> definition =
                    definition(key, element -> "input",
                            new RecordingController(new ArrayList<>()),
                            (element, output) -> {
                            }, new RecordingVisual(new ArrayList<>()));
            runtime.register(definition);
            assertTrue(runtime.attach(definition, "element-" + key.id(),
                    ProxyAttachToken.random()).isPresent());
        }
        assertEquals(ProxyKind.BLOCK_ENTITY, keys.get(0).kind());
        assertEquals(ProxyKind.ENTITY, keys.get(1).kind());
        assertEquals(ProxyKind.ITEM, keys.get(2).kind());
        assertEquals(ProxyKind.EXTERNAL, keys.get(3).kind());
    }

    private static ProxyDefinition<String, String, String, String, String> definition(
            ProxyKey<String> key,
            ProxyInputPort<String, String> input,
            ProxyControllerPort<String, String> controller,
            ProxyWritebackPort<String, String> writeback,
            RecordingVisual visual
    ) {
        return new ProxyDefinition<>(key, element -> composition(
                input, controller, writeback, visual));
    }

    private static ProxyComposition<String, String, String, String, String> composition(
            ProxyInputPort<String, String> input,
            ProxyControllerPort<String, String> controller,
            ProxyWritebackPort<String, String> writeback,
            RecordingVisual visual
    ) {
        return ProxyComposition.of(
                POLICY, input, controller, writeback,
                ProxyCommandPort.none(), visual, List.of());
    }

    private static ProxyComposition<String, String, String, String, String> composition(
            ProxyInputPort<String, String> input,
            ProxyControllerPort<String, String> controller,
            ProxyWritebackPort<String, String> writeback,
            ProxyCommandPort<String, String> command,
            RecordingVisual visual
    ) {
        return ProxyComposition.of(
                POLICY, input, controller, writeback,
                command, visual, List.of());
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not reached before timeout");
            }
            Thread.sleep(5L);
        }
    }

    private static final class RecordingController implements ProxyControllerPort<String, String> {
        private final List<String> order;
        private final AtomicInteger advances = new AtomicInteger();
        private RuntimeException failure;
        private RuntimeException outputFailure;
        private int failOnAdvance;
        private String output = "output";

        private RecordingController(List<String> order) {
            this.order = order;
        }

        @Override
        public void prepare(String input) {
            order.add("prepare:" + input);
        }

        @Override
        public RuntimeController<Void> controller() {
            return new RuntimeController<>() {
                @Override
                public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                    if (request.timeStep().cause() == ScopeAdvanceCause.GAME_TICK) {
                        order.add("advance");
                        advances.incrementAndGet();
                        if (failure != null
                                && (failOnAdvance == 0 || advances.get() == failOnAdvance)) {
                            throw failure;
                        }
                    }
                    return new ControlFrame<>(null, List.of(), List.of());
                }

                @Override
                public ControllerCheckpoint beginStep() {
                    return ControllerCheckpoint.NONE;
                }
            };
        }

        @Override
        public String output() {
            order.add("output");
            if (outputFailure != null) {
                throw outputFailure;
            }
            return output;
        }
    }

    private static final class RecordingVisual implements ProxyVisualPort<String, String> {
        private final List<String> order;
        private final List<String> frames = new ArrayList<>();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger rebinds = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
        private final AtomicInteger suspends = new AtomicInteger();
        private boolean ready = true;
        private RuntimeException readyFailure;
        private RuntimeException tickFailure;
        private RuntimeException frameFailure;

        private RecordingVisual(List<String> order) {
            this.order = order;
        }

        @Override
        public boolean isReady(String element) {
            if (readyFailure != null) {
                throw readyFailure;
            }
            return ready;
        }

        @Override
        public void bind(lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort port) {
            order.add("bind");
        }

        @Override
        public void publishFrame(String frame) {
            order.add("frame");
            frames.add(frame);
            if (frameFailure != null) {
                throw frameFailure;
            }
        }

        @Override
        public void tick(String element) {
            order.add("tick");
            if (tickFailure != null) {
                throw tickFailure;
            }
        }

        @Override
        public void suspend() {
            suspends.incrementAndGet();
            order.add("suspend");
        }

        @Override
        public void resume() {
            resumes.incrementAndGet();
        }

        @Override
        public boolean rebind(String element) {
            order.add("rebind");
            rebinds.incrementAndGet();
            return ready;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final class BlockingInputPort implements ProxyInputPort<String, String> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String observe(String element) {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release input");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("input observation interrupted", interrupted);
            }
            return "input";
        }
    }
}
