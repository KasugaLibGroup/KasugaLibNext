package lib.kasuga.create.registration.bogey;

import com.simibubi.create.content.trains.bogey.BogeySizes;
import lib.kasuga.create.registration.CreateRegistrationEvent;
import lib.kasuga.create.registration.CreateRegistrationStages;
import lib.kasuga.registration.Reg;
import lib.kasuga.registration.core.RegisterContext;
import lib.kasuga.registration.core.ResourceLocationModifiers;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public final class BogeySizeReg extends Reg<BogeySizeReg, BogeySizes.BogeySize> {
    private final String name;
    private float wheelRadius;
    private BogeySizes.BogeySize delegate;

    public BogeySizeReg(String name) {
        this(name, 1.0f);
    }

    public BogeySizeReg(String name, float wheelRadius) {
        this.name = Objects.requireNonNull(name, "name");
        this.wheelRadius = validateRadius(wheelRadius);
    }

    public BogeySizeReg size(float wheelRadius) {
        this.wheelRadius = validateRadius(wheelRadius);
        return this;
    }

    @Override
    public void register(RegisterContext<?> context) {
        context.onStage(CreateRegistrationStages.PREPARE, event -> {
            ResourceLocation id = resolveId();
            event.submit(
                    CreateRegistrationEvent.Phase.BOGEY_SIZE,
                    id,
                    this,
                    () -> validate(id),
                    () -> commit(id)
            );
        });
    }

    private void validate(ResourceLocation id) {
        if (delegate != null) {
            throw new IllegalStateException("Bogey size already committed: " + id);
        }
        if (BogeySizes.all().containsKey(id)) {
            throw new IllegalStateException("Bogey size ID is already registered: " + id);
        }
    }

    private void commit(ResourceLocation id) {
        BogeySizes.BogeySize size = new BogeySizes.BogeySize(id, wheelRadius);
        BogeySizes.register(size);
        BogeyMetadataIndex.put(size, new BogeyMetadataIndex.SizeMetadata(id, wheelRadius));
        delegate = size;
    }

    ResourceLocation resolveId() {
        ResourceLocation id = transform(
                ResourceLocationModifiers.ID,
                ResourceLocation.withDefaultNamespace(name)
        );
        return applyProperties(ResourceLocation.class, id);
    }

    private static float validateRadius(float radius) {
        if (!Float.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Bogey wheel radius must be finite and positive");
        }
        return radius;
    }

    @Override
    public BogeySizes.BogeySize getEntry() {
        if (delegate == null) {
            throw new IllegalStateException("Bogey size has not been committed: " + name);
        }
        return delegate;
    }

    public String getName() {
        return name;
    }

    boolean isCommitted() {
        return delegate != null;
    }
}
