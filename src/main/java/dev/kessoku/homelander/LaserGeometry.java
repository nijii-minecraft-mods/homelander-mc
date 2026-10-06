package dev.kessoku.homelander;

import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class LaserGeometry {
    private LaserGeometry() {}

    public static AffineTransformation frame(Vec3d start, Vec3d end, float width) {
        return frame(start, end, width, 0);
    }

    public static AffineTransformation frame(Vec3d start, Vec3d end, float width, float axialAngle) {
        Vec3d delta = end.subtract(start);
        float length = Math.max(0.001f, (float) delta.length());
        Vec3d direction = delta.lengthSquared() < 1.0e-12 ? new Vec3d(0, 0, 1) : delta.normalize();
        Quaternionf rotation = new Quaternionf().rotationTo(new Vector3f(0, 0, 1),
                new Vector3f((float) direction.x, (float) direction.y, (float) direction.z)).rotateZ(axialAngle);
        Vector3f offset = rotation.transform(new Vector3f(-width / 2, -width / 2, 0));
        return new AffineTransformation(offset, rotation,
                new Vector3f(width, width, length), new Quaternionf());
    }
}
