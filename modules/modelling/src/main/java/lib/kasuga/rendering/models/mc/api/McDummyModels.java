package lib.kasuga.rendering.models.mc.api;

import lib.kasuga.rendering.models.mc.dynamic.MinecraftModelRuntime;
import lib.kasuga.rendering.models.uml.dynamic.DummyModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

/** Client-side transform groups, automatically evaluated without a render backend or resource ID. */
public final class McDummyModels {
    private McDummyModels() {}

    public static DummyModelInstance create() {
        return create(null);
    }

    /** The owning host must close this group when it is removed or its world is unloaded. */
    public static DummyModelInstance create(@Nullable Transform rootTransform) {
        DummyModelInstance group = new DummyModelInstance(rootTransform) {
            @Override public void close() {
                try { super.close(); }
                finally { MinecraftModelRuntime.unregister(this); }
            }
        };
        MinecraftModelRuntime.register(group);
        return group;
    }
}
