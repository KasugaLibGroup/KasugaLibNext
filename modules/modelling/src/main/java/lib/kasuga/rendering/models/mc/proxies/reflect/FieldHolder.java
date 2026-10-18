package lib.kasuga.rendering.models.mc.proxies.reflect;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class FieldHolder {

    @Getter
    private final Field field;

    @Getter
    private final String name;

    @Getter
    private final boolean isFinal, forceAccess;

    @Getter
    private final Class<?> type;

    @Getter
    @Setter
    @Nullable
    private Limitations limitations;


    public FieldHolder(Field field) {
        this(field, false);
    }

    public FieldHolder(Field field, @Nullable Limitations limitations) {
        this(field, false, limitations);
    }

    public FieldHolder(Field field, boolean forceAccess) {
        this(field, forceAccess, null);
    }

    public FieldHolder(Field field, boolean forceAccess, @Nullable Limitations limitations) {
        this.field = field;
        this.name = field.getName();
        this.isFinal = Modifier.isFinal(field.getModifiers());
        type = field.getType();
        this.limitations = limitations;
        this.forceAccess = forceAccess;
        if (forceAccess) {
            field.setAccessible(true);
        }
    }

    public Object get(Object instance) throws IllegalAccessException {
        if (cantAccess(instance)) {
            throw new IllegalAccessException("Field " + name + " is not accessible on instance of type " + instance.getClass().getName());
        }
        if (hasLimitations() && !limitations.canGet(this)) {
            throw new IllegalAccessException("Field " + name + " cannot be accessed due to limitations.");
        }
        return hasLimitations() ? limitations.getValue(this, instance) : field.get(instance);
    }

    public void set(Object instance, Object value) throws IllegalAccessException {
        if (isFinal) {
            throw new IllegalAccessException("Field " + name + " is final and cannot be modified.");
        }
        if (cantAccess(instance)) {
            throw new IllegalAccessException("Field " + name + " is not accessible on instance of type " + instance.getClass().getName());
        }
        if (hasLimitations() && !limitations.canSet(this, value)) {
            throw new IllegalAccessException("Field " + name + " cannot be modified due to limitations.");
        }
        if (hasLimitations()) {
            field.set(instance, limitations.setValue(this, field.get(instance), value));
            return;
        }
        field.set(instance, value);
    }

    public boolean hasLimitations() {
        return limitations != null;
    }

    public boolean cantAccess(Object instance) {
        return !field.canAccess(instance);
    }

    public boolean isInstanceOf(Class<?> instance) {
        return type.isAssignableFrom(instance);
    }
}
