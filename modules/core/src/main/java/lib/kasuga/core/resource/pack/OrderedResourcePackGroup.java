package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.ResourceSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Input for a required parent pack whose real resource roots become ordered hidden children.
 */
public record OrderedResourcePackGroup(
        String groupId,
        Component title,
        List<Layer> layers,
        Map<PackType, Placement> placements
) {
    public OrderedResourcePackGroup {
        ResourcePaths.id(groupId);
        Objects.requireNonNull(title, "title");
        layers = List.copyOf(layers);
        Objects.requireNonNull(placements, "placements");
        EnumMap<PackType, Placement> checked = new EnumMap<>(PackType.class);
        for (PackType type : List.of(PackType.CLIENT_RESOURCES, PackType.SERVER_DATA)) {
            checked.put(type, Objects.requireNonNull(
                    placements.get(type),
                    "Missing placement for " + type
            ));
        }
        placements = Map.copyOf(checked);
    }

    /**
     * One captured resource provider root and its Minecraft projection plan.
     */
    public record Layer(
            String legacyRegistrationId,
            ResourceSource source,
            ResourceRouting routing
    ) {
        public Layer {
            ResourcePaths.id(legacyRegistrationId);
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(routing, "routing");
        }
    }

    /**
     * Placement for both the visible parent and its hidden children.
     */
    public record Placement(Pack.Position defaultPosition, boolean fixedPosition) {
        public Placement {
            Objects.requireNonNull(defaultPosition, "defaultPosition");
        }
    }
}
