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
import net.minecraft.nbt.Tag;
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
 * <p>UUID 规则（review 口径）：<b>只在 {@code Carriage} 构造期铸造一次</b>（新对象的身份，
 * 和实体 UUID 同性质），读档只接受存档里写着的值。<b>绝不</b>在读取时按索引 / 位置 / 对象身份
 * 合成替代值，也绝不把随机值写进存档。存档里没有就是没有，{@link CarriageIdentity#kasuga$carriageId()}
 * 返回 {@code null}，由重映射那边按「身份未知」放弃本轮处理。</p>
 *
 * <p>这里不改任何设备状态：{@code Train#read} 会连续调用 {@code setTrain} 多次，在世界事件里动
 * 设备状态会踩到半构造状态。实际重映射在下一次服务端 tick 开头做。</p>
 */
@Mixin(value = Carriage.class, remap = false)
public abstract class CarriageDeviceMixin implements CarriageIdentity {

    private static final String TAG_CARRIAGE_ID = "KasugaCarriageId";

    /** 车厢身份。{@code null} = 没有身份（构造函数注入未命中，或读档时显式置空）。 */
    @Unique
    @Nullable
    private UUID kasuga$carriageId;

    /**
     * 身份只在构造期铸造：这是「新对象的身份」，不是从缺失数据里猜出来的兜底值。
     * 读档时 {@code Carriage#read} 会用存档里的值覆盖它。
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void kasuga$mintCarriageId(CallbackInfo ci) {
        this.kasuga$carriageId = UUID.randomUUID();
    }

    @Override
    @Nullable
    public UUID kasuga$carriageId() {
        return kasuga$carriageId;
    }

    @Override
    public void kasuga$setCarriageId(@Nullable UUID id) {
        this.kasuga$carriageId = id;
    }

    /** 只置脏：这是提前通知，不是数据来源，漏命中由下一 tick 的轮询兜住。 */
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

    /** 没有身份就不写这个键（不写占位值），有身份就原样写。 */
    @Inject(method = "write", at = @At("RETURN"))
    private void kasuga$writeCarriageId(DimensionPalette dimensions, HolderLookup.Provider registries,
                                        CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        UUID id = this.kasuga$carriageId;
        if (tag == null || id == null) {
            return;
        }
        tag.putUUID(TAG_CARRIAGE_ID, id);
    }

    /** 只接受存档里的值；存档里没有就保持读取前的状态，不生成。 */
    @Inject(method = "read", at = @At("RETURN"))
    private static void kasuga$readCarriageId(CompoundTag tag, HolderLookup.Provider registries,
                                              TrackGraph graph, DimensionPalette dimensions,
                                              CallbackInfoReturnable<Carriage> cir) {
        Carriage carriage = cir.getReturnValue();
        if (carriage == null || tag == null || !tag.contains(TAG_CARRIAGE_ID)) {
            return;
        }
        if (!tag.hasUUID(TAG_CARRIAGE_ID)) {
            // 存档里有个坏值：宁可让这次读档失败，也不用新 id 悄悄覆盖它。
            throw new IllegalStateException("车厢 NBT 的 " + TAG_CARRIAGE_ID
                    + " 不是合法 UUID，拒绝覆盖：" + tag.get(TAG_CARRIAGE_ID));
        }
        ((CarriageIdentity) carriage).kasuga$setCarriageId(tag.getUUID(TAG_CARRIAGE_ID));
    }
}
