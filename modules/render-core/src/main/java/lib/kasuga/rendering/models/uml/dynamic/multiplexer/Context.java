package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard;

/**
 * The input to a {@link Multiplexer}: a read-only snapshot of the world / object being evaluated.
 *
 * <p>It provides two data planes:
 * <ul>
 *   <li><b>Properties</b>: string-keyed string values, analogous to block-state properties.</li>
 *   <li><b>Data</b>: an open {@link Blackboard} for typed or raw extension values.</li>
 * </ul>
 *
 * <p>Minecraft-specific context data (redstone power, neighbors, tags, time-of-day, ...) lives in an
 * implementation such as {@code lib.kasuga.rendering.models.mc.multiplexer.McContext}.
 */
public interface Context {

    /** A string property, or {@code null} when absent (like a missing block-state property). */
    String property(String name);

    /** The open extension channel. */
    Blackboard data();
}
