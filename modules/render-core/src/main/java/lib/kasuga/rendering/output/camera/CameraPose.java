package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.WorldCameraView;

import java.util.Map;

/** Immutable camera-channel sample. Missing channels keep the live provider's pose and output dimensions. */
public record CameraPose(Map<CameraAnimationClip.Channel, Double> channels) {
    public CameraPose { channels = Map.copyOf(channels); }

    public WorldCameraView apply(WorldCameraView base) {
        double x = base.x(), y = base.y(), z = base.z(), zoom = 1;
        float yaw = base.yaw(), pitch = base.pitch(), roll = base.roll();
        for (var entry : channels.entrySet()) {
            double value = entry.getValue();
            switch (entry.getKey()) {
                case X -> x = value;
                case Y -> y = value;
                case Z -> z = value;
                case YAW -> yaw = (float) value;
                case PITCH -> pitch = (float) value;
                case ROLL -> roll = (float) value;
                case ZOOM -> zoom = value;
            }
        }
        var view = new WorldCameraView(x, y, z, yaw, pitch, roll, base.verticalFov(), base.width(), base.height());
        return zoom == 1 ? view : view.zoom(zoom);
    }
}
