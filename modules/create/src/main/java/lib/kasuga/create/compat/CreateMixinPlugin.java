package lib.kasuga.create.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Select the actual Create curve API before loading its classes; never relax injection checks. */
public final class CreateMixinPlugin implements IMixinConfigPlugin {
    private Boolean legacyCurve;
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".BezierConnectionMixin")) return legacy();
        if (mixinClassName.endsWith(".ModernBezierSegmentsMixin") || mixinClassName.endsWith(".ModernBezierGirdersMixin")) return !legacy();
        return true;
    }
    private boolean legacy() {
        if (legacyCurve == null) {
            try (var input = MixinService.getService().getResourceAsStream("com/simibubi/create/content/trains/track/BezierConnection.class")) {
                if (input == null) throw new IllegalStateException("Create BezierConnection is missing");
                var node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                legacyCurve = legacyCurve(node);
            } catch (IOException error) { throw new IllegalStateException("Cannot inspect Create curve API", error); }
        }
        return legacyCurve;
    }
    static boolean legacyCurve(ClassNode node) {
        for (var method : node.methods) if (method.name.equals("getBakedSegments")) {
            var result = Type.getReturnType(method.desc);
            if (result.getSort() == Type.ARRAY) return true;
            if (result.getSort() == Type.OBJECT && result.getInternalName().equals("com/simibubi/create/content/trains/track/BezierConnection$SegmentAngles")) return false;
        }
        throw new IllegalStateException("Unsupported Create BezierConnection API");
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
