package lib.kasuga.rendering.models.uml.loaders.serial;

public interface ContextData<T extends ContextData<T>> {

    public void build(SerialContext<T> context);
}
