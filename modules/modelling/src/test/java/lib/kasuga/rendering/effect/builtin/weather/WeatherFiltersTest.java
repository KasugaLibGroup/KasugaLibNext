package lib.kasuga.rendering.effect.builtin.weather;

import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayScene;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.rendering.models.mc.scene.display.WorldWeatherFilterTarget;
import lib.kasuga.rendering.weather.WeatherFilterFrame;
import lib.kasuga.rendering.weather.WeatherFilterVolume;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

final class WeatherFiltersTest {

    @AfterEach
    void cleanup() {
        WeatherFilters.resetForTests();
    }

    @Test
    void entitySelectionPriorityAndRefCountedInclusionsExclusionsWorkWithoutRegistries() {
        Map<String, Integer> included = new HashMap<>();
        Map<String, Integer> excluded = new HashMap<>();

        String pig = "minecraft:pig";
        String boat = "minecraft:boat";
        String minecart = "minecraft:minecart";

        assertFalse(WeatherFilters.evaluateEntitySelection(false, pig, null, included, excluded, null, false, false, () -> false));
        assertTrue(WeatherFilters.evaluateEntitySelection(false, pig, null, included, excluded, null, true, false, () -> false));
        assertFalse(WeatherFilters.evaluateEntitySelection(true, pig, null, included, excluded, null, true, false, () -> false));
        assertFalse(WeatherFilters.evaluateEntitySelection(false, null, null, included, excluded, null, true, false, () -> false));

        // Ref-counted inclusion and exclusion
        WeatherFilters.incrementCounts(included, Set.of(boat));
        WeatherFilters.incrementCounts(included, Set.of(boat));
        assertTrue(WeatherFilters.evaluateEntitySelection(false, boat, null, included, excluded, null, false, false, () -> false));
        assertFalse(WeatherFilters.evaluateEntitySelection(false, pig, null, included, excluded, null, false, false, () -> false));

        WeatherFilters.decrementCounts(included, Set.of(boat));
        assertTrue(WeatherFilters.evaluateEntitySelection(false, boat, null, included, excluded, null, false, false, () -> false),
                "Second registration keeps inclusion active");

        WeatherFilters.incrementCounts(excluded, Set.of(boat));
        assertFalse(WeatherFilters.evaluateEntitySelection(false, boat, null, included, excluded, null, false, false, () -> false),
                "Exclusion overrides inclusion");
        assertFalse(WeatherFilters.evaluateEntitySelection(false, boat, null, included, excluded, null, true, true, () -> true),
                "Exclusion overrides default adapter, tag, and custom predicate");

        WeatherFilters.decrementCounts(excluded, Set.of(boat));
        assertTrue(WeatherFilters.evaluateEntitySelection(false, boat, null, included, excluded, null, false, false, () -> false));

        WeatherFilters.decrementCounts(included, Set.of(boat));
        assertFalse(WeatherFilters.evaluateEntitySelection(false, boat, null, included, excluded, null, false, false, () -> false));

        // Optional EntityType gate restricts default adapters while still requiring inclusion/tag/predicate/adapter match
        Predicate<String> gate = type -> type.equals(minecart);
        assertFalse(WeatherFilters.evaluateEntitySelection(false, pig, null, included, excluded, gate, true, false, () -> false));
        assertFalse(WeatherFilters.evaluateEntitySelection(false, minecart, null, included, excluded, gate, false, false, () -> false));
        assertTrue(WeatherFilters.evaluateEntitySelection(false, minecart, null, included, excluded, gate, true, false, () -> false));
        assertTrue(WeatherFilters.evaluateEntitySelection(false, minecart, null, included, excluded, gate, false, true, () -> false));
        assertTrue(WeatherFilters.evaluateEntitySelection(false, minecart, null, included, excluded, gate, false, false, () -> true));
    }

    @Test
    void worldWeatherFilterTargetComposesSceneHierarchyAndClosesCleanly() {
        WorldDisplayScene root = new WorldDisplayScene();
        root.transform(new Matrix4f().translation(10.0f, 70.0f, -5.0f).rotateY((float) (Math.PI / 2.0)));

        WorldWeatherFilterTarget child = new WorldWeatherFilterTarget(
                WeatherFilterVolume.box(4.0, 2.0, 6.0).position(0.0, 1.0, 2.0).strength(0.8f).build()
        );
        root.add(child);

        List<WorldDisplayTarget.WeatherFilter> filters = root.weatherFilters();
        assertEquals(1, filters.size());
        assertEquals(0.8f, filters.get(0).volume().strength(), 1e-6f);

        root.visible(false);
        assertTrue(root.weatherFilters().isEmpty());
        root.visible(true);
        assertEquals(1, root.weatherFilters().size());

        root.remove(child);
        assertTrue(child.isClosed());
        assertTrue(root.weatherFilters().isEmpty());
        root.close();
        root.close();
    }

    @Test
    void cpuColumnClampAndShelterChecksRespondToVolumesAndOccluders() throws Exception {
        WeatherFilterVolume roofBox = WeatherFilterVolume.box(4.0, 2.0, 4.0)
                .position(5.0, 72.0, 5.0)
                .strength(1.0f)
                .build();
        WeatherFilterVolume mistBox = WeatherFilterVolume.box(4.0, 2.0, 4.0)
                .position(20.0, 72.0, 20.0)
                .strength(0.4f)
                .build();

        try (AutoCloseable v1 = WeatherFilters.addVolume(WeatherFilterFrame.world(roofBox));
             AutoCloseable v2 = WeatherFilters.addVolume(WeatherFilterFrame.world(mistBox))) {
            assertEquals(73, WeatherFilters.computeClampedColumnHeight(null, 5, 5, 64, 90, 1.0f));
            assertEquals(64, WeatherFilters.computeClampedColumnHeight(null, 20, 20, 64, 90, 1.0f));

            assertFalse(WeatherFilters.shouldSpawnRainParticle(null, 5.5, 72.0, 5.5));
            assertTrue(WeatherFilters.shouldSpawnRainParticle(null, 20.5, 72.0, 20.5));
            assertTrue(WeatherFilters.isPositionSheltered(null, new Vec3(5.5, 72.0, 5.5)));
            assertFalse(WeatherFilters.isPositionSheltered(null, new Vec3(20.5, 72.0, 20.5)));
        }
        assertEquals(64, WeatherFilters.computeClampedColumnHeight(null, 5, 5, 64, 90, 1.0f));
        assertTrue(WeatherFilters.shouldSpawnRainParticle(null, 5.5, 72.0, 5.5));

        try (AutoCloseable occluder = WeatherFilters.registerOccluder(new WeatherOccluder() {
            @Override
            public boolean hasNearbyOccluders(ClientLevel level, Vec3 camera,
                                                @Nullable Entity cameraEntity, double horizontalRadius,
                                                double minWorldY, double maxWorldY, float partialTick) {
                return true;
            }

            @Override
            public void renderDepth(WeatherDepthPassContext context) {}

            @Override
            public OptionalDouble sampleRoofHeight(ClientLevel level, @Nullable Entity cameraEntity,
                                                   double worldX, double minY, double maxY,
                                                   double worldZ, float partialTick) {
                return OptionalDouble.of(75.25);
            }
        })) {
            assertEquals(76, WeatherFilters.computeClampedColumnHeight(null, 0, 0, 64, 90, 1.0f));
        }
    }
}
