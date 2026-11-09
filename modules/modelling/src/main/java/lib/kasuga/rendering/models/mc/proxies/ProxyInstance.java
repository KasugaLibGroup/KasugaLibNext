package lib.kasuga.rendering.models.mc.proxies;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmAnimatedModel;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Supplier;

public class ProxyInstance<T> implements AutoCloseable{

    private static final Logger LOGGER = LogUtils.getLogger();

    private enum Stage {
        NEW,
        LIVE,
        FAILED,
        RELEASED
    }

    private final Object owner;
    private final T element;
    private final ElementProxy<T> template;
    @Nullable private final ProxyIdentity fixedIdentity;
    @Nullable private final Supplier<Transform> fixedRoot;

    private Stage stage = Stage.NEW;
    @Nullable private ProxyIdentity identity;
    @Nullable private ProxyConfig config;
    @Nullable private FsmAnimatedModel fsm;
    @Nullable private McModelHandle handle;
    @Nullable private ModelGroupScope modelGroupScope;
    @Nullable private RigidPartVisualStage partStage;
    @Nullable private Level lastLevel;
    @Nullable private Boolean clientSide;
    @Nullable private Supplier<Transform> rootSupplier;
    private boolean detached;

    private long lastTickGameTime = Long.MIN_VALUE;

    public ProxyInstance(Object owner, T element, ElementProxy<T> template, @Nullable ProxyIdentity fixedIdentity, @Nullable Supplier<Transform> fixedRoot) {
        this.owner = owner;
        this.element = element;
        this.template = template;
        this.fixedIdentity = fixedIdentity;
        this.fixedRoot = fixedRoot;
    }

    public @Nullable ProxyIdentity identity() {
        return this.identity;
    }

    public @Nullable StateMachine<?> machine() {
        return fsm == null || lastLevel == null ? null : fsm.machine(lastLevel);
    }

    public void tick(Level level, BlockPos pos) {
        if (stage == Stage.RELEASED || level == null) return;
        lastLevel = level;

        long now = level.getGameTime();
        if(now == lastTickGameTime) return;
        lastTickGameTime = now;

        if (stage == Stage.NEW) {
            ProxyDebug.log("materialize start: element={} pos={} side={}", elementName(), pos, sideName(level));
            stage = materialize(level, pos) ? Stage.LIVE : Stage.FAILED;
            ProxyDebug.log("materialize result: identity={} stage={} machine={} model={} parts={}",
                    identity, stage,
                    config == null ? null : config.machine(),
                    config == null ? null : config.model(),
                    config == null ? 0 : config.parts().size());
        }
        if (stage != Stage.LIVE) return;

        if (fsm != null && partStage == null) {
            StateMachine<?> m = fsm.machine(level);
            if (m != null) {
                template.pull(element, m);
                fsm.tick(level, pos);
                template.push(element, m);
            }
        } else if (handle != null && level.isClientSide()) {
            boolean mounted = handle.mount();
            if (!mounted && now % 100 == 0) {
                ProxyDebug.log("model still unmounted: model={} instance={} "
                                + "(resource not published, or the loader rejected it)",
                        config == null ? null : config.model(),
                        identity == null ? null : identity.key());
            }
        }

        if (partStage != null) {
            tickParts(level, now);
        }

    }

    private boolean materialize(Level level, BlockPos pos) {
        boolean success = false;
        try {
            this.clientSide = level.isClientSide();
            this.identity = fixedIdentity != null ? fixedIdentity : resolveIdentity(level, pos);
            this.config = template.configure(element);
            if (config == null) {
                LOGGER.warn("[proxy] {} refused to configure ({})", element.getClass().getName(), identity);
                return false;
            }
            ProxyDebug.log("configure: identity={} side={} machine={} model={} modelName={} parts={}",
                    identity, sideName(level), config.machine(), config.model(), config.modelName(),
                    config.parts().size());
            if (config.isEmpty()) {
                LOGGER.warn("[proxy] {} produced an empty config (no machine, no model, no parts) at {}",
                        element.getClass().getName(), identity);
                return false;
            }
            Supplier<Transform> root = config.rootTransform() != null ? config.rootTransform() : fixedRoot != null ?
                    fixedRoot : () -> new Transform().translate(pos.getX() + 0.5f, pos.getY(), pos.getZ() + 0.5f);
            this.rootSupplier = root;

            if (config.machine() != null) {
                // Parts are bound separately below, so the machine gets no model of its own: it is a
                // pure parameter source for the parts (a model here would be a second binding of the
                // same geometry under a different instance key).
                this.fsm = new FsmAnimatedModel(owner, identity.discriminator(), identity.key(), root,
                        config.machine(), config.model(), config.modelName(), null, null);
                this.fsm.setVarProvider(template.varProvider(element));
            } else if (config.model() != null && level.isClientSide()) {
                this.handle = McModelHandle.of(config.model(), config.modelName(), identity.key(), null)
                        .setPose(root.get());
                boolean mounted = this.handle.mount();
                ProxyDebug.log("model bind: model={} instance={} mounted={}",
                        config.model(), identity.key(), mounted);
            }

            if (!config.parts().isEmpty()) {
                materializeParts(level);
            }
            Proxies.remember(this);
            success = true;
            return true;
        } catch (Throwable t) {
            LOGGER.error("[proxy] configure failed for {} ({})", element.getClass().getName(), identity, t);
            return false;
        } finally {
            if (!success) {
                cleanupResources(level);
            }
        }
    }

