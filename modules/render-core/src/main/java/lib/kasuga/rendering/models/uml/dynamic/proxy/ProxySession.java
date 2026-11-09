package lib.kasuga.rendering.models.uml.dynamic.proxy;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ManagedAnimationScope;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceResult;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-element owner of one logical scope and one declared composition.
 *
 * <p>The session never creates a second clock. Every successful tick observes input, prepares the
 * controller, advances this scope once and writes back only after the step commits. Frame inputs
 * are routed to the visual port and cannot alter revision.
 *
 * <p>Lifecycle, tick and frame operations are serialized. A tick either commits before writeback
 * or returns its failure through {@link ProxyTickOutcome}; a post-commit visual failure is
 * reported as both {@code advanced} and {@code aborted}. Rebind only rebuilds while the session is
 * waiting for a resource, so repeated calls after recovery are idempotent.
 */
public final class ProxySession<T, I, O, C, F> implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProxySession.class);

    private final ProxyAttachToken token;
    private final ProxyKey<T> key;
    private final T element;
    private final ProxyComposition<T, I, O, C, F> composition;
    private final ManagedAnimationScope scope;
    private final Consumer<ProxySession<?, ?, ?, ?, ?>> closeCallback;
    private final ArrayDeque<C> commands = new ArrayDeque<>();

    private ProxyLifecycleState state = ProxyLifecycleState.NEW;
    private Long lastGameTime;
    private boolean resumeAfterRecovery;

    ProxySession(
            ProxyAttachToken token,
            ProxyKey<T> key,
            T element,
            ProxyComposition<T, I, O, C, F> composition,
            Consumer<ProxySession<?, ?, ?, ?, ?>> closeCallback
    ) {
        this.token = Objects.requireNonNull(token, "token");
        this.key = Objects.requireNonNull(key, "key");
        this.element = Objects.requireNonNull(element, "element");
        this.composition = Objects.requireNonNull(composition, "composition");
        this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback");

        ManagedAnimationScope.Builder builder = ManagedAnimationScope.builder(
                        UUID.randomUUID(), composition.policy())
                .controller(ControllerBinding.create(composition.controllerPort().controller()));
        for (TargetBinding<?> target : composition.targets()) {
            builder.target(target);
        }
        ManagedAnimationScope built = builder.build();
        try {
            composition.visualPort().bind(built.renderPort());
            this.scope = built;
        } catch (RuntimeException | Error failure) {
            try {
                built.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                composition.visualPort().close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public ProxyAttachToken token() {
        return token;
    }

    public ProxyKey<T> key() {
        return key;
    }

    public synchronized ProxyLifecycleState state() {
        return state;
    }

    public synchronized long revision() {
        return scope.view().revision();
    }

    public synchronized boolean isClosed() {
        return state == ProxyLifecycleState.CLOSED;
    }

    public synchronized boolean activate() {
        if (state == ProxyLifecycleState.CLOSED || state == ProxyLifecycleState.ACTIVE) {
            return state == ProxyLifecycleState.ACTIVE;
        }
        if (state == ProxyLifecycleState.SUSPENDED) {
            return false;
        }
        if (state == ProxyLifecycleState.RESOURCE_WAIT) {
            return recoverVisual() && state == ProxyLifecycleState.ACTIVE;
        }
        return startVisual();
    }

    public synchronized ProxyTickOutcome tick(long gameTime, double deltaSeconds) {
        if (!ensureActive()) {
            return outcome(false, false, null);
        }
        if (lastGameTime != null && lastGameTime == gameTime) {
            return outcome(false, false, null);
        }
        return advanceObservedStep(gameTime, deltaSeconds);
    }

    private ProxyTickOutcome advanceObservedStep(long gameTime, double deltaSeconds) {
        try {
            drainCommands();
            I input = composition.inputPort().observe(element);
            composition.controllerPort().prepare(input);
        } catch (RuntimeException failure) {
            return outcome(false, true, failure);
        }

        long revisionBeforeAdvance = scope.view().revision();
        ScopeAdvanceResult result;
        try {
            result = scope.advance(new ScopeAdvance(
                    deltaSeconds, ScopeAdvanceCause.GAME_TICK));
        } catch (RuntimeException failure) {
            boolean advanced = scope.view().revision() != revisionBeforeAdvance;
            if (advanced) {
                lastGameTime = gameTime;
            }
            return outcome(advanced, true, failure);
        }
        boolean advanced = !result.steps().isEmpty();
        if (advanced) {
            lastGameTime = gameTime;
        }
        if (result.aborted()) {
            return outcome(advanced, true, result.failure());
        }
        lastGameTime = gameTime;
        return publishCommittedStep(advanced);
    }

    private ProxyTickOutcome publishCommittedStep(boolean advanced) {
        RuntimeException failure = null;
        try {
            composition.visualPort().tick(element);
        } catch (RuntimeException visualFailure) {
            failure = visualFailure;
            LOG.error("Proxy visual maintenance failed for {}", key.id(), visualFailure);
        }

        O output;
        try {
            output = composition.controllerPort().output();
        } catch (RuntimeException outputFailure) {
            return outcome(advanced, true, mergeFailure(failure, outputFailure));
        }
        if (output == null) {
            return outcome(advanced, failure != null, failure);
        }

        ProxyWritebackCheckpoint checkpoint;
        try {
            checkpoint = composition.writebackPort().beginWriteback(element);
        } catch (RuntimeException checkpointFailure) {
            return outcome(advanced, true, mergeFailure(failure, checkpointFailure));
        }
        try {
            composition.writebackPort().writeback(element, output);
            return outcome(advanced, failure != null, failure);
        } catch (RuntimeException writebackFailure) {
            try {
                checkpoint.rollback();
            } catch (RuntimeException rollbackFailure) {
                writebackFailure.addSuppressed(rollbackFailure);
            }
            return outcome(advanced, true, mergeFailure(failure, writebackFailure));
        }
    }

    public synchronized boolean publishFrame(F frame) {
        if (state == ProxyLifecycleState.CLOSED
                || state == ProxyLifecycleState.SUSPENDED
                || state == ProxyLifecycleState.RESOURCE_WAIT) {
            return false;
        }
        try {
            composition.visualPort().publishFrame(frame);
            return true;
        } catch (RuntimeException failure) {
            LOG.error("Proxy frame publication failed for {}", key.id(), failure);
            return false;
        }
    }

    public synchronized void submitCommand(C command) {
        Objects.requireNonNull(command, "command");
        if (state == ProxyLifecycleState.CLOSED) {
            return;
        }
        commands.addLast(command);
    }

    public synchronized void suspend() {
        if (state == ProxyLifecycleState.CLOSED || state == ProxyLifecycleState.SUSPENDED) {
            return;
        }
        if (state == ProxyLifecycleState.RESOURCE_WAIT) {
            resumeAfterRecovery = true;
            return;
        }
        transitionToQuiesced(ProxyLifecycleState.SUSPENDED, false);
    }

    public synchronized boolean resume() {
        if (state == ProxyLifecycleState.CLOSED) {
            return false;
        }
        if (state != ProxyLifecycleState.SUSPENDED) {
            if (state == ProxyLifecycleState.NEW) {
                return activate();
            }
            if (state == ProxyLifecycleState.RESOURCE_WAIT) {
                resumeAfterRecovery = false;
                return recoverVisual();
            }
            return state == ProxyLifecycleState.ACTIVE;
        }
        return startVisual();
    }

    public synchronized void waitForResource() {
        if (state == ProxyLifecycleState.CLOSED
                || state == ProxyLifecycleState.RESOURCE_WAIT) {
            return;
        }
        transitionToQuiesced(
                ProxyLifecycleState.RESOURCE_WAIT,
                state == ProxyLifecycleState.SUSPENDED);
    }

    /**
     * Swaps in a fresh visual subtree while preserving this session's logical scope. A failed
     * replacement leaves the session in resource-wait so the owner can retry.
     */
    public synchronized boolean rebind() {
        if (state == ProxyLifecycleState.CLOSED) {
            return false;
        }
        if (state == ProxyLifecycleState.ACTIVE
                || state == ProxyLifecycleState.SUSPENDED) {
            return true;
        }
        if (state != ProxyLifecycleState.RESOURCE_WAIT) {
            return false;
        }
        return recoverVisual();
    }

    synchronized ManagedAnimationScope animationScope() {
        return scope;
    }

    T elementForRuntime() {
        return element;
    }

    @Override
    public synchronized void close() {
        if (state == ProxyLifecycleState.CLOSED) {
            return;
        }
        state = ProxyLifecycleState.CLOSED;
        resumeAfterRecovery = false;
        commands.clear();
        RuntimeException failure = null;
        try {
            scope.close();
        } catch (RuntimeException cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            composition.visualPort().close();
        } catch (RuntimeException cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
        closeCallback.accept(this);
        if (failure != null) {
            throw failure;
        }
    }

    private void drainCommands() {
        while (!commands.isEmpty()) {
            composition.commandPort().accept(element, commands.peekFirst());
            commands.removeFirst();
        }
    }

    private boolean startVisual() {
        try {
            if (!composition.visualPort().isReady(element)) {
                enterResourceWait(false, null, true);
                return false;
            }
            composition.visualPort().resume();
            scope.resume();
            state = ProxyLifecycleState.ACTIVE;
            resumeAfterRecovery = false;
            return true;
        } catch (RuntimeException failure) {
            enterResourceWait(false, failure, true);
            return false;
        }
    }

    private boolean ensureActive() {
        if (state == ProxyLifecycleState.CLOSED) {
            return false;
        }
        if (state == ProxyLifecycleState.NEW) {
            return activate();
        }
        if (state == ProxyLifecycleState.RESOURCE_WAIT) {
            return recoverVisual() && state == ProxyLifecycleState.ACTIVE;
        }
        try {
            if (!composition.visualPort().isReady(element)) {
                enterResourceWait(false, null, true);
                return false;
            }
        } catch (RuntimeException failure) {
            enterResourceWait(false, failure, true);
            return false;
        }
        return state == ProxyLifecycleState.ACTIVE;
    }

    private boolean recoverVisual() {
        boolean preserveSuspended = resumeAfterRecovery;
        try {
            if (!composition.visualPort().isReady(element)
                    || !composition.visualPort().rebind(element)) {
                suspendAfterFailedRecovery(preserveSuspended, null);
                return false;
            }
            if (!preserveSuspended) {
                composition.visualPort().resume();
                scope.resume();
            }
            state = preserveSuspended
                    ? ProxyLifecycleState.SUSPENDED
                    : ProxyLifecycleState.ACTIVE;
            resumeAfterRecovery = false;
            return true;
        } catch (RuntimeException failure) {
            suspendAfterFailedRecovery(preserveSuspended, failure);
            return false;
        }
    }

    private void suspendAfterFailedRecovery(
            boolean preserveSuspended,
            @Nullable RuntimeException failure
    ) {
        enterResourceWait(preserveSuspended, failure, failure != null);
    }

    private void enterResourceWait(
            boolean preserveSuspended,
            @Nullable RuntimeException failure,
            boolean quiesce
    ) {
        state = ProxyLifecycleState.RESOURCE_WAIT;
        resumeAfterRecovery = preserveSuspended;
        if (failure != null) {
            LOG.error("Proxy visual lifecycle failed for {}", key.id(), failure);
        }
        if (quiesce) {
            quiesceVisual(failure);
        }
    }

    private void transitionToQuiesced(
            ProxyLifecycleState target,
            boolean preserveSuspended
    ) {
        boolean alreadyQuiesced = state == ProxyLifecycleState.SUSPENDED
                || state == ProxyLifecycleState.RESOURCE_WAIT;
        state = target;
        resumeAfterRecovery = preserveSuspended;
        if (!alreadyQuiesced) {
            quiesceVisual(null);
        }
    }

    private void quiesceVisual(@Nullable RuntimeException failure) {
        try {
            scope.pause();
        } catch (RuntimeException pauseFailure) {
            attachFailure(failure, pauseFailure);
        }
        try {
            composition.visualPort().suspend();
        } catch (RuntimeException suspendFailure) {
            attachFailure(failure, suspendFailure);
        }
    }

    private static RuntimeException mergeFailure(
            @Nullable RuntimeException first,
            RuntimeException second
    ) {
        if (first == null) {
            return second;
        }
        attachFailure(first, second);
        return first;
    }

    private static void attachFailure(
            @Nullable RuntimeException primary,
            RuntimeException secondary
    ) {
        if (primary != null && primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    private ProxyTickOutcome outcome(boolean advanced, boolean aborted,
                                     @Nullable RuntimeException failure) {
        return new ProxyTickOutcome(
                state, advanced, scope.view().revision(), aborted, failure);
    }
}
