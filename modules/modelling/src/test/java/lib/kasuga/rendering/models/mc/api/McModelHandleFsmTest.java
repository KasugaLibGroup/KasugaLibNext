package lib.kasuga.rendering.models.mc.api;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmAnimatedModel;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmPoseDriver;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.mc.backend.schedule.ModelRenderScheduler;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McModelHandleFsmTest {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("test", "model");
    private static final ResourceLocation INSTANCE =
            ResourceLocation.fromNamespaceAndPath("test", "instance");
    private static final Id DOOR_FSM = Id.fromNamespaceAndPath("test", "door");
    private static final StateVar<Boolean> OPEN = StateVar.of(
            Id.fromNamespaceAndPath("test", "open"), Boolean.class, Codec.BOOL, false);

    private static StateMachine<Object> simpleMachine() {
        return StateMachine.builder(new Object())
                .layer("main", layer -> {
                    State<Object> closed = layer.state("closed");
                    State<Object> opened = layer.state("opened");
                    layer.initial(closed);
                    layer.transition("open", closed, opened).when(ctx -> ctx.get(OPEN));
                })
                .build();
    }

    //region 本地状态机

    @Test
    void localStateMachineDrivesTheMountedInstance() {
        ModelInstance instance = fixture(null);
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);
        assertTrue(handle.mount());

        StateMachine<Object> machine = simpleMachine();
        McModelHandle.LocalFsmBinding binding = handle.attachLocalStateMachine(machine);

        assertTrue(handle.instance().getPoseDriver() instanceof FsmPoseDriver,
                "the driver must be plugged into the mounted instance");
        assertSame(machine, binding.machine());
        assertSame(handle.instance(), binding.driver().model());

        // 逻辑端 tick 推进机器（触发转移）并发布姿态目标；渲染采样由管线完成。
        machine.mutableVars().set(OPEN, true);
        int versionBefore = machine.version();
        binding.tick();
        assertTrue(binding.driver().machine().version() >= versionBefore,
                "the logic tick must advance the machine");

        binding.dispose();
        assertNull(handle.instance().getPoseDriver());
        ModelRenderScheduler.detach(instance);
    }

    @Test
    void localStateMachineRequiresAMountedHandle() {
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> null, loc -> null);
        assertFalse(handle.isMounted());
        assertThrows(IllegalStateException.class,
                () -> handle.attachLocalStateMachine(simpleMachine()));
    }

    @Test
    void vanillaSchedulingStillGatesFsmDrivenModels() {
        ModelInstance instance = fixture(null);
        McModelHandle handle = McModelHandle.custom(MODEL, null, INSTANCE,
                pose -> instance, loc -> null);
        assertTrue(handle.mount());
        handle.attachLocalStateMachine(simpleMachine());
        handle.scheduleVanillaRenderer();

        assertFalse(handle.shown(), "culled by the vanilla dispatcher this frame");
        handle.markRenderedThisFrame();
        assertTrue(handle.shown(), "the host renderer ran — the FSM pose renders too");
        ModelRenderScheduler.flipFrame();
        ModelRenderScheduler.flipFrame();
        assertFalse(handle.shown());
    }

    //endregion

    //region 同步状态机宿主

    @Test
    void syncedStateMachineAdoptsTheBoundInstance() {
        AtomicReference<ModelInstance> bound = new AtomicReference<>();
        McModelHandle handle = McModelHandle.ofFsmWithBinder(MODEL, null, 42L,
                pose -> {
                    ModelInstance fresh = fixture(pose);
                    bound.set(fresh);
                    return fresh;
                });

        FsmAnimatedModel fsm = handle.attachSyncedStateMachine(new Object(), 42L, DOOR_FSM);
        assertNotNull(fsm);
        assertSame(fsm, handle.syncedStateMachine());
        assertEquals(McModelHandle.fsmInstanceLoc(MODEL, 42L), handle.instanceLoc(),
                "ofFsm must derive the same identity the FSM wrapper uses");

        // Simulate the wrapper's client bind callback firing (production: ensureClientModel).
        handle.markExternalBind(bound.get());
        assertSame(bound.get(), handle.instance(),
                "the handle must adopt the instance created by the FSM wrapper");

        // Adoption must not clobber the FSM-applied root pose.
        assertThrows(IllegalStateException.class,
                () -> handle.attachSyncedStateMachine(new Object(), 42L, DOOR_FSM),
                "a second synced machine on one handle is a configuration error");
    }

    @Test
    void syncedStateMachineRejectsMismatchedHandleIdentity() {
        McModelHandle handle = McModelHandle.custom(
                ResourceLocation.fromNamespaceAndPath("test", "other"),
                null, INSTANCE, pose -> null, loc -> null);
        assertThrows(IllegalStateException.class,
                () -> handle.attachSyncedStateMachine(new Object(), 7L, null));
    }

    //endregion

    private static ModelInstance fixture(Transform rootPose) {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root,
                new Anchor[0], null, new Transform());
        Model model = new Model(new Vertex[0], new Mesh[0], new Bone[]{root},
                skeleton, new MaterialSet(List.of(), List.of()), MeshMode.TRIANGLES, null, null);
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        if (rootPose != null) instance.getSkeletonInstance().transformRoot(rootPose.copy());
        return instance;
    }
}
