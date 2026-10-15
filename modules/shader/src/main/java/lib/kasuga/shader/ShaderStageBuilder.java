package lib.kasuga.shader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Shared typed statements and declarations for vertex and fragment stages. */
abstract class ShaderStageBuilder implements ShaderAssignmentOwner {
    private static final List<Number> IDENTITY_MATRIX_2 = List.of(
            1, 0,
            0, 1
    );
    private static final List<Number> IDENTITY_MATRIX_3 = List.of(
            1, 0, 0,
            0, 1, 0,
            0, 0, 1
    );
    private static final List<Number> IDENTITY_MATRIX = List.of(
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    );

    private final List<ShaderGlobal> globals = new ArrayList<>();
    private final List<ShaderParameter> exposedParameters = new ArrayList<>();
    private final List<ShaderStructType> structs = new ArrayList<>();
    private final Set<ShaderStructType> declaredStructs = new LinkedHashSet<>();
    private final List<String> rawPreamble = new ArrayList<>();
    private final List<String> rawDeclarations = new ArrayList<>();
    private final Set<String> names = new LinkedHashSet<>();
    private final Deque<List<ShaderIr.Statement>> blocks = new ArrayDeque<>();
    private final Deque<BreakTarget> breakTargets = new ArrayDeque<>();
    private int loopDepth;
    private boolean built;

    protected ShaderStageBuilder() {
        blocks.push(new ArrayList<>());
    }

    public FloatExpr uniformFloat(String name, float defaultValue) {
        return declareFloat(ShaderStorage.UNIFORM, name, List.of(defaultValue));
    }

