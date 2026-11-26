package lib.kasuga.elementOld.reactivity;

import java.util.ArrayDeque;
import java.util.Queue;

public class ThreadCollector {
    public ThreadLocal<Queue<EffectCollector>> threadLocal = ThreadLocal.withInitial(ArrayDeque::new);

    public void push(EffectCollector collector) {
        threadLocal.get().add(collector);
    }

    public void pop() {
        threadLocal.get().poll();
    }
}
