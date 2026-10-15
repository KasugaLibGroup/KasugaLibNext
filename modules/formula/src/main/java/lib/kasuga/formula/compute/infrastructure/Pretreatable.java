package lib.kasuga.formula.compute.infrastructure;

/**
 * Marker for elements that need normalization after parsing: lines and
 * functions implement it, and the parser invokes {@link #preTreatment()} on them.
 */
public interface Pretreatable {
    /**
     * Normalization step after parsing: merges adjacent operators, applies the
     * unary-minus flip, and recursively pre-treats children. Called by the line
     * parser before evaluation.
     */
    void preTreatment();
}