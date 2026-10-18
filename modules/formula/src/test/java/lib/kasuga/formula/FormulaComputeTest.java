package lib.kasuga.formula;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaComputeTest {

    private Namespace newNs() {
        return new Namespace(Code.ROOT_NAMESPACE);
    }

    private float eval(String expr, Namespace ns) {
        return Code.decodeFormula(expr, ns).getResult();
    }

    private float eval(String expr) {
        return eval(expr, newNs());
    }

    @Test
    void operatorPrecedence() {
        assertEquals(7f, eval("1 + 2 * 3"), 0f);
        assertEquals(4f, eval("2 + 6 / 3"), 0f);
        assertEquals(4f, eval("2 + 6 % 4"), 0f);
    }

    @Test
    void powerOperator() {
        assertEquals(8f, eval("2 ^ 3"), 0f);
    }

    @Test
    void moduloOperator() {
        assertEquals(3f, eval("7 % 4"), 0f);
        assertEquals(1f, eval("10 % 3"), 0f);
    }

    @Test
    void unaryMinus() {
        assertEquals(-5f, eval("-5"), 0f);
        assertEquals(-5f, eval("-(2 + 3)"), 0f);
        assertEquals(-2f, eval("-5 + 3"), 0f);
        assertEquals(-6f, eval("2 * -3"), 0f);
        assertEquals(5f, eval("3 - -2"), 0f);
    }

    @Test
    void decimalLiterals() {
        assertEquals(4f, eval("1.5 + 2.5"), 0f);
    }

    @Test
    void functionNestingWithBoundVariableUsesRadians() {
        Namespace ns = newNs();
        Formula f = Code.decodeFormula("sin(time * 180) * 30", ns);
        ns.assign("time", 0.5f);
        float expected = (float) (Math.sin(0.5f * 180f) * 30f);
        assertEquals(expected, f.getResult(), 1e-4f);
    }

    @Test
    void singleParamFunctions() {
        assertEquals(1f, eval("cos(0)"), 1e-4f);
        assertEquals(1f, eval("sin(rad(90))"), 1e-4f);
        assertEquals(4f, eval("sqrt(16)"), 0f);
        assertEquals(1f, eval("exp(0)"), 0f);
        assertEquals(0f, eval("log(1)"), 1e-4f);
        assertEquals(2f, eval("floor(2.7)"), 0f);
        assertEquals(3f, eval("ceil(2.1)"), 0f);
        assertEquals(2f, eval("round(2.4)"), 0f);
    }

    @Test
    void doubleParamFunctions() {
        assertEquals(1024f, eval("pow(2, 10)"), 0f);
        assertEquals(3f, eval("min(3, 7)"), 0f);
        assertEquals(3f, eval("min(7, 3)"), 0f);
        assertEquals(7f, eval("max(3, 7)"), 0f);
    }

    @Test
    void tripleParamFunction() {
        Namespace ns = newNs();
        ns.register3Param("test_clamp", (v, lo, hi) -> Math.max(lo, Math.min(v, hi)));
        assertEquals(10f, eval("test_clamp(15, 0, 10)", ns), 0f);
        assertEquals(0f, eval("test_clamp(-5, 0, 10)", ns), 0f);
        assertEquals(5f, eval("test_clamp(5, 0, 10)", ns), 0f);
    }

    @Test
    void variableAssignmentAndReassignment() {
        Namespace ns = newNs();
        Formula f = Code.decodeFormula("x + 1", ns);
        assertTrue(ns.containsInstance("x"));
        assertEquals(1, ns.instanceVarSize());

        ns.assign("x", 5f);
        assertEquals(6f, f.getResult(), 0f);

        ns.assign("x", 8f);
        assertEquals(9f, f.getResult(), 0f);
    }

    @Test
    void variableAutoCreatedWithZeroValue() {
        Namespace ns = newNs();
        Formula f = Code.decodeFormula("x", ns);
        assertEquals(0f, f.getResult(), 0f);
        ns.assign("x", 3f);
        assertEquals(3f, f.getResult(), 0f);
    }

    @Test
    void dottedVariableNames() {
        Namespace ns = newNs();
        Formula f = Code.decodeFormula("query.anim_time + 1", ns);
        ns.assign("query.anim_time", 3f);
        assertEquals(4f, f.getResult(), 0f);
        assertTrue(ns.containsInstance("query.anim_time"));
    }

    @Test
    void longExpressionBeyondTenTokens() {
        assertEquals(66f, eval("1+2+3+4+5+6+7+8+9+10+11"), 0f);
    }

    @Test
    void deeplyNestedParentheses() {
        assertEquals(3465f, eval("(((1+2)*(3+4))*((5+6)*(7+8)))"), 0f);
    }

    @Test
    void encodeFormulaRoundTrip() {
        Namespace ns = newNs();
        Formula f = Code.decodeFormula("1 + 2 * 3", ns);
        assertEquals("1.0+2.0*3.0", Code.encodeFormula(f));
    }

    @Test
    void moduloSharesPrecedenceWithMulDiv() {
        // % 与 * / 同级左结合：10 % 3 * 5 = (10 % 3) * 5 = 5，而非 10 % 15
        assertEquals(5f, eval("10 % 3 * 5"), 0f);
        assertEquals(4f, eval("10 % 3 * 2 + 2"), 0f);
        assertEquals(5f, eval("7 % 4 * 2 - 1"), 0f);
    }

    @Test
    void doubleStarAsPower() {
        // Python 风格 ** 幂：a ** b == a ^ b
        assertEquals(8f, eval("2 ** 3"), 0f);
        assertEquals(1024f, eval("2 ** 10"), 0f);
        assertEquals(27f, eval("3 ** 3"), 0f);
    }
}
