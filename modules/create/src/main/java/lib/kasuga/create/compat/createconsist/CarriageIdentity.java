package lib.kasuga.create.compat.createconsist;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 由 {@code CarriageDeviceMixin} 注入到 Create 的 {@code Carriage} 上：给车厢一个能写进存档的稳定 id。
 *
 * <p>Create 的车厢 NBT 没有 UUID，编组重映射如果要「按车厢归属」就必须自己带一个。</p>
 *
 * <p><b>缺失就是缺失</b>：读档时存档里没有 {@code KasugaCarriageId} 就是 {@code null} ——
 * 不现场生成随机 UUID，也不按索引 / 位置 / 对象身份合成替代值。调用方拿到 {@code null}
 * 必须按「身份未知」处理（放弃这一轮重映射、设备状态原样保留），而不是补一个 id 上去。</p>
 */
public interface CarriageIdentity {

    /**
     * 车厢身份。唯一的铸造点是 {@code Carriage} 构造期（新对象的身份，等同实体 UUID），
     * 读档只接受存档里写着的值；没有就是 {@code null}。
     */
    @Nullable
    UUID kasuga$carriageId();

    void kasuga$setCarriageId(@Nullable UUID id);
}
