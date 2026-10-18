package lib.kasuga.formula;

import lib.kasuga.formula.compute.infrastructure.Formula;

/**
 * Bracket-parsing utilities: balance checks, matched-bracket location and
 * left-bracket detection.
 *
 * <p>Shared by the arithmetic line (Line) and the logical line (LogicalLine)
 * for bracket extraction. Note that {@link #positionBrackets(String)} requires
 * the input to actually contain a left bracket (callers should first test with
 * {@link #containsBrackets(String)}), otherwise it runs out of bounds.
 */
public class Utils {
    /** Utility class: not instantiable. */
    private Utils() {}


    /**
     * Whether the counts of left and right brackets are equal (does not
     * validate nesting).
     *
     * @param input the string to check
     * @return true if the bracket counts match
     */
    public static boolean checkBrackets(String input) {
        char[] chars = input.toCharArray();
        int front = 0, back = 0;
        for(char c : chars) {
            if(c == '(') front++;
            if(c == ')') back++;
        }
        return front == back;
    }

    /**
     * Whether the string contains a left bracket ({@code (}).
     *
     * @param string the string to check
     * @return true if a left bracket is present
     */
    public static boolean containsBrackets(String string) {
        return string.contains(Formula.FRONT_BRACKET_CODEC);
    }

    /**
     * Locates the first bracket pair, returning
     * {@code [left-bracket index, matching right-bracket index]}; the input must
     * contain a left bracket (callers should first test with
     * {@link #containsBrackets(String)}), otherwise it runs out of bounds. The
     * matching right bracket is found by nesting depth returning to zero.
     *
     * @param input the string to scan (must contain a left bracket)
     * @return {@code [left-bracket index, matching right-bracket index]}
     */
    public static int[] positionBrackets(String input) {
        int[] result = new int[]{-1, -1};
        result[0] = input.indexOf(Formula.FRONT_BRACKET_CODEC);
        char[] chars = input.substring(result[0]).toCharArray();
        int counter = 0;
        int index = 0;
        for(char c : chars) {
            if(c == '(') counter ++;
            if(c == ')') counter --;
            if(counter == 0) {
                result[1] = result[0] + index;
                break;
            }
            index++;
        }
        return result;
    }
}