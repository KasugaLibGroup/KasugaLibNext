package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TickLoopPipeline<I> {
    private final Map<String, I> handlers = new HashMap<>();
    private final List<String> orderList = new ArrayList<>();
    private final ArrayList<I> compiled = new ArrayList<>();

    public void update() {
        this.compiled.clear();

        for (var id : this.orderList) {
            this.compiled.add(this.handlers.get(id));
        }
    }

    public boolean contains(String id) {
        return this.handlers.containsKey(id);
    }

    @SuppressWarnings("unchecked")
    public <M extends I> M get(String id, Class<M> type) {
        I item = this.handlers.get(id);
        if (item == null) return null;
        if (!type.isInstance(item)) {
            throw new IllegalStateException("module '" + id + "' is " + item.getClass().getName()
                    + ", expected " + type.getName());
        }
        return (M) item;
    }

    public List<String> ids() {
        return List.copyOf(this.orderList);
    }

    public int size() {
        return this.orderList.size();
    }

    /** Appends {@code item}; registering an existing id again replaces it in place-order. */
    public void addLast(String id, I item) {
        this.remove(id);
        this.handlers.put(id, item);
        this.orderList.add(id);
        this.update();
    }

    /** Prepends {@code item}; registering an existing id again replaces it in place-order. */
    public void addFirst(String id, I item) {
        this.remove(id);
        this.handlers.put(id, item);
        this.orderList.add(0, id);
        this.update();
    }

    /**
     * Inserts {@code id} relative to {@code target}: {@code addBefore} places
     * the item at the target's index (pushing the target back), {@code addAfter}
     * one past it. The previous offset arithmetic was off by one and inserted
     * before the element preceding the target.
     */
    private void _insert(String target, String id, int offset, I item) {
        if (!this.handlers.containsKey(target)) {
            throw new IllegalArgumentException("Unknown target: " + target);
        }
        if (this.handlers.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate id: " + id);
        }

        this.handlers.put(id, item);
        this.orderList.add(this.orderList.indexOf(target) + offset, id);
        this.update();
    }

    public void addBefore(String target, String id, I item) {
        this._insert(target, id, 0, item);
    }

    public void addAfter(String target, String id, I item) {
        this._insert(target, id, 1, item);
    }

    public void remove(String id) {
        this.handlers.remove(id);
        this.orderList.remove(id);
        this.update();
    }

    /**
     * Live view of the compiled order — zero allocation per tick. Iterate
     * without mutating the pipeline; register modules before or after ticks.
     */
    public List<I> list() {
        return java.util.Collections.unmodifiableList(this.compiled);
    }

    public void clear() {
        this.handlers.clear();
        this.orderList.clear();
        this.update();
    }
}
