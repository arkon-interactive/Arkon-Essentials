package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import java.util.List;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps vanished players out of {@code /list}.
 *
 * <p>Without this, vanish is undone by one command any player can run: hiding the entity and the tab
 * list entry means nobody can <em>see</em> a ghost, but {@code /list} reads the server's player list
 * directly and would name them anyway — along with the player count, which is the same leak in numeric
 * form.
 *
 * <p>Redirects the {@code getPlayers()} call inside {@code format}, which is the single source for both
 * the names and the count. Filtering after the fact would fix one and not the other.
 */
@Mixin(ListPlayersCommand.class)
public abstract class ListPlayersCommandMixin {
	@Redirect(
		method = "format",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"
		)
	)
	private static List<ServerPlayer> arkonessentials$hideVanished(
		final PlayerList playerList,
		final CommandSourceStack source,
		final Function<ServerPlayer, Component> formatter
	) {
		ServerPlayer viewer = source.getPlayer();

		// Console, command blocks and RCON see the truth. They are the operator's own view of the server,
		// and an admin tool that lies to its own console is worse than one that reveals a ghost.
		if (viewer == null) {
			return playerList.getPlayers();
		}

		// canSee already encodes the whole rule: hidden players are visible to staff holding see_hidden,
		// and always to each other, so two ghosts on the same incident can still find one another.
		return playerList.getPlayers().stream().filter(target -> AdminManager.canSee(viewer, target)).toList();
	}
}
