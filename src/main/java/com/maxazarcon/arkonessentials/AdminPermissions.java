package com.maxazarcon.arkonessentials;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * The mod's permission nodes.
 *
 * <p>Everything goes through Fabric's permission API rather than vanilla's {@code PermissionSet}. The
 * {@code checkPermission(node, PermissionLevel)} overload is precisely what staff commands want —
 * "granted the node, or else an operator" — and the typed overloads let a node carry a value or
 * default to allowed. Using one mechanism throughout also means a permissions mod only has to
 * implement one thing for all of this to work.
 *
 * <p><strong>Nothing here implies anything else.</strong> Groups and inheritance belong in the
 * permissions mod, where an Admin group lists what it grants (or uses a wildcard such as
 * {@code arkonessentials.admin.*}). The one exception is operator level, which satisfies every staff
 * check so the mod still works on a server with no permissions mod at all.
 */
public final class AdminPermissions {
	// Staff: granted node, or operator level.
	public static final Identifier ADMIN_MODE = node("admin.mode");
	public static final Identifier ADMIN_GHOST = node("admin.ghost");
	public static final Identifier ADMIN_TP = node("admin.tp");
	public static final Identifier ADMIN_HOME = node("admin.home");

	/** Who can see vanished players. Vanished players always see each other regardless. */
	public static final Identifier ADMIN_SEE_HIDDEN = node("admin.see_hidden");

	/** {@code /xray} — outline every player through walls, for your eyes only. */
	public static final Identifier ADMIN_XRAY = node("admin.xray");

	public static final Identifier PASSIVE = node("passive");
	public static final Identifier BUILD = node("build");
	public static final Identifier BUILD_NV = node("build.nv");
	public static final Identifier BUILD_REACH = node("build.reach");
	/**
	 * Everyday teleporting, separate from {@code admin.tp}: this moves nobody into a state and writes to
	 * its own stored location.
	 *
	 * <p>Deliberately siblings rather than {@code tp.back.death}. A dotted child inherits from its
	 * parent, so nesting death return under {@code tp.back} would hand it out with every {@code /back}
	 * grant — the exact trap that makes a child node unable to be stricter than its parent.
	 */
	public static final Identifier TP = node("tp");

	public static final Identifier TP_OTHERS = node("tp.others");
	public static final Identifier TP_COORDS = node("tp.coords");
	public static final Identifier TP_BACK = node("tp.back");

	/** Whether {@code /back} also returns to where the player last died. */
	public static final Identifier TP_DEATH = node("tp.death");

	public static final Identifier TP_TOP = node("tp.top");

	/** {@code /thru} — step through whatever is in front of you. */
	public static final Identifier TP_THRU = node("tp.thru");
	public static final Identifier TP_HERE = node("tp.here");
	public static final Identifier TP_THERE = node("tp.there");
	public static final Identifier TP_ALL = node("tp.all");

	/**
	 * Cannot be teleported by anyone else.
	 *
	 * <p>Checked with {@link #checkStrict}, <strong>not</strong> {@link #check}. An immunity is a
	 * protection rather than a capability, so the usual "granted, or else operator" fallback is exactly
	 * wrong: it would make every operator immune to every other operator, and on a server with no
	 * permissions mod it would make the whole staff untouchable by each other.
	 */
	public static final Identifier TP_IMMUNE = node("tp.immune");

	/** Cannot have a mode granted or revoked by anyone else. Also {@link #checkStrict}. */
	public static final Identifier GRANT_IMMUNE = node("admin.grant.immune");

	public static final Identifier AFK_TOGGLE = node("afk.toggle");

	public static final Identifier FAKE_LEAVE = node("fake.leave");
	public static final Identifier FAKE_JOIN = node("fake.join");

	/** {@code /vanish} — its own node, not implied by {@code admin.ghost} or anything else. */
	public static final Identifier VANISH = node("vanish");

	/**
	 * {@code /noclip}. Note the dotted-node rule: granting {@code vanish} also grants this unless the
	 * child is denied explicitly, which is usually right — both are the same job.
	 */
	public static final Identifier VANISH_NOCLIP = node("vanish.noclip");

	public static final Identifier GOD = node("god");
	public static final Identifier DEMIGOD = node("demigod");
	public static final Identifier FLY = node("fly");

