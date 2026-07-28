package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /fakeleave [mode]} and {@code /fakejoin} — appearing to log out without doing so.
 *
 * <p>The mode argument takes <em>any</em> state, concealing or not. Usually a faked departure is about
 * carrying on watching — Ghost keeps your own gear and survival, Admin hands you creative and the admin
 * loadout — but {@code none} is deliberately allowed too, so you can have the server announce you left
 * while standing in the open. Ghost is the default, configurable via {@code fakeLeaveDefaultMode}.
 */
public final class PresenceCommand {
	private PresenceCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		var fakeLeave = Commands.literal("fakeleave")
			.requires(source -> AdminPermissions.check(source, AdminPermissions.FAKE_LEAVE))
			.executes(context -> fakeLeave(context.getSource(), EssentialsConfig.get().fakeLeaveMode()));

		// Every state is offered, concealing or not — including "none", which announces a departure and
		// changes nothing else. Standing in plain sight after the server says you left is a legitimate
		// use of this, so the command does not second-guess which one you meant.
		for (AdminState state : AdminState.values()) {
			fakeLeave = fakeLeave.then(
				Commands.literal(state.getSerializedName())
					.executes(context -> fakeLeave(context.getSource(), state))
			);
		}

		dispatcher.register(fakeLeave);

		dispatcher.register(
			Commands.literal("fakejoin")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.FAKE_JOIN))
				.executes(context -> fakeJoin(context.getSource()))
		);
	}

	private static int fakeLeave(final CommandSourceStack source, final AdminState state) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PresenceManager.fakeLeave(player, state);

		// Sent only to them, and after the broadcast, so the confirmation cannot be mistaken for part of
		// what everyone else just saw. NONE has no label, so it gets wording of its own rather than a
		// sentence with a hole in it.
		Component confirmation = state == AdminState.NONE
			? Component.literal("Announced your departure. You are still in plain sight.")
			: Component.literal("Announced your departure. You are now in " + state.label() + ".");

		source.sendSuccess(() -> confirmation.copy().withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static int fakeJoin(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PresenceManager.fakeJoin(player);

		source.sendSuccess(
			() -> Component.literal("Announced your arrival. Your mode is unchanged.").withStyle(ChatFormatting.GRAY),
			false
		);
		return 1;
	}
}
