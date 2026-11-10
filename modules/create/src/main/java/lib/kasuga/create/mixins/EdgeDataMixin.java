package lib.kasuga.create.mixins;

import com.simibubi.create.content.trains.graph.EdgeData;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import lib.kasuga.KasugaLib;
import lib.kasuga.content.graph.GraphManager;
import lib.kasuga.create.content.train.graph.EdgeExtraData;
import lib.kasuga.create.content.train.graph.RailwayManager;
import lib.kasuga.create.content.train.signal.BoundarySegmentRegistry;
import lib.kasuga.create.content.train.signal.CustomBoundary;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.Lazy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = EdgeData.class, remap = false)
public class EdgeDataMixin {

    @Unique
    private static Lazy<RailwayManager> railwayManager =
            Lazy.of(() -> KasugaLib.getBean(RailwayManager.class));

    @Shadow private TrackEdge edge;

    @Inject(method = "addPoint",at=@At("HEAD"))
    private void onAddPoint(TrackGraph graph, TrackEdgePoint point, CallbackInfo ci){
        if (!(point instanceof CustomBoundary customBoundary)) {
            return;
        }
        ResourceLocation boundaryFeature = BoundarySegmentRegistry.getFeatureName(customBoundary);

        if(boundaryFeature == null)
            return;

        railwayManager.get().getData().withGraph(graph).getOrComputeEdgeData(edge).setBoundaryFeature(boundaryFeature, null);
    }

    @Inject(method = "removePoint",at=@At("TAIL"))
    private void onRemovePoint(TrackGraph graph, TrackEdgePoint point, CallbackInfo ci){
        EdgeData self = (EdgeData)(Object) this;

        if (!(point instanceof CustomBoundary customBoundary)) {
            return;
        }

        ResourceLocation boundaryFeature = BoundarySegmentRegistry.getFeatureName(customBoundary);

        if(boundaryFeature == null)
            return;

        UUID nextId = self.next(point.getType(), 0) == null ? EdgeExtraData.passiveBoundaryGroup : null;
        EdgeExtraData edgeData = railwayManager.get().getData().withGraph(graph).getEdgeData(edge);
        if(edgeData == null)
            return;
        edgeData.setBoundaryFeature(boundaryFeature, nextId);
    }
}
