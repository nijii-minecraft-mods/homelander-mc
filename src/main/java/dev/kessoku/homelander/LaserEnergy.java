package dev.kessoku.homelander;

/** Integer units avoid accumulated rounding errors in partial recharge. */
public final class LaserEnergy {
    public static final int CAPACITY = 2800;
    public static final int DRAIN = 20;
    public static final int REGEN = 7;
    private int units;

    public LaserEnergy() { this(CAPACITY); }
    public LaserEnergy(int saved) { units = Math.clamp(saved, 0, CAPACITY); }
    public int units() { return units; }
    public int percent() { return units * 100 / CAPACITY; }

    /** Holding at empty never recharges or produces flickering single-tick bursts. */
    public boolean tick(boolean held) {
        if (!held) {
            units = Math.min(CAPACITY, units + REGEN);
            return false;
        }
        if (units < DRAIN) return false;
        units -= DRAIN;
        return true;
    }
}
