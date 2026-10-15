package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.shader.ShaderProgram;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderShaderRegistryTest {

    @Test
    void exposesStableHandleAndSortedIds() {
        ResourceLocation zeta = id("zeta");
        ResourceLocation alpha = id("alpha");
        var zetaRegistration = register(
                RenderShaderDescriptor.standard(zeta, DefaultVertexFormat.BLIT_SCREEN)
        );
        var alphaRegistration = register(
                RenderShaderDescriptor.standard(alpha, DefaultVertexFormat.BLIT_SCREEN)
        );

        try {
            assertEquals(owner(), zetaRegistration.owner());
            assertTrue(zetaRegistration.isActive());
            assertSame(zetaRegistration.handle(), RenderShaderRegistry.get(zeta).orElseThrow());
            assertFalse(zetaRegistration.handle().isReady());
            assertEquals(List.of(alpha, zeta), RenderShaderRegistry.registeredIds().stream()
                    .filter(value -> value.getNamespace().equals("kasuga_shader_test"))
                    .toList());
        } finally {
            zetaRegistration.close();
            alphaRegistration.close();
        }
        assertFalse(zetaRegistration.isActive());
        assertEquals(ShaderLoadState.CLOSED, zetaRegistration.status().state());
        assertTrue(zetaRegistration.whenReady().isCompletedExceptionally());
    }

    @Test
    void staleRegistrationCannotRemoveReplacement() {
        ResourceLocation id = id("replacement");
        var old = register(
                RenderShaderDescriptor.standard(id, DefaultVertexFormat.BLIT_SCREEN)
        );
        assertThrows(IllegalStateException.class, () -> register(
                RenderShaderDescriptor.standard(id, DefaultVertexFormat.POSITION)
        ));
        var replacement = RenderShaderRegistry.register(
                owner(), RenderShaderDescriptor.standard(id, DefaultVertexFormat.POSITION),
                DuplicatePolicy.REPLACE
        );

        try {
            old.close();
            assertTrue(RenderShaderRegistry.isRegistered(id));
            assertSame(replacement.handle(), RenderShaderRegistry.get(id).orElseThrow());

            replacement.close();
            assertFalse(RenderShaderRegistry.isRegistered(id));
        } finally {
            old.close();
            replacement.close();
        }
    }

    @Test
    void registersImperativeProgramWithoutAResourceDescriptor() {
        ShaderProgram program = ShaderProgram.fullscreen("kasuga_shader_test:generated", shader -> {
            var scene = shader.sampler2D("SceneSampler");
            shader.fragmentColor(scene.sample(shader.texCoord()));
        });

        var registration = register(RenderShaderDescriptor.generated(program));
        try {
            assertEquals(id("generated"), registration.descriptor().id());
            assertSame(DefaultVertexFormat.BLIT_SCREEN, registration.descriptor().vertexFormat());
            assertEquals(RenderShaderDescriptor.SourceKind.GENERATED, registration.descriptor().sourceKind());
            assertSame(registration.handle(), RenderShaderRegistry.get(id("generated")).orElseThrow());
        } finally {
            registration.close();
        }
    }

    @Test
    void reusesTranslatedGeneratedShaderBundle() {
        GeneratedShaderPreloader.clear();
        ShaderProgram program = ShaderProgram.fullscreen("kasuga_shader_test:cached_generated", shader -> {
            var scene = shader.sampler2D("SceneSampler");
            shader.fragmentColor(scene.sample(shader.texCoord()));
        });

        RenderShaderDescriptor.generated(program);
        RenderShaderDescriptor.generated(program);

        GeneratedShaderPreloader.Prepared first = GeneratedShaderPreloader.prepare(program);
        GeneratedShaderPreloader.Prepared second = GeneratedShaderPreloader.prepare(program);
        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        assertSame(first.resources(), second.resources());
        assertEquals(first.translationNanos(), second.translationNanos());
        GeneratedShaderPreloader.clear();
    }

    @Test
    void deduplicatesConcurrentGeneratedShaderPreparation() throws Exception {
        GeneratedShaderPreloader.clear();
        ShaderProgram program = ShaderProgram.fullscreen("kasuga_shader_test:concurrent_generated", shader -> {
            var scene = shader.sampler2D("SceneSampler");
            shader.fragmentColor(scene.sample(shader.texCoord()));
        });
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<GeneratedShaderPreloader.Prepared>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return GeneratedShaderPreloader.prepare(program);
                }));
            }
            start.countDown();
            List<GeneratedShaderPreloader.Prepared> prepared = new ArrayList<>();
            for (Future<GeneratedShaderPreloader.Prepared> future : futures) prepared.add(future.get());

            assertEquals(1, prepared.stream().filter(value -> !value.cacheHit()).count());
            GeneratedShaderResourceProvider shared = prepared.getFirst().resources();
            assertTrue(prepared.stream().allMatch(value -> value.resources() == shared));
        } finally {
            executor.shutdownNow();
            GeneratedShaderPreloader.clear();
        }
    }

    @Test
    void exposesRegisteredShaderPreloadState() {
        ResourceLocation shaderId = id("diagnostics");
        var registration = register(
                RenderShaderDescriptor.standard(shaderId, DefaultVertexFormat.POSITION)
                        .withPreload(ShaderPreloadPolicy.MANUAL, -20)
        );
        try {
            RenderShaderRegistry.ShaderSnapshot snapshot = RenderShaderRegistry.snapshots().stream()
                    .filter(value -> value.id().equals(shaderId))
                    .findFirst()
                    .orElseThrow();
            assertEquals(RenderShaderDescriptor.SourceKind.RESOURCE, snapshot.sourceKind());
            assertEquals(ShaderLoadState.REGISTERED, snapshot.state());
            assertEquals(ShaderPreloadPolicy.MANUAL, snapshot.preloadPolicy());
            assertEquals(-20, snapshot.preloadPriority());
            assertTrue(RenderShaderRegistry.preloadStats().registered() >= 1);
        } finally {
            registration.close();
        }
    }

    @Test
    void customFactoryBuilderExposesPreloadConfiguration() {
        RenderShaderDescriptor descriptor = RenderShaderDescriptor.builder(
                        id("deferred"), DefaultVertexFormat.POSITION
                )
                .preloadPolicy(ShaderPreloadPolicy.DEFERRED)
                .preloadPriority(30)
                .failurePolicy(ShaderFailurePolicy.FAIL_RELOAD)
                .build();

        assertEquals(ShaderPreloadPolicy.DEFERRED, descriptor.preloadPolicy());
        assertEquals(30, descriptor.preloadPriority());
        assertEquals(ShaderFailurePolicy.FAIL_RELOAD, descriptor.failurePolicy());
    }

    @Test
    void registersGeneratedGraphicsProgramWithExplicitVertexFormat() {
        ShaderProgram program = ShaderProgram.graphics(
                "kasuga_shader_test:graphics",
                vertex -> {
                    var position = vertex.inputVec3("Position");
                    var modelView = vertex.uniformMat4("ModelViewMat");
                    var projection = vertex.uniformMat4("ProjMat");
                    vertex.position(projection.transform(modelView.transform(
                            vertex.vec4(position, vertex.f32(1))
                    )));
                },
                fragment -> fragment.fragmentColor(fragment.vec4(1, 1, 1, 1))
        );

        var registration = register(
                RenderShaderDescriptor.generated(program, DefaultVertexFormat.POSITION)
        );
        try {
            assertEquals(id("graphics"), registration.descriptor().id());
            assertSame(DefaultVertexFormat.POSITION, registration.descriptor().vertexFormat());
            assertThrows(IllegalArgumentException.class, () -> RenderShaderDescriptor.generated(program));
        } finally {
            registration.close();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_shader_test", path);
    }

    private static ResourceLocation owner() {
        return id("owner");
    }

    private static ShaderRegistration register(RenderShaderDescriptor descriptor) {
        return RenderShaderRegistry.register(owner(), descriptor, DuplicatePolicy.FAIL);
    }
}