	/**
	 * Tuning the flight speed multiplier, separate from being allowed to fly.
	 *
	 * <p>Its own node because the two do not travel together: someone in Build Mode is in creative and
	 * therefore already flying natively, without ever holding {@link #FLY}. Without this they could fly
	 * but not adjust it.
	 */
	public static final Identifier FLY_SPEED = node("fly.speed");

	// Public: allowed unless a permissions mod revokes them, so they still work with none installed.
	public static final Identifier TPS = node("tps");
	public static final Identifier PING = node("ping");
	public static final Identifier HOME = node("home");
	public static final Identifier HOME_NAMED = node("home.named");
	public static final Identifier AFK = node("afk");

	/** {@code /mode}. Reports only your own state and fixed text, so there is nothing to withhold. */
	public static final Identifier MODE = node("mode");

	/**
	 * Attaching a free-text reason to {@code /afk}. Defaults from {@code afkReasonsAvailable} rather
	 * than being public outright, so a server can hand everyone AFK without handing everyone a
	 * broadcast channel.
	 */
	public static final Identifier AFK_REASON = node("afk.reason");

	/**
	 * Valued nodes, each falling back to its config default when nothing grants one.
	 *
	 * <p>The identifiers are separate constants because these carry a <em>value</em> rather than a
	 * yes/no, so they are not {@link Gate}s — and the permissions manifest still has to list them, which
	 * needs the plain identifier.
	 */
	public static final Identifier HOME_LIMIT_ID = node("home.limit");

	public static final PermissionNode<Integer> HOME_LIMIT = PermissionNode.ofInteger(HOME_LIMIT_ID);

	public static final Identifier ADMIN_HOME_LIMIT_ID = node("admin.home.limit");

	public static final PermissionNode<Integer> ADMIN_HOME_LIMIT = PermissionNode.ofInteger(ADMIN_HOME_LIMIT_ID);

	/** Grants Demigod flight where the config withholds it. God and Ghost never consult this. */
	public static final Identifier DEMIGOD_FLIGHT_ID = node("fly.demigod");

	public static final PermissionNode<Boolean> DEMIGOD_FLIGHT = PermissionNode.of(DEMIGOD_FLIGHT_ID);

	/**
	 * A node that carries a value rather than a yes/no.
	 *
	 * @param configKey the server setting it falls back to when nothing grants it
	 */
	public record Valued(Identifier node, String type, String configKey) {
	}

	public static final List<Valued> VALUED = List.of(
		new Valued(HOME_LIMIT_ID, "integer", "playerHomes"),
		new Valued(ADMIN_HOME_LIMIT_ID, "integer", "adminHomes"),
		new Valued(DEMIGOD_FLIGHT_ID, "boolean", "demigodFlight")
	);

	/**
	 * Every gate the mod checks, for diagnostics.
	 *
	 * <p>{@code fallback} must mirror what the real check does when nothing grants or denies the node,
	 * so the three shapes are all represented: public nodes default to allowed, staff nodes fall back
	 * to operator level, and a couple take their default from the config. Getting this wrong makes the
	 * diagnostic lie, which is worse than not having it.
	 */
	public record Gate(Identifier node, Default defaultKind, Predicate<PermissionContext> fallback) {
	}

	/**
	 * What decides a node when nothing has granted or denied it.
	 *
	 * <p>Carried alongside the predicate rather than inferred from it, so the shipped permissions
	 * manifest can state each node's default without anyone re-deriving it by hand — and so the test
	 * that compares the two has something exact to compare.
	 */
	public enum Default {
		/** Allowed unless revoked. */
		PUBLIC,
		/** Granted, or else operator level. */
		OPERATOR,
		/** Taken from a server config key. */
		CONFIG,
		/** Denied outright; only an explicit grant turns it on. */
		DENIED
	}

	private static Gate open(final Identifier node) {
		return new Gate(node, Default.PUBLIC, PUBLIC);
	}

	private static Gate staff(final Identifier node) {
		return new Gate(node, Default.OPERATOR, OPERATOR);
	}

	private static Gate denied(final Identifier node) {
		return new Gate(node, Default.DENIED, DENIED);
	}

	private static Gate configured(final Identifier node, final Predicate<PermissionContext> fallback) {
		return new Gate(node, Default.CONFIG, fallback);
	}

	private static final Predicate<PermissionContext> PUBLIC = context -> true;

