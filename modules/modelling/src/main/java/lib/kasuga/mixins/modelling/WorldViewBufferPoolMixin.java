package lib.kasuga.mixins.modelling;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import lib.kasuga.rendering.output.mc.CameraBufferPool;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import org.spongepowered.asm.mixin.*;
import java.util.Queue;
@Mixin(SectionBufferBuilderPool.class) abstract class WorldViewBufferPoolMixin implements CameraBufferPool {
    @Shadow @Final private Queue<SectionBufferBuilderPack> freeBuffers;
    @Unique private boolean kasuga$owned, kasuga$closed;
    public synchronized void kasuga$own() { kasuga$owned = true; }
    public synchronized void kasuga$close() {
        kasuga$closed = true;
        SectionBufferBuilderPack pack;
        while ((pack = freeBuffers.poll()) != null) pack.close();
    }
    @WrapMethod(method = "acquire")
    private SectionBufferBuilderPack kasuga$acquire(Operation<SectionBufferBuilderPack> original) {
        if (!kasuga$owned) return original.call();
        synchronized (this) { return kasuga$closed ? null : original.call(); }
    }
    @WrapMethod(method = "release")
    private void kasuga$release(SectionBufferBuilderPack pack, Operation<Void> original) {
        if (!kasuga$owned) { original.call(pack); return; }
        synchronized (this) { if (kasuga$closed) pack.close(); else original.call(pack); }
    }
}
