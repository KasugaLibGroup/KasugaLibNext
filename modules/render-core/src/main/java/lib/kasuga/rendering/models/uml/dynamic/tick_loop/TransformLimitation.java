package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class TransformLimitation {
    private LimitComponent offsetX = LimitComponent.free();
    private LimitComponent offsetY = LimitComponent.free();
    private LimitComponent offsetZ = LimitComponent.free();
    private LimitComponent rotationX = LimitComponent.rotation();
    private LimitComponent rotationY = LimitComponent.rotation();
    private LimitComponent rotationZ = LimitComponent.rotation();
    private LimitComponent scaleX = LimitComponent.free();
    private LimitComponent scaleY = LimitComponent.free();
    private LimitComponent scaleZ = LimitComponent.free();

    @Setter
    @Getter
    public static final class LimitComponent {
        private float min;
        private float max;
        private float lockValue;
        private boolean locked;

        private LimitComponent(float lock) {
            this.lock(lock);
        }

        public LimitComponent(float min, float max) {
            this.range(min, max);
        }

        public static LimitComponent lockOn(float lock) {
            return new LimitComponent(lock);
        }

        public static LimitComponent positive() {
            return new LimitComponent(0.0000000001F, Float.POSITIVE_INFINITY);
        }

        public static LimitComponent rotation() {
            return new LimitComponent(-3.1415926535897932614F, 3.1415926535897932614F);
        }

        public static LimitComponent free() {
            return new LimitComponent(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
        }

        public void range(float min, float max) {
            this.min = min;
            this.max = max;
            this.locked = false;
        }

        public void lock(float lockValue) {
            this.lockValue = lockValue;
            this.locked = true;
        }

        public float process(float value) {
            if (this.locked) {
                return this.lockValue;
            }

            return Math.clamp(value, this.min, this.max);
        }
    }
}
