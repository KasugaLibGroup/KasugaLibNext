package lib.kasuga.formula.logic.data;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.logic.operations.BoolOperation;
import lib.kasuga.formula.logic.operations.MathOperation;
import lib.kasuga.formula.Utils;
import lib.kasuga.formula.logic.infrastructure.LogicalAssignable;
import lib.kasuga.formula.logic.infrastructure.LogicalData;
import lib.kasuga.formula.logic.infrastructure.LogicalOperator;
import lib.kasuga.formula.logic.operations.BoolType;
import lib.kasuga.formula.logic.operations.MathType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logical line: parser and evaluator for comparison and boolean expressions.
 *
 * <p>Supports comparison operators {@code > < >= <= == != <>} ({@code <>} is an
 * alias for {@code !=}; symbol operators do not require surrounding spaces) and
 * boolean operators {@code and or not} (word operators must be space-delimited,
 * to avoid colliding with variable names such as {@code nota}). Precedence,
 * highest to lowest: {@code ==}, {@code !=}, {@code > <}, {@code >= <=},
 * {@code not}, {@code and}, {@code or}. Chained comparisons are not supported
 * ({@code a > b > c} throws
 * {@link lib.kasuga.formula.compute.exceptions.FormulaSyntaxError}).
 */
public class LogicalLine implements LogicalData, LogicalAssignable {
    private final ArrayList<LogicalData> elements;
    private final String context = "CONTEXT";
    private final Namespace namespace;

    /**
     * Creates an empty logical line.
     *
     * @param namespace the namespace used for evaluation and registration
     */
    public LogicalLine(Namespace namespace) {
        this.elements = new ArrayList<>();
        this.namespace = namespace;
    }

    /**
     * Creates a logical line from a pre-built element list.
     *
     * @param elements the element list
     * @param namespace the namespace used for evaluation and registration
     */
    public LogicalLine(ArrayList<LogicalData> elements, Namespace namespace) {
        this.elements = elements;
        this.namespace = namespace;
    }

    /**
     * Creates a logical line and parses the given string.
     *
     * @param string the logical expression string
     * @param namespace the namespace used for evaluation and registration
     */
    public LogicalLine(String string, Namespace namespace) {
        this(namespace);
        fromString(string);
    }


    /**
     * Parses the string (bracket extraction, operator location and token
     * loading).
     *
     * @param string the logical expression
     */
    public void fromString(String string) {
        string = string.trim();
        int length = 0;
        while (length != string.length()) {
            length = string.length();
            string = string.replace("  ", " ");
        }

        HashMap<String, LogicalData> inner = new HashMap<>();
        ArrayList<Integer> patternIndex = new ArrayList<>();
        ArrayList<String> patternString = new ArrayList<>();
        if(Utils.containsBrackets(string)) {
            if(!Utils.checkBrackets(string)) throw new RuntimeException();

            findPatterns(string, patternIndex, patternString);
            int[] brackets = Utils.positionBrackets(string);
            while (isValidBrackets(brackets)) {
                int former = -1;
                for(Integer index : patternIndex) {
                    if(index > brackets[0] && index < brackets[1]) {
                        former = index;
                        break;
                    }
                }
                if(former > -1) {
                    LogicalData data = new LogicalLine(string.substring(brackets[0] + 1, brackets[1]), namespace);
                    while (data instanceof LogicalLine line && !line.isEmpty() && line.isAtomic()) {
                        data = line.getFirst();
                    }
                    inner.put(context + inner.size(), data);
                    string = string.substring(0, brackets[0]) + context + (inner.size() - 1)
                            + string.substring(brackets[1] + 1);
                    if(Utils.containsBrackets(string)) {
                        findPatterns(string, patternIndex, patternString);
                        brackets = Utils.positionBrackets(string);
                    } else {break;}
                } else {
                    int ofst = brackets[1] + 1;
                    String s = string.substring(brackets[1] + 1);
                    if(Utils.containsBrackets(s)) {
                        brackets = Utils.positionBrackets(s);
                        if (isValidBrackets(brackets)) {
                            brackets[0] += ofst;
                            brackets[1] += ofst;
                        }
                    } else {break;}
                }
            }
        }

        findPatterns(string, patternIndex, patternString);
        int offset = 0, counter = 0;
        for(Integer index : patternIndex) {
            String token = string.substring(offset, index).replace(" ", "");
            if(!token.equals("")) {
                loadToken(inner, token, namespace);
            }
            String pattern = patternString.get(counter);
            if(BoolOperation.isBoolOperation(pattern)) {
                elements.add(new BoolOperation(pattern));
            } else {
                elements.add(new MathOperation(pattern));
            }
            offset = index + pattern.length();
            counter++;
        }
        String token = string.substring(offset).trim();
        loadToken(inner, token, namespace);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for(LogicalData data : elements) {
            if(data instanceof LogicalOperator)
                builder.append(" ").append(data).append(" ");
            else
                builder.append(data.toString());
        }
        return builder.toString();
    }

