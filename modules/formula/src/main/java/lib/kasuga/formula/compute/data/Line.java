package lib.kasuga.formula.compute.data;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.Utils;
import lib.kasuga.formula.compute.data.functions.Function;
import lib.kasuga.formula.compute.exceptions.FormulaParseError;
import lib.kasuga.formula.compute.exceptions.FormulaSyntaxError;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.formula.compute.infrastructure.Pretreatable;


import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arithmetic line: the expression parser and evaluator core.
 *
 * <p>Parses a string into a sequence of formula elements (numbers, variables,
 * functions, sub-lines and operators), pre-treats them (merging adjacent
 * operators, applying the unary-minus flip, extracting parentheses and function
 * arguments) and evaluates with precedence {@code ^} highest, {@code * / %}
 * next (same level, left-associative), {@code + -} lowest. {@code **} is an alias
 * for {@code ^}. Lines nest through bracketed sub-lines and are consumed as
 * function arguments.
 */
public class Line implements Formula, Assignable, Pretreatable {
    private final List<Formula> elements;
    private final Namespace namespace;
    /** Placeholder prefix for bracketed sub-expressions. */
    public static final String brackets_codec = "BRACKETS_CODEC";
    private boolean flip = false;
    /**
     * Creates an empty line.
     *
     * @param namespace the namespace used for evaluation and registration
     */
    public Line(Namespace namespace) {
        elements = new ArrayList<>();
        this.namespace = namespace;
    }

    /**
     * Creates a line and parses the given string immediately.
     *
     * @param line the expression string
     * @param namespace the namespace used for evaluation and registration
     */
    public Line(String line, Namespace namespace) {
        this(namespace);
        fromString(line);
    }

    /**
     * Creates a line from a pre-built element list.
     *
     * @param elements the element list
     * @param namespace the namespace used for evaluation and registration
     */
    public Line(List<Formula> elements, Namespace namespace) {
        this.elements = elements;
        this.namespace = namespace;
    }

    @Override
    public String getString() {
        StringBuilder builder = new StringBuilder();
        if(flip)
            builder.append("-");
        for(Formula formula : elements) {
            if(formula.isAtomic() || formula instanceof Function)
                builder.append(formula.getString());
            else
                builder.append(FRONT_BRACKET_CODEC).append(formula.getString()).append(BACK_BRACKET_CODEC);
        }
        return builder.toString();
    }

    public String toString() {
        return getString();
    }


    @Override
    public String getIdentifier() {
        return "line";
    }

    @Override
    public float getResult() {
        if(elements.isEmpty()) return 0;
        if(elements.size() == 1) {
            if(elements.get(0) instanceof Operational) throw new FormulaSyntaxError(this, 0);
            return elements.get(0).getResult() * (flip ? -1 : 1);
        }
        ArrayList<Formula> formulas = new ArrayList<>(getElements());
        dealLeveledOperation(formulas, "^");
        // `%` shares the level of `*` `/` (left-associative): 10 % 3 * 5 = (10 % 3) * 5 = 5,
        // lowering `%` alone would yield 10 % 15 = 10, contrary to the usual math convention.
        dealLeveledOperation(formulas, "*", "/", "%");
        dealLeveledOperation(formulas, "+", "-");
        return formulas.get(0).getResult() * (flip ? -1 : 1);
    }

    private void dealLeveledOperation(ArrayList<Formula> formulas, String... operations) {
        LinkedList<Integer> indexList = getOperationsIndex(formulas, operations);
        int offset = 0;
        for (int index : indexList) {
            Operational opt = (Operational) formulas.get(index - offset);
            formulas.set(index - 1 - offset,
                    new Numeric(opt.operate(formulas.get(index - 1 - offset), formulas.get(index + 1 - offset))));
            formulas.remove(index - offset);
            formulas.remove(index - offset);
            offset+=2;
        }
    }

    private LinkedList<Integer> getOperationsIndex(List<Formula> elements, String... type) {
        LinkedList<Integer> result = new LinkedList<>();
        int counter = 0;
        for(Formula formula : elements) {
            counter++;
            if(!(formula instanceof Operational)) continue;
            for(String t : type) {
                if(formula.getString().equals(t)) result.add(counter - 1);
            }
        }
        return result;
    }

