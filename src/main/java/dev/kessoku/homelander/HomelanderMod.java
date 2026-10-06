package dev.kessoku.homelander;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntityElytraEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

public final class HomelanderMod implements ModInitializer {
    public static final String MOD_ID = "homelander";
    public static final RegistryEntry.Reference<StatusEffect> COMPOUND_V = Registry.registerReference(
            Registries.STATUS_EFFECT, Identifier.of(MOD_ID, "compound_v"),
            new CompoundStatusEffect());
    public static final Item SERUM = Registry.register(Registries.ITEM,
            Identifier.of(MOD_ID, "serum"), new SerumItem(new Item.Settings().maxCount(16)));
    public static final Item CONTROLLER = Registry.register(Registries.ITEM,
            Identifier.of(MOD_ID, "controller"), new ControllerItem(new Item.Settings().maxCount(1)));

    @Override
    public void onInitialize() {
        eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.addModAssets(MOD_ID);
        eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils.markAsRequired();
        configurePackHosting();
        EntityElytraEvents.CUSTOM.register(FlightController::allowsElytra);
        AttackEntityCallback.EVENT.register((player, world, hand, target, hit) ->
                player instanceof ServerPlayerEntity serverPlayer
                        && FlightController.boostFromController(serverPlayer, hand) ? ActionResult.FAIL : ActionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
                player instanceof ServerPlayerEntity serverPlayer
                        && FlightController.boostFromController(serverPlayer, hand) ? ActionResult.FAIL : ActionResult.PASS);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LaserController.clear(handler.player);
            FlightController.release(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            LaserController.clear(oldPlayer);
            FlightController.forget(oldPlayer);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LaserController.clearAll());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerManager().getPlayerList()) {
                ControllerItem.onServerTick(player);
                FlightController.tick(player);
                LaserController.tick(player);
            }
        });
    }

    private static void configurePackHosting() {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("polymer-autohost")) return;
        var config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("polymer/auto-host.json");
        try {
            java.nio.file.Files.createDirectories(config.getParent());
            if (!java.nio.file.Files.exists(config)) {
                java.nio.file.Files.writeString(config, """
                        {"enabled":true,"required":true,"type":"polymer:netty","settings":{"forced_address":""},"message":"Текстуры сыворотки Хоумлендера"}
                        """, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not configure Polymer pack hosting", exception);
        }
    }

    public static StatusEffectInstance newCompoundEffect() {
        return new StatusEffectInstance(COMPOUND_V, 20 * 60 * 10, 0, false, true, true);
    }
}
