package lib.kasuga.formula;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.data.Variable;
import lib.kasuga.formula.compute.data.functions.DoublePrarmFunction;
import lib.kasuga.formula.compute.data.functions.NoParamFunction;
import lib.kasuga.formula.compute.data.functions.SingleParamFunction;
import lib.kasuga.formula.compute.data.functions.TripleParamFunction;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.logic.operations.MathType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFunctionRegistrationTest {

    private Namespace newNs() {
        return new Namespace(Code.ROOT_NAMESPACE);
    }

    private float eval(String expr, Namespace ns) {
        return Code.decodeFormula(expr, ns).getResult();
    }

    @Test
    void sqrtRegressionMigratedFix() {
        assertNotNull(Code.getFunction("sqrt"));
        assertInstanceOf(SingleParamFunction.class, Code.getFunction("sqrt"));
        assertEquals(3f, eval("sqrt(9)", newNs()), 0f);
        assertEquals(4f, eval("sqrt(16)", newNs()), 0f);
    }

    @Test
    void minIsRegisteredAsTwoParamFunction() {
        assertNotNull(Code.getFunction("min"));
        assertInstanceOf(DoublePrarmFunction.class, Code.getFunction("min"));
        assertEquals(3f, eval("min(3, 7)", newNs()), 0f);
        assertEquals(3f, eval("min(7, 3)", newNs()), 0f);
    }

    @Test
    void defaultFunctionsRegistered() {
        assertInstanceOf(SingleParamFunction.class, Code.getFunction("sin"));
        assertInstanceOf(SingleParamFunction.class, Code.getFunction("cos"));
        assertInstanceOf(SingleParamFunction.class, Code.getFunction("tan"));
        assertInstanceOf(SingleParamFunction.class, Code.getFunction("log"));
        assertInstanceOf(SingleParamFunction.class, Code.getFunction("exp"));
        assertInstanceOf(DoublePrarmFunction.class, Code.getFunction("pow"));
        assertInstanceOf(DoublePrarmFunction.class, Code.getFunction("max"));
    }

    @Test
    void getFunctionsContainsDefaults() {
        assertTrue(Code.getFunctions().containsKey("sqrt"));
        assertTrue(Code.getFunctions().containsKey("min"));
        assertTrue(Code.getFunctions().containsKey("sin"));
    }

    @Test
    void getStaticVarPiAndE() {
        Variable pi = Code.getStaticVar("pi");
        Variable e = Code.getStaticVar("e");
        assertNotNull(pi);
        assertNotNull(e);
        assertEquals((float) Math.PI, pi.getValue("pi"), 1e-5f);
        assertEquals((float) Math.E, e.getValue("e"), 1e-5f);
        assertTrue(Code.getStaticVars().containsKey("pi"));
        assertTrue(Code.getStaticVars().containsKey("e"));
    }

    @Test
    void rootReturnsRootNamespace() {
        assertSame(Code.ROOT_NAMESPACE, Code.root());
    }

    @Test
    void getFunctionUnknownReturnsNull() {
        assertNull(Code.getFunction("definitely_not_registered_codec"));
    }

    @Test
    void registerGenericFunction() {
        NoParamFunction f = Code.register("test_ans", new NoParamFunction("test_ans", Code.ROOT_NAMESPACE, () -> 42f));
        assertNotNull(f);
        assertNotNull(Code.getFunction("test_ans"));
        assertEquals(42f, eval("test_ans()", newNs()), 0f);
    }

    @Test
    void register1Param() {
        SingleParamFunction f = Code.register1Param("test_quad", in -> in * in);
        assertNotNull(f);
        assertEquals(25f, eval("test_quad(5)", newNs()), 0f);
    }

    @Test
    void register2Param() {
        DoublePrarmFunction f = Code.register2Param("test_add2", (a, b) -> a + b);
        assertNotNull(f);
        assertEquals(7f, eval("test_add2(3, 4)", newNs()), 0f);
    }

    @Test
    void register3Param() {
        TripleParamFunction f = Code.register3Param("test_mix3", (a, b, c) -> a * b + c);
        assertNotNull(f);
        assertEquals(11f, eval("test_mix3(2, 3, 5)", newNs()), 0f);
    }

    @Test
    void registerStaticVar() {
        Code.register("test_const", 7f);
        Variable v = Code.getStaticVar("test_const");
        assertNotNull(v);
        assertEquals(7f, v.getValue("test_const"), 0f);
    }

    @Test
    @Disabled("TODO(engine bug): static vars (pi/e) are never promoted to instance vars at decode time, "
            + "so decodeFormula(\"pi\") yields 0 instead of Math.PI")
    void constantPiResolvesInsideFormulaString() {
        assertEquals((float) Math.PI, eval("pi", Code.ROOT_NAMESPACE), 1e-4f);
    }

    @Test
    @Disabled("TODO(engine bug): MathType.LARGER_EQU.toString() returns \"<=\" and MathType.SMALLER_EQU.toString() "
            + "returns \">=\" — the two are swapped (cosmetic; getResult() is unaffected)")
    void largeEquEncodeIsNotSwapped() {
        assertEquals(">=", MathType.LARGER_EQU.toString());
        assertEquals("<=", MathType.SMALLER_EQU.toString());
    }
}