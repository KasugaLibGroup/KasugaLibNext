package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.NativeImage;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.data_type.MCRenderableContext;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.SpriteHolder;
import lib.kasuga.rendering.models.uml.backend.Backend;
import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.ColorizedMeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.util.ModelProfiler;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class MCBridge implements Bridge<BackendInstance> {

    private static final Vector4f DEFAULT_MESH_COLOR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector4f HIDDEN_MESH_COLOR = new Vector4f();

    @Getter
    @Setter
    private Map<String, Backend<?, BackendInstance, ?, ?>> backends;

    @Override
    public HashMap<Vertex, Vertex> transformVertices(Model model, SkeletonInstance skeleton, Vertex[] vertices) {
        if (KsgVertexBuffer.isGpuSkinningEnabled() || KsgVertexBuffer.isIrisGpuSkinningEnabled()) {
            return new HashMap<>();
        }
        if (skeleton.isBindPose()) {
            return new HashMap<>();
        }
        return skeleton.getVertexTransforms(model, this);
    }

    @Override
    public Mesh[] transformMeshes(Model model, SkeletonInstance skeleton, Mesh[] meshes) {
        return meshes;
    }

    @Override
    public BackendInstance getBackendRenderable(ModelInstance instance, HashMap<Vertex, Vertex> vertexMap, Mesh[] meshes) {
        Backend<?, BackendInstance, ?, ?> backend = getBackends() == null ? null : getBackends().get("mc_backend");
        if (!(backend instanceof MCBackend mcBackend)) {
            throw new IllegalStateException("Legacy MCBridge factory requires a registered mc_backend");
        }
        return createRenderable(instance, mcBackend.getExecutor());
    }

    /** Backend services are supplied explicitly; geometry conversion owns no executor. */
    public BackendInstance createRenderable(ModelInstance instance, ExecutorService executor) {
        return new BackendInstance(instance, executor, false);
    }

    public static Vector2f getUVPosition(Vector2f uv, float u0, float v0, float u1, float v1, float u2, float v2, float u3, float v3) {
        float x = uv.x();
        float y = uv.y();
        float invX = 1f - x;
        float invY = 1f - y;
        float w0 = invX * invY;
        float w1 = x * invY;
        float w2 = x * y;
        float w3 = invX * y;
        return new Vector2f(
                u0 * w0 + u1 * w1 + u2 * w2 + u3 * w3,
                v0 * w0 + v1 * w1 + v2 * w2 + v3 * w3
        );
    }

    @Override
    public BoneBindingFunc getBoneBindingFunc(
            Model model,
            SkeletonInstance skeleton,
            Vertex vertex
    ) {
        return vertex.getBinding().getFunc();
    }

    @Override
    public MCRenderableContext getBackendContext(ModelInstance modelInstance) {
        return  new MCRenderableContext(this, modelInstance);
    }

    public static final net.minecraft.core.Direction toMCDirection(Direction direction) {
        return switch (direction) {
            case DOWN -> net.minecraft.core.Direction.DOWN;
            case UP -> net.minecraft.core.Direction.UP;
            case NORTH -> net.minecraft.core.Direction.NORTH;
            case SOUTH -> net.minecraft.core.Direction.SOUTH;
            case WEST -> net.minecraft.core.Direction.WEST;
            case EAST -> net.minecraft.core.Direction.EAST;
        };
    }

    public static final Direction fromMCDirection(net.minecraft.core.Direction direction) {
        return switch (direction) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    public static int bindingType(BoneBindingFunc func) {
        if (func == BoneBindingFunc.SDEF) return 1;
        if (func == BoneBindingFunc.QDEF) return 2;
        return 0;  // BDEF
    }
}
