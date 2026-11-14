package lib.kasuga.test.registration.data_driven.source;

import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceDescriptorTest {
    @Test
    void keepsNamespacesImmutableAndStable() {
        SourceDescriptor descriptor = new SourceDescriptor(
                "installed/example", "example", List.of("zeta", "example_railway", "alpha"), "example pack");

        assertEquals(List.of("alpha", "example_railway", "zeta"), new ArrayList<>(descriptor.namespaces()));
        assertThrows(UnsupportedOperationException.class, () -> descriptor.namespaces().add("other"));
    }

    @Test
    void rejectsMissingIdentityAndInvalidNamespaces() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceDescriptor("", "owner", List.of("example"), "label"));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceDescriptor("source", "owner", List.of(), "label"));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceDescriptor("source", "owner", List.of("BadNamespace"), "label"));
    }

    @Test
    void preservesSharedResourceProviderIdentity() {
        ResourceSourceDescriptor resource = new ResourceSourceDescriptor(
                "example",
                "example-pack",
                "parcel-m1",
                42,
                ResourceSourceDescriptor.Origin.EXTERNAL
        );
        SourceDescriptor descriptor = SourceDescriptor.of(
                resource,
                List.of("example"),
                "example parcel"
        );

        assertEquals("example-pack", descriptor.sourceId());
        assertEquals("example", descriptor.ownerId());
        assertEquals("parcel-m1", descriptor.rootId());
        assertEquals(42, descriptor.rank());
        assertEquals(resource, descriptor.resource());
    }
}
