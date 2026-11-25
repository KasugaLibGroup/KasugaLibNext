package lib.kasuga.rendering.effect.builtin.weather;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.mc.backend.schedule.UmlModelEntityRenderer;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.BoneCollisionBounds;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.WeakHashMap;

/**
 * Default {@link WeatherOccluder} for generic entities opted in via
 * {@link WeatherFilters#includeEntityTypes}, {@code #kasuga_lib:occludes_weather},
 * {@link WeatherFilterConfig}, or {@link WeatherFilters#registerEntityFilter}.
 * Entities handled by dedicated adapters (e.g. Create contraptions) are skipped here.
 */
final class GenericEntityWeatherOccluder implements WeatherOccluder {
    private static final double ENTITY_MARGIN = 4.0;
    private final Map<Model, BoneCollisionBounds> boundsCache = new WeakHashMap<>();

    @Override
    public boolean hasNearbyOccluders(
            ClientLevel level,
            Vec3 camera,
            @Nullable Entity cameraEntity,
            double horizontalRadius,
            double minWorldY,
            double maxWorldY,
            float partialTick
    ) {
        AABB search = searchBox(camera, horizontalRadius, minWorldY, maxWorldY);
        List<Entity> candidates = level.getEntities(cameraEntity, search, entity -> isEligible(entity, cameraEntity));
        return !candidates.isEmpty();
    }

    @Override
    public void renderDepth(WeatherDepthPassContext context) {
        ClientLevel level = context.level();
        Vec3 camera = context.camera();
        Entity cameraEntity = context.cameraEntity();
        float partialTick = context.partialTick();
        AABB search = searchBox(camera, context.horizontalRadius(), context.minWorldY(), context.maxWorldY());
        List<Entity> entities = level.getEntities(cameraEntity, search, entity -> isEligible(entity, cameraEntity));
        if (entities.isEmpty()) {
            return;
        }

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        PoseStack poseStack = context.poseStack();
        for (Entity entity : entities) {
            EntityRenderer<? super Entity> renderer = dispatcher.getRenderer(entity);
            if (renderer instanceof UmlModelEntityRenderer<?> umlRenderer) {
                if (renderUmlBounds(entity, umlRenderer, camera, partialTick, context)) {
                    continue;
                }
            }
            double ex = Mth.lerp(partialTick, entity.xOld, entity.getX());
            double ey = Mth.lerp(partialTick, entity.yOld, entity.getY());
            double ez = Mth.lerp(partialTick, entity.zOld, entity.getZ());
            float yaw = Mth.lerp(partialTick, entity.yRotO, entity.getYRot());
            poseStack.pushPose();
            try {
                dispatcher.render(
                        entity,
                        ex - camera.x,
                        ey - camera.y,
                        ez - camera.z,
                        yaw,
                        partialTick,
                        poseStack,
                        context.buffers(),
                        LightTexture.FULL_BRIGHT
                );
            } catch (RuntimeException ignored) {
                // Keep rain depth capture resilient against third-party entity renderer quirks.
            } finally {
                poseStack.popPose();
            }
        }
    }

    @Override
    public OptionalDouble sampleRoofHeight(
            ClientLevel level,
            @Nullable Entity cameraEntity,
            double worldX,
            double minY,
            double maxY,
            double worldZ,
            float partialTick
    ) {
        if (level == null) {
            return OptionalDouble.empty();
        }
        AABB queryBox = new AABB(
                worldX - 0.5, minY - 1.0, worldZ - 0.5,
                worldX + 0.5, maxY + 1.0, worldZ + 0.5
        );
        List<Entity> entities = level.getEntities(cameraEntity, queryBox, entity -> isEligible(entity, cameraEntity));
        if (entities.isEmpty()) {
            return OptionalDouble.empty();
        }
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        double highest = Double.NEGATIVE_INFINITY;
        for (Entity entity : entities) {
            EntityRenderer<? super Entity> renderer = dispatcher.getRenderer(entity);
            if (renderer instanceof UmlModelEntityRenderer<?> umlRenderer) {
                ModelInstance model = resolveMountedModel(umlRenderer, entity);
                if (model != null) {
                    highest = Math.max(highest, sampleUmlBoxes(entity, model, worldX, minY, maxY, worldZ, partialTick));
                    continue;
                }
            }
            AABB box = entity.getBoundingBox();
            if (worldX >= box.minX && worldX <= box.maxX
                    && worldZ >= box.minZ && worldZ <= box.maxZ
                    && box.maxY >= minY && box.maxY <= maxY && box.maxY > highest) {
                highest = box.maxY;
            }
        }
        return highest > Double.NEGATIVE_INFINITY ? OptionalDouble.of(highest) : OptionalDouble.empty();
    }

