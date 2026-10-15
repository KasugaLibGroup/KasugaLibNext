package lib.kasuga.rendering.models.uml.dynamic.morph.blender;

import lib.kasuga.rendering.models.uml.dynamic.morph.types.MorphType;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;

import java.util.Collection;
import java.util.Map;

public interface BlenderType<BlendedType> {

    BlendedType blend(BlendedType original, Map<? extends MorphType, Float[]> map, Collection<BlendedType> blended);
}
