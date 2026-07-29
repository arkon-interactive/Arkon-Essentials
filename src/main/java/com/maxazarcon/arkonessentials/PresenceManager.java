package com.maxazarcon.arkonessentials;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Faked comings and goings — {@code /fakeleave} and {@code /fakejoin}.
 *
 * <p>The announcements are built exactly as vanilla builds its own, from the same translation keys
 * ({@code multiplayer.player.left} / {@code joined}) and the same yellow styling. That is deliberate:
 * anything reworded by a resource pack, a language setting or a chat mod is reworded here too, so the
 * fake is indistinguishable from the real thing rather than merely similar to the English default.
 *
 * <p>Like AFK, the flag is <strong>memory only</strong>. It records that a departure has been
 * announced, which is a fact about this session; a player who genuinely disconnects and returns while
 * still in a vanished state stays unannounced anyway, because vanish already suppresses join and leave
 * messages.
 */
public final class PresenceManager {
	private static final Set<UUID> APPEARING_OFFLINE = ConcurrentHashMap.newKeySet();

	private PresenceManager() {
	}

	public static boolean isAppearingOffline(final ServerPlayer player) {
		return APPEARING_OFFLINE.contains(player.getUUID());
	}

	public static void onDisconnect(final ServerPlayer player) {
		APPEARING_OFFLINE.remove(player.getUUID());
	}

	/**
	 * Announces a departure and puts the player into {@code state}.
	 *
	 * <p>{@code state} need not conceal anyone — {@link AdminState#NONE} is a legitimate choice, leaving
	 * the player visible while the server insists they left. The appearing-offline flag is what drives
	 * the HUD label, and it is independent of the state, so every combination reads correctly.
	 */
	public static void fakeLeave(final ServerPlayer player, final AdminState state) {
		AdminManager.setState(player, state);

		// Cleared before the flag goes up, so the "no longer AFK" line is suppressed along with everything
		// else. Someone who was AFK and then fakes a departure must not have a return announcement fire
		// later and contradict it.
		if (AfkManager.isAfk(player)) {
			AfkManager.clear(player);
		}

		APPEARING_OFFLINE.add(player.getUUID());

		broadcast(player, Component.translatable("multiplayer.player.left", player.getDisplayName()).withStyle(ChatFormatting.YELLOW));
		AdminManager.syncTo(player);
	}

	/**
	 * Announces an arrival.
	 *
	 * <p>Deliberately does not touch the player's state — you asked for the broadcast and nothing else,
	 * so coming off duty stays a separate, explicit act. It does clear the appearing-offline flag,
	 * since the label claims something the announcement has just contradicted.
	 */
	public static void fakeJoin(final ServerPlayer player) {
		APPEARING_OFFLINE.remove(player.getUUID());

		broadcast(player, Component.translatable("multiplayer.player.joined", player.getDisplayName()).withStyle(ChatFormatting.YELLOW));
		AdminManager.syncTo(player);
	}

	private static void broadcast(final ServerPlayer player, final Component message) {
		MinecraftServer server = player.level().getServer();

		if (server != null) {
			server.getPlayerList().broadcastSystemMessage(message, false);
		}
	}
}
