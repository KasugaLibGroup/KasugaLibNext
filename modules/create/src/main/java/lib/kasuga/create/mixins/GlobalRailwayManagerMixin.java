package lib.kasuga.create.mixins;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackNode;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.content.train.graph.RailwayManager;
import lib.kasuga.create.content.train.sync.CarriageExtraBogeySync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.Lazy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

@Mixin(value = GlobalRailwayManager.class, remap = false)
public class GlobalRailwayManagerMixin {

    @Unique private static Lazy<RailwayManager> railwayManager =
            Lazy.of(() -> KasugaLib.getBean(RailwayManager.class));


    @Shadow public Map<UUID, TrackGraph> trackNetworks;

    @Shadow public Map<UUID, Train> trains;

    /**
     * Re-sends extra-bogey data for existing trains when a player joins. Trains assembled while the
     * player was online are covered by {@code StationBlockEntityMixin}; this pass handles trains
     * loaded from disk. {@code TAIL} ensures the track graph and {@code AddTrainPacket}s were
     * written first, and play-phase payloads arrive in write order, so the client already knows the
     * trains.
     */
    @Inject(method = "playerLogin", at = @At("TAIL"))
    public void onPlayerLogin(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer))
            return;
        for (Train train : trains.values())
            CarriageExtraBogeySync.send(serverPlayer, train);
    }

    @Inject(method = "loadTrackData", at = @At("TAIL"))
    public void onLoadTrackData(MinecraftServer server, CallbackInfo ci){
        railwayManager.get().load(server);
        railwayManager.get().getData().syncExtraData(Create.RAILWAYS.trackNetworks.keySet());
        trackNetworks.forEach(((uuid, graph) -> {
            HashSet<TrackEdge> edges = new HashSet<>();
            for (Map<TrackNode, TrackEdge> value : ((TrackGraphAccessor) graph).getConnectionsByNode().values()) {
                for (TrackEdge edge : value.values()) {
                    edges.add(edge);
                }
            }
            railwayManager.get().getData().withGraph(graph).syncWithExternal(edges);
        }));
    }

    @Inject(method = "putGraph", at = @At("TAIL"))
    public void onPutGraph(TrackGraph graph, CallbackInfo ci){
        railwayManager.get().getData().createExtraData(graph.id);
    }

    @Inject(method = "removeGraph", at = @At("TAIL"))
    public void onRemoveGraph(TrackGraph graph, CallbackInfo ci){
        railwayManager.get().getData().removeExtraData(graph.id);
    }

    @Inject(method = "tick", at=@At("HEAD"))
    public void onTick(Level level, CallbackInfo ci){
        if(level.dimension() != Level.OVERWORLD)
            return;
        railwayManager.get().getData().onEarlyGlobalTick(level);
    }
}
