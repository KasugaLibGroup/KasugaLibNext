package lib.kasuga.rendering.models.uml.framework;

import lib.kasuga.rendering.models.uml.framework.schedule.DefaultRenderScheduler;
import lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduleMode;
import lib.kasuga.rendering.models.uml.framework.schedule.RenderScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RenderSchedulerContractTest {
    @Test
    void viewVisibilityDoesNotLeakWhilePolicyAndDistanceSurvive() {
        RenderScheduler<Object> scheduler = new DefaultRenderScheduler<>();
        Object a = new Object(), b = new Object();
        scheduler.setMode(a, RenderScheduleMode.HOST_RENDERER);
        scheduler.setMode(b, RenderScheduleMode.HOST_RENDERER);
        scheduler.setMaxRenderDistance(a, 10);
        scheduler.markRenderedThisFrame(a);
        scheduler.flipFrame();
        assertTrue(scheduler.shouldRender(a));
        scheduler.clearFrameMarks();
        scheduler.markRenderedThisFrame(b);
        scheduler.flipFrame();
        assertFalse(scheduler.shouldRender(a));
        assertTrue(scheduler.shouldRender(b));
        assertEquals(10, scheduler.maxRenderDistance(a));
        assertEquals(RenderScheduleMode.HOST_RENDERER, scheduler.mode(a));
    }
    @Test
    void switchingAuthorityPreservesIndependentDistanceGate() {
        RenderScheduler<Object> scheduler = new DefaultRenderScheduler<>();
        Object instance = new Object();
        scheduler.setMaxRenderDistance(instance, 10);
        scheduler.setVisible(instance, false);
        scheduler.setMode(instance, RenderScheduleMode.ALWAYS);
        assertTrue(scheduler.shouldRender(instance));
        assertEquals(10, scheduler.maxRenderDistance(instance));
        assertFalse(scheduler.withinRenderDistance(instance, 101));
        scheduler.setMode(instance, RenderScheduleMode.HOST_RENDERER);
        assertFalse(scheduler.shouldRender(instance));
        scheduler.markRenderedThisFrame(instance);
        scheduler.flipFrame();
        assertTrue(scheduler.shouldRender(instance));
        assertFalse(scheduler.withinRenderDistance(instance, 101));
        scheduler.flipFrame();
        assertFalse(scheduler.shouldRender(instance));
    }

    @Test
    void equalInstancesAndSeparateSchedulersDoNotShareState() {
        RenderScheduler<String> first = new DefaultRenderScheduler<>();
        RenderScheduler<String> second = new DefaultRenderScheduler<>();
        String a = new String("same"), b = new String("same");
        first.setVisible(a, false);
        assertFalse(first.shouldRender(a));
        assertTrue(first.shouldRender(b));
        assertTrue(second.shouldRender(a));
        first.detach(a);
        assertTrue(first.shouldRender(a));
    }

    @Test
    void rejectsUndefinedPolicyInputsWithoutChangingState() {
        RenderScheduler<Object> scheduler = new DefaultRenderScheduler<>();
        Object instance = new Object();
        scheduler.setMaxRenderDistance(instance, 4);
        assertThrows(NullPointerException.class, () -> scheduler.setMode(instance, null));
        assertThrows(NullPointerException.class, () -> scheduler.setMode(null, RenderScheduleMode.ALWAYS));
        assertThrows(IllegalArgumentException.class, () -> scheduler.setMaxRenderDistance(instance, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> scheduler.setMaxRenderDistance(instance, Float.POSITIVE_INFINITY));
        assertEquals(4, scheduler.maxRenderDistance(instance));
    }
}
