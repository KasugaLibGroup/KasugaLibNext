package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationLibrary;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.dynamic.morph.Morph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.*;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.MorphType;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.*;
import lib.kasuga.rendering.models.uml.structure.material.*;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;

final class DefaultModelAssembler implements ModelAssembler {
    static final DefaultModelAssembler INSTANCE = new DefaultModelAssembler(MorphRemapper.standard());
    private final MorphRemapper morphs;
    DefaultModelAssembler(MorphRemapper morphs) { this.morphs = Objects.requireNonNull(morphs); }

    @Override public ModelAssembly assemble(ModelAssembly.Request request) {
        return new Build(request, morphs).assemble();
    }

    private static final class Build {
        final ModelAssembly.Request request;
        final MorphRemapper morphs;
        final List<PartRemap> parts = new ArrayList<>();
        final List<Bone> bones = new ArrayList<>();
        final Map<String, Bone> names = new LinkedHashMap<>();
        final Map<Bone, Transform> bindWorlds = new IdentityHashMap<>();
        final List<Vertex> vertices = new ArrayList<>();
        final List<Mesh> meshes = new ArrayList<>();
        final List<Material> materials = new ArrayList<>();
        final List<Texture> textures = new ArrayList<>();
        final Set<Texture> textureSet = Collections.newSetFromMap(new IdentityHashMap<>());
        Bone root;
        Map<String, Bone> bodyNames;

        Build(ModelAssembly.Request request, MorphRemapper morphs) {
            this.request = Objects.requireNonNull(request); this.morphs = morphs;
        }

        ModelAssembly assemble() {
            for (var definition : request.parts()) {
                PartRemap part = new PartRemap(this, definition, parts.isEmpty());
                parts.add(part);
                Set<Bone> visiting = new HashSet<>();
                for (Bone bone : part.source().getBones()) mapBone(part, bone, visiting);
                if (part.body) {
                    root = part.bone(part.source().getSkeleton().getRoot());
                    bodyNames = Map.copyOf(names);
                }
            }
            Map<Bone, List<Bone>> children = new IdentityHashMap<>();
            for (Bone bone : bones) if (bone.getParent() != null) {
                children.computeIfAbsent(bone.getParent(), ignored -> new ArrayList<>()).add(bone);
            }
            for (Bone bone : bones) bone.setChildren(children.getOrDefault(bone, List.of()).toArray(Bone[]::new));
            List<Anchor> anchors = new ArrayList<>();
            Set<String> anchorNames = new HashSet<>();
            MeshMode mode = null;
            for (PartRemap part : parts) {
                copyMaterials(part);
                copyGeometry(part);
                for (Anchor anchor : part.source().getSkeleton().getAnchors()) {
                    String name = part.anchorName(anchor.getName());
                    if (!anchorNames.add(name)) throw new IllegalArgumentException("duplicate anchor: " + name);
                    anchors.add(new Anchor(name, binding(part, anchor.getBinding()), anchor.getTransform().copy(), anchor.getData()));
                }
                if (!part.meshes.isEmpty()) {
                    MeshMode next = part.source().getMeshMode();
                    if (mode == null) mode = next;
                    else if (mode != next) {
                        if (mode == MeshMode.LINES || next == MeshMode.LINES) {
                            throw new IllegalArgumentException("line and surface geometry need separate assemblies");
                        }
                        mode = MeshMode.MIXED;
                    }
                }
            }
            Skeleton skeleton = new Skeleton(bones.toArray(Bone[]::new), root, anchors.toArray(Anchor[]::new), null, new Transform());
            copyDynamics(skeleton);
            Model model = new Model(vertices.toArray(Vertex[]::new), meshes.toArray(Mesh[]::new), skeleton.getBones(), skeleton,
                    new MaterialSet(textures, materials), mode == null ? request.parts().getFirst().source().model().getMeshMode() : mode,
                    null, null);
            for (PartRemap part : parts) {
                copyMorphs(part, model.getMorph());
                for (var clip : part.source().getAnimations().clips().values()) {
                    model.getAnimations().register(part.animationName(clip.name()), part.sampler(AnimationLibrary.SAMPLER), clip);
                }
            }
            Map<String, ModelAssembly.Remap> mappings = new LinkedHashMap<>();
            for (PartRemap part : parts) mappings.put(part.id(), part);
            return new ModelAssembly(model, request, mappings);
        }

