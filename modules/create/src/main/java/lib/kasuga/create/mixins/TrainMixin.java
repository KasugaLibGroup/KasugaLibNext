package lib.kasuga.create.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import lib.kasuga.create.content.train.device.TrainDeviceManager;
import lib.kasuga.create.content.train.device.TrainDeviceProvider;
import lib.kasuga.create.content.train.device.sync.TrainDeviceSyncManager;
import lib.kasuga.structure.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Mixin(value = Train.class, remap = false)
public abstract class TrainMixin implements TrainDeviceProvider {

    @Unique
    private TrainDeviceManager kasuga$manager;

    @Shadow public double speed;

    @Shadow protected abstract void updateNavigationTarget(Level level, double distance);

    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/trains/entity/Train;tickPassiveSlowdown()V")
    )
    public void doTickPassiveSlowdown(Train train, Operation<Train> original) {
        if (kasuga$manager == null || !kasuga$manager.cancelSlowdown()) {
            original.call(train);
        }

        Optional<Double> speed = kasuga$manager.beforeSpeed();

        speed.ifPresent(aDouble -> this.speed = aDouble);
    }


    //@TODO: using mixinexrtas to avoid ....
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/trains/entity/Train;updateNavigationTarget(Lnet/minecraft/world/level/Level;D)V"
            )
    )
    public void doUpdateNavigationTarget(Train train, Level level, double distance){
        if(train.graph == null)return;
        this.updateNavigationTarget(level, distance);
//        KasugaLib.STACKS.RAILWAY.getIntegrator(train).addDistance(distance);
        if(kasuga$manager != null) {
            kasuga$manager.tick(level);
            kasuga$manager.notifySpeed(speed);
            kasuga$manager.notifyDistance(distance);
        }
    }



    @Inject(
            method = "frontSignalListener",
            at = @At("RETURN"),
            cancellable = true
    )
    public void doFrontSignalListener(CallbackInfoReturnable<TravellingPoint.IEdgePointListener> ci){
        TravellingPoint.IEdgePointListener oldListener = ci.getReturnValue();
        ci.setReturnValue((distance, pair)->{
            TrackEdgePoint edgePoint = pair.getFirst();
//            if(edgePoint instanceof BogeyObserverEdgePoint observerEdgePoint){
//                observerEdgePoint.notifyBogey((Train)(Object)this);
//                return false;
//            }
            if(kasuga$manager != null && kasuga$manager.notifySignalFront(distance, Pair.of(pair.getFirst(), pair.getSecond()))) return true;
            return oldListener.test(distance, pair);
        });
    }

    @Inject(
            method = "backSignalListener",
            at = @At("RETURN"),
            cancellable = true
    )
    public void doBackSignalListener(CallbackInfoReturnable<TravellingPoint.IEdgePointListener> ci){
        TravellingPoint.IEdgePointListener oldListener = ci.getReturnValue();

        ci.setReturnValue((distance, pair)->{
            TrackEdgePoint edgePoint = pair.getFirst();
//            if(edgePoint instanceof BogeyObserverEdgePoint observerEdgePoint){
//                observerEdgePoint.notifyBogey((Train)(Object)this);
//                return false;
//            }
            if(kasuga$manager != null && kasuga$manager.notifySignalBack(distance, Pair.of(pair.getFirst(), pair.getSecond()))) return false;
            return oldListener.test(distance, pair);
        });
    }

    @Inject(method = "<init>*", at = @At("RETURN"))
    public void onConstruct(CallbackInfo ci) {
        this.kasuga$manager = new TrainDeviceManager((Train) (Object) this);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void onRead(CompoundTag tag, HolderLookup.Provider registries, Map<UUID, TrackGraph> trackNetworks, DimensionPalette dimensions, CallbackInfoReturnable<Train> cir) {
        ((TrainDeviceProvider)cir.getReturnValue()).kasugaLib$getDeviceManager().read(tag);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void onWrite(DimensionPalette dimensions, HolderLookup.Provider registries, CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        this.kasuga$manager.write(tag);
    }

    @Override
    public TrainDeviceManager kasugaLib$getDeviceManager() {
        return this.kasuga$manager;
    }

    @Inject(method = "disassemble", at = @At("RETURN"))
    public void onDisassemble(Direction assemblyDirection, BlockPos pos, CallbackInfoReturnable<Boolean> cir){
        if(!cir.getReturnValue())
            return;
        if(this.kasuga$manager != null) {
            this.kasuga$manager.notifyDisassemble(assemblyDirection, pos);
        }
        TrainDeviceSyncManager.get().clearTrain(((Train) (Object) this).id);
    }

    @Inject(method= "collectInitiallyOccupiedSignalBlocks", at=@At("RETURN"))
    public void onCollectInitiallyOccupiedSignalBlocks(CallbackInfo ci){
        if(this.kasuga$manager != null) {
            this.kasuga$manager.notifySignalCollection();
        }
    }

    @Inject(method = "earlyTick", at=@At("RETURN"))
    public void onEarlyTick(Level level, CallbackInfo ci){
        if(this.kasuga$manager != null) {
            this.kasuga$manager.earlyTick(level);
        }
    }
}
