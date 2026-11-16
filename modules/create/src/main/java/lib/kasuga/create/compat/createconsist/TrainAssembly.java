package lib.kasuga.create.compat.createconsist;

import com.simibubi.create.content.trains.entity.Train;

import java.util.List;
import java.util.UUID;

/**
 * 编组信息的访问门面。设备的编组逻辑只依赖这个接口，Create 内部类型只出现在
 * {@link CreateTrainAssembly} 里 —— Create 升级时只需要改这一个实现。
 */
public interface TrainAssembly {

    /**
     * 当前编组的车厢稳定 id，顺序即 carriageIndex 顺序。
     *
     * <p>缺失的车厢身份是 {@code null} 元素（长度与车厢数一致），不会合成替代值。
     * 调用方必须先检查 {@code null}：有缺失就说明「编组身份不可用」，应当放弃重映射，
     * 而不是猜一个映射出来。</p>
     */
    List<UUID> carriageIds(Train train);
}
