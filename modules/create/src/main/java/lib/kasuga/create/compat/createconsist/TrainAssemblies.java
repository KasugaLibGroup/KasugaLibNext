package lib.kasuga.create.compat.createconsist;

import com.simibubi.create.content.trains.entity.Train;

/** {@link TrainAssembly} 的取用入口，避免各调用点自己 new。 */
public final class TrainAssemblies {

    private TrainAssemblies() {
    }

    public static TrainAssembly get() {
        return CreateTrainAssembly.INSTANCE;
    }

    public static java.util.List<java.util.UUID> carriageIds(Train train) {
        return get().carriageIds(train);
    }
}
