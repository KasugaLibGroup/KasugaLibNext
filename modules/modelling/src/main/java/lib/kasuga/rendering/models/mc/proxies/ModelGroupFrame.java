package lib.kasuga.rendering.models.mc.proxies;

import java.util.List;
import java.util.Objects;

/** One immutable root and local-motion generation published by a model group. */
record ModelGroupFrame(RootSnapshot root, List<PartMotionSnapshot> motions) {

    ModelGroupFrame {
        root = Objects.requireNonNull(root, "root");
        motions = List.copyOf(Objects.requireNonNull(motions, "motions"));
    }

    PartMotionSnapshot motion(int index) {
        return motions.get(index);
    }
}
