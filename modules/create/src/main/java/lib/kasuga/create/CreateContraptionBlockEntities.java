package lib.kasuga.create;

import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Resolves Create's client-side contraption block entity view across supported 1.21.1 releases. */
public final class CreateContraptionBlockEntities {
    private static final MethodHandle ACCESSOR = resolve();

    private CreateContraptionBlockEntities() {}

    public static BlockEntity get(Contraption contraption, BlockPos position) {
        try {
            return (BlockEntity) ACCESSOR.invokeExact(contraption, position);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Create block entity view failed", failure);
        }
    }

    private static MethodHandle resolve() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            return lookup.findVirtual(Contraption.class, "getBlockEntityClientSide",
                    MethodType.methodType(BlockEntity.class, BlockPos.class));
        } catch (NoSuchMethodException olderCreate) {
            try {
                MethodHandle map = lookup.findGetter(Contraption.class, "presentBlockEntities", java.util.Map.class);
                MethodHandle get = lookup.findVirtual(java.util.Map.class, "get",
                        MethodType.methodType(Object.class, Object.class));
                return MethodHandles.filterArguments(get, 0, map).asType(
                        MethodType.methodType(BlockEntity.class, Contraption.class, BlockPos.class));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Create block entity view API", failure);
            }
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
