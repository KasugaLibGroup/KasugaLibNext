package lib.kasuga.create.client.model;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
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
    private static final MethodHandle BLOCK_ENTITY = resolveBlockEntity();

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

    private static MethodHandle resolveBlockEntity() {
        var lookup = MethodHandles.publicLookup();
        try {
            return lookup.findVirtual(Contraption.class, "getBlockEntityClientSide",
                    MethodType.methodType(BlockEntity.class, BlockPos.class));
        } catch (NoSuchMethodException olderCreate) {
            try {
                var map = lookup.findGetter(Contraption.class, "presentBlockEntities", java.util.Map.class);
                var get = lookup.findVirtual(java.util.Map.class, "get", MethodType.methodType(Object.class, Object.class));
                return MethodHandles.filterArguments(get, 0, map).asType(
                        MethodType.methodType(BlockEntity.class, Contraption.class, BlockPos.class));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Create block entity view API", failure);
            }
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    static BlockEntity blockEntity(Contraption contraption, BlockPos position) {
        try { return (BlockEntity) BLOCK_ENTITY.invokeExact(contraption, position); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Create block entity view failed", failure); }
    }
}
