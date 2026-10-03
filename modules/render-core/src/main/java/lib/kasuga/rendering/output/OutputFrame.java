package lib.kasuga.rendering.output;

/**
 * A completed view, borrowed read-only for synchronous render-thread consumption.
 * All subscribers to a view receive this same object and resource. The router
 * owns storage per view; the next publication may overwrite it, resize may
 * replace it, and closing the last subscription retires it after callbacks end.
 * Consumers must not mutate/delete the resource. Retained frames need owned copies.
 */
public record OutputFrame<T>(String viewId, long frameNumber, int width, int height, T resource) {}
