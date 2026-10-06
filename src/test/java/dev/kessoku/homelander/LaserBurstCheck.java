package dev.kessoku.homelander;
public final class LaserBurstCheck {
    public static void main(String[] args) {
        LaserBurst burst = new LaserBurst();
        check(burst.start(100));
        check(!burst.start(101));
        check(burst.remaining(239) == 1 && !burst.expire(239));
        check(burst.expire(240) && burst.cooldown(240) == 400);
        check(!burst.start(639) && burst.start(640));
        burst.stop(641);
        check(burst.cooldown(641) == 400 && !burst.start(642));
        burst.stop(900); // Disconnect/cleanup cannot extend or reset the cooldown.
        check(burst.cooldown(900) == 141 && burst.start(1041));
        check(burst.expire(2000) && burst.cooldown(2000) == 0);
        check(burst.start(2000));
        HeatTimer heat = new HeatTimer();
        check(!heat.tick("wood",3,0,4));
        check(!heat.tick("wood",3,1,4));
        check(heat.tick("wood",3,2,4));
        check(!heat.tick("leaves",3,3,4));
        check(!heat.tick("leaves",3,4,4));
        check(!heat.tick("leaves",3,5,4));
        check(heat.tick("leaves",3,6,4));
        System.out.println("PASS: 7-second limit, 20-second cooldown, early stop, late expiry and rapid ignition");
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Burst timing failed"); }
}
