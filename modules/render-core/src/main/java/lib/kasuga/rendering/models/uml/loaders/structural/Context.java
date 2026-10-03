package lib.kasuga.rendering.models.uml.loaders.structural;

import lombok.Getter;

import java.util.HashMap;
import java.util.Stack;

public class Context {

    @Getter
    private final Loader loader;

    @Getter
    private final Stack<Processor> stack;

    @Getter
    private final HashMap<String, Object> data;

    @Getter
    private final HashMap<Processor, HashMap<String, Object>> temp;

    public Context(Loader loader) {
        this.stack = new Stack<>();
        this.data = new HashMap<>();
        this.temp = new HashMap<>();
        this.loader = loader;
    }

    public HashMap<String, Object> push(Processor processor) {
        stack.push(processor);
        temp.put(processor, new HashMap<>());
        return data;
    }

    public HashMap<String, Object> pop() {
        temp.remove(stack.pop());
        return data;
    }

    public Processor peek() {
        return stack.peek();
    }

    public void clear() {
        stack.clear();
        data.clear();
        temp.clear();
    }

    public int depth() {
        return stack.size();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public Object getData(String key) {
        return data.get(key);
    }

    public Object getDataOrDefault(String key, Object defaultValue) {
        return data.getOrDefault(key, defaultValue);
    }

    public boolean hasData(String key) {
        return data.containsKey(key);
    }

    public void setData(String key, Object value) {
        data.put(key, value);
    }

    public void clearData() {
        data.clear();
    }

    public int dataSize() {
        return data.size();
    }

    public Object getTemp(String key) {
        return temp.get(peek()).get(key);
    }

    public Object setTemp(String key, Object value) {
        return temp.get(peek()).put(key, value);
    }

    public Object getTempOrDefault(String key, Object defaultValue) {
        return temp.get(peek()).getOrDefault(key, defaultValue);
    }

    public boolean hasTemp(String key) {
        return temp.get(peek()).containsKey(key);
    }

    public void clearTemp() {
        temp.get(peek()).clear();
    }

    public int tempSize() {
        return temp.get(peek()).size();
    }

    public boolean isTempEmpty() {
        return temp.get(peek()).isEmpty();
    }
}
