package lib.kasuga.formula.compute.data;

import lib.kasuga.formula.compute.data.functions.DoublePrarmFunction;
import lib.kasuga.formula.compute.data.functions.Function;
import lib.kasuga.formula.compute.data.functions.SingleParamFunction;
import lib.kasuga.formula.compute.data.functions.TripleParamFunction;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.logic.data.LogicalLine;
import lib.kasuga.formula.logic.infrastructure.LogicalData;

import java.util.HashMap;
import java.util.Set;

/**
 * Symbol table: host of the three registries — functions (FUNCTIONS),
 * static read-only variables (STATIC_VARS) and runtime instance variables
 * (INSTANT_VARS).
 *
 * <p>A child namespace copies all parent registrations at construction; later
 * registrations only affect itself. {@link #clone()} performs the same copy and
 * keeps the parent link. Unknown variables are auto-registered into the instance
 * map while expressions are decoded.
 */
public class Namespace {
    /** Function registry (name → template instance). */
    public final HashMap<String, Function> FUNCTIONS;
    /** Static read-only variable registry (e.g. {@code pi}, {@code e}). */
    public final HashMap<String, Variable> STATIC_VARS;
    /** Runtime instance variable registry (unknown names are auto-registered). */
    public final HashMap<String, Assignable> INSTANT_VARS;
    private Namespace parent;

    /** Creates a root namespace without a parent. */
    public Namespace() {
        this(null);
    }

    /**
     * Creates a child namespace: copies the parent's functions, static variables
     * and instance variables; later registrations only affect this namespace.
     *
     * @param parent the parent namespace (may be null for the root)
     */
    public Namespace(Namespace parent) {
        FUNCTIONS = new HashMap<>();
        STATIC_VARS = new HashMap<>();
        INSTANT_VARS = new HashMap<>();
        if(parent != null) {
            this.parent = parent;
            this.FUNCTIONS.putAll(parent.FUNCTIONS);
            this.STATIC_VARS.putAll(parent.STATIC_VARS);
            this.INSTANT_VARS.putAll(parent.INSTANT_VARS);
        }
    }

    /**
     * The parent namespace (null for the root).
     *
     * @return the parent namespace
     */
    public Namespace parent() {
        return parent;
    }

    /**
     * The function table (read-only use).
     *
     * @return the name-to-instance map
     */
    public HashMap<String, Function> functions() {
        return FUNCTIONS;
    }

    /**
     * Merged view of static and instance variables.
     *
     * @return the name-to-assignable map
     */
    public HashMap<String, Assignable> variables() {
        HashMap<String, Assignable> vars = new HashMap<>();
        vars.putAll(STATIC_VARS);
        vars.putAll(INSTANT_VARS);
        return vars;
    }

    /**
     * Registers a function instance.
     *
     * @param <T> the function type
     * @param codec the function name
     * @param function the function instance
     * @return the registered function
     */
    public <T extends Function> T register(String codec, T function) {
        FUNCTIONS.put(codec, function);
        return function;
    }

    /**
     * Registers a single-parameter function.
     *
     * @param codec the function name
     * @param computer the computation logic
     * @return the registered function
     */
    public SingleParamFunction register1Param(String codec, SingleParamFunction.Computer computer) {
        SingleParamFunction function = new SingleParamFunction(codec, this, computer);
        FUNCTIONS.put(codec, function);
        return function;
    }

    /**
     * Registers a two-parameter function.
     *
     * @param codec the function name
     * @param computer the computation logic
     * @return the registered function
     */
    public DoublePrarmFunction register2Param(String codec, DoublePrarmFunction.Computer computer) {
        DoublePrarmFunction function = new DoublePrarmFunction(codec, this, computer);
        FUNCTIONS.put(codec, function);
        return function;
    }