	/** For nodes with no operator fallback — see {@link #checkStrict}. */
	private static final Predicate<PermissionContext> DENIED = context -> false;
	private static final Predicate<PermissionContext> OPERATOR =
		context -> context.permissionLevel().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);

	private static final List<Gate> FIXED_GATES = List.of(
		open(TPS),
		open(PING),
		open(HOME),
		configured(HOME_NAMED, context -> EssentialsConfig.get().playerNamedHomes),
		open(AFK),
		open(MODE),
		configured(AFK_REASON, context -> EssentialsConfig.get().afkReasonsAvailable),
		staff(PASSIVE),
		staff(BUILD),
		configured(BUILD_NV, context -> EssentialsConfig.get().buildNightVisionAvailable || OPERATOR.test(context)),
		staff(BUILD_REACH),
		staff(TP),
		staff(TP_OTHERS),
		staff(TP_COORDS),
		staff(TP_BACK),
		staff(TP_DEATH),
		staff(TP_TOP),
		staff(TP_THRU),
		staff(TP_HERE),
		staff(TP_THERE),
		staff(TP_ALL),
		// The immunities default to false outright, matching checkStrict — an operator is NOT immune
		// unless something granted it. Reporting these as OPERATOR here would make the diagnostic lie.
		denied(TP_IMMUNE),
		denied(GRANT_IMMUNE),
		staff(AFK_TOGGLE),
		staff(FAKE_LEAVE),
		staff(FAKE_JOIN),
		staff(VANISH),
		staff(VANISH_NOCLIP),
		staff(GOD),
		staff(DEMIGOD),
		staff(FLY),
		staff(FLY_SPEED),
		staff(ADMIN_MODE),
		staff(ADMIN_GHOST),
		staff(ADMIN_TP),
		staff(ADMIN_HOME),
		staff(ADMIN_SEE_HIDDEN),
		staff(ADMIN_XRAY)
	);

	/**
	 * Every gate, including one per grantable mode.
	 *
	 * <p>The grant nodes are generated from {@link AdminState} rather than listed, so adding a state
	 * cannot leave a hole in the diagnostic. Declared after {@link #FIXED_GATES} because static
	 * initialisers run in source order and this reads it.
	 */
	public static final List<Gate> GATES = allGates();

	private static List<Gate> allGates() {
		List<Gate> gates = new ArrayList<>(FIXED_GATES);

		for (AdminState state : AdminState.values()) {
			if (state != AdminState.NONE) {
				gates.add(staff(grantNode(state)));
			}
		}

		return List.copyOf(gates);
	}

	/**
	 * Resolves a gate against an arbitrary permission context rather than a live command source, so
	 * {@code /arkon perms} can answer for a player who is not online.
	 *
	 * @return the raw provider answer, or empty when nothing grants or denies it
	 */
	public static Optional<Boolean> resolve(final PermissionContext context, final Identifier node) {
		return Optional.ofNullable(context.checkPermission(PermissionNode.of(node)));
	}

	private AdminPermissions() {
	}

	private static Identifier node(final String path) {
		return Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, path);
	}

	/**
	 * A staff check: passes if the node is granted, otherwise if the source is at least an operator.
	 *
	 * <p>The level fallback cannot be dropped — with no permissions mod installed nothing can grant a
	 * node, so a lone server owner would be locked out of their own mod. The consequence is that
	 * operators satisfy every staff check, so tiered staff must be left unopped.
	 */
	public static boolean check(final CommandSourceStack source, final Identifier node) {
		return source.checkPermission(node, PermissionLevel.GAMEMASTERS);
	}

	public static boolean check(final ServerPlayer player, final Identifier node) {
		return player.checkPermission(node, PermissionLevel.GAMEMASTERS);
	}

	/** A public check: allowed by default, but a permissions mod may revoke it. */
	public static boolean checkPublic(final CommandSourceStack source, final Identifier node) {
		return source.checkPermission(node, true);
	}

	/**
	 * A check with <strong>no operator fallback</strong>: true only if something actually granted it.
	 *
	 * <p>For immunities and other protections, where {@link #check}'s fallback would be actively
	 * harmful. Operators satisfy every staff check by design, so using {@code check} for an immunity
	 * would silently make all of them immune to one another — and on a server with no permissions mod,
	 * immune to everyone.
	 */
	public static boolean checkStrict(final ServerPlayer player, final Identifier node) {
		return player.checkPermission(node, false);
	}

	/** Whether {@code target} refuses to be teleported by anyone else. */
	public static boolean isTeleportImmune(final ServerPlayer target) {
		return checkStrict(target, TP_IMMUNE);
	}

	/** Whether {@code target} refuses to have a mode granted or revoked by anyone else. */
	public static boolean isGrantImmune(final ServerPlayer target) {
		return checkStrict(target, GRANT_IMMUNE);
	}

	/** The node that lets someone put another player into {@code state}. */
	public static Identifier grantNode(final AdminState state) {
		return node("admin.grant." + state.getSerializedName());
	}

	/**
	 * Whether the {@code /admin grant} and {@code /admin revoke} subtree should appear.
	 *
	 * <p>Any single mode grant opens it; the per-mode check happens on each mode's own branch, and
	 * revoking needs only that you can grant something.
	 */
	public static boolean mayGrantAnything(final CommandSourceStack source) {
		for (AdminState state : AdminState.values()) {
			if (state != AdminState.NONE && check(source, grantNode(state))) {
				return true;
			}
		}

		return false;
	}

	/** How many homes this player may keep under {@code /home}, permission first, then config. */
	public static int playerHomeLimit(final CommandSourceStack source) {
		return source.checkPermission(HOME_LIMIT, EssentialsConfig.get().playerHomes);
	}

	/** How many named homes this player may keep under {@code /admin home}. */
	public static int adminHomeLimit(final CommandSourceStack source) {
		return source.checkPermission(ADMIN_HOME_LIMIT, EssentialsConfig.get().adminHomes);
	}

	/** Whether this player may name their own homes at all. */
	public static boolean mayNamePlayerHomes(final CommandSourceStack source) {
		return checkPublic(source, HOME) && source.checkPermission(HOME_NAMED, EssentialsConfig.get().playerNamedHomes);
	}

	/**
	 * Whether the {@code /tp} tree should appear at all.
	 *
	 * <p>Any one of the teleport nodes opens the root, so a player granted only {@code tp.coords} still
	 * sees the command; each branch then gates itself. Same reasoning as {@code mayUseAdminSuite}.
	 */
	public static boolean mayUseTeleport(final CommandSourceStack source) {
		return check(source, TP) || check(source, TP_OTHERS) || check(source, TP_COORDS);
	}

	/** Whether a death should overwrite this player's {@code /back} point. */
	public static boolean mayReturnToDeath(final ServerPlayer player) {
		return check(player, TP_DEATH);
	}

	/** Whether this player may attach a reason to {@code /afk}. */
	public static boolean mayGiveAfkReason(final CommandSourceStack source) {
		return checkPublic(source, AFK) && source.checkPermission(AFK_REASON, EssentialsConfig.get().afkReasonsAvailable);
	}

	public static boolean mayDemigodFly(final ServerPlayer player) {
		return player.checkPermission(DEMIGOD_FLIGHT, EssentialsConfig.get().demigodFlight);
	}

	public static boolean mayDemigodFly(final CommandSourceStack source) {
		return source.checkPermission(DEMIGOD_FLIGHT, EssentialsConfig.get().demigodFlight);
	}

	/**
	 * Editing the mod's own settings via {@code /arkon}.
	 *
	 * <p>Operators only, and deliberately not a grantable node: this changes server-wide behaviour for
	 * everyone, so no staff grant should reach it.
	 */
	public static boolean mayEditConfig(final CommandSourceStack source) {
		return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
	}

	/**
	 * Whether the {@code /admin} tree should appear at all.
	 *
	 * <p>Without this the root would require {@link #ADMIN_MODE}, putting every subcommand out of reach
	 * of the very people the individual nodes exist for — a passive-only player could not get to
	 * {@code /admin passive}. Each subcommand still gates itself.
	 */
	public static boolean mayUseAdminSuite(final CommandSourceStack source) {
		for (Identifier node : new Identifier[]{
			ADMIN_MODE, ADMIN_GHOST, ADMIN_TP, ADMIN_HOME, PASSIVE, BUILD, GOD, DEMIGOD, VANISH, FLY, FLY_SPEED
		}) {
			if (check(source, node)) {
				return true;
			}
		}

		// Anyone currently in a state keeps access, so /admin off is always reachable. Without this a
		// player demoted while in Build Mode would be stranded in creative with no way back.
		ServerPlayer player = source.getPlayer();
		return player != null && AdminManager.getState(player) != AdminState.NONE;
	}
}
