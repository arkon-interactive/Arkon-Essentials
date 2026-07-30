package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import com.maxazarcon.arkonessentials.EssentialsConfig;
import com.maxazarcon.arkonessentials.PresenceManager;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps vanished players out of the server-list ping.
 *
 * <p>The status response carries an online count and a sample of names, both built straight from
 * {@code PlayerList} without ever touching the client packet path that vanish controls. A count that
 * drops by one whenever staff go on duty is exactly the tell vanish exists to remove — and unlike
 * {@code /list}, this one is readable by anyone who can reach the port, without logging in.
 *
 * <p>Redirects the single {@code getPlayers()} call at the top of {@code buildPlayerStatus}, which
 * feeds both the count and the sample. Filtering only one of them would leave the other contradicting
 * it.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Redirect(
		method = "buildPlayerStatus",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"
		)
	)
	private List<ServerPlayer> arkonessentials$hideVanishedFromPing(final PlayerList playerList) {
		if (!EssentialsConfig.get().hideFromPing) {
			return playerList.getPlayers();
		}

		// No viewer to compare against — a ping is anonymous, so there is no see_hidden to honour and
		// concealed means concealed. Both halves of vanish count: a hidden state, and a faked departure,
		// which may leave the state as NONE.
		return playerList.getPlayers().stream()
			.filter(player -> !AdminManager.getState(player).hiddenFromPlayers()
				&& !PresenceManager.isAppearingOffline(player))
			.toList();
	}
}
