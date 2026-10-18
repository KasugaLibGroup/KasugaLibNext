package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderParameterSchema;
import lib.kasuga.shader.ShaderParameterType;
import net.minecraft.client.renderer.ShaderInstance;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Mutable runtime values for one exposed-parameter schema. Values survive shader resource reloads;
 * each draw uploads the active snapshot to the current ShaderInstance.
 */
public final class ShaderParameterBlock {
    private static final Runnable NO_CHANGE_LISTENER = () -> {};

    private final ShaderParameterSchema schema;
    private final Object updateLock = new Object();
    private volatile State state;
    private volatile Binding binding;
    private volatile Runnable changeListener = NO_CHANGE_LISTENER;

    public ShaderParameterBlock(ShaderParameterSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
        List<ShaderParameter> parameters = schema.parameters();
        Object[] values = new Object[parameters.size()];
        for (int index = 0; index < parameters.size(); index++) {
            values[index] = defaults(parameters.get(index));
        }
        state = new State(values, 0L);
    }

    public ShaderParameterSchema schema() {
        return schema;
    }

    public long version() {
        return state.version;
    }

    /** Returns an exact numeric copy; integer and boolean parameters do not pass through float storage. */
    public double[] values(String name) {
        ShaderParameter parameter = schema.require(name);
        Object value = state.values[indexOf(name)];
        if (parameter.type().integral()) return new double[]{(Integer) value};
        float[] components = (float[]) value;
        double[] result = new double[components.length];
        for (int index = 0; index < components.length; index++) result[index] = components[index];
        return result;
    }

    public float floatValue(String name) {
        ShaderParameter parameter = requireType(name, ShaderParameterType.FLOAT);
        return ((float[]) state.values[indexOf(parameter.name())])[0];
    }

    public int intValue(String name) {
        ShaderParameter parameter = requireType(name, ShaderParameterType.INTEGER);
        return (Integer) state.values[indexOf(parameter.name())];
    }

    public boolean booleanValue(String name) {
        ShaderParameter parameter = requireType(name, ShaderParameterType.BOOLEAN);
        return (Integer) state.values[indexOf(parameter.name())] != 0;
    }

    /** Returns whether this parameter still equals the default declared by its schema. */
    public boolean isDefault(String name) {
        ShaderParameter parameter = schema.require(name);
        return valueEquals(state.values[indexOf(name)], defaults(parameter));
    }

    /** Declaration-based overload that also verifies the parameter belongs to this schema. */
    public boolean isDefault(ShaderParameter parameter) {
        requireSameParameter(parameter);
        return isDefault(parameter.name());
    }

    /** Returns true when at least one value differs from its schema default. */
    public boolean hasOverrides() {
        State snapshot = state;
        List<ShaderParameter> parameters = schema.parameters();
        for (int index = 0; index < parameters.size(); index++) {
            if (!valueEquals(snapshot.values[index], defaults(parameters.get(index)))) return true;
        }
        return false;
    }

    public void setFloat(ShaderParameter parameter, float value) {
        requireSameParameter(parameter, ShaderParameterType.FLOAT);
        set(parameter.name(), value);
    }

    public void setFloat(String name, float value) {
        requireType(name, ShaderParameterType.FLOAT);
        set(name, value);
    }

    public void setInt(ShaderParameter parameter, int value) {
        requireSameParameter(parameter, ShaderParameterType.INTEGER);
        set(parameter.name(), value);
    }

    public void setInt(String name, int value) {
        requireType(name, ShaderParameterType.INTEGER);
        set(name, value);
    }

    public void setBoolean(ShaderParameter parameter, boolean value) {
        requireSameParameter(parameter, ShaderParameterType.BOOLEAN);
        set(parameter.name(), value ? 1 : 0);
    }

    public void setBoolean(String name, boolean value) {
        requireType(name, ShaderParameterType.BOOLEAN);
        set(name, value ? 1 : 0);
    }

    /** Sets every component after validating type shape, finiteness and the declared range. */
    public void set(ShaderParameter parameter, double... values) {
        requireSameParameter(parameter);
        set(parameter.name(), values);
    }

    /** Name-based entry point intended for generic GUIs, persistence and scripting bridges. */
    public void set(String name, double... values) {
        ShaderParameter parameter = schema.require(name);
        parameter.validate(values);
        update(indexOf(name), convert(parameter.type(), values));
    }

    public void reset(ShaderParameter parameter) {
        requireSameParameter(parameter);
        reset(parameter.name());
    }

    public void reset(String name) {
        ShaderParameter parameter = schema.require(name);
        update(indexOf(name), defaults(parameter));
    }

    public void resetAll() {
        boolean changed;
        synchronized (updateLock) {
            State previous = state;
            Object[] values = new Object[schema.size()];
            changed = false;
            for (int index = 0; index < values.length; index++) {
                values[index] = defaults(schema.parameters().get(index));
                changed |= !valueEquals(previous.values[index], values[index]);
            }
            if (changed) state = new State(values, previous.version + 1);
        }
        if (changed) changeListener.run();
    }

