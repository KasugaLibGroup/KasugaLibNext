package lib.kasuga.rendering.models.mc.dynamic.collision;

import io.netty.handler.codec.DecoderException;
import lib.kasuga.KasugaLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

record ModelCollisionPayload(ResourceLocation dimension, long chunk, List<AABB> boxes) implements CustomPacketPayload {
    static final Type<ModelCollisionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "model_collision/chunk"));
    static final StreamCodec<FriendlyByteBuf, ModelCollisionPayload> CODEC = StreamCodec.ofMember(
            ModelCollisionPayload::write, ModelCollisionPayload::read);

    @Override public Type<ModelCollisionPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(dimension);
        buffer.writeLong(chunk);
        buffer.writeVarInt(boxes.size());
        for (AABB box : boxes) {
            buffer.writeDouble(box.minX).writeDouble(box.minY).writeDouble(box.minZ);
            buffer.writeDouble(box.maxX).writeDouble(box.maxY).writeDouble(box.maxZ);
        }
    }

    private static ModelCollisionPayload read(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        long chunk = buffer.readLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > ModelCollisionIndex.MAX_BOXES_PER_CHUNK || count > buffer.readableBytes() / 48) {
            throw new DecoderException("Invalid model collision box count " + count);
        }
        List<AABB> boxes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            AABB box = new AABB(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                    || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)) {
                throw new DecoderException("Non-finite model collision box");
            }
            boxes.add(box);
        }
        return new ModelCollisionPayload(dimension, chunk, List.copyOf(boxes));
    }
}
