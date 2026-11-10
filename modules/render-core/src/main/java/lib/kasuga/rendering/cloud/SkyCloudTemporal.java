package lib.kasuga.rendering.cloud;

/** Temporal accumulation is opt-in by view identity. Anonymous views always use
 * the independent spatial path; weight zero also disables all history work. */
public record SkyCloudTemporal(float historyWeight, float maxTimeGap, float maxCameraMovement, float relativeDepthTolerance) {
    public static final SkyCloudTemporal STABLE = new SkyCloudTemporal(.65f, .2f, 16, .05f);
    public static final SkyCloudTemporal OFF = new SkyCloudTemporal(0, .2f, 16, .05f);
    public SkyCloudTemporal {
        for (float v : new float[]{historyWeight, maxTimeGap, maxCameraMovement, relativeDepthTolerance})
            if (!Float.isFinite(v)) throw new IllegalArgumentException("Cloud history controls must be finite");
        if (historyWeight < 0 || historyWeight > .95f || maxTimeGap < .01f || maxTimeGap > 1
                || maxCameraMovement <= 0 || maxCameraMovement > 256
                || relativeDepthTolerance <= 0 || relativeDepthTolerance > .25f)
            throw new IllegalArgumentException("Cloud history controls outside supported range");
    }
}
