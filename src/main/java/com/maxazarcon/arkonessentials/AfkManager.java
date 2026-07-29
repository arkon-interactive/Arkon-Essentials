package com.maxazarcon.arkonessentials;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Away-from-keyboard tracking.
 *
 * <p>AFK is <strong>a flag, not an {@link AdminState}</strong>, for the same reason flight is: the
 * states are mutually exclusive, and anyone can go AFK from any of them. An admin stepping away is
 * still an admin.
 *
 * <p>Deliberately in memory only. AFK describes what someone is doing right now, so it cannot outlive
 * the session — a player who reconnects is by definition at their keyboard.
 *
 * <h2>How activity is detected</h2>
 *
 * <p>The server never sees keystrokes or mouse motion; it sees packets. Two sources are combined:
 *
 * <ul>
 *   <li>{@link ServerPlayer#getLastActionTime()}, which vanilla already maintains for the
 *       {@code player-idle-timeout} kick. It is reset by movement keys, actual movement, attacking,
 *       interacting, item use, container clicks, chat and commands.
 *   <li>A per-tick comparison of head rotation, because vanilla's timer <em>ignores</em> looking
 *       around — {@code handlePlayerKnownMovement} only resets on positional movement, and
 *       {@code handleMovePlayer} applies rotation without touching it. Rotation is how mouse movement
 *       reaches the server, so without this a player could look around and still be called idle.
 * </ul>
 *
 * <p>What this cannot see is a key that produces no packet at all — toggling perspective, or opening
 * the inventory without clicking. Those leave a player idle until they touch the mouse, which in
 * practice is immediately.
 */
public final class AfkManager {
	/**
	 * How long after going AFK before activity is allowed to end it.
	 *
	 * <p>Without this, {@code /afk} would be unusable: letting go of the mouse nudges it, that nudge is
	 * a rotation change, and the player would be back before they stood up.
	 */
	private static final long ENGAGE_GRACE_MILLIS = 2_000L;

	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

	private AfkManager() {
	}

	/** One player's activity, live only while they are connected. */
	private static final class Session {
		private boolean afk;
		private @Nullable String reason;

		/**
		 * High-water mark of genuine activity.
		 *
		 * <p>Kept separately rather than reading {@link ServerPlayer#getLastActionTime()} directly,
		 * because vanilla's timer counts things we do not — see {@link #noteCommand}. Once a command has
		 * overwritten vanilla's value, the earlier real activity would be unrecoverable; this remembers
		 * it.
		 */
		private long lastActivityMillis = Util.getMillis();

		/**
		 * When a command last reset vanilla's timer.
		 *
		 * <p>Anything at or before this instant is treated as command-caused and ignored.
		 */
		private long lastCommandMillis;

		private long engagedAtMillis;
		private float yRot;
		private float xRot;
	}

	/**
	 * Records that a command — not chat — just reset vanilla's idle timer.
	 *
	 * <p>Vanilla resets {@code lastActionTime} for both, from the same {@code tryHandleChat} method,
	 * distinguished only by its {@code isCommand} flag. Typing in chat should end AFK; running a command
	 * should not, or {@code /afk} itself would cancel instantly and an AFK player checking {@code /tps}
	 * would silently come back.
	 *
	 * <p>Called from the mixin <strong>after</strong> vanilla's reset, so the recorded instant is at or
	 * after the timer's new value and the comparison in {@link #tick} reliably excludes it.
	 */
	public static void noteCommand(final ServerPlayer player) {
		Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());
		session.lastCommandMillis = Util.getMillis();
	}

	public static boolean isAfk(final ServerPlayer player) {
		Session session = SESSIONS.get(player.getUUID());
		return session != null && session.afk;
	}

	public static void onJoin(final ServerPlayer player) {
		Session session = new Session();
		session.yRot = player.getYRot();
		session.xRot = player.getXRot();
		SESSIONS.put(player.getUUID(), session);
	}

	public static void onDisconnect(final ServerPlayer player) {
		SESSIONS.remove(player.getUUID());
	}

	/**
	 * Drops AFK without announcing anything.
	 *
	 * <p>For {@code /fakeleave}, where the ordinary "no longer AFK" line would be exactly the broadcast
	 * the fake departure is trying to prevent. Everywhere else, use {@link #toggle} so the state change
	 * is visible to whoever was told about it.
	 */
	public static void clear(final ServerPlayer player) {
		Session session = SESSIONS.get(player.getUUID());

		if (session == null || !session.afk) {
			return;
		}

		session.afk = false;
		session.reason = null;
		AdminManager.syncTo(player);
	}

	/**
	 * Handles {@code /afk}, both directions.
	 *
	 * @param reason shown alongside the announcement, or null for the plain message
	 * @return true if the player is now AFK
	 */
	public static boolean toggle(final ServerPlayer player, final @Nullable String reason) {
		Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());

		if (session.afk) {
			release(player, session);
			return false;
		}

		engage(player, session, reason);
		return true;
	}

	/**
	 * Watches every player for the idle timeout and for the activity that ends it.
	 *
	 * <p>Runs each tick rather than on a slower schedule so a rotation change cannot slip between two
	 * samples: it is a float comparison per player, and the alternative is missing the very input we
	 * are looking for.
	 */
	public static void tick(final MinecraftServer server) {
		long now = Util.getMillis();
		int timeoutSeconds = EssentialsConfig.get().afkTimeoutSeconds;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());

			// Mouse movement reaches the server as head rotation, and vanilla's timer ignores it entirely.
			if (player.getYRot() != session.yRot || player.getXRot() != session.xRot) {
				session.yRot = player.getYRot();
				session.xRot = player.getXRot();
				session.lastActivityMillis = now;
			}

			// Vanilla's timer counts movement, keys, clicks, interactions and chat — all of which we want
			// — but also commands, which we do not. Anything at or before the last command instant is
			// therefore discarded, and the high-water mark keeps whatever real activity preceded it.
			long vanilla = player.getLastActionTime();

			if (vanilla > session.lastCommandMillis) {
				session.lastActivityMillis = Math.max(session.lastActivityMillis, vanilla);
			}

			long lastActivity = session.lastActivityMillis;

			if (session.afk) {
				// Only activity after the grace window counts, so a settling mouse cannot cancel what the
				// player just asked for.
				if (lastActivity > session.engagedAtMillis + ENGAGE_GRACE_MILLIS) {
					release(player, session);
				}

				continue;
			}

			if (timeoutSeconds <= 0 || now - lastActivity < timeoutSeconds * 1000L) {
				continue;
			}

			// Someone who has announced a fake departure is being deliberately quiet; marking them AFK
			// would broadcast their name and undo the point of it. Their own /afk still works.
			if (PresenceManager.isAppearingOffline(player)) {
				continue;
			}

			// Per-player opt-out via /afkoff.
			if (!EssentialsData.get(server).getAfkEnabled(player.getUUID())) {
				continue;
			}

			engage(player, session, null);
		}
	}

	private static void engage(final ServerPlayer player, final Session session, final @Nullable String reason) {
		session.afk = true;
		session.reason = reason;
		session.engagedAtMillis = Util.getMillis();

		EssentialsConfig config = EssentialsConfig.get();
		Component name = Component.literal(player.getGameProfile().name()).withStyle(ChatFormatting.WHITE);

		announce(
			player,
			reason == null
				? format(config.afkMessage, name)
				: format(config.afkReasonMessage, name, Component.literal(reason).withStyle(ChatFormatting.YELLOW))
		);

		AdminManager.syncTo(player);
	}

	private static void release(final ServerPlayer player, final Session session) {
		session.afk = false;
		session.reason = null;

		Component name = Component.literal(player.getGameProfile().name()).withStyle(ChatFormatting.WHITE);
		announce(player, format(EssentialsConfig.get().afkReturnMessage, name));
		AdminManager.syncTo(player);
	}

	/**
	 * Broadcasts, unless doing so would give a vanished player away.
	 *
	 * <p>Vanish already suppresses join and leave messages; an AFK announcement would undo that work by
	 * naming someone nobody can see. They still get told themselves, so the state is never silent to the
	 * person in it.
	 */
	private static void announce(final ServerPlayer player, final Component message) {
		// Both conditions matter, and appearing-offline is the one that is easy to miss: /fakeleave accepts
		// any state including NONE, so someone can be appearing offline while hiddenFromPlayers() is false.
		// Checking only the state would broadcast the name of someone the server has just announced as
		// having left.
		if (AdminManager.getState(player).hiddenFromPlayers() || PresenceManager.isAppearingOffline(player)) {
			player.sendSystemMessage(message);
			return;
		}

		MinecraftServer server = player.level().getServer();

		if (server != null) {
			server.getPlayerList().broadcastSystemMessage(message, false);
		}
	}

	/**
	 * Fills {@code %s} placeholders in a configured message with components that keep their own colour.
	 *
	 * <p>Done by hand rather than with {@code String.format} because the whole point is that the pieces
	 * are styled differently — a formatted string would arrive as one flat colour. Surplus placeholders
	 * are left as written rather than throwing, so a mistyped config line is legible instead of fatal.
	 */
	private static Component format(final String template, final Component... args) {
		MutableComponent out = Component.empty().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
		int next = 0;
		int from = 0;

		while (next < args.length) {
			int at = template.indexOf("%s", from);

			if (at < 0) {
				break;
			}

			out.append(Component.literal(template.substring(from, at)));
			out.append(args[next++]);
			from = at + 2;
		}

		out.append(Component.literal(template.substring(from)));
		return out;
	}
}
