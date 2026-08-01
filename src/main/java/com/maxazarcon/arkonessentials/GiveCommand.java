package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /give}, with the typing taken out.
 *
 * <pre>
 * /give &lt;item&gt;                    a configured amount, to yourself
 * /give &lt;item&gt; &lt;count&gt;            to yourself
 * /give &lt;player&gt; &lt;item&gt; [count]   the vanilla shape, still there
 * /giveall &lt;item&gt; [count]         to everyone online
 * </pre>
 *
 * <p>The item is matched loosely, so {@code /give cobble} finds {@code minecraft:cobblestone}. Vanilla
 * demands a full resource location and rejects anything else outright, which is most of what makes the
 * command tedious to type. Matching is by substring, not by edit distance — {@code diamnd} finds nothing,
 * because guessing at a typo is how a command hands someone the wrong item without saying so.
 *
 * <p><strong>Argument order is load-bearing, and vanilla's own registration has to go.</strong>
 * Vanilla's {@code /give} takes a player first, and a player argument accepts any bare word — so
 * {@code /give cobble} would be read as a player named "cobble" and fail before ever reaching an item.
 * Brigadier merges same-named literals and keeps the <em>existing</em> node's children ahead of new
 * ones, so this cannot be fixed by adding branches; {@code GiveCommandMixin} drops vanilla's
 * registration and the item branch is registered first here. Every vanilla form is reimplemented below,
 * so nothing is lost.
 */