    public void preTreatment() {
        if(elements.isEmpty()) return;
        while(elements.get(0) instanceof Operational operational) {
            String str = operational.getString();
            if(!str.equals("+") && !str.equals("-")) throw new FormulaSyntaxError(this, 0);
            if(str.equals("+")) elements.remove(operational);
            if(str.equals("-")) elements.add(0, new Numeric(0f));
        }
        int length = elements.size();
        if(elements.get(elements.size() - 1) instanceof Operational) {
            throw new FormulaSyntaxError(this, elements.size() - 1);
        }
        if(length == 1) {
            subPreTreatment(elements.get(0));
        }
        for(int i = 1; i < length; i++) {
            Formula formula = elements.get(i);
            Formula former = elements.get(i - 1);
            if(formula instanceof Operational && former instanceof Operational) {
                ((Operational) formula).mergeOperation(former, this, i);
                elements.removeIf(Formula::shouldRemove);
                if(elements.size() != length) {
                    i = 0;
                    length = elements.size();
                }
            }
        }
        LinkedList<Integer> minusGroup = getOperationsIndex(elements, "-");
        ArrayList<Integer> markRemove = new ArrayList<>();
        for(Integer index : minusGroup) {
            Formula formula = elements.get(index + 1);
            if(index == 0) {
                formula.flipOutput(!formula.isOutputFlipped());
                markRemove.add(0);
            } else if (elements.get(index - 1) instanceof Operational && index < elements.size() - 1) {
                formula.flipOutput(!formula.isOutputFlipped());
                markRemove.add(index);
            }
        }
        for(int i = markRemove.size() - 1; i > -1; i--) {
            elements.remove((int) markRemove.get(i));
        }
    }

    private void subPreTreatment(Formula formula) {
        if(formula instanceof Pretreatable pretreatable)
            pretreatable.preTreatment();
    }

    @Override
    public List<Formula> getElements() {
        return elements;
    }

    @Override
    public boolean isAtomic() {
        return elements.size() <= 1;
    }

    @Override
    public boolean shouldRemove() {
        return elements.isEmpty();
    }

    @Override
    public void fromString(String string) {
        string = string.replaceAll(" ", "");
        HashMap<String, String> inners = new HashMap<>();
        if(string.contains(FRONT_BRACKET_CODEC) || string.contains(BACK_BRACKET_CODEC)) {
            if(!Utils.checkBrackets(string)) throw new FormulaSyntaxError(this, 0);
            while (Utils.containsBrackets(string)) {
                int index = inners.size();
                int[] pos = Utils.positionBrackets(string);
                for(String funcCodec : namespace.functions().keySet()) {
                    int index2 = string.indexOf(funcCodec);
                    if(index2 == -1) continue;
                    if(index2 == pos[0] - funcCodec.length()) {
                        pos[0] -= funcCodec.length();
                        break;
                    }
                }
                inners.put(brackets_codec + index, string.substring(pos[0], pos[1] + 1));
                string = string.substring(0, pos[0]) + brackets_codec + index + string.substring(pos[1] + 1);
            }
        }
        Matcher matcher = matcher(string);
        String copy = String.copyValueOf(string.toCharArray());
        ArrayList<Formula> formulaGroup = new ArrayList<>();
        int skip = 0;
        int start = 0;
        while(matcher.find()) {
            if(skip > 0) {
                skip--;
                continue;
            }
            String str = matcher.group();
            if (str.startsWith(brackets_codec)) {
                String sys = inners.getOrDefault(str, null);
                if (sys == null) throw new FormulaSyntaxError(this, matcher.start());
                if (sys.startsWith("(")) {
                    Line subLine = new Line(namespace);
                    subLine.fromString(sys.substring(1, sys.length() - 1));
                    formulaGroup.add(subLine);
                    copy = copy.replaceFirst(str, "TOKEN" + (formulaGroup.size() - 1));
                } else {
                    for (String codec : namespace.functions().keySet()) {
                        if (sys.startsWith(codec)) {
                            Function function = namespace.createFunctionInstance(codec);
                            function.fromString(sys.substring(sys.indexOf(FRONT_BRACKET_CODEC) + 1, sys.length() - 1));
                            formulaGroup.add(function);
                            // this.namespace.registerInstance(function.getCodec() + namespace.instanceVarSize(), (Assignable) function);
                            copy = copy.replaceFirst(str, "TOKEN" + (formulaGroup.size() - 1));
                            break;
                        }
                    }
                }
            } else {
                Formula formula = null;
                String var = str;
                if (Numeric.isNumber(str)) {
                    formula = new Numeric(str);
                } else if (Variable.isVar(str)) {
                    if (matcher.end() < string.length() - 1 && string.charAt(matcher.end()) == '.') {
                        String cache = string.substring(matcher.end());
                        var = findLongVar(cache, str, 0);
                        skip = var.split("\\.").length - 1;
                    }
                    if (namespace.containsInstance(var))
                        formula = (Variable) namespace.getInstance(var);
                    else {
                        formula = new Variable(var, namespace, 0);
                        namespace.registerInstance(var, (Variable) formula);
                    }
                }
                if (formula != null) {
                    formulaGroup.add(formula);
                    String rep = "TOKEN" + (formulaGroup.size() - 1);
                    String cop = copy.substring(start);
                    int index = cop.indexOf(var);
                    cop = cop.replaceFirst(var, rep);
                    copy = copy.substring(0, start) + cop;
                    start += index + rep.length();
                } else {
                    throw new FormulaParseError(new FormulaSyntaxError(this, matcher.start()), string);
                }
            }
        }
        int operationIndex = Operational.getOperationIndex(copy);
        if(operationIndex == -1) {
            this.elements.addAll(formulaGroup);
        } else {
            while (operationIndex > -1) {
                String opt = String.valueOf(copy.charAt(operationIndex));
                String former = copy.substring(0, operationIndex);
                if(!former.equals("")) {
                    int idx = Integer.parseInt(former.replaceAll("TOKEN", ""));
                    this.elements.add(formulaGroup.get(idx));
                }
                this.elements.add(new Operational(opt));
                copy = copy.substring(operationIndex + 1);
                operationIndex = Operational.getOperationIndex(copy);
            }
            int idx = Integer.parseInt(copy.replaceAll("TOKEN", ""));
            this.elements.add(formulaGroup.get(idx));
            preTreatment();
        }
    }

