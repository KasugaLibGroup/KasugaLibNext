package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;

import java.util.Objects;
import java.util.function.Supplier;

/** Immutable camera creation data. Entity and player adapters belong to the host, not render-core. */
public sealed interface CameraSource permits CameraSource.Player, CameraSource.Free, CameraSource.Fixed {
    CameraType type();

    @FunctionalInterface interface ViewProvider { WorldCameraView sample(float partialTick); }

    record Player(ViewProvider provider) implements CameraSource {
        public Player { Objects.requireNonNull(provider); }
        public CameraType type() { return CameraType.PLAYER; }
    }
    record Free(Supplier<WorldCameraView> provider) implements CameraSource {
        public Free { Objects.requireNonNull(provider); }
        public CameraType type() { return CameraType.FREE; }
    }
    record Fixed(CameraTarget target, CameraProjection projection, CameraFollowSettings follow) implements CameraSource {
        public Fixed { Objects.requireNonNull(target); Objects.requireNonNull(projection); Objects.requireNonNull(follow); }
        public CameraType type() { return CameraType.FIXED; }
    }
}
