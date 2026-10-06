package dev.kessoku.homelander;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.text.Text;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.util.math.Vec3d;


import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LaserController {
    private static final double RANGE = 48;
    private static final Map<UUID, BeamPair> ACTIVE = new HashMap<>();
    private static final Map<UUID, LaserBurst> BURSTS = new HashMap<>();
    private static long clock;
    private LaserController() {}

    public static void advanceClock() {
        clock++;
        if (clock % 20 == 0) BURSTS.entrySet().removeIf(entry ->
                !ACTIVE.containsKey(entry.getKey()) && entry.getValue().cooldown(clock) == 0);
    }

    // Vanilla display entities render on ordinary clients. This approximates a
    // beacon's solid core and coloured sheath; no client renderer/resource pack.
    private static final class Beam extends DisplayEntity.BlockDisplayEntity {
        final float width;
        net.minecraft.util.math.AffineTransformation frame;
        net.minecraft.util.math.AffineTransformation sentFrame;

        Beam(ServerWorld world, BlockState block, float width) {
            super(EntityType.BLOCK_DISPLAY, world);
            this.width = width;
            setBlockState(block);
            setBrightness(new Brightness(15, 15));
            setInterpolationDuration(0);
            setTeleportDuration(0);
            setViewRange(2);
            setShadowRadius(0);
            // A zero culling size makes a long beam visible even when its source
            // is just outside the camera. Entity tracking still limits its range.
            setDisplayWidth(0);
            setDisplayHeight(0);
            setInvulnerable(true);
            setNoGravity(true);
        }

        @Override
        public boolean shouldSave() { return false; }

        void update(Vec3d start, Vec3d end) {
            float axialAngle = (float) ((getWorld().getTime() * 0.02) % (Math.PI * 2));
            var frame = LaserGeometry.frame(start, end, width, axialAngle);
            // The client mounts this display on the player, so translation must be
            // relative to the same vanilla riding attachment, never a stale world anchor.
            var owner = getVehicle();
            Vec3d anchor = owner == null ? getPos()
                    : owner.getPassengerRidingPos(this).subtract(getVehicleAttachmentPos(owner));
            Vec3d relative = start.subtract(anchor);
            var translation = new org.joml.Vector3f(frame.getTranslation())
                    .add((float) relative.x, (float) relative.y, (float) relative.z);
            this.frame = new net.minecraft.util.math.AffineTransformation(translation,
                    frame.getLeftRotation(), frame.getScale(), frame.getRightRotation());
            setTransformation(this.frame);
        }

        void sendNow() {
            if (frame == null || frame.equals(sentFrame)) return;
            var entries = getDataTracker().getChangedEntries();
            if (entries == null || entries.isEmpty()) return;
            var packet = new EntityTrackerUpdateS2CPacket(getId(), entries);
            // Do not consume dirty entries: normal tracking still initializes
            // newly arriving observers and sends the same current frame.
            for (var viewer : PlayerLookup.tracking(this)) viewer.networkHandler.sendPacket(packet);
            sentFrame = frame;
        }
    }

    private static final class BeamPair {
        final ServerWorld world;
        final Beam[] beams = new Beam[4];
        final ServerPlayerEntity owner;
        boolean mountSynced;
        final LaserInteraction.State interaction;

        BeamPair(ServerPlayerEntity player) {
            this(player, new LaserInteraction.State());
        }

        BeamPair(ServerPlayerEntity player, LaserInteraction.State interaction) {
            this.world = player.getServerWorld();
            this.owner = player;
            this.interaction = interaction;
            for (int eye = 0; eye < 2; eye++) {
                beams[eye * 2] = new Beam(world, Blocks.RED_STAINED_GLASS.getDefaultState(), 0.16f);
                beams[eye * 2 + 1] = new Beam(world, Blocks.WHITE_CONCRETE.getDefaultState(), 0.035f);
                beams[eye * 2].setPosition(player.getEyePos());
                beams[eye * 2 + 1].setPosition(player.getEyePos());
            }
        }

        void spawn() {
            for (Beam beam : beams) {
                world.spawnEntity(beam);
                if (!beam.startRiding(owner, true)) throw new IllegalStateException("Could not attach eye beam");
                owner.updatePassengerPosition(beam);
            }
            // Mounting changes the attachment index; recompute all local frames.
            update(owner, this);
        }

        void syncMount() {
            var packet = new EntityPassengersSetS2CPacket(owner);
            owner.networkHandler.sendPacket(packet);
            for (var viewer : PlayerLookup.tracking(owner)) viewer.networkHandler.sendPacket(packet);
        }

        void remove() {
            for (Beam beam : beams) {
                beam.stopRiding();
                beam.discard();
            }
            syncMount();
        }
    }

    public static void toggle(ServerPlayerEntity player) {
        if (ACTIVE.containsKey(player.getUuid())) {
            clear(player);
            player.sendMessage(Text.literal("Лазеры выключены. Перезарядка: 20 с"), true);
            return;
        }
        LaserBurst burst = BURSTS.computeIfAbsent(player.getUuid(), id -> new LaserBurst());
        if (!burst.start(clock)) {
            player.sendMessage(Text.literal("Лазеры перезаряжаются: " + ((burst.cooldown(clock) + 19) / 20) + " с"), true);
            return;
        }
        BeamPair pair = new BeamPair(player);
        update(player, pair);
        pair.spawn();
        ACTIVE.put(player.getUuid(), pair);
        player.sendMessage(Text.literal("Лазеры включены: 7 с"), true);
    }

    public static void clear(ServerPlayerEntity player) {
        BeamPair pair = ACTIVE.remove(player.getUuid());
        if (pair != null) pair.remove();
        LaserBurst burst = BURSTS.get(player.getUuid());
        if (burst != null) burst.stop(clock);
    }

    public static void clearAll() {
        for (BeamPair pair : ACTIVE.values()) pair.remove();
        ACTIVE.clear();
        BURSTS.clear();
        clock = 0;
    }

    private static void update(ServerPlayerEntity player, BeamPair pair) {
        Vec3d forward = player.getRotationVec(1).normalize();
        // Use yaw rather than a cross product so looking straight up/down never
        // collapses the distance between the two eyes.
        double yaw = Math.toRadians(player.getYaw());
        Vec3d right = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d eye = player.getEyePos();
        for (int index = 0; index < 2; index++) {
            Vec3d start = eye.add(right.multiply(index == 0 ? -0.13 : 0.13)).add(forward.multiply(0.03));
            Vec3d target = start.add(forward.multiply(RANGE));
            Vec3d end = LaserInteraction.trace(player, start, target).end();
            pair.beams[index * 2].update(start, end);
            pair.beams[index * 2 + 1].update(start, end);
        }
    }

    public static void onViewChanged(ServerPlayerEntity player) {
        BeamPair pair = ACTIVE.get(player.getUuid());
        if (pair == null) return;
        if (!player.isAlive() || !player.hasStatusEffect(HomelanderMod.COMPOUND_V)
                || pair.world != player.getServerWorld()) {
            clear(player);
            return;
        }
        if (java.util.Arrays.stream(pair.beams).anyMatch(beam -> beam.isRemoved() || beam.getVehicle() != player)) {
            // Unexpected dismount must terminate the burst rather than bypass its timer.
            clear(player);
            return;
        }
        LaserBurst burst = BURSTS.get(player.getUuid());
        if (burst == null || burst.expire(clock)) {
            clear(player);
            player.sendMessage(Text.literal("Лазеры остывают: 20 с"), true);
            return;
        }
        update(player, pair);
        for (var beam : pair.beams) beam.sendNow();
    }

    public static void tick(ServerPlayerEntity player) {
        onViewChanged(player);
        BeamPair pair = ACTIVE.get(player.getUuid());
        if (pair == null) return;
        // END_SERVER_TICK follows normal entity tracking. The owner also needs
        // the mount packet, and it must arrive after all four display spawn packets.
        if (!pair.mountSynced) {
            pair.syncMount();
            pair.mountSynced = true;
        }
        if (clock % 20 == 0) {
            var burst = BURSTS.get(player.getUuid());
            player.sendMessage(Text.literal("Лазеры: " + ((burst.remaining(clock) + 19) / 20) + " с"), true);
        }
        Vec3d start = player.getEyePos().add(player.getRotationVec(1).multiply(0.03));
        var hit = LaserInteraction.trace(player, start, start.add(player.getRotationVec(1).multiply(RANGE)));
        LaserInteraction.tick(player, pair.interaction, hit);
    }
}
