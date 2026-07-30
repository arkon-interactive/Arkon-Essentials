package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /ping} — round trip to the server, and how long it takes.
 *
 * <p><strong>This used to deliberately report no number</strong>, on the reasoning that a meaningful
 * one would need a client-side timestamp and so could not work for vanilla clients. That was wrong: the
 * server already measures the round trip of its own keep-alive packets and keeps the result per
 * connection, precisely so it can put ping bars in everyone's tab list. No client mod is involved.
 *
 * <p>Two things worth knowing about the number, both from how vanilla maintains it:
 *
 * <ul>
 *   <li>It is <strong>smoothed</strong>, {@code (latency * 3 + sample) / 4}, so it leans on history and
 *       moves toward a change over several samples rather than jumping.
 *   <li>Samples arrive on the <strong>keep-alive cadence</strong>, roughly every 15 seconds — so it is a
 *       recent average, not a live probe, and running it twice in a row will usually give the same
 *       answer.
 * </ul>
 *
 * <p>Latency is not privileged information: it is in the player-list packet sent to every client, which
 * is what the tab list draws. Exposing it here changes what is <em>reachable</em>, not what is known.
 */
public final class PingCommand {
	private PingCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("ping")
				.requires(source -> AdminPermissions.checkPublic(source, AdminPermissions.PING))
				.executes(context -> self(context.getSource()))
				.then(
					Commands.argument("player", EntityArgument.player())
						.executes(context -> other(context.getSource(), EntityArgument.getPlayer(context, "player")))
				)
		);
	}

	private static int self(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		int ping = player.connection.latency();

		// "Pong!" is kept: the round trip proving the server is still processing commands was the original
		// point, and it remains true whatever the number says.
		source.sendSuccess(
			() -> Component.literal("Pong! " + ping + "ms").withStyle(colour(ping)),
			false
		);
		return ping;
	}

	private static int other(final CommandSourceStack source, final ServerPlayer target) {
		ServerPlayer viewer = source.getPlayer();

		// Refused with vanilla's own "no player" wording rather than an explanation, so asking about a
		// vanished player cannot be used to confirm that they are online.
		if (viewer != null && !AdminManager.canSee(viewer, target)) {
			source.sendFailure(Component.translatable("argument.entity.notfound.player"));
			return 0;
		}

		int ping = target.connection.latency();

		source.sendSuccess(
			() -> Component.literal(target.getGameProfile().name() + ": " + ping + "ms").withStyle(colour(ping)),
			false
		);
		return ping;
	}

	/** Rough bands, matching what the tab list's bars convey at a glance. */
	private static ChatFormatting colour(final int ping) {
		if (ping < 100) {
			return ChatFormatting.GREEN;
		}

		return ping < 300 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}
}
