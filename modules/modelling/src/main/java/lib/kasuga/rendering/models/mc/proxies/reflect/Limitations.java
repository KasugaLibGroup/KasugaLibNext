package lib.kasuga.rendering.models.mc.proxies.reflect;

public interface Limitations {

    boolean canSet(FieldHolder holder, Object value);

    boolean canGet(FieldHolder holder);

    Object getValue(FieldHolder holder, Object gotValue);

    Object setValue(FieldHolder holder, Object originalValue, Object newValue);
}
