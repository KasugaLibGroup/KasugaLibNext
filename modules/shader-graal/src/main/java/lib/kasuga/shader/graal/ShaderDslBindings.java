package lib.kasuga.shader.graal;

import lib.kasuga.shader.BoolExpr;
import lib.kasuga.shader.FloatArrayExpr;
import lib.kasuga.shader.FloatExpr;
import lib.kasuga.shader.FragmentShaderBuilder;
import lib.kasuga.shader.IntExpr;
import lib.kasuga.shader.Mat2Expr;
import lib.kasuga.shader.Mat3Expr;
import lib.kasuga.shader.Mat4Expr;
import lib.kasuga.shader.Sampler2DExpr;
import lib.kasuga.shader.ShaderExpression;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderParameterRange;
import lib.kasuga.shader.ShaderParameterSchema;
import lib.kasuga.shader.ShaderParameterType;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.ShaderStructField;
import lib.kasuga.shader.ShaderStructType;
import lib.kasuga.shader.ShaderSwitchBuilder;
import lib.kasuga.shader.ShaderType;
import lib.kasuga.shader.ShaderValueType;
import lib.kasuga.shader.ShaderVariable;
import lib.kasuga.shader.StructExpr;
import lib.kasuga.shader.Vec2Expr;
import lib.kasuga.shader.Vec3Expr;
import lib.kasuga.shader.Vec4Expr;
import lib.kasuga.shader.VertexShaderBuilder;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Defines the direct DSL surface without granting access to arbitrary public Java members. */
final class ShaderDslBindings {
    private static final Map<String, Class<?>> GLOBALS = Map.of(
            "Shader", ShaderProgram.class,
            "ShaderParameter", ShaderParameter.class,
            "ShaderParameterType", ShaderParameterType.class,
            "ShaderStructType", ShaderStructType.class,
            "ShaderType", ShaderType.class
    );

    static final HostAccess HOST_ACCESS = hostAccess();

    private ShaderDslBindings() {}

    static boolean allowsClassLookup(String name) {
        return GLOBALS.values().stream().anyMatch(type -> type.getName().equals(name));
    }

    static void install(Context context, Map<String, String> glslSources) {
        context.getBindings("js").putMember("glsl", (ProxyExecutable) arguments -> {
            if (arguments.length != 1 || !arguments[0].isString()) {
                throw new IllegalArgumentException("glsl requires one source name");
            }
            String name = arguments[0].asString();
            String source = glslSources.get(name);
            if (source == null) throw new IllegalArgumentException("Unknown GLSL source: " + name);
            return source;
        });
        StringBuilder declarations = new StringBuilder();
        GLOBALS.forEach((name, type) -> declarations.append("const ").append(name)
                .append(" = Java.type('").append(type.getName()).append("');\n"));
        context.eval("js", declarations.toString());
    }

    private static HostAccess hostAccess() {
        HostAccess.Builder access = HostAccess.newBuilder(HostAccess.NONE)
                .allowImplementations(Consumer.class)
                .allowImplementations(Runnable.class)
                .allowArrayAccess(true)
                .allowListAccess(true)
                // JS numbers are doubles. Match Java's explicit float rounding for values such
                // as 0.1; Graal otherwise rejects these as lossy primitive conversions. Integer
                // conversions stay exact; DSL construction validates non-finite float values.
                .targetTypeMapping(Double.class, Float.class, value -> true, Double::floatValue,
                        HostAccess.TargetMappingPrecedence.LOW);
        List<Class<?>> types = List.of(
                ShaderProgram.class, ShaderProgram.Kind.class,
                ShaderParameter.class, ShaderParameter.Builder.class, ShaderParameterType.class,
                ShaderParameterRange.class, ShaderParameterSchema.class,
                ShaderStructType.class, ShaderStructType.Builder.class, ShaderStructField.class,
                ShaderSwitchBuilder.class, ShaderVariable.class, ShaderValueType.class, ShaderType.class,
                FragmentShaderBuilder.class, VertexShaderBuilder.class, ShaderExpression.class,
                FloatExpr.class, IntExpr.class, BoolExpr.class,
                Vec2Expr.class, Vec3Expr.class, Vec4Expr.class,
                Mat2Expr.class, Mat3Expr.class, Mat4Expr.class,
                StructExpr.class, FloatArrayExpr.class, Sampler2DExpr.class
        );
        for (Class<?> type : types) {
            // getMethods includes inherited builder methods. Object, Enum, reflection and I/O
            // members are deliberately excluded, including Object.getClass().
            for (var method : type.getMethods()) {
                if (method.getDeclaringClass().getPackageName().equals("lib.kasuga.shader")) {
                    access.allowAccess(method);
                }
            }
            for (var field : type.getFields()) {
                if (field.isEnumConstant()) access.allowAccess(field);
            }
        }
        return access.build();
    }
}
