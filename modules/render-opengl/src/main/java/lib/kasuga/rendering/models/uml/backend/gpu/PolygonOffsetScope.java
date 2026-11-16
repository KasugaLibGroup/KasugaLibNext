package lib.kasuga.rendering.models.uml.backend.gpu;

import lib.kasuga.rendering.models.uml.backend.DepthBias;
import org.lwjgl.opengl.GL11;

/** Restores polygon fill offset state after a draw, including nested and exceptional exits. */
public final class PolygonOffsetScope implements AutoCloseable {
    private static final PolygonOffsetScope NONE = new PolygonOffsetScope();
    private final boolean enabled;
    private final float factor;
    private final float units;
    private boolean closed;

    private PolygonOffsetScope() {
        enabled = false;
        factor = units = 0;
        closed = true;
    }

    private PolygonOffsetScope(boolean enabled, float factor, float units) {
        this.enabled = enabled;
        this.factor = factor;
        this.units = units;
    }

    /** NONE performs no GL queries or mutations and needs no current GL context. */
    public static PolygonOffsetScope apply(DepthBias bias) {
        if (!bias.enabled()) return NONE;
        var scope = new PolygonOffsetScope(GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL),
                GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR),
                GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS));
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(bias.factor(), bias.units());
        return scope;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        GL11.glPolygonOffset(factor, units);
        if (enabled) GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        else GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }
}
