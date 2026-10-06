package dev.kessoku.homelander;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.PersistentState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared overworld save: dimension changes, death and reconnects retain the pool. */
public final class LaserEnergyStore extends PersistentState {
    private static final Type<LaserEnergyStore> TYPE = new Type<>(LaserEnergyStore::new, LaserEnergyStore::read, null);
    private final Map<UUID, LaserEnergy> players = new HashMap<>();

    public static LaserEnergyStore get(ServerPlayerEntity player) {
        return player.getServer().getOverworld().getPersistentStateManager().getOrCreate(TYPE, "homelander_laser_energy");
    }

    public LaserEnergy energy(UUID uuid) {
        return players.computeIfAbsent(uuid, key -> { markDirty(); return new LaserEnergy(); });
    }

    public static LaserEnergyStore read(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        var state = new LaserEnergyStore();
        var saved = nbt.getCompound("players");
        for (String key : saved.getKeys()) {
            try { state.players.put(UUID.fromString(key), new LaserEnergy(saved.getInt(key))); }
            catch (IllegalArgumentException ignored) { /* Skip malformed UUID entries. */ }
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        var saved = new NbtCompound();
        players.forEach((uuid, energy) -> saved.putInt(uuid.toString(), energy.units()));
        nbt.put("players", saved);
        return nbt;
    }
}
