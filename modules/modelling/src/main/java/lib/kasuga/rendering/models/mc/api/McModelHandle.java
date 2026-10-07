package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.mc.backend.schedule.RenderScheduleMode;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmAnimatedModel;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollRuntime;
import lib.kasuga.rendering.models.mc.dynamic.fsm.KasugaModelPipelines;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmMachineBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmPoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.RebindablePoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.AnchorModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3d;

import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 模型句柄 —— 上层<b>渲染端</b>操作一个已挂载 UML 模型的唯一入口。
 *
 * <p>分工约定：上层<b>逻辑端</b>操作状态机（FSM/PoseDriver，决定模型"做什么动作"），
 * 上层<b>渲染端</b>操作本句柄（决定模型"放在哪、是否可见、走哪条调度路径"）。
 * 两者作用于同一个 {@link ModelInstance} 的不同层面——状态机写骨骼局部变换，
 * 句柄写骨架根变换与渲染调度，互不冲突，共同构成模型的完整行为。</p>
 *
 * <p>生命周期语义与资源管线对齐：模型资源尚未发布时 {@link #mount()} 返回
 * false，宿主每 tick 重试即可；挂载前的姿态修改会暂存并在挂载成功后生效，
 * 因此句柄可以安全地早于资源加载创建。若实例由逻辑端先行创建
 * （如 {@code FsmAnimatedModel} 的自愈绑定），用 {@link #ofExisting(ResourceLocation, String, ResourceLocation)} 收编它。</p>
 */
public final class McModelHandle {

    /** Attempts to create/bind the instance; null while the resource is unpublished. */
    @FunctionalInterface
    interface Binder {
        @Nullable ModelInstance bind(@Nullable Transform rootPose);
    }

    private static final String BACKEND = "mc_backend";

    private ResourceLocation modelLoc;
    @Nullable private String modelName;
    private final ResourceLocation instanceLoc;

    private Binder binder;
    private Function<ResourceLocation, @Nullable ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> pipelineResolver;
    @Nullable private ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> boundPipeline;
    @Nullable private ResourceLocation boundKey;
    @Nullable private AutoCloseable publicationSubscription;
    @Nullable private RebindState pendingRebind;
    @Nullable private BiConsumer<ModelInstance, ModelInstance> onRebind;
    private RenderScheduleMode scheduleMode = RenderScheduleMode.ALWAYS;
    private boolean manualVisible = true;
    private float renderDistance;

    @Nullable
    private ModelInstance instance;
    @Nullable
    private Transform pendingPose;
    private float ambientLightEnhancement = ModelInstance.DEFAULT_AMBIENT_LIGHT_ENHANCEMENT;
    private boolean destroyed;
    private record AnchorCallback(ModelInstance owner, AnchorModule.Attachment adapter) {}
    private final Map<String, IdentityHashMap<BiConsumer<String, Transform>, AnchorCallback>> anchorCallbacks = new HashMap<>();
    @Nullable
    private FsmAnimatedModel syncedFsm;

    private McModelHandle(ResourceLocation modelLoc, @Nullable String modelName,
                          ResourceLocation instanceLoc,
                          Binder binder,
                          Function<ResourceLocation, @Nullable ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> pipelineResolver) {
        this.modelLoc = Objects.requireNonNull(modelLoc, "modelLoc");
        this.modelName = modelName;
        this.instanceLoc = Objects.requireNonNull(instanceLoc, "instanceLoc");
        this.binder = binder;
        this.pipelineResolver = pipelineResolver;
    }

    // ------------------------------------------------------------------
    // 工厂
    // ------------------------------------------------------------------

    /**
     * 创建指向全局内容管线的句柄（.mmd.zip / .glb / .obj / .geo.json / JE json）。
     * 句柄可先于资源发布创建；{@link #mount()} 未成功前其余操作安全空转或暂存。
     */
    public static McModelHandle of(ResourceLocation modelLoc, @Nullable String modelName,
                                   ResourceLocation instanceLoc, @Nullable Vec3 pos) {
        McModelHandle handle = new McModelHandle(modelLoc, modelName, instanceLoc,
                pose -> KasugaModelPipelines.createAndBind(modelLoc, instanceLoc, modelName, pose),
                McModelHandle::globalPipeline);
        if (pos != null) handle.setPos(pos);
        return handle;
    }

    /**
     * 收编一个已由其它组件（典型：逻辑端的 {@code FsmAnimatedModel}）创建并绑定
     * 的实例。渲染端不重复建实例，只在其上施加摆放/调度/锚点等渲染上下文。
     */
    public static McModelHandle ofExisting(ResourceLocation modelLoc, @Nullable String modelName,
                                           ResourceLocation instanceLoc) {
        return new McModelHandle(modelLoc, modelName, instanceLoc,
                pose -> {
                    ModelInstance adopted = KasugaModelPipelines.createAndBind(
                            modelLoc, instanceLoc, modelName, pose);
                    if (adopted != null && pose != null) {
                        adopted.getSkeletonInstance().transformRoot(pose.copy());
                    }
                    return adopted;
                },
                McModelHandle::globalPipeline);
    }

    private static ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> globalPipeline(ResourceLocation id) {
        return PipelineRegistry.isInitialized() ? PipelineRegistry.resolve(id) : null;
    }

    /** A resource recipe may be registered before its component models are published. */
    public static McModelHandle ofAssembly(ResourceLocation id, ResourceLocation instanceId, @Nullable Vec3 pos) {
        McModelHandle handle = new McModelHandle(id, null, instanceId,
                pose -> {
                    var service = PipelineRegistry.assemblies();
                    return service == null ? null : service.createAndBind(id, instanceId, pose);
                }, ignored -> {
                    var service = PipelineRegistry.assemblies();
                    return service == null ? null : service.pipeline();
                });
        if (pos != null) handle.setPos(pos);
        return handle;
    }

    /** 测试与自定义管线用的底层工厂：显式提供绑定策略。 */
    public static McModelHandle custom(ResourceLocation modelLoc, @Nullable String modelName,
                                       ResourceLocation instanceLoc,
                                       Binder binder,
                                       Function<ResourceLocation, @Nullable ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?>> pipelineResolver) {
        return new McModelHandle(modelLoc, modelName, instanceLoc, binder, pipelineResolver);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** Attempts binding; idempotent. Re-applies the latest buffered pose on success. */
    public boolean mount() {
        if (destroyed) return false;
        if (isMounted() && (boundPipeline == null || boundPipeline.isRendering(boundKey, instanceLoc, BACKEND))) return true;
        if (instance != null && !isMounted()) {
            if (pendingRebind == null) pendingRebind = RebindState.capture(instance);
            clearAnchorCallbacks();
            instance = null;
        }
        if (instance != null && pendingRebind == null) pendingRebind = RebindState.capture(instance);
        Transform root = pendingPose != null ? pendingPose : pendingRebind == null ? null : pendingRebind.root;
        ModelInstance bound = binder.bind(root);
        if (bound == null) return false;
        try { restore(bound, pendingRebind); }
        catch (RuntimeException | Error failure) {
            var pipeline = pipelineResolver.apply(modelLoc);
            var key = pipeline == null ? null : pipeline.modelKeyOf(bound);
            if (key != null) {
                try { pipeline.removeInstance(key, instanceLoc); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        if (instance != bound) clearAnchorCallbacks();
        instance = bound;
        bound.setAmbientLightEnhancement(ambientLightEnhancement);
        if (pendingPose != null && pendingRebind == null) {
            bound.getSkeletonInstance().transformRoot(pendingPose.copy());
        }
        pendingPose = null; pendingRebind = null;
        applyScheduling(bound);
        observePublication();
        return true;
    }

    public boolean isMounted() {
        return instance != null && !destroyed && (boundPipeline == null || boundKey == null
                || boundPipeline.getInstance(boundKey, instanceLoc) == instance);
    }

    /** Refresh custom drivers or physics here. Called before the old instance is retired on an explicit switch. */
    public McModelHandle onInstanceChanged(BiConsumer<ModelInstance, ModelInstance> callback) {
        onRebind = callback; return this;
    }

    public boolean switchAssembly(ResourceLocation id) {
        var service = PipelineRegistry.assemblies();
        return service != null && switchAssembly(service, id);
    }

    /** Prepare and mount the next outfit first. Missing/invalid resources leave the previous outfit intact. */
    public boolean switchAssembly(McModelAssemblies service, ResourceLocation id) {
        if (destroyed) return false;
        Objects.requireNonNull(service); Objects.requireNonNull(id);
        ModelInstance previous = instance;
        RebindState state = previous == null ? pendingRebind : RebindState.capture(previous);
        Transform root = pendingPose != null ? pendingPose : state == null ? null : state.root;
        ModelInstance next = service.createAndBind(id, instanceLoc, root);
        if (next == null) return false;
        if (next != previous) {
            try { restore(next, state); }
            catch (RuntimeException | Error failure) {
                try { service.pipeline().removeInstance(id, instanceLoc); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        var oldPipeline = boundPipeline; var oldKey = boundKey;
        stopObserving();
        modelLoc = id; modelName = null;
        binder = pose -> service.createAndBind(id, instanceLoc, pose);
        pipelineResolver = ignored -> service.pipeline();
        if (instance != next) clearAnchorCallbacks();
        instance = next; pendingPose = null; pendingRebind = null;
        next.setAmbientLightEnhancement(ambientLightEnhancement); applyScheduling(next); observePublication();
        if (previous != null && previous != next) {
            MinecraftRagdollRuntime.unregister(previous);
            if (oldPipeline != null && oldKey != null) oldPipeline.removeInstance(oldKey, instanceLoc);
            else previous.close();
        }
        return true;
    }

    private void restore(ModelInstance fresh, @Nullable RebindState state) {
        if (state != null) {
            if (state.floating) {
                if (fresh.getSkeletonInstance().isFloatingOriginEnabled()) fresh.getSkeletonInstance().rebaseFloatingOrigin(state.origin);
                else fresh.getSkeletonInstance().enableFloatingOrigin(state.origin);
                fresh.getSkeletonInstance().transformRoot((pendingPose == null ? state.root : pendingPose).copy());
            } else fresh.getSkeletonInstance().transformRootWorld((pendingPose == null ? state.root : pendingPose).copy());
            scheduleMode = state.mode; manualVisible = state.visible; renderDistance = state.distance;
            if (state.previous != fresh) {
                state.previous.getSkeletonInstance().copyPoseInputsTo(fresh.getSkeletonInstance());
                state.previous.getMorph().copyInputsTo(fresh.getMorph());
                if (onRebind != null) onRebind.accept(state.previous, fresh);
                if (fresh.getPoseDriver() == null && state.driver instanceof RebindablePoseDriver driver) {
                    driver.rebind(fresh); fresh.setPoseDriver(driver);
                }
            }
        }
    }

    private void observePublication() {
        stopObserving();
        boundPipeline = pipelineResolver.apply(modelLoc);
        if (boundPipeline == null || instance == null) return;
        boundKey = boundPipeline.modelKeyOf(instance);
        if (boundKey == null) { boundPipeline = null; return; }
        ResourceLocation observedKey = boundKey;
        publicationSubscription = boundPipeline.onModelChanged(change -> {
            if (observedKey.equals(change.key()) && instance != null && change.previous() == instance.getModel()) {
                MinecraftRagdollRuntime.unregister(instance);
                clearAnchorCallbacks();
                pendingRebind = RebindState.capture(instance); instance = null; pendingPose = null;
                stopObserving();
            }
        });
    }

    private void stopObserving() {
        if (publicationSubscription != null) {
            try { publicationSubscription.close(); }
            catch (Exception failure) { throw new IllegalStateException("model publication subscription cleanup failed", failure); }
        }
        publicationSubscription = null; boundPipeline = null; boundKey = null;
    }

    private void applyScheduling(ModelInstance target) {
        if (scheduleMode == RenderScheduleMode.MANUAL) ModelRenderScheduler.setVisible(target, manualVisible);
        else ModelRenderScheduler.setMode(target, scheduleMode);
        ModelRenderScheduler.setMaxRenderDistance(target, renderDistance);
    }

    private record RebindState(ModelInstance previous, Transform root, Vector3d origin, boolean floating,
                               PoseDriver driver, RenderScheduleMode mode, boolean visible, float distance) {
        static RebindState capture(ModelInstance instance) {
            var skeleton = instance.getSkeletonInstance();
            return new RebindState(instance, skeleton.getTransform().copy(), skeleton.getWorldOrigin(), skeleton.isFloatingOriginEnabled(),
                    instance.getPoseDriver(), ModelRenderScheduler.mode(instance), ModelRenderScheduler.shouldRender(instance),
                    ModelRenderScheduler.maxRenderDistance(instance));
        }
    }

    /** Detaches from the render backend; scheduling state is dropped. Returns whether it was mounted. */
    public boolean unmount() {
        if (destroyed || instance == null) return false;
        pendingRebind = RebindState.capture(instance);
        clearAnchorCallbacks();
        ModelRenderScheduler.detach(instance);
        var pipeline = boundPipeline != null ? boundPipeline : pipelineResolver.apply(modelLoc);
        if (pipeline != null) {
            pipeline.stopRendering(boundKey != null ? boundKey : modelLoc, instanceLoc, BACKEND);
        }
        stopObserving();
        instance = null;
        return true;
    }

    /**
     * Unmounts and removes the instance entirely; the handle must be discarded afterwards.
     * A synced state machine hosted here is detached too — call
     * {@code fsm.onRemoved(level)} yourself first when sync keys need cleanup.
     */
    public void destroy() {
        if (destroyed) return;
        clearAnchorCallbacks();
        if (instance != null) MinecraftRagdollRuntime.unregister(instance);
        else if (pendingRebind != null) MinecraftRagdollRuntime.unregister(pendingRebind.previous);
        var pipeline = boundPipeline != null ? boundPipeline : pipelineResolver.apply(modelLoc);
        ResourceLocation key = boundKey != null ? boundKey : pipeline != null && pendingRebind != null
                ? pipeline.modelKeyOf(pendingRebind.previous) : modelLoc;
        unmount();
        destroyed = true;
        if (syncedFsm != null) {
            syncedFsm.model(null, null, null);
            syncedFsm = null;
        }
        if (pipeline != null) {
            pipeline.removeInstance(key, instanceLoc);
        }
        instance = null;
        pendingPose = null;
        pendingRebind = null; stopObserving();
    }

    // ------------------------------------------------------------------
    // 姿态（vanilla 风格动词）
    // ------------------------------------------------------------------

    /** World-space root position; buffered while unmounted and applied on mount. */
    public McModelHandle setPos(Vec3 pos) {
        Objects.requireNonNull(pos, "pos");
        if (instance != null) {
            instance.getSkeletonInstance().setWorldRootPosition(
                    new org.joml.Vector3d(pos.x, pos.y, pos.z));
            if (pendingPose != null) {
                pendingPose.setPosition(instance.getSkeletonInstance().getTransform().getPosition());
                flushPose();
            }
        } else {
            if (pendingRebind != null && pendingRebind.floating) {
                pendingRebind.origin.set(pos.x, pos.y, pos.z); ensurePose().setPosition(new Vector3f());
            } else ensurePose().setPosition(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
        }
        return this;
    }

    public McModelHandle setPos(double x, double y, double z) {
        return setPos(new Vec3(x, y, z));
    }

    @Nullable
    public Vec3 getPos() {
        if (instance != null) {
            var p = instance.getSkeletonInstance().getWorldRootPosition();
            return new Vec3(p.x, p.y, p.z);
        }
        if (pendingRebind != null && pendingRebind.floating) {
            Vector3f local = (pendingPose == null ? pendingRebind.root : pendingPose).getPosition();
            return new Vec3(pendingRebind.origin.x + local.x, pendingRebind.origin.y + local.y, pendingRebind.origin.z + local.z);
        }
        if (pendingPose == null && pendingRebind != null) {
            var p = pendingRebind.root.getPosition(); return new Vec3(p.x, p.y, p.z);
        }
        if (pendingPose == null) return null;
        Vector3f p = pendingPose.getPosition();
        return new Vec3(p.x, p.y, p.z);
    }

    /** Moves relative to the current position (world-space translation). */
    public McModelHandle move(Vec3 delta) {
        Objects.requireNonNull(delta, "delta");
        if (instance != null) {
            Vec3 position = getPos();
            setPos(position.x + delta.x, position.y + delta.y, position.z + delta.z);
        } else {
            ensurePose().translateWorld(new Vector3f((float) delta.x, (float) delta.y, (float) delta.z));
        }
        return this;
    }

    /** Sets the root rotation as XYZ Euler degrees (vanilla convention). */
    public McModelHandle setRotationDegrees(float x, float y, float z) {
        Transform pose = ensurePose();
        Vector3f position = pose.getPosition();
        Vector3f scale = pose.transform().getScale(new Vector3f());
        pose.set(new org.joml.Matrix4f().translationRotateScale(position,
                new Quaternionf().rotationXYZ(
                        (float) Math.toRadians(x), (float) Math.toRadians(y), (float) Math.toRadians(z)),
                scale));
        flushPose();
        return this;
    }

    /** Rotates by additional XYZ Euler degrees. */
    public McModelHandle rotateByDegrees(float x, float y, float z) {
        ensurePose().rotate(x, y, z, true);
        flushPose();
        return this;
    }

    /** Resets root to identity. */
    public McModelHandle resetPose() {
        ensurePose().setIdentity();
        flushPose();
        return this;
    }

    /** Advanced: direct mutable access to the root transform (flushed on every prior call). */
    public Transform pose() {
        return ensurePose();
    }

    private Transform ensurePose() {
        if (pendingPose == null) {
            pendingPose = pendingRebind == null ? new Transform() : pendingRebind.root;
            if (instance != null) {
                // Mounted pose edits operate in the skeleton's origin-local
                // coordinate system so rotation/scale changes cannot quantize
                // the separate double-precision world anchor.
                pendingPose.set(instance.getSkeletonInstance().getTransform());
            }
        }
        return pendingPose;
    }

    private void flushPose() {
        if (instance != null && pendingPose != null) {
            instance.getSkeletonInstance().transformRoot(pendingPose.copy());
            pendingPose = null;
        }
    }

    @Nullable
    private Transform currentPose() {
        if (instance != null) return instance.getSkeletonInstance().getWorldTransform();
        return pendingPose;
    }

    // ------------------------------------------------------------------
    // 渲染调度（对接原版渲染器机制）
    // ------------------------------------------------------------------

    /** Vanilla owns visibility: an {@code EntityRenderer}/{@code BER} adapter marks each frame. */
    public McModelHandle scheduleVanillaRenderer() {
        scheduleMode = RenderScheduleMode.VANILLA_RENDERER;
        if (instance != null) ModelRenderScheduler.setMode(instance, RenderScheduleMode.VANILLA_RENDERER);
        return this;
    }

    /** Legacy global-pipeline behavior: draw every frame (frustum/distance gates still apply). */
    public McModelHandle scheduleAlways() {
        scheduleMode = RenderScheduleMode.ALWAYS;
        if (instance != null) ModelRenderScheduler.setMode(instance, RenderScheduleMode.ALWAYS);
        return this;
    }

    public McModelHandle show() {
        scheduleMode = RenderScheduleMode.MANUAL; manualVisible = true;
        if (instance != null) ModelRenderScheduler.setVisible(instance, true);
        return this;
    }

    public McModelHandle hide() {
        scheduleMode = RenderScheduleMode.MANUAL; manualVisible = false;
        if (instance != null) ModelRenderScheduler.setVisible(instance, false);
        return this;
    }

    /** Whether the current schedule allows rendering this frame (mounted instances only). */
    public boolean shown() {
        return instance != null && ModelRenderScheduler.shouldRender(instance);
    }

    /**
     * Additional per-model ambient-light multiplier for the vanilla shader pipeline.
     * {@code 1} disables the enhancement; Iris shader packs always receive {@code 1}.
     */
    public McModelHandle setAmbientLightEnhancement(float enhancement) {
        if (!Float.isFinite(enhancement)) {
            throw new IllegalArgumentException("ambient light enhancement must be finite");
        }
        ambientLightEnhancement = Math.clamp(enhancement, 0f, 10000f);
        if (instance != null) instance.setAmbientLightEnhancement(ambientLightEnhancement);
        return this;
    }

    public McModelHandle disableAmbientLightEnhancement() {
        return setAmbientLightEnhancement(1f);
    }

    public float ambientLightEnhancement() {
        return instance != null ? instance.getAmbientLightEnhancement() : ambientLightEnhancement;
    }

    /** Per-instance view-distance cap in blocks; 0 disables distance culling. */
    public McModelHandle maxRenderDistance(float blocks) {
        renderDistance = blocks;
        if (instance != null) {
            ModelRenderScheduler.setMaxRenderDistance(instance, blocks);
        }
        return this;
    }

    /**
     * Called from inside a vanilla renderer's {@code render()} — proof that
     * vanilla passed its own culling this frame.
     */
    public void markRenderedThisFrame() {
        if (instance != null) {
            ModelRenderScheduler.markRenderedThisFrame(instance);
        }
    }

    // ------------------------------------------------------------------
    // 状态机接入（逻辑端）
    // ------------------------------------------------------------------

    /**
     * 挂载一个<b>本地</b>数据驱动状态机：从共享定义桶按 id 构建，装配
     * {@link FsmPoseDriver} 到本实例。无服务器同步——纯客户端表现或纯逻辑机用。
     * 定义未就绪时返回 null（下 tick 重试）。
     *
     * <p>逻辑端每 game tick 调用返回绑定的 {@link LocalFsmBinding#tick()}。</p>
     */
    @Nullable
    public LocalFsmBinding attachLocalStateMachine(Id definitionId) {
        requireMounted();
        var definition = FsmMachineBuilder.findDefinition(definitionId);
        if (definition == null) return null;
        var machine = FsmMachineBuilder
                .build(this, definition, null);
        if (machine == null) return null;
        return attachLocalStateMachine(machine);
    }

    /** 程序化状态机版本：直接接管一个已构建的机器。 */
    public LocalFsmBinding attachLocalStateMachine(StateMachine<?> machine) {
        requireMounted();
        Objects.requireNonNull(machine, "machine");
        FsmPoseDriver driver = new FsmPoseDriver(machine, instance);
        setAnimationDriver(driver);
        return new LocalFsmBinding(this, machine, driver);
    }

    /**
     * 带服务器权威同步的完整宿主：内部托管一个 {@link FsmAnimatedModel}
     * （服务端逻辑机 + 推送；客户端傀儡机 + 驱动），其实例自动收编进本句柄。
     * 句柄必须以 {@link #ofFsm} 创建（instanceLoc 需与 FSM 的派生规则一致）。
     *
     * <p>宿主在 BE/Entity 的 tick 与生命周期里转发：
     * {@code fsm.tick(level, pos)} / {@code fsm.onRemoved(level)} 等。</p>
     */
    public synchronized FsmAnimatedModel attachSyncedStateMachine(
            Object logicOwner, long ownerDiscriminator,
            @Nullable Id stateMachineId) {
        ResourceLocation expected = fsmInstanceLoc(modelLoc, ownerDiscriminator);
        if (!expected.equals(instanceLoc)) {
            throw new IllegalStateException(
                    "attachSyncedStateMachine requires the handle to be created with ofFsm(...): instanceLoc '"
                            + instanceLoc + "' != '" + expected + "'");
        }
        if (syncedFsm != null) {
            throw new IllegalStateException("a synced state machine is already attached to this handle");
        }
        FsmAnimatedModel fsm = new FsmAnimatedModel(logicOwner, ownerDiscriminator,
                this::poseCopyOrNull,
                stateMachineId, modelLoc, modelName,
                null,
                this::adoptFromFsm);
        this.syncedFsm = fsm;
        // 已挂载时无需动作——FSM 的 ensureClientModel 会通过 getInstance 找到同一实例。
        // 未挂载时由 FSM 自行创建并在绑定回调里收编进句柄。
        return fsm;
    }

    /** The internally-hosted synced state machine, if any. */
    @Nullable
    public FsmAnimatedModel syncedStateMachine() {
        return syncedFsm;
    }

    /** Instance identifier used by the FSM integration: {@code <namespace>:fsm_<discriminator>}. */
    public static ResourceLocation fsmInstanceLoc(ResourceLocation modelLoc, long ownerDiscriminator) {
        return ResourceLocation.fromNamespaceAndPath(modelLoc.getNamespace(), "fsm_" + ownerDiscriminator);
    }

    /** Factory aligned with {@link #attachSyncedStateMachine}'s identity derivation. */
    public static McModelHandle ofFsm(ResourceLocation modelLoc, @Nullable String modelName,
                                      long ownerDiscriminator, @Nullable Vec3 pos) {
        return of(modelLoc, modelName, fsmInstanceLoc(modelLoc, ownerDiscriminator), pos);
    }

    /** Test seam: {@link #ofFsm} with a controllable binder (same identity derivation). */
    static McModelHandle ofFsmWithBinder(ResourceLocation modelLoc, @Nullable String modelName,
                                         long ownerDiscriminator, Binder binder) {
        return new McModelHandle(modelLoc, modelName,
                fsmInstanceLoc(modelLoc, ownerDiscriminator), binder, PipelineRegistry::resolve);
    }

    private synchronized void adoptFromFsm(ModelInstance bound) {
        if (destroyed || instance == bound) return;
        clearAnchorCallbacks();
        instance = bound;
        bound.setAmbientLightEnhancement(ambientLightEnhancement);
        pendingPose = null; // FSM 在创建实例时已应用 rootTransform supplier 的值
    }

    /** Test seam: simulates the wrapper's client-bind callback firing. */
    synchronized void markExternalBind(ModelInstance bound) {
        adoptFromFsm(bound);
    }

    @Nullable
    private Transform poseCopyOrNull() {
        Transform current = currentPose();
        return current == null ? null : current.copy();
    }

    // ------------------------------------------------------------------
    // 能力面
    // ------------------------------------------------------------------

    /**
     * 逻辑端接入口：为该模型装配动画驱动（典型是状态机派生的
     * {@code FsmPoseDriver}）。逻辑端由此只面向状态机编程。
     */
    public McModelHandle setAnimationDriver(@Nullable PoseDriver driver) {
        requireMounted();
        instance.setPoseDriver(driver);
        return this;
    }

    /** Attaches a display sub-object to a skeleton anchor (e.g. a held item). */
    public boolean attachToAnchor(String anchorName, BiConsumer<String, Transform> receiver) {
        Objects.requireNonNull(anchorName); Objects.requireNonNull(receiver);
        if (instance == null) return false;
        var receivers = anchorCallbacks.computeIfAbsent(anchorName, ignored -> new IdentityHashMap<>());
        if (receivers.containsKey(receiver)) return true;
        AnchorModule.Attachment adapter = transform -> receiver.accept(anchorName, transform);
        if (!instance.attachToAnchor(anchorName, adapter)) return false;
        receivers.put(receiver, new AnchorCallback(instance, adapter));
        return true;
    }

    public boolean detachFromAnchor(String anchorName, BiConsumer<String, Transform> receiver) {
        var receivers = anchorCallbacks.get(anchorName);
        if (receivers == null) return false;
        var callback = receivers.remove(receiver);
        if (receivers.isEmpty()) anchorCallbacks.remove(anchorName);
        return callback != null && callback.owner.detachFromAnchor(anchorName, callback.adapter);
    }

    private void clearAnchorCallbacks() {
        for (var entry : anchorCallbacks.entrySet()) for (var callback : entry.getValue().values())
            callback.owner.detachFromAnchor(entry.getKey(), callback.adapter);
        anchorCallbacks.clear();
    }

    /**
     * Enables PMX/glTF ragdoll physics with automatic render-frame stepping,
     * or returns {@code null} when Box3D is unavailable.
     */
    @Nullable
    public SkeletonRagdoll enablePhysics(MinecraftRagdollConfig.UpdateMode updateMode) {
        requireMounted();
        SkeletonRagdoll ragdoll = instance.enablePhysics();
        if (ragdoll == null) return null;
        MinecraftRagdollRuntime.register(instance, updateMode);
        return ragdoll;
    }

    public void disablePhysics() {
        if (instance == null) return;
        MinecraftRagdollRuntime.unregister(instance);
        instance.disablePhysics();
    }

    /** Escape hatch: full tick-loop module access (pre-IK / post-IK / post-physics mounting). */
    public lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop tickLoop() {
        requireMounted();
        return instance.getTickLoop();
    }

    /** Escape hatch: the underlying instance for APIs not covered by the handle. */
    @Nullable
    public ModelInstance instance() {
        return instance;
    }

    public ResourceLocation modelLoc() { return modelLoc; }
    @Nullable public String modelName() { return modelName; }
    public ResourceLocation instanceLoc() { return instanceLoc; }

    private void requireMounted() {
        if (!isMounted()) {
            throw new IllegalStateException("model '" + modelLoc + "' (instance '" + instanceLoc + "') is not mounted yet");
        }
    }

    /**
     * 逻辑端持有的本地状态机绑定：机器 + 已装配的驱动。逻辑端只面向
     * {@link #machine()} 编程（触发变量、查询状态）；每 game tick 调
     * {@link #tick()} 推进并发布姿态目标，渲染线程的插值采样由管线完成。
     */
    public static final class LocalFsmBinding {
        private final McModelHandle handle;
        private final StateMachine<?> machine;
        private final FsmPoseDriver driver;

        private LocalFsmBinding(McModelHandle handle,
                                StateMachine<?> machine,
                                FsmPoseDriver driver) {
            this.handle = handle;
            this.machine = machine;
            this.driver = driver;
        }

        public StateMachine<?> machine() {
            return machine;
        }

        public FsmPoseDriver driver() {
            return driver;
        }

        /** 逻辑端 game-tick 入口：推进机器 + 发布姿态目标。 */
        public void tick() {
            tick(1f / 20f);
        }

        public void tick(float dtSeconds) {
            driver.tick(dtSeconds);
        }

        /** 解除绑定：卸下驱动，机器状态保留。 */
        public void dispose() {
            handle.setAnimationDriver(null);
        }
    }
}
