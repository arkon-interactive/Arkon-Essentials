package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a vanished player hoovering up dropped items.
 *
 * <p>{@code playerTouch} is the single funnel for picking an item up — it runs the pickup-delay check,
 * the inventory insert and the pickup sound — so cancelling here means nothing happens rather than
 * happening partially.
 *
 * <p>Silent by design: the item is simply not collected, and the player keeps walking. There is no
 * refusal message because this fires on every touch of every item, and a chat line per tick would be
 * unusable.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$blockPickups(final Player player, final CallbackInfo info) {
		if (player instanceof ServerPlayer serverPlayer && AdminManager.pickupsBlocked(serverPlayer)) {
			info.cancel();
		}
	}
}
