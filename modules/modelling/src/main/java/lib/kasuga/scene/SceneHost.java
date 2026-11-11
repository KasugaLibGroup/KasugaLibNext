package lib.kasuga.scene;

import org.jetbrains.annotations.Nullable;

/** Client render-thread view of a borrowed moving host. Sampling must never advance its clock.
 * Implementations check the identity of the original host, not only its reusable entity ID.
 */
public interface SceneHost {
    /** Also checked while the attachment is hidden and on client ticks. */
    boolean isAlive();

    /** Interpolated pose matching the host's renderer. Null permanently detaches this attachment. */
    @Nullable ScenePose pose(float partialTick);
}
