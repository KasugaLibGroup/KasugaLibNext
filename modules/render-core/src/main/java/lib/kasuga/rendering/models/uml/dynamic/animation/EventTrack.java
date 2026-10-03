package lib.kasuga.rendering.models.uml.dynamic.animation;

import java.util.HashMap;
import java.util.Map;

public final class EventTrack<I> {
    private final boolean[] hasEvents;
    private final Map<Integer, I> events = new HashMap<>();

    public EventTrack(int size) {
        this.hasEvents = new boolean[size];
    }

    public I getEvent(int time) {
        if (!this.events.containsKey(time)) {
            return null;
        }

        return this.events.get(time);
    }

    public void setEvent(int time, I event) {
        if (event == null) {
            this.events.remove(time);
            this.hasEvents[time] = false;
            return;
        }
        this.events.put(time, event);
        this.hasEvents[time] = true;
    }

    public I getNextEvent(int time) {
        while (!this.hasEvents[time]) {
            time++;
        }

        return getEvent(time);
    }
}
