package lib.kasuga.widget.dom.event;

import lib.kasuga.widget.dom.DomElement;
import lombok.Getter;

import java.util.List;

public abstract class Event {

    @Getter
    protected final boolean cancelable;

    @Getter
    protected boolean canceled;

    protected final List<DomElement> eventPath;


    public Event(boolean cancelable, List<DomElement> eventPath) {
        this.cancelable = cancelable;
        this.eventPath = eventPath;
    }

    public void stopPropagation() {
        if(cancelable)
            canceled = true;
    }

    public void emitAsPath() {
        for (DomElement domElement : this.eventPath) {
            domElement.dispatchEvent(this, true);
            if(canceled)
                return;
        }

        for (DomElement domElement : this.eventPath.reversed()) {
            domElement.dispatchEvent(this);
            if(canceled)
                return;
        }
    }

    public abstract EventType<?> getType();
}
