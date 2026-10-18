package lib.kasuga.rendering.models.mc.proxies.reflect;

import lombok.Getter;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class InstanceProbe<TargetType> {

    @Getter
    private final Class<TargetType> clazz;

    @Getter
    private final boolean removeFinal;

    public InstanceProbe(Class<TargetType> clazz, boolean removeFinal) {
        this.clazz = clazz;
        this.removeFinal = removeFinal;
    }
    
    public Result<TargetType> probe(TargetType instance) {
        if (instance == null) {
            return new Result<>(this, null, new FieldHolder[0]);
        }

        Class<?> clazz = instance.getClass();
        if (!isValidInstance(instance)) {
            throw new IllegalArgumentException("Instance of type " + clazz.getName() + " is not assignable to target type " + this.clazz.getName());
        }

        Field[] fields = clazz.getDeclaredFields();
        FieldHolder[] fieldHolders = new FieldHolder[fields.length];
        FieldHolder holder;
        for (int i = 0; i < fields.length; i++) {
            holder = new FieldHolder(fields[i], removeFinal);
            fieldHolders[i] = holder;
            holder.setLimitations(setLimitation(instance, holder));
        }
        return new Result<>(this, instance, fieldHolders);
    }

    public Limitations setLimitation(Object instance, FieldHolder holder) {
        return null;
    }

    public boolean isValidInstance(Object instance) {
        if (instance == null) return true;
        return clazz.isAssignableFrom(instance.getClass());
    }

    public static class Result<TargetType> {
        
        public final InstanceProbe<TargetType> probe;
        
        public final HashMap<String, FieldHolder> fieldMap;
        
        public final HashMap<String, FieldHolder> readOnlyFields;

        public final TargetType instance;
        
        
        public Result(InstanceProbe<TargetType> probe, TargetType instance, FieldHolder[] fields) {
            this.probe = probe;
            this.instance = instance;
            fieldMap = new HashMap<>();
            readOnlyFields = new HashMap<>();
            for (FieldHolder field : fields) {
                fieldMap.put(field.getName(), field);
                if (field.isFinal()) {
                    readOnlyFields.put(field.getName(), field);
                }
            }
        }

        public Set<String> getFieldNames() {
            return fieldMap.keySet();
        }

        public Set<String> getReadOnlyFieldNames() {
            return readOnlyFields.keySet();
        }

        public Set<String> getWritableFieldNames() {
            Set<String> allFields = getFieldNames();
            Set<String> readOnlyFields = getReadOnlyFieldNames();
            allFields.removeAll(readOnlyFields);
            return allFields;
        }

        public HashMap<String, Object> getFieldValues() throws IllegalAccessException {
            if (isInstanceNull()) return new HashMap<>();
            HashMap<String, Object> values = new HashMap<>();
            for (Map.Entry<String, FieldHolder> entry : fieldMap.entrySet()) {
                if (!entry.getValue().cantAccess(instance)) continue;
                values.put(entry.getKey(), entry.getValue().get(instance));
            }
            return values;
        }

        public Object get(String fieldName) throws IllegalAccessException {
            if (isInstanceNull()) {
                throw new IllegalStateException("Cannot get field value from null instance.");
            }
            FieldHolder holder = fieldMap.get(fieldName);
            if (holder == null) {
                throw new IllegalArgumentException("Field " + fieldName + " does not exist on instance of type " + instance.getClass().getName());
            }
            return holder.get(instance);
        }

        public boolean hasField(String fieldName) {
            return fieldMap.containsKey(fieldName);
        }

        public int fieldCount() {
            return fieldMap.size();
        }

        public boolean isFieldReadOnly(String fieldName) {
            return readOnlyFields.containsKey(fieldName);
        }

        public int readOnlyFieldCount() {
            return readOnlyFields.size();
        }

        public boolean isInstanceNull() {
            return instance == null;
        }
    }
}
