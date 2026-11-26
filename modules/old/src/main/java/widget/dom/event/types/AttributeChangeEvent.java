package lib.kasuga.widget.dom.event.types;

import lib.kasuga.widget.dom.event.Event;
import lib.kasuga.widget.dom.event.EventType;
import lombok.Getter;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

@Getter
public class AttributeChangeEvent extends Event {
    public static EventType<AttributeChangeEvent> TYPE = EventType.create("custom_attribute_change");
    private final ResourceLocation type;
    private final Object value;

    public AttributeChangeEvent(ResourceLocation attributeType, Object object) {
        super(true, List.of());
        this.type = attributeType;
        this.value = object;
    }

    @Override
    public EventType<?> getType() {
        return TYPE;
    }
}
