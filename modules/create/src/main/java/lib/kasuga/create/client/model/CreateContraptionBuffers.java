package lib.kasuga.create.client.model;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import lib.kasuga.create.CreateContraptionBlockEntities;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The native structure cache moved between Create 6.0.6 and 6.0.10. Resolve public APIs once. */
final class CreateContraptionBuffers {
    private static final MethodHandle BUFFER = resolve();

    private CreateContraptionBuffers() {}

    private static MethodHandle resolve() {
        var lookup = MethodHandles.publicLookup();
        var signature = MethodType.methodType(SuperByteBuffer.class, Contraption.class, VirtualRenderWorld.class, RenderType.class);
        try {
            return lookup.findStatic(ContraptionEntityRenderer.class, "getBuffer", signature);
        } catch (NoSuchMethodException olderCreate) {
            try {
                var info = Class.forName("com.simibubi.create.content.contraptions.render.ContraptionRenderInfo", false,
                        CreateContraptionBuffers.class.getClassLoader());
                var get = lookup.findStatic(info, "get", MethodType.methodType(info, Contraption.class));
                var buffer = lookup.findVirtual(info, "getBuffer", MethodType.methodType(SuperByteBuffer.class, RenderType.class));
                var legacy = MethodHandles.filterArguments(buffer, 0, get);
                return MethodHandles.dropArguments(legacy, 1, VirtualRenderWorld.class).asType(signature);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Create structure buffer API", failure);
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Create structure buffer API is not accessible", failure);
        }
    }

    static SuperByteBuffer get(Contraption contraption, VirtualRenderWorld renderWorld, RenderType type) {
        try {
            return (SuperByteBuffer) BUFFER.invokeExact(contraption, renderWorld, type);
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Create structure buffer failed", failure); }
    }

    static MethodType signature() { return BUFFER.type(); }

    static BlockEntity blockEntity(Contraption contraption, BlockPos position) {
        return CreateContraptionBlockEntities.get(contraption, position);
    }
}
