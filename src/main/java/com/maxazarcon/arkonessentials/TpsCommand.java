package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.util.TimeUtil;

/**
 * {@code /tps} — a public, read-only view of server tick health.
 *
 * <p>Vanilla reports the same numbers through {@code /tick query}, but that entire tree is gated
 * behind op level 3 — reasonably, since it also holds {@code freeze}, {@code sprint} and
 * {@code rate}. This exposes only the readings, to everyone.
 */
public final class TpsCommand {
	/** Fraction of the target rate still considered healthy; below this the colour changes. */
	private static final float HEALTHY_FRACTION = 0.98F;
	private static final float STRAINED_FRACTION = 0.85F;

	private TpsCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		// Public by default: allowed unless a permissions mod revokes the node, so this still works on a
		// server with none installed.
		dispatcher.register(
			Commands.literal("tps")
				.requires(source -> AdminPermissions.checkPublic(source, AdminPermissions.TPS))
				.executes(context -> report(context.getSource()))
		);
	}

	private static int report(final CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		ServerTickRateManager tickRate = server.tickRateManager();

		// Read from the manager rather than assuming 20: /tick rate can change the target.
		float target = tickRate.tickrate();
		long averageNanos = server.getAverageTickTimeNanos();

		// Not simply a second divided by tick time. The server aims for `target` ticks a second and
		// sleeps out the remainder, so a healthy server should report the target, not some huge number
		// derived from how little work it had to do. Only once a tick overruns its budget does the
		// achievable rate drop below the target — which is exactly how vanilla decides "lagging".
		double achievable = averageNanos <= 0L ? target : (double) TimeUtil.NANOSECONDS_PER_SECOND / averageNanos;
		double tps = Math.min(target, achievable);
		double mspt = (double) averageNanos / TimeUtil.NANOSECONDS_PER_MILLISECOND;

		ChatFormatting colour;

		if (tps >= target * HEALTHY_FRACTION) {
			colour = ChatFormatting.GREEN;
		} else if (tps >= target * STRAINED_FRACTION) {
			colour = ChatFormatting.YELLOW;
		} else {
			colour = ChatFormatting.RED;
		}

		String summary = String.format(Locale.ROOT, "TPS %.1f / %.0f  —  MSPT %.1f", tps, target, mspt);
		Component feedback = Component.literal(summary).withStyle(colour);
		source.sendSuccess(() -> feedback, false);

		// Both states make the reading above misleading, so say so rather than quietly reporting a
		// number nobody can interpret.
		if (tickRate.isFrozen()) {
			source.sendSuccess(() -> Component.literal("Ticking is frozen.").withStyle(ChatFormatting.AQUA), false);
		} else if (tickRate.isSprinting()) {
			source.sendSuccess(() -> Component.literal("Server is sprinting.").withStyle(ChatFormatting.AQUA), false);
		}

		return (int) Math.round(tps);
	}
}
