package lib.kasuga.registration.minecraft.common;

import com.mojang.logging.LogUtils;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import lib.kasuga.registration.stages.RegistrationStage;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public abstract class MinecraftDeferRegistryReg<S extends Reg<S, T>, R, T extends R> extends Reg<S, T> {
    private static Logger LOGGER = LogUtils.getLogger();
    private final String name;
    private final ResourceKey<Registry<R>> entryKey;
    protected T value;

    protected Holder<R> holder = new DeferredFillHolder<>();
    private ResourceLocation resourceLocation;
    private final List<Consumer<? super T>> registeredCallbacks = new ArrayList<>();

    protected MinecraftDeferRegistryReg(String name, ResourceKey<Registry<R>> entryKey) {
        this.name = name;
        this.entryKey = entryKey;
    }

    @Override
    public void register(RegisterContext<?> context) {
        context.onStage(RegistrationStage.REGISTER_EVENT, (ctx)-> {
            if (ctx.getRegistryKey() != entryKey) return;

            ResourceLocation id = resolveId();

            this.resourceLocation = id;

            LOGGER.info("Posting deferred registry entry: {} to {}", id, entryKey.location());
            value = this.createObject(id);
            Holder<R> localHolder = DeferredHolder.create(entryKey, id);
            if(this.holder instanceof DeferredFillHolder<R> dfh) {
                dfh.setValue(localHolder);
            }
            this.holder = localHolder;
            ctx.register(entryKey, id, ()->value);
            registeredCallbacks.forEach(callback -> callback.accept(value));
        });
    }

    public S onRegistered(Consumer<? super T> callback) {
        registeredCallbacks.add(callback);
        return self();
    }

    protected abstract T createObject(ResourceLocation id);

    @Override
    public T getEntry() {
        if(this.value == null) {
            throw new IllegalStateException("Registry item not present:" + name);
        }
        return this.value;
    }

    public String getName() {
        return name;
    }

    public Holder<R> getHolder() {
        return holder;
    }

    public ResourceLocation getResourceLocation() {
        return resourceLocation;
    }

    public ResourceLocation resolveId() {
        ResourceLocation id = transform(
                ResourceLocationModifiers.ID,
                ResourceLocation.fromNamespaceAndPath("minecraft", name)
        );
        return applyProperties(ResourceLocation.class, id);
    }
}
