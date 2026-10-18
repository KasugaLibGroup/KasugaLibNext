package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.shaders.Uniform;
import lib.kasuga.shader.ShaderParameter;
import lib.kasuga.shader.ShaderParameterSchema;
import lib.kasuga.shader.ShaderParameterType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderParameterBlockTest {
    private static final ShaderParameter STRENGTH = ShaderParameter.floatParameter(
            "Strength", "Distortion strength", 0.5f, 0.0f, 2.0f
    );
    private static final ShaderParameter ITERATIONS = ShaderParameter.intParameter(
            "Iterations", "Iteration count", 4, 1, 16
    );
    private static final ShaderParameter ENABLED = ShaderParameter.booleanParameter(
            "Enabled", "Whether the effect is enabled", true
    );

    @Test
    void usesDefaultsAndSupportsValidatedRuntimeUpdates() {
        ShaderParameterBlock block = new ShaderParameterBlock(ShaderParameterSchema.of(
                java.util.List.of(STRENGTH, ITERATIONS, ENABLED)
        ));

        assertEquals(0.5f, block.floatValue("Strength"));
        assertEquals(4, block.intValue("Iterations"));
        assertTrue(block.booleanValue("Enabled"));
        assertEquals(0L, block.version());
        assertTrue(block.isDefault(STRENGTH));
        assertFalse(block.hasOverrides());

        block.setFloat(STRENGTH, 1.25f);
        block.setInt(ITERATIONS, 8);
        block.setBoolean(ENABLED, false);
        assertEquals(1.25f, block.floatValue("Strength"));
        assertEquals(8, block.intValue("Iterations"));
        assertFalse(block.booleanValue("Enabled"));
        assertEquals(3L, block.version());
        assertFalse(block.isDefault("Strength"));
        assertTrue(block.hasOverrides());

        assertThrows(IllegalArgumentException.class, () -> block.set("Strength", 2.5));
        assertThrows(IllegalArgumentException.class, () -> block.set("Iterations", 3.5));
        assertThrows(IllegalArgumentException.class, () -> block.set("Missing", 1.0));

        block.reset(STRENGTH);
        assertEquals(0.5f, block.floatValue("Strength"));
        block.resetAll();
        assertEquals(4, block.intValue("Iterations"));
        assertTrue(block.booleanValue("Enabled"));
        assertFalse(block.hasOverrides());
    }

    @Test
    void independentBlocksDoNotShareRuntimeValues() {
        ShaderParameterSchema schema = ShaderParameterSchema.of(java.util.List.of(STRENGTH));
        ShaderParameterBlock first = new ShaderParameterBlock(schema);
        ShaderParameterBlock second = new ShaderParameterBlock(schema);

        first.setFloat(STRENGTH, 1.75f);

        assertArrayEquals(new double[]{1.75}, first.values("Strength"));
        assertArrayEquals(new double[]{0.5}, second.values("Strength"));
    }

    @Test
    void preservesFullSignedIntegerPrecision() {
        ShaderParameter largeInteger = ShaderParameter.intParameter(
                "LargeInteger", "Full precision integer", 0, Integer.MIN_VALUE, Integer.MAX_VALUE
        );
        ShaderParameterBlock block = new ShaderParameterBlock(
                ShaderParameterSchema.of(java.util.List.of(largeInteger))
        );

        block.setInt(largeInteger, 1_234_567_891);

        assertEquals(1_234_567_891, block.intValue("LargeInteger"));
        assertArrayEquals(new double[]{1_234_567_891.0}, block.values("LargeInteger"));
    }

    @Test
    void validatesEveryExposedTypeAgainstMinecraftUniformShape() {
        for (ShaderParameterType type : ShaderParameterType.values()) {
            Number[] defaults = new Number[type.componentCount()];
            java.util.Arrays.fill(defaults, type.integral() ? 0 : 0.0f);
            ShaderParameter parameter = ShaderParameter.builder(
                            "Value" + type.name(), "Uniform shape for " + type, type
                    )
                    .range(0, 1)
                    .defaultValues(defaults)
                    .build();
            int expectedType = switch (type) {
                case INTEGER, BOOLEAN -> Uniform.UT_INT1;
                case FLOAT -> Uniform.UT_FLOAT1;
                case VEC2 -> Uniform.UT_FLOAT2;
                case VEC3, COLOR_RGB -> Uniform.UT_FLOAT3;
                case VEC4, COLOR_RGBA -> Uniform.UT_FLOAT4;
                case MAT2 -> Uniform.UT_MAT2;
                case MAT3 -> Uniform.UT_MAT3;
                case MAT4 -> Uniform.UT_MAT4;
            };

            assertDoesNotThrow(() -> ShaderParameterBlock.validateUniformShape(
                    parameter, expectedType, type.componentCount(), "test"
            ));
            assertThrows(IllegalStateException.class, () -> ShaderParameterBlock.validateUniformShape(
                    parameter, Uniform.UT_FLOAT1, type.componentCount() + 1, "test"
            ));
        }
    }

    @Test
    void publishesWholeVectorSnapshotsAcrossConcurrentUpdates() throws Exception {
        ShaderParameter vector = ShaderParameter.builder(
                        "Vector", "Concurrent vector", ShaderParameterType.VEC4
                )
                .range(0, 8)
                .defaultValues(0, 0, 0, 0)
                .build();
        ShaderParameterBlock block = new ShaderParameterBlock(
                ShaderParameterSchema.of(List.of(vector))
        );
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        try {
            for (int worker = 1; worker <= 4; worker++) {
                int value = worker;
                writers.add(executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 1_000; iteration++) {
                        block.set(vector, value, value, value, value);
                        double[] snapshot = block.values("Vector");
                        for (double component : snapshot) assertEquals(snapshot[0], component);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) writer.get();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void notifiesPersistenceListenerOnlyForRealChanges() {
        ShaderParameterBlock block = new ShaderParameterBlock(
                ShaderParameterSchema.of(List.of(STRENGTH))
        );
        AtomicInteger changes = new AtomicInteger();
        block.onChange(changes::incrementAndGet);

        block.setFloat(STRENGTH, 0.5f);
        block.resetAll();
        assertEquals(0, changes.get());

        block.setFloat(STRENGTH, 1.0f);
        block.setFloat(STRENGTH, 1.0f);
        block.reset(STRENGTH);
        block.reset(STRENGTH);
        assertEquals(2, changes.get());
    }
}
