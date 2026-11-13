package lib.kasuga.core.resource.pack;

import lib.kasuga.core.vfs.VirtualFileSystem;
import net.minecraft.server.packs.PackResources;

/**
 * Marker and accessor interface for {@link PackResources} implementations backed by a
 * {@link VirtualFileSystem}, allowing {@link lib.kasuga.core.resource.ScopedResourceManager}
 * to adapt them directly as {@link VfsScopedPackResourcesAdapter} regardless of class hierarchy.
 */
public interface VfsBackedPackResources extends PackResources {
    VirtualFileSystem getVfs();

    default VfsScopedPackResourcesAdapter asScopedPackResources() {
        return new VfsScopedPackResourcesAdapter(getVfs());
    }
}
