package lib.kasuga.formula;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;
import lib.kasuga.formula.logic.data.LogicalLine;
import lib.kasuga.formula.logic.data.LogicalNumeric;
import lib.kasuga.formula.logic.infrastructure.LogicalData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaLogicTest {

    private Namespace newNs() {
        return new Namespace(Code.ROOT_NAMESPACE);
    }

    private LogicalData decode(String expr, Namespace ns) {
        return Code.decodeLogical(expr, ns);
    }

    @Test
    void numericComparisonGreater() {
        Namespace ns = newNs();
        LogicalData d = decode("a > 3", ns);
        assertInstanceOf(LogicalLine.class, d);
        ns.assign("a", 5f);
        assertTrue(d.getResult());
        ns.assign("a", 2f);
        assertFalse(d.getResult());
    }

    @Test
    void numericComparisonFamily() {
        Namespace ns = newNs();
        LogicalData gt = decode("a > 5", ns);
        LogicalData ge = decode("a >= 5", ns);
        LogicalData lt = decode("a < 5", ns);
        LogicalData le = decode("a <= 5", ns);
        LogicalData eq = decode("a == 5", ns);
        LogicalData ne = decode("a != 5", ns);
        ns.assign("a", 5f);
        assertFalse(gt.getResult());
        assertTrue(ge.getResult());
        assertFalse(lt.getResult());
        assertTrue(le.getResult());
        assertTrue(eq.getResult());
        assertFalse(ne.getResult());
    }

    @Test
    void andOperator() {
        Namespace ns = newNs();
        LogicalData d = decode("a > 3 and b > 4", ns);
        ns.assign("a", 5f);
        ns.assign("b", 9f);
        assertTrue(d.getResult());
        ns.assign("b", 2f);
        assertFalse(d.getResult());
    }

    @Test
    void orOperator() {
        Namespace ns = newNs();
        LogicalData d = decode("a > 3 or b > 4", ns);
        ns.assign("a", 2f);
        ns.assign("b", 9f);
        assertTrue(d.getResult());
        ns.assign("b", 2f);
        assertFalse(d.getResult());
    }

    @Test
    void notOperatorWithParentheses() {
        Namespace ns = newNs();
        LogicalData d = decode("not (a > 3)", ns);
        ns.assign("a", 5f);
        assertFalse(d.getResult());
        ns.assign("a", 2f);
        assertTrue(d.getResult());
    }

    @Test
    void arithmeticInsideLogicTokens() {
        Namespace ns = newNs();
        LogicalData d = decode("(a + 1) == 6", ns);
        ns.assign("a", 5f);
        assertTrue(d.getResult());

        LogicalData d2 = decode("a + b >= 10", ns);
        ns.assign("a", 6f);
        ns.assign("b", 5f);
        assertTrue(d2.getResult());
    }

    @Test
    void constantComparison() {
        assertTrue(decode("5 > 3", newNs()).getResult());
        assertTrue(decode("1 + 1 == 2", newNs()).getResult());
    }

    @Test
    void booleanLiterals() {
        assertFalse(decode("True and False", newNs()).getResult());
        assertTrue(decode("True or False", newNs()).getResult());
        assertFalse(decode("not True", newNs()).getResult());
    }

    @Test
    void logicalNumericTruthiness() {
        Namespace ns = newNs();
        LogicalData d = decode("a", ns);
        assertInstanceOf(LogicalNumeric.class, d);
        ns.assign("a", 5f);
        assertTrue(d.getResult());
        ns.assign("a", 0f);
        assertFalse(d.getResult());
    }

    @Test
    void logicalNumericMathResult() {
        Namespace ns = newNs();
        LogicalNumeric d = (LogicalNumeric) decode("a + 1", ns);
        ns.assign("a", 4f);
        assertTrue(d.mathResult() == 5f);
        assertTrue(d.getResult());
    }

    @Test
    void logicalNumericUsedAsBooleanOperand() {
        Namespace ns = newNs();
        LogicalData d = decode("a and b", ns);
        ns.assign("a", 1f);
        ns.assign("b", 0f);
        assertFalse(d.getResult());
    }

    @Test
    void reEvaluationAfterReassignment() {
        Namespace ns = newNs();
        LogicalData d = decode("a > 3 and b > 4", ns);
        ns.assign("a", 5f);
        ns.assign("b", 9f);
        assertTrue(d.getResult());
        ns.assign("a", 0f);
        assertFalse(d.getResult());
    }

    @Test
    void encodeLogicalRoundTrip() {
        Namespace ns = newNs();
        LogicalData d = decode("a > 3", ns);
        assertTrue(Code.encodeLogical(d).contains("a > 3"));
    }

    @Test
    void encodeLogicalRoundTripEqualityFamily() {
        // MathType.toString 曾把 LARGER_EQU/SMALLER_EQU 输出反了（">=" ↔ "<="）：
        // 序列化往返必须保持 >= / <= 符号正确。
        Namespace ns = newNs();
        assertTrue(Code.encodeLogical(decode("a >= 5", ns)).contains("a >= 5"));
        assertTrue(Code.encodeLogical(decode("a <= 5", ns)).contains("a <= 5"));
        assertTrue(Code.encodeLogical(decode("a != 5", ns)).contains("a != 5"));
        assertTrue(Code.encodeLogical(decode("a == 5", ns)).contains("a == 5"));
        assertTrue(Code.encodeLogical(decode("a <> 5", ns)).contains("a != 5"));
        assertTrue(Code.encodeLogical(decode("a < 5", ns)).contains("a < 5"));
    }

    @Test
    void noSpacesAroundSymbolOperators() {
        // 符号型运算符不要求空格：a>b 与 a > b 等价（修复前 a>b 静默忽略运算符）
        Namespace ns = newNs();
        LogicalData d = decode("a>b", ns);
        ns.assign("a", 2f); ns.assign("b", 5f);
        assertFalse(d.getResult());
        ns.assign("a", 7f); ns.assign("b", 5f);
        assertTrue(d.getResult());

        Namespace ns2 = newNs();
        LogicalData d2 = decode("a>=5", ns2);
        ns2.assign("a", 5f);
        assertTrue(d2.getResult());
        ns2.assign("a", 4f);
        assertFalse(d2.getResult());

        Namespace ns3 = newNs();
        LogicalData d3 = decode("a+1==6", ns3);
        ns3.assign("a", 5f);
        assertTrue(d3.getResult());
    }

    @Test
    void wordOperatorsStillRequireSpaces() {
        // 单词型运算符保留空格要求：nota 是合法变量名，不应被拆成 not + a
        Namespace ns = newNs();
        LogicalData d = decode("nota", ns);
        assertTrue(d instanceof LogicalNumeric);

        Namespace ns2 = newNs();
        LogicalData d2 = decode("not a > 3", ns2);
        ns2.assign("a", 5f);
        assertFalse(d2.getResult());
    }

    @Test
    void chainedComparisonThrowsSyntaxError() {
        // 链式比较不支持：抛 FormulaSyntaxError 而非裸 RuntimeException
        Namespace ns = newNs();
        LogicalData d = decode("a > b > c", ns);
        ns.assign("a", 9f); ns.assign("b", 5f); ns.assign("c", 1f);
        FormulaSyntaxError error = org.junit.jupiter.api.Assertions.assertThrows(
                FormulaSyntaxError.class, d::getResult);
        assertTrue(error.getMessage().contains("Chained comparison"));
    }
}
