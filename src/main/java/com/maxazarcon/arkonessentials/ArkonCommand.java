package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * {@code /arkon} — the mod's own administration, as opposed to the staff tools under {@code /admin}.
 *
 * <pre>
 * /arkon config                  list every setting and its value
 * /arkon config &lt;key&gt;            show one setting
 * /arkon config &lt;key&gt; &lt;value&gt;    change it, saving and applying at once
 * /arkon reload                  re-read the file, for when it was edited by hand
 * </pre>
 *
 * <p>The subtree is generated from {@link EssentialsConfig#OPTIONS}, so a new setting becomes editable
 * the moment it is declared — with an argument of the right type, meaning bad input is refused at parse
 * time rather than clamped afterwards.
 */
public final class ArkonCommand {
	private ArkonCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> config = Commands.literal("config")
			.executes(context -> listSettings(context.getSource()));

		for (EssentialsConfig.Option<?> option : EssentialsConfig.OPTIONS) {
			config.then(optionNode(option));
		}

		dispatcher.register(
			Commands.literal("arkon")
				.requires(AdminPermissions::mayEditConfig)
				.then(config)
				.then(Commands.literal("reload").executes(context -> reload(context.getSource())))
				.then(Commands.literal("ping").executes(context -> pingReport(context.getSource())))
				.then(
					Commands.literal("perms")
						.then(
							Commands.argument("target", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
									context.getSource().getServer().getPlayerNames(), builder
								))
								.executes(context -> showPerms(context.getSource(), StringArgumentType.getString(context, "target")))
						)
				)
		);
	}

	/**
	 * Lists every gate the mod checks and how it resolves for one player.
	 *
	 * <p>Distinguishes what the permission provider actually said from what the mod fell back to, which
	 * is the distinction that matters when a grant "isn't working": a node reading {@code default} means
	 * nothing granted or denied it, so the fallback decided — and for an operator the fallback is always
	 * yes, which is why tiers have to be tested on an unopped account.
	 */
	/**
	 * Resolves the target by online name first, then as a UUID.
	 *
	 * <p>The offline path matters: diagnosing "why can my moderator not do X" usually happens while
	 * they are not logged in, and it is the only way to inspect a permission setup without a second
	 * account.
	 */
	private static int showPerms(final CommandSourceStack source, final String target) {
		MinecraftServer server = source.getServer();
		ServerPlayer online = server.getPlayerList().getPlayerByName(target);

		if (online != null) {
			report(source, target, online.getPermissionContext());
			return 1;
		}

		UUID uuid;

		try {
			uuid = UUID.fromString(target);
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal("No player online called '" + target + "', and that is not a UUID."));
			return 0;
		}

		// Waited on rather than handled asynchronously, deliberately. Output sent to a command source
		// after its command has already returned is discarded — for console and RCON it would vanish
		// entirely. This is an operator diagnostic run occasionally, so a bounded stall is the right
		// trade for an answer that actually arrives. The provider resolves off the server thread, so
		// waiting here does not deadlock it.
		try {
			PermissionContext context = PermissionContext.offlinePlayer(uuid, server).get(2, TimeUnit.SECONDS);
			report(source, target, context);
			return 1;
		} catch (TimeoutException e) {
			source.sendFailure(Component.literal("Timed out asking the permission provider about " + uuid + "."));
		} catch (Exception e) {
			source.sendFailure(Component.literal("Could not resolve permissions for " + uuid + ": " + e.getMessage()));
			Thread.currentThread().interrupt();
		}

		return 0;
	}

	/**
	 * {@code /arkon ping} — every player's latency, as one line of JSON.
	 *
	 * <p>For launchers and monitoring rather than for reading: vanilla surfaces per-player latency
	 * nowhere a tool can reach it — {@code /list} gives names only and there is no vanilla ping command —
	 * even though the server has tracked it all along for the tab list.
	 *
	 * <p>Deliberately <strong>one line</strong>, and built by hand rather than through a JSON library, so
	 * that what arrives over RCON is exactly one parseable string. Sending several {@code sendSuccess}
	 * calls would have RCON concatenate them into something a parser has to split first.
	 *
	 * <p>Vanished players are included, flagged rather than omitted. This is an operator-only command on
	 * the operator's own server: silently under-reporting would make it useless for monitoring, and the
	 * flag lets the caller decide.
	 */
	private static int pingReport(final CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		StringBuilder json = new StringBuilder("{\"schema\":1,\"players\":[");

		for (int i = 0; i < players.size(); i++) {
			ServerPlayer player = players.get(i);

			if (i > 0) {
				json.append(',');
			}

			json.append("{\"name\":\"").append(escape(player.getGameProfile().name()))
				.append("\",\"uuid\":\"").append(player.getUUID())
				.append("\",\"ping\":").append(player.connection.latency())
				.append(",\"hidden\":").append(AdminManager.getState(player).hiddenFromPlayers()
					|| PresenceManager.isAppearingOffline(player))
				.append('}');
		}

		json.append("]}");

		String line = json.toString();
		source.sendSuccess(() -> Component.literal(line), false);
		return players.size();
	}

	/**
	 * Minimal JSON string escaping.
	 *
	 * <p>Only quote and backslash can realistically appear in a Minecraft name, but escaping nothing
	 * would let one malformed name break the whole document for the caller.
	 */
	private static String escape(final String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void report(final CommandSourceStack source, final String name, final PermissionContext context) {
		source.sendSuccess(() -> Component.literal("Permissions for " + name + ":").withStyle(ChatFormatting.GOLD), false);

		for (AdminPermissions.Gate gate : AdminPermissions.GATES) {
			Optional<Boolean> granted = AdminPermissions.resolve(context, gate.node());

			// What the provider said, versus what the mod would conclude after its fallback. A node
			// reading "default" means nothing granted or denied it and the fallback decided — and for an
			// operator the fallback is always yes, which is why tiers must be tested unopped.
			boolean effective = granted.orElseGet(() -> gate.fallback().test(context));

			String origin = granted.map(value -> value ? "granted" : "denied").orElse("default");
			ChatFormatting colour = effective ? ChatFormatting.GREEN : ChatFormatting.RED;

			source.sendSuccess(
				() -> Component.literal("  " + gate.node().getPath() + " = " + effective + " (" + origin + ")").withStyle(colour),
				false
			);
		}
	}

	private static <T> LiteralArgumentBuilder<CommandSourceStack> optionNode(final EssentialsConfig.Option<T> option) {
		return Commands.literal(option.key())
			.executes(context -> showSetting(context.getSource(), option))
			.then(
				Commands.argument("value", option.argumentType())
					.executes(context -> setSetting(context.getSource(), option, context.getArgument("value", option.valueType())))
			);
	}

	private static int listSettings(final CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal("Arkon Essentials config:").withStyle(ChatFormatting.GOLD), false);

		for (EssentialsConfig.Option<?> option : EssentialsConfig.OPTIONS) {
			source.sendSuccess(() -> Component.literal("  " + option.key() + " = " + option.read()), false);
		}

		return EssentialsConfig.OPTIONS.size();
	}

	private static int showSetting(final CommandSourceStack source, final EssentialsConfig.Option<?> option) {
		source.sendSuccess(() -> Component.literal(option.key() + " = " + option.read()), false);
		source.sendSuccess(() -> Component.literal(option.description()).withStyle(ChatFormatting.GRAY), false);
		return 1;
	}

	private static <T> int setSetting(final CommandSourceStack source, final EssentialsConfig.Option<T> option, final T value) {
		String previous = option.read();
		option.write(value);
		EssentialsConfig.save();
		applyToOnlinePlayers(source.getServer());

		source.sendSuccess(
			() -> Component.literal(option.key() + ": " + previous + " -> " + option.read()).withStyle(ChatFormatting.GREEN),
			true
		);
		return 1;
	}

	private static int reload(final CommandSourceStack source) {
		EssentialsConfig.load();
		applyToOnlinePlayers(source.getServer());
		source.sendSuccess(() -> Component.literal("Config reloaded from disk.").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	/**
	 * Makes a config change visible without anyone relogging.
	 *
	 * <p>Most settings are read live, so nothing is needed for those. Two things are not: values already
	 * pushed onto a player (Build Mode's reach modifier, flight speed) have to be recomputed, and the
	 * command tree has to be resent — {@code .requires} results are baked into the per-client tree when
	 * it is sent, so a setting that gates a command leaves clients showing a stale one otherwise.
	 */
	private static void applyToOnlinePlayers(final MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (AdminManager.getState(player).grantsNightVision()) {
				AdminManager.applyBuildPerks(player);
			}

			// refresh rather than apply: a config edit can revoke flight (demigodFlight going false),
			// not only grant it.
			AdminManager.refreshFlight(player);

			// hideFromWebMaps can be turned off as well as on, and turning it off has to put concealed
			// players back on the map rather than merely stop hiding new ones.
			WebMapIntegration.refresh(player);

			server.getCommands().sendCommands(player);
		}
	}
}
