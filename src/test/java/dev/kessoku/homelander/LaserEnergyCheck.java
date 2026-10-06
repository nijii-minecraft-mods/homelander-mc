package dev.kessoku.homelander;

import net.minecraft.nbt.NbtCompound;
import java.util.UUID;

public final class LaserEnergyCheck {
    public static void main(String[] args) {
        LaserEnergy energy = new LaserEnergy();
        for (int t=0;t<140;t++) check(energy.tick(true));
        check(energy.units() == 0);
        for (int t=0;t<30;t++) check(!energy.tick(true));
        check(energy.units() == 0); // Empty + held never causes recharge/flicker.
        for (int t=0;t<100;t++) check(!energy.tick(false));
        check(energy.units() == 700 && energy.percent() == 25);
        for (int t=0;t<35;t++) check(energy.tick(true));
        check(energy.units() == 0);
        for (int t=0;t<400;t++) energy.tick(false);
        check(energy.units() == LaserEnergy.CAPACITY);
        energy.tick(false);
        check(energy.units() == LaserEnergy.CAPACITY);
        check(new LaserEnergy(-1).units() == 0);
        check(new LaserEnergy(Integer.MAX_VALUE).units() == LaserEnergy.CAPACITY);
        UUID uuid = UUID.randomUUID();
        var store = new LaserEnergyStore();
        store.energy(uuid).tick(true);
        var saved = store.writeNbt(new NbtCompound(), null);
        var restored = LaserEnergyStore.read(saved, null);
        check(restored.energy(uuid).units() == LaserEnergy.CAPACITY - LaserEnergy.DRAIN);
        check(restored.energy(UUID.randomUUID()).units() == LaserEnergy.CAPACITY);
        HeatTimer heat = new HeatTimer();
        check(!heat.tick("stone",3,0,4));
        check(!heat.tick("stone",3,1,4));
        check(heat.tick("stone",3,2,4));
        heat.reset();
        check(!heat.tick("glass",3,3,4));
        check(!heat.tick("glass",3,4,4));
        check(!heat.tick("glass",3,5,4));
        check(heat.tick("glass",3,6,4));
        System.out.println("PASS: stored energy, partial spending/recharge, held-empty latch, NBT round-trip and rapid ignition");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Energy check failed"); }
}
