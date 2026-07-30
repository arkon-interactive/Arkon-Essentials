package com.maxazarcon.arkonessentials;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Free movement through blocks, by way of spectator.
 *
 * <p><strong>Spectator is the only real noclip, and that is not a design choice.</strong>
 * {@code Player} recomputes {@code noPhysics = isSpectator()} every tick, in code shared with the
 * client — so {@code LocalPlayer} decides collision for the player you are controlling, from your game
 * mode, locally. A server cannot grant it: setting the flag server-side is overwritten on the next tick
 * and the client never consulted it anyway. Creative flight collides with blocks like everything else.
 *
 * <p>So the toggle swaps game mode and swaps back. The costs are spectator's own and cannot be
 * separated from it: no interaction, no hotbar, and nobody but other spectators can see you.
 *
 * <p>The mode to return to is held <strong>in memory</strong> and restored on toggle or disconnect. A
 * server crash mid-noclip would leave someone in spectator — recoverable with {@code /gamemode}, and
 * cheaper than a schema field for something that only ever lasts seconds.
 */
public final class NoclipManager {
	/** Player to the game mode they were in before noclip. Presence in this map <em>is</em> the flag. */
	private static final Map<UUID, GameType> RETURN_MODES = new ConcurrentHashMap<>();

	/**
	 * Set while this class is changing game mode, so {@code ServerPlayerMixin} does not record spectator
	 * as the player's "last non-creative mode" and send {@code /admin off} there afterwards.
	 *
	 * <p>A plain boolean rather than a thread local: game mode changes happen on the server thread, and
	 * every write here is immediately followed by the read that clears it.
	 */
	private static boolean suppressModeTracking;

	private NoclipManager() {
	}

	public static boolean isNoclipping(final ServerPlayer player) {
		return RETURN_MODES.containsKey(player.getUUID());
	}

	/** Whether the game mode currently being set should be remembered. See {@link #suppressModeTracking}. */
	public static boolean trackingSuppressed() {
		return suppressModeTracking;
	}

	/**
	 * Toggles noclip.
	 *
	 * @return true if the player is now noclipping
	 */
	public static boolean toggle(final ServerPlayer player) {
		if (isNoclipping(player)) {
			restore(player);
			return false;
		}

		RETURN_MODES.put(player.getUUID(), player.gameMode());
		setModeQuietly(player, GameType.SPECTATOR);
		return true;
	}

	/**
	 * Puts the player back, if they were noclipping.
	 *
	 * <p>Also the disconnect hook: leaving someone in spectator because they logged out mid-flight would
	 * be a nasty surprise on their next login, and the return mode does not survive the session.
	 */
	public static void restore(final ServerPlayer player) {
		GameType previous = RETURN_MODES.remove(player.getUUID());

		if (previous != null) {
			setModeQuietly(player, previous);
		}
	}

	private static void setModeQuietly(final ServerPlayer player, final GameType mode) {
		suppressModeTracking = true;

		try {
			player.setGameMode(mode);
		} finally {
			suppressModeTracking = false;
		}
	}
}