    /**
     * Loads a token: bracket placeholder, boolean literal or logical numeric.
     *
     * @param inner the bracket sub-expression map
     * @param token the token to load
     * @param namespace the evaluation namespace
     */
    private void loadToken(HashMap<String, LogicalData> inner, String token, Namespace namespace) {
        if(token.startsWith(context)) {
            elements.add(inner.get(token));
        } else if(LogicalBool.isBool(token)) {
            elements.add(new LogicalBool(token));
        } else {
            LogicalNumeric numeric = new LogicalNumeric(token, namespace);
            elements.add(numeric);
        }
    }

    /**
     * Locates all operator occurrences (word operators need surrounding spaces,
     * symbol operators do not).
     *
     * @param string the string to scan
     * @param integers output: operator start indices
     * @param strings output: operator texts
     */
    public void findPatterns(String string, ArrayList<Integer> integers, ArrayList<String> strings) {
        integers.clear();
        strings.clear();
        Matcher matcher = matcher(string);
        while (matcher.find()) {
            if(!integers.contains(matcher.start())) {
                String matched = matcher.group();
                // Word operators (not/and/or) require surrounding spaces to avoid clashing with
                // variable names (nota/andx/or2 are legal); symbol operators (>= <= == != <> > <) do not need spaces, so `a>b` and `a>=5` parse correctly.
                boolean wordOperator = matched.equals("not") || matched.equals("and") || matched.equals("or");
                if (wordOperator) {
                    if(matcher.start() > 0 && string.charAt(matcher.start() - 1) != ' ') continue;
                    if(matcher.end() < string.length() && string.charAt(matcher.end()) != ' ') continue;
                }
                integers.add(matcher.start());
                strings.add(matcher.group());
            }
        }
    }

    private boolean isValidBrackets(int[] positions) {
        return positions[0] > -1 && positions[1] > -1;
    }

    /**
     * The first element.
     *
     * @return the first element
     */
    public LogicalData getFirst() {
        return this.elements.get(0);
    }

    /**
     * Whether the element list is empty.
     *
     * @return true if empty
     */
    public boolean isEmpty() {return elements.isEmpty();}


    public boolean isAtomic() {
        return elements.size() <= 1;
    }

    @Override
    public LogicalLine clone() {
        return new LogicalLine(new ArrayList<LogicalData>(elements), this.namespace.clone());
    }

    @Override
    public boolean getResult() {
        ArrayList<LogicalData> datas = new ArrayList<>(elements);
        dealLeveledOperation(datas, MathType.EQUALS);
        dealLeveledOperation(datas, MathType.NOT_EQU);
        dealLeveledOperation(datas, MathType.LARGER, MathType.SMALLER);
        dealLeveledOperation(datas, MathType.LARGER_EQU, MathType.SMALLER_EQU);
        dealLeveledOperation(datas, BoolType.NOT);
        dealLeveledOperation(datas, BoolType.AND);
        dealLeveledOperation(datas, BoolType.OR);
        if(datas.size() == 1) {
            return datas.get(0).getResult();
        }
        return false;
    }

    private void dealLeveledOperation(ArrayList<LogicalData> datas, Object... types) {

        ArrayList<Integer> locations = locateOperation(datas, types);
        int offset = 0;
        for(Integer index : locations) {
            LogicalOperator operator = (LogicalOperator) datas.get(index - offset);
            for(Object type : types) {
                if(operator.is(type)) {
                    if(!(type instanceof BoolType) && !(type instanceof MathType)) break;
                    if(type instanceof BoolType bool) {
                        if(bool == BoolType.NOT) {
                            datas.set(index - offset, new LogicalBool(operator.operate(null, datas.get(index - offset + 1))));
                            datas.remove(index - offset + 1);
                            offset++;
                            break;
                        }
                    }
                    datas.set(index - offset - 1, new LogicalBool(operator.operate(datas.get(index - offset - 1), datas.get(index - offset + 1))));
                    datas.remove(index - offset);
                    datas.remove(index - offset);
                    offset += 2;
                    break;
                }
            }
        }
    }

    private ArrayList<Integer> locateOperation(ArrayList<LogicalData> datas, Object... types) {
        ArrayList<Integer> result = new ArrayList<>();
        for(int i = 0; i < datas.size(); i++) {
            LogicalData data = datas.get(i);
            for(Object obj : types) {
                if(data instanceof LogicalOperator operator && operator.is(obj)) {
                    result.add(i);
                    break;
                }
            }

        }
        return result;
    }

    /**
     * Logical operator matcher ({@code not/and/or/>=/<=/==/!=/<>/>/<}).
     *
     * @param string the string to match
     * @return the matcher
     */
    public static Matcher matcher(String string) {
        Pattern pattern = Pattern.compile("(not)|(and)|(or)|(>=)|(<=)|(==)|(!=)|(<>)|(>)|(<)");
        return pattern.matcher(string);
    }

    @Override
    public boolean isAssignable() {
        return namespace.instanceVarSize() > 0;
    }

    @Override
    public void assign(String codec, float value) {
        namespace.assign(codec, value);
    }

    @Override
    public Namespace getNamespace() {
        return namespace;
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof LogicalLine line)) return false;
        return toString().equals(line.toString());
    }
}