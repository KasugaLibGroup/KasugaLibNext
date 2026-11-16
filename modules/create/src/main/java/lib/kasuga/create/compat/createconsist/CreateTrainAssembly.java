package lib.kasuga.create.compat.createconsist;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Create 侧的编组访问实现。这里是<b>唯一</b>直接碰 {@code Carriage} 的地方；
 * Create 改内部结构时只需要改这个类。
 *
 * <p>身份缺失（{@code CarriageIdentity} 没挂上，或车厢没有 id）时这里返回 {@code null} 元素，
 * <b>不合成替代 UUID</b>：调用方必须按「身份未知」处理。</p>
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
        for (Carriage carriage : train.carriages) {
            ids.add(carriage instanceof CarriageIdentity identity ? identity.kasuga$carriageId() : null);
        }
        return ids;
    }
}
