package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.builtin.light.WorldLight;
import lib.kasuga.rendering.effect.builtin.light.WorldLights;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.models.mc.component.ModelComponents;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Instance-owned bone lights. Uses the same post-physics anchors and world effects as authored scenes. */
public final class ModelLightRuntime {
    public static final String MODULE_ID = "kasuga:model_lights";
    private static final Map<LightModule, Boolean> MODULES = new WeakHashMap<>();

    private ModelLightRuntime() {}

    public static void install(ModelInstance instance, ModelComponents components) {
        if (components.lights().isEmpty() || module(instance) != null) return;
        LightModule module = new LightModule(components.lights());
        instance.getTickLoop().addPostPhysics(MODULE_ID, module);
        synchronized (MODULES) { MODULES.put(module, true); }
    }

    public static LightModule module(ModelInstance instance) { return instance.getTickLoop().module(MODULE_ID, LightModule.class); }
    public static void register(ModelInstance instance) {
        LightModule module = module(instance);
        if (module != null) module.enable(MinecraftWorldViews.sourceLevel());
    }
    public static void unregister(ModelInstance instance) {
        LightModule module = module(instance);
        if (module != null) module.disable();
    }
    public static void clearWorldState() {
        List<LightModule> modules;
        synchronized (MODULES) { modules = new ArrayList<>(MODULES.keySet()); }
        for (LightModule module : modules) module.disable();
    }

    /** Read-only frame sampling also used by authoring/parity tests; no GPU allocation. */
    public static LightFrame frame(SkeletonInstance skeleton, ModelComponents.LightDefinition definition) {
        Vector3d origin = skeleton.getWorldOrigin();
        Transform local = skeleton.anchorTransformRelative(definition.anchorName(), origin);
        if (local == null || !local.transform().isFinite()) return null;
        return new LightFrame(definition.source(), new Matrix4d().translation(origin)
                .mul(new Matrix4d(local.transform())));
    }

    public static final class LightModule implements ModelTickLoopModule {
        private final List<ModelComponents.LightDefinition> definitions;
        private final List<EffectHandle<WorldLight>> handles;
        private ClientLevel level;
        private boolean closed;

        private LightModule(List<ModelComponents.LightDefinition> definitions) {
            this.definitions = List.copyOf(definitions);
            handles = new ArrayList<>();
            for (int index = 0; index < definitions.size(); index++) handles.add(null);
        }

        public synchronized boolean closed() { return closed; }
        public synchronized void enable(ClientLevel value) { if (!closed) level = value; }
        public synchronized void disable() {
            level = null;
            for (int index = 0; index < handles.size(); index++) remove(index);
        }
        private void remove(int index) {
            EffectHandle<WorldLight> handle = handles.set(index, null);
            if (handle != null) handle.remove();
        }

        @Override public synchronized void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
            if (closed || level == null) return;
            for (int index = 0; index < definitions.size(); index++) {
                ModelComponents.LightDefinition definition = definitions.get(index);
                LightFrame frame = definition.enabled() ? frame(loop.getInstance().getSkeletonInstance(), definition) : null;
                if (frame == null) { remove(index); continue; }
                EffectHandle<WorldLight> handle = handles.get(index);
                if (handle == null || !handle.isActive()) {
                    handle = WorldLights.spawn(level, definition.source());
                    handles.set(index, handle);
                }
                handle.effect().frame(frame);
            }
        }

        @Override public synchronized void destroy(Model model) {
            closed = true;
            disable();
            synchronized (MODULES) { MODULES.remove(this); }
        }
    }
}
