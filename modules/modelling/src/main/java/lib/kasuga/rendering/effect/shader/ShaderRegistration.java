package lib.kasuga.rendering.effect.shader;

import lib.kasuga.rendering.effect.RenderRegistration;

import java.util.concurrent.CompletableFuture;

/** Owned shader registration with exact preload and status controls. */
public interface ShaderRegistration extends RenderRegistration {
    RenderShaderDescriptor descriptor();

    RenderShaderHandle handle();

    default ShaderStatus status() {
        return handle().status();
    }

    default boolean preload() {
        return handle().preload();
    }

    default CompletableFuture<ShaderStatus> whenReady() {
        return handle().whenReady();
    }
}
