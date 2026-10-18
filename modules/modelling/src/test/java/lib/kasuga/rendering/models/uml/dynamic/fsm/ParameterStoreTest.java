package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.MutableStateMap;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameter-store: the {@link ParameterSpec} attributes (externalWritable / sync) and the
 * {@link StateMachine} parameter face — typed get, role-checked set, internal set, declare.
 * Zero behavior change to the pre-existing var/machine semantics (identity by id, type-safe store).
 */
class ParameterStoreTest {

    private static final Id CURRENT_SPEED_ID = Id.fromNamespaceAndPath("kasuga_lib", "fan/current_speed");

    private static final ParameterSpec<Float> CURRENT_SPEED = ParameterSpec.<Float>parameter(
            CURRENT_SPEED_ID, Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .externalWritable(false) // derived
            .build();

    private static final ParameterSpec<Float> DOOR_OPEN = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("kasuga_lib", "door/open"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .validator(v -> v >= 0f && v <= 1f)
            .sync(true)
            .build();

    //region ParameterSpec

    @Test
    void builderDefaultsAreExternalWritableNonSync() {
        ParameterSpec<String> spec = ParameterSpec.<String>parameter(
                Id.fromNamespaceAndPath("kasuga_lib", "plain"), String.class, Codec.STRING)
                .defaultValue("x")
                .build();
        assertTrue(spec.externalWritable());
        assertFalse(spec.sync());
    }

    @Test
    void builderCarriesFlagsAndInheritsVarSemantics() {
        assertFalse(CURRENT_SPEED.externalWritable());
        assertFalse(CURRENT_SPEED.sync());
        assertEquals(0f, CURRENT_SPEED.defaultValue());
        assertTrue(DOOR_OPEN.externalWritable());
        assertTrue(DOOR_OPEN.sync());
        assertTrue(DOOR_OPEN.isValid(0.75f));
        assertFalse(DOOR_OPEN.isValid(1.5f));
    }

    @Test
    void identityIsByIdAloneAcrossSubclasses() {
        StateVar<Float> plain = StateVar.of(CURRENT_SPEED_ID, Float.class, Codec.FLOAT, 0f);
        assertEquals(plain, CURRENT_SPEED);
        assertEquals(plain.hashCode(), CURRENT_SPEED.hashCode());
        assertTrue(CURRENT_SPEED.equals(plain));
    }

    @Test
    void wrapPreservesVarAndDefaultsFlags() {
        StateVar<Float> plain = StateVar.builder(CURRENT_SPEED_ID, Float.class, Codec.FLOAT)
                .defaultValue(7f)
                .validator(v -> v >= 0f)
                .build();
        ParameterSpec<Float> wrapped = ParameterSpec.of(plain);
        assertTrue(wrapped.externalWritable());
        assertFalse(wrapped.sync());
        assertEquals(7f, wrapped.defaultValue());
        assertTrue(wrapped.isValid(3f));
        assertFalse(wrapped.isValid(-1f));
        assertEquals(plain, wrapped); // same id

        ParameterSpec<Float> synced = ParameterSpec.of(plain, false, true);
        assertFalse(synced.externalWritable());
        assertTrue(synced.sync());
    }

    @Test
    void ephemeralFlagIsInherited() {
        StateVar<Boolean> trigger = StateVar.trigger(Id.fromNamespaceAndPath("kasuga_lib", "t"));
        ParameterSpec<Boolean> spec = ParameterSpec.of(trigger);
        assertTrue(spec.ephemeral());
    }

    //endregion

    //region machine parameter face

    @Test
    void getReturnsDefaultUntilSet() {
        StateMachine<Object> machine = StateMachine.builder(new Object()).build();
        machine.declare(CURRENT_SPEED, DOOR_OPEN);
        assertEquals(0f, machine.get(CURRENT_SPEED));
        assertEquals(0f, machine.get(DOOR_OPEN));
    }

    @Test
    void externalSetStoresAndReadsBack() {
        StateMachine<Object> machine = StateMachine.builder(new Object()).build();
        machine.declare(DOOR_OPEN);
        machine.set(DOOR_OPEN, 0.75f);
        assertEquals(0.75f, machine.get(DOOR_OPEN));
    }

    @Test
    void externalSetRejectsDerivedParameter() {
        StateMachine<Object> machine = StateMachine.builder(new Object()).build();
        machine.declare(CURRENT_SPEED);
        assertThrows(IllegalStateException.class, () -> machine.set(CURRENT_SPEED, 360f));
        assertEquals(0f, machine.get(CURRENT_SPEED)); // unchanged
    }

    @Test
    void internalSetBypassesExternalWritable() {
        StateMachine<Object> machine = StateMachine.builder(new Object()).build();
        machine.declare(CURRENT_SPEED);
        machine.setInternal(CURRENT_SPEED, 540f);
        assertEquals(540f, machine.get(CURRENT_SPEED));
    }

    @Test
    void setStillValidatesAgainstSpecValidator() {
        StateMachine<Object> machine = StateMachine.builder(new Object()).build();
        machine.declare(DOOR_OPEN);
        assertThrows(IllegalArgumentException.class, () -> machine.set(DOOR_OPEN, 2f));
    }

    @Test
    void declaredVarsIncludesSpecsAndSyncFlagIsVisible() {
        StateMachine<Object> machine = StateMachine.builder(new Object()).build();
        machine.declare(CURRENT_SPEED, DOOR_OPEN);
        assertTrue(machine.declaredVars().contains(CURRENT_SPEED));
        assertTrue(machine.declaredVars().contains(DOOR_OPEN));
        long syncCount = machine.declaredVars().stream()
                .filter(v -> v instanceof ParameterSpec<?> spec && spec.sync())
                .count();
        assertEquals(1, syncCount); // only DOOR_OPEN
    }

    @Test
    void typeSafetyHoldsThroughParameterSpec() {
        StateMachine<Object> machine = StateMachine.builder(new Object()).build();
        ParameterSpec<Integer> intVar = ParameterSpec.<Integer>parameter(
                CURRENT_SPEED_ID, Integer.class, Codec.INT)
                .defaultValue(0)
                .build();
        machine.declare(CURRENT_SPEED);
        machine.setInternal(CURRENT_SPEED, 360f);
        // same id, different type — the store rejects the collision (same rule as MutableStateMap)
        assertThrows(IllegalStateException.class, () -> machine.set(intVar, 1));
    }

    @Test
    void storeEqualityByIdAllowsMixedGet() {
        MutableStateMap map = MutableStateMap.create();
        map.set(CURRENT_SPEED, 120f);
        StateVar<Float> plain = StateVar.of(CURRENT_SPEED_ID, Float.class, Codec.FLOAT, 0f);
        assertEquals(120f, map.get(plain)); // a plain var reads a value stored under a ParameterSpec
    }

    //endregion
}