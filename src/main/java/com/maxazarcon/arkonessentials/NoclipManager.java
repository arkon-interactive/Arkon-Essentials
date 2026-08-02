package com.maxazarcon.arkonessentials;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;

/**
 * Free movement through blocks, in two shapes.
 *
 * <p><strong>Collision for the player you are controlling is decided on your own machine.</strong>
 * {@code Player.tick} runs {@code noPhysics = isSpectator()} in code shared with the client, so
 * {@code LocalPlayer} recomputes it every tick from your game mode. That is why the first version of
 * this was spectator and nothing else: a plain server has no lever on it. Creative flight collides like
 * anything else.
 *
 * <p>But spectator cannot build, which is what makes it useless to the person who actually wants noclip.
 * So there are now two modes, chosen per player from whether they can receive {@link NoclipPayload}:
 *
 * <ul>
 *   <li><strong>Phase</strong> — the client has our jar. The server tells it to hold {@code noPhysics}
 *       open, and {@code PlayerMixin} does the same to the server-side copy so the movement checks
 *       accept positions inside terrain. Game mode is <em>untouched</em>, so Build Mode keeps its
 *       creative inventory and its reach and you can place blocks from inside a wall.</li>
 *   <li><strong>Spectator</strong> — a vanilla client, where nothing else is possible. Unchanged from
 *       before, including the costs: no interaction, no hotbar, invisible to everyone.</li>
 * </ul>
 *
 * <p>Both the return game mode and the phasing set are held <strong>in memory</strong>. A crash mid-noclip
 * leaves a spectator (recoverable with {@code /gamemode}) or simply clears the phase — cheaper than a
 * schema field for something that lasts seconds.
 *
 * <p>This class is loaded on both sides. The client populates {@link #PHASING} with its own UUID from the
 * payload, which is what lets one mixin in the shared source set serve both.
 */
public final class NoclipManager {
	/**
	 * Everyone currently phasing, by UUID.
	 *
	 * <p>Keyed by UUID rather than holding players because the client half has to write to it too, and a
	 * client's {@code LocalPlayer} is a different object from the server's {@code ServerPlayer} even in
	 * single player. On a client this holds at most one entry — its own.
	 */
	private static final Set<UUID> PHASING = ConcurrentHashMap.newKeySet();

	/** Players in the spectator fallback, mapped to the game mode to put them back into. */
	private static final Map<UUID, GameType> RETURN_MODES = new ConcurrentHashMap<>();

	/**
	 * Set while this class is changing game mode, so {@code ServerPlayerMixin} does not record spectator
	 * as the player's "last non-creative mode" and send {@code /admin off} there afterwards.
	 *
	 * <p>A plain boolean rather than a thread local: game mode changes happen on the server thread, and
	 * every write here is immediately followed by the read that clears it.
	 */
	private static boolean suppressModeTracking;

	/** How a toggle resolved, so the command can say which of the two the player got. */
	public enum Result {
		OFF,
		PHASE,
		SPECTATOR
	}

	private NoclipManager() {
	}

	/** Read by {@code PlayerMixin} on both sides, hence the bare UUID. */
	public static boolean isPhasing(final UUID id) {
		return PHASING.contains(id);
	}

	public static boolean isNoclipping(final ServerPlayer player) {
		return PHASING.contains(player.getUUID()) || RETURN_MODES.containsKey(player.getUUID());
	}

	/**
	 * The client's entry point, driven by {@link NoclipPayload}.
	 *
	 * <p>The client is being <em>told</em>, not deciding: this only ever runs in response to a packet the
	 * server chose to send, and the server keeps its own copy of the flag regardless.
	 */
	public static void setPhasing(final UUID id, final boolean phasing) {
		if (phasing) {
			PHASING.add(id);
		} else {
			PHASING.remove(id);
		}
	}

	/** Whether the game mode currently being set should be remembered. See {@link #suppressModeTracking}. */
	public static boolean trackingSuppressed() {
		return suppressModeTracking;
	}

	/** Toggles noclip, picking whichever shape this player's client can support. */
	public static Result toggle(final ServerPlayer player) {
		if (isNoclipping(player)) {
			restore(player);
			return Result.OFF;
		}

		if (!ServerPlayNetworking.canSend(player, NoclipPayload.TYPE)) {
			RETURN_MODES.put(player.getUUID(), player.gameMode());
			setModeQuietly(player, GameType.SPECTATOR);
			return Result.SPECTATOR;
		}

		PHASING.add(player.getUUID());
		ServerPlayNetworking.send(player, new NoclipPayload(true));

		// Set here as well as in the mixin purely so the very next movement packet is already accepted;
		// the mixin is what keeps it true, since Player.tick would clear it a tick later.
		player.noPhysics = true;
		holdAloft(player);
		return Result.PHASE;
	}

	/**
	 * Puts the player back, whichever shape they were in.
	 *
	 * <p>Also the disconnect hook: leaving someone in spectator because they logged out mid-noclip would
	 * be a nasty surprise on their next login, and neither flag survives the session.
	 */
	public static void restore(final ServerPlayer player) {
		if (PHASING.remove(player.getUUID())) {
			player.noPhysics = false;

			if (ServerPlayNetworking.canSend(player, NoclipPayload.TYPE)) {
				ServerPlayNetworking.send(player, new NoclipPayload(false));
			}

			// Puts flight back to whatever the player's state and preference actually earn — which may be
			// nothing, and refreshFlight books the soft landing in that case.
			AdminManager.refreshFlight(player);

			// Nothing is needed to get them out of a wall: LocalPlayer.aiStep runs moveTowardsClosestSpace
			// whenever noPhysics is false, which is vanilla's own escape hatch for exactly this.
		}

		GameType previous = RETURN_MODES.remove(player.getUUID());

		if (previous != null) {
			setModeQuietly(player, previous);
		}
	}

	/**
	 * Keeps phasing players in the air.
	 *
	 * <p>Without collision there is no floor, so a phasing player who is not flying sinks through the
	 * world and out the bottom of it. Flight is client-authoritative — the client can clear it at any time
	 * by double-tapping jump — so this re-asserts it rather than trusting the grant from
	 * {@link #holdAloft}. Only when it has actually drifted off, so this is not a packet every tick.
	 */
	public static void tick(final MinecraftServer server) {
		if (PHASING.isEmpty()) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (PHASING.contains(player.getUUID()) && !player.getAbilities().flying) {
				holdAloft(player);
			}
		}
	}

	private static void holdAloft(final ServerPlayer player) {
		Abilities abilities = player.getAbilities();
		abilities.mayfly = true;
		abilities.flying = true;
		player.onUpdateAbilities();
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
