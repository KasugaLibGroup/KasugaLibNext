package lib.kasuga.rendering.output.mc.iris;

import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import java.util.List;

/** Shader suppliers read these statics while drawing; retain their values per camera. */
record CameraIrisShadowState(boolean active, List<BlockEntity> visibleBlocks, int distance,
                             Matrix4f modelView, Matrix4f projection, Frustum frustum,
                             boolean renderingLevel, boolean tessellation, boolean extended, boolean bypass, boolean merge) {
    static CameraIrisShadowState capture() {
        return new CameraIrisShadowState(ShadowRenderer.ACTIVE, ShadowRenderer.visibleBlockEntities, ShadowRenderer.renderDistance,
                ShadowRenderer.MODELVIEW, ShadowRenderer.PROJECTION, ShadowRenderer.FRUSTUM,
                ImmediateState.isRenderingLevel, ImmediateState.usingTessellation, ImmediateState.renderWithExtendedVertexFormat,
                ImmediateState.bypass, ImmediateState.mergeRendering);
    }
    void install() {
        ShadowRenderer.ACTIVE = active; ShadowRenderer.visibleBlockEntities = visibleBlocks; ShadowRenderer.renderDistance = distance;
        ShadowRenderer.MODELVIEW = modelView; ShadowRenderer.PROJECTION = projection; ShadowRenderer.FRUSTUM = frustum;
        ImmediateState.isRenderingLevel = renderingLevel; ImmediateState.usingTessellation = tessellation;
        ImmediateState.renderWithExtendedVertexFormat = extended; ImmediateState.bypass = bypass; ImmediateState.mergeRendering = merge;
    }
    static CameraIrisShadowState initial() {
        return new CameraIrisShadowState(false, List.of(), 0, new Matrix4f(), new Matrix4f(), null, false, false, false, false, false);
    }
}
