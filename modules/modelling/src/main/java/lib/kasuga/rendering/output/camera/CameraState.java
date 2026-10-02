package lib.kasuga.rendering.output.camera;

/** Lifecycle shared by camera render runtimes and their owned output subscriptions. */
public enum CameraState { READY, PAUSED, FAILED, CLOSED }
