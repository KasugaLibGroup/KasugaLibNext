package lib.kasuga.rendering.models.uml.loaders.structural;

public abstract class Processor<T> {

    @SuppressWarnings("unchecked")
    public void walk(Object input, Context context) {
        context.push(this);
        process((T) input, context);
        context.pop();
    }

    public abstract void process(T input, Context context);
}
