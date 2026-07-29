package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * {@code /afk [reason]} — steps away, or comes back.
 *
 * <p>The reason argument sits behind its own gate, so a server can have AFK for everyone while keeping
 * free-text broadcasts to those trusted with them. It defaults to the {@code afkReasonsAvailable}
 * config value, the same shape as {@code /build nv}.
 */
public final class AfkCommand {
	private AfkCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("afk")
				// checkPublic, not check: this defaults to allowed rather than to operator, so /afk works
				// on a server with no permissions mod at all. Using check() here would make it staff-only
				// out of the box, which is the opposite of the intent.
				.requires(source -> AdminPermissions.checkPublic(source, AdminPermissions.AFK))
				.executes(context -> toggle(context.getSource(), null))
				.then(
					// greedyString so a reason can contain spaces without quoting. That necessarily makes it
					// the last argument, which is fine — nothing follows it.
					Commands.argument("reason", StringArgumentType.greedyString())
						.requires(AdminPermissions::mayGiveAfkReason)
						.executes(context -> toggle(context.getSource(), StringArgumentType.getString(context, "reason")))
				)
		);

		// Separate roots rather than /afk on|off, which would collide with the reason argument: greedy
		// text would swallow "on" as a reason and the subcommand would be unreachable.
		dispatcher.register(
			Commands.literal("afkoff")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.AFK_TOGGLE))
				.executes(context -> setEnabled(context.getSource(), false))
		);

		dispatcher.register(
			Commands.literal("afkon")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.AFK_TOGGLE))
				.executes(context -> setEnabled(context.getSource(), true))
		);
	}

	/**
	 * Turns the idle timer on or off for this player alone, persistently.
	 *
	 * <p>Only the automatic timer is affected — {@code /afk} keeps working either way, since deciding
	 * you are away is always yours to make. Turning it off while already AFK also releases you, so the
	 * command cannot leave someone stuck in a state its own timer can no longer clear.
	 */
	private static int setEnabled(final CommandSourceStack source, final boolean enabled) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		EssentialsData.get(source.getServer()).setAfkEnabled(player.getUUID(), enabled);

		if (!enabled && AfkManager.isAfk(player)) {
			AfkManager.toggle(player, null);
		}

		source.sendSuccess(
			() -> Component.literal(
				enabled
					? "Automatic AFK is on again."
					: "Automatic AFK is off for you. /afk still works."
			).withStyle(ChatFormatting.GREEN),
			false
		);
		return 1;
	}

	private static int toggle(final CommandSourceStack source, final @Nullable String reason) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();

		// Refused outright while appearing offline. AFK is a presence signal — the whole feature exists to
		// tell other people where you are — and /fakeleave exists to say you are not here at all. Even with
		// the broadcast suppressed, leaving the two able to coexist invites a leak the moment either side
		// changes.
		if (PresenceManager.isAppearingOffline(player)) {
			source.sendFailure(Component.literal("You are appearing offline. Use /fakejoin first if you want to be seen."));
			return 0;
		}

		AfkManager.toggle(player, reason);
		return 1;
	}
}
