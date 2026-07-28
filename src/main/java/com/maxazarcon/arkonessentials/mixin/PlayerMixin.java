package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes hunger for a protected player.
 *
 * <p>Exhaustion is the single source of hunger depletion, so refusing it here pins the bar without
 * having to write to it every tick. Vanilla already skips this for creative players; this extends the
 * same courtesy to God Mode and Demigod, which do not change game mode, and to anyone AFK.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$freezeHunger(final float amount, final CallbackInfo info) {
		if ((Object) this instanceof ServerPlayer player && AdminManager.hungerFrozen(player)) {
			info.cancel();
		}
	}
}
