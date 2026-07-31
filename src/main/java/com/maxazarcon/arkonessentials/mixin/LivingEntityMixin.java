package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import com.maxazarcon.arkonessentials.DemigodShield;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mob targeting for the hidden states, and the protective half of God Mode and Demigod.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	/**
	 * Makes mobs ignore hidden, passive and AFK players.
	 *
	 * <p>{@code canBeSeenByAnyone} is the first thing {@code TargetingConditions#test} checks, and
	 * {@code canBeSeenAsEnemy} delegates to it as well, so this one hook covers essentially all mob
	 * targeting without touching the targeting logic itself.
	 */
	@Inject(method = "canBeSeenByAnyone", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$hideFromMobs(final CallbackInfoReturnable<Boolean> info) {
		if ((Object) this instanceof ServerPlayer player && AdminManager.ignoredByMobs(player)) {
			info.setReturnValue(false);
		}
	}

	/**
	 * Refuses any drop in health for a protected player.
	 *
	 * <p>This is what makes Demigod work: damage is allowed to run its whole course, so the hurt
	 * animation, knockback, sounds and particles all play, and only the consequence is denied. Rises
	 * are left alone so healing and the initial top-up still function.
	 */
	@Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$freezeHealth(final float health, final CallbackInfo info) {
		if ((Object) this instanceof ServerPlayer player
			&& AdminManager.getState(player).pinsHealth()
			&& health < player.getHealth()) {
			info.cancel();
		}
	}

	/**
	 * Demigod without armour: half damage.
	 *
	 * <p>Injected at the end of the magic-absorb step, which is the last thing to touch the number before
	 * absorption is drained — so the halving lands first and the shield then soaks what is left, rather
	 * than the shield eating a full-size hit and the reduction applying to nothing.
	 *
	 * <p>Only while unarmoured. A Demigod in gear is already benefiting from the armour, and stacking a
	 * flat halving on top of that is what made the mode invulnerable in the first place.
	 */
	@Inject(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"), cancellable = true)
	private void arkonessentials$halveUnarmouredDamage(
		final DamageSource source,
		final float damage,
		final CallbackInfoReturnable<Float> info
	) {
		if ((Object) this instanceof ServerPlayer player && DemigodShield.halvesDamage(player)) {
			info.setReturnValue(info.getReturnValue() * 0.5F);
		}
	}

	/**
	 * Demigod's softened effects: harmful ones last half as long, and Wither becomes Resistance.
	 *
	 * <p>A {@code @ModifyVariable} rather than cancelling and re-adding, deliberately — re-adding would
	 * call this method again and need a re-entrancy guard to avoid looping. Rewriting the argument on the
	 * way in has no such problem.
	 *
	 * <p>Duration is halved rather than the amplifier: dropping the amplifier turns Poison II into Poison
	 * I, which is a different effect, whereas half the duration is uniformly "it wears off sooner" for
	 * everything from Slowness to Hunger.
	 */
	@ModifyVariable(
		method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
		at = @At("HEAD"),
		argsOnly = true
	)
	private MobEffectInstance arkonessentials$softenEffects(final MobEffectInstance effect) {
		if (!((Object) this instanceof ServerPlayer player) || !AdminManager.getState(player).hasDemigodShield()) {
			return effect;
		}

		if (effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) {
			return effect;
		}

		// Wither is singled out because it is the one harmful effect that ignores armour entirely and
		// ticks straight through health. Turning it into its opposite is the thematic answer: a demigod
		// does not decay.
		if (effect.getEffect().is(MobEffects.WITHER)) {
			return new MobEffectInstance(
				MobEffects.RESISTANCE, effect.getDuration(), effect.getAmplifier(),
				effect.isAmbient(), effect.isVisible(), effect.showIcon()
			);
		}

		return new MobEffectInstance(
			effect.getEffect(), Math.max(effect.getDuration() / 2, 1), effect.getAmplifier(),
			effect.isAmbient(), effect.isVisible(), effect.showIcon()
		);
	}

	/** God Mode only: harmful effects never take hold. Beneficial ones still apply. */
	@Inject(method = "canBeAffected", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$blockHarmfulEffects(final MobEffectInstance effect, final CallbackInfoReturnable<Boolean> info) {
		if ((Object) this instanceof ServerPlayer player
			&& AdminManager.getState(player).blocksDamageEntirely()
			&& effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
			info.setReturnValue(false);
		}
	}

	/**
	 * God Mode only: nothing shoves the player around.
	 *
	 * <p>Targets the six-argument overload, which is the one that actually does the work — the shorter
	 * signature just delegates to it. Cancelling damage alone would not be enough, since explosions and
	 * effects apply knockback through paths of their own.
	 */
	@Inject(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$blockKnockback(
		final double power,
		final double xd,
		final double zd,
		final DamageSource source,
		final float damage,
		final boolean comesFromEffect,
		final CallbackInfo info
	) {
		if ((Object) this instanceof ServerPlayer player && AdminManager.getState(player).blocksDamageEntirely()) {
			info.cancel();
		}
	}
}
