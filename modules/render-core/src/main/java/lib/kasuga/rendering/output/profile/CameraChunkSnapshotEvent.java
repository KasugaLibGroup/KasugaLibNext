package lib.kasuga.rendering.output.profile;
import jdk.jfr.*;
@Name("kasuga.CameraChunkSnapshot") @Label("Camera chunk snapshot")
@Category({"Kasuga", "Camera"}) @StackTrace(false)
public final class CameraChunkSnapshotEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(CameraChunkSnapshotEvent.class);
    public int bytes;
    public static CameraChunkSnapshotEvent start() {
        if (!TYPE.isEnabled()) return null;
        var event = new CameraChunkSnapshotEvent(); event.begin(); return event;
    }
    public void finish(int bytes) { this.bytes = bytes; end(); commit(); }
}
