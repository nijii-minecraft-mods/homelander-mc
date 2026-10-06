package dev.kessoku.homelander;

import eu.pb4.polymer.core.api.other.PolymerStatusEffect;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;

public final class CompoundStatusEffect extends StatusEffect implements PolymerStatusEffect {
    public CompoundStatusEffect() {
        super(StatusEffectCategory.BENEFICIAL, 0xD71920);
        addAttributeModifier(EntityAttributes.GENERIC_MAX_HEALTH,
                Identifier.of(HomelanderMod.MOD_ID, "compound_v_health"), 20.0,
                EntityAttributeModifier.Operation.ADD_VALUE);
    }

    @Override
    public StatusEffect getPolymerReplacement(ServerPlayerEntity player) {
        return StatusEffects.HEALTH_BOOST.value();
    }
}
