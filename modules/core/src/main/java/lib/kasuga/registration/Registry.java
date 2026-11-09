package lib.kasuga.registration;

import lib.kasuga.KasugaLib;
import lib.kasuga.registration.core.RegisterContextRegistry;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class Registry extends RegistryGroup {

    private final String modId;
    private final Map<Class<?>, Object> attachments = new ConcurrentHashMap<>();

    public Registry(String modId){
        this.modId = modId;
        this.configure(ResourceLocationModifiers.withNamespace(modId));
    }

    public String getModId() {
        return modId;
    }

    public <T> T getOrCreateAttachment(Class<T> type, Supplier<? extends T> factory) {
        return type.cast(attachments.computeIfAbsent(type, ignored -> factory.get()));
    }

    public <T> Optional<T> getAttachment(Class<T> type) {
        return Optional.ofNullable(type.cast(attachments.get(type)));
    }

    public <T> void setAttachment(Class<T> type, T value) {
        attachments.put(type, type.cast(value));
    }

    public void removeAttachment(Class<?> type) {
        attachments.remove(type);
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
