package lib.kasuga.rendering.models.uml.dynamic.morph;

import java.util.Map;

public record MorphResult<MorphedElement>(MorphedElement original, Map<Object, Object> morphTargetAndResult) {
}
