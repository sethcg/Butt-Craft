package com.github.sethcg.buttcraft;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.jspecify.annotations.Nullable;

/**
 * DAMAGE FROM LINGERING FART GAS, CREDITED TO THE PLAYER WHO FARTED.
 *
 * <p>PICKS A RANDOM DEATH MESSAGE FROM THE LANG FILE, DEPENDING ON WHETHER THE VICTIM
 * DIED TO THEIR OWN GAS OR TO SOMEONE ELSE'S.
 */
public final class FartGasDamageSource extends DamageSource {

    public static final ResourceKey<DamageType> TYPE = ResourceKey.create(
        Registries.DAMAGE_TYPE,
        ButtCraft.id("fart_gas")
    );

    // DEATH MESSAGES: death.attack.buttcraft.fart_gas[.self|.player].<NUMBER>
    private static final String MESSAGE_KEY = "death.attack." + ButtCraft.MOD_ID + ".fart_gas";
    private static final int UNKNOWN_MESSAGES = 2;
    private static final int SELF_MESSAGES = 5;
    private static final int PLAYER_MESSAGES = 6;

    private FartGasDamageSource(Holder<DamageType> type, @Nullable Entity farter) {
        super(type, null, farter);
    }

    public static FartGasDamageSource create(ServerLevel level, @Nullable Entity farter) {
        Holder<DamageType> type = level.registryAccess()
            .lookupOrThrow(Registries.DAMAGE_TYPE)
            .getOrThrow(TYPE);

        return new FartGasDamageSource(type, farter);
    }

    @Override
    public Component getLocalizedDeathMessage(LivingEntity victim) {
        Entity farter = this.getEntity();

        // THE FARTER LEFT BEFORE THEIR GAS FINISHED THE JOB
        if (farter == null) {
            return Component.translatable(
                MESSAGE_KEY + "." + victim.getRandom().nextInt(UNKNOWN_MESSAGES),
                victim.getDisplayName()
            );
        }

        // DIED TO THEIR OWN GAS
        if (farter == victim) {
            return Component.translatable(
                MESSAGE_KEY + ".self." + victim.getRandom().nextInt(SELF_MESSAGES),
                victim.getDisplayName()
            );
        }

        // DIED TO SOMEONE ELSE'S GAS
        return Component.translatable(
            MESSAGE_KEY + ".player." + victim.getRandom().nextInt(PLAYER_MESSAGES),
            victim.getDisplayName(),
            farter.getDisplayName()
        );
    }
}
