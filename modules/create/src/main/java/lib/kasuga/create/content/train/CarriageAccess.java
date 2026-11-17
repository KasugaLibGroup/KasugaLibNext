package lib.kasuga.create.content.train;

import com.simibubi.create.content.trains.entity.CarriageBogey;
import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.List;

/**
 * Access to the extra interior bogeys and their carriage-relative positions stored on a
 * {@code Carriage} by {@code CarriageMixin}.
 *
 * <p>Declared outside the mixin package because Mixin forbids referencing classes in a package
 * owned by its config; the mixin implements it and ordinary code casts the target to it. The two
 * lists are index-aligned, use the carriage-local block frame of {@code Contraption#toLocalPos},
 * and are empty for a normal one/two-bogey carriage. They are filled during server assembly,
 * restored by {@code CarriageMixin#write}/{@code read}, and replaced wholesale by
 * {@code CarriageExtraBogeySync} on the client.
 */
public interface CarriageAccess {

    /** Appends interior bogeys to the live list; does not clear it first. */
    void kasuga$addRedundantBogeys(Collection<CarriageBogey> bogeys);

    /** The registered interior bogeys, in track order. Live and mutable. */
    List<CarriageBogey> kasuga$getRedundantBogeys();

    /** Appends the matching carriage-local positions to the live list. */
    void kasuga$addRedundantBogeyPositions(Collection<BlockPos> positions);

    /** The registered interior bogey positions, in the carriage-local block frame. */
    List<BlockPos> kasuga$getRedundantBogeyPositions();
}
