package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /vanish} — watch without leaving a trace.
 *
 * <pre>
 * /vanish              toggle Vanish
 * /vanish pickups | p  allow or refuse picking items up while vanished
 * /vanish interact | i allow or refuse breaking, placing, attacking and using
 * </pre>
 *
 * <p>Distinct from {@code /admin}, which is for doing things: Vanish stays in survival, takes the admin
 * loadout, protects you completely, and refuses to touch the world. Distinct from {@code /ghost} too —
 * Ghost keeps your own gear and lets you interact normally.
 *
 * <p>The two modifiers persist per player and are read whenever the guard runs, so they can be flipped
 * mid-session without leaving the mode.
 */
public final class VanishCommand {
	private VanishCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> vanish = Commands.literal("vanish")
			.requires(source -> AdminPermissions.check(source, AdminPermissions.VANISH))
			.executes(context -> toggleState(context.getSource()));

		// Long form and single letter both registered, rather than one redirecting to the other: a
		// redirect would show up in the client's command tree as an alias of the whole subtree, and these
		// are meant to read as two spellings of one switch.
		for (String name : new String[]{"pickups", "p"}) {
			vanish = vanish.then(
				Commands.literal(name).executes(context -> togglePickups(context.getSource()))
			);
		}

		for (String name : new String[]{"interact", "i"}) {
			vanish = vanish.then(
				Commands.literal(name).executes(context -> toggleInteract(context.getSource()))
			);
		}

		dispatcher.register(vanish);

		// Its own root rather than /vanish noclip: it works from any mode, and burying it under /vanish
		// would imply it needs vanishing first.
		dispatcher.register(
			Commands.literal("noclip")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.VANISH_NOCLIP))
				.executes(context -> toggleNoclip(context.getSource()))
		);
	}

	private static int toggleNoclip(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		boolean on = NoclipManager.toggle(player);

		source.sendSuccess(
			() -> Component.literal(
				on
					? "Noclip on — spectator, so no hotbar and no interaction until you toggle it off."
					: "Noclip off."
			).withStyle(on ? ChatFormatting.AQUA : ChatFormatting.GRAY),
			false
		);
		return 1;
	}

	private static int toggleState(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AdminState current = AdminManager.getState(player);
		AdminState next = current == AdminState.VANISH ? AdminState.NONE : AdminState.VANISH;

		AdminManager.setState(player, next);

		if (next == AdminState.NONE) {
			source.sendSuccess(() -> Component.literal("Vanish off.").withStyle(ChatFormatting.GRAY), false);
			return 1;
		}

		EssentialsData data = EssentialsData.get(source.getServer());
		boolean pickups = data.getVanishPickups(player.getUUID());
		boolean interact = data.getVanishInteract(player.getUUID());

		source.sendSuccess(
			() -> Component.literal("Vanish on.").withStyle(style -> style.withColor(AdminState.VANISH.color() & 0xFFFFFF)),
			false
		);

		// Stated on the way in, because both are off by default and silently doing nothing when you hit a
		// block is otherwise indistinguishable from the mod being broken.
		source.sendSuccess(
			() -> Component.literal(
				"Pickups " + (pickups ? "on" : "off") + ", interaction " + (interact ? "on" : "off")
					+ ". Doors always work."
			).withStyle(ChatFormatting.GRAY),
			false
		);
		return 1;
	}

	private static int togglePickups(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		EssentialsData data = EssentialsData.get(source.getServer());
		boolean allowed = !data.getVanishPickups(player.getUUID());

		data.setVanishPickups(player.getUUID(), allowed);
		source.sendSuccess(
			() -> Component.literal("Vanish pickups " + (allowed ? "enabled." : "disabled.")).withStyle(ChatFormatting.GREEN),
			false
		);
		return 1;
	}

	private static int toggleInteract(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		EssentialsData data = EssentialsData.get(source.getServer());
		boolean allowed = !data.getVanishInteract(player.getUUID());

		data.setVanishInteract(player.getUUID(), allowed);
		source.sendSuccess(
			() -> Component.literal("Vanish interaction " + (allowed ? "enabled." : "disabled.")).withStyle(ChatFormatting.GREEN),
			false
		);
		return 1;
	}
}