    /** Uploads this block to a compatible ShaderInstance. Must run on the render thread. */
    public void apply(ShaderInstance shader) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(shader, "shader");
        Binding current = binding;
        if (current == null || current.shader != shader) {
            current = createBinding(shader);
            binding = current;
        }
        upload(current, state);
    }

    /** Resolves and probes every exposed uniform before a shader is published as ready. */
    void validate(ShaderInstance shader) {
        RenderSystem.assertOnRenderThread();
        Binding candidate = createBinding(Objects.requireNonNull(shader, "shader"));
        upload(candidate, state);
        binding = candidate;
    }

    void onChange(Runnable listener) {
        changeListener = Objects.requireNonNull(listener, "listener");
    }

    private void upload(Binding binding, State snapshot) {
        List<ShaderParameter> parameters = schema.parameters();
        for (int index = 0; index < parameters.size(); index++) {
            upload(binding.uniforms[index], parameters.get(index).type(), snapshot.values[index]);
        }
    }

    private Binding createBinding(ShaderInstance shader) {
        List<ShaderParameter> parameters = schema.parameters();
        Uniform[] uniforms = new Uniform[parameters.size()];
        for (int index = 0; index < parameters.size(); index++) {
            ShaderParameter parameter = parameters.get(index);
            Uniform uniform = shader.getUniform(parameter.name());
            if (uniform == null) {
                throw new IllegalStateException("Shader " + shader.getName()
                        + " does not declare exposed uniform " + parameter.name());
            }
            validateUniformShape(parameter, uniform.getType(), uniform.getCount(), shader.getName());
            uniforms[index] = uniform;
        }
        return new Binding(shader, uniforms);
    }

    private static void upload(Uniform uniform, ShaderParameterType type, Object value) {
        if (type.integral()) {
            uniform.set((Integer) value);
            return;
        }
        float[] values = (float[]) value;
        switch (type) {
            case FLOAT -> uniform.set(values[0]);
            case VEC2 -> uniform.set(values[0], values[1]);
            case VEC3, COLOR_RGB -> uniform.set(values[0], values[1], values[2]);
            case VEC4, COLOR_RGBA -> uniform.set(values[0], values[1], values[2], values[3]);
            case MAT2, MAT3, MAT4 -> uniform.set(values);
            case INTEGER, BOOLEAN -> throw new AssertionError("Integral type handled above");
        }
    }

    static void validateUniformShape(
            ShaderParameter parameter,
            int actualType,
            int actualCount,
            String shaderName
    ) {
        Objects.requireNonNull(parameter, "parameter");
        int expectedType = switch (parameter.type()) {
            case INTEGER, BOOLEAN -> Uniform.UT_INT1;
            case FLOAT -> Uniform.UT_FLOAT1;
            case VEC2 -> Uniform.UT_FLOAT2;
            case VEC3, COLOR_RGB -> Uniform.UT_FLOAT3;
            case VEC4, COLOR_RGBA -> Uniform.UT_FLOAT4;
            case MAT2 -> Uniform.UT_MAT2;
            case MAT3 -> Uniform.UT_MAT3;
            case MAT4 -> Uniform.UT_MAT4;
        };
        int expectedCount = parameter.type().componentCount();
        if (actualType != expectedType || actualCount != expectedCount) {
            throw new IllegalStateException("Shader " + shaderName + " uniform " + parameter.name()
                    + " has Minecraft type/count " + actualType + "/" + actualCount
                    + ", expected " + expectedType + "/" + expectedCount
                    + " for exposed " + parameter.type());
        }
    }

    private void update(int index, Object value) {
        boolean changed;
        synchronized (updateLock) {
            State previous = state;
            changed = !valueEquals(previous.values[index], value);
            if (changed) {
                Object[] updated = previous.values.clone();
                updated[index] = value;
                state = new State(updated, previous.version + 1);
            }
        }
        if (changed) changeListener.run();
    }

    private ShaderParameter requireType(String name, ShaderParameterType expected) {
        ShaderParameter parameter = schema.require(name);
        if (parameter.type() != expected) {
            throw new IllegalArgumentException("Shader parameter " + name + " has type "
                    + parameter.type() + ", expected " + expected);
        }
        return parameter;
    }

    private void requireSameParameter(ShaderParameter parameter, ShaderParameterType... expected) {
        Objects.requireNonNull(parameter, "parameter");
        ShaderParameter declared = schema.require(parameter.name());
        if (!declared.equals(parameter)) {
            throw new IllegalArgumentException("Shader parameter declaration does not match schema: "
                    + parameter.name());
        }
        if (expected.length > 0 && Arrays.stream(expected).noneMatch(type -> type == parameter.type())) {
            throw new IllegalArgumentException("Shader parameter " + parameter.name()
                    + " has incompatible type " + parameter.type());
        }
    }

    private int indexOf(String name) {
        List<ShaderParameter> parameters = schema.parameters();
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).name().equals(name)) return index;
        }
        throw new IllegalArgumentException("Shader does not expose parameter: " + name);
    }

    private static Object defaults(ShaderParameter parameter) {
        double[] values = new double[parameter.defaultValues().size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = parameter.defaultValues().get(index).doubleValue();
        }
        return convert(parameter.type(), values);
    }

    private static Object convert(ShaderParameterType type, double[] values) {
        if (type.integral()) return Integer.valueOf((int) values[0]);
        float[] converted = new float[values.length];
        for (int index = 0; index < values.length; index++) converted[index] = (float) values[index];
        return converted;
    }

    private static boolean valueEquals(Object left, Object right) {
        if (left instanceof float[] leftValues && right instanceof float[] rightValues) {
            return Arrays.equals(leftValues, rightValues);
        }
        return Objects.equals(left, right);
    }

    private record State(Object[] values, long version) {}

    private record Binding(ShaderInstance shader, Uniform[] uniforms) {}
}
