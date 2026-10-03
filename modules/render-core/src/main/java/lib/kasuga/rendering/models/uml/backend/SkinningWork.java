package lib.kasuga.rendering.models.uml.backend;

/** Cache keys include both the geometry and skeleton; only successful work is published. */
public final class SkinningWork {
    private boolean sourceReady, resultReady;
    private long uploadedSource, skinnedSource, skinnedSkeleton;
    private int skinnedVertices;
    public boolean needsSource(long source) { return !sourceReady || uploadedSource != source; }
    public void sourceUploaded(long source) { uploadedSource = source; sourceReady = true; }
    public boolean needsDispatch(long source, long skeleton, int vertices) {
        return !resultReady || skinnedSource != source || skinnedSkeleton != skeleton || skinnedVertices != vertices;
    }
    public void dispatched(long source, long skeleton, int vertices) {
        skinnedSource = source; skinnedSkeleton = skeleton; skinnedVertices = vertices; resultReady = true;
    }
    public void invalidate() { sourceReady = resultReady = false; }
}
