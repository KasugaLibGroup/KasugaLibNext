package lib.kasuga.widget.dom.event;

import java.util.function.Consumer;

public class EventEmitter {
    MapList<String, Consumer<Event>> namedCaptureConsumer = new MapList<>();
    MapList<EventType<?>, Consumer<?>> typeCaptureConsumers = new MapList<>();
    MapList<String, Consumer<Event>> namedBubbleConsumer = new MapList<>();
    MapList<EventType<?>, Consumer<?>> typeBubbleConsumers = new MapList<>();

    private MapList<String, Consumer<Event>> namedListener(boolean isCapture) {
        return isCapture ? namedCaptureConsumer : namedBubbleConsumer;
    }

    private MapList<EventType<?>, Consumer<?>> typeListener(boolean isCapture) {
        return isCapture ? typeCaptureConsumers : typeBubbleConsumers;
    }

    public void addEventListener(String eventName, Consumer<Event> eventConsumer, boolean isCapture) {
        this.namedListener(isCapture).addEntry(eventName, eventConsumer);
    }

    public <T extends Event> void addEventListener(EventType<T> eventType, Consumer<T> consumer, boolean isCapture) {
        this.typeListener(isCapture).addEntry(eventType, consumer);
    }

    public void removeEventListener(String eventName, Consumer<Event> eventConsumer, boolean isCapture) {
        this.namedListener(isCapture).removeEntry(eventName, eventConsumer);
    }

    public void removeEventListener(EventType<?> eventType, Consumer<?> consumer, boolean isCapture) {
        this.typeListener(isCapture).removeEntry(eventType, consumer);
    }

    public void dispatchEvent(Event event, boolean isCapture) {
        for (Consumer<?> consumer : this.typeListener(isCapture).get(event.getType())) {
            //noinspection unchecked
            ((Consumer<Event>) consumer).accept(event);
        }
        for (Consumer<Event> consumer : this.namedListener(isCapture).get(event.getType().getName())) {
            consumer.accept(event);
        }
    }
}
