package lib.kasuga.create.compat.createconsist;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Create 侧的编组访问实现。这里是<b>唯一</b>直接碰 {@code Carriage} 的地方；
 * Create 改内部结构时只需要改这个类。
 */
public final class CreateTrainAssembly implements TrainAssembly {

    public static final CreateTrainAssembly INSTANCE = new CreateTrainAssembly();

    private CreateTrainAssembly() {
    }

    @Override
    public List<UUID> carriageIds(Train train) {
        if (train == null || train.carriages == null || train.carriages.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(train.carriages.size());
        int index = 0;
        for (Carriage carriage : train.carriages) {
            ids.add(carriageId(carriage, index++));
        }
        return ids;
    }

    private static UUID carriageId(Carriage carriage, int index) {
        if (carriage instanceof CarriageIdentity identity) {
            return identity.kasuga$carriageId();
        }
        // mixin 没挂上（极少见）时退化成会话内稳定的伪 UUID，至少不会把不同车厢混成一个。
        return UUID.nameUUIDFromBytes(("kasuga-carriage:" + index + ':' + System.identityHashCode(carriage))
                .getBytes(StandardCharsets.UTF_8));
    }
}
