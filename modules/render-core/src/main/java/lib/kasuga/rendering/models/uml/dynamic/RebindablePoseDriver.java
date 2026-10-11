package lib.kasuga.rendering.models.uml.dynamic;

/** A driver whose output destination can move to a fresh instance while retaining logical playback state. */
public interface RebindablePoseDriver extends PoseDriver {
    void rebind(ModelInstance fresh);
}
