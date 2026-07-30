package com.maxazarcon.arkonessentials;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import com.maxazarcon.arkonessentials.EssentialsData.HomeTier;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * <pre>
 * /admin              toggle Admin Mode   (hidden + creative, inventory stashed)
 * /admin passive      toggle Passive Mode (mobs ignore you, players still see you)
 * /admin build        toggle Build Mode   (creative, own loadout, extended reach, night vision)
 * /build              same thing, shorter
 * /build reach        show your Build Mode reach bonus
 * /build reach &lt;0-10&gt; set it; 0 is vanilla reach, and it applies immediately if you are in Build Mode
 * /admin god          toggle God Mode     (nothing lands: damage, knockback, harmful effects)
 * /godmode /tgm       same thing, shorter
 * /admin demigod      toggle Demigod      (hits land in full, health just never drops)
 * /demigod /dg /tdg   same thing, shorter
 * /admin ghost        toggle Ghost        (God Mode plus hidden, still in survival with your gear)
 * /ghost              same thing, shorter
 * /admin off          clear everything and return to your last non-creative game mode
 * /admin tp &lt;player&gt;  go on duty and teleport to a player, saving where you were
 * /atp &lt;player&gt;       same thing, shorter
 * /admin back         swap between here and your saved return point
 * /home               teleport to your spawn point (bed, or /home set)
 * /home set           make this spot your spawn point
 * /home set &lt;name&gt;    named player homes, if permitted (off by default)
 * /admin home         a separate set of staff homes; named only, never touches spawn
 * /admin home set &lt;name&gt; | list | delete &lt;name&gt;
 * /fly                toggle the flight ability; God/Demigod/Ghost only, double-tap jump to fly
 * /fly speed &lt;1-5&gt;    flight speed multiplier, applies to creative flight too
 * /build nv           toggle Build Mode's night vision
 * /tps  /ping         public; see TpsCommand and PingCommand
 * /arkon config       operators only; see ArkonCommand
 * </pre>
 *
 * <p>The states are mutually exclusive, so switching between them moves straight across rather than
 * stacking. Running the same one twice turns it off.
 *
 * <p>Admin Mode and Ghost are both fully vanished, so switching between them only swaps the game
 * mode, protection and inventory — it never flickers the player back into view.
 *
 * <h2>Adding a new mode</h2>
 *
 * <p>Four places, in this order. Miss one and the failure is usually silent rather than a compile
 * error, so it is worth walking the list:
 *
 * <ol>
 *   <li>{@link AdminState} — add the constant with its serialized name, HUD label and colour, then say
 *       what it does by editing the behaviour predicates ({@code hiddenFromMobs}, {@code protectsPlayer}
 *       and friends) and {@code description()}. <strong>Add it at the end</strong>: the stream codec is
 *       ordinal-based.
 *   <li>{@link AdminPermissions} — add the node and a {@code Gate} entry, or {@code /arkon perms} will
 *       quietly not report it. Add it to {@code mayUseAdminSuite} too, or holders of only that node
 *       cannot open the {@code /admin} root.
 *   <li>Here — one {@code stateCommand(...)} under the root, and usually a second registration as a
 *       standalone alias (see the aliases block in {@link #register}).
 *   <li>{@code HudConfig.slots()} in the client source set, so the indicator is configurable.
 * </ol>
 *
 * <p>Nothing else needs touching: {@code AdminManager.setState} drives transitions off the predicates,
 * and the mixins ask {@code AdminManager} rather than testing states themselves.
 *
 * <h2>Conventions in this file</h2>
 *
 * <ul>
 *   <li>Subtrees are built by helper methods that <strong>return a fresh builder each call</strong>
 *       ({@code stateCommand}, {@code buildCommand}, {@code flyCommand}, {@code homeCommand}). That is
 *       required, not stylistic — registering one builder in two places makes Brigadier share the nodes.
 *   <li>Every command that <em>changes</em> anything calls {@link #dataLocked} first, so a save file
 *       from a newer build cannot be half-written.
 *   <li>Permission checks live on {@code .requires} where they should hide the command, and inside the
 *       executor where the command should stay visible and explain itself.
 * </ul>
 */
public final class AdminCommand {
	private AdminCommand() {
	}

	public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			// The root opens for anyone holding any suite node; each subcommand still gates itself. Bare
			// /admin — Admin Mode itself — keeps its own requirement via the executes guard below.
			Commands.literal("admin")
				.requires(AdminPermissions::mayUseAdminSuite)
				.executes(context -> toggleAdminMode(context.getSource()))
				.then(stateCommand("passive", AdminState.PASSIVE, AdminPermissions.PASSIVE))
				.then(buildCommand("build"))
				.then(stateCommand("god", AdminState.GOD, AdminPermissions.GOD))
				.then(stateCommand("demigod", AdminState.DEMIGOD, AdminPermissions.DEMIGOD))
				.then(stateCommand("ghost", AdminState.GHOST, AdminPermissions.ADMIN_GHOST))
				.then(
					Commands.literal("off")
						.executes(context -> turnOff(context.getSource()))
				)
				.then(
					Commands.literal("tp")
						.requires(source -> AdminPermissions.check(source, AdminPermissions.ADMIN_TP))
						.then(
							Commands.argument("player", EntityArgument.player())
								.executes(context -> teleport(context.getSource(), EntityArgument.getPlayer(context, "player")))
						)
				)
				.then(
					Commands.literal("back")
						.requires(source -> AdminPermissions.check(source, AdminPermissions.ADMIN_TP))
						.executes(context -> back(context.getSource()))
				)
				.then(adminHomeCommand("home"))
				.then(flyCommand("fly"))
				.then(grantCommand())
				.then(
					Commands.literal("revoke")
						// Any grant node is enough to revoke: taking a mode away is never more dangerous
						// than handing one out, and pairing them avoids a state where staff can grant
						// something they cannot then undo.
						.requires(AdminPermissions::mayGrantAnything)
						.then(
							Commands.argument("player", EntityArgument.player())
								.executes(context -> revoke(context.getSource(), EntityArgument.getPlayer(context, "player")))
						)
				)
		);

		// Registered separately rather than redirected: /atp takes the player directly, so it has a
		// different shape to the /admin tp subtree.
		dispatcher.register(
			Commands.literal("atp")
				.requires(source -> AdminPermissions.check(source, AdminPermissions.ADMIN_TP))
				.then(
					Commands.argument("player", EntityArgument.player())
						.executes(context -> teleport(context.getSource(), EntityArgument.getPlayer(context, "player")))
				)
		);

		// Registered a second time at the root rather than redirected. Each call returns a fresh
		// builder, which is what Brigadier wants — reusing one builder in two places shares nodes.
		dispatcher.register(buildCommand("build"));

		// Deliberately no /gm alias: server suites overwhelmingly bind that to gamemode.
		for (String alias : List.of("godmode", "tgm")) {
			dispatcher.register(stateCommand(alias, AdminState.GOD, AdminPermissions.GOD));
		}

		for (String alias : List.of("demigod", "dg", "tdg")) {
			dispatcher.register(stateCommand(alias, AdminState.DEMIGOD, AdminPermissions.DEMIGOD));
		}

		dispatcher.register(stateCommand("ghost", AdminState.GHOST, AdminPermissions.ADMIN_GHOST));
		// Every other mode had a root alias; passive did not, which left a passive-only player with no
		// way to reach it once the /admin root stopped implying full admin.
		dispatcher.register(stateCommand("passive", AdminState.PASSIVE, AdminPermissions.PASSIVE));
		dispatcher.register(homeCommand("home"));
		dispatcher.register(flyCommand("fly"));

		// /nv as a root alias for /build nv. The night vision it toggles is not Build Mode's alone any
		// more — Vanish grants it too — so a name that does not mention building is the better one to
		// reach for, and the preference behind both is the same.
		dispatcher.register(
			Commands.literal("nv")
				.requires(source -> EssentialsConfig.get().buildNightVisionAvailable
					|| AdminPermissions.check(source, AdminPermissions.BUILD_NV))
				.executes(context -> toggleNightVision(context.getSource()))
		);
	}

	/**
	 * The player home suite: {@code /home}, open to everyone.
	 *
	 * <p>The unnamed forms only move the player's own spawn point. Naming is gated on
	 * {@code arkonessentials:home.named}, which defaults to the config's {@code playerNamedHomes} —
	 * off, so out of the box a player has exactly one home and it is their spawn.
	 *
	 * <p><strong>The literal subcommands shadow home names.</strong> Brigadier always tries literal
	 * children before argument children, so a home called {@code set}, {@code list} or {@code delete} can
	 * be created but never travelled to — {@code /home set} runs the subcommand instead. Accepted as a
	 * quirk rather than fixed by renaming the subcommands, which players expect to be called those
	 * things. If you add another literal here, you take a word out of the usable name space; if that
	 * matters, reject the name in {@code setNamedHome} instead of adding the literal.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> homeCommand(final String name) {
		return Commands.literal(name)
			// Public by default, so revoking the node hides the command entirely. A limit of 0 instead
			// leaves it visible and refuses with a reason — "disabled here" is more useful than a
			// command that silently does not exist.
			.requires(source -> AdminPermissions.checkPublic(source, AdminPermissions.HOME))
			.executes(context -> home(context.getSource()))
			.then(
				Commands.literal("set")
					.executes(context -> setHome(context.getSource()))
					.then(
						Commands.argument("name", StringArgumentType.word())
							.requires(AdminPermissions::mayNamePlayerHomes)
							.suggests((context, builder) -> suggestHomes(context, builder, HomeTier.PLAYER))
							.executes(context -> setNamedHome(
								context.getSource(), HomeTier.PLAYER, StringArgumentType.getString(context, "name")
							))
					)
			)
			.then(
				Commands.literal("list")
					.requires(AdminPermissions::mayNamePlayerHomes)
					.executes(context -> listHomes(context.getSource(), HomeTier.PLAYER))
			)
			.then(
				Commands.literal("delete")
					.requires(AdminPermissions::mayNamePlayerHomes)
					.then(
						Commands.argument("name", StringArgumentType.word())
							.suggests((context, builder) -> suggestHomes(context, builder, HomeTier.PLAYER))
							.executes(context -> deleteHome(
								context.getSource(), HomeTier.PLAYER, StringArgumentType.getString(context, "name")
							))
					)
			)
			.then(
				Commands.argument("name", StringArgumentType.word())
					.requires(AdminPermissions::mayNamePlayerHomes)
					.suggests((context, builder) -> suggestHomes(context, builder, HomeTier.PLAYER))
					.executes(context -> namedHome(
						context.getSource(), HomeTier.PLAYER, StringArgumentType.getString(context, "name")
					))
			);
	}

	/**
	 * The admin home suite: {@code /admin home}, a separate set of locations from a player's own.
	 *
	 * <p>Named-only by design — spawn-setting belongs solely to {@code /home set}, so there is exactly
	 * one command in the mod that can move a respawn point.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> adminHomeCommand(final String name) {
		return Commands.literal(name)
			.requires(source -> AdminPermissions.check(source, AdminPermissions.ADMIN_HOME))
			.executes(context -> listHomes(context.getSource(), HomeTier.ADMIN))
			.then(
				Commands.literal("list")
					.executes(context -> listHomes(context.getSource(), HomeTier.ADMIN))
			)
			.then(
				Commands.literal("set")
					.then(
						Commands.argument("name", StringArgumentType.word())
							.suggests((context, builder) -> suggestHomes(context, builder, HomeTier.ADMIN))
							.executes(context -> setNamedHome(
								context.getSource(), HomeTier.ADMIN, StringArgumentType.getString(context, "name")
							))
					)
			)
			.then(
				Commands.literal("delete")
					.then(
						Commands.argument("name", StringArgumentType.word())
							.suggests((context, builder) -> suggestHomes(context, builder, HomeTier.ADMIN))
							.executes(context -> deleteHome(
								context.getSource(), HomeTier.ADMIN, StringArgumentType.getString(context, "name")
							))
					)
			)
			.then(
				Commands.argument("name", StringArgumentType.word())
					.suggests((context, builder) -> suggestHomes(context, builder, HomeTier.ADMIN))
					.executes(context -> namedHome(
						context.getSource(), HomeTier.ADMIN, StringArgumentType.getString(context, "name")
					))
			);
	}

	private static CompletableFuture<Suggestions> suggestHomes(
		final CommandContext<CommandSourceStack> context,
		final SuggestionsBuilder builder,
		final HomeTier tier
	) {
		ServerPlayer player = context.getSource().getPlayer();
		return player == null
			? builder.buildFuture()
			: SharedSuggestionProvider.suggest(AdminManager.namedHomes(player, tier), builder);
	}

	private static int homeLimit(final CommandSourceStack source, final HomeTier tier) {
		return tier == HomeTier.ADMIN ? AdminPermissions.adminHomeLimit(source) : AdminPermissions.playerHomeLimit(source);
	}

	/** The flight subtree, used both as {@code /admin fly [...]} and as the standalone {@code /fly [...]}. */
	private static LiteralArgumentBuilder<CommandSourceStack> flyCommand(final String name) {
		return Commands.literal(name)
			// Either node opens the tree; the toggle re-checks `fly` itself, so holding only `fly.speed`
			// gets you the tuning knob without the ability.
			.requires(source -> AdminPermissions.check(source, AdminPermissions.FLY)
				|| AdminPermissions.check(source, AdminPermissions.FLY_SPEED))
			.executes(context -> toggleFly(context.getSource()))
			.then(
				Commands.literal("speed")
					.requires(source -> AdminPermissions.check(source, AdminPermissions.FLY_SPEED))
					.executes(context -> showFlySpeed(context.getSource()))
					.then(
						Commands.argument("multiplier", IntegerArgumentType.integer(AdminManager.MIN_FLY_SPEED, AdminManager.MAX_FLY_SPEED))
							.executes(context -> setFlySpeed(context.getSource(), IntegerArgumentType.getInteger(context, "multiplier")))
					)
			);
	}

	/**
	 * A bare toggle for one state, used both under {@code /admin} and as a standalone alias.
	 *
	 * <p>This is the whole of a simple mode's command surface — call it once under the root and once at
	 * top level and the mode is fully wired. Modes needing subcommands get a builder of their own
	 * instead; see {@link #buildCommand}.
	 *
	 * <p>Returns a <strong>new builder every call</strong>, which is why it takes the name as a
	 * parameter rather than being a constant. Handing the same builder to two {@code register} calls
	 * makes Brigadier share the underlying nodes between both trees.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> stateCommand(
		final String name,
		final AdminState state,
		final Identifier node
	) {
		return Commands.literal(name)
			// On .requires, so a player without the node never sees the command exist. Bare /admin is the
			// exception — see toggleAdminMode, which checks inside so it can explain itself.
			.requires(source -> AdminPermissions.check(source, node))
			.executes(context -> toggle(context.getSource(), state));
	}

	/** The Build Mode subtree, used both as {@code /admin build} and as the standalone {@code /build}. */
	private static LiteralArgumentBuilder<CommandSourceStack> buildCommand(final String name) {
		return Commands.literal(name)
			.requires(source -> AdminPermissions.check(source, AdminPermissions.BUILD))
			.executes(context -> toggle(context.getSource(), AdminState.BUILD))
			.then(
				Commands.literal("reach")
					.requires(source -> AdminPermissions.check(source, AdminPermissions.BUILD_REACH))
					.executes(context -> showReach(context.getSource()))
					.then(
						Commands.argument("blocks", IntegerArgumentType.integer(0, AdminManager.MAX_REACH_BONUS))
							.executes(context -> setReach(context.getSource(), IntegerArgumentType.getInteger(context, "blocks")))
					)
			)
			.then(
				Commands.literal("nv")
					// When the config withholds night vision, it becomes a granted privilege rather than
					// a free toggle. When it is available, anyone who can build may use it.
					.requires(source -> EssentialsConfig.get().buildNightVisionAvailable
						|| AdminPermissions.check(source, AdminPermissions.BUILD_NV))
					.executes(context -> toggleNightVision(context.getSource()))
			);
	}

	/**
	 * {@code /admin grant <player> <mode>} — put someone else into a mode.
	 *
	 * <p>One branch per mode, each gated on its own {@code admin.grant.<mode>} node, so a server can let
	 * a head-builder hand out Build Mode without also handing out Admin. {@link AdminState#NONE} is
	 * absent: dropping someone to nothing is {@code /admin revoke}, which reads better and needs no mode
	 * argument.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> grantCommand() {
		LiteralArgumentBuilder<CommandSourceStack> grant = Commands.literal("grant")
			.requires(AdminPermissions::mayGrantAnything);

		for (AdminState state : AdminState.values()) {
			if (state == AdminState.NONE) {
				continue;
			}

			grant = grant.then(
				Commands.literal(state.getSerializedName())
					.requires(source -> AdminPermissions.check(source, AdminPermissions.grantNode(state)))
					.then(
						Commands.argument("player", EntityArgument.player())
							.executes(context -> grant(context.getSource(), EntityArgument.getPlayer(context, "player"), state))
					)
			);
		}

		return grant;
	}

	private static int grant(final CommandSourceStack source, final ServerPlayer target, final AdminState state) {
		if (dataLocked(source) || grantRefused(source, target)) {
			return 0;
		}

		AdminManager.setState(target, state);

		source.sendSuccess(
			() -> Component.literal("Granted " + state.label() + " to " + target.getGameProfile().name() + "."),
			true
		);

		// The target is told as well. Being silently put into creative, or having your inventory swapped,
		// is alarming without an explanation.
		target.sendSystemMessage(
			Component.literal(state.label() + " granted by " + source.getTextName() + ".")
				.withStyle(style -> style.withColor(TextColor.fromRgb(state.color() & 0xFFFFFF)))
		);
		return 1;
	}

	/**
	 * {@code /admin revoke <player>} — a full reset on someone else.
	 *
	 * <p>Uses the same path as {@code /admin off}, so it restores gear and returns them to the game mode
	 * they were last actually playing in rather than guessing survival.
	 */
	private static int revoke(final CommandSourceStack source, final ServerPlayer target) {
		if (dataLocked(source) || grantRefused(source, target)) {
			return 0;
		}

		if (!AdminManager.turnOff(target)) {
			source.sendFailure(Component.literal(target.getGameProfile().name() + " has nothing active."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Revoked " + target.getGameProfile().name() + "'s mode."), true);
		target.sendSystemMessage(Component.literal("Your mode was revoked by " + source.getTextName() + "."));
		return 1;
	}

	/**
	 * Whether this grant or revoke should be refused, reporting why.
	 *
	 * <p>Immunity is checked strictly — no operator fallback — so it is only ever true for someone
	 * explicitly granted it. Self-targeting is exempt: being unable to take yourself out of a mode you
	 * put yourself in would be a trap, and it is the same thing {@code /admin off} already allows.
	 */
	private static boolean grantRefused(final CommandSourceStack source, final ServerPlayer target) {
		if (source.getEntity() == target || !AdminPermissions.isGrantImmune(target)) {
			return false;
		}

		source.sendFailure(Component.literal(target.getGameProfile().name() + " is immune to mode changes."));
		return true;
	}

	/**
	 * Blocks every command when the saved data came from a newer build, and says why.
	 *
	 * <p>Without this the commands would appear to work while changing nothing, since a locked
	 * {@link EssentialsData} silently discards writes.
	 */
	private static boolean dataLocked(final CommandSourceStack source) {
		if (!EssentialsData.get(source.getServer()).isLocked()) {
			return false;
		}

		source.sendFailure(
			Component.literal(
				"Admin Mode is disabled: its saved data was written by a newer version of the mod. "
					+ "Nothing has been changed. Restore that version, or move data/arkonessentials/admin_mode.dat aside."
			)
		);
		return true;
	}

	/**
	 * Bare {@code /admin}. The root node now admits anyone with any suite permission, so Admin Mode
	 * itself has to check its own node here rather than relying on the root to have done it.
	 */
	private static int toggleAdminMode(final CommandSourceStack source) throws CommandSyntaxException {
		if (!AdminPermissions.check(source, AdminPermissions.ADMIN_MODE)) {
			source.sendFailure(Component.literal("You do not have permission for Admin Mode."));
			return 0;
		}

		return toggle(source, AdminState.ADMIN);
	}

	/**
	 * The shared entry point for every mode command: running one you are already in turns it off.
	 *
	 * <p>Because the states are mutually exclusive, switching is just {@code apply(target)} — there is no
	 * "leave the old one" step. {@code AdminManager.setState} works out what that transition implies for
	 * game mode and inventory.
	 *
	 * <p>Note this drops to {@link AdminState#NONE} rather than calling {@code turnOff}: toggling a mode
	 * back off is not the same as {@code /admin off}, which is a full reset that also pulls the player
	 * out of creative even if the mode never put them there.
	 */
	private static int toggle(final CommandSourceStack source, final AdminState target) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		AdminState current = AdminManager.getState(source.getPlayerOrException());
		return apply(source, current == target ? AdminState.NONE : target);
	}

	/** Performs the transition and reports it. The single place a mode change is announced to its owner. */
	private static int apply(final CommandSourceStack source, final AdminState target) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AdminState previous = AdminManager.getState(player);

		// Returning 0 rather than saying "already active" keeps the command silent when nothing happened,
		// which matters because /admin off and the toggles can both land here with no work to do.
		if (previous == target) {
			return 0;
		}

		AdminManager.setState(player, target);

		Component feedback = target == AdminState.NONE
			// Names the mode being left, not the one being entered — "None enabled." would be nonsense.
			? Component.literal(previous.label() + " disabled.")
			// Coloured to match the HUD indicator. color() is packed ARGB and TextColor wants RGB, hence
			// the mask; NONE is never coloured here because it takes the branch above.
			: Component.literal(target.label() + " enabled.")
				.withStyle(style -> style.withColor(TextColor.fromRgb(target.color() & 0xFFFFFF)));

		// false: mode changes are the player's own business and should not go to every operator's log.
		source.sendSuccess(() -> feedback, false);
		return 1;
	}

	/** The full reset, as opposed to toggling a single effect back off. */
	private static int turnOff(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		boolean changed = AdminManager.turnOff(source.getPlayerOrException());
		Component feedback = Component.literal(changed ? "Admin Mode off." : "Nothing was active.");

		source.sendSuccess(() -> feedback, false);
		return changed ? 1 : 0;
	}

	private static int teleport(final CommandSourceStack source, final ServerPlayer target) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		ServerPlayer admin = source.getPlayerOrException();

		if (admin == target) {
			source.sendFailure(Component.literal("You are already there."));
			return 0;
		}

		if (!AdminManager.teleportToPlayer(admin, target)) {
			source.sendFailure(Component.literal("Could not teleport to that player."));
			return 0;
		}

		Component feedback = Component.literal("Teleported to ")
			.append(target.getDisplayName())
			.append(". Use /admin back to return.");

		source.sendSuccess(() -> feedback, false);
		return 1;
	}

	private static int back(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		ServerPlayer player = source.getPlayerOrException();

		switch (AdminManager.returnToPoint(player)) {
			case OK -> {
				source.sendSuccess(() -> Component.literal("Returned. Run /admin back again to come straight back here."), false);
				return 1;
			}
			case NOT_SET -> source.sendFailure(
				Component.literal("No return point saved. Use /admin tp <player> first.")
			);
			case DIMENSION_MISSING -> source.sendFailure(
				Component.literal("The dimension you saved is no longer loaded.")
			);
		}

		return 0;
	}

	/**
	 * Withheld either by revoking the public {@code home} node or by a limit of 0, from config or the
	 * numeric permission.
	 */
	private static boolean homesDisabled(final CommandSourceStack source) {
		if (AdminPermissions.checkPublic(source, AdminPermissions.HOME) && AdminPermissions.playerHomeLimit(source) > 0) {
			return false;
		}

		source.sendFailure(Component.literal("Homes are disabled on this server."));
		return true;
	}

	private static int setHome(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source) || homesDisabled(source)) {
			return 0;
		}

		AdminManager.setSpawnHome(source.getPlayerOrException());
		source.sendSuccess(() -> Component.literal("Home set. You will respawn here."), false);
		return 1;
	}

	private static int home(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source) || homesDisabled(source)) {
			return 0;
		}

		ServerPlayer player = source.getPlayerOrException();

		// No NOT_SET branch: with no bed and no /home set, this falls back to world spawn.
		if (AdminManager.goHome(player) == AdminManager.TeleportResult.OK) {
			source.sendSuccess(() -> Component.literal("Teleported home."), false);
			return 1;
		}

		source.sendFailure(Component.literal("Your home dimension is no longer loaded."));
		return 0;
	}

	/** Which command a tier's messages should point people at. */
	private static String commandFor(final HomeTier tier) {
		return tier == HomeTier.ADMIN ? "/admin home" : "/home";
	}

	private static int setNamedHome(final CommandSourceStack source, final HomeTier tier, final String rawName)
		throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		String name = rawName.toLowerCase(Locale.ROOT);
		ServerPlayer player = source.getPlayerOrException();
		int limit = homeLimit(source, tier);

		if (!AdminManager.setNamedHome(player, tier, name, limit)) {
			source.sendFailure(Component.literal(
				"You already have " + limit + " homes here. Delete one with " + commandFor(tier) + " delete <name>."
			));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Home '" + name + "' saved."), false);
		return 1;
	}

	private static int namedHome(final CommandSourceStack source, final HomeTier tier, final String rawName)
		throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		String name = rawName.toLowerCase(Locale.ROOT);
		ServerPlayer player = source.getPlayerOrException();

		switch (AdminManager.goNamedHome(player, tier, name)) {
			case OK -> {
				source.sendSuccess(() -> Component.literal("Teleported to '" + name + "'."), false);
				return 1;
			}
			case NOT_SET -> source.sendFailure(
				Component.literal("No home called '" + name + "'. Use " + commandFor(tier) + " list to see yours.")
			);
			case DIMENSION_MISSING -> source.sendFailure(
				Component.literal("That home's dimension is no longer loaded.")
			);
		}

		return 0;
	}

	private static int listHomes(final CommandSourceStack source, final HomeTier tier) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		Set<String> homes = AdminManager.namedHomes(source.getPlayerOrException(), tier);
		int limit = homeLimit(source, tier);

		Component feedback = homes.isEmpty()
			? Component.literal("No named homes. Save one with " + commandFor(tier) + " set <name>.")
			: Component.literal("Homes (" + homes.size() + "/" + limit + "): " + String.join(", ", homes));

		source.sendSuccess(() -> feedback, false);
		return homes.size();
	}

	private static int deleteHome(final CommandSourceStack source, final HomeTier tier, final String rawName)
		throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		String name = rawName.toLowerCase(Locale.ROOT);

		if (!AdminManager.removeNamedHome(source.getPlayerOrException(), tier, name)) {
			source.sendFailure(Component.literal("No home called '" + name + "'."));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("Home '" + name + "' deleted."), false);
		return 1;
	}

	private static int setReach(final CommandSourceStack source, final int blocks) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		AdminManager.setReachBonus(source.getPlayerOrException(), blocks);

		Component feedback = blocks == 0
			? Component.literal("Build Mode reach set to normal.")
			: Component.literal("Build Mode reach set to +" + blocks + " blocks.");

		source.sendSuccess(() -> feedback, false);
		return 1;
	}

	private static int showReach(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		int blocks = AdminManager.getReachBonus(source.getPlayerOrException());
		String current = blocks == 0 ? "normal" : "+" + blocks + " blocks";

		Component feedback = Component.literal(
			"Build Mode reach is " + current + ". Change it with /build reach <0-" + AdminManager.MAX_REACH_BONUS + ">."
		);

		source.sendSuccess(() -> feedback, false);
		return 1;
	}

	private static int toggleFly(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		if (!AdminPermissions.check(source, AdminPermissions.FLY)) {
			source.sendFailure(Component.literal("You do not have permission to fly."));
			return 0;
		}

		ServerPlayer player = source.getPlayerOrException();
		boolean enable = !AdminManager.isFlyEnabled(player);

		// Flight is a perk of the god-tier states — Admin and Build already fly through creative, and
		// Passive is not meant to have it. Turning it OFF is always allowed, so a leftover preference
		// can be cleared from anywhere.
		if (enable && !AdminManager.stateGrantsFlight(player, AdminManager.getState(player))) {
			source.sendFailure(Component.literal(
				AdminManager.getState(player) == AdminState.DEMIGOD
					? "Flight is not available to Demigod on this server."
					: "Flight is only available in God Mode, Demigod, or Ghost."
			));
			return 0;
		}

		AdminManager.setFlyEnabled(player, enable);

		Component feedback = Component.literal(
			enable ? "Flight enabled. Double-tap jump to fly." : "Flight disabled."
		);

		source.sendSuccess(() -> feedback, false);
		return 1;
	}

	private static int setFlySpeed(final CommandSourceStack source, final int multiplier) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		AdminManager.setFlySpeed(source.getPlayerOrException(), multiplier);
		source.sendSuccess(() -> Component.literal("Flight speed set to " + multiplier + "x."), false);
		return 1;
	}

	private static int showFlySpeed(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		int multiplier = AdminManager.getFlySpeed(source.getPlayerOrException());

		Component feedback = Component.literal(
			"Flight speed is " + multiplier + "x. Change it with /fly speed <1-" + AdminManager.MAX_FLY_SPEED + ">."
		);

		source.sendSuccess(() -> feedback, false);
		return 1;
	}

	private static int toggleNightVision(final CommandSourceStack source) throws CommandSyntaxException {
		if (dataLocked(source)) {
			return 0;
		}

		ServerPlayer player = source.getPlayerOrException();
		boolean enable = !AdminManager.getBuildNightVision(player);
		AdminManager.setBuildNightVision(player, enable);

		Component feedback = Component.literal(
			enable ? "Build Mode night vision enabled." : "Build Mode night vision disabled."
		);

		source.sendSuccess(() -> feedback, false);
		return 1;
	}
}
