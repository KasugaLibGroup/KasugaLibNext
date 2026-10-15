package lib.kasuga.rendering.output.profile;

import jdk.jfr.*;

@Name("kasuga.CameraRenderPass")
@Label("Camera render pass")
@Category({"Kasuga", "Camera"})
@StackTrace(false)
public final class CameraRenderPassEvent extends Event {
    private static final EventType TYPE = EventType.getEventType(CameraRenderPassEvent.class);
    public String viewId;
    public int width, height, loadedChunks;
    public static CameraRenderPassEvent begin(String viewId, int width, int height) {
        if (!TYPE.isEnabled()) return null;
        var event = new CameraRenderPassEvent();
        event.viewId = viewId; event.width = width; event.height = height; event.begin();
        return event;
    }
    public void finish(int chunks) { loadedChunks = chunks; end(); commit(); }
}
