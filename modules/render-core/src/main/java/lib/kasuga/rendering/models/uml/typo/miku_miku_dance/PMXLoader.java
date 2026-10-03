package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.ByteStreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.TextLoader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.dynamic.morph.BlendMode;
import lib.kasuga.rendering.models.uml.dynamic.morph.Morph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.GroupMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.MorphHolder;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.BoneTransformMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.MaterialColorMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.MorphType;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexPosMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexUvMorph;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone.BoneChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone.BoneSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header.HeaderChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header.HeaderInfoChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.material.MaterialChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.material.MaterialSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.mesh.MeshChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.mesh.MeshSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.texture.TextureSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.vertex.VertexChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.vertex.VertexSetChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail.*;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.*;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class PMXLoader<InputType, OutputIdentifier, TextureIdentifier, M extends ContextData<M>>
        extends ByteStreamLoader<InputType, OutputIdentifier, TextureIdentifier, M> {

    public static final HeaderInfoChunk HEADER_INFO_CHUNK = new HeaderInfoChunk();

    public static final HeaderChunk HEADER_CHUNK = new HeaderChunk(HEADER_INFO_CHUNK);

    private final MeshSetChunk meshSetChunk;

    private final VertexSetChunk vertexSetChunk;

    private final TextureSetChunk textureSetChunk;

    private final MaterialSetChunk materialSetChunk;

    private final TextLoader textLoader;

    private final BoneSetChunk boneSetChunk;
    private final PmxTailReader tailReader;

    @Getter
    private boolean textureLoading;

    @Getter
    private final String name;

    @Getter
    private PmxHeader header;

    @Getter
    private List<PmxVertex> vertices;

    @Getter
    private List<PmxMesh> meshes;

    @Getter
    private List<String> textures;

    @Getter
    private List<PmxMaterial> materials;

    @Getter
    private List<PmxBone> bones;

    @Getter
    private PmxTail tail;

    public PMXLoader(String name) {
        this.name = name;
        this.textLoader = new TextLoader(StandardCharsets.UTF_8);
        registerLoader(HEADER_CHUNK);
        vertexSetChunk = new VertexSetChunk();
        registerLoader(vertexSetChunk);
        meshSetChunk = new MeshSetChunk();
        registerLoader(meshSetChunk);
        textureSetChunk = new TextureSetChunk();
        registerLoader(textureSetChunk);
        materialSetChunk = new MaterialSetChunk();
        registerLoader(materialSetChunk);
        boneSetChunk = new BoneSetChunk();
        registerLoader(boneSetChunk);
        tailReader = new PmxTailReader(this);
        registerLoader(tailReader);
        textureLoading = false;
    }

    @Override
    public void afterLoader(StreamLoader loader, ByteBuffer buffer, Object result, SerialContext<M> context) {
        if (loader == HEADER_CHUNK) {
            this.header = (PmxHeader) result;
            getTextLoader();
            vertexSetChunk.setVertexChunk(new VertexChunk(this));
            meshSetChunk.setMeshChunk(new MeshChunk(this));
            textureSetChunk.setTextLoader(textLoader);
            materialSetChunk.setMaterialChunk(new MaterialChunk(this));
            boneSetChunk.setBoneChunk(new BoneChunk(this));
        } else if (loader == vertexSetChunk) {
            this.vertices = (List<PmxVertex>) result;
        } else if (loader == meshSetChunk) {
            this.meshes = (List<PmxMesh>) result;
        } else if (loader == textureSetChunk) {
            this.textures = (List<String>) result;
        } else if (loader == materialSetChunk) {
            this.materials = (List<PmxMaterial>) result;
        } else if (loader == boneSetChunk) {
            this.bones = (List<PmxBone>) result;
        } else if (loader == tailReader) {
            this.tail = (PmxTail) result;
        }
    }

    @Override
    public void build(Map<OutputIdentifier, Model> map, OutputIdentifier outputIdentifier, ByteBuffer buffer, SerialContext<M> context) {
        MaterialSetBuilder builder = materialSetBuilder();
        registerTextures(builder, textures);
        registerMaterials(builder, materials);
        Map<Object, Material> materialMap = Map.copyOf(builder.getMaterialByIds());
        MaterialSet materialSet = builder.endMaterialSet();

        if (materialMap.size() != materials.size()) {
            throw new IllegalStateException("Material count mismatch: expected " + materials.size() + ", got " + materialMap.size());
        }

        Map<PmxVertex, List<PmxVertex>> vertexMapping = new HashMap<>();
        Map<PmxVertex, Integer> vertexIndices = new HashMap<>();
        List<PmxVertex> sortedVertices = new ArrayList<>();
        int[] mappings = new int[vertices.size()];
        int i = 0;
        for (PmxVertex v : vertices) {
            Integer existingIndex = vertexIndices.get(v);
            if (existingIndex != null) {
                List<PmxVertex> duplicates = vertexMapping.get(v);
                duplicates.add(v);
                mappings[i] = existingIndex;
            } else {
                ArrayList<PmxVertex> duplicates = new ArrayList<>();
                duplicates.add(v);
                vertexMapping.put(v, duplicates);
                sortedVertices.add(v);
                int newIndex = sortedVertices.size() - 1;
                vertexIndices.put(v, newIndex);
                mappings[i] = newIndex;
            }
            i++;
        }

        int mappingSize = vertexMapping.size();
        Vertex[] verticesArray = new Vertex[mappingSize];
        for (i = 0; i < sortedVertices.size(); i++) {
            PmxVertex v = sortedVertices.get(i);
            List<PmxVertex> duplicates = vertexMapping.get(v);
            Vertex vertex = getVertex(v, duplicates);
            verticesArray[i] = vertex;
        }

        Mesh[] meshesArray = new Mesh[meshes.size()];
        for (i = 0; i < meshes.size(); i++) {
            PmxMesh m = meshes.get(i);
            int source1 = checkedVertexIndex(m.getVertex1().intValue(), mappings.length);
            int source2 = checkedVertexIndex(m.getVertex2().intValue(), mappings.length);
            int source3 = checkedVertexIndex(m.getVertex3().intValue(), mappings.length);
            Vertex v1 = verticesArray[mappings[source1]];
            Vertex v2 = verticesArray[mappings[source2]];
            Vertex v3 = verticesArray[mappings[source3]];
            Mesh mesh = getMesh(v1, v2, v3, m);
            meshesArray[i] = mesh;
        }

        int offset = 0;
        for (i = 0; i < materials.size(); i++) {
            PmxMaterial m = materials.get(i);
            int count = m.meshCount / 3;
            if (m.meshCount < 0 || m.meshCount % 3 != 0 || offset + count > meshes.size()) {
                throw new PmxFormatException("Invalid PMX material surface count at material " + i
                        + ": " + m.meshCount);
            }
            Material material = materialMap.get(m);
            for (int j = offset; j < offset + count; j++) {
                PmxMesh pMesh = meshes.get(j);
                Mesh mesh = meshesArray[j];
                mesh.getMaterials()[0] = material;
                int mesh1Val = pMesh.getVertex1().intValue();
                setUVAndNormalForVertex(
                        mappings[mesh1Val],
                        mesh1Val, material, verticesArray, mesh
                );
                int mesh2Val = pMesh.getVertex2().intValue();
                setUVAndNormalForVertex(
                        mappings[mesh2Val],
                        mesh2Val, material, verticesArray, mesh
                );
                int mesh3Val = pMesh.getVertex3().intValue();
                setUVAndNormalForVertex(
                        mappings[mesh3Val],
                        mesh3Val, material, verticesArray, mesh
                );
            }
            offset += count;
        }
        if (offset != meshes.size()) {
            throw new PmxFormatException("PMX materials cover " + (offset * 3)
                    + " surface indices, expected " + (meshes.size() * 3));
        }

        // PMX stores normals per source vertex, but equivalent vertices can be
        // duplicated at UV/material seams. Reconcile compatible triangle
        // corners before the backend flattens every face into its GPU buffer.
        PmxNormalSmoother.smooth(meshesArray);

        Bone[] boneArray = new Bone[bones.size()];
        Bone rootBone = null;
        List<Bone> childOfRoot = new ArrayList<>();
        boolean hasMultiRootBone = false;

        for (i = 0; i < bones.size(); i++) {
            PmxBone bone = bones.get(i);
            scaleBone(bone);
        }

        for (i = 0; i < bones.size(); i++) {
            PmxBone b = bones.get(i);
            Bone bone = getBone(bones, b);
            boneArray[i] = bone;
            if (b.parentBoneIndex.intValue() == -1) {
                if (rootBone == null) {
                    rootBone = bone;
                    continue;
                }
                if (hasMultiRootBone) {
                    childOfRoot.add(bone);
                    continue;
                }
                childOfRoot.add(rootBone);
                rootBone = createSyntheticRoot();
                childOfRoot.add(bone);
                hasMultiRootBone = true;
            }
        }

        HashMap<Bone, List<Bone>> childMap = new HashMap<>();
        for (i = 0; i < bones.size(); i++) {
            PmxBone b = bones.get(i);
            Bone bone = boneArray[i];
            int parentIndex = b.parentBoneIndex.intValue();
            Bone parentBone;
            if (parentIndex == -1) {
                if (childOfRoot.contains(bone)) parentBone = rootBone;
                else continue;
            } else {
                if (parentIndex < 0 || parentIndex >= boneArray.length) {
                    throw new PmxFormatException("PMX bone " + i
                            + " references invalid parent " + parentIndex);
                }
                parentBone = boneArray[parentIndex];
            }
            bone.setParent(parentBone);
            if (childMap.containsKey(parentBone)) {
                ArrayList<Bone> children = (ArrayList<Bone>) childMap.get(parentBone);
                if (!children.contains(bone)) children.add(bone);
            } else {
                childMap.put(parentBone, new ArrayList<>(List.of(bone)));
            }
        }

        for (Map.Entry<Bone, List<Bone>> entry : childMap.entrySet()) {
            Bone parent = entry.getKey();
            parent.setChildren(entry.getValue().toArray(new Bone[0]));
            entry.getValue().clear();
        }

        for (i = 0; i < sortedVertices.size(); i++) {
            PmxVertex v = sortedVertices.get(i);
            PmxBoneBinding b = v.binding;
            List<Pair<Bone, Float>> validWeights = new ArrayList<>(b.boneWeights.size());
            for (Map.Entry<Number, Float> entry : b.boneWeights.entrySet()) {
                int boneIndex = entry.getKey().intValue();
                if (boneIndex < 0 || boneIndex >= boneArray.length) {
                    continue;
                }
                Bone bone = boneArray[boneIndex];
                validWeights.add(Pair.of(bone, entry.getValue()));
            }
            Pair<Bone, Float>[] weights = validWeights.toArray(Pair[]::new);
            BoneBinding binding = new BoneBinding(weights, b.toBindingFunc(), b);
            verticesArray[i].setBinding(binding);
        }

        Bone[] pmxBoneArray = boneArray.clone();

        if (rootBone == null) {
            rootBone = createSyntheticRoot();
            boneArray = new Bone[]{rootBone};
        } else if (hasMultiRootBone) {
            Bone[] bones1 = new Bone[boneArray.length + 1];
            System.arraycopy(boneArray, 0, bones1, 1, boneArray.length);
            bones1[0] = rootBone;
            boneArray = bones1;
        }
        Skeleton skeleton = new Skeleton(boneArray, rootBone, new Anchor[0], null, new Transform());

        Model model = new Model(
                verticesArray,
                meshesArray,
                boneArray,
                skeleton,
                materialSet,
                MeshMode.TRIANGLES,
                getModelData(header, tail),
                null
        );
        buildMorphs(model, mappings, verticesArray, meshesArray, materialMap, pmxBoneArray);

        vertexMapping.clear();
        vertexIndices.clear();
        sortedVertices.clear();
        childOfRoot.clear();
        map.put(outputIdentifier, model);
    }

    public Transform calculateBoneTransform(List<PmxBone> bones, PmxBone current) {
        Transform transform = new Transform();
        Vector3f localPosition = new Vector3f(current.position);
        int parentIndex = current.parentBoneIndex.intValue();
        if (parentIndex >= 0 && parentIndex < bones.size()) {
            localPosition.sub(bones.get(parentIndex).position);
        }
        transform.translate(localPosition);
        return transform;
    }

    private void setUVAndNormalForVertex(Number vertexIndex, Number unmappedIndex, Material material, Vertex[] vertices, Mesh mesh) {
        int indexVal = vertexIndex.intValue();
        Vertex v1 = vertices[indexVal];
        PmxVertex pmxVertex = this.vertices.get(unmappedIndex.intValue());
        Map<Mesh, HashMap<Material, Vector2f>> uvMap = v1.getUvs();
        Map<Mesh, Vector3f> normalMap = v1.getNormals();
        uvMap.computeIfAbsent(mesh, m -> new HashMap<>())
                .put(material, new Vector2f(pmxVertex.uv));
        normalMap.put(mesh, new Vector3f(pmxVertex.normal));
    }

    public void registerTextures(MaterialSetBuilder builder, List<String> texturePaths) {
        textureLoading = true;
        for (String str : texturePaths) {
            TextureIdentifier identifier = getTextureIdentifier(str);
            Texture texture = loadTexture(identifier);
            builder.registerTexture(identifier, texture);
        }
    }

    public void registerMaterials(MaterialSetBuilder builder, List<PmxMaterial> materials) {
        textureLoading = false;
        for (PmxMaterial material : materials) {
            buildMaterial(builder, material);
        }
    }

    public abstract void buildMaterial(MaterialSetBuilder builder, PmxMaterial material);

    public abstract TextureIdentifier getTextureIdentifier(String texturePath);

    public abstract Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertex);

    public abstract Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh);

    public abstract Bone getBone(List<PmxBone> bones, PmxBone bone);

    public abstract @Nullable ModelData getModelData(PmxHeader header);

    public @Nullable ModelData getModelData(PmxHeader header, PmxTail tail) {
        return getModelData(header);
    }

    protected Vector3f scaleMmdTranslation(Vector3f value) {
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void buildMorphs(Model model, int[] vertexMappings, Vertex[] builtVertices,
                             Mesh[] builtMeshes, Map<Object, Material> materialMap,
                             Bone[] pmxBones) {
        if (tail == null || tail.morphs().isEmpty()) return;
        Morph<String> runtime = model.getMorph();
        List<List<MorphType<?, ?, String>>> definitions = new ArrayList<>(tail.morphs().size());
        IdentityHashMap<Vertex, List<UvTarget>> uvTargets = new IdentityHashMap<>();
        for (Mesh mesh : builtMeshes) {
            for (Vertex vertex : mesh.getVertices()) {
                for (Material material : mesh.getMaterials()) {
                    if (vertex.getUV(mesh, material) != null) {
                        uvTargets.computeIfAbsent(vertex, ignored -> new ArrayList<>())
                                .add(new UvTarget(mesh, material));
                    }
                }
            }
        }

        for (PmxMorph pmxMorph : tail.morphs()) {
            String id = pmxMorph.localName();
            List<MorphType<?, ?, String>> current = new ArrayList<>();
            if (pmxMorph.kind() == MorphKind.VERTEX) {
                for (PmxMorphOffset raw : pmxMorph.offsets()) {
                    VertexOffset offset = (VertexOffset) raw;
                    Vertex vertex = mappedVertex(offset.vertexIndex(), vertexMappings, builtVertices);
                    if (vertex == null) continue;
                    Vector3f target = new Vector3f(vertex.getPosition())
                            .add(scaleMmdTranslation(new Vector3f(offset.displacement())));
                    current.add(new VertexPosMorph<>(vertex, id, target));
                }
            } else if (pmxMorph.kind() == MorphKind.BONE) {
                for (PmxMorphOffset raw : pmxMorph.offsets()) {
                    BoneOffset offset = (BoneOffset) raw;
                    if (offset.boneIndex() < 0 || offset.boneIndex() >= pmxBones.length) continue;
                    Bone bone = pmxBones[offset.boneIndex()];
                    Transform delta = new Transform()
                            .translate(scaleMmdTranslation(new Vector3f(offset.translation())))
                            .mul(new Quaternionf(offset.rotation()));
                    current.add(new BoneTransformMorph<>(bone, id, delta));
                }
            } else if (pmxMorph.kind() == MorphKind.UV) {
                for (PmxMorphOffset raw : pmxMorph.offsets()) {
                    UvOffset offset = (UvOffset) raw;
                    if (offset.layer() != 0) continue;
                    Vertex vertex = mappedVertex(offset.vertexIndex(), vertexMappings, builtVertices);
                    if (vertex == null) continue;
                    for (UvTarget uvTarget : uvTargets.getOrDefault(vertex, List.of())) {
                        Vector2f base = vertex.getUV(uvTarget.mesh(), uvTarget.material());
                        Vector2f target = new Vector2f(base).add(offset.displacement().x, offset.displacement().y);
                        current.add(new VertexUvMorph<>(vertex, id, uvTarget.mesh(), uvTarget.material(), target));
                    }
                }
            } else if (pmxMorph.kind() == MorphKind.MATERIAL) {
                for (PmxMorphOffset raw : pmxMorph.offsets()) {
                    MaterialOffset offset = (MaterialOffset) raw;
                    BlendMode mode = offset.operation() == 0 ? BlendMode.MULTIPLY : BlendMode.ADD;
                    if (offset.materialIndex() == -1) {
                        for (Material material : materialMap.values()) {
                            current.add(new MaterialColorMorph<>(material, id,
                                    new Vector4f(offset.diffuse()), mode));
                        }
                    } else if (offset.materialIndex() >= 0 && offset.materialIndex() < materials.size()) {
                        Material material = materialMap.get(materials.get(offset.materialIndex()));
                        if (material != null) current.add(new MaterialColorMorph<>(material, id,
                                new Vector4f(offset.diffuse()), mode));
                    }
                }
            }
            for (MorphType<?, ?, String> definition : current) runtime.addMorph(id, definition);
            definitions.add(current);
        }

        for (int index = 0; index < tail.morphs().size(); index++) {
            PmxMorph pmxMorph = tail.morphs().get(index);
            if (pmxMorph.kind() != MorphKind.GROUP && pmxMorph.kind() != MorphKind.FLIP) continue;
            GroupMorph<String> group = new GroupMorph<>(pmxMorph.localName());
            for (PmxMorphOffset raw : pmxMorph.offsets()) {
                GroupOffset offset = (GroupOffset) raw;
                if (offset.morphIndex() < 0 || offset.morphIndex() >= definitions.size()) continue;
                for (MorphType<?, ?, String> referenced : definitions.get(offset.morphIndex())) {
                    group.addHolder(new MorphHolder(referenced.getIdentifier(), referenced), offset.weight());
                }
            }
            runtime.addGroup(pmxMorph.localName(), group);
        }
    }

    private Vertex mappedVertex(int sourceIndex, int[] mappings, Vertex[] vertices) {
        if (sourceIndex < 0 || sourceIndex >= mappings.length) return null;
        int mapped = mappings[sourceIndex];
        return mapped >= 0 && mapped < vertices.length ? vertices[mapped] : null;
    }

    private int checkedVertexIndex(int index, int vertexCount) {
        if (index < 0 || index >= vertexCount) {
            throw new PmxFormatException("PMX surface references invalid vertex " + index
                    + " (vertex count " + vertexCount + ")");
        }
        return index;
    }

    private record UvTarget(Mesh mesh, Material material) {}

    private static Bone createSyntheticRoot() {
        // Synthetic root 不是 PMX 文件骨：data 必须是 null（而非 PmxBone），否则
        // SkeletonInstance 用 `instanceof PmxBone` 过滤 pmxBones 时会把它收进数组，
        // 使文件骨整体右移一位 → IK 链/effector 的 PMX 文件索引（boneIndex）全部
        // 错位 -1 → 足ＩＫ 链变成 [左足,腰キャンセル左]（缺 左ひざ）→ 腿不弯、整体折叠。
        return new Bone("dummy_root", new Transform(), null);
    }

    public void scaleBone(PmxBone bone) {}

    public TextLoader getTextLoader() {
        checkHeaderLoaded();
        textLoader.setEncoding(header.info.encoding);
        return textLoader;
    }

    public StreamLoader getLoaderForIndexType(boolean isVertex, int index) {
        return switch (index) {
            case 2 -> isVertex ? BasicLoaders.USHORT : BasicLoaders.SHORT;
            case 4 -> BasicLoaders.INT;
            default -> isVertex ? BasicLoaders.UBYTE : BasicLoaders.BYTE;
        };
    }

    public void checkHeaderLoaded() {
        if (header == null) {
            throw new IllegalStateException("Header must be loaded before accessing index loader");
        }
    }

    public StreamLoader vertexIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(true, header.info.vertexIndexSize);
    }

    public StreamLoader materialIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.materialIndexSize);
    }

    public StreamLoader textureIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.textureIndexSize);
    }

    public StreamLoader boneIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.boneIndexSize);
    }

    public StreamLoader morphIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.morphIndexSize);
    }

    public StreamLoader rigidBodyIndexLoader() {
        checkHeaderLoaded();
        return getLoaderForIndexType(false, header.info.rigidBodyIndexSize);
    }
}