    /**
     * Registers a three-parameter function.
     *
     * @param codec the function name
     * @param computer the computation logic
     * @return the registered function
     */
    public TripleParamFunction register3Param(String codec, TripleParamFunction.Computer computer) {
        TripleParamFunction function = new TripleParamFunction(codec, this, computer);
        FUNCTIONS.put(codec, function);
        return function;
    }

    /**
     * Clones a registered function template (bound to this namespace) for parsing.
     *
     * @param codec the function name
     * @return the function instance, or null if unregistered
     */
    public Function createFunctionInstance(String codec) {
        if(FUNCTIONS.containsKey(codec))
            return FUNCTIONS.get(codec).clone(this);
        return null;
    }


    /**
     * Registers a static read-only variable.
     *
     * @param codec the variable name
     * @param value the constant value
     * @return the registered variable
     */
    public Variable register(String codec, float value) {
        Variable variable = new Variable(codec, this, value);
        STATIC_VARS.put(codec, variable);
        return variable;
    }

    /**
     * Registers a runtime instance variable (called when an expression
     * auto-registers an unknown name).
     *
     * @param codec the variable name
     * @param assignable the assignable instance
     */
    public void registerInstance(String codec, Assignable assignable) {
        this.INSTANT_VARS.put(codec, assignable);
    }

    /**
     * Whether the instance variable table is non-empty.
     *
     * @return true if non-empty
     */
    public boolean hasInstance() {
        return !INSTANT_VARS.isEmpty();
    }

    /**
     * Writes a value to an instance variable by codec; silently ignored when
     * the codec is absent.
     *
     * @param codec the variable name
     * @param value the new value
     */
    public void assign(String codec, float value) {
        if(INSTANT_VARS.containsKey(codec))
            INSTANT_VARS.get(codec).assign(codec, value);
    }

    /**
     * Decodes an arithmetic expression within this namespace.
     *
     * @param formulaString the expression string
     * @return the formula tree
     */
    public Formula decodeFormula(String formulaString) {
        Line line = new Line(formulaString, this);
        Formula formula = line;
        while (formula instanceof Line && formula.getElements().size() == 1) {
            formula =  formula.getElements().get(0);
        }
        return formula;
    }

    /**
     * Decodes a logical expression within this namespace.
     *
     * @param logicalString the logical expression string
     * @return the logical tree
     */
    public LogicalData decodeLogical(String logicalString) {
        LogicalData data = new LogicalLine(logicalString, this);
        while (data instanceof LogicalLine line && !line.isEmpty() && line.isAtomic()) {
            data = line.getFirst();
        }
        return data;
    }

    /**
     * The number of instance variables.
     *
     * @return the instance variable count
     */
    public int instanceVarSize() {
        return INSTANT_VARS.size();
    }

    /**
     * Whether the instance variable table contains the given codec.
     *
     * @param codec the variable name
     * @return true if registered
     */
    public boolean containsInstance(String codec) {
        return INSTANT_VARS.containsKey(codec);
    }

    /**
     * Returns an instance variable.
     *
     * @param codec the variable name
     * @return the instance variable, or null if absent
     */
    public Assignable getInstance(String codec) {
        return INSTANT_VARS.getOrDefault(codec, null);
    }

    /**
     * The set of instance variable codecs.
     *
     * @return the codec set
     */
    public Set<String> instanceNames() {
        return INSTANT_VARS.keySet();
    }

    /**
     * Returns a static read-only variable.
     *
     * @param codec the variable name
     * @return the static variable, or null if absent
     */
    public Variable getStaticVar(String codec) {return STATIC_VARS.getOrDefault(codec, null);}

    /**
     * Returns a registered function.
     *
     * @param codec the function name
     * @return the function, or null if absent
     */
    public Function getFunction(String codec) {
        return FUNCTIONS.getOrDefault(codec, null);
    }

    /**
     * Shallow copy: copies the three registries (element references shared)
     * and keeps the parent link.
     *
     * @return the copy
     */
    @Override
    public Namespace clone() {
        Namespace namespace = new Namespace(this);
        namespace.parent = this.parent;
        return namespace;
    }
}
