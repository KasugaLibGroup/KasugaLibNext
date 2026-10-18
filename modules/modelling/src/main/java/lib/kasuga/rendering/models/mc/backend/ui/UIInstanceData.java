package lib.kasuga.rendering.models.mc.backend.ui;

import lombok.Getter;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public class UIInstanceData {

    @Getter
    private final GuiEventListener listener;

    @Getter
    private final Map<Entity, WorldMouseData> operators;

    public UIInstanceData(GuiEventListener listener) {
        this.listener = listener;
        this.operators = new HashMap<>();
    }

    public boolean isFocused(Entity entity) {
        WorldMouseData data = operators.get(entity);
        return data != null && data.focused;
    }

    public void removeFocused(Entity entity) {
        WorldMouseData data = operators.get(entity);
        if (data != null) {
            data.focused = false;
        }
    }

    public void setFocused(Entity entity, @Nullable Vector2f originalPos) {
        WorldMouseData data = operators.get(entity);
        if (data != null) {data.focused = true;}
        else operators.put(entity, new WorldMouseData(originalPos));
    }

    public void operate(Entity operator, BiConsumer<Entity, WorldMouseData> consumer) {
        if (operators.containsKey(operator)) {
            WorldMouseData data = operators.get(operator);
            consumer.accept(operator, data);
        }
    }
}
