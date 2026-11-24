package lib.kasuga.registration.data_driven.source;

import lib.kasuga.core.resource.source.ResourceSourceDescriptor;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Registration-specific metadata layered on the shared resource provider identity.
 *
 * <p>The four-component constructor remains the legacy adapter contract and permits its historical
 * diagnostic source identifiers. Sources created from the shared resource platform use
 * {@link #of(ResourceSourceDescriptor, Collection, String)} and retain owner, root, rank and origin.
 */
public record SourceDescriptor(
        String sourceId,
        String ownerId,
        Set<String> namespaces,
        String diagnosticLabel,
        ResourceSourceDescriptor resource
) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    public SourceDescriptor(
            String sourceId,
            String ownerId,
            Collection<String> namespaces,
            String diagnosticLabel
    ) {
        this(sourceId, ownerId, stableNamespaces(namespaces), diagnosticLabel, null);
    }

    public SourceDescriptor(
            ResourceSourceDescriptor resource,
            Collection<String> namespaces,
            String diagnosticLabel
    ) {
        this(
                resource.sourceId(),
                resource.ownerId(),
                stableNamespaces(namespaces),
                diagnosticLabel,
                resource
        );
    }

    public SourceDescriptor {
        sourceId = requireNonBlank(sourceId, "sourceId");
        ownerId = requireNonBlank(ownerId, "ownerId");
        Objects.requireNonNull(namespaces, "namespaces");
        if (resource != null
                && (!resource.sourceId().equals(sourceId)
                || !resource.ownerId().equals(ownerId))) {
            throw new IllegalArgumentException(
                    "resource descriptor does not match registration source identity");
        }
        if (namespaces.isEmpty()) {
            throw new IllegalArgumentException("a source descriptor must declare at least one namespace");
        }
        for (String namespace : namespaces) {
            if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
                throw new IllegalArgumentException("invalid resource namespace: " + namespace);
            }
        }
        namespaces = Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(namespaces)));
        diagnosticLabel = requireNonBlank(diagnosticLabel, "diagnosticLabel");
    }

    public static SourceDescriptor of(String sourceId, String ownerId, Collection<String> namespaces) {
        return new SourceDescriptor(sourceId, ownerId, namespaces, ownerId + " [" + sourceId + "]");
    }

    public static SourceDescriptor of(
            ResourceSourceDescriptor resource,
            Collection<String> namespaces,
            String diagnosticLabel
    ) {
        return new SourceDescriptor(resource, namespaces, diagnosticLabel);
    }

    public String rootId() {
        return resource == null ? sourceId : resource.rootId();
    }

    public int rank() {
        return resource == null ? 0 : resource.rank();
    }

    public ResourceSourceDescriptor.Origin origin() {
        return resource == null
                ? ResourceSourceDescriptor.Origin.EXTERNAL
                : resource.origin();
    }

    private static Set<String> stableNamespaces(Collection<String> namespaces) {
        Objects.requireNonNull(namespaces, "namespaces");
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(namespaces)));
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
