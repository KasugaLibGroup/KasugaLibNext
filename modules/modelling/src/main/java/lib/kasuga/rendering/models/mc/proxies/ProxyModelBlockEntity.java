package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.backend.ModelLightingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Block entity host configured by the {@code proxy_model_be} data-driven factory. */
public class ProxyModelBlockEntity extends BlockEntity implements ProxySessionHost {
    private final ProxyAttachToken attachToken = ProxyAttachToken.random();
    private final ProxyModelSelection selection;
    private final float yawOffset;
    private final Map<String, Float> facingYaw;
    @Nullable
    private final ModelLightingMode lightingMode;
    @Nullable
    private ProxySession<?, ?, ?, ?, ?> proxySession;
    @Nullable
    private ResourceLocation visualHostIdentity;
    @Nullable
    private Transform visualHostTransform;

    public ProxyModelBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            ResourceLocation model,
            @Nullable String modelName,
            float yawOffset,
            Map<String, Float> facingYaw
    ) {
        this(
                type,
                pos,
                state,
                singleSelection(model, modelName),
                yawOffset,
                facingYaw
        );
    }

    /** Binary-compatible constructor for the original single-model selector shape. */
    @Deprecated(forRemoval = false)
    public ProxyModelBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            ResourceLocation model,
            @Nullable String modelName,
            float yawOffset,
            Map<String, Float> facingYaw,
            Map<String, Map<String, ResourceLocation>> modelByState
    ) {
        this(
                type,
                pos,
                state,
                legacySelection(model, modelName, modelByState),
                yawOffset,
                facingYaw
        );
    }

    public ProxyModelBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            ProxyModelSelection selection,
            float yawOffset,
            Map<String, Float> facingYaw
    ) {
        this(type, pos, state, selection, yawOffset, facingYaw, null);
    }

    public ProxyModelBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            ProxyModelSelection selection,
            float yawOffset,
            Map<String, Float> facingYaw,
            @Nullable ModelLightingMode lightingMode
    ) {
        super(type, pos, state);
        this.selection = selection;
        this.yawOffset = yawOffset;
        this.facingYaw = Map.copyOf(facingYaw);
        this.lightingMode = lightingMode;
    }

    public ResourceLocation model() {
        return selection.defaultGroup().parts().getFirst().model();
    }

    public @Nullable String modelName() {
        return selection.defaultGroup().parts().getFirst().modelName();
    }

    public float yawOffset() {
        return yawOffset;
    }

    public Map<String, Float> facingYaw() {
        return facingYaw;
    }

    /** Null preserves the content's model default; an explicit value overrides every visual part. */
    public @Nullable ModelLightingMode lightingMode() {
        return lightingMode;
    }

    public ProxyModelSelection selection() {
        return selection;
    }

    /**
     * Binds a borrowed external host's world-space anchor pose. Block position and BlockState stay
     * local to that host; the existing composition and animation scope remain their owners.
     */
    public void bindVisualHost(ResourceLocation identity, Transform anchorTransform) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(anchorTransform, "anchorTransform");
        if (!anchorTransform.transform().isFinite() || !anchorTransform.normal().isFinite()) {
            throw new IllegalArgumentException("visual host transform must be finite");
        }
        if (!identity.equals(visualHostIdentity)) {
            closeProxySession();
            visualHostIdentity = identity;
        }
        visualHostTransform = anchorTransform.copy();
    }

    /** Ends the external attachment before the virtual BE or its owning structure is discarded. */
    public void releaseVisualHost() {
        closeProxySession();
        visualHostIdentity = null;
        visualHostTransform = null;
    }

    public ResourceLocation visualIdentity() {
        return visualHostIdentity != null
                ? visualHostIdentity
                : ProxyIdentity.ofBlock(Objects.requireNonNull(getLevel(), "level"), getBlockPos()).key();
    }

    public @Nullable Transform visualHostTransform() {
        return visualHostTransform == null ? null : visualHostTransform.copy();
    }

    public CompiledModelGroup resolveGroup(BlockState state) {
        return selection.resolve(state);
    }

    /** @deprecated use {@link #resolveGroup(BlockState)}. */
    @Deprecated(forRemoval = false)
    public ResourceLocation resolveModel(BlockState state) {
        return resolveGroup(state).parts().getFirst().model();
    }

    private static ProxyModelSelection singleSelection(
            ResourceLocation model,
            @Nullable String modelName
    ) {
        CompiledModelGroup group = CompiledModelGroup.single(
                "default",
                new ModelPart(model, modelName, new Transform(), null)
        );
        return new ProxyModelSelection(group, Map.of(group.id(), group), java.util.List.of());
    }

    private static ProxyModelSelection legacySelection(
            ResourceLocation model,
            @Nullable String modelName,
            Map<String, Map<String, ResourceLocation>> modelByState
    ) {
        CompiledModelGroup defaultGroup = CompiledModelGroup.single(
                "default",
                new ModelPart(model, modelName, new Transform(), null)
        );
        Map<String, CompiledModelGroup> groups = new LinkedHashMap<>();
        groups.put(defaultGroup.id(), defaultGroup);
        List<ProxyModelSelection.Rule> rules = new ArrayList<>();
        for (var property : modelByState.entrySet()) {
            Map<String, CompiledModelGroup> values = new LinkedHashMap<>();
            for (var state : property.getValue().entrySet()) {
                String groupId = "legacy_" + state.getValue().getNamespace() + "_"
                        + state.getValue().getPath().replace('/', '_');
                CompiledModelGroup group = groups.computeIfAbsent(
                        groupId,
                        ignored -> CompiledModelGroup.single(
                                groupId,
                                new ModelPart(
                                        state.getValue(), modelName, new Transform(), null)
                        )
                );
                values.put(state.getKey(), group);
            }
            rules.add(new ProxyModelSelection.Rule(property.getKey(), values));
        }
        return new ProxyModelSelection(defaultGroup, groups, rules);
    }

    @Override
    public ProxyAttachToken proxyAttachToken() {
        return attachToken;
    }

    @Override
    public @Nullable ProxySession<?, ?, ?, ?, ?> proxySession() {
        return proxySession;
    }

    @Override
    public void proxySession(@Nullable ProxySession<?, ?, ?, ?, ?> session) {
        if (session != null && proxySession != null && proxySession != session) {
            throw new IllegalStateException("block already has a proxy session");
        }
        proxySession = session;
    }

    @Override
    public void setRemoved() {
        closeProxySession();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        closeProxySession();
        super.onChunkUnloaded();
    }
}
