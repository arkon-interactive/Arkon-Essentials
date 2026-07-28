package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import com.maxazarcon.arkonessentials.AfkManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a hidden player's disconnect from announcing itself in chat.
 *
 * <p>The leave message is broadcast from here rather than from {@code PlayerList}, which is why it
 * needs its own hook alongside {@link PlayerListMixin}.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	/**
	 * The disconnecting player, borrowed from the target class.
	 *
	 * <p>{@code @Shadow} declares a field that already exists on the target so this mixin can read it;
	 * it is not a new field. The name and type must match the real one exactly, and a rename upstream
	 * shows up as a load-time failure rather than a compile error.
	 */
	@Shadow
	public ServerPlayer player;

	/**
	 * Swallows the leave broadcast for a vanished player.
	 *
	 * <p>Redirects the broadcast call rather than cancelling the method, for the same reason as the join
	 * side: {@code removePlayerFromWorld} does the actual removal, and cancelling it would leak the
	 * player. Unlike the join mixin, this needs no trailing parameters — {@code removePlayerFromWorld}
	 * takes none, so the handler signature is just the redirected call's own arguments.
	 */
	@Redirect(
		method = "removePlayerFromWorld",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"
		)
	)
	private void arkonessentials$suppressLeaveMessage(final PlayerList list, final Component message, final boolean overlay) {
		// Read at disconnect time, so someone who drops while vanished leaves silently and someone who
		// came off duty first leaves normally.
		if (!AdminManager.getState(this.player).hiddenFromPlayers()) {
			list.broadcastSystemMessage(message, overlay);
		}
	}

	/**
	 * Tells the AFK tracker that a command, rather than chat, just reset vanilla's idle timer.
	 *
	 * <p>{@code tryHandleChat} is the single funnel for both: {@code handleChat} passes
	 * {@code isCommand = false} and {@code handleChatCommand} passes true, and it resets
	 * {@code lastActionTime} either way. That is why AFK cannot simply read vanilla's timer — typing in
	 * chat should end AFK, but running a command should not, or {@code /afk} would cancel itself.
	 *
	 * <p>At TAIL, deliberately: vanilla's reset happens inside this method, so injecting at HEAD would
	 * record an instant fractionally <em>before</em> the timer's new value and the comparison in
	 * {@code AfkManager.tick} could let the command through as activity.
	 */
	@Inject(method = "tryHandleChat", at = @At("TAIL"))
	private void arkonessentials$noteCommandActivity(
		final String message,
		final boolean isCommand,
		final Runnable chatHandler,
		final CallbackInfo info
	) {
		if (isCommand) {
			AfkManager.noteCommand(this.player);
		}
	}
}
