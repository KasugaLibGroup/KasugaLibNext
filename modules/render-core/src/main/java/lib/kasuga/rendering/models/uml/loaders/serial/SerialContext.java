package lib.kasuga.rendering.models.uml.loaders.serial;

import lib.kasuga.rendering.models.uml.loaders.ModelLoader;
import lombok.Getter;

import java.util.Stack;

public class SerialContext<E extends ContextData<E>> {

    private final Stack<E> stack;

    @Getter
    private final ModelLoader loader;

    public SerialContext(ModelLoader loader) {
        this.loader = loader;
        this.stack = new Stack<>();
    }

    public void push(E context) {
        stack.push(context);
    }

    public E pop() {
        return stack.pop();
    }

    public E buildAndPop() {
        E context = pop();
        context.build(this);
        return context;
    }

    public int depth() {
        return stack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public E peek() {
        return stack.peek();
    }

    public void clear() {
        stack.clear();
    }
}