        Bone mapBone(PartRemap part, Bone source, Set<Bone> visiting) {
            Bone existing = part.bones.get(source);
            if (existing != null) return existing;
            if (!part.sourceBones.contains(source)) throw new IllegalArgumentException("foreign bone in " + part.id());
            if (!visiting.add(source)) throw new IllegalArgumentException("cyclic source hierarchy in " + part.id());
            String explicit = part.definition.boneMappings().get(source);
            Bone shared = explicit != null ? names.get(explicit)
                    : !part.body && part.definition.matchBodyBones() ? bodyNames.get(source.getName()) : null;
            if (explicit != null && shared == null) throw new IllegalArgumentException("unknown target bone: " + explicit);
            Transform absolute = part.source().getSkeleton().getBindingAbsolute(source);
            if (shared != null) {
                if (!absolute.transform().equals(bindWorlds.get(shared).transform(), request.bindTolerance())) {
                    throw new IllegalArgumentException("incompatible bind pose in " + part.id() + ": " + source.getName());
                }
                part.bones.put(source, shared);
                part.boneNames.put(source.getName(), shared.getName());
                visiting.remove(source);
                return shared;
            }
            Bone parent = source.getParent() == null ? (part.body ? null : root) : mapBone(part, source.getParent(), visiting);
            String sourceName = source.getName().isBlank() ? "bone_" + source.getIndex() : source.getName();
            String name = part.body ? sourceName : part.id() + "/" + sourceName;
            if (names.containsKey(name)) throw new IllegalArgumentException("duplicate output bone: " + name);
            Transform local = parent == null ? absolute.copy() : bindWorlds.get(parent).invert().mul(absolute);
            Bone result = new Bone(name, local, source.getBoneData());
            result.setParent(parent);
            bones.add(result); names.put(name, result); bindWorlds.put(result, absolute.copy());
            part.bones.put(source, result); part.boneNames.put(source.getName(), name);
            visiting.remove(source);
            return result;
        }

        void copyMaterials(PartRemap part) {
            for (Material original : part.source().getMaterialSet().getMaterials()) {
                if (part.materials.containsKey(original)) continue;
                Material copy = new Material(original.getTextures().clone(), original.getData());
                copy.getTextureMap().putAll(original.getTextureMap());
                for (SpriteSet set : original.getSprites()) {
                    Sprite[] sprites = Arrays.stream(set.getSprites()).map(Build::copySprite).toArray(Sprite[]::new);
                    copy.addSprite(new SpriteSet(set.getData(), sprites));
                }
                part.materials.put(original, copy); materials.add(copy);
                for (Texture texture : original.getTextures()) if (texture != null && textureSet.add(texture)) textures.add(texture);
            }
        }

        static Sprite copySprite(Sprite original) {
            Sprite copy = new Sprite(original.getTexture(), new Vector2f(original.getUv0()), new Vector2f(original.getUv1()),
                    new Vector2f(original.getUv2()), new Vector2f(original.getUv3()), new Vector4f(original.color),
                    new Vector4f(original.ambient), new Vector4f(original.specular), original.getData());
            copy.shade = original.shade; copy.flipU = original.flipU; copy.flipV = original.flipV;
            copy.ambientOcclusion = original.ambientOcclusion; copy.culled = original.culled; copy.emissive = original.emissive;
            return copy;
        }

