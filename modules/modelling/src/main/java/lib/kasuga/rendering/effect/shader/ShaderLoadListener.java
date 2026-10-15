package lib.kasuga.rendering.effect.shader;

import net.minecraft.client.renderer.ShaderInstance;

/** Complete shader lifecycle observer; callbacks run on the render thread. */
public interface ShaderLoadListener {
    ShaderLoadListener NONE = new ShaderLoadListener() {};

    default void onReady(ShaderInstance shader, long generation) {}

    default void onFailure(String error) {}

    default void onInvalidated() {}
}
