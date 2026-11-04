package lib.kasuga.shader.graal;

import lib.kasuga.shader.ShaderProgram;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Executes JavaScript definitions against the existing typed Java shader DSL.
 * Each call owns and closes its context; returned programs contain no guest callbacks.
 * Instances are immutable and may be shared between threads.
 */
public final class GraalShaderCompiler {
    /** Default guest statement budget for one shader definition. */
    public static final long DEFAULT_STATEMENT_LIMIT = 1_000_000;

    private final long statementLimit;

    /** Creates a compiler with {@link #DEFAULT_STATEMENT_LIMIT}. */
    public GraalShaderCompiler() {
        this(DEFAULT_STATEMENT_LIMIT);
    }

    /**
     * Creates a compiler with a positive guest statement budget per compilation.
     * This is a construction-time guard, not a wall-clock or memory limit.
     *
     * @param statementLimit maximum executed JavaScript statements, including DSL bindings
     */
    public GraalShaderCompiler(long statementLimit) {
        if (statementLimit <= 0) throw new IllegalArgumentException("Statement limit must be positive");
        this.statementLimit = statementLimit;
    }

    /**
     * Reads a UTF-8 definition. The path is retained in script diagnostics.
     *
     * @param source shader definition file
     * @return the constructed program
     * @throws IOException if the source cannot be read
     * @throws ShaderScriptException if evaluation or shader validation fails
     */
    public ShaderProgram compile(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        return compile(source.toString(), Files.readString(source, StandardCharsets.UTF_8));
    }

    /**
     * Evaluates a definition whose final expression must be a {@link ShaderProgram}.
     * Globals {@code Shader}, {@code ShaderParameter}, {@code ShaderParameterType},
     * {@code ShaderStructType} and {@code ShaderType} expose the Java DSL directly.
     * JavaScript control flow executes immediately; use builder control flow for GLSL.
     *
     * @param sourceName diagnostic source name, such as {@code screen-tint.js}
     * @param source JavaScript source text
     * @return a program that remains usable after this method closes the context
     * @throws ShaderScriptException if evaluation, validation or the statement budget fails,
     *                               or the result is not a program
     */
    public ShaderProgram compile(String sourceName, CharSequence source) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        try (Context context = Context.newBuilder("js")
                .allowHostAccess(ShaderDslBindings.HOST_ACCESS)
                .allowHostClassLookup(ShaderDslBindings::allowsClassLookup)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .resourceLimits(ResourceLimits.newBuilder()
                        .statementLimit(statementLimit, null).build())
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            ShaderDslBindings.install(context);
            Value result = context.eval(Source.newBuilder("js", source, sourceName).buildLiteral());
            if (result.isHostObject() && result.asHostObject() instanceof ShaderProgram program) {
                return program;
            }
            throw new ShaderScriptException(sourceName, 0, 0,
                    "Shader script must evaluate to a ShaderProgram as its final expression", null);
        } catch (PolyglotException exception) {
            throw ShaderScriptException.from(sourceName, exception);
        }
    }
}