    /**
     * Builds the function-name list sorted by name length descending, so the
     * parser prefers the longest match.
     *
     * @return the function name list
     */
    public ArrayList<String> generateFunctionList() {
        ArrayList<String> result = new ArrayList<>();
        for(String string : Code.getFunctions().keySet()) {
            if(result.isEmpty()) {
                result.add(string);
                continue;
            }
            int l1 = string.length();
            if(result.get(0).length() <= l1) {
                result.add(0, string);
                continue;
            }
            for(String x : result) {
                int index = result.indexOf(x);
                if(index == result.size() - 1) {
                    result.add(string);
                    break;
                }
                int l2 = x.length();
                if(result.get(index + 1).length() <= l1 && l2 >= l1) {
                    result.add(index + 1, string);
                    break;
                }
            }
        }
        return result;
    }

    public Line clone() {
        return new Line(new ArrayList<>(this.elements), namespace.clone());
    }

    @Override
    public void flipOutput(boolean flip) {
        this.flip = flip;
    }

    @Override
    public boolean isOutputFlipped() {
        return flip;
    }

    /**
     * Element token matcher: numbers, variables and bracket placeholders.
     *
     * @param input the string to match
     * @return the matcher
     */
    public Matcher matcher(String input) {
        Pattern pattern = Pattern.compile("(\\d+(\\.\\d+)?)|(([a-z])([a-z1-9])*(_([a-z1-9]+))*)|(BRACKETS_CODEC+(\\d+))");
        return pattern.matcher(input);
    }

    /**
     * Variable-name matcher (lowercase letter first, digits and underscores
     * allowed afterwards).
     *
     * @param input the string to match
     * @return the matcher
     */
    public Matcher varMatcher(String input) {
        Pattern pattern = Pattern.compile("(([a-z])([a-z1-9])*(_([a-z1-9]+))*)");
        return pattern.matcher(input);
    }

    /**
     * Scans the dot-suffixed segments and assembles the full dotted variable name
     * (e.g. {@code query.anim_time}).
     *
     * @param input the string to scan
     * @param starter the accumulated leading variable name
     * @param start_index the starting index after the dot
     * @return the assembled full variable name
     */
    public String findLongVar(String input, String starter, int start_index) {
        String current = "";
        Matcher matcher = varMatcher(input);
        StringBuilder starterBuilder = new StringBuilder(starter);
        while (matcher.find()) {
            current = matcher.group();
            if(start_index + 1 < input.length() && matcher.start() == start_index + 1 && input.charAt(start_index) == '.') {
                starterBuilder.append(".").append(current);
                start_index = matcher.end();
            } else {break;}
        }
        return starterBuilder.toString();
    }

    @Override
    public Set<String> variableCodecs() {
        return namespace.instanceNames();
    }

    @Override
    public void assign(String codec, float value) {
        if(!containsVar(codec)) return;
        Assignable assignable = namespace.getInstance(codec);
        assignable.assign(codec, value);
    }

    @Override
    public boolean containsVar(String codec) {
        return namespace.containsInstance(codec);
    }

    @Override
    public float getValue(String codec) {
        if(!containsVar(codec)) throw new FormulaSyntaxError(this, 0);
        return namespace.getInstance(codec).getValue(codec);
    }

    @Override
    public Namespace getNamespace() {
        return namespace;
    }

    @Override
    public boolean hasVar() {
        return namespace.instanceVarSize() > 0;
    }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof Line line)) return false;
        return toString().equals(line.toString());
    }
}
