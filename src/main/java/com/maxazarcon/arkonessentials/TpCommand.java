package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /tp} and {@code /back} — everyday teleporting, separate from the admin suite.
 *
 * <p>Distinct from {@code /admin tp} on purpose: this changes nobody's state and writes to its own
 * stored location, so a player's {@code /back} is never stamped over by staff teleporting around. The
 * two return points coexist in the saved data as {@code back_point} and {@code return_point}.
 *
 * <p>Vanilla's own {@code /tp} alias is suppressed so this can own the name (see
 * {@code TeleportCommandMixin}); {@code /teleport} is untouched and keeps every vanilla form, so
 * nothing is lost — selectors, {@code facing} and rotation all still live there.
 */
public final class TpCommand {
	/**
	 * How far {@code /tpthere} and {@code /tpall} will look for a block.
	 *
	 * <p>Far beyond interaction reach on purpose — the point is to point at somewhere across the map and
	 * send people there. Bounded rather than infinite so looking at the horizon fails cleanly instead of
	 * clipping through the whole world.
	 */
	private static final double LOOK_RANGE = 256.0;

	private TpCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("tp")
				.requires(source -> AdminPermissions.mayUseTeleport(source))
				// Coordinate forms are registered FIRST, and the order is load-bearing. Brigadier tries
				// argument children in insertion order and commits to the first that parses; a player
				// argument accepts any bare word, so "/tp 100 -200" would be read as two player names and
				// fail with "No player was found" rather than ever reaching the column form. Numbers cannot
				// masquerade as coordinates the other way round, so putting these first is unambiguous.
				.then(
					Commands.argument("position", Vec3Argument.vec3())
						.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_COORDS))
						.executes(context -> toPosition(
							context.getSource(),
							Vec3Argument.getCoordinates(context, "position").getPosition(context.getSource())
						))
				)
				.then(
					// Two coordinates, letting the command choose the height. The common case when reading
					// coordinates off a map or a report, where the height is the part you do not know.
					Commands.argument("column", Vec2Argument.vec2())
						.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_COORDS))
						.executes(context -> toColumn(
							context.getSource(),
							context.getSource().getPlayerOrException(),
							Vec2Argument.getVec2(context, "column")
						))
				)
				.then(
					Commands.argument("destination", EntityArgument.player())
						.requires(source -> AdminPermissions.check(source, AdminPermissions.TP))
						.executes(context -> toPlayer(
							context.getSource(),
							context.getSource().getPlayerOrException(),
							EntityArgument.getPlayer(context, "destination")
						))
						// Same ordering rule as the top level: the coordinate form must come before the
						// player form, or "/tp Steve 100 -200" reads "100" as a second player name.
						.then(
							Commands.argument("column", Vec2Argument.vec2())
								.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_OTHERS))
								.executes(context -> toColumn(
									context.getSource(),
									EntityArgument.getPlayer(context, "destination"),
									Vec2Argument.getVec2(context, "column")
								))
						)
						.then(
							Commands.argument("target", EntityArgument.player())
								.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_OTHERS))
								// Reads as "/tp <who> <to where>": the first argument moves, the second stays.
								.executes(context -> toPlayer(
									context.getSource(),
									EntityArgument.getPlayer(context, "destination"),
									EntityArgument.getPlayer(context, "target")
								))
						)
				)
		);

		dispatcher.register(
			Commands.literal("tphere")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_HERE))
				.then(
					Commands.argument("player", EntityArgument.player())
						.executes(context -> toPlayer(
							context.getSource(),
							EntityArgument.getPlayer(context, "player"),
							context.getSource().getPlayerOrException()
						))
				)
		);

		dispatcher.register(
			Commands.literal("tpthere")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_THERE))
				.then(
					Commands.argument("player", EntityArgument.player())
						.executes(context -> toLookedAt(
							context.getSource(),
							List.of(EntityArgument.getPlayer(context, "player"))
						))
				)
		);

		dispatcher.register(
			Commands.literal("tpall")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_ALL))
				.executes(context -> {
					ServerPlayer caller = context.getSource().getPlayerOrException();

					// Everyone but the caller — they are already standing where the others are going.
					List<ServerPlayer> others = new ArrayList<>(context.getSource().getServer().getPlayerList().getPlayers());
					others.remove(caller);

					return toLookedAt(context.getSource(), others);
				})
		);

		dispatcher.register(
			Commands.literal("back")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_BACK))
				.executes(context -> back(context.getSource()))
		);

		dispatcher.register(
			Commands.literal("top")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.TP_TOP))
				.executes(context -> top(context.getSource()))
		);
	}

	/** Sends the player to the highest spot in their own column that they can actually stand in. */
	private static int top(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = source.getLevel();

		int x = Mth.floor(player.getX());
		int z = Mth.floor(player.getZ());
		OptionalInt landing = findLanding(level, player, x, z);

		if (landing.isEmpty()) {
			source.sendFailure(Component.literal("Nothing to stand on in this column."));
			return 0;
		}

		int y = landing.getAsInt();
		recordBackPoint(player);
		player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot(), false);

		source.sendSuccess(() -> Component.literal("Moved you to y " + y + ".").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	/**
	 * Walks down the column for the highest block a player could stand on top of.
	 *
	 * <p>Not a heightmap lookup, because the two things that make this command non-trivial are exactly
	 * what a heightmap ignores:
	 *
	 * <ul>
	 *   <li><strong>Bedrock is skipped.</strong> The Nether's ceiling is bedrock at the top of the
	 *       column with open air above it, so the naive answer is "on the roof" — which is both useless
	 *       and somewhere players are not meant to be.
	 *   <li><strong>The space above must actually fit the player.</strong> Tested with the player's real
	 *       bounding box rather than by counting two air blocks, so slabs, trapdoors and any other
	 *       partial shape are judged by whether they collide, not by what kind of block they are. This
	 *       is also what stops the search settling in the solid rock under the Nether roof.
	 * </ul>
	 *
	 * @return the y to stand at, or empty if the whole column is unusable
	 */
	private static OptionalInt findLanding(final ServerLevel level, final ServerPlayer player, final int x, final int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int y = level.getMaxY(); y > level.getMinY(); y--) {
			BlockState ground = level.getBlockState(cursor.set(x, y, z));

			if (!ground.blocksMotion() || ground.is(Blocks.BEDROCK)) {
				continue;
			}

			if (fits(level, player, x, y + 1, z)) {
				return OptionalInt.of(y + 1);
			}
		}

		return OptionalInt.empty();
	}

	private static boolean fits(final ServerLevel level, final ServerPlayer player, final int x, final int y, final int z) {
		AABB target = player.getBoundingBox()
			.move(x + 0.5 - player.getX(), y - player.getY(), z + 0.5 - player.getZ());

		if (!level.noCollision(player, target)) {
			return false;
		}

		// Lava does not block motion, so the collision test alone would happily drop someone into a lava
		// lake sitting on top of the terrain — the likeliest way this command could hurt a player.
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int offset = 0; offset <= 1; offset++) {
			if (level.getFluidState(cursor.set(x, y + offset, z)).is(FluidTags.LAVA)) {
				return false;
			}
		}

		return true;
	}

	private static int toPlayer(final CommandSourceStack source, final ServerPlayer moved, final ServerPlayer destination)
		throws CommandSyntaxException {
		if (!(destination.level() instanceof ServerLevel level)) {
			source.sendFailure(Component.literal("That player is not somewhere you can teleport to."));
			return 0;
		}

		if (immune(source, moved)) {
			return 0;
		}

		recordBackPoint(moved);

		// Keeps the traveller's own facing rather than adopting the destination's, matching vanilla.
		moved.teleportTo(level, destination.getX(), destination.getY(), destination.getZ(), Set.of(), moved.getYRot(), moved.getXRot(), false);

		if (moved == source.getEntity()) {
			source.sendSuccess(() -> Component.literal("Teleported to " + destination.getGameProfile().name() + ".").withStyle(ChatFormatting.GREEN), false);
		} else {
			source.sendSuccess(
				() -> Component.literal(
					"Teleported " + moved.getGameProfile().name() + " to " + destination.getGameProfile().name() + "."
				).withStyle(ChatFormatting.GREEN),
				true
			);
		}

		return 1;
	}

	private static int toPosition(final CommandSourceStack source, final Vec3 position) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		recordBackPoint(player);

		player.teleportTo(source.getLevel(), position.x, position.y, position.z, Set.of(), player.getYRot(), player.getXRot(), false);
		source.sendSuccess(
			() -> Component.literal(String.format("Teleported to %.1f, %.1f, %.1f.", position.x, position.y, position.z))
				.withStyle(ChatFormatting.GREEN),
			false
		);
		return 1;
	}

	/**
	 * Teleports to an x/z column, choosing the height itself.
	 *
	 * <p>Shares {@link #findLanding} with {@code /top} rather than reading a heightmap, so the same
	 * guarantees apply: never the Nether's bedrock ceiling, never a spot without headroom, never lava.
	 * A heightmap answers "what is the top block here", which is a different and less useful question.
	 */
	private static int toColumn(final CommandSourceStack source, final ServerPlayer moved, final Vec2 column) {
		ServerLevel level = source.getLevel();

		int x = Mth.floor(column.x);
		int z = Mth.floor(column.y);

		if (immune(source, moved)) {
			return 0;
		}

		OptionalInt landing = findLanding(level, moved, x, z);

		if (landing.isEmpty()) {
			source.sendFailure(Component.literal("Nowhere to stand at " + x + ", " + z + "."));
			return 0;
		}

		int y = landing.getAsInt();
		recordBackPoint(moved);
		moved.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), moved.getYRot(), moved.getXRot(), false);

		source.sendSuccess(
			() -> Component.literal("Teleported " + moved.getGameProfile().name() + " to " + x + ", " + y + ", " + z + ".")
				.withStyle(ChatFormatting.GREEN),
			false
		);
		return 1;
	}

	/**
	 * Sends players to the block the caller is looking at.
	 *
	 * <p>Backs {@code /tpthere} and {@code /tpall}. Requires an actual block — the ray is clipped against
	 * collision shapes, so looking at the sky, or past the reach limit, fails with a reason rather than
	 * teleporting anyone somewhere arbitrary.
	 */
	private static int toLookedAt(final CommandSourceStack source, final List<ServerPlayer> targets) throws CommandSyntaxException {
		ServerPlayer caller = source.getPlayerOrException();
		ServerLevel level = source.getLevel();

		Vec3 eye = caller.getEyePosition();
		Vec3 end = eye.add(caller.getViewVector(1.0F).scale(LOOK_RANGE));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caller));

		if (hit.getType() != HitResult.Type.BLOCK) {
			source.sendFailure(Component.literal("You are not looking at a solid block."));
			return 0;
		}

		BlockPos ground = hit.getBlockPos();
		int x = ground.getX();
		int z = ground.getZ();
		int y = ground.getY() + 1;

		int moved = 0;

		for (ServerPlayer target : targets) {
			// Reported per player rather than aborting the whole command: with /tpall, one immune player
			// should not strand everyone else.
			if (immune(source, target)) {
				continue;
			}

			// Checked per player because the fit depends on their own bounding box.
			if (!fits(level, target, x, y, z)) {
				source.sendFailure(Component.literal("No room for " + target.getGameProfile().name() + " on that block."));
				continue;
			}

			recordBackPoint(target);
			target.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), target.getYRot(), target.getXRot(), false);
			moved++;
		}

		if (moved == 0) {
			return 0;
		}

		int count = moved;
		source.sendSuccess(
			() -> Component.literal("Teleported " + count + " player" + (count == 1 ? "" : "s") + " to " + x + ", " + y + ", " + z + ".")
				.withStyle(ChatFormatting.GREEN),
			true
		);
		return count;
	}

	/**
	 * Whether {@code target} refuses to be moved by someone else, reporting why.
	 *
	 * <p>Self-teleports are always allowed: the immunity is about other people moving you, and blocking
	 * your own {@code /tp} would make holding the node a punishment.
	 */
	private static boolean immune(final CommandSourceStack source, final ServerPlayer target) {
		if (source.getEntity() == target || !AdminPermissions.isTeleportImmune(target)) {
			return false;
		}

		source.sendFailure(Component.literal(target.getGameProfile().name() + " cannot be teleported by others."));
		return true;
	}

	/**
	 * Returns the player to wherever they last teleported from, or last died.
	 *
	 * <p>Swaps rather than clears, so running it twice bounces between two points — the same behaviour
	 * as {@code /admin back}, and the reason it is useful when checking something and coming straight
	 * back.
	 */
	private static int back(final CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		MinecraftServer server = source.getServer();
		EssentialsData data = EssentialsData.get(server);

		Optional<SavedLocation> stored = data.getBackPoint(player.getUUID());

		if (stored.isEmpty()) {
			source.sendFailure(Component.literal("Nowhere to go back to yet."));
			return 0;
		}

		SavedLocation here = SavedLocation.capture(player);

		if (!stored.get().teleport(player, server)) {
			source.sendFailure(Component.literal("That dimension no longer exists."));
			return 0;
		}

		data.setBackPoint(player.getUUID(), here);
		source.sendSuccess(() -> Component.literal("Sent you back.").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	/** Captured before the move, so {@code /back} returns to where the player actually was. */
	private static void recordBackPoint(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server != null) {
			EssentialsData.get(server).setBackPoint(player.getUUID(), SavedLocation.capture(player));
		}
	}
}
