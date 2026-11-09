package lib.kasuga.rendering.models.uml.dynamic;

/** A root transform driver whose output can move to a freshly materialized model instance. */
public interface RebindableRootTransformDriver extends RootTransformDriver {

    void rebind(ModelInstance fresh);
}
