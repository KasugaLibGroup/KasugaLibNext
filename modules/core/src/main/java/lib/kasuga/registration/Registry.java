package lib.kasuga.registration;

import lib.kasuga.KasugaLib;
import lib.kasuga.registration.core.RegisterContextRegistry;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;

public final class Registry extends RegistryGroup {

    private final String modId;

    public Registry(String modId){
        this.modId = modId;
        this.configure(ResourceLocationModifiers.withNamespace(modId));
    }

    public String getModId() {
        return modId;
    }

    public void register(IEventBus modEventBus) {
        KasugaLib.afterRunning(modEventBus, ()->{
            KasugaLib.getBean(RegisterContextRegistry.class).configure(RegisterContextRegistry.Side.COMMON, this, modEventBus);
        });
    }

    @OnlyIn(Dist.CLIENT)
    public void registerClient(IEventBus modEventBus) {
        KasugaLib.afterRunning(modEventBus, ()-> {
            KasugaLib.getBean(RegisterContextRegistry.class).configure(RegisterContextRegistry.Side.CLIENT, this, modEventBus);
        });
    }
}
