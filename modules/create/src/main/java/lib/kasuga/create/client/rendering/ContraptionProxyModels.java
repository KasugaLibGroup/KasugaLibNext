package lib.kasuga.create.client.rendering;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.create.CreateModBeans;
import lib.kasuga.create.client.model.CreateSceneHosts;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import lib.kasuga.rendering.models.mc.proxies.ProxyIdentity;
import lib.kasuga.rendering.models.mc.proxies.ProxyModelBlockEntity;
import lib.kasuga.rendering.models.mc.proxies.ProxyTickers;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.ScenePose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Borrows Create's captured proxy-model BEs. They have no world ticker or vanilla BER, so their
 * existing proxy compositions must be attached and ticked by the contraption adapter instead.
 */
@Context
@CreateModBeans
@BeanOnlyIn.Client
public final class ContraptionProxyModels {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<AbstractContraptionEntity, Attachment> attachments = new IdentityHashMap<>();
    @Nullable
    private ClientLevel activeLevel;

    @PostConstruct
    void init() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        // Publish host poses before the shared model runtime samples this frame's committed targets.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::frame);
        NeoForge.EVENT_BUS.addListener(this::entityLeft);
    }

    void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != activeLevel) {
            closeAll();
            activeLevel = level;
        }
        if (level == null || minecraft.isPaused()) {
            return;
        }
        Set<AbstractContraptionEntity> seen = new HashSet<>();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity contraptionEntity)
                    || entity.isRemoved() || contraptionEntity.getContraption() == null) {
                continue;
            }
            Contraption contraption = contraptionEntity.getContraption();
            seen.add(contraptionEntity);
            Attachment attachment = attachments.get(contraptionEntity);
            if (attachment != null && attachment.contraption != contraption) {
                attachment.close();
                attachments.remove(contraptionEntity);
                attachment = null;
            }
            if (attachment == null) {
                if (contraption.presentBlockEntities.values().stream()
                        .noneMatch(ProxyModelBlockEntity.class::isInstance)) {
                    continue;
                }
                attachment = new Attachment(contraptionEntity, contraption);
                attachments.put(contraptionEntity, attachment);
            }
            try {
                attachment.tick();
            } catch (RuntimeException failure) {
                LOGGER.error("[proxy] captured model tick failed for {}", entity.getUUID(), failure);
            }
        }
        var iterator = attachments.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!seen.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    void frame(RenderFrameEvent.Pre event) {
        if (Minecraft.getInstance().level != activeLevel) {
            closeAll();
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        var iterator = attachments.values().iterator();
        while (iterator.hasNext()) {
            Attachment attachment = iterator.next();
            if (!attachment.host.isAlive()) {
                attachment.close();
                iterator.remove();
                continue;
            }
            attachment.frame(partialTick);
        }
    }

    void entityLeft(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide) {
            return;
        }
        Attachment attachment = attachments.remove(event.getEntity());
        if (attachment != null) {
            attachment.close();
        }
    }

    private void closeAll() {
        for (Attachment attachment : attachments.values()) {
            attachment.close();
        }
        attachments.clear();
    }

    static ResourceLocation instanceIdentity(UUID entity, BlockPos localPos) {
        return ProxyIdentity.ofExternal("kasuga_lib",
                "contraption_model/" + entity + "/" + localPos.getX() + "/"
                        + localPos.getY() + "/" + localPos.getZ(), localPos.asLong()).key();
    }

    static Transform anchorTransform(ScenePose host, BlockPos localPos) {
        ScenePose anchor = host.compose(Vec3.atLowerCornerOf(localPos), new org.joml.Matrix4f());
        return new Transform().set(anchor.transform()).setPosition(new org.joml.Vector3f(
                (float) anchor.origin().x, (float) anchor.origin().y, (float) anchor.origin().z));
    }

    /** Refreshes host input without detaching its composition or advancing its animation clock. */
    static boolean refreshState(ProxyModelBlockEntity model, @Nullable StructureBlockInfo info) {
        if (info == null || info.state().getBlock() != model.getBlockState().getBlock()
                || !model.isValidBlockState(info.state())) {
            return false;
        }
        if (model.getBlockState() != info.state()) {
            model.setBlockState(info.state());
        }
        return true;
    }

    private static final class Attachment {
        private final AbstractContraptionEntity entity;
        private final Contraption contraption;
        private final SceneHost host;
        private final Map<BlockPos, ProxyModelBlockEntity> models = new java.util.HashMap<>();
        private final Set<BlockPos> portalHidden = new HashSet<>();

        private Attachment(AbstractContraptionEntity entity, Contraption contraption) {
            this.entity = entity;
            this.contraption = contraption;
            this.host = CreateSceneHosts.contraption(entity);
        }

        private void tick() {
            ScenePose pose = host.pose(1f);
            if (pose == null) {
                close();
                return;
            }
            Set<BlockPos> seen = new HashSet<>();
            for (var entry : contraption.presentBlockEntities.entrySet()) {
                BlockEntity blockEntity = entry.getValue();
                if (!(blockEntity instanceof ProxyModelBlockEntity model) || model.isRemoved()) {
                    continue;
                }
                BlockPos localPos = entry.getKey();
                if (!refreshState(model, contraption.getBlocks().get(localPos))) {
                    continue;
                }
                seen.add(localPos);
                ProxyModelBlockEntity previous = models.put(localPos, model);
                if (previous != null && previous != model) {
                    previous.releaseVisualHost();
                }
                model.bindVisualHost(instanceIdentity(entity.getUUID(), localPos),
                        anchorTransform(pose, localPos));
                if (contraption.isHiddenInPortal(localPos)) {
                    portalHidden.add(localPos);
                    if (model.proxySession() != null) model.proxySession().suspend();
                    continue;
                }
                if (portalHidden.remove(localPos) && model.proxySession() != null) {
                    model.proxySession().resume();
                }
                ProxyTickers.tickClientBlockEntity(model);
            }
            var iterator = models.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!seen.contains(entry.getKey())) {
                    entry.getValue().releaseVisualHost();
                    iterator.remove();
                }
            }
            portalHidden.retainAll(seen);
        }

        private void frame(float partialTick) {
            ScenePose pose = host.pose(partialTick);
            if (pose == null) return;
            for (var entry : models.entrySet()) {
                ProxyModelBlockEntity model = entry.getValue();
                model.bindVisualHost(instanceIdentity(entity.getUUID(), entry.getKey()),
                        anchorTransform(pose, entry.getKey()));
                if (model.proxySession() != null) {
                    model.proxySession().publishFrame(null);
                }
            }
        }

        private void close() {
            for (ProxyModelBlockEntity model : models.values()) {
                try {
                    model.releaseVisualHost();
                } catch (RuntimeException failure) {
                    LOGGER.error("[proxy] captured model cleanup failed for {}", entity.getUUID(), failure);
                }
            }
            models.clear();
            portalHidden.clear();
        }
    }
}
