package com.maxazarcon.arkonessentials;

import com.maxazarcon.arkonessentials.EssentialsData.HomeTier;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.waypoints.ServerWaypointManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * Everything that actually happens when a player changes state.
 *
 * <p><strong>This class owns every transition; nothing else mutates state.</strong> Commands decide
 * what should happen and call in here, the mixins ask questions and get answers, and
 * {@link EssentialsData} is only ever written through these methods. Keeping that true is what makes
 * the effects of a state consistent regardless of how a player got into it — command, relog, or a
 * config change that revoked something underneath them.
 *
 * <h2>The shape of it</h2>
 *
 * <ul>
 *   <li><strong>States are mutually exclusive</strong> and live in {@link EssentialsData};
 *       {@link #setState} is the only way in or out.
 *   <li><strong>Flags layer on top</strong> and are independent of each other and of the state: flight
 *       (persisted), soft landings, AFK and appearing-offline (both in memory, in their own managers).
 *       Anything that can be true alongside any state belongs as a flag, not a state.
 *   <li><strong>Mixins ask, they do not decide.</strong> {@link #ignoredByMobs} and
 *       {@link #hungerFrozen} exist so a mixin has one question to ask; adding a second reason for
 *       either effect means editing the helper, not the mixin.
 * </ul>
 *
 * <h2>Rules worth knowing before editing</h2>
 *
 * <ul>
 *   <li>Anything <em>pushed onto</em> a player rather than read live — the reach modifier, flight
 *       speed, night vision — has to be re-applied on join and after a config change. That is what
 *       {@link #onJoin} and {@code ArkonCommand.applyToOnlinePlayers} are for.
 *   <li>Transient attribute modifiers only, never permanent ones: a permanent modifier is written into
 *       player data and would strand itself on players if the mod were removed. The cost is re-applying
 *       on join, which is deliberate.
 *   <li>{@link #refreshVisibility} is only called when visibility actually changes, so switching
 *       between two hidden states never flickers the player back into view.
 *   <li>{@link #syncTo} is gated on {@code canSend}. A vanilla client has no receiver registered and an
 *       unknown payload would disconnect it, so this can never be called unconditionally.
 * </ul>
 */
public final class AdminManager {
	private static final Identifier BUILD_REACH_MODIFIER = Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, "build_reach");

	/** Upper bound the command offers. Zero means vanilla reach; the default comes from the config. */
	public static final int MAX_REACH_BONUS = 10;

	/** Flight speed multiplier bounds; the default within them comes from the config. */
	public static final int MIN_FLY_SPEED = 1;
	public static final int MAX_FLY_SPEED = 5;

	/** Vanilla's full hunger bar. */
	private static final int FULL_FOOD_LEVEL = 20;

	/** Vanilla's default {@code Abilities#flyingSpeed}. */
	private static final float BASE_FLY_SPEED = 0.05F;

	/**
	 * Players owed one free landing: they lost a flight source while airborne, so the fall they are
	 * currently in should not hurt. Survives a relog (rejoining mid-fall keeps the ticket), but
	 * deliberately not persisted to disk — losing it to a full server restart mid-fall is an accepted
	 * edge, not worth a schema field.
	 */
	private static final Set<UUID> SOFT_LANDINGS = ConcurrentHashMap.newKeySet();

	private static final List<Holder<Attribute>> REACH_ATTRIBUTES = List.of(
		Attributes.BLOCK_INTERACTION_RANGE,
		Attributes.ENTITY_INTERACTION_RANGE
	);

	private AdminManager() {
	}

	public static AdminState getState(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return server == null ? AdminState.NONE : EssentialsData.get(server).getState(player.getUUID());
	}

	/**
	 * Whether mobs should refuse to target this player.
	 *
	 * <p>Two independent reasons converge here — a hidden or passive state, or being AFK — so the mixin
	 * has one thing to ask rather than having to know about both.
	 */
	public static boolean ignoredByMobs(final ServerPlayer player) {
		return getState(player).hiddenFromMobs() || AfkManager.isAfk(player);
	}

	/** Whether hunger should be pinned: the protected states, or being AFK. */
	public static boolean hungerFrozen(final ServerPlayer player) {
		return getState(player).protectsPlayer() || AfkManager.isAfk(player);
	}

	public static void setState(final ServerPlayer player, final AdminState next) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		EssentialsData data = EssentialsData.get(server);
		UUID id = player.getUUID();
		AdminState current = data.getState(id);

		if (current == next) {
			return;
		}

		boolean leavingCreative = current.stashesInventory();
		boolean enteringCreative = next.stashesInventory();
		// Judged against the outgoing state, before anything below mutates abilities.
		boolean hadFlight = data.getFlyEnabled(id) && stateGrantsFlight(player, current);

		// Whatever the player is holding belongs to the state they are leaving, so it goes back into
		// that state's loadout rather than a single shared one.
		if (leavingCreative) {
			data.putLoadout(id, current, InventorySnapshot.capture(player));
		}

		// Their own gear is stashed only on the way in from a non-creative state. Switching directly
		// between Admin and Build must leave it alone, or the second swap would overwrite it with a
		// creative loadout.
		if (enteringCreative && !leavingCreative) {
			data.setLastNonCreativeMode(id, player.gameMode());
			data.putSurvivalInventory(id, InventorySnapshot.capture(player));
		}

		if (enteringCreative) {
			player.setGameMode(GameType.CREATIVE);
			restoreOrClear(player, data.getLoadout(id, next));
		} else if (leavingCreative) {
			player.setGameMode(data.getLastNonCreativeMode(id));
			restoreOrClear(player, data.takeSurvivalInventory(id));
		}

		if (current == AdminState.BUILD) {
			clearBuildPerks(player);
		}

		if (next == AdminState.BUILD) {
			applyBuildPerks(player);
		}

		// Topped up on the way in; the mixins are what hold them there afterwards.
		if (next.protectsPlayer()) {
			restoreVitals(player);
		}

		player.inventoryMenu.broadcastChanges();
		data.setState(id, next);

		// Flight exists only in the god-tier states. The preference survives, but moving to a state
		// without flight — Passive, or off entirely — takes the ability away (softly, if it was real
		// and the player airborne), and returning to one hands it back. Runs after the state commit so
		// isFlightActive sees the new state; hadFlight was judged against the old one.
		if (data.getFlyEnabled(id)) {
			if (stateGrantsFlight(player, next)) {
				applyFlightAbilities(player);
			} else {
				teardownFlight(player, hadFlight);
			}
		}

		if (current.hiddenFromPlayers() != next.hiddenFromPlayers()) {
			refreshVisibility(player, next.hiddenFromPlayers());
		}

		syncTo(player);
	}

	/**
	 * Clearing on the empty branch is deliberate: the outgoing loadout has just been saved, and
	 * leaving it in place would walk creative items into whatever state comes next.
	 */
	private static void restoreOrClear(final ServerPlayer player, final Optional<InventorySnapshot> inventory) {
		inventory.ifPresentOrElse(snapshot -> snapshot.restore(player), () -> player.getInventory().clearContent());
	}

	/**
	 * Build Mode's extended reach and night vision.
	 *
	 * <p>The reach modifier is deliberately transient: it is never written into player data, so it
	 * cannot be left stranded on a player if the mod is later removed. The cost is that it has to be
	 * re-applied on login, which {@link #onJoin} does.
	 */
	public static void applyBuildPerks(final ServerPlayer player) {
		int bonus = getReachBonus(player);

		for (Holder<Attribute> attribute : REACH_ATTRIBUTES) {
			AttributeInstance instance = player.getAttribute(attribute);

			if (instance != null) {
				// Removed first so re-applying on login, or after a reach change, cannot stack copies.
				instance.removeModifier(BUILD_REACH_MODIFIER);

				// A zero-value modifier would work but leaves a pointless entry on the attribute, which
				// then shows up in tooltips and debug output.
				if (bonus > 0) {
					instance.addTransientModifier(
						new AttributeModifier(BUILD_REACH_MODIFIER, bonus, AttributeModifier.Operation.ADD_VALUE)
					);
				}
			}
		}

		if (getBuildNightVision(player)) {
			addBuildNightVision(player);
		}
	}

	/** Fills health and hunger. Only ever called on entry — the mixins do the holding. */
	private static void restoreVitals(final ServerPlayer player) {
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(FULL_FOOD_LEVEL);
		player.getFoodData().setSaturation(FULL_FOOD_LEVEL);
	}

	public static int getReachBonus(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return server == null
			? EssentialsConfig.get().defaultBuildReach
			: EssentialsData.get(server).getReachBonus(player.getUUID());
	}

	/** Stores the player's preferred Build Mode reach, applying it at once if they are already in it. */
	public static void setReachBonus(final ServerPlayer player, final int bonus) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		EssentialsData.get(server).setReachBonus(player.getUUID(), bonus);

		if (getState(player) == AdminState.BUILD) {
			applyBuildPerks(player);
		}
	}

	public static boolean isFlyEnabled(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return server != null && EssentialsData.get(server).getFlyEnabled(player.getUUID());
	}

	/**
	 * Whether a state grants this particular player flight.
	 *
	 * <p>God and Ghost always do. Demigod is conditional — the config withholds it by default and the
	 * {@code fly.demigod} permission grants it — which is why this takes a player rather than living on
	 * the enum.
	 */
	public static boolean stateGrantsFlight(final ServerPlayer player, final AdminState state) {
		return state.alwaysGrantsFlight() || (state == AdminState.DEMIGOD && AdminPermissions.mayDemigodFly(player));
	}

	/**
	 * Whether flight should actually be granted right now: the preference is on <em>and</em> the
	 * current state grants it. The flag alone is just a saved setting — moving to a state without
	 * flight takes the ability away, and returning hands it back.
	 */
	public static boolean isFlightActive(final ServerPlayer player) {
		return isFlyEnabled(player) && stateGrantsFlight(player, getState(player));
	}

	/**
	 * Recomputes flight after something outside the player changed — a config edit or a permission
	 * change — granting or revoking as needed. Revocation grants a soft landing if it drops someone
	 * out of the air.
	 */
	public static void refreshFlight(final ServerPlayer player) {
		if (isFlightActive(player)) {
			applyFlightAbilities(player);
		} else if (player.getAbilities().mayfly) {
			teardownFlight(player, true);
		}

		syncTo(player);
	}

	/**
	 * Sets the flight preference. The ability itself is granted only in the god-tier states
	 * ({@link #isFlightActive}); it never touches game mode. Engaging is the client's native
	 * double-tap-jump; the server merely holds {@code mayfly} open, which also natively suppresses
	 * fall damage ({@code Player#causeFallDamage} checks {@code mayfly}) and exempts the player from
	 * the floating kick.
	 */
	public static void setFlyEnabled(final ServerPlayer player, final boolean enabled) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		// Captured before the flag flips, so the teardown knows whether flight was genuinely active —
		// a dormant preference being switched off mid-air must not book a free landing.
		boolean hadFlight = isFlightActive(player);

		EssentialsData.get(server).setFlyEnabled(player.getUUID(), enabled);

		if (enabled) {
			applyFlightAbilities(player);
		} else {
			teardownFlight(player, hadFlight);
		}

		syncTo(player);
	}

	/**
	 * Hands the ability flags back to the game mode, booking a soft landing only when this actually
	 * took flight away from someone airborne.
	 */
	private static void teardownFlight(final ServerPlayer player, final boolean hadFlight) {
		// Creative keeps mayfly, survival loses it.
		player.gameMode().updatePlayerAbilities(player.getAbilities());

		if (hadFlight && !player.getAbilities().mayfly && !player.onGround()) {
			grantSoftLanding(player);
		}

		player.onUpdateAbilities();
	}

	public static int getFlySpeed(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return server == null
			? EssentialsConfig.get().defaultFlySpeed
			: EssentialsData.get(server).getFlySpeed(player.getUUID());
	}

	/** Applies immediately — including to creative flight, which reads the same abilities field. */
	public static void setFlySpeed(final ServerPlayer player, final int multiplier) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		EssentialsData.get(server).setFlySpeed(player.getUUID(), multiplier);
		applyFlightAbilities(player);
	}

	/**
	 * Brings the abilities in line with the player's flight settings, syncing only when something
	 * actually changed. Raises {@code mayfly} but never lowers it — revoking is the one-off in
	 * {@link #setFlyEnabled}, so a mode-granted mayfly is never fought.
	 */
	public static void applyFlightAbilities(final ServerPlayer player) {
		Abilities abilities = player.getAbilities();
		boolean changed = false;

		if (isFlightActive(player) && !abilities.mayfly) {
			abilities.mayfly = true;
			changed = true;
		}

		float speed = BASE_FLY_SPEED * getFlySpeed(player);

		if (abilities.getFlyingSpeed() != speed) {
			abilities.setFlyingSpeed(speed);
			changed = true;
		}

		if (changed) {
			player.onUpdateAbilities();
		}
	}

	/**
	 * Called from the {@code setGameModeForPlayer} mixin — the one method every ability wipe flows
	 * through (game-mode change, respawn, dimension transfer, and initial join construction).
	 *
	 * <p>Re-asserts flight for fly-enabled players the instant vanilla stomps it, and books a soft
	 * landing when a mode that flew was swapped for one that cannot while the player was airborne.
	 */
	public static void onGameModeChanged(final ServerPlayer player, final GameType previousMode) {
		if (player.level().getServer() == null) {
			return;
		}

		applyFlightAbilities(player);

		boolean couldFly = previousMode == GameType.CREATIVE || previousMode == GameType.SPECTATOR;

		if (couldFly && !player.getAbilities().mayfly && !player.onGround()) {
			grantSoftLanding(player);
		}
	}

	public static void grantSoftLanding(final ServerPlayer player) {
		SOFT_LANDINGS.add(player.getUUID());
	}

	/** True exactly once per granted ticket; the caller negates that fall damage. */
	public static boolean consumeSoftLanding(final ServerPlayer player) {
		return SOFT_LANDINGS.remove(player.getUUID());
	}

	/**
	 * Retires tickets whose fall ended harmlessly — grounded, swimming, or flying again. Without this,
	 * a soft landing would leave the ticket armed to excuse some unrelated future fall. Skips offline
	 * players on purpose: logging out mid-fall and back in resumes the same fall, which the ticket
	 * still rightly covers.
	 */
	public static void tickSoftLandings(final MinecraftServer server) {
		if (SOFT_LANDINGS.isEmpty()) {
			return;
		}

		SOFT_LANDINGS.removeIf(id -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			return player != null && (player.onGround() || player.isInWater() || player.getAbilities().flying);
		});
	}

	/** Whether Build Mode should grant this player night vision. Toggled by {@code /build nv}. */
	public static void setBuildNightVision(final ServerPlayer player, final boolean enabled) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		EssentialsData.get(server).setBuildNightVision(player.getUUID(), enabled);

		// Takes effect immediately when already on the scaffolding, so to speak.
		if (getState(player) == AdminState.BUILD) {
			if (enabled) {
				addBuildNightVision(player);
			} else {
				player.removeEffect(MobEffects.NIGHT_VISION);
			}
		}
	}

	public static boolean getBuildNightVision(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		return server == null || EssentialsData.get(server).getBuildNightVision(player.getUUID());
	}

	private static void addBuildNightVision(final ServerPlayer player) {
		// Ambient and particle-free, so building is not spent staring through a cloud of swirls.
		player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, true, false, true));
	}

	public static void clearBuildPerks(final ServerPlayer player) {
		for (Holder<Attribute> attribute : REACH_ATTRIBUTES) {
			AttributeInstance instance = player.getAttribute(attribute);

			if (instance != null) {
				instance.removeModifier(BUILD_REACH_MODIFIER);
			}
		}

		player.removeEffect(MobEffects.NIGHT_VISION);
	}

	/**
	 * Clears whatever is active and puts the player back in a normal game mode.
	 *
	 * <p>Deliberately does more than toggling a state off with its own command. {@code /admin off} is
	 * the "put me back to normal" escape hatch, so it pulls the player out of creative even when the
	 * state they were in never put them there. Repeating a state's own command instead just drops
	 * that one effect and leaves the game mode alone.
	 *
	 * @return whether anything actually changed
	 */
	public static boolean turnOff(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return false;
		}

		boolean wasActive = getState(player) != AdminState.NONE;
		setState(player, AdminState.NONE);

		// Leaving a creative state already restored the game mode, so this only bites when the player
		// put themselves in creative by hand while hidden, passive, or in no state at all.
		if (player.gameMode() == GameType.CREATIVE) {
			player.setGameMode(EssentialsData.get(server).getLastNonCreativeMode(player.getUUID()));
			return true;
		}

		return wasActive;
	}

	/** Outcome of a saved-location teleport, so the command layer can say what actually went wrong. */
	public enum TeleportResult {
		OK,
		NOT_SET,
		DIMENSION_MISSING
	}

	/** Saves where the admin is standing, puts them on duty, and sends them to {@code target}. */
	public static boolean teleportToPlayer(final ServerPlayer admin, final ServerPlayer target) {
		MinecraftServer server = admin.level().getServer();

		if (server == null || !(target.level() instanceof ServerLevel targetLevel)) {
			return false;
		}

		// Captured before both the state change and the teleport, so the return point is exactly where
		// the admin was standing when they typed the command.
		EssentialsData.get(server).setReturnPoint(admin.getUUID(), SavedLocation.capture(admin));

		setState(admin, AdminState.ADMIN);

		// Keeps the admin's own facing rather than the target's, matching vanilla /tp.
		admin.teleportTo(targetLevel, target.getX(), target.getY(), target.getZ(), Set.of(), admin.getYRot(), admin.getXRot(), false);
		return true;
	}

	/**
	 * Sends the player to their stored return point, and stores where they were standing as the new
	 * one.
	 *
	 * <p>The swap is the whole point: running {@code /admin back} again returns them to where they
	 * just were, so an admin can bounce between a job and wherever they came from indefinitely.
	 */
	public static TeleportResult returnToPoint(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return TeleportResult.DIMENSION_MISSING;
		}

		EssentialsData data = EssentialsData.get(server);
		Optional<SavedLocation> stored = data.getReturnPoint(player.getUUID());

		if (stored.isEmpty()) {
			return TeleportResult.NOT_SET;
		}

		SavedLocation here = SavedLocation.capture(player);

		if (!stored.get().teleport(player, server)) {
			return TeleportResult.DIMENSION_MISSING;
		}

		data.setReturnPoint(player.getUUID(), here);
		return TeleportResult.OK;
	}

	/**
	 * Pins the player's current spot as their spawn point.
	 *
	 * <p>The unnamed home <em>is</em> the respawn point rather than a copy of it, which is what makes
	 * "your home is your spawn until you sleep in a bed" true for free — a bed moves the respawn point
	 * through vanilla, and the home follows because there is nothing separate to fall out of sync.
	 * Forced, so it works anywhere rather than only where a bed or anchor would be valid.
	 */
	public static void setSpawnHome(final ServerPlayer player) {
		LevelData.RespawnData respawnData = LevelData.RespawnData.of(
			player.level().dimension(), player.blockPosition(), player.getYRot(), player.getXRot()
		);

		player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawnData, true), false);
	}

	/** Where {@code /home} sends this player: their respawn point, or world spawn if they have none. */
	public static SavedLocation spawnHomeOf(final ServerPlayer player, final MinecraftServer server) {
		ServerPlayer.RespawnConfig config = player.getRespawnConfig();
		LevelData.RespawnData respawnData = config != null
			? config.respawnData()
			: server.overworld().getRespawnData();

		BlockPos pos = respawnData.pos();

		// Centred on the block, and a touch above it, so the arrival is not inside the bed or floor.
		return new SavedLocation(
			respawnData.dimension(),
			new Vec3(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5),
			respawnData.yaw(),
			respawnData.pitch()
		);
	}

	/** Sends the player to their spawn point. Never "not set" — world spawn is the fallback. */
	public static TeleportResult goHome(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return TeleportResult.DIMENSION_MISSING;
		}

		return spawnHomeOf(player, server).teleport(player, server) ? TeleportResult.OK : TeleportResult.DIMENSION_MISSING;
	}

	/**
	 * Saves a named home in the given tier.
	 *
	 * <p>Named homes never touch the respawn point — only the unnamed {@link #setSpawnHome} does that.
	 * The limit is passed in rather than read here, because it comes from the caller's permission with
	 * the config as fallback.
	 *
	 * @return false when the player is already at their cap and this name is a new one
	 */
	public static boolean setNamedHome(final ServerPlayer player, final HomeTier tier, final String name, final int limit) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return false;
		}

		EssentialsData data = EssentialsData.get(server);
		Set<String> existing = data.getHomeNames(player.getUUID(), tier);

		// Overwriting an existing name is always allowed; only genuinely new ones consume a slot.
		if (!existing.contains(name) && existing.size() >= limit) {
			return false;
		}

		data.setHome(player.getUUID(), tier, name, SavedLocation.capture(player));
		return true;
	}

	public static TeleportResult goNamedHome(final ServerPlayer player, final HomeTier tier, final String name) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return TeleportResult.DIMENSION_MISSING;
		}

		Optional<SavedLocation> home = EssentialsData.get(server).getHome(player.getUUID(), tier, name);

		if (home.isEmpty()) {
			return TeleportResult.NOT_SET;
		}

		return home.get().teleport(player, server) ? TeleportResult.OK : TeleportResult.DIMENSION_MISSING;
	}

	public static boolean removeNamedHome(final ServerPlayer player, final HomeTier tier, final String name) {
		MinecraftServer server = player.level().getServer();
		return server != null && EssentialsData.get(server).removeHome(player.getUUID(), tier, name);
	}

	public static Set<String> namedHomes(final ServerPlayer player, final HomeTier tier) {
		MinecraftServer server = player.level().getServer();
		return server == null ? Set.of() : EssentialsData.get(server).getHomeNames(player.getUUID(), tier);
	}

	/**
	 * Whether {@code viewer} is allowed to see {@code target}. Called from the entity tracker on a hot
	 * path, so the common case (nobody is hidden) gets out before any permission work.
	 */
	public static boolean canSee(final ServerPlayer viewer, final ServerPlayer target) {
		if (viewer == target) {
			return true;
		}

		if (!getState(target).hiddenFromPlayers()) {
			return true;
		}

		// The vanished see each other. Without this two ghosts working the same incident would be
		// invisible to one another, which is worse than useless for coordinating.
		if (getState(viewer).hiddenFromPlayers()) {
			return true;
		}

		return AdminPermissions.check(viewer, AdminPermissions.ADMIN_SEE_HIDDEN);
	}

	public static void onJoin(final ServerPlayer player) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		syncTo(player);

		// State persists across logout, so someone rejoining while vanished must be taken off the web map
		// again — the map only learns about them from us, and it has just seen them come online.
		WebMapIntegration.refresh(player);

		// The setGameModeForPlayer mixin already restored the flag values during construction, but that
		// ran before the connection existed — so the fields are right and it is only the client that has
		// not been told. Force one abilities packet to be certain.
		applyFlightAbilities(player);
		player.onUpdateAbilities();

		// Transient attribute modifiers do not survive a relog, so Build Mode's reach has to be put
		// back on by hand.
		if (getState(player) == AdminState.BUILD) {
			applyBuildPerks(player);
		}

		if (getState(player).protectsPlayer()) {
			restoreVitals(player);
		}

		// Vanilla just sent this client the whole tab list, hidden players included. Take them back out.
		if (!AdminPermissions.check(player, AdminPermissions.ADMIN_SEE_HIDDEN)) {
			List<UUID> hidden = server.getPlayerList()
				.getPlayers()
				.stream()
				.filter(other -> other != player && getState(other).hiddenFromPlayers())
				.map(ServerPlayer::getUUID)
				.toList();

			if (!hidden.isEmpty()) {
				player.connection.send(new ClientboundPlayerInfoRemovePacket(hidden));
			}
		}
	}

	/** Adds or removes this player from everyone else's tab list and locator bar to match their new state. */
	private static void refreshVisibility(final ServerPlayer player, final boolean hidden) {
		MinecraftServer server = player.level().getServer();

		if (server == null) {
			return;
		}

		// Web maps read the player list directly rather than the packets below, so they need telling
		// separately — this is the one leak the tracker-level vanish cannot close on its own.
		WebMapIntegration.refresh(player);

		// The locator bar would otherwise keep broadcasting a hidden player's position, which defeats the
		// point of hiding the entity. Note this drops the marker for everyone, ops included — they can
		// still see the player themselves, just not the bar dot.
		if (player.level() instanceof ServerLevel serverLevel) {
			ServerWaypointManager waypoints = serverLevel.getWaypointManager();

			if (hidden) {
				waypoints.untrackWaypoint(player);
			} else {
				waypoints.trackWaypoint(player);
			}
		}

		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			if (viewer == player) {
				continue;
			}

			if (hidden && !AdminPermissions.check(viewer, AdminPermissions.ADMIN_SEE_HIDDEN)) {
				viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
			} else {
				viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
			}
		}
	}

	/**
	 * Pushes state to the client so it can draw the indicator. Gated on {@code canSend}: a client
	 * without this mod has no receiver registered, and an unknown payload would disconnect it.
	 */
	public static void syncTo(final ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, AdminStatePayload.TYPE)) {
			ServerPlayNetworking.send(
				player,
				new AdminStatePayload(
					getState(player), isFlightActive(player), AfkManager.isAfk(player), PresenceManager.isAppearingOffline(player)
				)
			);
		}
	}
}
