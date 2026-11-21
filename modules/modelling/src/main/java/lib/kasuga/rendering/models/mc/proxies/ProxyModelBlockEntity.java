package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Block entity host configured by the {@code proxy_model_be} data-driven factory. */
public class ProxyModelBlockEntity extends BlockEntity implements ProxySessionHost {
    private final ProxyAttachToken attachToken = ProxyAttachToken.random();
    private final ResourceLocation model;
    @Nullable
    private final String modelName;
    @Nullable
    private ProxySession<?, ?, ?, ?, ?> proxySession;

    public ProxyModelBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            ResourceLocation model,
            @Nullable String modelName
    ) {
        super(type, pos, state);
        this.model = model;
        this.modelName = modelName;
    }

    public ResourceLocation model() {
        return model;
    }

    public @Nullable String modelName() {
        return modelName;
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
