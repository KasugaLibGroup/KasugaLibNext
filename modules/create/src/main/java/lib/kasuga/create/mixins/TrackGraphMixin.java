package lib.kasuga.create.mixins;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.content.train.graph.GraphExtraData;
import lib.kasuga.create.content.train.graph.RailwayManager;
import net.createmod.catnip.data.Couple;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.common.util.Lazy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import javax.annotation.Nullable;
import java.util.Map;

@Mixin(value = TrackGraph.class, remap = false)
public abstract class TrackGraphMixin {

    @Unique private static Lazy<RailwayManager> railwayManager =
            Lazy.of(() -> KasugaLib.getBean(RailwayManager.class));

    @Shadow public abstract TrackNode getNode(int netId);

    @Shadow public abstract TrackNode locateNode(TrackNodeLocation position);

    @Shadow public abstract Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode node);

    @Shadow private Map<TrackNode, Map<TrackNode, TrackEdge>> connectionsByNode;

    @Inject(method = "transferAll", at = @At("TAIL"))
    public void onTransferAll(TrackGraph toOther, CallbackInfo ci){
        GraphExtraData toOtherExtra = railwayManager.get().getData().withGraph(toOther);
        railwayManager.get().getData().withGraph(((TrackGraph) (Object)this)).transferAll(toOtherExtra);
    }

    @Inject(method = "transfer", at = @At("HEAD"), locals = LocalCapture.CAPTURE_FAILHARD)
    public void onTransfer(
            LevelAccessor level,
            TrackNode node,
            TrackGraph target,
            CallbackInfo ci
    ){
        if(level == null || level.isClientSide())
            return;
        Map<TrackNode, TrackEdge> connections = this.getConnectionsFrom(node);
        GraphExtraData targetExtraData = railwayManager.get().getData().withGraph(target);
        railwayManager.get().getData().withGraph(((TrackGraph) (Object)this)).transfer(level, node, connections, targetExtraData);
    }

    @Inject(method = "connectNodes", at = @At("TAIL"))
    void onConnectNodes(
            LevelAccessor reader,
            TrackNodeLocation.DiscoveredLocation location,
            TrackNodeLocation.DiscoveredLocation location2,
            @Nullable BezierConnection turn,
            CallbackInfo ci
    ){
        if(reader != null && reader.isClientSide()){
            return;
        }
        final TrackGraph $this = ((TrackGraph) (Object)this);
        TrackNode node1 = locateNode(location);
        TrackNode node2 = locateNode(location2);
        TrackEdge edge = $this.getConnection(Couple.create(node1, node2));
        TrackEdge edgeReverse = $this.getConnection(Couple.create(node2, node1));
        railwayManager.get().getData().withGraph($this).createEdge(edge);
        railwayManager.get().getData().withGraph($this).createEdge(edgeReverse);
    }

    @Inject(method = "removeNode", at = @At("HEAD"))
    void onRemoveNodes(
            @Nullable LevelAccessor reader,
            TrackNodeLocation location,
            CallbackInfoReturnable<Boolean> ci
    ){
        if(reader == null || reader.isClientSide())
            return;
        TrackNode node = locateNode(location);
        if(node == null)
            return;
        final TrackGraph $this = ((TrackGraph) (Object)this);
        Map<TrackNode, TrackEdge> connections = getConnectionsFrom(node);
        connections.forEach((_node,edge)->{
            railwayManager.get().getData().withGraph($this).removeEdge(edge);
        });
        // @TODO: Create's code, add MIT's LICENSE
        for (TrackNode fromNodes : connections.keySet())
            if (connectionsByNode.containsKey(fromNodes)) {
                TrackEdge edge = connectionsByNode.get(fromNodes).get(node);
                railwayManager.get().getData().withGraph($this).removeEdge(edge);
            }
    }
}
