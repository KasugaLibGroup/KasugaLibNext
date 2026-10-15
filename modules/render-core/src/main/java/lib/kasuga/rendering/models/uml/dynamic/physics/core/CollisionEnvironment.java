package lib.kasuga.rendering.models.uml.dynamic.physics.core;

/**
 * Refreshes static colliders once per world step. Implementations translate
 * host-world geometry (for example Minecraft blocks) into planes, boxes and
 * environment-mesh cells.
 */
@FunctionalInterface
public interface CollisionEnvironment {
    void update(RigidBodyWorld world);
}
