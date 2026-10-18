package lib.kasuga.formula;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.data.functions.DoublePrarmFunction;
import lib.kasuga.formula.compute.data.functions.Function;
import lib.kasuga.formula.compute.data.Line;
import lib.kasuga.formula.compute.data.functions.SingleParamFunction;
import lib.kasuga.formula.compute.data.Variable;
import lib.kasuga.formula.compute.data.functions.TripleParamFunction;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.logic.data.LogicalLine;
import lib.kasuga.formula.logic.infrastructure.LogicalData;

import java.util.HashMap;

/**
 * Static facade and built-in registry of the formula engine.
 *
 * <p>Owns the root namespace ({@link #ROOT_NAMESPACE}), where all built-in
 * single- and two-parameter functions (trigonometry, logarithms, power,
 * rounding, ...) and the static constants {@code pi} and {@code e} are
 * registered. Provides the expression decode/encode entry points
 * ({@link #decodeFormula}, {@link #encodeFormula}, {@link #decodeLogical},
 * {@link #encodeLogical}) and custom registration methods
 * ({@link #register1Param(String, lib.kasuga.formula.compute.data.functions.SingleParamFunction.Computer)},
 * {@link #register2Param(String, lib.kasuga.formula.compute.data.functions.DoublePrarmFunction.Computer)},
 * {@link #register3Param(String, lib.kasuga.formula.compute.data.functions.TripleParamFunction.Computer)}).
 */
public class Code {
    /** Utility class: not instantiable. */
    private Code() {}

    /**
     * Root namespace where built-in functions and constants are registered;
     * new namespaces default to it as their parent.
     */
    public static final Namespace ROOT_NAMESPACE = new Namespace();

    // -------------------------------------------------------------------------------------------------------- //

    /** Cosine (radians). */
    public static final SingleParamFunction COS = register1Param("cos", (in) -> (float) Math.cos(in));
    /** Sine (radians). */
    public static final SingleParamFunction SIN = register1Param("sin", (in) -> (float) Math.sin(in));
    /** Tangent (radians). */
    public static final SingleParamFunction TAN = register1Param("tan", (in) -> (float) Math.tan(in));
    /** Arc sine (returns radians). */
    public static final SingleParamFunction ASIN = register1Param("asin", (in) -> (float) Math.asin(in));
    /** Arc cosine (returns radians). */
    public static final SingleParamFunction ACOS = register1Param("acos", (in) -> (float) Math.acos(in));
    /** Arc tangent (returns radians). */
    public static final SingleParamFunction ATAN = register1Param("atan", (in) -> (float) Math.atan(in));
    /** Natural logarithm. */
    public static final SingleParamFunction LOG = register1Param("log", (in) -> (float) Math.log(in));
    /** Common logarithm (log10). */
    public static final SingleParamFunction LG = register1Param("lg", (in) -> (float) Math.log10(in));
    /** Exponential (e^x). */
    public static final SingleParamFunction EXP = register1Param("exp", (in) -> (float) Math.exp(in));
    /** Rounds to the nearest integer. */
    public static final SingleParamFunction ROUND = register1Param("round", Math::round);
    /** Square root. */
    public static final SingleParamFunction SQRT = register1Param("sqrt", (in1) -> (float) Math.sqrt(in1));
    /** Converts degrees to radians. */
    public static final SingleParamFunction DEG2RAD = register1Param("rad", (in1) -> (float) Math.toRadians(in1));
    /** Converts radians to degrees. */
    public static final SingleParamFunction RAD2DEG = register1Param("deg", (in1) -> (float) Math.toDegrees(in1));
    /** Floor. */
    public static final SingleParamFunction FLOOR = register1Param("floor", (in) -> (float) Math.floor(in));
    /** Ceiling. */
    public static final SingleParamFunction CEIL = register1Param("ceil", (in) -> (float) Math.ceil(in));

    // -------------------------------------------------------------------------------------------------------- //

    /** Power (a^b). */
    public static final DoublePrarmFunction POW = register2Param("pow", (in1, in2) -> (float) Math.pow(in1, in2));
    /** Maximum of two values. */
    public static final DoublePrarmFunction MAX = register2Param("max", Math::max);
    /** Minimum of two values. */
    public static final DoublePrarmFunction MIN = register2Param("min", Math::min);

    // -------------------------------------------------------------------------------------------------------- //

    /** The constant pi. */
    public static final Variable PI = register("pi", (float) Math.PI);
    /** The constant e. */
    public static final Variable E = register("e", (float) Math.E);

