package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a vanished player's advancements to themselves.
 *
 * <p>Easy to miss, and a complete giveaway: an admin watching quietly picks up an advancement for
 * something incidental and the server announces their name to everyone. Vanish hides presence, and an
 * advancement announcement is presence stated out loud.
 *
 * <p>Only the <em>announcement</em> is dropped. The advancement is still awarded, its rewards still
 * granted, and the player still sees their own toast — being unseen should not cost progress.
 *
 * <p><strong>The target is the lambda, not {@code award} itself.</strong> Vanilla wraps the broadcast in
 * {@code display().ifPresent(display -> ...)}, so the call lives in a synthetic method and a redirect
 * aimed at {@code award} scans zero targets and fails the injection check — which is exactly what
 * happened, and what {@code MixinApplicationTest} caught. The name was read out of the class rather than
 * guessed ({@code javap -p}: {@code lambda$award$0}).
 *
 * <p>A synthetic name is a fragile thing to depend on: it is not part of the API and a Mojang refactor
 * that adds an earlier lambda to this class would renumber it. That is tolerable only because the
 * failure is now loud — the test loads this class on every build, so a rename fails there rather than
 * quietly ceasing to hide anything.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
	@Shadow
	private ServerPlayer player;

	@Redirect(
		method = "lambda$award$0",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
		)
	)
	private void arkonessentials$suppressAdvancementAnnouncement(
		final PlayerList list,
		final Component message,
		final boolean overlay
	) {
		if (!AdminManager.getState(this.player).hiddenFromPlayers()) {
			list.broadcastSystemMessage(message, overlay);
		}
	}
}
