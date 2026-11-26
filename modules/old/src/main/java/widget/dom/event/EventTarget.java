package lib.kasuga.widget.dom.event;

import java.util.function.Consumer;

public interface EventTarget {
    public void dispatchEvent(Event event, boolean capture);
    public <T extends Event> void addEventListener(EventType<T> type, Consumer<T> consumer, boolean capture);
    public void addEventListener(String name, Consumer<Event> consumer, boolean capture);
    public void removeEventListener(EventType<?> type, Consumer<?> consumer, boolean capture);
    public void removeEventListener(String name, Consumer<Event> consumer, boolean capture);
    public default void dispatchEvent(Event event) {
        dispatchEvent(event, false);
    }
    public default <T extends Event> void addEventListener(EventType<T> type, Consumer<T> consumer) {
        addEventListener(type, consumer, false);
    }
    public default void addEventListener(String name, Consumer<Event> consumer) {
        addEventListener(name, consumer, false);
    }
    public default void removeEventListener(EventType<?> type, Consumer<?> consumer) {
        removeEventListener(type, consumer, false);
    }
    public default void removeEventListener(String name, Consumer<Event> consumer) {
        removeEventListener(name, consumer, false);
    }
}
