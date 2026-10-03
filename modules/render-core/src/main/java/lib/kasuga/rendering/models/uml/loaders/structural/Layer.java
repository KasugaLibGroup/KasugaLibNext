package lib.kasuga.rendering.models.uml.loaders.structural;

import lib.kasuga.structure.Pair;
import lombok.Getter;

import java.util.*;

@Getter
public class Layer<T> extends Processor<T> {

    private final HashMap<String, Processor<?>> namedProcessors;

    private final Queue<Pair<?, Processor<?>>> childrenProcess;

    public Layer(Map<String, Processor<?>> namedProcessors) {
        super();
        this.namedProcessors = new HashMap<>(namedProcessors);
        this.childrenProcess = new LinkedList<>();
    }

    public Layer() {
        this(new HashMap<>());
    }

    public void addNamedProcessor(String name, Processor<?> processor) {
        this.namedProcessors.put(name, processor);
    }

    public <R> void addChildProcess(R input, Processor<R> childProcess) {
        this.childrenProcess.add(Pair.of(input, childProcess));
    }

    public <R> void addChildProcess(R input, String processorName) {
        if (!namedProcessors.containsKey(processorName)) {
            throw new IllegalArgumentException("No processor named " + processorName);
        }
        this.childrenProcess.add(Pair.of(input, namedProcessors.get(processorName)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void walk(Object input, Context context) {
        context.push(this);
        process((T) input, context);
        while (!childrenProcess.isEmpty()) {
            Pair<?, Processor<?>> childProcess = childrenProcess.poll();
            childProcess.getSecond().walk(childProcess.getFirst(), context);
        }
        postProcess((T) input, context);
        context.pop();
    }

    @Override
    public void process(T input, Context context) {}


    public void postProcess(T input, Context context) {}
}
