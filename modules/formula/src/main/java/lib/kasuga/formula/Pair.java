package lib.kasuga.formula;

import java.util.Objects;

/**
 * A simple data structure holding two elements.
 *
 * @param <K> type of the first element
 * @param <V> type of the second element
 */
public class Pair<K, V> {
    K first;
    V second;
    /**
     * Creates a pair.
     *
     * @param first the first element
     * @param second the second element
     */
    protected Pair(K first, V second) {
        this.first = first;
        this.second = second;
    }

    /**
     * Factory method: creates a pair.
     *
     * @param <K> the first element type
     * @param <V> the second element type
     * @param first the first element
     * @param second the second element
     * @return the new pair
     */
    public static <K, V> Pair<K, V> of(K first, V second) {
        return new Pair<K, V>(first, second);
    }


    /**
     * The first element.
     *
     * @return the first element
     */
    public K getFirst() {
        return first;
    }

    /**
     * The second element.
     *
     * @return the second element
     */
    public V getSecond() {
        return second;
    }

    /**
     * Returns an element by position.
     *
     * @param isFirst true for the first, false for the second
     * @return the element at the requested position
     */
    public Object get(boolean isFirst) {
        return isFirst ? first : second;
    }

    /**
     * Two pairs are equal when both elements are equal.
     *
     * @param object the object to compare
     * @return true if equal
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        Pair<?, ?> pair = (Pair<?, ?>) object;
        return Objects.equals(first, pair.first) && Objects.equals(second, pair.second);
    }

    /**
     * Hash code computed from both elements.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }
}