    public FloatExpr exposeFloat(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.FLOAT);
        registerExposed(parameter);
        return uniformFloat(parameter.name(), parameter.defaultValues().getFirst().floatValue());
    }

    public IntExpr exposeInt(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.INTEGER);
        registerExposed(parameter);
        return uniformInt(parameter.name(), parameter.defaultValues().getFirst().intValue());
    }

    public BoolExpr exposeBool(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.BOOLEAN);
        registerExposed(parameter);
        return uniformBool(parameter.name(), parameter.defaultValues().getFirst().intValue() != 0);
    }

    public Vec2Expr exposeVec2(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.VEC2);
        registerExposed(parameter);
        List<Number> value = parameter.defaultValues();
        return uniformVec2(parameter.name(), value.get(0).floatValue(), value.get(1).floatValue());
    }

    public Vec3Expr exposeVec3(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.VEC3, ShaderParameterType.COLOR_RGB);
        registerExposed(parameter);
        List<Number> value = parameter.defaultValues();
        return uniformVec3(parameter.name(), value.get(0).floatValue(), value.get(1).floatValue(),
                value.get(2).floatValue());
    }

    public Vec4Expr exposeVec4(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.VEC4, ShaderParameterType.COLOR_RGBA);
        registerExposed(parameter);
        List<Number> value = parameter.defaultValues();
        return uniformVec4(parameter.name(), value.get(0).floatValue(), value.get(1).floatValue(),
                value.get(2).floatValue(), value.get(3).floatValue());
    }

    public Mat2Expr exposeMat2(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.MAT2);
        registerExposed(parameter);
        return uniformMat2(parameter.name(), floatDefaults(parameter));
    }

    public Mat3Expr exposeMat3(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.MAT3);
        registerExposed(parameter);
        return uniformMat3(parameter.name(), floatDefaults(parameter));
    }

    public Mat4Expr exposeMat4(ShaderParameter parameter) {
        requireExposedType(parameter, ShaderParameterType.MAT4);
        registerExposed(parameter);
        return uniformMat4(parameter.name(), floatDefaults(parameter));
    }

    public IntExpr uniformInt(String name, int defaultValue) {
        return declareInt(ShaderStorage.UNIFORM, name, List.of(defaultValue));
    }

    public BoolExpr uniformBool(String name, boolean defaultValue) {
        return declareBool(ShaderStorage.UNIFORM, name, List.of(defaultValue ? 1 : 0));
    }

    public Vec2Expr uniformVec2(String name, float x, float y) {
        return declareVec2(ShaderStorage.UNIFORM, name, List.of(x, y));
    }

    public Vec3Expr uniformVec3(String name, float x, float y, float z) {
        return declareVec3(ShaderStorage.UNIFORM, name, List.of(x, y, z));
    }

    public Vec4Expr uniformVec4(String name, float x, float y, float z, float w) {
        return declareVec4(ShaderStorage.UNIFORM, name, List.of(x, y, z, w));
    }

    public Mat2Expr uniformMat2(String name) {
        return declareMat2(ShaderStorage.UNIFORM, name, IDENTITY_MATRIX_2);
    }

    public Mat2Expr uniformMat2(String name, float... defaultValues) {
        return declareMat2(ShaderStorage.UNIFORM, name, matrixDefaults("mat2", 4, defaultValues));
    }

    public Mat3Expr uniformMat3(String name) {
        return declareMat3(ShaderStorage.UNIFORM, name, IDENTITY_MATRIX_3);
    }

    public Mat3Expr uniformMat3(String name, float... defaultValues) {
        return declareMat3(ShaderStorage.UNIFORM, name, matrixDefaults("mat3", 9, defaultValues));
    }

    /** Declares an identity-initialized matrix, suitable for Minecraft's standard matrix uniforms. */
    public Mat4Expr uniformMat4(String name) {
        return declareMat4(ShaderStorage.UNIFORM, name, IDENTITY_MATRIX);
    }

    /** Declares a column-major matrix with exactly sixteen default values. */
    public Mat4Expr uniformMat4(String name, float... defaultValues) {
        return declareMat4(ShaderStorage.UNIFORM, name, matrixDefaults("mat4", 16, defaultValues));
    }

    public FloatArrayExpr uniformFloatArray(String name, int length, float defaultValue) {
        requireNewName(name);
        if (length <= 1) throw new IllegalArgumentException("Uniform array length must be greater than one");
        globals.add(new ShaderGlobal(
                ShaderStorage.UNIFORM, ShaderType.FLOAT, name, length, List.of(defaultValue)
        ));
        return new FloatArrayExpr(new ShaderIr.Reference(ShaderType.FLOAT, name), length);
    }

    public Sampler2DExpr sampler2D(String name) {
        requireNewName(name);
        globals.add(new ShaderGlobal(
                ShaderStorage.SAMPLER, ShaderType.SAMPLER_2D, name, 0, List.of()
        ));
        return new Sampler2DExpr(new ShaderIr.Reference(ShaderType.SAMPLER_2D, name));
    }

    public FloatExpr inputFloat(String name) {
        return declareFloat(ShaderStorage.INPUT, name, List.of());
    }

    public IntExpr inputInt(String name) {
        return declareInt(ShaderStorage.INPUT, name, List.of());
    }

    public Vec2Expr inputVec2(String name) {
        return declareVec2(ShaderStorage.INPUT, name, List.of());
    }

    public Vec3Expr inputVec3(String name) {
        return declareVec3(ShaderStorage.INPUT, name, List.of());
    }

    public Vec4Expr inputVec4(String name) {
        return declareVec4(ShaderStorage.INPUT, name, List.of());
    }

    public FloatExpr f32(float value) { return FloatExpr.literal(value); }
    public IntExpr i32(int value) { return IntExpr.literal(value); }
    public BoolExpr bool(boolean value) { return BoolExpr.literal(value); }

    public Vec2Expr vec2(FloatExpr x, FloatExpr y) {
        return new Vec2Expr(new ShaderIr.Construct(ShaderType.VEC2, List.of(x.ir(), y.ir())));
    }

    public Vec2Expr vec2(float x, float y) {
        return vec2(f32(x), f32(y));
    }

    public Vec3Expr vec3(FloatExpr x, FloatExpr y, FloatExpr z) {
        return new Vec3Expr(new ShaderIr.Construct(ShaderType.VEC3, List.of(x.ir(), y.ir(), z.ir())));
    }

    public Vec3Expr vec3(float x, float y, float z) {
        return vec3(f32(x), f32(y), f32(z));
    }

    public Vec4Expr vec4(FloatExpr x, FloatExpr y, FloatExpr z, FloatExpr w) {
        return new Vec4Expr(new ShaderIr.Construct(ShaderType.VEC4, List.of(x.ir(), y.ir(), z.ir(), w.ir())));
    }

    public Vec4Expr vec4(Vec3Expr xyz, FloatExpr w) {
        return new Vec4Expr(new ShaderIr.Construct(ShaderType.VEC4, List.of(xyz.ir(), w.ir())));
    }

    public Vec4Expr vec4(float x, float y, float z, float w) {
        return vec4(f32(x), f32(y), f32(z), f32(w));
    }

    public Mat2Expr mat2(FloatExpr... values) {
        return new Mat2Expr(matrixConstruct(ShaderType.MAT2, 4, values));
    }

    public Mat2Expr mat2(float... values) {
        return mat2(floatExpressions(values));
    }

    public Mat3Expr mat3(FloatExpr... values) {
        return new Mat3Expr(matrixConstruct(ShaderType.MAT3, 9, values));
    }

    public Mat3Expr mat3(float... values) {
        return mat3(floatExpressions(values));
    }

    public Mat4Expr mat4(FloatExpr... values) {
        return new Mat4Expr(matrixConstruct(ShaderType.MAT4, 16, values));
    }

    public Mat4Expr mat4(float... values) {
        return mat4(floatExpressions(values));
    }

    /** Declares a nominal struct before it is used by expressions in this stage. */
    public ShaderStructType declareStruct(ShaderStructType type) {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        requireNewName(type.name());
        for (ShaderStructField<?> field : type.fields()) {
            if (field.type() instanceof ShaderStructType nested && !declaredStructs.contains(nested)) {
                throw new IllegalArgumentException("Struct " + type.name()
                        + " depends on undeclared struct " + nested.name());
            }
        }
        declaredStructs.add(type);
        structs.add(type);
        return type;
    }

    /** Constructs a struct value using fields in declaration order. */
    public StructExpr structValue(ShaderStructType type, ShaderExpression... values) {
        ensureOpen();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(values, "values");
        if (!declaredStructs.contains(type)) {
            throw new IllegalArgumentException("Struct is not declared in this stage: " + type.name());
        }
        if (values.length != type.fields().size()) {
            throw new IllegalArgumentException("Struct " + type.name() + " expects "
                    + type.fields().size() + " fields but got " + values.length);
        }
        List<ShaderIr.Expression> arguments = new ArrayList<>(values.length);
        for (int index = 0; index < values.length; index++) {
            ShaderExpression value = Objects.requireNonNull(values[index], "values contains null");
            ShaderValueType expected = type.fields().get(index).type();
            if (!expected.equals(value.type())) {
                throw new IllegalArgumentException("Struct " + type.name() + " field "
                        + type.fields().get(index).name() + " expects " + expected
                        + " but got " + value.type());
            }
            arguments.add(value.ir());
        }
        return new StructExpr(new ShaderIr.Construct(type, arguments), type);
    }

    public ShaderVariable<FloatExpr> localFloat(String name, FloatExpr initializer) {
        return local(name, initializer, FloatExpr::new);
    }

    public ShaderVariable<IntExpr> localInt(String name, IntExpr initializer) {
        return local(name, initializer, IntExpr::new);
    }

    public ShaderVariable<Vec2Expr> localVec2(String name, Vec2Expr initializer) {
        return local(name, initializer, Vec2Expr::new);
    }

    public ShaderVariable<Vec3Expr> localVec3(String name, Vec3Expr initializer) {
        return local(name, initializer, Vec3Expr::new);
    }

    public ShaderVariable<Vec4Expr> localVec4(String name, Vec4Expr initializer) {
        return local(name, initializer, Vec4Expr::new);
    }

    public ShaderVariable<Mat2Expr> localMat2(String name, Mat2Expr initializer) {
        return local(name, initializer, Mat2Expr::new);
    }

    public ShaderVariable<Mat3Expr> localMat3(String name, Mat3Expr initializer) {
        return local(name, initializer, Mat3Expr::new);
    }

    public ShaderVariable<Mat4Expr> localMat4(String name, Mat4Expr initializer) {
        return local(name, initializer, Mat4Expr::new);
    }

    public ShaderVariable<StructExpr> localStruct(String name, StructExpr initializer) {
        Objects.requireNonNull(initializer, "initializer");
        ShaderStructType type = initializer.structType();
        return local(name, initializer, expression -> new StructExpr(expression, type));
    }

    public FloatExpr letFloat(String name, FloatExpr initializer) {
        return localFloat(name, initializer).get();
    }

    public IntExpr letInt(String name, IntExpr initializer) {
        return localInt(name, initializer).get();
    }

    public Vec2Expr letVec2(String name, Vec2Expr initializer) {
        return localVec2(name, initializer).get();
    }

    public Vec3Expr letVec3(String name, Vec3Expr initializer) {
        return localVec3(name, initializer).get();
    }

    public Vec4Expr letVec4(String name, Vec4Expr initializer) {
        return localVec4(name, initializer).get();
    }

    public Mat2Expr letMat2(String name, Mat2Expr initializer) {
        return localMat2(name, initializer).get();
    }

    public Mat3Expr letMat3(String name, Mat3Expr initializer) {
        return localMat3(name, initializer).get();
    }

    public Mat4Expr letMat4(String name, Mat4Expr initializer) {
        return localMat4(name, initializer).get();
    }

    public StructExpr letStruct(String name, StructExpr initializer) {
        return localStruct(name, initializer).get();
    }

    /** Assigns one field of a struct local with compile-time wrapper type checking. */
    public <T extends ShaderExpression> void setStructField(
            ShaderVariable<StructExpr> struct,
            ShaderStructField<T> field,
            T value
    ) {
        Objects.requireNonNull(struct, "struct");
        assign(struct.get().field(field), Objects.requireNonNull(value, "value"));
    }

    public void ifThen(BoolExpr condition, Runnable thenBlock) {
        ifThenElse(condition, thenBlock, null);
    }

    public void ifThenElse(BoolExpr condition, Runnable thenBlock, Runnable elseBlock) {
        Objects.requireNonNull(condition, "condition");
        ShaderIr.Block thenIr = capture(Objects.requireNonNull(thenBlock, "thenBlock"));
        ShaderIr.Block elseIr = elseBlock == null ? null : capture(elseBlock);
        emit(new ShaderIr.IfStatement(condition.ir(), thenIr, elseIr));
    }

    public void forRange(String indexName, IntExpr startInclusive, IntExpr endExclusive,
                         Consumer<IntExpr> body) {
        requireNewName(indexName);
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        Objects.requireNonNull(body, "body");
        IntExpr index = new IntExpr(new ShaderIr.Reference(ShaderType.INT, indexName));
        ShaderIr.Block bodyIr = captureBreakable(BreakTarget.LOOP, true, () -> body.accept(index));
        emit(new ShaderIr.ForRange(indexName, startInclusive.ir(), endExclusive.ir(), bodyIr));
    }

    /** Emits a GLSL {@code while} loop. */
    public void whileLoop(BoolExpr condition, Runnable body) {
        Objects.requireNonNull(condition, "condition");
        ShaderIr.Block bodyIr = captureBreakable(
                BreakTarget.LOOP, true, Objects.requireNonNull(body, "body")
        );
        emit(new ShaderIr.WhileLoop(condition.ir(), bodyIr));
    }

    /** Emits a GLSL {@code do while} loop. */
    public void doWhile(BoolExpr condition, Runnable body) {
        Objects.requireNonNull(condition, "condition");
        ShaderIr.Block bodyIr = captureBreakable(
                BreakTarget.LOOP, true, Objects.requireNonNull(body, "body")
        );
        emit(new ShaderIr.DoWhileLoop(bodyIr, condition.ir()));
    }

    /** Emits an integer GLSL switch; cases do not receive implicit breaks. */
    public void switchOn(IntExpr selector, Consumer<ShaderSwitchBuilder> definition) {
        Objects.requireNonNull(selector, "selector");
        ShaderSwitchBuilder cases = new ShaderSwitchBuilder(body ->
                captureBreakable(BreakTarget.SWITCH, false, body)
        );
        Objects.requireNonNull(definition, "definition").accept(cases);
        emit(cases.finish(selector.ir()));
    }

    /** Breaks the innermost shader loop, which must also be the innermost break target. */
    public void breakLoop() {
        if (breakTargets.peek() != BreakTarget.LOOP) {
            throw new IllegalStateException("breakLoop() must target the innermost shader loop");
        }
        emit(ShaderIr.BreakStatement.INSTANCE);
    }

    /** Breaks the innermost shader switch. */
    public void breakSwitch() {
        if (breakTargets.peek() != BreakTarget.SWITCH) {
            throw new IllegalStateException("breakSwitch() must target the innermost shader switch");
        }
        emit(ShaderIr.BreakStatement.INSTANCE);
    }

    /** Continues the innermost shader loop. */
    public void continueLoop() {
        if (loopDepth <= 0) throw new IllegalStateException("continueLoop() must be inside a shader loop");
        emit(ShaderIr.ContinueStatement.INSTANCE);
    }

    /** Inserts source immediately after the generated {@code #version 150} line. */
    @DelicateShaderApi
    public void rawPreamble(String source) {
        ensureOpen();
        rawPreamble.add(requireRawSource(source, "preamble"));
    }

    /** Inserts source after globals and before the generated {@code main} function. */
    @DelicateShaderApi
    public void rawDeclaration(String source) {
        ensureOpen();
        rawDeclarations.add(requireRawSource(source, "declaration"));
    }

    /** Inserts an unchecked statement into the current shader block. */
    @DelicateShaderApi
    public void rawStatement(String source) {
        ensureOpen();
        emit(new ShaderIr.RawStatement(requireRawSource(source, "statement")));
    }

    /** Wraps unchecked source and promises that its GLSL result is {@code float}. */
    @DelicateShaderApi
    public FloatExpr rawFloat(String source) {
        return new FloatExpr(rawExpression(ShaderType.FLOAT, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code int}. */
    @DelicateShaderApi
    public IntExpr rawInt(String source) {
        return new IntExpr(rawExpression(ShaderType.INT, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code bool}. */
    @DelicateShaderApi
    public BoolExpr rawBool(String source) {
        return new BoolExpr(rawExpression(ShaderType.BOOL, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code vec2}. */
    @DelicateShaderApi
    public Vec2Expr rawVec2(String source) {
        return new Vec2Expr(rawExpression(ShaderType.VEC2, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code vec3}. */
    @DelicateShaderApi
    public Vec3Expr rawVec3(String source) {
        return new Vec3Expr(rawExpression(ShaderType.VEC3, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code vec4}. */
    @DelicateShaderApi
    public Vec4Expr rawVec4(String source) {
        return new Vec4Expr(rawExpression(ShaderType.VEC4, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code mat2}. */
    @DelicateShaderApi
    public Mat2Expr rawMat2(String source) {
        return new Mat2Expr(rawExpression(ShaderType.MAT2, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code mat3}. */
    @DelicateShaderApi
    public Mat3Expr rawMat3(String source) {
        return new Mat3Expr(rawExpression(ShaderType.MAT3, source));
    }
    /** Wraps unchecked source and promises that its GLSL result is {@code mat4}. */
    @DelicateShaderApi
    public Mat4Expr rawMat4(String source) {
        return new Mat4Expr(rawExpression(ShaderType.MAT4, source));
    }
    /** Wraps unchecked source and promises that its result has the declared struct type. */
    @DelicateShaderApi
    public StructExpr rawStruct(ShaderStructType type, String source) {
        Objects.requireNonNull(type, "type");
        if (!declaredStructs.contains(type)) {
            throw new IllegalArgumentException("Struct is not declared in this stage: " + type.name());
        }
        return new StructExpr(rawExpression(type, source), type);
    }

    @Override
    public final void assign(ShaderExpression target, ShaderExpression value) {
        ensureOpen();
        emit(new ShaderIr.Assignment(target.ir(), value.ir()));
    }

    protected final <T extends ShaderExpression> ShaderVariable<T> output(
            String name,
            ShaderType type,
            java.util.function.Function<ShaderIr.Expression, T> wrapper
    ) {
        declare(ShaderStorage.OUTPUT, type, name, List.of());
        return new ShaderVariable<>(this, wrapper.apply(new ShaderIr.Reference(type, name)));
    }

    protected final ShaderModule finish() {
        ensureOpen();
        built = true;
        return new ShaderModule(
                structs, globals, rawPreamble, rawDeclarations, new ShaderIr.Block(blocks.pop())
        );
    }

    final List<ShaderParameter> exposedParameters() {
        return List.copyOf(exposedParameters);
    }

    private void registerExposed(ShaderParameter parameter) {
        ensureOpen();
        exposedParameters.add(Objects.requireNonNull(parameter, "parameter"));
    }

    private static void requireExposedType(
            ShaderParameter parameter,
            ShaderParameterType... accepted
    ) {
        Objects.requireNonNull(parameter, "parameter");
        for (ShaderParameterType type : accepted) {
            if (parameter.type() == type) return;
        }
        throw new IllegalArgumentException("Parameter " + parameter.name() + " has type "
                + parameter.type() + ", expected " + java.util.Arrays.toString(accepted));
    }

    private static float[] floatDefaults(ShaderParameter parameter) {
        float[] values = new float[parameter.defaultValues().size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = parameter.defaultValues().get(index).floatValue();
        }
        return values;
    }

    private <T extends ShaderExpression> ShaderVariable<T> local(
            String name, T initializer, java.util.function.Function<ShaderIr.Expression, T> wrapper
    ) {
        ensureOpen();
        requireNewName(name);
        emit(new ShaderIr.VariableDeclaration(initializer.type(), name, initializer.ir()));
        T reference = wrapper.apply(new ShaderIr.Reference(initializer.type(), name));
        return new ShaderVariable<>(this, reference);
    }

    private ShaderIr.Block capture(Runnable body) {
        ensureOpen();
        List<ShaderIr.Statement> statements = new ArrayList<>();
        blocks.push(statements);
        try {
            body.run();
        } finally {
            List<ShaderIr.Statement> popped = blocks.pop();
            if (popped != statements) throw new IllegalStateException("Shader block stack is corrupted");
        }
        return new ShaderIr.Block(statements);
    }

    private ShaderIr.Block captureBreakable(BreakTarget target, boolean loop, Runnable body) {
        breakTargets.push(target);
        if (loop) loopDepth++;
        try {
            return capture(body);
        } finally {
            if (loop) loopDepth--;
            BreakTarget popped = breakTargets.pop();
            if (popped != target) throw new IllegalStateException("Shader break target stack is corrupted");
        }
    }

    private void emit(ShaderIr.Statement statement) {
        ensureOpen();
        blocks.element().add(statement);
    }

    private FloatExpr declareFloat(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.FLOAT, name, defaults);
        return new FloatExpr(new ShaderIr.Reference(ShaderType.FLOAT, name));
    }

    private IntExpr declareInt(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.INT, name, defaults);
        return new IntExpr(new ShaderIr.Reference(ShaderType.INT, name));
    }

    private BoolExpr declareBool(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.BOOL, name, defaults);
        return new BoolExpr(new ShaderIr.Reference(ShaderType.BOOL, name));
    }

    private Vec2Expr declareVec2(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.VEC2, name, defaults);
        return new Vec2Expr(new ShaderIr.Reference(ShaderType.VEC2, name));
    }

    private Vec3Expr declareVec3(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.VEC3, name, defaults);
        return new Vec3Expr(new ShaderIr.Reference(ShaderType.VEC3, name));
    }

    private Vec4Expr declareVec4(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.VEC4, name, defaults);
        return new Vec4Expr(new ShaderIr.Reference(ShaderType.VEC4, name));
    }

    private Mat4Expr declareMat4(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.MAT4, name, defaults);
        return new Mat4Expr(new ShaderIr.Reference(ShaderType.MAT4, name));
    }

    private Mat2Expr declareMat2(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.MAT2, name, defaults);
        return new Mat2Expr(new ShaderIr.Reference(ShaderType.MAT2, name));
    }

    private Mat3Expr declareMat3(ShaderStorage storage, String name, List<Number> defaults) {
        declare(storage, ShaderType.MAT3, name, defaults);
        return new Mat3Expr(new ShaderIr.Reference(ShaderType.MAT3, name));
    }

    private void declare(ShaderStorage storage, ShaderValueType type, String name, List<Number> defaults) {
        requireNewName(name);
        globals.add(new ShaderGlobal(storage, type, name, 0, defaults));
    }

    private void requireNewName(String name) {
        ensureOpen();
        ShaderNames.requireIdentifier(name);
        if (!names.add(name)) throw new IllegalArgumentException("Duplicate shader identifier: " + name);
    }

    protected final void ensureOpen() {
        if (built) throw new IllegalStateException("Shader builder has already been built");
    }

    private static List<Number> matrixDefaults(String type, int size, float[] values) {
        Objects.requireNonNull(values, "defaultValues");
        if (values.length != size) {
            throw new IllegalArgumentException("A " + type + " uniform requires " + size + " default values");
        }
        List<Number> result = new ArrayList<>(size);
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Uniform default must be finite");
            result.add(value);
        }
        return result;
    }

    private ShaderIr.Construct matrixConstruct(
            ShaderType type,
            int size,
            FloatExpr[] values
    ) {
        ensureOpen();
        Objects.requireNonNull(values, "values");
        if (values.length != size) {
            throw new IllegalArgumentException(type.glslName() + " requires " + size + " components");
        }
        List<ShaderIr.Expression> arguments = new ArrayList<>(size);
        for (FloatExpr value : values) {
            arguments.add(Objects.requireNonNull(value, "values contains null").ir());
        }
        return new ShaderIr.Construct(type, arguments);
    }

    private FloatExpr[] floatExpressions(float[] values) {
        Objects.requireNonNull(values, "values");
        FloatExpr[] expressions = new FloatExpr[values.length];
        for (int index = 0; index < values.length; index++) expressions[index] = f32(values[index]);
        return expressions;
    }

    private ShaderIr.RawExpression rawExpression(ShaderValueType type, String source) {
        ensureOpen();
        return new ShaderIr.RawExpression(type, requireRawSource(source, "expression"));
    }

    private static String requireRawSource(String source, String kind) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Raw shader " + kind + " cannot be blank");
        }
        return source;
    }

    private enum BreakTarget {
        LOOP,
        SWITCH
    }
}
