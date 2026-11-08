package lib.kasuga.rendering.models.uml.dynamic.fsm;

import java.util.Map;

/**
 * Per-entity formula variable provider. {@link FsmPoseEvaluator} invokes it on the logical thread once
 * after each committed state-machine step. It derives parameter values from machine state and writes
 * them back through the machine-internal write path, then exposes the step values through {@code out},
 * keyed by the formula name <em>without</em> the {@code "query."} prefix.
 *
 * <p>The provider is the machine-internal writer of derived ({@code externalWritable=false}) parameters:
 * it uses {@link StateMachine#setInternal} so the derived-parameter guard is bypassed (external consumers
 * cannot write them). {@code out} is a fresh map each tick; the evaluator assigns it to the formula
 * {@link lib.kasuga.formula.compute.data.Namespace} before sampling that logical step's result.
 *
 * <p>Default (no provider) behaves exactly like the pre-G2 driver: {@code out} stays empty, so formula
 * tracks fall back to their identity values — zero behavior change for existing users.
 */
public interface VarProvider {

    /**
     * Compute derived parameters for one game tick and project their render values.
     *
     * @param machine the driven state machine (main-thread owned; safe to read state / write vars)
     * @param dt      seconds since the last tick
     * @param out     destination map for the render projection — keys are formula {@code query.*} names
     *                without the {@code "query."} prefix (e.g. {@code "speed"}), values are {@code float}s
     */
    void provide(StateMachine<?> machine, float dt, Map<String, Float> out);
}
