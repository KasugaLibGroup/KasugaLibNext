package lib.kasuga.create.content.train;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Read access to the multi-bogey bookkeeping added to {@code CarriageContraption} by
 * {@code CarriageContraptionMixin}.
 *
 * <p>Declared outside the mixin package because Mixin forbids referencing classes in a package
 * owned by its config. The data is only meaningful while {@link #kasuga$isMoreThan2Bogeys()} is
 * true, and instances are single-assembly: the station reads them right after {@code assemble}
 * returns.
 */
public interface CarriageContraptionAccess {

    /** Whether the contraption captured more than two bogey blocks. */
    boolean kasuga$isMoreThan2Bogeys();

    /**
     * Captured bogeys other than the leading and the last captured one, in raw BFS capture order
     * (not necessarily track order). The last captured bogey stays in {@code secondBogeyPos};
     * callers must combine and validate the set before use. Live and mutable.
     */
    List<BlockPos> kasuga$getRedundantBogeys();
}
