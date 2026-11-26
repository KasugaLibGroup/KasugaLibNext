package lib.kasuga.widget.dom.tree;

public interface Document<T extends Element<T>, I> {
    public T createElement(I elementIdentifier);

    public AttributeType<?, T> getAttributeType(I attributeIdentifier);
}
