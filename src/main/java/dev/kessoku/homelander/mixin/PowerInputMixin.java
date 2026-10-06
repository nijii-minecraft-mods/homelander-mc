package dev.kessoku.homelander.mixin;

import dev.kessoku.homelander.FlightController;
import dev.kessoku.homelander.LaserController;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class PowerInputMixin {
    @Shadow public ServerPlayerEntity player;

    // TAIL runs after vanilla's main-thread check and validation, never on the
    // network IO thread and never with an unvalidated client position/rotation.
    @Inject(method = "onPlayerMove", at = @At("TAIL"))
    private void homelander$move(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (packet.changesPosition()) FlightController.observeMovement(player);
        if (packet.changesLook() || packet.changesPosition()) LaserController.onViewChanged(player);
    }

    @Inject(method = "onHandSwing", at = @At("TAIL"))
    private void homelander$swing(HandSwingC2SPacket packet, CallbackInfo ci) {
        FlightController.boostFromController(player, packet.getHand());
    }
}
