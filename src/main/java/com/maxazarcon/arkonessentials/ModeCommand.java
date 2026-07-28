package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /mode} — what am I in, and what does it do?
 *
 * <p>Exists for players without the client jar, who get no indicator, and for anyone who cannot
 * remember which of six similar-sounding modes stops mobs and which stops damage. {@code /mode <name>}
 * describes any mode without entering it, so it doubles as the in-game manual.
 *
 * <p>Public by default: it reports only your own state and some fixed text, so there is nothing here
 * worth withholding.
 */
public final class ModeCommand {
	private ModeCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> mode = Commands.literal("mode")
			.requires(source -> AdminPermissions.checkPublic(source, AdminPermissions.MODE))
			.executes(context -> current(context.getSource()));

		for (AdminState state : AdminState.values()) {
			mode = mode.then(
				Commands.literal(state.getSerializedName())
					.executes(context -> {
						describe(context.getSource(), state);
						return 1;
					})
			);
		}

		dispatcher.register(mode);
	}

	private static int current(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AdminState state = AdminManager.getState(player);

		describe(source, state);

		// The flags are independent of the state, so they are reported separately rather than folded into
		// the description — any combination of them can be live at once.
		List<String> active = new ArrayList<>(3);

		if (AdminManager.isFlightActive(player)) {
			active.add("flight");
		}

		if (AfkManager.isAfk(player)) {
			active.add("AFK");
		}

		if (PresenceManager.isAppearingOffline(player)) {
			active.add("appearing offline");
		}

		if (!active.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Also active: " + String.join(", ", active)).withStyle(ChatFormatting.AQUA), false);
		}

		if (state == AdminState.BUILD) {
			source.sendSuccess(
				() -> Component.literal(
					"Reach bonus +" + AdminManager.getReachBonus(player)
						+ ", night vision " + (AdminManager.getBuildNightVision(player) ? "on" : "off")
				).withStyle(ChatFormatting.DARK_AQUA),
				false
			);
		}

		return 1;
	}

	private static void describe(final CommandSourceStack source, final AdminState state) {
		String name = state == AdminState.NONE ? "None" : state.label();

		// NONE draws nothing on the HUD and so carries colour 0, which as RGB is black and unreadable
		// against a dark chat background. It gets white here rather than a special case in the enum,
		// which would mean inventing a colour for something that is never drawn.
		int color = state == AdminState.NONE ? 0xFFFFFF : state.color() & 0xFFFFFF;

		source.sendSuccess(
			// Otherwise drawn in the state's own indicator colour, so chat and the HUD agree. The stored
			// value is packed ARGB and Style wants RGB, hence the mask.
			() -> Component.literal(name).withStyle(style -> style.withColor(color)),
			false
		);

		source.sendSuccess(() -> Component.literal(state.description()).withStyle(ChatFormatting.GRAY), false);
	}
}
