package lib.kasuga.create.mixins;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import lib.kasuga.create.compat.createconsist.CarriageIdentity;
import lib.kasuga.create.content.train.device.TrainDeviceManager;
import lib.kasuga.create.content.train.device.TrainDeviceProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 编组挂钩（I-05）：给车厢一个可存盘的稳定 UUID，并在车厢归属变化时只置一个「编组脏」标记。
 *
 * <p>这里不改任何设备状态：{@code Train#read} 会连续调用 {@code setTrain} 多次，在世界事件里动
 * 设备状态会踩到半构造状态。实际重映射在下一次服务端 tick 开头做。</p>
 */
@Mixin(value = Carriage.class, remap = false)
public abstract class CarriageDeviceMixin implements CarriageIdentity {

    @Unique
    private UUID kasuga$carriageId;

    @Override
    public UUID kasuga$carriageId() {
        if (kasuga$carriageId == null) {
            kasuga$carriageId = UUID.randomUUID();
        }
        return kasuga$carriageId;
    }

    @Override
    public void kasuga$setCarriageId(@Nullable UUID id) {
        this.kasuga$carriageId = id;
    }

    @Inject(method = "setTrain", at = @At("TAIL"), require = 0)
    private void kasuga$onSetTrain(Train train, CallbackInfo ci) {
        if (!(train instanceof TrainDeviceProvider provider)) {
            return;
        }
        TrainDeviceManager manager = provider.kasugaLib$getDeviceManager();
        if (manager != null) {
            manager.markConsistDirty();
        }
    }

    @Inject(method = "write", at = @At("RETURN"), require = 0)
    private void kasuga$writeCarriageId(DimensionPalette dimensions, HolderLookup.Provider registries,
                                        CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        if (tag != null) {
            tag.putUUID("KasugaCarriageId", kasuga$carriageId());
        }
    }

    @Inject(method = "read", at = @At("RETURN"), require = 0)
    private static void kasuga$readCarriageId(CompoundTag tag, HolderLookup.Provider registries,
                                              TrackGraph graph, DimensionPalette dimensions,
                                              CallbackInfoReturnable<Carriage> cir) {
        Carriage carriage = cir.getReturnValue();
        if (carriage == null || tag == null || !tag.contains("KasugaCarriageId")) {
            return;
        }
        ((CarriageIdentity) carriage).kasuga$setCarriageId(tag.getUUID("KasugaCarriageId"));
    }
}
