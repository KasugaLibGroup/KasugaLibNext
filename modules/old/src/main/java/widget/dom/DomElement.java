package lib.kasuga.widget.dom;

import lib.kasuga.rendering.RenderContext;
import lib.kasuga.widget.dom.event.Event;
import lib.kasuga.widget.dom.event.EventEmitter;
import lib.kasuga.widget.dom.event.EventTarget;
import lib.kasuga.widget.dom.event.EventType;
import lib.kasuga.widget.dom.event.types.AttributeChangeEvent;
import lib.kasuga.widget.dom.style.StyleStore;
import lib.kasuga.widget.dom.tree.AttributeType;
import lib.kasuga.widget.dom.tree.Element;
import lib.kasuga.widget.renderer.ElementRenderer;
import lombok.Getter;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.function.Consumer;

public abstract class DomElement<S extends DomElement<S>> extends Element<S> implements EventTarget {
    protected final DomSchema schema;

    protected final HashMap<ResourceLocation, Object> unknownAttributes = new HashMap<>();

    protected final EventEmitter emitter = new EventEmitter();

    @Getter
    protected final ElementRenderer renderer;

    public DomElement(DomSchema schema) {
        this.schema = schema;
        this.renderer = createRenderer(this);
    }

    protected abstract ElementRenderer createRenderer(DomElement domElement);

    StyleStore<S> styleStore = new StyleStore<>(self());


    @Override
    protected void setParent(@Nullable S parent) {
        super.setParent(parent);
        styleStore.setParent(parent == null ? null : parent.styleStore);
    }

    public StyleStore<S> getStyle() {
        return styleStore;
    }

    public <V> void setAttribute(ResourceLocation type, V attribute) {
        //noinspection unchecked
        AttributeType<V, S> attributeType = (AttributeType<V, S>) schema.getAttributeType(type);
        this.dispatchEvent(new AttributeChangeEvent(type, attribute));
        if(attributeType == null) {
            unknownAttributes.put(type, attribute);
            return;
        }
        setAttribute(attributeType, attributeType.validate(attribute));
    }

    @Override
    public int addChild(S element) {
        int index = super.addChild(element);
        this.renderer.addElement(element, index);
        return index;
    }

    @Override
    public int addChildAfter(S source, S element) {
        int index = super.addChildAfter(source, element);
        this.renderer.addElement(element, index);
        return index;
    }

    @Override
    public int addChildBefore(S source, S element) {
        int index = super.addChildBefore(source, element);
        this.renderer.addElement(element, index);
        return index;
    }

    @Override
    public int removeChild(S element) {
        int index = super.removeChild(element);
        this.renderer.removeElement(element, index);
        return index;
    }

    public void dispatchEvent(Event event, boolean capture) {
        this.emitter.dispatchEvent(event, capture);
    }

    public <T extends Event> void addEventListener(EventType<T> type, Consumer<T> consumer, boolean capture) {
        this.emitter.addEventListener(type, consumer, capture);
    }

    public void addEventListener(String name, Consumer<Event> consumer, boolean capture) {
        this.emitter.addEventListener(name, consumer, capture);
    }

    public void removeEventListener(EventType<?> type, Consumer<?> consumer, boolean capture) {
        this.emitter.removeEventListener(type, consumer, capture);
    }

    public void removeEventListener(String name, Consumer<Event> consumer, boolean capture) {
        this.emitter.removeEventListener(name, consumer, capture);
    }

    public void renderSelf(RenderContext context) {
    }

}
