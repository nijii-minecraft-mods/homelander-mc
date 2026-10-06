package dev.kessoku.homelander;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.UseAction;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

public final class SerumItem extends Item implements PolymerItem {
    public SerumItem(Settings settings) { super(settings); }

    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.DRINK; }
    @Override public int getMaxUseTime(ItemStack stack, LivingEntity user) { return 32; }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        user.setCurrentHand(hand);
        return TypedActionResult.consume(stack);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (user instanceof ServerPlayerEntity player) {
            player.addStatusEffect(HomelanderMod.newCompoundEffect());
            player.setHealth(player.getMaxHealth());
            FlightController.grant(player);
            ControllerItem.scheduleGive(player);
        }
        if (user instanceof PlayerEntity player && !player.getAbilities().creativeMode) {
            stack.decrement(1);
            ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
            if (stack.isEmpty()) return bottle;
            if (!player.getInventory().insertStack(bottle)) player.dropItem(bottle, false);
        }
        return stack;
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, ServerPlayerEntity player) {
        return Items.POTION;
    }
}
