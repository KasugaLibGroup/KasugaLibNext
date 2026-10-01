package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.SpriteHolder;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.dynamic.morph.MorphInstance;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.ColorizedMeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.SpriteSet;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.BiConsumer;

public class FlatModelData implements AutoCloseable {

    protected final ModelInstance model;

    public static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN);

    @Nullable
    private final ModelRenderPass renderPass;

    @Getter
    protected final int vertexCount;

    @Getter
    protected final int vertexSize;

    @Getter
    protected final int posOffset, normOffset, colorOffset,
            tangOffset, uv0Offset, uv1Offset, uv2Offset,
            overlayOffset, lightmapOffset,
            bindingTypeOffset, bindingBoneOffset, bindingWeightOffset,
            sdefR0Offset, sdefR1Offset, sdefCOffset,
            textureUvOffset, textureBoundsOffset, alphaCutoffOffset;

    @Getter
    protected final ByteBuffer buffer;

    private final Mesh[] meshes;
    private final float[] meshNormals;
    private final float[] meshColors;
    private final BitSet dirtyMeshes;
    private final BitSet allMorphedMeshes;

    private final Map<Mesh, Integer[]> vertexByMesh;           // 用以映射颜色
    private final Map<Vertex, Integer[]> vertexByIndex;        // 用以映射位置、法线
    private final Map<Material, Integer[]> vertexByMaterials;  // 用以映射颜色、uv
    private final Map<Bone, Integer[]> vertexByBone;           // 用以映射骨骼

    private final Vertex[] vertices;           // fixed
    private final int[] vertexMaterials;       // fixed
    private final int[] vertexMeshes;          // fixed

    @Getter
    private final BitSet dirtyVertices;        // dynamic
    private final BitSet allMorphedVertices;   // dynamic

    private final float[] positions;           // dynamic
    private final float[] basicOffsets;        // fixed
    private final BitSet dirtyPositions;       // dynamic
    private final BitSet allMorphedPositions;  // dynamic

    private final float[] normals;             // dynamic
    private final BitSet dirtyNormals;         // dynamic
    private final BitSet allMorphedNormals;    // dynamic

    private final float[] tangents;            // dynamic;

    private final float[] colors;              // dynamic
    private final BitSet dirtyColors;          // dynamic
    private final BitSet allMorphedColors;     // dynamic

    private final float[] uv0s;                      // dynamic
    private final BitSet dirtyUv0s;            // dynamic
    private final BitSet allMorphedUv0;        // dynamic

    private final int[] weightOffsets;         // fixed
    private final int[] weightCounts;          // fixed
    private final int[] vertexBones;           // fixed
    private final float[] weights;                   // dynamic
    private final BitSet dirtyWeights;         // dynamic
    private final BitSet allMorphedWeights;    // dynamic

    private final Bone[] bones;                // fixed
    private final Transform[] bindInverses;    // fixed
    private final Transform[] absTransforms;   // dynamic
    private final BitSet dirtyBones;           // dynamic
    private final BitSet allMorphedBones;      // dynamic

    private final Material[] materials;        // fixed
    private final float[] materialUvBounds;    // dynamic
    private final float[] materialColors;      // dynamic
    private final BitSet dirtyMaterials;       // dynamic
    private final BitSet allMorphedMaterials;  // dynamic

    private final MeshMode meshMode;           // fixed
    private final MeshMode instanceMeshMode;   // fixed

    @Getter
    private float brightness = 1f;

    @Getter
    private boolean readAlpha = true;

    @Getter
    private int overlay = OverlayTexture.NO_OVERLAY,
                lightmap = LightTexture.FULL_BLOCK;

    private boolean needToUpdateAll = false;
    private boolean closed;

    private final BiConsumer<Sprite, Vector4f> materialColorBlender;  // fixed
    private final boolean cpuSkinning;

    private long skeletonVersion;
    private long morphVersion, materialVersion, materialMorphVersion;
    @Getter
    private long contentVersion;
    @Getter
    private long skinningSourceVersion;
    private final BitSet changedModelVertices = new BitSet();
    private final BitSet changedModelMaterials = new BitSet();
    private final BitSet changedMorphMaterials = new BitSet();

    public FlatModelData(ModelInstance model,
                         int vertexSize,
                         Map<VertexFormatElement, Integer> bufOffsets,
                         @Nullable BiConsumer<Sprite, Vector4f> materialColorBlender,
                         float brightness, boolean readAlpha, boolean cpuSkinning,
                         int overlay, int lightmap) {
        this(model, vertexSize, bufOffsets, materialColorBlender, brightness, readAlpha,
                cpuSkinning, overlay, lightmap, null);
    }

    public FlatModelData(ModelInstance model,
                         int vertexSize,
                         Map<VertexFormatElement, Integer> bufOffsets,
                         @Nullable BiConsumer<Sprite, Vector4f> materialColorBlender,
                         float brightness, boolean readAlpha, boolean cpuSkinning,
                         int overlay, int lightmap,
                         @Nullable ModelRenderPass renderPass) {
        this.model = model;
        this.renderPass = renderPass;
        this.meshMode = model.getModel().getMeshMode();
        this.instanceMeshMode = model.getMeshMode();
        this.vertexSize = vertexSize;
        this.materialColorBlender =  materialColorBlender;
        this.brightness = brightness;
        this.readAlpha = readAlpha;
        this.cpuSkinning = cpuSkinning;
        this.overlay = overlay;
        this.lightmap = lightmap;
        this.skeletonVersion = model.getSkeletonInstance().getVersion();

        this.posOffset = bufOffsets.get(VertexFormatElement.POSITION);
        this.normOffset = bufOffsets.getOrDefault(VertexFormatElement.NORMAL, -1);
        this.colorOffset = bufOffsets.getOrDefault(VertexFormatElement.COLOR, -1);
        this.tangOffset = bufOffsets.getOrDefault(RenderState.TANGENT, -1);
        this.uv0Offset = bufOffsets.getOrDefault(VertexFormatElement.UV, -1);
        this.overlayOffset = bufOffsets.getOrDefault(VertexFormatElement.UV1, -1);
        this.lightmapOffset = bufOffsets.getOrDefault(VertexFormatElement.UV2, -1);
        this.bindingTypeOffset = bufOffsets.getOrDefault(RenderState.BONE_BINDING_TYPE, -1);
        this.bindingBoneOffset = bufOffsets.getOrDefault(RenderState.BONE_INDICES, -1);
        this.bindingWeightOffset = bufOffsets.getOrDefault(RenderState.BONE_WEIGHTS, -1);
        this.sdefR0Offset = bufOffsets.getOrDefault(RenderState.SDEF_R0, -1);
        this.sdefR1Offset = bufOffsets.getOrDefault(RenderState.SDEF_R1, -1);
        this.sdefCOffset = bufOffsets.getOrDefault(RenderState.SDEF_C, -1);
        this.textureUvOffset = bufOffsets.getOrDefault(RenderState.TEXTURE_UV, -1);
        this.textureBoundsOffset = bufOffsets.getOrDefault(RenderState.TEXTURE_BOUNDS, -1);
        this.alphaCutoffOffset = bufOffsets.getOrDefault(RenderState.ALPHA_CUTOFF, -1);

        this.materials = model.getModel().getMaterialSet().getMaterials();
        this.materialUvBounds = new float[materials.length * 8];
        this.materialColors = new float[materials.length * 4];
        this.dirtyMaterials = new BitSet(materials.length);
        this.allMorphedMaterials = new BitSet(this.materials.length);
        for (int i = 0; i < materials.length; i++) {
            setupMaterial(i, materialColorBlender);
        }

        int u1Off = -1, u2Off = -1;
        for (Map.Entry<VertexFormatElement, Integer> entry : bufOffsets.entrySet()) {
            VertexFormatElement element = entry.getKey();
            if (element.usage().equals(VertexFormatElement.Usage.UV)) {
                if (element.index() == 3) {
                    u1Off = entry.getValue();
                } else if (element.index() == 4) {
                    u2Off = entry.getValue();
                }
            }
        }
        this.uv1Offset = u1Off;
        this.uv2Offset = u2Off;

        this.meshes = model.getModel().getMeshes();
        this.bones = model.getModel().getBones();
        this.absTransforms = new Transform[bones.length];
        this.bindInverses =  new Transform[bones.length];
        SkeletonInstance skl = model.getSkeletonInstance();
        if (skl.checkShouldUpdate()) skl.updateTransform();
        for (int i = 0; i < bones.length; i++) {
            Bone bone = bones[i];
            Transform absTransform = skl.getAbsoluteTransforms().get(bone);
            Transform bindInv = skl.getSkeleton().getBindingInverse(bone);
            this.absTransforms[i] = absTransform;
            this.bindInverses[i] = bindInv;
        }
        this.dirtyBones = new BitSet(bones.length);
        this.allMorphedBones = new BitSet(bones.length);

        this.meshNormals = new float[meshes.length * 3];
        this.meshColors = new float[meshes.length * 4];
        this.dirtyMeshes = new BitSet(meshes.length);
        this.allMorphedMeshes = new BitSet(meshes.length);

        this.vertexByMesh = new HashMap<>();
        this.vertexByIndex = new HashMap<>();
        this.vertexByMaterials = new HashMap<>();
        this.vertexByBone = new HashMap<>();

        // 先统计顶点的总数量
        Vector4f meshColor = new Vector4f();
        int vCount = 0;
        for (int i = 0; i < meshes.length; i++) {
            Mesh mesh = meshes[i];
            setMeshNormals(i, mesh.getNormal());
            meshColor.set(1, 1, 1, 1);
            if (mesh.getData() instanceof ColorizedMeshData colorized) {
                meshColor.set(colorized.getColor());
            }
            setMeshColors(i, meshColor);

            // 每个纹理分别处理为一个面，每一层纹理稍微往外凸一点点
            vCount += calculateVertexCount(mesh.getVertices().length) *
                    (renderPass == null ? mesh.getMaterials().length :
                            (int) Arrays.stream(mesh.getMaterials())
                                    .filter(renderPass::matches).count());
        }

        this.vertexCount = vCount;
        this.vertices = new Vertex[vertexCount];
        this.vertexMaterials = new int[vertexCount];
        this.vertexMeshes = new int[vertexCount];
        this.dirtyVertices = new BitSet(vertexCount);
        this.allMorphedVertices = new BitSet(vertexCount);

        this.positions = new float[vertexCount * 3];
        this.basicOffsets = new float[vertexCount * 3];
        this.dirtyPositions = new BitSet(vertexCount);
        this.allMorphedPositions = new BitSet(vertexCount);

        this.normals = new float[vertexCount * 3];
        this.dirtyNormals = new BitSet(vertexCount);
        this.allMorphedNormals = new BitSet(vertexCount);
        this.tangents = new float[vertexCount * 4];

        this.colors = new float[vertexCount * 4];
        this.dirtyColors = new BitSet(vertexCount);
        this.allMorphedColors = new BitSet(vertexCount);

        this.uv0s = new float[vertexCount * 2];
        this.dirtyUv0s =  new BitSet(vertexCount);
        this.allMorphedUv0 = new BitSet(vertexCount);

        this.weightOffsets = new int[vertexCount];
        this.weightCounts = new int[vertexCount];
        this.dirtyWeights = new BitSet(vertexCount);
        this.allMorphedWeights = new BitSet(vertexCount);
        this.buffer = MemoryUtil.memAlloc(vertexCount * vertexSize);
        buffer.order(ByteOrder.nativeOrder());

        Vector3f posOffset = new Vector3f();
        Vector3f sumPosOffset = new Vector3f();
        Vector3f vertexPosition = new Vector3f();
        Vector3f vertexNorm =  new Vector3f();
        Vector2f vertexUv = new Vector2f();

        Map<Material, Integer> materialIndices = new HashMap<>();
        for (int i = 0; i < materials.length; i++) {
            Material material = materials[i];
            materialIndices.put(material, i);
        }

        int sumWeights = 0, vertexPos = 0;
        float offsetScale = .0001f;
        BoneBinding binding;
        Map<Material, ArrayList<Integer>> buildingMaterialIndices = new HashMap<>();
        Map<Mesh, ArrayList<Integer>> buildingMeshIndices = new HashMap<>();
        Map<Vertex, ArrayList<Integer>> buildingVertexIndices = new HashMap<>();
        Map<Bone, ArrayList<Integer>> buildingBoneIndices = new HashMap<>();
        ArrayList<Integer> currentMaterialIndices;
        ArrayList<Integer> currentMeshIndices;
        Vector4f vColor = new Vector4f(1, 1, 1, 1);
        for (int i = 0; i < meshes.length; i++) {
            Mesh mesh = meshes[i];
            fillMeshNormal(i, posOffset);
            posOffset.mul(offsetScale);
            sumPosOffset.set(0, 0, 0);
            Material[] materials = mesh.getMaterials();
            Vertex[] vertices = flatVertices(mesh.getVertices());
            currentMeshIndices = buildingMeshIndices.
                    computeIfAbsent(mesh, m -> new ArrayList<>());

            for (Material mat :  materials) {
                if (renderPass != null && !renderPass.matches(mat)) continue;
                currentMaterialIndices = buildingMaterialIndices.
                        computeIfAbsent(mat, m -> new ArrayList<>());
                for (Vertex vertex : vertices) {
                    this.vertices[vertexPos] = vertex;
                    model.getVertexPosition(vertex, vertexPosition);
                    setVertexPosition(vertexPos, vertexPosition);
                    model.getVertexNormal(vertex, mesh, vertexNorm);
                    setVertexNormal(vertexPos, vertexNorm);
                    setVertexBasicOffset(vertexPos, sumPosOffset);
                    setVertexColor(vertexPos, vColor);

                    model.getVertexUv(vertex, mesh, mat, vertexUv);
                    setVertexUv(vertexPos, vertexUv);
                    vertexMaterials[vertexPos] = materialIndices.get(mat);
                    vertexMeshes[vertexPos] = i;
                    binding = vertex.getBinding();
                    weightOffsets[vertexPos] = sumWeights;
                    weightCounts[vertexPos] = binding.getWeights().length;
                    sumWeights += binding.getWeights().length;

                    for (Pair<Bone, Float> weight : binding.getWeights()) {
                        buildingBoneIndices.computeIfAbsent(
                                weight.getFirst(), b -> new ArrayList<>()
                        ).add(vertexPos);
                        Bone b = weight.getFirst();
                        while ((b = b.getParent()) != null) {
                            buildingBoneIndices.computeIfAbsent(
                                    b, vb -> new ArrayList<>()
                            ).add(vertexPos);
                        }
                    }

                    currentMaterialIndices.add(vertexPos);
                    currentMeshIndices.add(vertexPos);

                    buildingVertexIndices.computeIfAbsent(
                            vertex, m -> new ArrayList<>()
                    ).add(vertexPos);
                    vertexPos++;
                }
                sumPosOffset.add(posOffset);
            }
        }
        materialIndices.clear();

        for (Map.Entry<Material, ArrayList<Integer>> entry : buildingMaterialIndices.entrySet()) {
            this.vertexByMaterials.put(entry.getKey(), entry.getValue().toArray(new Integer[0]));
        }
        buildingMaterialIndices.clear();
        for (Map.Entry<Mesh, ArrayList<Integer>> entry : buildingMeshIndices.entrySet()) {
            this.vertexByMesh.put(entry.getKey(), entry.getValue().toArray(new Integer[0]));
        }
        buildingMeshIndices.clear();
        for  (Map.Entry<Vertex, ArrayList<Integer>> entry : buildingVertexIndices.entrySet()) {
            this.vertexByIndex.put(entry.getKey(), entry.getValue().toArray(new Integer[0]));
        }
        buildingVertexIndices.clear();
        for (Map.Entry<Bone, ArrayList<Integer>> entry : buildingBoneIndices.entrySet()) {
            this.vertexByBone.put(entry.getKey(), entry.getValue().toArray(new Integer[0]));
        }
        buildingBoneIndices.clear();

        weights = new float[sumWeights];
        vertexBones = new int[sumWeights];
        for (int i = 0; i < vertices.length; i++) {
            resetVertexWeight(i, true);
        }

        calculateTangents();
        buildupBuffer(cpuSkinning);
    }

    public boolean updateModel() {
        return updateModel(true);
    }

    boolean updateModel(boolean evaluateModel) {
        boolean modelUpdated = evaluateModel && model.checkForUpdate();
        if (modelUpdated) model.update();
        updateMorphedMaterials();
        MorphInstance<?> morph = model.getMorph();
        morphVersion = morph.getVertexChanges().collectSince(morphVersion, changedModelVertices);
        Vector3f pos = new Vector3f(), normal = new Vector3f();
        Vector2f uv = new Vector2f();
        boolean vertexChanged = false;
        for (int index = changedModelVertices.nextSetBit(0); index >= 0;
             index = changedModelVertices.nextSetBit(index + 1)) {
            Integer[] instances = vertexByIndex.get(model.getModel().getVertices()[index]);
            if (instances == null) continue;
            vertexChanged = true;
            for (int i : instances) {
                Vertex vertex = vertices[i];
                Mesh mesh = meshes[vertexMeshes[i]];
                model.getVertexPosition(vertex, pos);
                setVertexPosition(i, pos);
                model.getVertexNormal(vertex, mesh, normal);
                setVertexNormal(i, normal);
                model.getVertexUv(vertex, mesh, materials[vertexMaterials[i]], uv);
                setVertexUv(i, uv);
                dirtyVertices.set(i);
                dirtyPositions.set(i);
            }
        }
        if (vertexChanged) skinningSourceVersion++;
        if (vertexChanged && tangOffset >= 0) {
            calculateTangents();
            dirtyVertices.set(0, vertexCount); // Triangle neighbours also receive new tangents.
        }
        updateForVersion();
        for (int i = dirtyMaterials.nextSetBit(0); i >= 0; i = dirtyMaterials.nextSetBit(i + 1)) {
            markMaterialDirty(materials[i], dirtyVertices);
        }
        dirtyMaterials.clear();
        updateBOL();
        boolean contentChanged = !dirtyVertices.isEmpty();
        if (contentChanged) {
            ArrayList<BoneContext> contexts = new ArrayList<>();
            Vector3f p = new Vector3f(), p2 = new Vector3f(), n = new Vector3f(), n2 = new Vector3f(), t2 = new Vector3f();
            Vector4f t = new Vector4f();
            for (int i = dirtyVertices.nextSetBit(0); i >= 0; i = dirtyVertices.nextSetBit(i + 1)) {
                fillVertexData(i, uv, contexts, p, p2, n, n2, t, t2, true);
            }
            contentVersion++;
        }
        return modelUpdated || contentChanged;
    }

    /**
     * B: Brightness
     * O: Overlay
     * L: Light
     */
    public void updateBOL() {
        if (!this.needToUpdateAll) return;
        int bufOrg = 0;
        for (int i = 0; i < vertexCount; i++) {
            fillLightAndOverlayToBuffer(bufOrg);
            fillColorToBuffer(i, bufOrg);
            dirtyColors.clear(i);
            dirtyVertices.set(i);
            bufOrg += vertexSize;
        }
        this.needToUpdateAll = false;
    }

    public void updateMorphedMaterials() {
        MaterialSetInstance instance = model.getMaterialInstance();
        changedModelMaterials.clear();
        if (instance != null) materialVersion = instance.getChanges().collectSince(materialVersion, changedModelMaterials);
        materialMorphVersion = model.getMorph().getMaterialChanges().collectSince(materialMorphVersion, changedMorphMaterials);
        changedModelMaterials.or(changedMorphMaterials);
        for (int i = changedModelMaterials.nextSetBit(0); i >= 0; i = changedModelMaterials.nextSetBit(i + 1)) {
            setupMaterial(i, materialColorBlender);
        }
        if (instance != null) instance.clearDirty(); // Compatibility flags only; versioned readers retain every change.
    }

    public void updateForVersion() {
        if (!cpuSkinning) return;
        SkeletonInstance skeletonInstance = model.getSkeletonInstance();
        if (skeletonInstance.checkShouldUpdate()) skeletonInstance.updateTransform();
        if (this.skeletonVersion == skeletonInstance.getVersion()) return;
        this.skeletonVersion = skeletonInstance.getVersion();

        Transform neoTransform;
        for (int i = 0; i < bones.length; i++) {
            neoTransform = skeletonInstance.getAbsoluteTransforms().get(bones[i]);
            absTransforms[i] = neoTransform;
            markBoneDirty(bones[i], dirtyPositions, dirtyVertices);
        }

        int count = dirtyPositions.cardinality();
        int maxMergeGap = 64;
        if (count * 4 > vertexCount * 3) {
            buildupBuffer(true);
            return;
        }

        ArrayList<BoneContext> cacheList = new ArrayList<BoneContext>();
        Vector3f posCache = new Vector3f(),
                posCache2 = new Vector3f(),
                normalCache = new Vector3f(),
                normalCache2 = new Vector3f();
        Vector4f tangentCache = new Vector4f();
        Vector3f tangentCache2 = new Vector3f();

        int start = dirtyPositions.nextSetBit(0);
        while (start >= 0) {
            int end = dirtyPositions.nextClearBit(start);
            int next = dirtyPositions.nextSetBit(end);
            while (next >= 0 && next - end <= maxMergeGap) {
                end = dirtyPositions.nextClearBit(next);
                next = dirtyPositions.nextSetBit(end);
            }
            end = Math.min(end, vertexCount);
            for (int i = start; i < end; i++) {
                int bufOrg = i * vertexSize;
                fillCpuSkinningElements(i, bufOrg,
                        cacheList,
                        posCache, posCache2,
                        normalCache, normalCache2,
                        tangentCache, tangentCache2,
                        true);
            }
            start = next;
        }
    }

    public void markBoneDirty(Bone bone, BitSet... vertexBitsets) {
        Integer[] dirtyVertices = vertexByBone.get(bone);
        if (dirtyVertices == null) return;
        for (int i : dirtyVertices) {
            for (BitSet bs : vertexBitsets) {
                bs.set(i);
            }
        }
    }

    public void markMeshDirty(Mesh mesh, BitSet... vertexBitsets) {
        Integer[] dirtyVertices = vertexByMesh.get(mesh);
        if (dirtyVertices == null) return;
        for (int i : dirtyVertices) {
            for (BitSet bs : vertexBitsets) {
                bs.set(i);
            }
        }
    }

    public void markMaterialDirty(Material mat, BitSet... vertexBitsets) {
        Integer[] dirtyVertices = vertexByMaterials.get(mat);
        if (dirtyVertices == null) return;
        for (int i : dirtyVertices) {
            for (BitSet bs :  vertexBitsets) {
                bs.set(i);
            }
        }
    }

    public void markVertexDirty(Vertex vertex, BitSet... vertexBitsets) {
        Integer[] dirtyIndices = vertexByIndex.get(vertex);
        if (dirtyIndices == null) return;
        for (int i : dirtyIndices) {
            for (BitSet bs : vertexBitsets) {
                bs.set(i);
            }
        }
    }

    public void setBrightness(float brightness) {
        brightness = Math.clamp(brightness, 0f, 1f);
        if (this.brightness != brightness) {
            this.brightness = brightness;
            this.needToUpdateAll = true;
        }
    }

    public void setReadAlpha(boolean readAlpha) {
        if (this.readAlpha != readAlpha) {
            this.readAlpha = readAlpha;
            this.needToUpdateAll = true;
        }
    }

    public void setOverlay(int packedOverlay) {
        if (packedOverlay != this.overlay) {
            this.overlay = packedOverlay;
            this.needToUpdateAll = true;
        }
    }

    public void setLight(int packedLight) {
        if (packedLight != this.lightmap) {
            this.lightmap = packedLight;
            this.needToUpdateAll = true;
        }
    }

    protected void resetVertexUV(int vertexIndex, Vector2f cache) {
        Vertex vertex = vertices[vertexIndex];
        Mesh mesh = meshes[vertexMeshes[vertexIndex]];
        Material mat = materials[vertexMaterials[vertexIndex]];

        cache.set(vertex.getUV(mesh, mat));
        setVertexUv(vertexIndex, cache);
        dirtyUv0s.set(vertexIndex);
        allMorphedUv0.clear(vertexIndex);
    }

    protected void resetVertexPosition(int vertexIndex, Vector3f cache) {
        Vertex vertex = vertices[vertexIndex];
        cache.set(vertex.getPosition());
        setVertexPosition(vertexIndex, cache);
        dirtyVertices.set(vertexIndex);
        allMorphedVertices.clear(vertexIndex);
    }

    protected void resetMeshColor(int meshIndex, Vector4f cache) {
        Mesh mesh = meshes[meshIndex];
        if (mesh instanceof ColorizedMeshData colorized) {
            cache.set(colorized.getColor());
        } else {
            cache.set(1, 1, 1, 1);
        }
        setMeshColors(meshIndex, cache);
        dirtyMeshes.set(meshIndex);
        allMorphedMaterials.clear(meshIndex);
    }

    protected void resetAbsTransform(int boneIndex) {
        SkeletonInstance skl = model.getSkeletonInstance();
        Transform absTransform = skl.getAbsoluteTransforms().get(bones[boneIndex]);
        absTransforms[boneIndex] = absTransform;
        dirtyBones.set(boneIndex);
        allMorphedBones.clear(boneIndex);
    }

    protected void setupMaterial(int materialIndex, BiConsumer<Sprite, Vector4f> consumer) {
        Vector4f matColor = new Vector4f(1f, 1f, 1f, 1f);
        Material materialCache = materials[materialIndex];
        setMaterialUvBounds(materialIndex, materialCache);
        calculateMaterialColor(materialCache, matColor, consumer);
        setMaterialColor(materialIndex, matColor);
        dirtyMaterials.set(materialIndex);
        allMorphedMaterials.clear(materialIndex);
    }

    protected void resetVertexWeight(int vertexIndex, boolean init) {
        Vertex vertex = vertices[vertexIndex];
        BoneBinding binding = vertex.getBinding();
        int weightIndex = weightOffsets[vertexIndex];
        for (int j = 0; j < binding.getWeights().length; j++) {
            Pair<Bone, Float> pair = binding.getWeights()[j];
            vertexBones[weightIndex + j] = pair.getFirst().getIndex();
            weights[weightIndex + j] = pair.getSecond();
        }
        if (init) return;
        dirtyWeights.set(vertexIndex);
        allMorphedWeights.clear(vertexIndex);
    }

    public void buildupBuffer(boolean updateTangent) {
        Vector2f v2fCache = new Vector2f();
        ArrayList<BoneContext> cacheList = new ArrayList<BoneContext>();
        Vector3f posCache = new Vector3f(),
                posCache2 = new Vector3f(),
                normalCache = new Vector3f(),
                normalCache2 = new Vector3f();
        Vector4f tangentCache = new Vector4f();
        Vector3f tangentCache2 = new Vector3f();
        for (int i = 0; i < vertexCount; i++) {
            fillVertexData(i, v2fCache, cacheList,
                    posCache, posCache2,
                    normalCache, normalCache2,
                    tangentCache, tangentCache2,
                    updateTangent);
        }
    }

    public void fillVertexData(int vertex, Vector2f v2fCache, ArrayList<BoneContext> cacheList,
                               Vector3f posCache, Vector3f posCache2,
                               Vector3f normalCache, Vector3f normalCache2,
                               Vector4f tangentCache, Vector3f tangentCache2,
                               boolean updateTangent) {
        int bufOrg = vertex * vertexSize;
        if (cpuSkinning) {
            fillCpuSkinningElements(vertex, bufOrg,
                    cacheList,
                    posCache, posCache2,
                    normalCache, normalCache2,
                    tangentCache, tangentCache2,
                    updateTangent);
        } else {
            fillFullPositionToBuffer(vertex, bufOrg);
            fillNormalToBuffer(vertex, bufOrg);
            fillTangentToBuffer(vertex, bufOrg);
        }
        fillColorToBuffer(vertex, bufOrg);
        fillAlphaCutoffToBuffer(vertex, bufOrg);
        fillUvToBuffer(vertex, bufOrg, v2fCache);

        fillBoneAndWeightToBuffer(vertex, bufOrg);
        fillSdefDataToBuffer(vertex, bufOrg);
        fillLightAndOverlayToBuffer(bufOrg);
    }

    public void getFinalUv(int vertexIndex, Vector2f dest) {
        float x = uv0s[vertexIndex * 2];
        float y = uv0s[vertexIndex * 2 + 1];

        // PMX textures use repeat addressing and frequently contain UVs well
        // outside [0, 1]. The custom Kasuga shader can repeat in local texture
        // space using its extra attributes, but Iris' NEW_ENTITY format only
        // receives this final atlas coordinate. Repeat before atlas mapping so
        // Iris cannot sample neighbouring sprites in the combined atlas.
        x = x - (float) Math.floor(x);
        y = y - (float) Math.floor(y);

        int matIndex = vertexMaterials[vertexIndex] * 8;
        float u0 = materialUvBounds[matIndex];
        float v0 = materialUvBounds[matIndex + 1];
        float u1 = materialUvBounds[matIndex + 2];
        float v1 = materialUvBounds[matIndex + 3];
        float u2 = materialUvBounds[matIndex + 4];
        float v2 = materialUvBounds[matIndex + 5];
        float u3 = materialUvBounds[matIndex + 6];
        float v3 = materialUvBounds[matIndex + 7];

        float invX = 1f - x;
        float invY = 1f - y;

        float w0 = invX * invY;
        float w1 = x * invY;
        float w2 = x * y;
        float w3 = invX * y;

        dest.set(
                u0 * w0 + u1 * w1 + u2 * w2 + u3 * w3,
                v0 * w0 + v1 * w1 + v2 * w2 + v3 * w3
        );
    }

    protected void fillCpuSkinningElements(int index, int bufPos,
                                           ArrayList<BoneContext> cacheList,
                                           Vector3f cachePos, Vector3f scratchPosition,
                                           Vector3f cacheNorm, Vector3f scratchNormal,
                                           Vector4f cacheTangent, Vector3f scratchTangent,
                                           boolean updateTangent) {
        Vertex vertex = vertices[index];
        Mesh mesh = meshes[vertexMeshes[index]];
        BoneBindingFunc func = vertex.getBinding() == null ? null : vertex.getBinding().getFunc();

        if (func == null || func == BoneBindingFunc.IDENTITY) {
            fillFullVertexPosition(index, cachePos);
            fillVertexNormal(index, cacheNorm);
            fillVertexTangent(index, cacheTangent);
        } else if (func == BoneBindingFunc.BDEF) {
            applyBdef(index, cachePos, cacheNorm, updateTangent ? cacheTangent : null,
                    scratchPosition, scratchNormal, scratchTangent);
        } else {
            cacheList.clear();
            model.getSkeletonInstance().collectBoneContexts(cacheList, vertex);
            Vertex transformed = func.apply(vertex, cacheList);
            cachePos.set(transformed.getPosition()).add(
                    basicOffsets[index * 3],
                    basicOffsets[index * 3 + 1],
                    basicOffsets[index * 3 + 2]
            );
            cacheNorm.set(transformed.getNormal(mesh));
            if (updateTangent) setVertexTangent(index, cacheTangent);
        }

        fillAllCpuSkinningComponentsToBuffer(bufPos, cachePos, cacheNorm, cacheTangent);
    }

    protected void fillAllCpuSkinningComponentsToBuffer(int bufPos, Vector3f pos, Vector3f norm, Vector4f tangent) {
        fillPositionToBufferFromV3f(bufPos, pos);
        fillNormalToBufferFromV3f(bufPos, norm);
        fillTangentToBufferFromV4f(bufPos, tangent);
    }

    protected void applyBdef(int skinningIndex, Vector3f position, Vector3f normal,
                           @Nullable Vector4f tangent, Vector3f scratchPosition, Vector3f scratchNormal,
                           Vector3f scratchTangent) {
        position.zero();
        normal.zero();
        if (tangent != null) {
            tangent.zero();
        }
        int weightOffset = weightOffsets[skinningIndex];
        int weightCount = weightCounts[skinningIndex];
        if (weightCount == 0) {
            setVertexPosition(skinningIndex, position);
            setVertexNormal(skinningIndex, normal);
            if (tangent != null) setVertexTangent(skinningIndex, tangent);
            return;
        }
        int componentOffset = skinningIndex * 3;
        float baseX = positions[componentOffset] + basicOffsets[componentOffset];
        float baseY = positions[componentOffset + 1] + basicOffsets[componentOffset + 1];
        float baseZ = positions[componentOffset + 2] + basicOffsets[componentOffset + 2];
        float normalX = normals[componentOffset];
        float normalY = normals[componentOffset + 1];
        float normalZ = normals[componentOffset + 2];
        int tangentOffset = skinningIndex * 4;
        float tangentX = tangent == null ? 0.0f : tangents[tangentOffset];
        float tangentY = tangent == null ? 0.0f : tangents[tangentOffset + 1];
        float tangentZ = tangent == null ? 0.0f : tangents[tangentOffset + 2];
        float tangentW = tangent == null ? 0.0f : tangents[tangentOffset + 3];
        for (int i = 0; i < weightCount; i++) {
            int index = weightOffset + i;
            float weight = weights[index];
            int boneIndex = vertexBones[index];
            Transform absTransform = absTransforms[boneIndex];
            Transform bindInverse = bindInverses[boneIndex];
            if (absTransform == null || bindInverse == null) continue;
            scratchPosition.set(baseX, baseY, baseZ);
            bindInverse.apply(scratchPosition);
            absTransform.apply(scratchPosition);
            position.add(scratchPosition.mul(weight));

            scratchNormal.set(normalX, normalY, normalZ);
            bindInverse.normal().transform(scratchNormal);
            absTransform.normal().transform(scratchNormal);
            normal.add(scratchNormal.mul(weight));

            if (tangent != null) {
                scratchTangent.set(tangentX, tangentY, tangentZ);
                bindInverse.normal().transform(scratchTangent);
                absTransform.normal().transform(scratchTangent);
                tangent.x += scratchTangent.x() * weight;
                tangent.y += scratchTangent.y() * weight;
                tangent.z += scratchTangent.z() * weight;
            }
        }
        if (tangent != null) {
            tangent.w = tangentW;
        }
    }

    protected void fillPositionToBuffer(int index) {
        int bufPos = index * vertexSize + posOffset;
        int posPos = index * 3;
        buffer.putFloat(bufPos, positions[posPos]);
        buffer.putFloat(bufPos + 4, positions[posPos + 1]);
        buffer.putFloat(bufPos + 8, positions[posPos + 2]);
    }

    protected void fillFullPositionToBuffer(int index, int bufPos) {
        bufPos += posOffset;
        int posPos =  index * 3;
        buffer.putFloat(bufPos, positions[posPos] + basicOffsets[posPos]);
        buffer.putFloat(bufPos + 4, positions[posPos + 1] + basicOffsets[posPos + 1]);
        buffer.putFloat(bufPos + 8, positions[posPos + 2] + basicOffsets[posPos + 2]);
    }

    protected void fillPositionToBufferFromV3f(int bufPos, Vector3f v3f) {
        bufPos += posOffset;
        buffer.putFloat(bufPos, v3f.x());
        buffer.putFloat(bufPos + 4, v3f.y());
        buffer.putFloat(bufPos + 8, v3f.z());
    }

    protected void fillNormalToBuffer(int index, int bufPos) {
        if (normOffset < 0) return;

        bufPos += normOffset;
        int posPos = index * 3;
        buffer.put(bufPos, (byte) Math.round(normals[posPos] * 127f));
        buffer.put(bufPos + 1, (byte) Math.round(normals[posPos + 1] * 127f));
        buffer.put(bufPos + 2, (byte) Math.round(normals[posPos + 2] * 127f));
    }

    protected void fillNormalToBufferFromV3f(int bufPos, Vector3f v3f) {
        bufPos += normOffset;

        if (v3f.lengthSquared() > 0f) {
            v3f.normalize();
        }
        buffer.put(bufPos, (byte) Math.round(v3f.x() * 127f));
        buffer.put(bufPos + 1, (byte) Math.round(v3f.y() * 127f));
        buffer.put(bufPos + 2, (byte) Math.round(v3f.z() * 127f));
    }

    protected void fillTangentToBuffer(int index, int bufPos) {
        if (tangOffset < 0) return;

        bufPos += tangOffset;
        int tangPos = index * 4;
        buffer.putFloat(bufPos, tangents[tangPos]);
        buffer.putFloat(bufPos + 4, tangents[tangPos + 1]);
        buffer.putFloat(bufPos + 8, tangents[tangPos + 2]);
        buffer.putFloat(bufPos + 12, tangents[tangPos + 3]);
    }

    protected void fillTangentToBufferFromV4f(int bufPos, Vector4f v3f) {
        bufPos += tangOffset;
        buffer.putFloat(bufPos, v3f.x());
        buffer.putFloat(bufPos + 4, v3f.y());
        buffer.putFloat(bufPos + 8, v3f.z());
        buffer.putFloat(bufPos + 12, v3f.w());
    }

    protected void fillColorToBuffer(int index, int bufPos) {
        if (colorOffset < 0) return;

        bufPos += colorOffset;
        int matIndex = vertexMaterials[index] * 4;
        int meshIndex = vertexMeshes[index] * 4;
        int posPos = index * 4;
        float r = colors[posPos] * materialColors[matIndex] * meshColors[meshIndex] * brightness;
        float g = colors[posPos + 1] * materialColors[matIndex + 1] * meshColors[meshIndex + 1] * brightness;
        float b = colors[posPos + 2] * materialColors[matIndex + 2] * meshColors[meshIndex + 2] * brightness;
        // Lighting changes RGB, never material coverage. Applying brightness
        // to alpha would make a fully opaque MASK texel fail its cutoff in a
        // dark area and create artificial holes.
        float a = colors[posPos + 3] * materialColors[matIndex + 3] * meshColors[meshIndex + 3];

        // Preserve RGB even for low-alpha BLEND texels. The shader owns the
        // independent near-zero discard; zeroing RGB here would turn visible
        // alpha values such as 0.02 into black weighted-OIT contributions.
        int af = readAlpha ? Math.round(Math.clamp(a, 0f, 1f) * 255f) : 255;
        int bf = Math.round(b * 255f);
        int gf = Math.round(g * 255f);
        int rf = Math.round(r * 255f);
        int colorFinal = af << 24 | bf << 16 | gf << 8 | rf;
        buffer.putInt(bufPos, IS_LITTLE_ENDIAN ?  colorFinal : Integer.reverseBytes(colorFinal));
    }

    protected void fillAlphaCutoffToBuffer(int index, int bufPos) {
        if (alphaCutoffOffset < 0) return;
        Material material = materials[vertexMaterials[index]];
        buffer.putFloat(bufPos + alphaCutoffOffset, ModelRenderPass.alphaCutoff(material));
    }

    protected void fillUvToBuffer(int index, int bufPos, Vector2f cache) {
        int vertexStart = bufPos;
        bufPos = vertexStart + uv0Offset;
        getFinalUv(index, cache);
        buffer.putFloat(bufPos, cache.x());
        buffer.putFloat(bufPos + 4, cache.y());

        if (textureUvOffset >= 0) {
            int localUvPos = vertexStart + textureUvOffset;
            buffer.putFloat(localUvPos, uv0s[index * 2]);
            buffer.putFloat(localUvPos + 4, uv0s[index * 2 + 1]);
        }
        if (textureBoundsOffset >= 0) {
            int boundsPos = vertexStart + textureBoundsOffset;
            int materialPos = vertexMaterials[index] * 8;
            buffer.putFloat(boundsPos, materialUvBounds[materialPos]);
            buffer.putFloat(boundsPos + 4, materialUvBounds[materialPos + 1]);
            buffer.putFloat(boundsPos + 8, materialUvBounds[materialPos + 4]);
            buffer.putFloat(boundsPos + 12, materialUvBounds[materialPos + 5]);
        }
    }

    protected void fillLightAndOverlayToBuffer(int bufPos) {
        int lightPos = bufPos + lightmapOffset;
        int overlayPos = bufPos + overlayOffset;
        putPackedUv(lightPos, lightmap);
        putPackedUv(overlayPos, overlay);
    }

    protected void fillBoneAndWeightToBuffer(int index, int bufPos) {
        if (bindingBoneOffset <= 0 || bindingTypeOffset <= 0 || bindingWeightOffset <= 0) return;

        Vertex vertex = vertices[index];
        BoneBinding binding = vertex.getBinding();
        int type = bindingType(binding.getFunc());
        int weightOffset = weightOffsets[index];
        int weightCount = weightCounts[index];

        buffer.putInt(bufPos + bindingTypeOffset, type);
        int indexPos = bufPos + bindingBoneOffset;
        int weightPos = bufPos + bindingWeightOffset;
        for (int i = 0; i < 4; i++) {
            if (i >= weightCount) {
                buffer.putFloat(indexPos + 4 * i, 0f);
                buffer.putFloat(weightPos + 4 * i, 0f);
            } else {
                buffer.putFloat(indexPos + 4 * i, vertexBones[weightOffset + i]);
                buffer.putFloat(weightPos + 4 * i, weights[weightOffset + i]);
            }
        }
    }

    protected void fillSdefDataToBuffer(int index, int bufPos) {
        if (sdefR1Offset <= 0 || sdefR0Offset <= 0 || sdefCOffset <= 0) return;

        Vertex vertex = vertices[index];
        BoneBinding binding = vertex.getBinding();
        Vector3f cache = new Vector3f();
        int intCache;
        if (binding.getData() instanceof SDEFBoneBindingData data) {
            SDEFData sdefData = data.getSDEFData();
            if (sdefData == null) return;

            intCache = bufPos + sdefR0Offset;
            cache.set(sdefData.r0());
            buffer.putFloat(intCache, cache.x());
            buffer.putFloat(intCache + 4, cache.y());
            buffer.putFloat(intCache + 8, cache.z());

            intCache = bufPos + sdefR1Offset;
            cache.set(sdefData.r1());
            buffer.putFloat(intCache, cache.x());
            buffer.putFloat(intCache + 4, cache.y());
            buffer.putFloat(intCache + 8, cache.z());

            intCache = bufPos + sdefCOffset;
            cache.set(sdefData.c());
            buffer.putFloat(intCache, cache.x());
            buffer.putFloat(intCache + 4, cache.y());
            buffer.putFloat(intCache + 8, cache.z());
        }
    }

    protected void putPackedUv(int pos, int packedUv) {
        if (IS_LITTLE_ENDIAN) {
            buffer.putInt(pos, packedUv);
        } else {
            buffer.putShort(pos, (short)(packedUv & '\uffff'));
            buffer.putShort(pos + 2, (short)(packedUv >> 16 & '\uffff'));
        }
    }

    protected void calculateMaterialColor(Material org, Vector4f colorCache,
                                    @Nullable BiConsumer<Sprite, Vector4f> biConsumer) {
        colorCache.set(1, 1, 1, 1);
        Sprite sprite = model.getMaterialSprite(org);
        model.getMaterialColor(org, sprite, colorCache);
        if (biConsumer != null) biConsumer.accept(sprite, colorCache);
    }

    protected void setMaterialColor(int index, Vector4f org) {
        materialColors[index * 4] =  org.x();
        materialColors[index * 4 + 1] = org.y();
        materialColors[index * 4 + 2] = org.z();
        materialColors[index * 4 + 3] = org.w();
    }

    protected void fillMaterialColor(int index, Vector4f dest) {
        dest.set(
                materialColors[index * 4],
                materialColors[index * 4 + 1],
                materialColors[index * 4 + 2],
                materialColors[index * 4 + 3]
        );
    }

    public void setMaterialUvBounds(int index, Material org) {
        Sprite umlSprite = model.getMaterialSprite(org);
        boolean flipU = umlSprite.flipU;
        boolean flipV = umlSprite.flipV;

        float u0, v0, u1, v1, u2, v2, u3, v3;
        if (umlSprite.getTexture() != null &&
                umlSprite.getTexture().getData() instanceof SpriteHolder textureData) {
            TextureAtlasSprite sprite = textureData.getSprite();
            u0 = flipU ? sprite.getU1() : sprite.getU0();
            v0 = flipV ? sprite.getV1() : sprite.getV0();
            u1 = flipU ? sprite.getU0() : sprite.getU1();
            v1 = flipV ? sprite.getV0() : sprite.getV1();
        } else {
            u0 = 0f; v0 = 0f;
            u1 = 1f; v1 = 1f;
        }
        float atlasU0 = u0;
        float atlasV0 = v0;
        float rectU = u1 - atlasU0;
        float rectV = v1 - atlasV0;
        float maxV = Math.max(v0, v1);
        float maxU = Math.max(u0, u1);
        float minV = Math.min(v0, v1);
        float minU = Math.min(u0, u1);
        u0 = Math.clamp(atlasU0 + rectU * umlSprite.getUv0().x(), minU, maxU);
        v0 = Math.clamp(atlasV0 + rectV * umlSprite.getUv0().y(), minV, maxV);
        u1 = Math.clamp(atlasU0 + rectU * umlSprite.getUv1().x(), minU, maxU);
        v1 = Math.clamp(atlasV0 + rectV * umlSprite.getUv1().y(), minV, maxV);
        u2 = Math.clamp(atlasU0 + rectU * umlSprite.getUv2().x(), minU, maxU);
        v2 = Math.clamp(atlasV0 + rectV * umlSprite.getUv2().y(), minV, maxV);
        u3 = Math.clamp(atlasU0 + rectU * umlSprite.getUv3().x(), minU, maxU);
        v3 = Math.clamp(atlasV0 + rectV * umlSprite.getUv3().y(), minV, maxV);

        int i = index * 8;
        materialUvBounds[i] = u0;
        materialUvBounds[i+1] = v0;
        materialUvBounds[i+2] = u1;
        materialUvBounds[i+3] = v1;
        materialUvBounds[i+4] = u2;
        materialUvBounds[i+5] = v2;
        materialUvBounds[i+6] = u3;
        materialUvBounds[i+7] = v3;
    }

    public int calculateVertexCount(int vertexCountPerMesh) {
        if (instanceMeshMode == MeshMode.LINES) {
            return vertexCountPerMesh * 2;
        }
        if (vertexCountPerMesh > instanceMeshMode.vertexCount) {
            return 2 * instanceMeshMode.vertexCount;
        }
        return instanceMeshMode.vertexCount;
    }

    public Vertex[] flatVertices(Vertex[] vertices) {
        // 划线模式，无论顶点多少，都是顶点数量的两倍
        if (instanceMeshMode == MeshMode.LINES) {
            Vertex[] result = new Vertex[vertices.length * 2];
            int j;
            for (int i = 0; i < vertices.length; i++) {
                j = i * 2;
                result[j] = vertices[i];
                result[j + 1] = vertices[(i + 1) % vertices.length];
            }
            return result;
        }

        int vCount = instanceMeshMode.vertexCount;
        if (vertices.length == vCount) return vertices;

        // 列表长度比所需顶点数量多，拆分成两个面
        if (vertices.length > vCount) {
            Vertex[] result = new Vertex[vCount * 2];
            int j;
            for (int i = 0; i < vCount * 2; i++) {
                j = i >= vCount ? (i - 1) % vertices.length : i;
                result[i] = vertices[j];
            }
            return result;
        }

        // 列表长度比所需顶点数量少, 统一到所需数量
        Vertex[] result = new Vertex[vCount];
        Vertex lastVertex = vertices[vertices.length - 1];
        for (int i = 0; i < vCount; i++) {
            result[i] = i >= vertices.length ? lastVertex : vertices[i];
        }
        return result;
    }

    public void setVertexUv(int index, Vector2f org) {
        uv0s[index * 2] = org.x();
        uv0s[index * 2 + 1] = org.y();
    }

    public void fillVertexUv(int index, Vector2f dest) {
        dest.set(
                uv0s[index * 2],
                uv0s[index * 2 + 1]
        );
    }

    public void setVertexPosition(int index, Vector3f org) {
        positions[index * 3] = org.x();
        positions[index * 3 + 1] = org.y();
        positions[index * 3 + 2] = org.z();
    }

    public void setVertexBasicOffset(int index, Vector3f org) {
        basicOffsets[index * 3] = org.x();
        basicOffsets[index * 3 + 1] = org.y();
        basicOffsets[index * 3 + 2] = org.z();
    }

    public void fillVertexBasicOffset(int index, Vector3f dest) {
        dest.set(
                basicOffsets[index * 3],
                basicOffsets[index * 3 + 1],
                basicOffsets[index * 3 + 2]
        );
    }

    public void fillVertexPosition(int index, Vector3f dest) {
        dest.set(positions[index * 3], positions[index * 3 + 1], positions[index * 3 + 2]);
    }

    public void fillFullVertexPosition(int index, Vector3f dest) {
        dest.set(
                positions[index * 3] + basicOffsets[index * 3],
                positions[index * 3 + 1] + basicOffsets[index * 3 + 1],
                positions[index * 3 + 2] + basicOffsets[index * 3 + 2]
        );
    }

    public void setVertexNormal(int index, Vector3f org) {
        normals[index * 3] = org.x();
        normals[index * 3 + 1] = org.y();
        normals[index * 3 + 2] = org.z();
    }

    public void fillVertexNormal(int index, Vector3f dest) {
        dest.set(normals[index * 3],  normals[index * 3 + 1], normals[index * 3 + 2]);
    }

    public void setVertexTangent(int index, Vector4f org) {
        tangents[index * 4] = org.x();
        tangents[index * 4 + 1] = org.y();
        tangents[index * 4 + 2] = org.z();
        tangents[index * 4 + 3] = org.w();
    }

    public void fillVertexTangent(int index, Vector4f dest) {
        dest.set(
                tangents[index * 4],
                tangents[index * 4 + 1],
                tangents[index * 4 + 2],
                tangents[index * 4 + 3]
        );
    }

    /**
     * 为所有 mesh 计算切线（Mikktspace 算法），结果写入 {@link #tangents} 数组。
     * 在 {@link #buildupBuffer} 之前调用以确保切线数据被写入缓冲区。
     */
    public void calculateTangents() {
        if (tangOffset < 0) return;
        for (int m = 0; m < meshes.length; m++) {
            calculateMeshTangents(meshes[m], m);
        }
    }

    private void calculateMeshTangents(Mesh mesh, int meshIndex) {
        Vertex[] rawVertices = mesh.getVertices();
        if (rawVertices.length < 3) return;

        int n = rawVertices.length;
        for (int i = 0; i < n; i++) {
            Vertex v0 = rawVertices[i];
            Vertex v1 = rawVertices[(i + n - 1) % n];
            Vertex v2 = rawVertices[(i + 1) % n];

            int i0 = getFirstVertexIndexInMesh(v0, meshIndex);
            int i1 = getFirstVertexIndexInMesh(v1, meshIndex);
            int i2 = getFirstVertexIndexInMesh(v2, meshIndex);
            if (i0 < 0 || i1 < 0 || i2 < 0) continue;

            calculateAndSetTangent(i0, i1, i2, v0);
        }
    }

    private int getFirstVertexIndexInMesh(Vertex vertex, int meshIndex) {
        Integer[] indices = vertexByIndex.get(vertex);
        if (indices == null) return -1;
        for (int idx : indices) {
            if (vertexMeshes[idx] == meshIndex) {
                return idx;
            }
        }
        return -1;
    }

    private void calculateAndSetTangent(int i0, int i1, int i2, Vertex vertex) {
        // 边的 3D 向量
        int p0 = i0 * 3, p1 = i1 * 3, p2 = i2 * 3;
        float e1x = positions[p1]     - positions[p0];
        float e1y = positions[p1 + 1] - positions[p0 + 1];
        float e1z = positions[p1 + 2] - positions[p0 + 2];
        float e2x = positions[p2]     - positions[p0];
        float e2y = positions[p2 + 1] - positions[p0 + 1];
        float e2z = positions[p2 + 2] - positions[p0 + 2];

        // UV 差值
        int u0 = i0 * 2, u1 = i1 * 2, u2 = i2 * 2;
        float du1 = uv0s[u1]     - uv0s[u0];
        float dv1 = uv0s[u1 + 1] - uv0s[u0 + 1];
        float du2 = uv0s[u2]     - uv0s[u0];
        float dv2 = uv0s[u2 + 1] - uv0s[u0 + 1];

        float denom = du1 * dv2 - du2 * dv1;
        float factor = denom == 0.0f ? 1.0f : 1.0f / denom;

        // 切线方向
        float tx = factor * (dv2 * e1x - dv1 * e2x);
        float ty = factor * (dv2 * e1y - dv1 * e2y);
        float tz = factor * (dv2 * e1z - dv1 * e2z);
        float tLen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);

        if (tLen == 0.0f || Float.isNaN(tLen)) {
            setVertexTangentForAll(vertex, 0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }
        tx /= tLen;
        ty /= tLen;
        tz /= tLen;

        // 正交化：bitangent = cross(normal, tangent) * factor
        int n0 = i0 * 3;
        float nx = normals[n0];
        float ny = normals[n0 + 1];
        float nz = normals[n0 + 2];

        float bx = factor * (ty * nz - tz * ny);
        float by = factor * (tz * nx - tx * nz);
        float bz = factor * (tx * ny - ty * nx);
        float bLen = (float) Math.sqrt(bx * bx + by * by + bz * bz);

        if (bLen == 0.0f || Float.isNaN(bLen)) {
            setVertexTangentForAll(vertex, 0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }
        setVertexTangentForAll(vertex,
                bx / bLen, by / bLen, bz / bLen,
                bLen < 0.0f ? -1.0f : 1.0f);
    }

    private void setVertexTangentForAll(Vertex vertex, float x, float y, float z, float w) {
        Integer[] indices = vertexByIndex.get(vertex);
        if (indices == null) return;
        for (int idx : indices) {
            setVertexTangent(idx, new Vector4f(x, y, z, w));
        }
    }

    public void setVertexColor(int position, Vector4f org) {
        colors[position * 4] = org.x();
        colors[position * 4 + 1] = org.y();
        colors[position * 4 + 2] = org.z();
        colors[position * 4 + 3] = org.w();
    }

    public void fillVertexColor(int position, Vector4f dest) {
        dest.set(
                colors[position * 4],
                colors[position * 4 + 1],
                colors[position * 4 + 2],
                colors[position * 4 + 3]
        );
    }

    public void setMeshColors(int index, Vector4f org) {
        meshColors[index * 4] = org.x();
        meshColors[index * 4 + 1] = org.y();
        meshColors[index * 4 + 2] = org.z();
        meshColors[index * 4 + 3] = org.w();
    }

    public void fillMeshColors(int index, Vector4f dest) {
        dest.set(
                meshColors[index * 4],
                meshColors[index * 4 + 1],
                meshColors[index * 4 + 2],
                meshColors[index * 4 + 3]
        );
    }

    public void setMeshNormals(int index, Vector3f org) {
        meshNormals[index * 3] = org.x();
        meshNormals[index * 3 + 1] = org.y();
        meshNormals[index * 3 + 2] = org.z();
    }

    public void fillMeshNormal(int index, Vector3f dest) {
        dest.set(meshNormals[index * 3],
                meshNormals[index * 3 + 1],
                meshNormals[index * 3 + 2]);
    }

    public VertexFormat.Mode getMcMeshMode() {
        return switch (meshMode) {
            case LINES -> VertexFormat.Mode.LINES;
            case TRIANGLES -> VertexFormat.Mode.TRIANGLES;
            case QUADS, MIXED -> VertexFormat.Mode.QUADS;
        };
    }

    public static int bindingType(BoneBindingFunc func) {
        if (func == BoneBindingFunc.SDEF) return 1;
        if (func == BoneBindingFunc.QDEF) return 2;
        return 0;  // BDEF
    }

    public static Map<VertexFormatElement, Integer> genVertexFormat(VertexFormat format) {
        List<VertexFormatElement> list = format.getElements();
        Map<VertexFormatElement, Integer> map = new HashMap<>();
        int offset = 0;
        for (VertexFormatElement element : list) {
            map.put(element, offset);
            offset += element.byteSize();
        }
        return map;
    }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        MemoryUtil.memFree(buffer);
    }
}
