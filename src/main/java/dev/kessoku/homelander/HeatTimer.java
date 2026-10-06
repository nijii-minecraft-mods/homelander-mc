package dev.kessoku.homelander;

/** Continuous aim is required; looking away does not bank heat for later. */
public final class HeatTimer {
    private Object target;
    private int ticks;
    private long lastIgnition = -1000;

    public void reset() { target = null; ticks = 0; }

    public boolean tick(Object next, int threshold, long now) {
        return tick(next, threshold, now, 40);
    }

    public boolean tick(Object next, int threshold, long now, int cooldownTicks) {
        if (next == null) { reset(); return false; }
        if (!next.equals(target)) { target = next; ticks = 0; }
        ticks++;
        if (ticks < threshold || now - lastIgnition < cooldownTicks) return false;
        ticks = 0;
        lastIgnition = now;
        return true;
    }
}
