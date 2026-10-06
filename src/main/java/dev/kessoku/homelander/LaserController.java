package dev.kessoku.homelander;

import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3f;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LaserController {
    private static final double RANGE = 48.0;
    private static final double STEP = 0.65;
    private static final DustParticleEffect RED_LASER = new DustParticleEffect(
            new Vector3f(0xF2 / 255.0f, 0x1B / 255.0f, 0x24 / 255.0f), 1.15f);
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
    private LaserController() {}

    public static boolean toggle(ServerPlayerEntity player) {
        if (ACTIVE.remove(player.getUuid())) return false;
        ACTIVE.add(player.getUuid());
        return true;
    }

    public static void tick(ServerPlayerEntity player) {
        if (!player.hasStatusEffect(HomelanderMod.COMPOUND_V)) {
            ACTIVE.remove(player.getUuid());
            return;
        }
        if (!ACTIVE.contains(player.getUuid()) || player.age % 4 != 0) return;
        ServerWorld world = player.getServerWorld();
        Vec3d direction = player.getRotationVec(1.0f).normalize();
        Vec3d center = player.getCameraPosVec(1.0f);
        BlockHitResult hit = world.raycast(new RaycastContext(center, center.add(direction.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d end = hit.getType() == HitResult.Type.MISS ? center.add(direction.multiply(RANGE)) : hit.getPos();
        Vec3d side = direction.crossProduct(new Vec3d(0, 1, 0)).normalize().multiply(0.045);
        emitBeam(world, center.add(side), end.add(side));
        emitBeam(world, center.subtract(side), end.subtract(side));
    }

    private static void emitBeam(ServerWorld world, Vec3d start, Vec3d end) {
        Vec3d delta = end.subtract(start);
        int points = Math.max(1, (int) (delta.length() / STEP));
        for (int i = 0; i <= points; i++) {
            Vec3d point = start.add(delta.multiply((double) i / points));
            world.spawnParticles(RED_LASER, point.x, point.y, point.z, 1, 0, 0, 0, 0);
        }
    }
}