    /**
     * Binds every {@link ModelPart} as its own pipeline instance, keyed
     * {@code <identity>/part/<index>}.
     *
     * <p>The index (not the model location) is the key's identity: two parts may reference the same
     * model, and a stable key is what lets a resource reload re-bind instead of leaking a second GPU
     * instance. Parts are client-only — the server never draws, and mounting there would just hold
     * GPU resources for nothing.
     */
    private void materializeParts(Level level) {
        if (!level.isClientSide()) {
            return;
        }
        materializeParts(config == null ? List.of() : config.parts(),
                (spec, instanceLoc) -> McModelHandle.of(spec.model(), spec.modelName(), instanceLoc, null));
    }

    void materializePartsForTest(
            List<ModelPart> specs,
            RigidPartVisualStage.PartHandleFactory factory
    ) {
        boolean success = false;
        try {
            materializeParts(specs, factory);
            success = true;
        } finally {
            if (!success) {
                cleanupResources(lastLevel);
            }
        }
    }

    private void materializeParts(
            List<ModelPart> specs,
            RigidPartVisualStage.PartHandleFactory factory
    ) {
        ResourceLocation base = identity == null ? null : identity.key();
        ResourceLocation instanceBase = base != null
                ? base
                : ResourceLocation.fromNamespaceAndPath("kasuga_lib", "proxy/unresolved");
        RigidPartVisualStage stage = new RigidPartVisualStage(
                instanceBase, specs, this::machine, rootTransform(), factory);
        this.partStage = stage;
        RuntimeController<Void> controller = fsm == null ? null
                : new ProxyMachineController(
                        () -> fsm.machine(lastLevel),
                        machine -> template.pull(element, machine),
                        machine -> template.push(element, machine),
                        template.varProvider(element));
        this.modelGroupScope = new ModelGroupScope(stage, controller);
        stage.maintainVisuals();
    }

    /**
     * Per-tick part maintenance: retry binding parts whose model was not published yet, then let
     * the model group scope publish one shared tick to every part target.
     *
     * <p>Poses are not written here once a part is mounted: the driver samples
     * {@code root × local} and the angle on the render frame, so a moving host stays smooth and a
     * static part still follows it. Before the model is published there is nothing to drive, so the
     * part keeps the pose it was bound with.
     *
     * <p>Angles are absolute, not deltas, so a part stays correct after a re-bind or after the chunk
     * was unloaded for a while.
     */
    private void tickParts(Level level, long now) {
        if (!level.isClientSide()) {
            return;
        }
        tickClientParts(now);
    }

    void tickPartsForTest(long now) {
        tickClientParts(now);
    }

    private void tickClientParts(long ignoredNow) {
        RigidPartVisualStage stage = partStage;
        if (stage == null) {
            return;
        }
        if (modelGroupScope != null) {
            modelGroupScope.tick(1f / 20f, rootTransform());
        }
        stage.maintainVisuals();
    }

    private Transform rootTransform() {
        if (rootSupplier != null) {
            return rootSupplier.get();
        }
        if (fixedRoot != null) {
            return fixedRoot.get();
        }
        return new Transform();
    }

    /** Publishes a host-native root frame to the existing model group; never reads the root supplier. */
    public void publishVisualFrame(Transform root) {
        RigidPartVisualStage stage = partStage;
        if (stage != null) {
            stage.publishFrame(root);
        }
    }

    /** Pauses the visual subtree without advancing or rebuilding its logical owner. */
    public void suspendVisuals() {
        ModelGroupScope scope = modelGroupScope;
        if (scope != null) {
            scope.pause();
        }
    }

    /** Resumes the existing visual subtree; repeated calls are idempotent. */
    public void resumeVisuals() {
        ModelGroupScope scope = modelGroupScope;
        if (scope != null) {
            scope.resume();
        }
    }

