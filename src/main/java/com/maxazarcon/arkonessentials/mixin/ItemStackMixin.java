package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spares a protected player's gear from wear.
 *
 * <p>This overload is the funnel every other {@code hurtAndBreak} delegates into, so armour, tools and
 * anything else held or worn are all covered by the one hook. The <strong>full descriptor is spelled
 * out</strong> in {@code method} precisely because there are several overloads — matching by bare name
 * would be ambiguous, and picking a shallower one would leave the paths that skip it uncovered.
 *
 * <p>Note this is keyed on the <em>player</em> the damage is attributed to, not on the stack. There is
 * nothing marking a protected player's items, so gear moved to someone else wears normally, which is
 * the behaviour you want.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(
		method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void arkonessentials$protectGear(
		final int amount,
		final ServerLevel level,
		final @Nullable ServerPlayer player,
		final Consumer<Item> onBreak,
		final CallbackInfo info
	) {
		// Nullable in the signature and checked: plenty of durability loss has no player behind it at all
		// (dispensers, other entities), and those must keep wearing normally.
		if (player != null && AdminManager.getState(player).protectsPlayer()) {
			// Cancelling at HEAD means the damage is never applied and onBreak never runs, so a stack on
			// its last point of durability survives instead of vanishing.
			info.cancel();
		}
	}
}
