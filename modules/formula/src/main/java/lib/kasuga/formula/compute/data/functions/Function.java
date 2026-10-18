package lib.kasuga.formula.compute.data.functions;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.compute.data.Line;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;
import lib.kasuga.formula.compute.infrastructure.Pretreatable;

import java.util.*;

/**
 * Abstract base for functions: holds the parameter list ({@code params}) and
 * the owning namespace.
 *
 * <p>Parsing splits arguments on commas ({@code ,}; commas inside parentheses
 * are not split), each argument is parsed as an independent line and collected
 * into the parameter table; {@link #operate()} is implemented by subclasses.
 * This class also implements {@link lib.kasuga.formula.compute.infrastructure.Assignable}
 * and {@link lib.kasuga.formula.compute.infrastructure.Pretreatable} (recursively
 * pre-treating parameters).
 */
public abstract class Function implements Formula, Assignable, Pretreatable {
    final String codec;
    /** The parameter list (filled during parsing or construction). */
    public final List<Formula> params;
    final Namespace namespace;
    boolean flip = false;
    /** Argument separator: comma. */
    public static final String DOTS = ",";
    /**
     * Creates a function without parameters.
     *
     * @param codec the function name
     * @param namespace the owning namespace
     */
    public Function(String codec, Namespace namespace) {
        this.codec = codec;
        params = new ArrayList<>();
        this.namespace = namespace;
    }

    /**
     * Creates a function with the given parameter list.
     *
     * @param codec the function name
     * @param namespace the owning namespace
     * @param params the parameter list
     */
    public Function(String codec, Namespace namespace, List<Formula> params) {
        this.codec = codec;
        this.params = params;
        this.namespace = namespace;
    }

    /**
     * Creates a function with varargs parameters.
     *
     * @param codec the function name
     * @param namespace the owning namespace
     * @param params the varargs
     */
    public Function(String codec, Namespace namespace, Formula... params) {
        this.codec = codec;
        this.params = new ArrayList<>(List.of(params));
        this.namespace = namespace;
    }

    /**
     * Creates a function from a comma-separated parameter string (parsed as
     * lines internally).
     *
     * @param codec the function name
     * @param namespace the owning namespace
     * @param paramString the comma-separated parameter string
     */
    public Function(String codec, Namespace namespace, String paramString) {
        this(codec, namespace);
        fromString(paramString);
    }

    /**
     * The function name.
     *
     * @return the function name
     */
    public String getCodec() {
        return codec;
    }

    public String toString() {
        return getString();
    }

    /**
     * The function expression string, including its parameters
     * (e.g. {@code pow(2, 10)}).
     *
     * @return the expression string
     */
    @Override
    public String getString() {
        StringBuilder builder = new StringBuilder();
        if(flip)
            builder.append("-");
        builder.append(codec).append(FRONT_BRACKET_CODEC);
        if(params.size() == 1) {
            builder.append(params.get(0).getString());
        } else {
            for (Formula formula : params) {
                if (params.indexOf(formula) == params.size() - 1)
                    builder.append(formula.getString());
                else
                    builder.append(formula.getString()).append(", ");
            }
        }
        return builder.append(BACK_BRACKET_CODEC).toString();
    }

    /**
     * The number of parameters.
     *
     * @return the parameter count
     */
    public int paramListLength() {
        return params.size();
    }


    @Override
    public String getIdentifier() {
        return "function";
    }

    @Override
    public float getResult() {
        return flip ? - operate() : operate();
    }

    /**
     * Actual computation logic, implemented by subclasses.
     *
     * @return the result
     */
    public abstract float operate();

    @Override
    public List<Formula> getElements() {
        return List.of(this);
    }

    @Override
    public boolean isAtomic() {
        return false;
    }

    @Override
    public boolean shouldRemove() {
        return false;
    }

    @Override
    public void flipOutput(boolean flip) {
        this.flip = flip;
    }

    @Override
    public boolean isOutputFlipped() {
        return flip;
    }

    @Override
    public void fromString(String string) {
        String str = string.replaceAll(" ", "");
        Integer[] dots = getAllDots(string);
        if(dots.length == 0) {
            addParamsFromLine(new Line(str, namespace));
        } else if (dots.length == 1) {
            if(dots[0] == str.length() - 1)
                throw new FormulaSyntaxError(this, dots[0]);
            String front = string.substring(0, dots[0]);
            String back = string.substring(dots[0] + 1);
            addParamsFromLine(new Line(front, namespace));
            addParamsFromLine(new Line(back, namespace));
        } else {
            String param = string.substring(0, dots[0]);
            addParamsFromLine(new Line(param, namespace));
            for(int i = 0; i < dots.length - 1; i++) {
                param = string.substring(dots[i] + 1, dots[i + 1]);
                addParamsFromLine(new Line(param, namespace));
            }
            param = string.substring(dots[dots.length - 1] + 1);
            addParamsFromLine(new Line(param, namespace));
        }
    }

    /**
     * Collects a parsed line into the parameter table (an atomic line yields its
     * inner element; otherwise the line itself is stored).
     *
     * @param line the parameter line
     */
    void addParamsFromLine(Line line) {
        if(line.isAtomic() && !line.shouldRemove()) {
            this.params.add(line.getElements().get(0));
        } else if (!line.shouldRemove()) {
            this.params.add(line);
        }
    }

    Integer[] getAllDots(String paramString) {
        if(!paramString.contains(DOTS)) return new Integer[0];
        int counter = 0;
        char dots = DOTS.charAt(0);
        ArrayList<Integer> result = new ArrayList<>();
        for(int i = 0; i < paramString.length(); i++) {
            char regex = paramString.charAt(i);
            if(regex == '(') {
                counter++;
            } else if (regex == ')') {
                counter--;
            } else if (regex == dots && counter == 0) {
                result.add(i);
            }
        }
        return result.toArray(new Integer[0]);
    }

    /**
     * Clones this function.
     *
     * @return the copy bound to the original namespace
     */
    public abstract Function clone();
    /**
     * Clones this function and binds the copy to a new namespace.
     *
     * @param newNamespace the new namespace
     * @return the copy
     */
    public abstract Function clone(Namespace newNamespace);

    @Override
    public Set<String> variableCodecs() {
        return namespace.instanceNames();
    }

    @Override
    public void assign(String codec, float value) {
        if(namespace.containsInstance(codec))
            namespace.getInstance(codec).assign(codec, value);
    }

    @Override
    public boolean containsVar(String codec) {
        return namespace.containsInstance(codec);
    }

    @Override
    public float getValue(String codec) {
        if(!containsVar(codec)) throw new FormulaSyntaxError(this, 0);
        return namespace.getInstance(codec).getValue(codec);
    }

    @Override
    public boolean hasVar() {
        return namespace.instanceVarSize() > 0;
    }

    /**
     * Recursively pre-treats all parameters.
     */
    @Override
    public void preTreatment() {
        for(Formula formula : params) {
            if(formula instanceof Pretreatable pretreatable)
                pretreatable.preTreatment();
        }
    }

    /**
     * The parameter list.
     *
     * @return the parameter list
     */
    public List<Formula> getParams() {
        return params;
    }

    /**
     * The number of parameters (same as {@link #paramListLength()}).
     *
     * @return the parameter count
     */
    public int paramCount() {
        return params.size();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Function function)) return false;
        return function.toString().equals(toString());
    }

    @Override
    public Namespace getNamespace() {
        return namespace;
    }
}