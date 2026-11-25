package lib.kasuga.core.compat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompatCapabilityTest {
    @Test
    void probesLazilyAndCachesSuccessfulResult() {
        AtomicInteger calls = new AtomicInteger();
        CompatCapability<String> capability = new CompatCapability<>("sample", () -> {
            calls.incrementAndGet();
            return "ready";
        });

        assertEquals(0, calls.get());
        assertTrue(capability.isAvailable());
        assertSame(capability.result(), capability.result());
        assertEquals("ready", capability.result().value());
        assertEquals(1, calls.get());
    }

    @Test
    void reportsMissingClassAndMissingMemberWithCauses() {
        CompatCapability<Class<?>> missingClass = new CompatCapability<>("optional mod", () ->
                CompatCapability.requireClass("missing.optional.Mod", getClass().getClassLoader()));
        assertFalse(missingClass.isAvailable());
        assertTrue(missingClass.result().cause() instanceof ClassNotFoundException);
        assertTrue(missingClass.result().reason().contains("missing.optional.Mod"));

        CompatCapability<Method> missingMember = new CompatCapability<>("optional method", () ->
                CompatCapability.requireMethod(ExampleApi.class, "gone", void.class));
        assertFalse(missingMember.isAvailable());
        assertTrue(missingMember.result().cause() instanceof NoSuchMethodException);
    }

    @Test
    void reportsSignatureDriftExplicitly() {
        CompatCapability<Method> drift = new CompatCapability<>("changed API", () ->
                CompatCapability.requireMethod(ExampleApi.class, "count", String.class));

        assertFalse(drift.result().available());
        assertTrue(drift.result().reason().contains("return type changed"));
        assertTrue(drift.result().cause() instanceof NoSuchMethodException);
    }

    @Test
    void catchesLinkageAndSecurityFailuresButPropagatesApplicationErrors() {
        CompatCapability<Object> linkage = new CompatCapability<>("linked API", () -> {
            throw new NoClassDefFoundError("dependency missing");
        });
        assertFalse(linkage.isAvailable());
        assertTrue(linkage.result().cause() instanceof NoClassDefFoundError);

        CompatCapability<Object> denied = new CompatCapability<>("restricted API", () -> {
            throw new SecurityException("access denied");
        });
        assertFalse(denied.isAvailable());
        assertTrue(denied.result().cause() instanceof SecurityException);

        CompatCapability<Object> applicationBug = new CompatCapability<>("application", () -> {
            throw new IllegalStateException("bug in probe logic");
        });
        assertThrows(IllegalStateException.class, applicationBug::result);
    }

    @Test
    void treatsNullAndInaccessibleReflectionAsUnavailable() {
        CompatCapability<Object> noCapability = new CompatCapability<>("optional API", () -> null);
        assertFalse(noCapability.isAvailable());
        assertNull(noCapability.result().value());
        assertTrue(noCapability.result().reason().contains("Probe returned null"));
        assertTrue(noCapability.result().cause() instanceof IllegalStateException);

        CompatCapability<Object> inaccessible = new CompatCapability<>("closed module", () -> {
            throw new java.lang.reflect.InaccessibleObjectException("module is not open");
        });
        assertFalse(inaccessible.isAvailable());
        assertTrue(inaccessible.result().cause() instanceof java.lang.reflect.InaccessibleObjectException);
        assertTrue(inaccessible.result().reason().contains("module is not open"));
    }

    public static final class ExampleApi {
        public int count() { return 1; }
    }
}
