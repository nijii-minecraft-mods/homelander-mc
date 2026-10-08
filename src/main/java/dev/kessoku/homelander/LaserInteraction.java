package dev.kessoku.homelander;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.Set;

public final class LaserInteraction {
    private LaserInteraction() {}
    public record Hit(Vec3d end, LivingEntity entity, BlockHitResult block) {}

    public static final class State {
        final HeatTimer heat = new HeatTimer();
        long lastDamage = -1000;
        /** Previous hit position on a surface (for DDA line ignition). */
        Vec3d prevHitPos = null;
        /** Previous look direction for sweep interpolation. */
        Vec3d prevDirection = null;
        /**
         * Intermediate look directions captured from network packets between
         * server ticks. Gives sub-tick resolution for the sweep, compensating
         * for network jitter and high-sensitivity mice.
         */
        final java.util.List<Vec3d> intermediateDirections = new java.util.ArrayList<>();

        /** Called from the mixin on every PlayerMoveC2SPacket with a look change. */
        public void recordLookDirection(Vec3d dir) {
            // Cap the queue to avoid unbounded growth from packet floods.
            if (intermediateDirections.size() < 64) {
                intermediateDirections.add(dir.normalize());
            }
        }
    }

    // Maximum number of interpolation steps per tick to prevent lag spikes from
    // extremely fast mouse movement.
    private static final int MAX_SWEEP_STEPS = 48;
    // Maximum DDA steps when walking a line between two surface hit points.
    private static final int MAX_LINE_STEPS = 128;

