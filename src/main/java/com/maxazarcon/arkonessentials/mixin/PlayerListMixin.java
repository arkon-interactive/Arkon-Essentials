package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a hidden player's reconnection from announcing itself in chat.
 *
 * <p>Join and leave messages are built in two <em>different</em> classes, so vanish needs two mixins to
 * cover both: this one for joining, and {@code ServerGamePacketListenerImplMixin} for leaving. Changing
 * one without the other leaves half the leak open.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	/**
	 * Swallows the join broadcast for a vanished player.
	 *
	 * <p>A {@code @Redirect} on the broadcast call rather than an {@code @Inject} cancelling the method:
	 * {@code placeNewPlayer} does the entire join — spawning, chunk sending, abilities — and cancelling it
	 * would break joining outright. Redirecting one call replaces only that call.
	 *
	 * <p>The trailing three parameters are not ours to choose. A redirect handler takes the redirected
	 * call's own arguments first ({@code list} is the receiver, then its two parameters), followed by the
	 * enclosing method's parameters — so this signature has to track {@code placeNewPlayer}'s. If that
	 * signature changes upstream, this fails to apply at class load with an injection error rather than
	 * at compile time; see the mixin verification notes in CLAUDE.md.
	 */
	@Redirect(
		method = "placeNewPlayer",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
		)
	)
	private void arkonessentials$suppressJoinMessage(
		final PlayerList list,
		final Component message,
		final boolean overlay,
		final Connection connection,
		final ServerPlayer player,
		final CommonListenerCookie cookie
	) {
		// Not calling through is the suppression: the message is simply never broadcast. Everything else
		// in the join sequence carries on untouched.
		if (!AdminManager.getState(player).hiddenFromPlayers()) {
			list.broadcastSystemMessage(message, overlay);
		}
	}
}
