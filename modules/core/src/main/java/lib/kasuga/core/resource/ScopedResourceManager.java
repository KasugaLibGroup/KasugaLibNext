package lib.kasuga.core.resource;

import lib.kasuga.core.holder.Holder;
import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.core.resource.pack.ScopedVanillaFileResourcePack;
import lib.kasuga.core.resource.pack.ScopedVanillaPathResourcePack;
import lib.kasuga.mixins.*;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ScopedResourceManager implements ResourceManagerReloadListener {
    private ResourceManager resourceManager;
    private List<ScopedPackResources> packResources = new ArrayList<>();
    private List<ScopedResourcePackListener> listeners = new ArrayList<>();

    public ScopedResourceManager(ResourceManager resourceManager) {
        reload(resourceManager);
    }

    public void reload(ResourceManager rm) {
        this.resourceManager = rm;
        packResources.clear();
        Iterator<PackResources> vanillaPacks  = rm.listPacks().iterator();
        while(vanillaPacks.hasNext()) {
            adapt(vanillaPacks.next());
        }
        for (ScopedResourcePackListener listener : listeners) {
            listener.onReloaded(this);
        }
    }

    /** The vanilla resource manager this scoped manager wraps. */
    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    @Override
    public void onResourceManagerReload(@NotNull ResourceManager rm) {
        this.reload(rm);
    }

    private void adapt(PackResources next) {
        if(next instanceof CompositePackResources composite) {
            adapt(((CompositePackResourcesAccess) composite).getPrimaryPackResources());
            for (PackResources resources : ((CompositePackResourcesAccess) composite).getPackResourcesStack()) {
                adapt(resources);
            }
        } else if(next instanceof PathPackResources path) {
            packResources.add(new ScopedVanillaPathResourcePack(((PathPackResourcesMixin) path).getRoot(), path));
        } else if(next instanceof FilePackResources file) {
            packResources.add(new ScopedVanillaFileResourcePack(((SharedZipFileAccessAccess)((FilePackResourcesMixin)file).getZipFileAccess()).invokeGetOrCreateZipFile()));
        }
    }

    public List<ScopedPackResources> getPackResources() {
        return packResources;
    }

    public void addListener(ScopedResourcePackListener listener) {
        listeners.add(listener);
        listener.onReloaded(this);
    }
}
