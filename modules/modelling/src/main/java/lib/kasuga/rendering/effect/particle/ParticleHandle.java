package lib.kasuga.rendering.effect.particle;

/** Owned reference to one particle instance in a group. */
public interface ParticleHandle {
    long id();

    ParticleInstance instance();

    boolean isActive();

    boolean remove();
}