        void copyGeometry(PartRemap part) {
            Mesh[] sources = part.source().getMeshes();
            Set<Vertex> sourceVertices = Set.of(part.source().getVertices());
            for (int index = 0; index < sources.length; index++) {
                if (part.definition.hiddenMeshes().contains(index)) continue;
                Mesh source = sources[index];
                if (source.getVertices().length < 2) throw new IllegalArgumentException("invalid face in " + part.id());
                Vertex[] mapped = new Vertex[source.getVertices().length];
                for (int i = 0; i < mapped.length; i++) {
                    Vertex vertex = source.getVertices()[i];
                    if (!sourceVertices.contains(vertex)) throw new IllegalArgumentException("foreign face vertex in " + part.id());
                    mapped[i] = part.vertices.computeIfAbsent(vertex, original -> {
                        Vertex copy = new Vertex(new Vector3f(original.getPosition()), original.getData());
                        copy.setBinding(binding(part, original.getBinding()));
                        vertices.add(copy);
                        return copy;
                    });
                }
                Material[] palette = Arrays.stream(source.getMaterials()).map(part::material).toArray(Material[]::new);
                Mesh copy = new Mesh(mapped, new Vector3f(source.getNormal()), source.getTransform().copy(), palette, source.getData());
                copy.setVisible(source.isVisible()); copy.setCulled(source.isCulled());
                part.meshes.put(source, copy); meshes.add(copy);
                for (int i = 0; i < mapped.length; i++) {
                    Vertex original = source.getVertices()[i];
                    mapped[i].getNormals().put(copy, new Vector3f(original.getNormal(source)));
                    for (Material material : source.getMaterials()) {
                        Vector2f uv = original.getUV(source, material);
                        if (uv != null) mapped[i].getUvs().computeIfAbsent(copy, ignored -> new HashMap<>())
                                .put(part.material(material), new Vector2f(uv));
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        static BoneBinding binding(PartRemap part, BoneBinding source) {
            Objects.requireNonNull(source, "source binding");
            Pair<Bone, Float>[] weights = Arrays.stream(source.getWeights())
                    .map(weight -> Pair.of(part.bone(weight.getFirst()), weight.getSecond())).toArray(Pair[]::new);
            return new BoneBinding(weights, source.getFunc(), source.getData());
        }

        void copyDynamics(Skeleton skeleton) {
            SkeletonDynamicsBuilder builder = new SkeletonDynamicsBuilder(skeleton);
            Map<Bone, BonePoseConstraint> constraints = new IdentityHashMap<>();
            Map<String, IkChain> chains = new LinkedHashMap<>();
            Set<String> diamondNames = new HashSet<>();
            Map<Set<String>, DiamondConstraint> diamondsByChains = new HashMap<>();
            List<RigidBody> bodies = new ArrayList<>(); List<Joint> joints = new ArrayList<>();
            Map<RigidBody, Integer> bodyIndices = new HashMap<>(); Set<Joint> jointSet = new HashSet<>();
            Set<Bone> followers = new HashSet<>();
            boolean profileRequired = false, affine = false;
            Float radiusScale = null;
            for (PartRemap part : parts) {
                if (!part.definition.includeDynamics()) continue;
                var dynamics = part.source().getSkeleton().getDynamics();
                for (BonePoseConstraint original : dynamics.poseConstraints().values()) {
                    var inheritance = original.inheritance();
                    BonePoseConstraint mapped = new BonePoseConstraint(part.bone(original.bone()), inheritance == null ? null
                            : new TransformInheritance(part.bone(inheritance.source()), inheritance.weight(), inheritance.translation(), inheritance.rotation()),
                            original.fixedAxis());
                    BonePoseConstraint previous = constraints.putIfAbsent(mapped.bone(), mapped);
                    if (previous != null && !previous.equals(mapped)) {
                        throw new IllegalArgumentException("conflicting pose constraints on " + mapped.bone().getName());
                    }
                }
                for (IkChain original : dynamics.ikChains()) {
                    var links = original.links().stream().map(link -> new IkLink(part.bone(link.bone()), link.limit(), link.joint())).toList();
                    String name = part.body ? original.name() : part.id() + "/" + original.name();
                    IkChain mapped = new IkChain(name, part.bone(original.controller()), part.bone(original.effector()), links,
                            original.iterations(), original.tolerance(), original.maxRotationStep(), original.replaceAuthoredRotation(), original.targetMode());
                    IkChain reusable = chains.get(original.name());
                    if (!part.body && reusable != null && new IkChain(reusable.name(), mapped.controller(), mapped.effector(), mapped.links(),
                            mapped.iterations(), mapped.tolerance(), mapped.maxRotationStep(), mapped.replaceAuthoredRotation(), mapped.targetMode()).equals(reusable)) {
                        part.ikNames.put(original.name(), reusable.name());
                    } else {
                        if (chains.putIfAbsent(name, mapped) != null) throw new IllegalArgumentException("duplicate IK chain: " + name);
                        part.ikNames.put(original.name(), name);
                    }
                }
                for (DiamondConstraint diamond : dynamics.diamonds()) {
                    String name = part.body ? diamond.name() : part.id() + "/" + diamond.name();
                    DiamondConstraint mapped = new DiamondConstraint(name, part.ikName(diamond.firstChain()), part.ikName(diamond.secondChain()),
                            diamond.iterations(), diamond.tolerance());
                    Set<String> pair = Set.of(mapped.firstChain(), mapped.secondChain());
                    DiamondConstraint previous = diamondsByChains.get(pair);
                    if (previous != null) {
                        if (!new DiamondConstraint(previous.name(), mapped.firstChain(), mapped.secondChain(),
                                mapped.iterations(), mapped.tolerance()).equals(previous)) {
                            throw new IllegalArgumentException("conflicting diamond constraints in " + part.id());
                        }
                        continue;
                    }
                    if (!diamondNames.add(name)) throw new IllegalArgumentException("duplicate diamond: " + name);
                    diamondsByChains.put(pair, mapped);
                    builder.diamond(mapped);
                }
                Physics physics = dynamics.physics();
                Vector3f scale = physics.unitScale();
                part.bodyIndices = new int[physics.bodies().size()];
                for (int index = 0; index < physics.bodies().size(); index++) {
                    RigidBody original = physics.bodies().get(index);
                    RigidBody mapped = new RigidBody(original.name(), original.alias(), original.bone() == null ? null : part.bone(original.bone()),
                            original.collisionGroup(), original.nonCollisionMask(), original.shape(), original.size().mul(scale),
                            original.position().mul(scale), original.rotation(), original.mass(), original.linearDamping(),
                            original.angularDamping(), original.restitution(), original.friction(), original.mode());
                    Integer reused = mapped.bone() == null ? null : bodyIndices.get(mapped);
                    if (reused == null) {
                        reused = bodies.size(); bodies.add(mapped);
                        if (mapped.bone() != null) bodyIndices.put(mapped, reused);
                    }
                    part.bodyIndices[index] = reused;
                }
                for (Joint original : physics.joints()) {
                    int a = part.rigidBodyIndex(original.rigidBodyA()), b = part.rigidBodyIndex(original.rigidBodyB());
                    if (a == b) throw new IllegalArgumentException("joint collapsed by shared bodies in " + part.id());
                    Joint mapped = new Joint(original.name(), original.alias(), a, b, original.position().mul(scale), original.rotation(),
                            original.positionMin().mul(scale), original.positionMax().mul(scale), original.rotationMin(), original.rotationMax(),
                            original.positionSpring(), original.rotationSpring());
                    if (jointSet.add(mapped)) joints.add(mapped);
                }
                physics.bindPoseFollowers().forEach(bone -> followers.add(part.bone(bone)));
                if (!physics.bodies().isEmpty()) {
                    if (radiusScale != null && radiusScale != physics.profileRadiusScale()) {
                        throw new IllegalArgumentException("incompatible profile radius scales in " + part.id());
                    }
                    radiusScale = physics.profileRadiusScale(); profileRequired |= physics.profileRequired(); affine |= physics.affineWriteback();
                }
            }
            constraints.values().forEach(builder::pose); chains.values().forEach(builder::ik);
            builder.physics(new Physics(bodies, joints, new Vector3f(1), profileRequired, affine,
                    radiusScale == null ? 1 : radiusScale, followers)).attach();
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void copyMorphs(PartRemap part, Morph output) {
            Morph<?> source = part.source().getMorph();
            source.getMorphsById().forEach((id, definitions) -> {
                for (MorphType<?, ?, ?> definition : definitions) {
                    MorphType<?, ?, ?> mapped = part.morph(definition);
                    if (mapped != null) output.addMorph(part.morphId(id), mapped);
                }
            });
            source.getGroupMorphs().forEach((id, group) -> output.addGroup(part.morphId(id), copyGroup(part, group, new HashSet<>())));
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        GroupMorph<Object> copyGroup(PartRemap part, GroupMorph<?> source, Set<IMorphHolder<?, ?>> visiting) {
            if (!visiting.add(source)) throw new IllegalArgumentException("cyclic morph group in " + part.id());
            GroupMorph<Object> output = new GroupMorph<>(part.morphId(source.getIdentifier()));
            for (IMorphHolder holder : source.getSubHolders()) {
                IMorphHolder mapped;
                if (holder instanceof GroupMorph group) mapped = copyGroup(part, group, visiting);
                else {
                    MorphType prototype = holder.getMorphPrototype();
                    MorphType remapped = prototype == null ? null : part.morph(prototype);
                    if (remapped == null) continue;
                    List<Object> targets = new ArrayList<>();
                    for (Object element : holder.getMorphedElements()) {
                        Object target = part.element(element);
                        if (target != null) targets.add(target);
                    }
                    if (targets.isEmpty()) continue;
                    mapped = new MorphHolder(part.morphId(holder.getIdentifier()), remapped, targets);
                }
                output.addHolder(mapped, source.getFactors().getOrDefault(holder, 1f));
            }
            visiting.remove(source);
            return output;
        }
    }

    private static final class PartRemap implements ModelAssembly.Remap {
        final Build build;
        final ModelAssembly.Part definition;
        final boolean body;
        final Set<Bone> sourceBones;
        final Map<Bone, Bone> bones = new IdentityHashMap<>();
        final Map<Vertex, Vertex> vertices = new IdentityHashMap<>();
        final Map<Mesh, Mesh> meshes = new IdentityHashMap<>();
        final Map<Material, Material> materials = new IdentityHashMap<>();
        final Map<String, String> boneNames = new HashMap<>(), ikNames = new HashMap<>();
        final Map<MorphType<?, ?, ?>, MorphType<?, ?, ?>> morphs = new IdentityHashMap<>();
        final Set<MorphType<?, ?, ?>> visitingMorphs = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] bodyIndices = new int[0];
        PartRemap(Build build, ModelAssembly.Part definition, boolean body) {
            this.build = build; this.definition = definition; this.body = body;
            sourceBones = Set.of(source().getBones());
        }
        public String id() { return definition.id(); }
        public Model source() { return definition.source().model(); }
        public Bone bone(Bone source) {
            Bone target = bones.get(source);
            if (target == null) throw new IllegalArgumentException("unmapped bone in " + id());
            return target;
        }
        public Vertex vertex(Vertex source) { return vertices.get(source); }
        public Mesh mesh(Mesh source) { return meshes.get(source); }
        public Material material(Material source) {
            Material result = materials.get(source);
            if (result == null) throw new IllegalArgumentException("unmapped material in " + id());
            return result;
        }
        public Object morphId(Object source) { return body ? source : new ModelAssembly.PartMorphId(id(), source); }
        public String boneName(String source) { return boneNames.getOrDefault(source, source); }
        public String ikName(String source) { return ikNames.getOrDefault(source, body ? source : id() + "/" + source); }
        public String animationName(String source) { return body ? source : id() + "/" + source; }
        public String anchorName(String source) { return body ? source : id() + "/" + source; }
        public int rigidBodyIndex(int index) {
            if (!definition.includeDynamics()) return -1;
            if (index < 0 || index >= bodyIndices.length) throw new IllegalArgumentException("invalid source body index in " + id());
            return bodyIndices[index];
        }
        Object element(Object source) {
            if (source instanceof Vertex vertex) return vertex(vertex);
            if (source instanceof Mesh mesh) return mesh(mesh);
            if (source instanceof Bone bone) return bone(bone);
            if (source instanceof Material material) return material(material);
            throw new IllegalArgumentException("unsupported morph element: " + source.getClass().getName());
        }
        public MorphType<?, ?, ?> morph(MorphType<?, ?, ?> source) {
            if (morphs.containsKey(source)) return morphs.get(source);
            if (!visitingMorphs.add(source)) throw new IllegalArgumentException("cyclic morph definition in " + id());
            MorphType<?, ?, ?> result = build.morphs.remap(source, this);
            visitingMorphs.remove(source); morphs.put(source, result);
            return result;
        }
        Object frameRef(Object ref) {
            if (ref instanceof Material material) return materials.get(material);
            Integer index = ref instanceof Number number ? number.intValue() : null;
            if (index == null && ref instanceof String text) {
                try { index = Integer.valueOf(text); } catch (NumberFormatException ignored) { return ref; }
            }
            if (index == null) return ref;
            Material[] palette = source().getMaterialSet().getMaterials();
            return index < 0 || index >= palette.length ? null : material(palette[index]);
        }
        public Pose pose(Pose source) {
            Pose.Builder target = new Pose.Builder();
            source.bones().forEach((name, value) -> target.bone(boneName(name), value.transform(), value.mode()));
            source.morphs().forEach((id, value) -> target.morph(morphId(id), value.value(), value.factor()));
            source.ikEnabled().forEach((name, enabled) -> target.ikEnabled(ikName(name), enabled));
            source.frames().forEach((ref, frame) -> {
                Object mapped = frameRef(ref);
                if (mapped != null) target.frame(mapped, frame.frame());
            });
            return target.build();
        }
        public <T> AnimationSampler<T> sampler(AnimationSampler<T> source) {
            Objects.requireNonNull(source);
            return new AnimationSampler<>() {
                public float duration(T data) { return source.duration(data); }
                public Pose sample(T data, float seconds) { return pose(source.sample(data, seconds)); }
                public Pose sample(T data, float seconds, Namespace namespace) { return pose(source.sample(data, seconds, namespace)); }
            };
        }
    }
}
