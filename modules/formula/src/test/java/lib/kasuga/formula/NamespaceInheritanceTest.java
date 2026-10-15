package lib.kasuga.formula;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.logic.infrastructure.LogicalData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceInheritanceTest {

    @Test
    void childInheritsParentFunctions() {
        Namespace parent = new Namespace(Code.ROOT_NAMESPACE);
        parent.register3Param("test_clamp", (v, lo, hi) -> Math.max(lo, Math.min(v, hi)));

        Namespace child = new Namespace(parent);
        Formula f = child.decodeFormula("test_clamp(15, 0, 10)");
        assertEquals(10f, f.getResult(), 0f);
        assertNotNullIn(child, "test_clamp");
    }

    @Test
    void childInheritsParentStaticVars() {
        Namespace parent = new Namespace(Code.ROOT_NAMESPACE);
        parent.register("test_base", 10f);

        Namespace child = new Namespace(parent);
        assertEquals(10f, child.getStaticVar("test_base").getValue("test_base"), 0f);
        assertTrue(child.STATIC_VARS.containsKey("test_base"));
    }

    @Test
    void childNamespaceUsesOwnCopiedMaps() {
        Namespace parent = new Namespace(Code.ROOT_NAMESPACE);
        Namespace child = new Namespace(parent);
        assertNotSame(parent.FUNCTIONS, child.FUNCTIONS);
        assertNotSame(parent.STATIC_VARS, child.STATIC_VARS);
        assertNotSame(parent.INSTANT_VARS, child.INSTANT_VARS);
    }

    @Test
    void childAssignDoesNotAffectParent() {
        Namespace parent = new Namespace(Code.ROOT_NAMESPACE);
        Namespace child = new Namespace(parent);

        child.decodeFormula("x + 1");
        assertTrue(child.containsInstance("x"));
        child.assign("x", 5f);
        assertEquals(5f, child.getInstance("x").getValue("x"), 0f);

        assertFalse(parent.containsInstance("x"));
        assertEquals(0, parent.instanceVarSize());
    }

    @Test
    void childSeesParentRegistrationsMadeBeforeConstruction() {
        Namespace parent = new Namespace(Code.ROOT_NAMESPACE);
        parent.register("test_early", 3f);
        parent.register2Param("test_early_add", (a, b) -> a + b);

        Namespace child = new Namespace(parent);
        assertEquals(3f, child.getStaticVar("test_early").getValue("test_early"), 0f);
        assertEquals(5f, child.decodeFormula("test_early_add(2, 3)").getResult(), 0f);
    }

    @Test
    void childDoesNotSeeParentRegistrationsMadeAfterConstruction() {
        Namespace parent = new Namespace(Code.ROOT_NAMESPACE);
        Namespace child = new Namespace(parent);

        parent.register("test_late", 3f);
        parent.register2Param("test_late_add", (a, b) -> a + b);

        assertFalse(child.STATIC_VARS.containsKey("test_late"));
        assertFalse(child.functions().containsKey("test_late_add"));
    }

    @Test
    void cloneKeepsParentAndCopiesState() {
        Namespace parent = new Namespace(Code.ROOT_NAMESPACE);
        parent.register("test_c", 1f);
        Namespace original = new Namespace(parent);

        Namespace cloned = original.clone();
        assertSame(parent, cloned.parent());
        assertSame(original.parent(), cloned.parent());
        assertEquals(1f, cloned.getStaticVar("test_c").getValue("test_c"), 0f);
    }

    @Test
    void decodeFormulaViaNamespaceInstanceMethod() {
        Namespace ns = new Namespace(Code.ROOT_NAMESPACE);
        Formula f = ns.decodeFormula("2 + 3");
        assertEquals(5f, f.getResult(), 0f);
    }

    @Test
    void decodeLogicalViaNamespaceInstanceMethod() {
        Namespace ns = new Namespace(Code.ROOT_NAMESPACE);
        LogicalData d = ns.decodeLogical("a > 3");
        ns.assign("a", 4f);
        assertTrue(d.getResult());
    }

    private static void assertNotNullIn(Namespace ns, String codec) {
        assertTrue(ns.functions().containsKey(codec), "function " + codec + " should be present in namespace");
    }
}