public final class GiveCommand {
	private GiveCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("give")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.GIVE))
				// Item first. An item name that is not a real item falls through to the player branch,
				// but a player name would happily masquerade as an item argument if the order were
				// reversed — the failure only goes one way, so this order is the unambiguous one.
				//
				// IdentifierArgument rather than a plain word: word() rejects a colon, which would put
				// every namespaced id — every modded item on the server — out of reach. It is also a
				// vanilla argument type, so the command tree still serialises to a vanilla client; a
				// custom type would need registering on both sides and break exactly the players this
				// mod promises not to require anything of. The cost is that input must be lowercase,
				// which is what vanilla's own /give demands anyway.
				.then(
					Commands.argument("item", IdentifierArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(itemSuggestions(), builder))
						.executes(context -> giveTo(
							context.getSource(),
							List.of(context.getSource().getPlayerOrException()),
							IdentifierArgument.getId(context, "item").toString(),
							EssentialsConfig.get().giveDefaultCount
						))
						.then(
							Commands.argument("count", IntegerArgumentType.integer(1, 6400))
								.executes(context -> giveTo(
									context.getSource(),
									List.of(context.getSource().getPlayerOrException()),
									IdentifierArgument.getId(context, "item").toString(),
									IntegerArgumentType.getInteger(context, "count")
								))
						)
				)
				.then(
					Commands.argument("targets", EntityArgument.players())
						.requires(source -> AdminPermissions.check(source, AdminPermissions.GIVE_OTHERS))
						.then(
							Commands.argument("item", IdentifierArgument.id())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(itemSuggestions(), builder))
								.executes(context -> giveTo(
									context.getSource(),
									EntityArgument.getPlayers(context, "targets"),
									IdentifierArgument.getId(context, "item").toString(),
									EssentialsConfig.get().giveDefaultCount
								))
								.then(
									Commands.argument("count", IntegerArgumentType.integer(1, 6400))
										.executes(context -> giveTo(
											context.getSource(),
											EntityArgument.getPlayers(context, "targets"),
											IdentifierArgument.getId(context, "item").toString(),
											IntegerArgumentType.getInteger(context, "count")
										))
								)
						)
				)
		);

		LiteralArgumentBuilder<CommandSourceStack> giveAll = Commands.literal("giveall")
			.requires(source -> AdminPermissions.check(source, AdminPermissions.GIVE_ALL))
			.then(
				Commands.argument("item", IdentifierArgument.id())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(itemSuggestions(), builder))
					.executes(context -> giveTo(
						context.getSource(),
						context.getSource().getServer().getPlayerList().getPlayers(),
						IdentifierArgument.getId(context, "item").toString(),
						EssentialsConfig.get().giveDefaultCount
					))
					.then(
						Commands.argument("count", IntegerArgumentType.integer(1, 6400))
							.executes(context -> giveTo(
								context.getSource(),
								context.getSource().getServer().getPlayerList().getPlayers(),
								IdentifierArgument.getId(context, "item").toString(),
								IntegerArgumentType.getInteger(context, "count")
							))
					)
			);

		dispatcher.register(giveAll);
	}

	/**
	 * Resolves a loose item name.
	 *
	 * <p>Tried in order of how sure we can be: an exact id, then a path that starts with the query, then
	 * one that merely contains it. Within a tier the shortest path wins, which is what makes
	 * {@code cobble} land on {@code cobblestone} rather than {@code cobblestone_stairs} — the shortest
	 * match is almost always the plain form of the thing.
	 */
	public static Optional<Item> resolve(final String query) {
		String cleaned = query.toLowerCase(Locale.ROOT).replace(' ', '_');
		Identifier exact = Identifier.tryParse(cleaned.contains(":") ? cleaned : "minecraft:" + cleaned);

		if (exact != null) {
			Optional<Item> match = BuiltInRegistries.ITEM.getOptional(exact);

			if (match.isPresent()) {
				return match;
			}
		}

		String needle = cleaned.contains(":") ? cleaned.split(":", 2)[1] : cleaned;
		Item prefix = null;
		Item contains = null;

		for (Item item : BuiltInRegistries.ITEM) {
			String path = BuiltInRegistries.ITEM.getKey(item).getPath();

			if (path.startsWith(needle) && (prefix == null || path.length() < pathOf(prefix).length())) {
				prefix = item;
			} else if (path.contains(needle) && (contains == null || path.length() < pathOf(contains).length())) {
				contains = item;
			}
		}

		return Optional.ofNullable(prefix != null ? prefix : contains);
	}

	private static String pathOf(final Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	private static Iterable<String> itemSuggestions() {
		List<String> names = new ArrayList<>();
		BuiltInRegistries.ITEM.forEach(item -> names.add(pathOf(item)));
		return names;
	}

	private static int giveTo(
		final CommandSourceStack source,
		final Collection<ServerPlayer> targets,
		final String query,
		final int count
	) throws CommandSyntaxException {
		Optional<Item> resolved = resolve(query);

		if (resolved.isEmpty()) {
			source.sendFailure(Component.literal("No item matching '" + query + "'."));
			return 0;
		}

		Item item = resolved.get();

		for (ServerPlayer target : targets) {
			handOver(target, item, count);
		}

		String name = pathOf(item);
		int recipients = targets.size();

		source.sendSuccess(
			() -> Component.literal(
				recipients == 1
					? "Gave " + count + " " + name + " to " + targets.iterator().next().getGameProfile().name() + "."
					: "Gave " + count + " " + name + " to " + recipients + " players."
			).withStyle(ChatFormatting.GREEN),
			true
		);
		return recipients;
	}

	/**
	 * Puts the items in the player's inventory, dropping whatever will not fit.
	 *
	 * <p>Split into stack-sized pieces the way vanilla does, so asking for 200 cobblestone yields three
	 * full stacks and a partial one rather than a single impossible stack that the inventory would
	 * silently clamp.
	 */
	private static void handOver(final ServerPlayer target, final Item item, final int count) {
		int remaining = count;

		while (remaining > 0) {
			ItemStack stack = new ItemStack(item);
			int size = Math.min(remaining, stack.getMaxStackSize());
			stack.setCount(size);
			remaining -= size;

			if (target.getInventory().add(stack)) {
				continue;
			}

			// No room. Dropped at the player rather than discarded, and marked as theirs so nobody
			// else can beat them to it.
			ItemEntity dropped = target.drop(stack, false);

			if (dropped != null) {
				dropped.setNoPickUpDelay();
				dropped.setTarget(target.getUUID());
			}
		}
	}
}