    private boolean renderUmlBounds(
            Entity entity,
            UmlModelEntityRenderer<?> umlRenderer,
            Vec3 camera,
            float partialTick,
            WeatherDepthPassContext context
    ) {
        ModelInstance instance = resolveMountedModel(umlRenderer, entity);
        if (instance == null || instance.isClosed()) {
            return false;
        }
        List<BoneCollisionBounds.Box> boxes = sampleWorldOrLocalBoxes(entity, instance, partialTick);
        if (boxes.isEmpty()) {
            return false;
        }
        VertexConsumer consumer = context.buffers().getBuffer(RenderType.debugQuads());
        for (BoneCollisionBounds.Box box : boxes) {
            emitBoxTopAndWalls(consumer, box, camera);
        }
        return true;
    }

    private double sampleUmlBoxes(
            Entity entity,
            ModelInstance instance,
            double worldX,
            double minY,
            double maxY,
            double worldZ,
            float partialTick
    ) {
        double highest = Double.NEGATIVE_INFINITY;
        List<BoneCollisionBounds.Box> boxes = sampleWorldOrLocalBoxes(entity, instance, partialTick);
        for (BoneCollisionBounds.Box box : boxes) {
            if (worldX >= box.minX() && worldX <= box.maxX()
                    && worldZ >= box.minZ() && worldZ <= box.maxZ()
                    && box.maxY() >= minY && box.maxY() <= maxY && box.maxY() > highest) {
                highest = box.maxY();
            }
        }
        return highest;
    }

    private List<BoneCollisionBounds.Box> sampleWorldOrLocalBoxes(Entity entity, ModelInstance instance, float partialTick) {
        BoneCollisionBounds bounds;
        synchronized (boundsCache) {
            bounds = boundsCache.computeIfAbsent(instance.getModel(), BoneCollisionBounds::from);
        }
        List<BoneCollisionBounds.Box> raw = bounds.worldBounds(instance.getSkeletonInstance());
        if (raw.isEmpty()) {
            return raw;
        }
        AABB entityBox = entity.getBoundingBox().inflate(8.0);
        boolean matchesWorld = false;
        for (BoneCollisionBounds.Box box : raw) {
            if (box.maxX() >= entityBox.minX && box.minX() <= entityBox.maxX
                    && box.maxY() >= entityBox.minY && box.minY() <= entityBox.maxY
                    && box.maxZ() >= entityBox.minZ && box.minZ() <= entityBox.maxZ) {
                matchesWorld = true;
                break;
            }
        }
        if (matchesWorld) {
            return raw;
        }
        double ex = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double ey = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double ez = Mth.lerp(partialTick, entity.zOld, entity.getZ());
        List<BoneCollisionBounds.Box> shifted = new java.util.ArrayList<>(raw.size());
        for (BoneCollisionBounds.Box box : raw) {
            shifted.add(new BoneCollisionBounds.Box(
                    box.minX() + ex, box.minY() + ey, box.minZ() + ez,
                    box.maxX() + ex, box.maxY() + ey, box.maxZ() + ez
            ));
        }
        return shifted;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <E extends Entity> ModelInstance resolveMountedModel(UmlModelEntityRenderer<E> renderer, Entity entity) {
        return renderer.mountedModel((E) entity);
    }

    private static void emitBoxTopAndWalls(VertexConsumer consumer, BoneCollisionBounds.Box box, Vec3 camera) {
        float x0 = (float) (box.minX() - camera.x);
        float y0 = (float) (box.minY() - camera.y);
        float z0 = (float) (box.minZ() - camera.z);
        float x1 = (float) (box.maxX() - camera.x);
        float y1 = (float) (box.maxY() - camera.y);
        float z1 = (float) (box.maxZ() - camera.z);

        // Top face (+Y)
        consumer.addVertex(x0, y1, z0).setColor(255, 255, 255, 255);
        consumer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y1, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255);

        // North/South/West/East walls for tilted edges
        consumer.addVertex(x0, y0, z0).setColor(255, 255, 255, 255);
        consumer.addVertex(x0, y1, z0).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y0, z0).setColor(255, 255, 255, 255);

        consumer.addVertex(x0, y0, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y0, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y1, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255);

        consumer.addVertex(x0, y0, z0).setColor(255, 255, 255, 255);
        consumer.addVertex(x0, y0, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x0, y1, z0).setColor(255, 255, 255, 255);

        consumer.addVertex(x1, y0, z0).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y1, z1).setColor(255, 255, 255, 255);
        consumer.addVertex(x1, y0, z1).setColor(255, 255, 255, 255);
    }

    private static boolean isEligible(Entity entity, @Nullable Entity cameraEntity) {
        if (entity == cameraEntity) {
            return false;
        }
        if (WeatherFilters.isHandledByDedicatedAdapter(entity)) {
            return false;
        }
        return WeatherFilters.shouldFilterEntity(entity, false);
    }

    private static AABB searchBox(Vec3 camera, double horizontalRadius, double minWorldY, double maxWorldY) {
        double rx = horizontalRadius + ENTITY_MARGIN;
        return new AABB(
                camera.x - rx, minWorldY - ENTITY_MARGIN, camera.z - rx,
                camera.x + rx, maxWorldY + ENTITY_MARGIN, camera.z + rx
        );
    }
}
