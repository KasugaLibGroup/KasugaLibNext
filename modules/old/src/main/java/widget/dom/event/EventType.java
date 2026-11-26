package lib.kasuga.widget.dom.event;

import lombok.Getter;

@Getter
public class EventType<T extends Event> {

    private final String name;

    public EventType(String name) {
        this.name = name;
    }

    public static <T extends Event> EventType<T> create(String name) {
        return new EventType<T>(name);
    }
}
