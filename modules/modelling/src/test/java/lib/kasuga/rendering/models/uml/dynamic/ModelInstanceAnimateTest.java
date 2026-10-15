package lib.kasuga.rendering.models.uml.dynamic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ModelInstance#animate(float)} forwards to the attached {@link PoseDriver}; {@link ModelInstance#update()}
 * never does. Pins the R1 threading contract: animation advances on the tick thread via {@code animate()},
 * while the render-thread {@code update()} path stays purely a GPU flush.
 */
class ModelInstanceAnimateTest {

    private static final class CountingDriver implements PoseDriver {
        int calls = 0;
        float lastDt = -1f;

        @Override
        public void tick(float dt) {
            calls++;
            lastDt = dt;
        }
    }

    @Test
    void noDriverByDefaultAndAnimateIsNoOp() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        assertNull(instance.getPoseDriver(), "an instance has no pose driver until the host attaches one");
        instance.animate(0.05f); // must not throw
    }

    @Test
    void animateForwardsDtToDriver() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        CountingDriver driver = new CountingDriver();
        instance.setPoseDriver(driver);

        instance.animate(0.05f);

        assertEquals(1, driver.calls, "animate(dt) must forward exactly one tick to the driver");
        assertEquals(0.05f, driver.lastDt, 1e-6f);
    }

    @Test
    void updateDoesNotDriveAnimation() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        CountingDriver driver = new CountingDriver();
        instance.setPoseDriver(driver);

        instance.update(); // the render-thread path — must never advance the driver

        assertEquals(0, driver.calls, "update() must not call the pose driver (R1 threading contract)");
    }
}
