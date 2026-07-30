package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a fully protected player from catching fire at all.
 *
 * <p>Immunity to burn <em>damage</em> already exists, but that is a different thing from not burning:
 * the flames still attach, which means the fire overlay across the player's own screen and a lit figure
 * anyone with {@code see_hidden} would notice. For a mode built around watching quietly, both are the
 * wrong outcome.
 *
 * <p>{@code setRemainingFireTicks} is the one setter — {@code igniteForSeconds} and every block, mob and
 * projectile that sets something alight route through it — so refusing an increase here is enough.
 * Decreases are left alone so anything already burning still counts down and goes out.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "setRemainingFireTicks", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$suppressFire(final int remainingTicks, final CallbackInfo info) {
		if (remainingTicks > 0 && (Object) this instanceof ServerPlayer player && AdminManager.fireSuppressed(player)) {
			info.cancel();
		}
	}
}
