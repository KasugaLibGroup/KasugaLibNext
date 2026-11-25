package lib.kasuga.create.client.weather;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import lib.kasuga.rendering.effect.builtin.weather.WeatherFilters;
import lib.kasuga.scene.ScenePose;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

final class CreateAndSableWeatherTest {

    @AfterEach
    void cleanupWeatherFilters() {
        WeatherFilters.setOptionalEntityTypeFilter(null);
    }

    @Test
    void weatherFiltersEntityTypeApiWorksWithBootstrappedEntityTypes() throws Exception {
        Entity pig = dummyEntity(EntityType.PIG, false, false);
        Entity boat = dummyEntity(EntityType.BOAT, false, false);
        Entity minecart = dummyEntity(EntityType.MINECART, false, false);
        Entity spectatorPig = dummyEntity(EntityType.PIG, false, true);
        Entity removedBoat = dummyEntity(EntityType.BOAT, true, false);

        assertFalse(WeatherFilters.shouldFilterEntity(pig, false));
        assertTrue(WeatherFilters.shouldFilterEntity(pig, true));
        assertFalse(WeatherFilters.shouldFilterEntity(spectatorPig, true));
        assertFalse(WeatherFilters.shouldFilterEntity(removedBoat, true));

        try (AutoCloseable included = WeatherFilters.includeEntityTypes(EntityType.BOAT)) {
            assertTrue(WeatherFilters.shouldFilterEntity(boat, false));
            assertFalse(WeatherFilters.shouldFilterEntity(pig, false));

            try (AutoCloseable excluded = WeatherFilters.excludeEntityTypes(EntityType.BOAT)) {
                assertFalse(WeatherFilters.shouldFilterEntity(boat, false));
                assertFalse(WeatherFilters.shouldFilterEntity(boat, true));
            }
            assertTrue(WeatherFilters.shouldFilterEntity(boat, false));
        }
        assertFalse(WeatherFilters.shouldFilterEntity(boat, false));

        WeatherFilters.setOptionalEntityTypeFilter(type -> type == EntityType.MINECART);
        assertFalse(WeatherFilters.shouldFilterEntity(pig, true));
        assertFalse(WeatherFilters.shouldFilterEntity(minecart, false));
        assertTrue(WeatherFilters.shouldFilterEntity(minecart, true));

        try (AutoCloseable predicate = WeatherFilters.registerEntityFilter(e -> e.getType() == EntityType.MINECART)) {
            assertTrue(WeatherFilters.shouldFilterEntity(minecart, false));
        }
        assertFalse(WeatherFilters.shouldFilterEntity(minecart, false));
    }

    @Test
    void contraptionWeatherRaycastHitsTiltedRoofAndLeavesWindowOutsideUnoccluded() {
        // Hollow carriage cabin in local coordinates:
        // Floor at y = 0, side pillars at x = -1 and x = +1 (y = 1..2), roof at y = 3 covering x in [-1..1], z in [0..4]
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z = 0; z <= 4; z++) {
            for (int x = -1; x <= 1; x++) {
                blocks.put(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
                blocks.put(new BlockPos(x, 3, z), Blocks.GLASS.defaultBlockState());
            }
            blocks.put(new BlockPos(-1, 1, z), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(-1, 2, z), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(1, 1, z), Blocks.STONE.defaultBlockState());
            blocks.put(new BlockPos(1, 2, z), Blocks.STONE.defaultBlockState());
        }
        BlockGetter getter = mapBlockGetter(blocks);

        // Rotate carriage 30 degrees around Y and bank 15 degrees around Z at origin (100, 70, -40)
        Matrix4f transform = new Matrix4f().rotateY((float) Math.toRadians(30.0)).rotateZ((float) Math.toRadians(15.0));
        ScenePose pose = new ScenePose(new Vec3(100.0, 70.0, -40.0), transform);

        // Cabin interior center (local 0.5, 1.5, 2.5); vertical world ray hits tilted plane local y = 4.0
        Vec3 cabinWorld = pose.toWorld(new Vec3(0.5, 1.5, 2.5));
        Vec3 localRayDir = pose.toLocal(cabinWorld.add(0.0, 1.0, 0.0)).subtract(new Vec3(0.5, 1.5, 2.5));
        double expectedRoofWorldY = cabinWorld.y + (4.0 - 1.5) / localRayDir.y;

        OptionalDouble roofHit = ContraptionWeatherRaycast.raycastVertical(
                pose, -1, 0, 0, 1, 3, 4, getter,
                cabinWorld.x, cabinWorld.y, cabinWorld.y + 12.0, cabinWorld.z
        );
        assertTrue(roofHit.isPresent(), "Tilted glass roof must block rain above cabin");
        assertEquals(expectedRoofWorldY, roofHit.getAsDouble(), 1e-4);

        // Outside the carriage beside the window (local x = 4.0, z = 2.5)
        Vec3 outsideWorld = pose.toWorld(new Vec3(4.0, 1.5, 2.5));
        OptionalDouble outsideHit = ContraptionWeatherRaycast.raycastVertical(
                pose, -1, 0, 0, 1, 3, 4, getter,
                outsideWorld.x, outsideWorld.y, outsideWorld.y + 12.0, outsideWorld.z
        );
        assertTrue(outsideHit.isEmpty(), "Outside the carriage window must remain unoccluded");
    }

