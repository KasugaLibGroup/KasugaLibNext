package lib.kasuga.compat.iris;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.core.compat.CompatCapability;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisVertexFormats;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class IrisModelFormats {
    private IrisModelFormats() {}

    static Boolean probe() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        lookup.findStaticGetter(ImmediateState.class, "skipExtension", ThreadLocal.class);
        lookup.findStaticGetter(IrisVertexFormats.class, "TERRAIN", VertexFormat.class);
        lookup.findStaticGetter(IrisVertexFormats.class, "ENTITY", VertexFormat.class);
        lookup.findStaticGetter(IrisVertexFormats.class, "GLYPH", VertexFormat.class);
        lookup.findStaticGetter(IrisVertexFormats.class, "MID_TEXTURE_ELEMENT", VertexFormatElement.class);
        lookup.findStaticGetter(IrisVertexFormats.class, "TANGENT_ELEMENT", VertexFormatElement.class);
        lookup.findStaticGetter(IrisVertexFormats.class, "ENTITY_ID_ELEMENT", VertexFormatElement.class);
        lookup.findStaticGetter(CapturedRenderingState.class, "INSTANCE", CapturedRenderingState.class);
        CompatCapability.requireMethod(CapturedRenderingState.class, "getCurrentRenderedEntity", int.class);
        CompatCapability.requireMethod(CapturedRenderingState.class, "getCurrentRenderedBlockEntity", int.class);
        CompatCapability.requireMethod(CapturedRenderingState.class, "getCurrentRenderedItem", int.class);
        lookup.findStatic(Iris.class, "isPackInUseQuick", MethodType.methodType(boolean.class));
        return true;
    }

    static VertexFormat compatibleFormat(VertexFormat format, boolean force) {
        if (!force && (ImmediateState.skipExtension.get() || !Iris.isPackInUseQuick())) return format;
        if (format == DefaultVertexFormat.NEW_ENTITY) return IrisVertexFormats.ENTITY;
        if (format == DefaultVertexFormat.BLOCK) return IrisVertexFormats.TERRAIN;
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) return IrisVertexFormats.GLYPH;
        return format;
    }
}
