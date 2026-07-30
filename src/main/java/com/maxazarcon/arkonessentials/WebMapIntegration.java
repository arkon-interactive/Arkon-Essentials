package com.maxazarcon.arkonessentials;

import de.bluecolored.bluemap.api.BlueMapAPI;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import xyz.jpenilla.squaremap.api.SquaremapProvider;

/**
 * Hides vanished players on server-side web maps.
 *
 * <p>This is the one place vanish cannot reach on its own. Everything else works by controlling what a
 * client is told — the entity is never tracked, the tab-list entry is removed, the join message is
 * swallowed. Web maps do not go through any of that: they read the server's {@code PlayerList} directly
 * and publish positions over HTTP, so a ghost would sit on the map for anyone with the URL.
 *
 * <p>Both integrations are <strong>soft</strong>. The API artifacts are {@code compileOnly}, and every
 * call is behind an {@code isModLoaded} check, so the classes below are only ever loaded on a server
 * that actually has the map installed. A server with neither never touches them.
 *
 * <p><strong>Dynmap is deliberately absent.</strong> It has no Fabric build for 26.2, so there is
 * nothing to compile or test against; adding an untested integration would be worse than the honest gap
 * documented in the README.
 */
public final class WebMapIntegration {
	// Resolved once. Mod sets do not change while the server is running.
	private static final boolean BLUEMAP = FabricLoader.getInstance().isModLoaded("bluemap");
	private static final boolean SQUAREMAP = FabricLoader.getInstance().isModLoaded("squaremap");

	/** Logged once per map so a broken integration is visible without spamming every state change. */
	private static boolean blueMapFailed;

	private static boolean squaremapFailed;

	private WebMapIntegration() {
	}

	/** Whether any web map is present, for {@code /arkon} to report something useful. */
	public static boolean anyPresent() {
		return BLUEMAP || SQUAREMAP;
	}

	/**
	 * Says at startup which maps will be kept in step.
	 *
	 * <p>Worth a log line because the failure this guards against is invisible: with no message, an
	 * operator running a web map has no way to tell whether vanish covers it or whether staff have been
	 * sitting on a public map the whole time.
	 */
	public static void logStatus() {
		if (!anyPresent()) {
			return;
		}

		ArkonEssentials.LOGGER.info(
			"Web map integration active for {}{}{}. Vanished players will be hidden there while hideFromWebMaps is on.",
			BLUEMAP ? "BlueMap" : "",
			BLUEMAP && SQUAREMAP ? " and " : "",
			SQUAREMAP ? "squaremap" : ""
		);
	}

	/**
	 * Pushes this player's current visibility to every installed map.
	 *
	 * <p>Safe to call whenever anything that affects concealment changes — state, appearing-offline, a
	 * config edit, or joining. It computes the answer rather than being told it, so callers cannot get
	 * the direction wrong.
	 */
	public static void refresh(final ServerPlayer player) {
		if (!anyPresent()) {
			return;
		}

		// Concealed covers both halves of vanish: a hidden state, and a faked departure that may leave the
		// state as NONE. The config switch turns the whole thing off by making everyone visible again —
		// note it must *restore* visibility, not merely stop hiding, or a player hidden before the setting
		// changed would stay stuck on the map.
		boolean concealed = AdminManager.getState(player).hiddenFromPlayers()
			|| PresenceManager.isAppearingOffline(player);
		boolean visible = !concealed || !EssentialsConfig.get().hideFromWebMaps;

		if (BLUEMAP) {
			applyBlueMap(player.getUUID(), visible);
		}

		if (SQUAREMAP) {
			applySquaremap(player.getUUID(), visible);
		}
	}

	/**
	 * BlueMap. {@code getInstance()} is empty until BlueMap finishes starting, and a player who joins
	 * before that is covered by the refresh on the next state change.
	 */
	private static void applyBlueMap(final UUID id, final boolean visible) {
		try {
			BlueMapAPI.getInstance().ifPresent(api -> api.getWebApp().setPlayerVisibility(id, visible));
		} catch (Throwable e) {
			if (!blueMapFailed) {
				blueMapFailed = true;
				ArkonEssentials.LOGGER.error("BlueMap integration failed; vanished players may show on its map.", e);
			}
		}
	}

	/** squaremap. Its API talks in "hidden", the inverse of everything else here. */
	private static void applySquaremap(final UUID id, final boolean visible) {
		try {
			SquaremapProvider.get().playerManager().hidden(id, !visible);
		} catch (Throwable e) {
			if (!squaremapFailed) {
				squaremapFailed = true;
				ArkonEssentials.LOGGER.error("squaremap integration failed; vanished players may show on its map.", e);
			}
		}
	}
}
