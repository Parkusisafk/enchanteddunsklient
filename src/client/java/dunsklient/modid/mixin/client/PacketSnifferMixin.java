package dunsklient.modid.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.network.ClientConnection.class)
public class PacketSnifferMixin {
    @Inject(
            method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onSend(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (packet instanceof PlayerActionC2SPacket p) {
            if (p.getAction() == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK) {
                // Only cancel the rogue ones — those fired on the same tick as a START
                MinecraftClient mc = MinecraftClient.getInstance();
                int tick = mc.player != null ? mc.player.age : -1;
                if (tick == lastStartDestroyTick) {
                    ci.cancel();
                    return;
                }
            }
            if (p.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
                MinecraftClient mc = MinecraftClient.getInstance();
                lastStartDestroyTick = mc.player != null ? mc.player.age : -1;
            }
        }
    }

    private int lastStartDestroyTick = -1;
}