    public static Hit trace(ServerPlayerEntity player, Vec3d start, Vec3d end) {
        ServerWorld world = player.getServerWorld();
        BlockHitResult block = world.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d stop = block.getType() == HitResult.Type.MISS ? end : block.getPos();
        double nearest = start.squaredDistanceTo(stop);
        LivingEntity victim = null;
        Vec3d contact = stop;
        for (var entity : world.getOtherEntities(player, new Box(start, stop).expand(0.2),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
            // The eye beams have a narrow physical width, not a large aim-assist box.
            var intersection = entity.getBoundingBox().expand(0.14).raycast(start, stop);
            if (intersection.isEmpty()) continue;
            double distance = start.squaredDistanceTo(intersection.get());
            if (distance < nearest) {
                nearest = distance;
                victim = (LivingEntity) entity;
                contact = intersection.get();
            }
        }
        return new Hit(contact, victim, victim == null && block.getType() != HitResult.Type.MISS ? block : null);
    }

    /**
     * Main tick: damage entities instantly, then sweep-ignite blocks along the
     * entire arc the player's gaze covered since the previous tick, using
     * buffered intermediate directions from network packets for sub-tick
     * precision.
     */
    public static void tick(ServerPlayerEntity player, State state, Hit hit) {
        ServerWorld world = player.getServerWorld();
        long now = world.getTime();

        // --- Entity damage (unchanged, instant) ---
        if (hit.entity() != null) {
            state.heat.reset();
            if (now - state.lastDamage >= 10) {
                hit.entity().damage(player.getDamageSources().playerAttack(player), 40.0f);
                state.lastDamage = now;
            }
            state.prevDirection = player.getRotationVec(1).normalize();
            state.prevHitPos = null;
            state.intermediateDirections.clear();
            return;
        }

        if (!world.getGameRules().getBoolean(GameRules.DO_FIRE_TICK)) {
            state.heat.reset();
            state.prevDirection = player.getRotationVec(1).normalize();
            state.prevHitPos = null;
            state.intermediateDirections.clear();
            return;
        }

        // --- Build the full chain of directions to sweep through ---
        Vec3d currentDir = player.getRotationVec(1).normalize();
        Vec3d eyePos = player.getEyePos();
        double range = 256;

        // Collect all directions: [prevDirection, ...intermediates, currentDir]
        java.util.List<Vec3d> chain = new java.util.ArrayList<>();
        if (state.prevDirection != null) {
            chain.add(state.prevDirection);
        }
        chain.addAll(state.intermediateDirections);
        chain.add(currentDir);
        state.intermediateDirections.clear();

        // Deduplicate near-identical consecutive directions.
        java.util.List<Vec3d> deduped = new java.util.ArrayList<>();
        for (Vec3d dir : chain) {
            if (deduped.isEmpty() || angleBetween(deduped.get(deduped.size() - 1), dir) > 0.001) {
                deduped.add(dir);
            }
        }

        // Track ignited positions across the whole sweep to avoid double-sets.
        Set<BlockPos> ignited = new HashSet<>();

        // For each consecutive pair of directions, sweep with slerp + DDA.
        Vec3d prevSurfaceHit = state.prevHitPos;
        for (int seg = 0; seg < deduped.size() - 1; seg++) {
            prevSurfaceHit = sweepSegment(player, world, eyePos, deduped.get(seg),
                    deduped.get(seg + 1), range, ignited, prevSurfaceHit);
        }

        // Also ignite the exact current aim point.
        Vec3d finalHit = igniteAtDirection(player, world, eyePos, currentDir, range, ignited);
        if (finalHit != null) {
            // DDA line from last sweep hit to the final aim point.
            if (prevSurfaceHit != null) {
                igniteLine(player, world, prevSurfaceHit, finalHit, ignited);
            }
            prevSurfaceHit = finalHit;
        }

        state.prevDirection = currentDir;
        state.prevHitPos = prevSurfaceHit;
    }

    /**
     * Sweep between two directions: slerp to get intermediate aim directions,
     * raycast each, then DDA-connect consecutive surface hits.
     * Returns the last surface hit position (or null).
     */
    private static Vec3d sweepSegment(ServerPlayerEntity player, ServerWorld world,
                                       Vec3d eyePos, Vec3d fromDir, Vec3d toDir,
                                       double range, Set<BlockPos> ignited,
                                       Vec3d prevSurfaceHit) {
        double angle = angleBetween(fromDir, toDir);
        if (angle < 0.003) return prevSurfaceHit;

        // Adaptive step count: ~1 block gap at the surface distance.
        // For a surface 50 blocks away, 1 block = ~0.02 rad → many steps.
        // For close surfaces, fewer steps are fine.
        // Use a reasonable heuristic: steps = angle / 0.015 rad (~0.86°), capped.
        int steps = Math.min(MAX_SWEEP_STEPS, Math.max(2, (int) Math.ceil(angle / 0.015)));

        Vec3d lastHit = prevSurfaceHit;

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3d dir = slerp(fromDir, toDir, t);
            Vec3d end = eyePos.add(dir.multiply(range));

            BlockHitResult block = world.raycast(new RaycastContext(eyePos, end,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            if (block.getType() == HitResult.Type.MISS) continue;

            Vec3d surfacePos = block.getPos();

            // Ignite this exact point.
            BlockPos firePos = findFirePosition(player, block);
            if (firePos != null && ignited.add(firePos)) {
                var fire = AbstractFireBlock.getState(world, firePos);
                world.setBlockState(firePos, fire, 3);
            }

            // DDA-line from previous hit to this one — fills gaps on the surface.
            if (lastHit != null && lastHit.squaredDistanceTo(surfacePos) > 1.0) {
                igniteLine(player, world, lastHit, surfacePos, ignited);
            }

            lastHit = surfacePos;
        }
        return lastHit;
    }

    /**
     * Ignite a single block at the given direction (instant, no heat-up).
     * Returns the surface hit position, or null if missed.
     */
    private static Vec3d igniteAtDirection(ServerPlayerEntity player, ServerWorld world,
                                            Vec3d eyePos, Vec3d direction, double range,
                                            Set<BlockPos> ignited) {
        Vec3d end = eyePos.add(direction.multiply(range));
        BlockHitResult block = world.raycast(new RaycastContext(eyePos, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (block.getType() == HitResult.Type.MISS) return null;

        BlockPos firePos = findFirePosition(player, block);
        if (firePos != null && ignited.add(firePos)) {
            var fire = AbstractFireBlock.getState(world, firePos);
            world.setBlockState(firePos, fire, 3);
        }
        return block.getPos();
    }

    /**
     * Walk blocks along a 3D line between two surface points and ignite each
     * one. Uses a simple parametric step (DDA-style) to cover every block the
     * line passes through — this is what makes the fire trail truly continuous
     * even when slerp samples are sparse due to network latency.
     */
    private static void igniteLine(ServerPlayerEntity player, ServerWorld world,
                                    Vec3d from, Vec3d to, Set<BlockPos> ignited) {
        Vec3d delta = to.subtract(from);
        double length = delta.length();
        if (length < 0.5) return;

        // Step size ≈ 0.45 blocks → guaranteed to hit every block cell.
        int steps = Math.min(MAX_LINE_STEPS, (int) Math.ceil(length / 0.45));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3d point = from.add(delta.multiply(t));
            BlockPos bp = BlockPos.ofFloored(point);

            if (!ignited.add(bp)) continue;
            if (!world.canPlayerModifyAt(player, bp)) continue;
            if (!world.getWorldBorder().contains(bp) || world.isOutOfHeightLimit(bp)) continue;

            // Try to place fire at this position or an adjacent air block.
            if (world.getBlockState(bp).isAir()) {
                var fire = AbstractFireBlock.getState(world, bp);
                if (fire.canPlaceAt(world, bp)) {
                    world.setBlockState(bp, fire, 3);
                    continue;
                }
            }
            // If the block itself is solid, try placing fire on any adjacent face.
            for (Direction side : Direction.values()) {
                BlockPos adj = bp.offset(side);
                if (!ignited.add(adj)) continue;
                if (!world.getWorldBorder().contains(adj) || world.isOutOfHeightLimit(adj)) continue;
                if (!world.canPlayerModifyAt(player, adj)) continue;
                if (world.getBlockState(adj).isAir()) {
                    var fire = AbstractFireBlock.getState(world, adj);
                    if (fire.canPlaceAt(world, adj)) {
                        world.setBlockState(adj, fire, 3);
                        break;
                    }
                }
            }
        }
    }

    /** Angle in radians between two unit vectors. */
    private static double angleBetween(Vec3d a, Vec3d b) {
        return Math.acos(Math.max(-1, Math.min(1, a.dotProduct(b))));
    }

    /**
     * Spherical linear interpolation between two unit vectors.
     * Produces evenly spaced intermediate directions along the great-circle arc.
     */
    private static Vec3d slerp(Vec3d a, Vec3d b, double t) {
        double dot = Math.max(-1, Math.min(1, a.dotProduct(b)));
        double theta = Math.acos(dot);
        if (theta < 1e-6) return b; // effectively same direction
        double sinTheta = Math.sin(theta);
        double wa = Math.sin((1 - t) * theta) / sinTheta;
        double wb = Math.sin(t * theta) / sinTheta;
        return new Vec3d(
                a.x * wa + b.x * wb,
                a.y * wa + b.y * wb,
                a.z * wa + b.z * wb
        ).normalize();
    }

    private static BlockPos findFirePosition(ServerPlayerEntity player, BlockHitResult hit) {
        var world = player.getServerWorld();
        // For a nonflammable wall the hit face may not support ordinary fire.
        // Prefer the face, then the top and other adjacent supported air spaces.
        Direction[] sides = {hit.getSide(), Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.DOWN};
        if (!world.canPlayerModifyAt(player, hit.getBlockPos())) return null;
        for (var side : sides) {
            var pos = hit.getBlockPos().offset(side);
            if (!world.getWorldBorder().contains(pos) || world.isOutOfHeightLimit(pos)) continue;
            var fire = AbstractFireBlock.getState(world, pos);
            if (world.getBlockState(pos).isAir() && fire.canPlaceAt(world, pos)
                    && world.canPlayerModifyAt(player, pos)) return pos;
        }
        return null;
    }
}
