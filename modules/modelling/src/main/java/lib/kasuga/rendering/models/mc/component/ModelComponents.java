package lib.kasuga.rendering.models.mc.component;

import com.google.gson.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.loaders.SkeletonBuilder;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import java.util.*;

/** Versioned authoring metadata in Blockbench model coordinates: pixels and degrees.
 * No mutable runtime state, Minecraft classes or global Bone references are retained. */
public final class ModelComponents {
    public static final String KEY = "kasuga_lib";
    private final JsonObject source;
    private final LinkedHashMap<String, JsonObject> definitions = new LinkedHashMap<>();
    private final Map<Integer, String> bindings = new HashMap<>();
    private final List<EmitterDefinition> emitters = new ArrayList<>();
    private ModelComponents(JsonObject source) {
        this.source = source;
        if (source == null) return;
        if (integer(source, "version", -1) != 1) throw error("version: expected 1");
        Set<String> names = new HashSet<>();
        for (JsonElement element : array(source, "bones")) {
            JsonObject b = element.getAsJsonObject();
            String id = string(b, "id", ""), name = string(b, "name", "");
            if (id.isBlank() || name.isBlank() || definitions.putIfAbsent(id, b) != null || !names.add(name))
                throw error("bones: duplicate or empty id/name");
            vector(b, "pivot", new Vector3f());
        }
        for (var entry : definitions.entrySet()) {
            Set<String> visited = new HashSet<>(Set.of(entry.getKey()));
            String parent = nullableString(entry.getValue(), "parent");
            while (parent != null) {
                if (!visited.add(parent)) throw error("bones.parent: cycle");
                parent = nullableString(definition(parent), "parent");
            }
        }
        for (JsonElement value : array(source, "bindings")) {
            JsonObject binding = value.getAsJsonObject();
            int index = integer(binding, "element", -1);
            String id = nullableString(binding, "bone");
            if (index < 0 || bindings.containsKey(index)) throw error("bindings.element: duplicate/negative index");
            if (id != null) definition(id);
            bindings.put(index, id);
        }
        Set<String> emitterIds = new HashSet<>();
        for (JsonElement value : array(source, "emitters")) {
            JsonObject emitter = value.getAsJsonObject();
            String id = string(emitter, "id", "");
            if (id.isBlank() || definitions.containsKey(id) || !emitterIds.add(id)) throw error("emitters: duplicate or empty id");
            String bone = nullableString(emitter, "bone");
            if (bone != null) definition(bone);
            Vector3f position = vector(emitter, "position", new Vector3f());
            Vector3f rotation = vector(emitter, "rotation", new Vector3f());
            JsonObject particle = object(emitter, "particle");
            String color = string(particle, "color", "#ffffff");
            if (!color.matches("#[0-9a-fA-F]{6}")) throw error("emitters." + id + ".particle.color");
            int maxParticles = integer(emitter, "max_particles", 256);
            if (maxParticles < 1 || maxParticles > 4096) throw error("emitters." + id + ".max_particles");
            String space = string(emitter, "space", "world");
            if (!space.equals("local") && !space.equals("world")) throw error("emitters." + id + ".space");
            String texture = string(particle, "texture", "");
            if (!texture.isBlank() && net.minecraft.resources.ResourceLocation.tryParse(texture) == null)
                throw error("emitters." + id + ".particle.texture");
            emitters.add(new EmitterDefinition(
                    id, string(emitter, "name", id), bone, position, rotation,
                    emitter.has("enabled") ? emitter.get("enabled").getAsBoolean() : true,
                    number(emitter, "rate", 20, 0, 10_000),
                    maxParticles,
                    "local".equals(space), texture,
                    number(particle, "lifetime", 1, 0.01f, 60),
                    number(particle, "speed", 8, 0, 10_000),
                    number(particle, "spread", 15, 0, 180),
                    number(particle, "size", 1, 0.01f, 256),
                    parseColor(color),
                    vector(particle, "gravity", new Vector3f())
            ));
        }
        if (emitters.stream().mapToInt(EmitterDefinition::maxParticles).sum() > 16_384)
            throw error("emitters: total model particle budget exceeds 16384");
    }
    public static ModelComponents parse(JsonElement value) {
        if (value == null || value.isJsonNull()) return new ModelComponents(null);
        try { return new ModelComponents(value.getAsJsonObject().deepCopy()); }
        catch (IllegalStateException | ClassCastException exception) { throw new IllegalArgumentException("kasuga_lib: invalid JSON structure", exception); }
    }
    public boolean isEmpty() { return source == null; }

