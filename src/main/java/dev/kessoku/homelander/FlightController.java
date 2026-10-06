package dev.kessoku.homelander;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FlightController {
    public enum Mode { HOVER, GLIDE }
    private static final Map<UUID, State> STATES = new HashMap<>();
    private FlightController() {}

    private static final class State {
        Mode mode = Mode.HOVER;
        final boolean originalAllowFlying;
        final boolean originalFlying;
        boolean startedGliding;
        boolean brakeArmed;
        int glideTicks;
        int slowTicks;
        Vec3d lastPosition;

        State(ServerPlayerEntity player) {
            originalAllowFlying = !player.isCreative() && !player.isSpectator()
                    && player.getAbilities().allowFlying;
            originalFlying = originalAllowFlying && player.getAbilities().flying;
            lastPosition = player.getPos();
        }
    }

    public static void grant(ServerPlayerEntity player) {
        State state = STATES.computeIfAbsent(player.getUuid(), id -> new State(player));
        if (state.mode == Mode.GLIDE) enterHover(player, state, canGlide(player));
        else applyHover(player, false);
    }

    public static Mode mode(ServerPlayerEntity player) {
        State state = STATES.get(player.getUuid());
        return state == null ? Mode.HOVER : state.mode;
    }

    // Fabric's custom-elytra callback keeps vanilla fall-flying active without
    // replacing the player's chest armour. The unmodified client runs elytra physics.
    public static boolean allowsElytra(LivingEntity entity, boolean tickElytra) {
        return entity instanceof ServerPlayerEntity player
                && player.hasStatusEffect(HomelanderMod.COMPOUND_V)
                && !player.isCreative() && !player.isSpectator()
                && mode(player) == Mode.GLIDE;
    }

    public static Mode toggleMode(ServerPlayerEntity player) {
        State state = STATES.computeIfAbsent(player.getUuid(), id -> new State(player));
        if (state.mode == Mode.GLIDE) {
            enterHover(player, state, canGlide(player));
        } else {
            state.mode = Mode.GLIDE;
            state.startedGliding = false;
            state.brakeArmed = !player.isSneaking();
            state.glideTicks = 0;
            state.slowTicks = 0;
            state.lastPosition = player.getPos();
            if (!player.isCreative() && !player.isSpectator()) {
                player.getAbilities().flying = false;
                player.getAbilities().allowFlying = false;
                player.sendAbilitiesUpdate();
                if (canGlide(player)) startGliding(player, state);
            }
        }
        return state.mode;
    }

    private static boolean canGlide(ServerPlayerEntity player) {
        return !player.isOnGround() && !player.isTouchingWater() && !player.isInLava()
                && !player.hasVehicle() && !player.isClimbing()
                && !player.hasStatusEffect(StatusEffects.LEVITATION);
    }

    private static void startGliding(ServerPlayerEntity player, State state) {
        player.startFallFlying();
        state.startedGliding = true;
        state.lastPosition = player.getPos();
        // Entering from a stationary hover needs an initial forward impulse. After
        // this, vanilla client-side elytra physics controls motion, lift and steering.
        player.setVelocity(player.getRotationVec(1.0f).multiply(0.9));
        player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
        player.fallDistance = 0;
    }

    private static void applyHover(ServerPlayerEntity player, boolean flyNow) {
        if (player.isCreative() || player.isSpectator()) return;
        player.getAbilities().allowFlying = true;
        if (flyNow) player.getAbilities().flying = true;
        player.sendAbilitiesUpdate();
    }

    private static void enterHover(ServerPlayerEntity player, State state, boolean flyNow) {
        state.mode = Mode.HOVER;
        state.startedGliding = false;
        state.slowTicks = 0;
        player.stopFallFlying();
        if (!player.isCreative() && !player.isSpectator()) {
            player.setVelocity(Vec3d.ZERO);
            player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
            player.getAbilities().flying = flyNow;
            applyHover(player, flyNow);
            player.fallDistance = 0;
        }
    }

    private static void automaticHover(ServerPlayerEntity player, State state, boolean flyNow) {
        enterHover(player, state, flyNow);
        player.sendMessage(Text.literal("Полёт: зависание"), true);
    }

    public static void release(ServerPlayerEntity player) {
        State state = STATES.remove(player.getUuid());
        if (state == null || player.isCreative() || player.isSpectator()) return;
        if (state.mode == Mode.GLIDE) player.stopFallFlying();
        player.getAbilities().allowFlying = state.originalAllowFlying;
        player.getAbilities().flying = state.originalFlying && state.originalAllowFlying;
        player.sendAbilitiesUpdate();
    }

    public static void forget(ServerPlayerEntity player) {
        STATES.remove(player.getUuid());
    }

    public static void tick(ServerPlayerEntity player) {
        if (!player.isAlive() || !player.hasStatusEffect(HomelanderMod.COMPOUND_V)) {
            release(player);
            return;
        }
        State state = STATES.get(player.getUuid());
        if (state == null) {
            grant(player);
            state = STATES.get(player.getUuid());
        }
        if (player.isCreative() || player.isSpectator()) return;
        if (state.mode == Mode.HOVER) {
            if (!player.getAbilities().allowFlying) applyHover(player, false);
            return;
        }

        // Shift+use selects the glide; wait for Shift to be released before treating
        // another Shift press as a brake, otherwise the glide would end immediately.
        if (!player.isSneaking()) state.brakeArmed = true;
        if (!canGlide(player)) {
            if (state.startedGliding) automaticHover(player, state, false);
            return;
        }
        if (!state.startedGliding) {
            startGliding(player, state);
            return;
        }
        state.glideTicks++;
        double movement = player.getPos().distanceTo(state.lastPosition);
        state.lastPosition = player.getPos();
        state.slowTicks = movement < 0.06 ? state.slowTicks + 1 : 0;
        if ((state.brakeArmed && player.isSneaking()) || player.horizontalCollision
                || !player.isFallFlying() || (state.glideTicks > 20 && state.slowTicks >= 10)) {
            automaticHover(player, state, true);
        }
        player.fallDistance = 0;
    }
}
