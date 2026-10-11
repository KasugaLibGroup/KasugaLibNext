package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftRagdollConfigTest {
    @Test
    void parsesTopologySolverCollisionAndCustomLimits() {
        MinecraftRagdollConfig config = MinecraftRagdollConfig.fromJson(
                JsonParser.parseString("""
                        {
                          "include_secondary_bodies": true,
                          "simulation": {
                            "hertz": 144,
                            "substeps": 4,
                            "solver_iterations": 8,
                            "update_mode": "manual",
                            "max_fixed_steps_per_update": 7
                          },
                          "sleeping": {
                            "enabled": false,
                            "linear_speed": 0.02,
                            "angular_speed": 0.1,
                            "delay_seconds": 2.0
                          },
                          "collision": {"self_collision": false, "continuous": true},
                          "environment": {"refresh_interval_ticks": 2},
                          "bodies": [
                            {"rigid_body": 7, "role": "pelvis"},
                            {
                              "rigid_body": 0,
                              "parent": 7,
                              "role": "spine",
                              "rotation_min_degrees": [-10, -20, -30],
                              "rotation_max_degrees": [10, 20, 30]
                            },
                            {
                              "rigid_body": 9,
                              "parent": 7,
                              "role": "head",
                              "max_swing_degrees": 42,
                              "min_twist_degrees": -18,
                              "max_twist_degrees": 24,
                              "limit_stiffness": 0.7
                            }
                          ]
                        }
                        """).getAsJsonObject());

        assertEquals(144f, config.simulation().hertz());
        assertEquals(4, config.simulation().substeps());
        assertEquals(8, config.simulation().solverIterations());
        assertEquals(100f, config.simulation().maxLinearSpeed());
        assertEquals(50f, config.simulation().maxAngularSpeed());
        assertEquals(7, config.simulation().maxFixedStepsPerUpdate());
        assertEquals(MinecraftRagdollConfig.UpdateMode.MANUAL, config.updateMode());
        assertFalse(config.sleeping().enabled());
        assertEquals(0.02f, config.sleeping().linearSpeed());
        assertEquals(0.1f, config.sleeping().angularSpeed());
        assertEquals(2f, config.sleeping().delaySeconds());
        assertFalse(config.collision().selfCollision());
        assertTrue(config.collision().continuous());
        assertEquals(2, config.environment().refreshIntervalTicks());
        assertTrue(config.dragging().enabled());
        assertEquals(16f, config.dragging().maxDistance());
        assertEquals(3, config.profile().bodies().size());
        assertTrue(config.profile().includeSecondaryBodies());
        assertEquals((float) Math.toRadians(30),
                config.profile().bodies().getFirst().swingTwistLimit().maxSwing(), 1e-6f);
        SkeletonRagdoll.Registration spine = config.profile().bodies().get(1);
        assertEquals(null, spine.swingTwistLimit(),
                "legacy Euler limits remain an explicit compatibility path");
        assertEquals(7, spine.parentRigidBodyIndex());
        assertEquals((float) Math.toRadians(-20), spine.rotationMinimum().y, 1e-6f);
        assertEquals((float) Math.toRadians(30), spine.rotationMaximum().z, 1e-6f);
        SkeletonRagdoll.SwingTwistLimit head = config.profile().bodies().get(2).swingTwistLimit();
        assertEquals((float) Math.toRadians(42), head.maxSwing(), 1e-6f);
        assertEquals((float) Math.toRadians(-18), head.minTwist(), 1e-6f);
        assertEquals((float) Math.toRadians(24), head.maxTwist(), 1e-6f);
        assertEquals(0.7f, head.stiffness());
    }

    @Test
    void roleLimitsFromModelProfileApplyWithoutPerBodyDuplication() {
        MinecraftRagdollConfig config = MinecraftRagdollConfig.fromJson(
                JsonParser.parseString("""
                        {
                          "limits": {
                            "pelvis": {
                              "max_swing_degrees": 9,
                              "min_twist_degrees": -5,
                              "max_twist_degrees": 5,
                              "limit_stiffness": 0.84
                            },
                            "toe": {
                              "max_swing_degrees": 20,
                              "min_twist_degrees": -7,
                              "max_twist_degrees": 7
                            }
                          },
                          "bodies": [
                            {"rigid_body": 12, "role": "pelvis"},
                            {"rigid_body": 4, "parent": 12, "role": "toe"}
                          ]
                        }
                        """).getAsJsonObject());

        assertEquals((float)Math.toRadians(9),
                config.profile().bodies().getFirst().swingTwistLimit().maxSwing(), 1e-6f);
        assertEquals(0.84f, config.profile().bodies().getFirst().swingTwistLimit().stiffness());
        assertEquals(SkeletonRagdoll.BodyRole.TOE, config.profile().bodies().get(1).role());
        assertEquals((float)Math.toRadians(7),
                config.profile().bodies().get(1).swingTwistLimit().maxTwist(), 1e-6f);
    }
}
