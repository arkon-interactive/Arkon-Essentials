package com.maxazarcon.arkonessentials.client;

import com.maxazarcon.arkonessentials.AdminState;
import com.maxazarcon.arkonessentials.AdminStatePayload;
import com.maxazarcon.arkonessentials.ArkonEssentials;
import com.maxazarcon.arkonessentials.HandshakePayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The on-screen indicator. Entirely optional: the server never depends on this being installed, and a
 * player without it just loses the labels, not the functionality.
 *
 * <p>Its appearance is all local preference — see {@link HudConfig}, which Mod Menu edits.
 *
 * <p>This half is <strong>display only</strong>. It never decides anything: every value below arrives
 * from the server, and nothing here is sent back or trusted as authoritative. A player editing their
 * client config can change how the labels look and nothing else.
 */
public class ArkonEssentialsClient implements ClientModInitializer {
	/** Vertical gap between stacked labels. */
	private static final int LINE_GAP = 2;

	/**
	 * The last state the server told us about.
	 *
	 * <p>Static because there is exactly one local player and one HUD. Written only inside
	 * {@code context.client().execute(...)} — payloads arrive on the netty thread, and touching these
	 * from there would race the render thread reading them.
	 */
	private static AdminState state = AdminState.NONE;

	/** Flags that stack under the state label. Independent of it and of each other. */
	private static boolean flightActive;

	private static boolean afk;
	private static boolean appearingOffline;

	/** One label ready to draw, resolved from the state and the player's settings. */
	private record Line(String text, int color) {
	}

	@Override
	public void onInitializeClient() {
		// Before the first render, so the HUD never draws a frame with unresolved defaults.
		HudConfig.load();

		// The channel this receiver registers on is what the server's canSend check sees. It carries the
		// protocol version, so registering here is also how this client declares which packet shape it
		// understands — a server on a different version simply never sends to it.
		ClientPlayNetworking.registerGlobalReceiver(
			AdminStatePayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				state = payload.state();
				flightActive = payload.flightActive();
				afk = payload.afk();
				appearingOffline = payload.appearingOffline();
			})
		);

		// Announce ourselves so a version-mismatched server can say so in chat. Gated on canSend: a server
		// without the mod, or one too old to know this channel, has no receiver and sending would be
		// throwing packets into the dark.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (ClientPlayNetworking.canSend(HandshakePayload.TYPE)) {
				ClientPlayNetworking.send(new HandshakePayload(ArkonEssentials.PROTOCOL_VERSION, ArkonEssentials.VERSION));
			}
		});

		// Without this the indicator would linger after leaving a server.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			state = AdminState.NONE;
			flightActive = false;
			afk = false;
			appearingOffline = false;
		});

		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, "indicator"),
			(graphics, deltaTracker) -> render(graphics)
		);
	}

	private static void render(final GuiGraphicsExtractor graphics) {
		HudConfig config = HudConfig.get();

		if (!config.enabled) {
			return;
		}

		List<Line> lines = new ArrayList<>(2);

		if (state != AdminState.NONE) {
			HudConfig.Indicator indicator = config.forState(state);

			if (indicator.shown) {
				lines.add(new Line(state.label(), indicator.color(state.color())));
			}
		}

		// Everything below sits under the active mode. Each is a flag rather than a state, so any
		// combination can be on at once and the block simply grows.
		if (appearingOffline && config.offline.shown) {
			lines.add(new Line(HudConfig.OFFLINE_LABEL, config.offline.color(HudConfig.OFFLINE_COLOR)));
		}

		if (afk && config.afk.shown) {
			lines.add(new Line(HudConfig.AFK_LABEL, config.afk.color(HudConfig.AFK_COLOR)));
		}

		// Flight implies a mode server-side, but the standalone branch is kept so a stale sync can never
		// draw at a weird offset.
		if (flightActive && config.flight.shown) {
			lines.add(new Line(HudConfig.FLIGHT_LABEL, config.flight.color(HudConfig.FLIGHT_COLOR)));
		}

		if (lines.isEmpty()) {
			return;
		}

		Font font = Minecraft.getInstance().font;
		float scale = config.scalePercent / 100.0F;

		var pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(scale, scale);

		// Laid out in the scaled coordinate space rather than the screen's. That keeps the offsets in the
		// units the sliders show, and keeps a corner-anchored block the same distance from its corner at
		// every scale — measuring in screen pixels would drift as the scale changed.
		int width = Math.round(graphics.guiWidth() / scale);
		int height = Math.round(graphics.guiHeight() / scale);

		int lineHeight = font.lineHeight + LINE_GAP;
		int blockHeight = lines.size() * lineHeight - LINE_GAP;
		int y = config.anchor.bottom() ? height - config.offsetY - blockHeight : config.offsetY;

		for (Line line : lines) {
			// Each line is measured on its own, so a right-anchored block has a flush right edge rather
			// than a ragged one inherited from the longest label.
			int x = config.anchor.right() ? width - config.offsetX - font.width(line.text()) : config.offsetX;

			graphics.text(font, Component.literal(line.text()), x, y, line.color(), config.textShadow);
			y += lineHeight;
		}

		pose.popMatrix();
	}
}
