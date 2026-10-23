package lib.kasuga.uip.ldlib.compat;

import com.mojang.blaze3d.systems.RenderSystem;

/** Render-thread scope for LDLib2 compatibility hooks; ordinary LDLib2 screens never enter it. */
public final class LdlibWorldRenderScope implements AutoCloseable {
    private static int depth;

    private LdlibWorldRenderScope() { depth++; }

    public static LdlibWorldRenderScope enter() {
        RenderSystem.assertOnRenderThread();
        return new LdlibWorldRenderScope();
    }

    public static boolean isActive() { return depth > 0; }

    @Override public void close() { depth--; }
}
