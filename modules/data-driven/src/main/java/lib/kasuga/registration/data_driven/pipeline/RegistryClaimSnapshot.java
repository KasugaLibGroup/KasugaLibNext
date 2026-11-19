package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.Reg;
import lib.kasuga.registration.RegistryGroup;
import lib.kasuga.registration.minecraft.common.MinecraftDeferRegistryReg;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot of registry entries already attached to a host registration tree.
 */
public final class RegistryClaimSnapshot {
    private RegistryClaimSnapshot() {
    }

    public static Set<RegistryClaim.RegistryKey> collect(Reg<?, ?> root) {
        Objects.requireNonNull(root, "root");
        Set<RegistryClaim.RegistryKey> claims = new LinkedHashSet<>();
        ArrayDeque<Reg<?, ?>> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Reg<?, ?> registration = pending.removeFirst();
            if (registration instanceof RegistryGroup group && !group.isDispatchEnabled()) {
                continue;
            }
            if (registration instanceof MinecraftDeferRegistryReg<?, ?, ?> deferred) {
                claims.add(new RegistryClaim.RegistryKey(
                        deferred.getRegistryKey(),
                        deferred.resolveId()
                ));
            }
            pending.addAll(registration.getChildren());
        }
        return Set.copyOf(claims);
    }
}