    @Test
    void sablePlotRaycastHandlesRotationNonUniformScaleAndThirtyMillionOrigin() {
        Pose3d pose = new Pose3d();
        pose.position().set(29_999_998.25, 80.0, -29_999_995.5);
        pose.rotationPoint().set(1_280_000.5, 64.0, 1_280_000.5);
        pose.orientation().rotateXYZ(0.15, -0.45, 0.25);
        pose.scale().set(1.2, 0.9, 1.5);

        BlockPos roofBlock = new BlockPos(1_280_000, 66, 1_280_000);
        Map<BlockPos, BlockState> blocks = Map.of(roofBlock, Blocks.IRON_BLOCK.defaultBlockState());
        BlockGetter getter = mapBlockGetter(blocks);
        BoundingBox3d plotBounds = new BoundingBox3d(1_280_000, 64, 1_280_000, 1_280_002, 68, 1_280_002);

        Vec3 cabinWorld = pose.transformPosition(new Vec3(1_280_000.5, 64.5, 1_280_000.5));
        var localUp = pose.transformPositionInverse(new org.joml.Vector3d(cabinWorld.x, cabinWorld.y + 1.0, cabinWorld.z))
                .sub(1_280_000.5, 64.5, 1_280_000.5);
        double expectedWorldY = cabinWorld.y + (67.0 - 64.5) / localUp.y;

        OptionalDouble hit = SableWeatherBridge.sampleSableRoof(
                pose, plotBounds, getter,
                cabinWorld.x, cabinWorld.y, cabinWorld.y + 12.0, cabinWorld.z
        );
        assertTrue(hit.isPresent(), "Sable plot roof should be hit at 30M world coordinates");
        assertEquals(expectedWorldY, hit.getAsDouble(), 1e-4);

        Vec3 outsideWorld = pose.transformPosition(new Vec3(1_280_005.0, 64.5, 1_280_005.0));
        OptionalDouble miss = SableWeatherBridge.sampleSableRoof(
                pose, plotBounds, getter,
                outsideWorld.x, outsideWorld.y, outsideWorld.y + 12.0, outsideWorld.z
        );
        assertTrue(miss.isEmpty());
    }

    @Test
    void createWeatherOccludersLoadsCleanlyWithoutSableCompanion() throws Exception {
        ClassLoader parent = CreateWeatherOccluders.class.getClassLoader();
        String targetName = CreateWeatherOccluders.class.getName();
        ClassLoader noSableLoader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.ryanhcode.sable.")) {
                    throw new ClassNotFoundException("Sable Companion deliberately absent");
                }
                if (name.equals(targetName) || name.startsWith(targetName + "$")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            try (var stream = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                                if (stream == null) {
                                    throw new ClassNotFoundException(name);
                                }
                                byte[] bytes = stream.readAllBytes();
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            } catch (java.io.IOException io) {
                                throw new ClassNotFoundException(name, io);
                            }
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };

        assertFalse(SableWeatherBridge.class.isAnnotationPresent(net.neoforged.fml.common.EventBusSubscriber.class));
        Class<?> isolated = Class.forName(targetName, true, noSableLoader);
        assertNotNull(isolated.getMethod("ensureRegistered"));
        assertTrue(isolated.getDeclaredMethods().length > 0);
        Class<?> raycastIsolated = Class.forName(ContraptionWeatherRaycast.class.getName(), true, noSableLoader);
        assertTrue(raycastIsolated.getDeclaredMethods().length > 0);
    }

    private static Entity dummyEntity(EntityType<?> type, boolean removed, boolean spectator) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Entity entity = (Entity) unsafe.allocateInstance(DummyEntity.class);
        setField(Entity.class, entity, "type", type);
        setField(Entity.class, entity, "removalReason", removed ? Entity.RemovalReason.DISCARDED : null);
        setField(DummyEntity.class, entity, "spectator", spectator);
        return entity;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class DummyEntity extends Entity {
        private boolean spectator;

        private DummyEntity(EntityType<?> entityType, Level level) {
            super(entityType, level);
        }

        @Override public boolean isSpectator() { return spectator; }
        @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag compound) {}
        @Override protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag compound) {}
    }

    private static BlockGetter mapBlockGetter(Map<BlockPos, BlockState> blocks) {
        return new BlockGetter() {
            @Override
            public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return getBlockState(pos).getFluidState();
            }

            @Override
            public int getHeight() {
                return 320;
            }

            @Override
            public int getMinBuildHeight() {
                return -64;
            }
        };
    }
}
