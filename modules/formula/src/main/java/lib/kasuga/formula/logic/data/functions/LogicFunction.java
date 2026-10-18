package lib.kasuga.formula.logic.data.functions;

import lib.kasuga.formula.logic.infrastructure.LogicalData;

/**
 * Abstract base for logical functions (reserved extension point): logical
 * expressions currently have no built-in functions; custom logical predicates
 * can be hooked in here later.
 */
public abstract class LogicFunction implements LogicalData {

    /** Constructor for subclasses. */
    protected LogicFunction() {}

    @Override
    public abstract LogicFunction clone();
}