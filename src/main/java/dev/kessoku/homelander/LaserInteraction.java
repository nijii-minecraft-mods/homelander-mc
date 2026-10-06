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

public final class LaserInteraction {
    private LaserInteraction() {}
    public record Hit(Vec3d end, LivingEntity entity, BlockHitResult block) {}
    private record HeatedFace(BlockPos pos, Direction side) {}

    public static final class State {
        final HeatTimer heat = new HeatTimer();
        long lastDamage = -1000;
    }

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

    public static void tick(ServerPlayerEntity player, State state, Hit hit) {
        ServerWorld world = player.getServerWorld();
        long now = world.getTime();
        if (hit.entity() != null) {
            state.heat.reset();
            if (now - state.lastDamage >= 10) {
                // A player damage source preserves vanilla PvP/team protections.
                hit.entity().damage(player.getDamageSources().playerAttack(player), 40.0f);
                state.lastDamage = now;
            }
            return;
        }
        if (hit.block() == null || !world.getGameRules().getBoolean(GameRules.DO_FIRE_TICK)) {
            state.heat.reset();
            return;
        }
        var block = hit.block();
        int threshold = 3; // 0.15 seconds of continuous aim.
        BlockPos firePos = findFirePosition(player, block);
        if (firePos == null) {
            state.heat.reset();
            return;
        }
        var fire = AbstractFireBlock.getState(world, firePos);
        if (state.heat.tick(new HeatedFace(block.getBlockPos(), block.getSide()), threshold, now, 4)) {
            world.setBlockState(firePos, fire, 3);
        }
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