    // -------------------------------------------------------------------------------------------------------- //

    /**
     * Registers a function instance into the root namespace and returns it for
     * chaining.
     *
     * @param <T> the function type
     * @param codec the function name (the identifier used in expressions)
     * @param function the function instance
     * @return the registered function
     */
    public static <T extends Function> T register(String codec, T function) {
        return ROOT_NAMESPACE.register(codec, function);
    }

    /**
     * Registers a single-parameter function (e.g. {@code sin}, {@code sqrt}).
     *
     * @param codec the function name
     * @param computer the single-argument computation logic
     * @return the registered function
     */
    public static SingleParamFunction register1Param(String codec, SingleParamFunction.Computer computer) {
        return ROOT_NAMESPACE.register1Param(codec, computer);
    }

    /**
     * Registers a two-parameter function (e.g. {@code pow}, {@code max}).
     *
     * @param codec the function name
     * @param computer the two-argument computation logic
     * @return the registered function
     */
    public static DoublePrarmFunction register2Param(String codec, DoublePrarmFunction.Computer computer) {
        return ROOT_NAMESPACE.register2Param(codec, computer);
    }

    /**
     * Registers a three-parameter function (not pre-registered; for extension).
     *
     * @param codec the function name
     * @param computer the three-argument computation logic
     * @return the registered function
     */
    public static TripleParamFunction register3Param(String codec, TripleParamFunction.Computer computer) {
        return ROOT_NAMESPACE.register3Param(codec, computer);
    }

    /**
     * Registers a static read-only variable (e.g. {@code pi}, {@code e}).
     *
     * @param codec the variable name
     * @param value the constant value
     * @return the registered variable
     */
    public static Variable register(String codec, float value) {
        return ROOT_NAMESPACE.register(codec, value);
    }

    /**
     * Returns a static read-only variable, or null when absent.
     *
     * @param codec the variable name
     * @return the variable instance, or null
     */
    public static Variable getStaticVar(String codec) {return ROOT_NAMESPACE.getStaticVar(codec);}

    /**
     * Returns a registered function, or null when absent.
     *
     * @param codec the function name
     * @return the function instance, or null
     */
    public static Function getFunction(String codec) {
        return ROOT_NAMESPACE.getFunction(codec);
    }

    /**
     * All functions of the root namespace (read-only use).
     *
     * @return the name-to-instance map
     */
    public static HashMap<String, Function> getFunctions() {
        return ROOT_NAMESPACE.FUNCTIONS;
    }

    /**
     * All static variables of the root namespace (read-only use).
     *
     * @return the name-to-instance map
     */
    public static HashMap<String, Variable> getStaticVars() {return ROOT_NAMESPACE.STATIC_VARS;}

    /**
     * Decodes an arithmetic expression into a
     * {@link lib.kasuga.formula.compute.infrastructure.Formula} tree; a
     * single-element line is unwrapped into its inner element. Unknown variables
     * are registered into the namespace during parsing.
     *
     * @param formulaString the expression string
     * @param namespace the namespace used for evaluation and registration
     * @return the formula tree
     */
    public static Formula decodeFormula(String formulaString, Namespace namespace) {
        Line line = new Line(namespace);
        line.fromString(formulaString);
        Formula formula = line;
        while (formula instanceof Line && formula.getElements().size() == 1) {
            formula =  formula.getElements().get(0);
        }
        return formula;
    }
    /**
     * Encodes a formula tree to a string ({@link Formula#getString()}).
     *
     * @param formula the formula tree
     * @return the expression string
     */
    public static String encodeFormula(Formula formula) {
        return formula.getString();
    }

    /**
     * Decodes a logical expression into a
     * {@link lib.kasuga.formula.logic.infrastructure.LogicalData} tree; a
     * single-element line is unwrapped into its inner element.
     *
     * @param logicalString the logical expression string
     * @param namespace the namespace used for evaluation and registration
     * @return the logical tree
     */
    public static LogicalData decodeLogical(String logicalString, Namespace namespace) {
        LogicalData data = new LogicalLine(logicalString, namespace);
        while (data instanceof LogicalLine line && !line.isEmpty() && line.isAtomic()) {
            data = line.getFirst();
        }
        return data;
    }

    /**
     * Encodes a logical tree to a string.
     *
     * @param data the logical tree
     * @return the logical expression string
     */
    public static String encodeLogical(LogicalData data) {
        return data.toString();
    }

    /**
     * The root namespace.
     *
     * @return the root namespace
     */
    public static Namespace root() {
        return ROOT_NAMESPACE;
    }
}