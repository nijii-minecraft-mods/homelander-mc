package dev.kessoku.homelander;

/** Uses server ticks, not world time: switching dimensions cannot reset the burst. */
public final class LaserBurst {
    public static final int DURATION = 7 * 20;
    public static final int COOLDOWN = 20 * 20;
    private long activeUntil = -1;
    private long readyAt;

    public boolean start(long now) {
        expire(now);
        if (activeUntil >= 0 || now < readyAt) return false;
        activeUntil = now + DURATION;
        return true;
    }

    public boolean expire(long now) {
        if (activeUntil < 0 || now < activeUntil) return false;
        stop(activeUntil);
        return true;
    }

    public void stop(long now) {
        if (activeUntil < 0) return;
        readyAt = Math.min(now, activeUntil) + COOLDOWN;
        activeUntil = -1;
    }

    public long remaining(long now) { return Math.max(0, activeUntil - now); }
    public long cooldown(long now) { return Math.max(0, readyAt - now); }
}
