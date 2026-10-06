package dev.kessoku.homelander;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FlightController {
    public enum Mode { HOVER, GLIDE }
    private static final Map<UUID, Mode> MODES = new ConcurrentHashMap<>();
    private FlightController() {}

    public static void grant(ServerPlayerEntity player) {
        MODES.putIfAbsent(player.getUuid(), Mode.HOVER);
        apply(player);
    }

    public static Mode mode(ServerPlayerEntity player) {
        return MODES.getOrDefault(player.getUuid(), Mode.HOVER);
    }

    public static void setMode(ServerPlayerEntity player, Mode mode) {
        MODES.put(player.getUuid(), mode);
        apply(player);
    }

    public static Mode toggleMode(ServerPlayerEntity player) {
        Mode next = mode(player) == Mode.HOVER ? Mode.GLIDE : Mode.HOVER;
        setMode(player, next);
        return next;
    }

    private static void apply(ServerPlayerEntity player) {
        if (!player.hasStatusEffect(HomelanderMod.COMPOUND_V) || player.isCreative() || player.isSpectator()) return;
        boolean hover = mode(player) == Mode.HOVER;
        player.getAbilities().allowFlying = hover;
        if (!hover) player.getAbilities().flying = false;
        player.sendAbilitiesUpdate();
    }

    public static void tick(ServerPlayerEntity player) {
        if (!player.hasStatusEffect(HomelanderMod.COMPOUND_V)) {
            if (MODES.remove(player.getUuid()) != null && !player.isCreative() && !player.isSpectator()) {
                player.getAbilities().flying = false;
                player.getAbilities().allowFlying = false;
                player.sendAbilitiesUpdate();
            }
            return;
        }
        if (player.isCreative() || player.isSpectator()) return;

        if (mode(player) == Mode.HOVER) {
            if (!player.getAbilities().allowFlying) {
                player.getAbilities().allowFlying = true;
                player.sendAbilitiesUpdate();
            }
            return;
        }

        // Look direction steers the glide; holding sprint adds thrust and gravity stays gentle.
        if (!player.isOnGround() && !player.isTouchingWater() && !player.hasVehicle()) {
            Vec3d velocity = player.getVelocity();
            Vec3d look = player.getRotationVec(1.0f).normalize();
            Vec3d next = velocity.add(look.multiply(player.isSprinting() ? 0.075 : 0.035)).add(0, -0.018, 0);
            double max = player.isSprinting() ? 1.35 : 0.95;
            if (next.lengthSquared() > max * max) next = next.normalize().multiply(max);
            player.setVelocity(next);
            player.fallDistance = 0;
        }
    }
}
