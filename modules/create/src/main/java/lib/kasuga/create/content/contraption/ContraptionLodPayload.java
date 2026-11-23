package lib.kasuga.create.content.contraption;

import io.netty.handler.codec.DecoderException;
import lib.kasuga.KasugaLib;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ContraptionLodPayload(ResourceLocation dimension, UUID entity,
                                   @Nullable Pose pose, @Nullable ContraptionLodMesh mesh) implements CustomPacketPayload {
    public static final Type<ContraptionLodPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "create/contraption_lod"));
    static final StreamCodec<FriendlyByteBuf, ContraptionLodPayload> CODEC =
            StreamCodec.ofMember(ContraptionLodPayload::write, ContraptionLodPayload::read);

    public record Pose(int entityId, Vec3 origin, float x, float y, float z, float w, int light) {
        public Quaternionf rotation() {
            return new Quaternionf(x, y, z, w);
        }
    }

    @Override public Type<ContraptionLodPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(dimension);
        buffer.writeUUID(entity);
        buffer.writeBoolean(pose != null);
        if (pose == null) return;
        buffer.writeVarInt(pose.entityId());
        buffer.writeDouble(pose.origin().x).writeDouble(pose.origin().y).writeDouble(pose.origin().z);
        buffer.writeFloat(pose.x()).writeFloat(pose.y()).writeFloat(pose.z()).writeFloat(pose.w());
        buffer.writeVarInt(pose.light());
        buffer.writeBoolean(mesh != null);
        if (mesh == null) return;
        buffer.writeByte(mesh.scale());
        buffer.writeVarInt(mesh.cells().size());
        for (ContraptionLodMesh.Cell cell : mesh.cells()) {
            buffer.writeBlockPos(cell.position());
            buffer.writeVarInt(cell.state());
        }
    }

    private static ContraptionLodPayload read(FriendlyByteBuf buffer) {
        ResourceLocation dimension = buffer.readResourceLocation();
        UUID entity = buffer.readUUID();
        if (!buffer.readBoolean()) return new ContraptionLodPayload(dimension, entity, null, null);
        int id = buffer.readVarInt();
        Vec3 origin = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        Pose pose = new Pose(id, origin, buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(),
                buffer.readVarInt());
        float norm = pose.rotation().lengthSquared();
        if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)
                || !Float.isFinite(norm) || Math.abs(norm - 1f) > .01f) throw new DecoderException("Invalid contraption lod pose");
        if (!buffer.readBoolean()) return new ContraptionLodPayload(dimension, entity, pose, null);
        int scale = buffer.readUnsignedByte();
        int count = buffer.readVarInt();
        if (scale < 2 || scale > ContraptionLodMesh.MAX_SCALE || Integer.bitCount(scale) != 1
                || count < 1 || count > ContraptionLodMesh.MAX_CELLS) {
            throw new DecoderException("Invalid contraption lod mesh size");
        }
        List<ContraptionLodMesh.Cell> cells = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            BlockPos position = buffer.readBlockPos();
            int state = buffer.readVarInt();
            if (state < 0 || Math.abs(position.getX()) > 2048 || Math.abs(position.getY()) > 2048
                    || Math.abs(position.getZ()) > 2048) throw new DecoderException("Invalid contraption lod cell");
            cells.add(new ContraptionLodMesh.Cell(position, state));
        }
        return new ContraptionLodPayload(dimension, entity, pose, new ContraptionLodMesh(scale, cells));
    }
}
