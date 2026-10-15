package lib.kasuga.create.client.rendering.edge_point;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;

public class EdgePointOverlayRenderer {
    public void renderOverlay(
            LevelAccessor level,
            BlockPos pos,
            Direction.AxisDirection direction,
            BezierTrackPointLocation bezier,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int light,
            int overlay,
            float scale
    ) {
    }
}
