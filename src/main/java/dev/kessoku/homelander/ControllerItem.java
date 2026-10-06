package dev.kessoku.homelander;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ControllerItem extends Item implements PolymerItem {
    private static final Set<UUID> PENDING_GIVE = ConcurrentHashMap.newKeySet();
    public ControllerItem(Settings settings) { super(settings); }

    @Override
    public TypedActionResult<ItemStack> use(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            if (!player.hasStatusEffect(HomelanderMod.COMPOUND_V)) {
                player.sendMessage(Text.literal("Контроллер работает, пока действует сыворотка."), true);
            } else if (player.isSneaking()) {
                FlightController.Mode mode = FlightController.toggleMode(player);
                player.sendMessage(Text.literal(mode == FlightController.Mode.HOVER
                        ? "Полёт: зависание" : "Полёт: парение"), true);
            } else {
                boolean enabled = LaserController.toggle(player);
                player.sendMessage(Text.literal(enabled ? "Лазеры включены" : "Лазеры выключены"), true);
            }
        }
        return TypedActionResult.success(stack);
    }

    public static void scheduleGive(ServerPlayerEntity player) {
        PENDING_GIVE.add(player.getUuid());
    }

    public static void onServerTick(ServerPlayerEntity player) {
        if (PENDING_GIVE.remove(player.getUuid())) giveToHotbar(player);
    }

    private static void giveToHotbar(ServerPlayerEntity player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack existing = inventory.getStack(slot);
            if (existing.isOf(HomelanderMod.CONTROLLER)) {
                if (slot != 0) {
                    ItemStack first = inventory.getStack(0).copy();
                    inventory.setStack(0, existing.copy());
                    inventory.setStack(slot, first);
                }
                return;
            }
        }

        ItemStack previous = inventory.getStack(0).copy();
        inventory.setStack(0, new ItemStack(HomelanderMod.CONTROLLER));
        if (!previous.isEmpty() && !inventory.insertStack(previous)) {
            player.dropItem(previous, false);
        }
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, ServerPlayerEntity player) {
        return Items.SPYGLASS;
    }
}
