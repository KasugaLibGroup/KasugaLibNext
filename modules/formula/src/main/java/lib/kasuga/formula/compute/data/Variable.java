package lib.kasuga.formula.compute.data;

import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;

import java.util.*;

/**
 * Atomic variable: named by a {@code codec} (may contain dot-scoped segments
 * such as {@code query.anim_time}) and holding a mutable float value.
 *
 * <p>An unknown name used in an expression is auto-created by the line parser and
 * registered in the {@link lib.kasuga.formula.compute.data.Namespace} instance map
 * (initial value 0); it can then be re-assigned via {@code namespace.assign(codec, value)}.
 * {@link #appendCodec(String)} appends a dot-suffixed segment (used by dotted-name
 * resolution).
 */
public class Variable implements Formula, Assignable {
    private String codec;
    private float value;
    private boolean flip = false;
    private final Namespace namespace;

    /**
     * Creates a variable.
     *
     * @param codec the variable name
     * @param namespace the owning namespace
     */
    public Variable(String codec, Namespace namespace) {
        this.codec = codec;
        this.namespace = namespace;
    }

    /**
     * Creates a variable with an initial value.
     *
     * @param codec the variable name
     * @param namespace the owning namespace
     * @param value the initial value
     */
    public Variable(String codec, Namespace namespace, float value) {
        this(codec, namespace);
        this.value = value;
    }

    /**
     * Appends a dot-suffixed segment (used by dotted-name resolution).
     *
     * @param append the suffix segment to append
     * @return the resulting full codec
     */
    public String appendCodec(String append) {
        if(!append.equals("")) codec = codec + "." + append;
        return codec;
    }

    /**
     * Whether the string is a valid variable name (lowercase letter first,
     * digits and underscores allowed afterwards).
     *
     * @param input the string to test
     * @return true if it is a variable name
     */
    public static boolean isVar(String input) {
        return input.replaceAll("([a-z])([a-z1-9])*(_([a-z1-9]+))*", "").equals("");
    }

    @Override
    public String getString() {
        return (flip ? "-" : "") + codec;
    }

    @Override
    public String getIdentifier() {
        return "var";
    }

    @Override
    public float getResult() {
        return flip ? - value : value;
    }

    @Override
    public List<Formula> getElements() {
        return List.of(this);
    }

    @Override
    public boolean isAtomic() {
        return true;
    }

    @Override
    public boolean shouldRemove() {
        return false;
    }

    @Override
    public void fromString(String string) {
        if(isVar(string)) this.codec = string;
    }

    @Override
    public Formula clone() {
        return new Variable(this.codec, this.namespace, this.value);
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
    public Namespace getNamespace() {
        return namespace;
    }

    @Override
    public Set<String> variableCodecs() {
        return Set.of(this.codec);
    }

    @Override
    public void assign(String codec, float value) {
        if(codec.equals(this.codec))
            this.value = value;
    }

    @Override
    public boolean containsVar(String codec) {
        return codec.equals(this.codec);
    }

    @Override
    public float getValue(String codec) {
        return getResult();
    }

    @Override
    public boolean hasVar() {
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Variable variable)) return false;
        return variable.codec.equals(codec);
    }
}
