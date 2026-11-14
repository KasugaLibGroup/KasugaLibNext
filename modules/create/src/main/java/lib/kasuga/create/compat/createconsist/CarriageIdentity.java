package lib.kasuga.create.compat.createconsist;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 由 {@code CarriageMixin} 注入到 Create 的 {@code Carriage} 上：给车厢一个能写进存档的稳定 id。
 *
 * <p>Create 的车厢 NBT 没有 UUID，编组重映射如果要「按车厢归属」就必须自己带一个。</p>
 */
public interface CarriageIdentity {

    /** 懒生成：没有读过存档时生成一个随机 id。 */
    UUID kasuga$carriageId();

    void kasuga$setCarriageId(@Nullable UUID id);
}
