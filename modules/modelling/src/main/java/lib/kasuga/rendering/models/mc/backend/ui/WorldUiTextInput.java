package lib.kasuga.rendering.models.mc.backend.ui;

import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * Allows a world panel with its own widget tree to expose editable text focus.
 * Implementations must clear their internal focus when {@link #setFocused(boolean)} receives false.
 */
public interface WorldUiTextInput extends GuiEventListener {
    boolean hasTextInputFocus();
}
