package lib.kasuga.rendering.effect.builtin;

import lib.kasuga.rendering.effect.RenderEffect;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** A configurable camera-facing textured quad with simple client-side motion. */
public final class BillboardEffect implements RenderEffect {
    private final ResourceLocation texture;
    private final int lifetime;
    private final float startSize;
    private final float endSize;
    private final Color startColor;
    private final Color endColor;
    private final double gravity;
    private final double drag;
    private final float startRotation;
    private final float rotationSpeed;

    private Vec3 previousPosition;
    private Vec3 position;
    private Vec3 velocity;
    private int age;

    private BillboardEffect(Builder builder) {
        this.texture = builder.texture;
        this.previousPosition = builder.position;
        this.position = builder.position;
        this.velocity = builder.velocity;
        this.lifetime = builder.lifetime;
        this.startSize = builder.startSize;
        this.endSize = builder.endSize;
        this.startColor = builder.startColor;
        this.endColor = builder.endColor;
        this.gravity = builder.gravity;
        this.drag = builder.drag;
        this.startRotation = builder.startRotation;
        this.rotationSpeed = builder.rotationSpeed;
    }

    public static Builder builder(ResourceLocation texture, Vec3 position) {
        return new Builder(texture, position);
    }

    @Override
    public void tick(ClientLevel level) {
        previousPosition = position;
        position = position.add(velocity);
        velocity = new Vec3(velocity.x * drag, velocity.y * drag - gravity, velocity.z * drag);
        age++;
    }

    @Override
    public boolean isAlive() {
        return age < lifetime;
    }

    @Override
    public Vec3 position(float partialTick) {
        return previousPosition.lerp(position, Mth.clamp(partialTick, 0.0f, 1.0f));
    }

    @Override
    public double distanceToSqr(float partialTick, Vec3 observer) {
        float progress = Mth.clamp(partialTick, 0.0f, 1.0f);
        double deltaX = Mth.lerp(progress, previousPosition.x, position.x) - observer.x;
        double deltaY = Mth.lerp(progress, previousPosition.y, position.y) - observer.y;
        double deltaZ = Mth.lerp(progress, previousPosition.z, position.z) - observer.z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    @Override
    public AABB bounds(float partialTick) {
        Vec3 center = position(partialTick);
        double radius = Math.max(startSize, endSize) * Math.sqrt(2.0) * 0.5;
        return new AABB(
                center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius
        );
    }

    public ResourceLocation texture() {
        return texture;
    }

    public float size(float partialTick) {
        return Mth.lerp(progress(partialTick), startSize, endSize);
    }

    public float rotation(float partialTick) {
        return startRotation + (age + partialTick) * rotationSpeed;
    }

    public Color color(float partialTick) {
        return Color.lerp(startColor, endColor, progress(partialTick));
    }

    public float progress(float partialTick) {
        return Mth.clamp((age + partialTick) / lifetime, 0.0f, 1.0f);
    }

    public int age() {
        return age;
    }

    public int lifetime() {
        return lifetime;
    }

    public record Color(float red, float green, float blue, float alpha) {
        public Color {
            red = Mth.clamp(red, 0.0f, 1.0f);
            green = Mth.clamp(green, 0.0f, 1.0f);
            blue = Mth.clamp(blue, 0.0f, 1.0f);
            alpha = Mth.clamp(alpha, 0.0f, 1.0f);
        }

        public static Color lerp(Color from, Color to, float progress) {
            return new Color(
                    Mth.lerp(progress, from.red, to.red),
                    Mth.lerp(progress, from.green, to.green),
                    Mth.lerp(progress, from.blue, to.blue),
                    Mth.lerp(progress, from.alpha, to.alpha)
            );
        }
    }

    public static final class Builder {
        private final ResourceLocation texture;
        private final Vec3 position;
        private Vec3 velocity = Vec3.ZERO;
        private int lifetime = 20;
        private float startSize = 1.0f;
        private float endSize = 1.0f;
        private Color startColor = new Color(1, 1, 1, 1);
        private Color endColor = new Color(1, 1, 1, 0);
        private double gravity;
        private double drag = 1.0;
        private float startRotation;
        private float rotationSpeed;

        private Builder(ResourceLocation texture, Vec3 position) {
            this.texture = Objects.requireNonNull(texture, "texture");
            this.position = Objects.requireNonNull(position, "position");
        }

        public Builder velocity(Vec3 value) {
            velocity = Objects.requireNonNull(value, "velocity");
            return this;
        }

        public Builder lifetime(int ticks) {
            if (ticks <= 0) throw new IllegalArgumentException("lifetime must be positive");
            lifetime = ticks;
            return this;
        }

        public Builder size(float start, float end) {
            if (start < 0 || end < 0) throw new IllegalArgumentException("billboard size cannot be negative");
            startSize = start;
            endSize = end;
            return this;
        }

        public Builder color(Color start, Color end) {
            startColor = Objects.requireNonNull(start, "startColor");
            endColor = Objects.requireNonNull(end, "endColor");
            return this;
        }

        public Builder motion(double gravity, double drag) {
            if (drag < 0) throw new IllegalArgumentException("drag cannot be negative");
            this.gravity = gravity;
            this.drag = drag;
            return this;
        }

        /** Rotation values are radians and radians per tick. */
        public Builder rotation(float start, float speed) {
            startRotation = start;
            rotationSpeed = speed;
            return this;
        }

        public BillboardEffect build() {
            return new BillboardEffect(this);
        }
    }
}
