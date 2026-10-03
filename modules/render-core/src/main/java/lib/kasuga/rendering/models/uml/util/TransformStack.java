package lib.kasuga.rendering.models.uml.util;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.Stack;

@Getter
public class TransformStack {

    @NonNull
    private final Transform rootTransform;

    private final Stack<Pair<String, Pair<Transform, Transform>>> stack;

    public TransformStack(@Nullable Transform rootTransform) {
        this.stack = new Stack<>();
        this.rootTransform = rootTransform == null ? new Transform() : rootTransform;
    }

    public void push(@NonNull String boneName, @NonNull Transform localTransform) {
        Transform parentAbsTransform = stack.isEmpty() ? rootTransform : stack.peek().getSecond().getSecond();
        stack.push(Pair.of(boneName, Pair.of(localTransform, parentAbsTransform.copy().mul(localTransform))));
    }

    public void pop() {
        stack.pop();
    }

    public Pair<String, Pair<Transform, Transform>> peek() {
        return stack.peek();
    }

    public int size() {
        return stack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public void clear() {
        stack.clear();
    }
}