    /** Pipeline instance key of part {@code index}: {@code <identity>/part/<index>}. */
    static ResourceLocation partInstanceKey(@Nullable ResourceLocation proxyKey, int index) {
        ResourceLocation base = proxyKey != null
                ? proxyKey
                : ResourceLocation.fromNamespaceAndPath("kasuga_lib", "proxy/unresolved");
        return RigidPartVisualStage.partInstanceKey(base, index);
    }

    private ProxyIdentity resolveIdentity(Level level, BlockPos pos) {
        if (element instanceof BlockEntity) return ProxyIdentity.ofBlock(level, pos);
        if (element instanceof Entity entity) return ProxyIdentity.ofEntity(entity);
        throw new IllegalStateException(element.getClass().getName()
                + " has no implicit identity; use Proxies.installExternal(...)");
    }

    public void release(@Nullable Level level) {
        if (stage == Stage.RELEASED) return;
        ProxyDebug.log("release: identity={} stage={} parts={}", identity, stage, partCount());
        this.stage = Stage.RELEASED;
        cleanupResources(level);
    }

    private void cleanupResources(@Nullable Level level) {
        Throwable failure = null;
        if (modelGroupScope != null) {
            try {
                modelGroupScope.close();
            } catch (Throwable cleanupFailure) {
                failure = CleanupFailures.accumulate(failure, cleanupFailure);
            }
            modelGroupScope = null;
        }
        if (partStage != null) {
            try {
                partStage.close();
            } catch (Throwable cleanupFailure) {
                failure = CleanupFailures.accumulate(failure, cleanupFailure);
            }
            partStage = null;
        }
        if (fsm != null) {
            try {
                fsm.onRemoved(level);
            } catch (Throwable cleanupFailure) {
                failure = CleanupFailures.accumulate(failure, cleanupFailure);
            }
            fsm = null;
        }
        if (handle != null) {
            try {
                handle.destroy();
            } catch (Throwable cleanupFailure) {
                failure = CleanupFailures.accumulate(failure, cleanupFailure);
            }
            handle = null;
        }
        try {
            detachTemplateOnce();
        } catch (Throwable cleanupFailure) {
            failure = CleanupFailures.accumulate(failure, cleanupFailure);
        }
        Proxies.forget(this);
        rootSupplier = null;
        if (failure != null) {
            LOGGER.error("[proxy] cleanup failed for {} ({})", element.getClass().getName(), identity, failure);
        }
    }

    private void detachTemplateOnce() {
        if (detached) {
            return;
        }
        detached = true;
        template.onDetach(element);
    }

    @Override
    public void close() {
        release(this.lastLevel);
    }

    // ------------------------------------------------------------------
    // diagnostics (read-only; used by /kasuga_proxy and tests)
    // ------------------------------------------------------------------

    /** Whether the proxy finished materializing and is ticking. */
    public boolean isLive() {
        return stage == Stage.LIVE;
    }

    /** Stage name for diagnostics: {@code NEW}, {@code LIVE}, {@code FAILED} or {@code RELEASED}. */
    public String stageName() {
        return stage.name();
    }

    /** Model this proxy bound, or {@code null} before materialize / for machine-only proxies. */
    public @Nullable ResourceLocation modelLocation() {
        return config == null ? null : config.model();
    }

    /** Whether the client-side model handle currently holds a bound instance. */
    public boolean modelMounted() {
        if (handle != null) {
            return handle.isMounted();
        }
        if (partStage != null) {
            return mountedPartCount() == partStage.partCount();
        }
        if (fsm != null) {
            return fsm.isModelBound();
        }
        return false;
    }

    /** Number of model parts this proxy declared; 0 for the single-model and machine-only shapes. */
    public int partCount() {
        return config == null ? 0 : config.parts().size();
    }

    /** Number of parts whose model is currently bound; 0 for the single-model shape. */
    public int mountedPartCount() {
        RigidPartVisualStage stage = partStage;
        return stage == null ? 0 : stage.mountedPartCount();
    }

    /** One-line description used by {@code /kasuga_proxy list}. */
    public String describe() {
        return (identity == null ? "<unresolved>" : identity.key().toString())
                + " side=" + (clientSide == null ? "?" : clientSide ? "client" : "server")
                + " element=" + elementName()
                + " stage=" + stage
                + " machine=" + (config == null ? null : config.machine())
                + " model=" + (config == null ? null : config.model())
                + (partCount() > 0 ? " parts=" + mountedPartCount() + "/" + partCount() : "")
                + " mounted=" + modelMounted()
                + " lastTick=" + (lastTickGameTime == Long.MIN_VALUE ? "never" : Long.toString(lastTickGameTime));
    }

    private String elementName() {
        return element.getClass().getSimpleName();
    }

    private static String sideName(Level level) {
        return level.isClientSide() ? "client" : "server";
    }
}
