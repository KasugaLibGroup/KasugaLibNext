package lib.kasuga.rendering.models.mc.compat.iris;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisVertexFormats;

public class IrisConstants {

    public static void register() {
        PBRTextureLoaderRegistry.INSTANCE.register(KasugaTextureAtlas.class, new KasugaPBRLoader());
    }

    public static VertexFormat getIrisFormat(VertexFormat format) {
        if (!(Boolean) ImmediateState.skipExtension.get() && Iris.isPackInUseQuick()) {
            if (format != DefaultVertexFormat.BLOCK && format != IrisVertexFormats.TERRAIN) {
                if (format != DefaultVertexFormat.NEW_ENTITY && format != IrisVertexFormats.ENTITY) {
                    if (format != DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP && format != IrisVertexFormats.GLYPH) {
                        return format;
                    } else {
                        return IrisVertexFormats.GLYPH;
                    }
                } else {
                    return IrisVertexFormats.ENTITY;
                }
            } else {
                return IrisVertexFormats.TERRAIN;
            }
        } else {
            return format;
        }
    }

    public static VertexFormat forceIrisFormat(VertexFormat format) {
        if (format == DefaultVertexFormat.NEW_ENTITY || format == IrisVertexFormats.ENTITY) {
            return IrisVertexFormats.ENTITY;
        } else if (format == DefaultVertexFormat.BLOCK || format == IrisVertexFormats.TERRAIN) {
            return IrisVertexFormats.TERRAIN;
        } else if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP || format == IrisVertexFormats.GLYPH) {
            return IrisVertexFormats.GLYPH;
        }
        return format;
    }
}
