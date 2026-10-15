package lib.kasuga.rendering.output.mc.stream;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
/** Only entity state is routed through the real connection's handlers. No UI, player control or world-switch packets. */
final class CameraEntityPackets {
    static boolean allowed(Packet<?> packet) {
        return packet instanceof ClientboundAddEntityPacket || packet instanceof ClientboundAddExperienceOrbPacket
                || packet instanceof ClientboundRemoveEntitiesPacket || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundSetEntityDataPacket || packet instanceof ClientboundSetEntityMotionPacket
                || packet instanceof ClientboundSetEquipmentPacket || packet instanceof ClientboundUpdateAttributesPacket
                || packet instanceof ClientboundRotateHeadPacket || packet instanceof ClientboundSetPassengersPacket
                || packet instanceof ClientboundSetEntityLinkPacket;
    }
}
