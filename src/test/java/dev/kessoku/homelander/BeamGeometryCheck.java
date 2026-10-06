package dev.kessoku.homelander;

import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/** Verifies that the visible core starts at the eye and ends at the raycast hit. */
public final class BeamGeometryCheck {
    public static void main(String[] args) {
        Vec3d start = new Vec3d(117.25, 64.75, -39.125);
        Vec3d[] directions = {
            new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0),
            new Vec3d(0, 1, 0), new Vec3d(0, -1, 0),
            new Vec3d(0, 0, 1), new Vec3d(0, 0, -1),
            new Vec3d(1, 2, -3).normalize()
        };
        for (Vec3d direction : directions) {
            for (double length : new double[]{0.01, 3.5, 48}) {
                Vec3d delta = direction.multiply(length);
                for (float width : new float[]{0.035f, 0.16f}) {
                    for (float angle : new float[]{0, 0.4f, 1.57f, 3.14f, 5.7f}) {
                    var matrix = LaserGeometry.frame(start, start.add(delta), width, angle).getMatrix();
                    Vector3f near = matrix.transformPosition(new Vector3f(0.5f, 0.5f, 0));
                    Vector3f far = matrix.transformPosition(new Vector3f(0.5f, 0.5f, 1));
                    check(near, Vec3d.ZERO);
                    check(far, delta);
                    }
                }
            }
        }
        var zero = LaserGeometry.frame(start, start, 0.16f).getMatrix();
        check(zero.transformPosition(new Vector3f(0.5f, 0.5f, 0)), Vec3d.ZERO);
        check(zero.transformPosition(new Vector3f(0.5f, 0.5f, 1)), new Vec3d(0, 0, 0.001));
        System.out.println("PASS: 210 rotating beam frames, vertical/reverse directions, hit endpoints and zero-length hits");
    }

    private static void check(Vector3f actual, Vec3d expected) {
        if (!actual.isFinite() || new Vec3d(actual.x, actual.y, actual.z).distanceTo(expected) > 0.0001) {
            throw new AssertionError("Beam endpoint: " + actual + ", expected " + expected);
        }
    }
}
