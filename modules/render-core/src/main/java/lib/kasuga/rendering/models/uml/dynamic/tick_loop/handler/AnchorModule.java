package lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler;

import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes the world transform of every bound skeleton anchor after the
 * final pose (animation + IK + physics) of the tick is known.
 *
 * <p>Anchors are display sub-object attachment points authored on the
 * skeleton — e.g. a sword held in a player's hand. Hosts register an
 * {@link Attachment} per anchor name; each tick it receives the anchor's
 * current world transform (or {@code null} once the anchor or its bones no
 * longer resolve) and can place its attached object accordingly.</p>
 */
public class AnchorModule implements ModelTickLoopModule {
    private final Map<String, List<Attachment>> attachments = new LinkedHashMap<>();
    private final Map<Attachment, Transform> buffers = new IdentityHashMap<>();

    /** Receives one anchor's world transform every tick. */
    @FunctionalInterface
    public interface Attachment {
        void accept(@Nullable Transform worldTransform);
    }

    public void attach(String anchorName, Attachment attachment) {
        Objects.requireNonNull(anchorName, "anchorName");
        Objects.requireNonNull(attachment, "attachment");
        List<Attachment> list = attachments.computeIfAbsent(anchorName, ignored -> new ArrayList<>());
        if (list.stream().noneMatch(current -> current == attachment)) list.add(attachment);
        buffers.putIfAbsent(attachment, new Transform());
    }

    public boolean detach(String anchorName, Attachment attachment) {
        List<Attachment> list = attachments.get(anchorName);
        if (list == null) return false;
        boolean removed = list.removeIf(current -> current == attachment);
        if (list.isEmpty()) attachments.remove(anchorName);
        if (removed && attachments.values().stream().noneMatch(values -> values.stream().anyMatch(current -> current == attachment)))
            buffers.remove(attachment);
        return removed;
    }

    public void detachAll() {
        attachments.clear();
        buffers.clear();
    }

    public int attachmentCount() {
        return attachments.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
        if (attachments.isEmpty()) return;
        var skeletonInstance = loop.getInstance().getSkeletonInstance();
        for (Map.Entry<String, List<Attachment>> entry : new ArrayList<>(attachments.entrySet())) {
            Transform world = skeletonInstance.anchorTransform(entry.getKey());
            for (Attachment attachment : new ArrayList<>(entry.getValue())) {
                if (!attachments.getOrDefault(entry.getKey(), List.of()).stream().anyMatch(current -> current == attachment)) continue;
                Transform buffer = buffers.get(attachment);
                if (world == null) {
                    attachment.accept(null);
                    continue;
                }
                buffer.set(world);
                attachment.accept(buffer);
            }
        }
    }

    @Override
    public void destroy(Model model) {
        detachAll();
    }
}
