package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ManagedAnimationScope;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmPoseEvaluator;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FsmAnimatedModelPoseBindingTest {

    @Test
    void rebindMovesTheScopeRenderPortToAFreshInstanceWithoutRebuildingTheMachineEvaluator()
            throws Exception {
        ModelInstance previous = ModelInstanceFixture.minimal();
        ModelInstance fresh = ModelInstanceFixture.minimal();
        StateMachine<Object> machine = simpleMachine();
        FsmAnimatedModel animated = new FsmAnimatedModel(
                new Object(), 1L, Transform::new, null, null, null, null, null);
        setField(animated, "machine", machine);
        setField(animated, "modelInstance", previous);

        attachDriver(animated);
        FsmPoseEvaluator evaluator = (FsmPoseEvaluator) getField(animated, "poseEvaluator");
        ManagedAnimationScope scope = (ManagedAnimationScope) getField(animated, "animationScope");
        assertNotNull(previous.getAnimationRenderPort());
        assertNull(previous.getPoseDriver());
        setField(animated, "modelInstance", fresh);

        attachDriver(animated);

        assertNull(previous.getAnimationRenderPort());
        assertSame(scope.renderPort(), fresh.getAnimationRenderPort());
        assertSame(evaluator, getField(animated, "poseEvaluator"));
        assertSame(machine, evaluator.machine());
    }

    private static void attachDriver(FsmAnimatedModel animated) throws Exception {
        Method method = FsmAnimatedModel.class.getDeclaredMethod("attachDriverIfReady");
        method.setAccessible(true);
        method.invoke(animated);
    }

    private static void setField(FsmAnimatedModel animated, String name, Object value) throws Exception {
        Field field = FsmAnimatedModel.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(animated, value);
    }

    private static Object getField(FsmAnimatedModel animated, String name) throws Exception {
        Field field = FsmAnimatedModel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(animated);
    }

    private static StateMachine<Object> simpleMachine() {
        StateVar<Boolean> open = StateVar.of(
                Id.fromNamespaceAndPath("test", "open"), Boolean.class, Codec.BOOL, false);
        return StateMachine.builder(new Object())
                .layer("main", layer -> {
                    State<Object> closed = layer.state("closed");
                    State<Object> opened = layer.state("opened");
                    layer.initial(closed);
                    layer.transition("open", closed, opened).when(context -> context.get(open));
                })
                .build();
    }
}
