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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LaserController {
    private static final double RANGE = 48;
    private static final Map<UUID, BeamPair> ACTIVE = new HashMap<>();
    private LaserController() {}

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
            Vec3d relative = start.subtract(getPos());
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
        final Vec3d anchor;
        final LaserInteraction.State interaction;

        BeamPair(ServerPlayerEntity player) {
            this(player, new LaserInteraction.State());
        }

        BeamPair(ServerPlayerEntity player, LaserInteraction.State interaction) {
            this.world = player.getServerWorld();
            this.anchor = player.getEyePos();
            this.interaction = interaction;
            for (int eye = 0; eye < 2; eye++) {
                beams[eye * 2] = new Beam(world, Blocks.RED_STAINED_GLASS.getDefaultState(), 0.16f);
                beams[eye * 2 + 1] = new Beam(world, Blocks.WHITE_CONCRETE.getDefaultState(), 0.035f);
                beams[eye * 2].setPosition(anchor);
                beams[eye * 2 + 1].setPosition(anchor);
            }
        }

        void spawn() {
            for (Beam beam : beams) world.spawnEntity(beam);
        }

        void remove() {
            for (Beam beam : beams) beam.discard();
        }
    }

    public static boolean toggle(ServerPlayerEntity player) {
        if (ACTIVE.containsKey(player.getUuid())) {
            clear(player);
            return false;
        }
        BeamPair pair = new BeamPair(player);
        update(player, pair);
        pair.spawn();
        ACTIVE.put(player.getUuid(), pair);
        return true;
    }

    public static void clear(ServerPlayerEntity player) {
        BeamPair pair = ACTIVE.remove(player.getUuid());
        if (pair != null) pair.remove();
    }

    public static void clearAll() {
        for (BeamPair pair : ACTIVE.values()) pair.remove();
        ACTIVE.clear();
    }

    private static void update(ServerPlayerEntity player, BeamPair pair) {
        Vec3d forward = player.getRotationVec(1).normalize();
        // Use yaw rather than a cross product so looking straight up/down never
        // collapses the distance between the two eyes.
        double yaw = Math.toRadians(player.getYaw());
        Vec3d right = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d eye = player.getEyePos();
        for (int index = 0; index < 2; index++) {
            Vec3d start = eye.add(right.multiply(index == 0 ? -0.13 : 0.13)).add(forward.multiply(0.25));
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
        if (pair.beams[0].isRemoved() || player.getEyePos().squaredDistanceTo(pair.anchor) > 64) {
            // Re-anchor by spawning a new pair, not by teleporting tracked entities:
            // mixing manual teleports with relative tracking packets doubles motion.
            var interaction = pair.interaction;
            clear(player);
            pair = new BeamPair(player, interaction);
            update(player, pair);
            pair.spawn();
            ACTIVE.put(player.getUuid(), pair);
            return;
        }
        update(player, pair);
        for (var beam : pair.beams) beam.sendNow();
    }

    public static void tick(ServerPlayerEntity player) {
        onViewChanged(player);
        BeamPair pair = ACTIVE.get(player.getUuid());
        if (pair == null) return;
        Vec3d start = player.getEyePos().add(player.getRotationVec(1).multiply(0.25));
        var hit = LaserInteraction.trace(player, start, start.add(player.getRotationVec(1).multiply(RANGE)));
        LaserInteraction.tick(player, pair.interaction, hit);
    }
}
