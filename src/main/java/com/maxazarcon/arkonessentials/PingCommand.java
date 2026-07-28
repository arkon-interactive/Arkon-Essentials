package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /ping} — answers immediately.
 *
 * <p>Deliberately does not measure anything: the value is that a round trip through the server proves
 * the connection is alive and the server is still processing commands. A number would need a
 * client-side timestamp to mean anything, and this has to work for vanilla clients.
 */
public final class PingCommand {
	private PingCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("ping")
				.requires(source -> AdminPermissions.checkPublic(source, AdminPermissions.PING))
				.executes(context -> {
					context.getSource().sendSuccess(() -> Component.literal("Pong!").withStyle(ChatFormatting.YELLOW), false);
					return 1;
				})
		);
	}
}
