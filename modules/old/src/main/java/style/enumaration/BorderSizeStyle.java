package lib.kasuga.widget.renderer.ui.style.enumaration;

import lib.kasuga.widget.dom.DomElement;
import lib.kasuga.widget.dom.style.StyleMap;
import lib.kasuga.widget.renderer.ui.UiElementRenderer;
import lib.kasuga.widget.renderer.ui.style.UiElementStyle;

import java.util.function.BiConsumer;

public class BorderSizeStyle<T> extends UiElementStyle<T> {
    private final BiConsumer<T, BorderSize> consumer;

    public BorderSizeStyle(BiConsumer<T, BorderSize> boxConsumer) {
        this.consumer = boxConsumer;
    }

    @Override
    protected void applyStyle(StyleMap<DomElement> styleTypes, DomElement instance, T value, UiElementRenderer renderer) {
        consumer.accept(value, renderer.getBorderRenderer().getBorderSize());
    }


    public static BorderSizeStyle<Float> BORDER_SIZE = new BorderSizeStyle<>((size, box) -> {
        box.setTop(size);
        box.setLeft(size);
        box.setRight(size);
        box.setBottom(size);
    });

    public static BorderSizeStyle<Float> BORDER_TOP = new BorderSizeStyle<>((size, box) -> box.setTop(size));
    public static BorderSizeStyle<Float> BORDER_LEFT = new BorderSizeStyle<>((size, box) -> box.setLeft(size));
    public static BorderSizeStyle<Float> BORDER_RIGHT = new BorderSizeStyle<>((size, box) -> box.setRight(size));
    public static BorderSizeStyle<Float> BORDER_BOTTOM = new BorderSizeStyle<>((size, box) -> box.setBottom(size));
}
