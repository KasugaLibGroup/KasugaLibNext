package lib.kasuga.core.resource.platform;

import lib.kasuga.core.resource.ResourceSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceScopeOwnerTest {
    private static MinecraftServer serverIdentity() throws Exception {
        // Identity-only owner keys, matching the existing VFS scope regression. No world/server is started.
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (MinecraftServer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(DedicatedServer.class);
    }
    @Test void serverRetirementPreservesOtherOwnersAndProcessShutdownClosesAll() throws Exception {
        var system = new ResourceSystem();
        var server1 = serverIdentity(); var server2 = serverIdentity();
        var first = system.createServerResourceScope(server1, "server-one");
        var second = system.createServerResourceScope(server2, "server-two");
        var client = system.createClientResourceScope("client");
        var process = system.createProcessResourceScope("process");
        try {
            system.closeManagedResources(server1);
            assertTrue(first.isClosed()); assertFalse(second.isClosed());
            assertFalse(client.isClosed()); assertFalse(process.isClosed());
            system.close();
            assertTrue(second.isClosed()); assertTrue(client.isClosed()); assertTrue(process.isClosed());
            assertThrows(IllegalStateException.class, () -> system.createProcessResourceScope("late"));
        } finally { system.close(); }
    }
}
