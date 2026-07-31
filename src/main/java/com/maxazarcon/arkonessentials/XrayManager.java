package com.maxazarcon.arkonessentials;

import com.maxazarcon.arkonessentials.mixin.EntityAccessor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Outlines every player, for one viewer only.
 *
 * <p>Surveillance without entry: the glow renders through blocks, so an admin can see who is inside a
 * base without going in. The point is that it is <strong>one-way</strong> — the people being watched
 * see nothing, and nobody else's view changes.
 *
 * <p>That one-way property is the whole difficulty. Glowing is bit 6 of the entity's shared-flags byte,
 * which is synchronised state broadcast to <em>everyone</em> tracking that entity — setting it the
 * normal way would light the target up for the whole server. So nothing on the entity is touched:
 * instead each viewer is sent a hand-built {@link ClientboundSetEntityDataPacket} carrying a flags byte
 * with the glow bit forced on, and the real byte on the server stays untouched.
 *
 * <p><strong>Resent on a timer, deliberately.</strong> Any genuine data change — sneaking, sprinting,
 * catching fire — makes the server broadcast the true byte and overwrite our spoofed one, so the glow
 * would flicker out. Re-sending is what holds it. The interval trades network chatter against how long
 * a dropped outline stays dropped.
 *
 * <p>Memory only. Being able to see through walls is not something anyone should still have after a
 * restart without asking for it again.
 */
public final class XrayManager {
	private static final Set<UUID> VIEWERS = ConcurrentHashMap.newKeySet();

	/** Ticks between refreshes. One second is frequent enough that a flicker is barely visible. */
	private static final int REFRESH_TICKS = 20;

	/** Bit 6 of the shared-flags byte. */
	private static final int GLOWING_BIT = 6;

	private XrayManager() {
	}

	public static boolean isViewing(final ServerPlayer player) {
		return VIEWERS.contains(player.getUUID());
	}

	public static void onDisconnect(final ServerPlayer player) {
		VIEWERS.remove(player.getUUID());
	}

	/**
	 * Turns the outlines on or off.
	 *
	 * @return true if they are now on
	 */
	public static boolean toggle(final ServerPlayer player) {
		if (VIEWERS.remove(player.getUUID())) {
			restore(player);
			return false;
		}

		VIEWERS.add(player.getUUID());
		return true;
	}

	public static void tick(final MinecraftServer server) {
		if (VIEWERS.isEmpty() || server.getTickCount() % REFRESH_TICKS != 0) {
			return;
		}

		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (!VIEWERS.contains(viewer.getUUID())) {
				continue;
			}

			for (ServerPlayer target : server.getPlayerList().getPlayers()) {
				// Not yourself: your own outline is drawn around the camera and is just a nuisance. And
				// not anyone you could not otherwise see, so xray never becomes a vanish detector.
				if (target == viewer || !AdminManager.canSee(viewer, target)) {
					continue;
				}

				viewer.connection.send(glowPacket(target, true));
			}
		}
	}

	/** Puts the truth back, so the outlines go out immediately rather than at the next data change. */
	private static void restore(final ServerPlayer viewer) {
		MinecraftServer server = viewer.level().getServer();

		if (server == null) {
			return;
		}

		for (ServerPlayer target : server.getPlayerList().getPlayers()) {
			if (target != viewer) {
				viewer.connection.send(glowPacket(target, false));
			}
		}
	}

	/**
	 * One entity-data packet carrying {@code target}'s flags byte, with the glow bit set or cleared.
	 *
	 * <p>Built from the entity's <em>current</em> byte rather than from zero, so everything else the byte
	 * carries — sneaking, sprinting, swimming, invisibility — is preserved. Sending a bare glow bit would
	 * make watched players stand up out of their crouch on the viewer's screen.
	 */
	private static ClientboundSetEntityDataPacket glowPacket(final ServerPlayer target, final boolean glowing) {
		byte flags = target.getEntityData().get(EntityAccessor.arkonessentials$sharedFlags());
		byte spoofed = (byte) (glowing ? flags | 1 << GLOWING_BIT : flags & ~(1 << GLOWING_BIT));

		return new ClientboundSetEntityDataPacket(
			target.getId(),
			List.of(SynchedEntityData.DataValue.create(EntityAccessor.arkonessentials$sharedFlags(), spoofed))
		);
	}
}
