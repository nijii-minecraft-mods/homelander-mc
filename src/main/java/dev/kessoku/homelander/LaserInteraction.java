package dev.kessoku.homelander;

import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.FireBlock;
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
            var intersection = entity.getBoundingBox().expand(0.08).raycast(start, stop);
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
        var material = world.getBlockState(block.getBlockPos());
        if (!((FireBlock) Blocks.FIRE).isFlammable(material)) {
            state.heat.reset();
            return;
        }
        int threshold = 3; // 0.15 seconds of continuous aim.
        BlockPos firePos = block.getBlockPos().offset(block.getSide());
        var fire = AbstractFireBlock.getState(world, firePos);
        if (!world.getBlockState(firePos).isAir() || !fire.canPlaceAt(world, firePos)
                || !world.canPlayerModifyAt(player, firePos)) {
            state.heat.reset();
            return;
        }
        if (state.heat.tick(new HeatedFace(block.getBlockPos(), block.getSide()), threshold, now, 4)) {
            world.setBlockState(firePos, fire, 3);
        }
    }
}