    public List<EmitterDefinition> emitters() { return List.copyOf(emitters); }

    public record EmitterDefinition(
            String id, String name, String boneId, Vector3f position, Vector3f rotation,
            boolean enabled, float rate, int maxParticles, boolean localSpace,
            String texture, float lifetimeSeconds, float speedPixelsPerSecond,
            float spreadDegrees, float sizePixels, Vector4f color, Vector3f gravityPixelsPerSecondSquared
    ) {
        public String anchorName() { return "kasuga_emitter:" + id; }

        public EmitterDefinition {
            position = new Vector3f(position);
            rotation = new Vector3f(rotation);
            color = new Vector4f(color);
            gravityPixelsPerSecondSquared = new Vector3f(gravityPixelsPerSecondSquared);
        }

        @Override public Vector3f position() { return new Vector3f(position); }
        @Override public Vector3f rotation() { return new Vector3f(rotation); }
        @Override public Vector4f color() { return new Vector4f(color); }
        @Override public Vector3f gravityPixelsPerSecondSquared() { return new Vector3f(gravityPixelsPerSecondSquared); }
    }
    public String elementBone(int index) {
        String id = bindings.get(index);
        return id == null ? null : string(definition(id), "name", "");
    }
    public void validateElementCount(int count) {
        if (bindings.keySet().stream().anyMatch(index -> index >= count)) throw error("bindings.element: outside model elements");
    }
    public String rootName() {
        String name = "__kasuga_root";
        Set<String> names = new HashSet<>();
        definitions.values().forEach(b -> names.add(string(b, "name", "")));
        while (names.contains(name)) name += "_";
        return name;
    }
    /** Materialize extension-only control bones without adding them to the base model JSON. */
    public void addMissingBones(SkeletonBuilder builder, Set<String> nativeNames) {
        for (var entry : definitions.entrySet()) {
            JsonObject b = entry.getValue();
            String name = string(b, "name", "");
            if (nativeNames.contains(name)) continue;
            String parent = nullableString(b, "parent");
            Vector3f pivot = vector(b, "pivot", new Vector3f());
            if (parent != null) pivot.sub(vector(definition(parent), "pivot", new Vector3f()));
            builder.addBone(name, new Transform().translate(pivot.div(16)),
                    parent == null ? rootName() : string(definition(parent), "name", ""), null);
        }
    }
    /** Java models have no native bones; build translation pivots and bind the exported element indices. */
    public Skeleton createSkeleton() {
        Bone root = new Bone(rootName(), new Transform(), null);
        Map<String, Bone> bones = new LinkedHashMap<>();
        definitions.forEach((id,b) -> bones.put(id, new Bone(string(b, "name", ""), new Transform(), null)));
        definitions.forEach((id,b) -> {
            String parentId = nullableString(b, "parent");
            Bone bone = bones.get(id), parent = parentId == null ? root : bones.get(parentId);
            Vector3f pivot = vector(b, "pivot", new Vector3f());
            if (parentId != null) pivot.sub(vector(definition(parentId), "pivot", new Vector3f()));
            bone.getTransform().translate(pivot.div(16));
            bone.setParent(parent);
        });
        List<Bone> all = new ArrayList<>(); all.add(root); all.addAll(bones.values());
        for (Bone bone : all) bone.setChildren(all.stream().filter(child -> child.getParent() == bone).toArray(Bone[]::new));
        return new Skeleton(all.toArray(Bone[]::new), root, new Anchor[0], null, new Transform());
    }
    public void apply(Skeleton skeleton) {
        if (isEmpty()) return;
        Map<String, Bone> bones = new LinkedHashMap<>();
        for (var entry : definitions.entrySet()) {
            String name = string(entry.getValue(), "name", "");
            long count = Arrays.stream(skeleton.getBones()).filter(b -> b.getName().equals(name)).count();
            if (count != 1) throw error("bones." + entry.getKey() + ": native bone must exist exactly once: " + name);
            bones.put(entry.getKey(), skeleton.getBoneMap().get(name));
        }
        installEmitterAnchors(skeleton, bones);
        List<SkeletonDynamics.IkChain> chains = new ArrayList<>();
        List<SkeletonDynamics.RigidBody> bodies = new ArrayList<>();
        List<SkeletonDynamics.Joint> joints = new ArrayList<>();
        Map<String,Integer> bodyIndices = new HashMap<>();
        for (var entry : definitions.entrySet()) {
            String id = entry.getKey(); JsonObject components = object(entry.getValue(), "components");
            if (components.has("ik")) {
                JsonObject ik = object(components, "ik");
                String target = string(ik, "target", ""); Bone controller = resolve(bones, target);
                Set<Bone> lineage = new HashSet<>();
                for (Bone p = bones.get(id); p != null; p = p.getParent()) lineage.add(p);
                if (lineage.contains(controller)) throw error("ik.target: must be outside driven chain");
                List<SkeletonDynamics.IkLink> links = new ArrayList<>();
                for (JsonElement value : array(ik, "links")) {
                    JsonObject link = value.getAsJsonObject();
                    SkeletonDynamics.RotationLimit limit = link.has("min") || link.has("max")
                            ? new SkeletonDynamics.RotationLimit(radians(vector(link, "min", new Vector3f(-180))), radians(vector(link, "max", new Vector3f(180)))) : null;
                    links.add(new SkeletonDynamics.IkLink(resolve(bones, string(link, "bone", "")), limit));
                }
                Bone child = bones.get(id);
                for (int i = links.size()-1; i >= 0; i--) {
                    if (child.getParent() != links.get(i).bone()) throw error("ik.links: expected contiguous base-to-tip ancestors");
                    child = links.get(i).bone();
                }
                for (Bone p = controller; p != null; p = p.getParent()) {
                    if (p == child) throw error("ik.target: cannot descend from a driven link");
                }
                chains.add(new SkeletonDynamics.IkChain(id, controller, bones.get(id), links, integer(ik, "iterations", 32),
                        number(ik, "tolerance", 0.01f, 0.000001f, 16)/16,
                        (float)Math.toRadians(number(ik, "max_step", 45, 0, 180)), false, SkeletonDynamics.TargetMode.POSITION));
            }
            if (components.has("physics")) {
                JsonObject p = object(components, "physics");
                Vector3f size = vector(p, "size", new Vector3f(1,4,1)).div(16);
                if (size.x <= 0 || size.y <= 0 || size.z <= 0) throw error("physics.size: positive dimensions required");
                Vector3f local = vector(p, "position", new Vector3f()).div(16);
                Transform bind = skeleton.getBindingAbsolute(bones.get(id)).copy();
                Vector3f position = bind.apply(local);
                Quaternionf orientation = new Quaternionf(bind.getRotation()).mul(new Quaternionf().rotationZYX(
                        (float)Math.toRadians(vector(p, "rotation", new Vector3f()).z),
                        (float)Math.toRadians(vector(p, "rotation", new Vector3f()).y),
                        (float)Math.toRadians(vector(p, "rotation", new Vector3f()).x)));
                int shape = switch(string(p, "shape", "capsule")) { case "sphere" -> 0; case "box" -> 1; case "capsule" -> 2; default -> throw error("physics.shape"); };
                int mode = switch(string(p, "mode", "rotation")) { case "kinematic" -> 0; case "dynamic" -> 1; case "rotation" -> 2; default -> throw error("physics.mode"); };
                bodyIndices.put(id,bodies.size());
                bodies.add(new SkeletonDynamics.RigidBody(id, string(entry.getValue(), "name", id), bones.get(id), integer(p, "collision_group", 0), integer(p, "non_collision_mask", 0),
                        shape, size, position, Frames.euler(orientation), number(p, "mass", 1,0,1e6f), number(p, "linear_damping",0.2f,0,1),
                        number(p, "angular_damping",0.2f,0,1),number(p, "restitution",0,0,1),number(p, "friction",0.5f,0,10),mode));
            }
        }
        for (var entry : definitions.entrySet()) {
            JsonObject p = object(object(entry.getValue(), "components"), "physics");
            String parent = nullableString(p, "parent_body");
            if (parent == null || parent.isBlank()) continue;
            String id = entry.getKey();
            if (parent.equals(id) || !bodyIndices.containsKey(parent)) throw error("physics.parent_body: missing/self rigid body");
            JsonObject joint = object(p, "joint");
            Vector3f min = radians(vector(joint, "rotation_min", new Vector3f(-45)));
            Vector3f max = radians(vector(joint, "rotation_max", new Vector3f(45)));
            if (min.x>max.x || min.y>max.y || min.z>max.z) throw error("physics.joint: reversed limits");
            joints.add(new SkeletonDynamics.Joint(id+"/joint",id,bodyIndices.get(parent),bodyIndices.get(id),
                    skeleton.getBindingAbsolute(bones.get(id)).getPosition(),new Vector3f(),new Vector3f(),new Vector3f(),
                    min,max,new Vector3f(),vector(joint, "rotation_spring", new Vector3f())));
        }
        List<SkeletonDynamics.DiamondConstraint> diamonds = new ArrayList<>();
        Set<String> diamondIds = new HashSet<>(), usedBranches = new HashSet<>();
        Set<String> chainIds = new HashSet<>();
        chains.forEach(chain -> chainIds.add(chain.name()));
        if (source.has("diamonds")) for (JsonElement value : array(source, "diamonds")) {
            JsonObject diamond = value.getAsJsonObject();
            String id = string(diamond, "id", ""), first = string(diamond, "first", ""), second = string(diamond, "second", "");
            if (id.isBlank() || !diamondIds.add(id) || first.equals(second)
                    || !chainIds.contains(first) || !chainIds.contains(second)
                    || !usedBranches.add(first) || !usedBranches.add(second))
                throw error("diamonds: invalid id or shared/missing branch");
            diamonds.add(new SkeletonDynamics.DiamondConstraint(id, first, second,
                    integer(diamond, "iterations", 64), number(diamond, "tolerance", 0.01f, 0.000001f, 16)/16));
        }
        skeleton.setDynamics(new SkeletonDynamics(new SkeletonDynamics.Physics(bodies,joints,new Vector3f(1),false,true,1,Set.of()),chains,diamonds));
    }
    private JsonObject definition(String id) { JsonObject b=definitions.get(id);if(b==null)throw error("missing bone id: "+id);return b; }
    private void installEmitterAnchors(Skeleton skeleton, Map<String, Bone> bones) {
        for (EmitterDefinition emitter : emitters) {
            Bone bone = emitter.boneId() == null ? skeleton.getRoot() : resolve(bones, emitter.boneId());
            Transform offset = new Transform()
                    .translate(emitter.position().div(16f))
                    .mul(lib.kasuga.rendering.models.uml.math.QuaternionHelper.fromZYXDegrees(emitter.rotation()));
            if (skeleton.getAnchor(emitter.anchorName()) == null)
                skeleton.defineAnchor(emitter.anchorName(), bone, offset);
        }
    }
    private static Bone resolve(Map<String,Bone> bones,String id) { Bone b=bones.get(id);if(b==null)throw error("missing bone: "+id);return b; }
    private static JsonObject object(JsonObject o,String key) { return o.has(key)?o.getAsJsonObject(key):new JsonObject(); }
    private static JsonArray array(JsonObject o,String key) { if(!o.has(key)||!o.get(key).isJsonArray())throw error(key+": array required");return o.getAsJsonArray(key); }
    private static String nullableString(JsonObject o,String key) {return !o.has(key)||o.get(key).isJsonNull()?null:o.get(key).getAsString();}
    private static String string(JsonObject o,String key,String fallback) {String s=nullableString(o,key);return s==null?fallback:s;}
    private static float number(JsonObject o,String key,float fallback,float min,float max) {float n=o.has(key)?o.get(key).getAsFloat():fallback;if(!Float.isFinite(n)||n<min||n>max)throw error(key+": invalid finite range");return n;}
    private static int integer(JsonObject o,String key,int fallback) {if(!o.has(key))return fallback;double n=o.get(key).getAsDouble();if(!Double.isFinite(n)||n!=Math.rint(n)||n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw error(key+": int32 required");return (int)n;}
    private static Vector3f vector(JsonObject o,String key,Vector3f fallback) {if(!o.has(key))return new Vector3f(fallback);JsonArray a=o.getAsJsonArray(key);if(a.size()!=3)throw error(key+": vector3 required");Vector3f v=new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());if(!v.isFinite())throw error(key+": non-finite vector");return v;}
    private static Vector3f radians(Vector3f v) {return v.mul((float)Math.PI/180);}
    private static Vector4f parseColor(String value) {
        int rgb = Integer.parseInt(value.substring(1), 16);
        return new Vector4f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, 1f);
    }
    private static IllegalArgumentException error(String message) {return new IllegalArgumentException("kasuga_lib."+message);}
}
