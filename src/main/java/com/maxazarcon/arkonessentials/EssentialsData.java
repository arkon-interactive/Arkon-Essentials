package com.maxazarcon.arkonessentials;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Disk-backed record of who is in which state, their inventories, the game mode to put them back
 * into, and the two locations they can teleport to.
 *
 * <p>Lives in the server's global saved data rather than a level's, so it is not tied to whichever
 * dimension a player happened to be standing in. Persisting it is what stops a crash or restart
 * mid-admin-mode from destroying an inventory.
 *
 * <h2>Why mistakes here are expensive</h2>
 *
 * <p>A decode failure is <strong>silent data loss</strong>, not an error. {@code SavedDataStorage} does
 * this:
 *
 * <pre>
 * return type.codec().parse(ops, tag.get("data"))
 *     .resultOrPartial(error -> LOGGER.error(...))
 *     .orElse(null);
 * </pre>
 *
 * <p>A failed parse yields null, {@code computeIfAbsent} then builds a fresh empty instance, and
 * {@code set()} marks it dirty immediately — so <em>failing to read the file schedules an overwrite of
 * it</em>. Everything below is shaped around never failing to read.
 *
 * <h2>Adding a persisted field</h2>
 *
 * <p>Fields are grouped into {@link Preferences}, {@link Locations} and {@link Legacy}, each a
 * {@link MapCodec} costing <strong>one</strong> argument in {@code ENTRY_CODEC} while still writing its
 * keys flat at the entry's own level. The entry codec therefore sits at eight of DFU's sixteen, with
 * room to grow, and adding a field usually means touching one small record rather than this whole file.
 *
 * <p>To add a per-player preference:
 *
 * <ol>
 *   <li>Add the component and its codec line to {@link Preferences}, and the value to
 *       {@code Preferences.DEFAULT}. Use {@code optionalFieldOf} <strong>without</strong> a default
 *       where the setting should follow a config default, so it stays absent-until-set.
 *   <li>Add a {@code withX} on {@link Entry} that rebuilds the preferences record.
 *   <li>Add the getter/setter pair on {@code EssentialsData} itself.
 *   <li>Extend {@code EssentialsDataTest} with a value that cannot be confused with its neighbours — a
 *       transposed pair of same-typed adjacent fields compiles perfectly, and the test is the only
 *       thing that catches it.
 * </ol>
 *
 * <p>{@code isDefault()} needs no update: {@link Preferences#isDefault()} is {@code equals(DEFAULT)},
 * so a new field is covered automatically. That was previously a hand-written clause the compiler could
 * not check, and forgetting it left entries that never got pruned.
 *
 * <p>Bump {@link #DATA_VERSION} only if an older build reading the file would lose something a player
 * would mind — inventories, saved locations. Do <em>not</em> bump it for an additive setting with a
 * sane default: an older build silently resetting a preference is far cheaper than the guard disabling
 * the mod outright.
 */
public class EssentialsData extends SavedData {
	/**
	 * Schema version of the saved file.
	 *
	 * <p>Bump this whenever the persisted shape changes in a way an older build could not faithfully
	 * write back. Version 1 was the 1.0.x layout with a single {@code admin_inventory}; version 2 is
	 * per-state loadouts plus saved locations; version 3 splits loadouts by game mode and gives every
	 * mode its own stash.
	 *
	 * <p>Version 3 earns the bump: an older build would read the two loadout slots of a mode as one — the
	 * {@code creative} flag it does not know about would collapse and one would overwrite the other — and
	 * would drop every per-mode stash on the floor. Both are inventories, which is exactly the line
	 * between bumping and not.
	 *
	 * <p>Two rules keep the downgrade guard working, and future format changes must respect both:
	 * {@code data_version} stays a plain int at the root, and {@code players} stays an optional list.
	 * The guard can only report a version it is still able to read.
	 */
	public static final int DATA_VERSION = 3;

	/**
	 * Persisted as a list of pairs rather than {@link Codec#unboundedMap}. A map codec needs its keys
	 * to survive as map keys in the target format, which holds for NBT but quietly stops holding for
	 * any ops that compress maps. A list has no such dependency.
	 */
	/**
	 * Deterministic write order for loadout slots.
	 *
	 * <p>Declared before the codec that uses it: static initialisers run in source order, so referring to
	 * it from above would be an illegal forward reference rather than merely untidy.
	 */
	private static final Comparator<Map.Entry<LoadoutKey, InventorySnapshot>> LOADOUT_ORDER =
		Comparator.<Map.Entry<LoadoutKey, InventorySnapshot>, Integer>comparing(e -> e.getKey().state().ordinal())
			.thenComparing(e -> e.getKey().creative());

	private static final Codec<Map<LoadoutKey, InventorySnapshot>> LOADOUTS_CODEC = Loadout.CODEC
		.listOf()
		.xmap(
			list -> {
				Map<LoadoutKey, InventorySnapshot> loadouts = new LinkedHashMap<>();
				list.forEach(loadout -> loadouts.put(new LoadoutKey(loadout.state(), loadout.creative()), loadout.inventory()));
				return loadouts;
			},
			loadouts -> loadouts.entrySet()
				.stream()
				// Sorted rather than left in map order, so the file does not reshuffle between saves for
				// no reason. A record key cannot use EnumMap, so insertion order is all the map offers.
				.sorted(LOADOUT_ORDER)
				.map(e -> new Loadout(e.getKey().state(), e.getKey().creative(), e.getValue()))
				.toList()
		);

	/** Per-mode stash of the player's <em>own</em> gear, as it was when they entered that mode. */
	private static final Codec<Map<AdminState, InventorySnapshot>> STASHES_CODEC = Stash.CODEC
		.listOf()
		.xmap(
			list -> {
				Map<AdminState, InventorySnapshot> stashes = new EnumMap<>(AdminState.class);
				list.forEach(stash -> stashes.put(stash.state(), stash.inventory()));
				return stashes;
			},
			stashes -> stashes.entrySet().stream().map(e -> new Stash(e.getKey(), e.getValue())).toList()
		);

	/**
	 * An unmodifiable copy that keeps insertion order.
	 *
	 * <p>Deliberately not {@link Map#copyOf}: its iteration order is unspecified and randomised per JVM
	 * run, which would shuffle home listings between restarts for no visible reason.
	 */
	private static Map<String, SavedLocation> orderedCopy(final Map<String, SavedLocation> homes) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(homes));
	}

	/** Named homes, persisted as a list of pairs for the same reason the loadouts are. */
	private static final Codec<Map<String, SavedLocation>> HOMES_CODEC = Home.CODEC
		.listOf()
		.xmap(
			list -> {
				Map<String, SavedLocation> homes = new LinkedHashMap<>();
				list.forEach(home -> homes.put(home.name(), home.location()));
				return homes;
			},
			homes -> homes.entrySet().stream().map(e -> new Home(e.getKey(), e.getValue())).toList()
		);

	private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
		UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::playerId),
		AdminState.CODEC.optionalFieldOf("state", AdminState.NONE).forGetter(Entry::state),
		// Read-only from 0.30 on: a single global stash, folded into the per-mode map by fromDisk.
		InventorySnapshot.CODEC.optionalFieldOf("survival_inventory").forGetter(entry -> Optional.<InventorySnapshot>empty()),
		LOADOUTS_CODEC.optionalFieldOf("loadouts", Map.of()).forGetter(Entry::loadouts),
		STASHES_CODEC.optionalFieldOf("stashes", Map.of()).forGetter(Entry::stashes),
		GameType.CODEC.optionalFieldOf("last_non_creative_mode", GameType.SURVIVAL).forGetter(Entry::lastNonCreativeMode),
		// Three grouped MapCodecs rather than seventeen loose fields. Each writes its own keys at THIS
		// level — the file layout is unchanged — while costing one argument instead of many, which is
		// what keeps this clear of DFU's 16-argument ceiling. Add new fields inside the group they belong
		// to, not here.
		Locations.CODEC.forGetter(Entry::locations),
		Preferences.CODEC.forGetter(Entry::preferences),
		// Always written as NONE, so the retired keys are read from old files and never written back.
		Legacy.CODEC.forGetter(entry -> Legacy.NONE)
	).apply(instance, Entry::fromDisk));

	public static final Codec<EssentialsData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		// Read before anything else, and always written as the current version.
		Codec.INT.optionalFieldOf("data_version", 1).forGetter(data -> DATA_VERSION),
		// Purely diagnostic: lets a refusal name the build an operator needs to put back.
		Codec.STRING.optionalFieldOf("written_by", "unknown").forGetter(data -> ArkonEssentials.VERSION),
		ENTRY_CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(EssentialsData::toEntries)
	).apply(instance, EssentialsData::load));

	public static final SavedDataType<EssentialsData> TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, "admin_mode"),
		EssentialsData::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	/**
	 * The storage key this data used before the mod was renamed from Admin Mode. Read once, never
	 * written — the mod id feeds the saved-data path, so without this the rename would silently orphan
	 * every stashed inventory, home and loadout in an existing world.
	 */
	private static final SavedDataType<EssentialsData> LEGACY_TYPE = new SavedDataType<>(
		Identifier.fromNamespaceAndPath("adminmode", "admin_mode"),
		EssentialsData::new,
		CODEC,
		DataFixTypes.LEVEL
	);

	private final Map<UUID, Entry> entries = new HashMap<>();
	private boolean locked;

	public static EssentialsData get(final MinecraftServer server) {
		SavedDataStorage storage = server.getDataStorage();
		EssentialsData data = storage.get(TYPE);

		if (data != null) {
			return data;
		}

		// Nothing under the current key yet: adopt the pre-rename file if one is there. set() marks it
		// dirty, so it is rewritten under the new key on the next save and the old file goes stale
		// rather than being deleted — a free rollback if the rename is ever reverted.
		EssentialsData legacy = storage.get(LEGACY_TYPE);

		if (legacy != null) {
			storage.set(TYPE, legacy);
			ArkonEssentials.LOGGER.info("Adopted saved data from the pre-rename 'adminmode' storage key.");
			return legacy;
		}

		return storage.computeIfAbsent(TYPE);
	}

	private static EssentialsData load(final int version, final String writtenBy, final List<Entry> entries) {
		EssentialsData data = new EssentialsData();

		// Written by a build that knows more than this one does. Reading it through an older schema and
		// then writing that back would quietly destroy everything this build does not understand, so
		// refuse to do either.
		if (version > DATA_VERSION) {
			data.locked = true;
			ArkonEssentials.LOGGER.error(
				"Admin Mode saved data is schema version {}, written by version {}, but this build ({}) understands only schema {}. "
					+ "Commands are disabled and nothing will be written, so the file on disk is left intact. "
					+ "Reinstall Admin Mode {} or newer, or move data/arkonessentials/admin_mode.dat aside to start fresh.",
				version,
				writtenBy,
				ArkonEssentials.VERSION,
				DATA_VERSION,
				writtenBy
			);
			return data;
		}

		for (Entry entry : entries) {
			data.entries.put(entry.playerId(), entry);
		}

		return data;
	}

	/**
	 * Whether this data came from a newer build and is therefore untouchable. Commands check it so
	 * they can explain themselves rather than silently doing nothing.
	 */
	public boolean isLocked() {
		return this.locked;
	}

	/**
	 * Refuses to mark a locked instance dirty.
	 *
	 * <p>This is the load-bearing half of the guard. {@code SavedDataStorage} only writes entries it
	 * finds dirty, so staying clean is what keeps the newer file on disk untouched. Note the storage
	 * calls {@code setDirty()} on construction, which is exactly the call this has to swallow.
	 */
	@Override
	public void setDirty(final boolean dirty) {
		if (!this.locked) {
			super.setDirty(dirty);
		}
	}

	private List<Entry> toEntries() {
		return new ArrayList<>(this.entries.values());
	}

	/**
	 * This player's entry, or a blank one.
	 *
	 * <p>Never null and never stored on read, so a getter for someone who has never used the mod costs
	 * nothing and leaves the map alone. Entries only enter the map through {@link #put}, which is what
	 * keeps the saved file to players who actually have something saved.
	 */
	private Entry entry(final UUID playerId) {
		return this.entries.getOrDefault(playerId, Entry.empty(playerId));
	}

	/**
	 * Stores the entry, dropping it entirely once it carries nothing worth remembering.
	 *
	 * <p>Every mutation funnels through here — the {@code withX} methods return new records and this is
	 * what commits them — so the locked check and the dirty flag each need to exist in exactly one place.
	 * A setter that writes {@code this.entries} directly would bypass both.
	 */
	private void put(final Entry entry) {
		// Belt and braces alongside the setDirty override: a locked instance holds no entries, and
		// mutating it in memory would only invent state that the real file does not agree with.
		if (this.locked) {
			return;
		}

		if (entry.isDefault()) {
			this.entries.remove(entry.playerId());
		} else {
			this.entries.put(entry.playerId(), entry);
		}

		this.setDirty();
	}

	public AdminState getState(final UUID playerId) {
		return entry(playerId).state();
	}

	public void setState(final UUID playerId, final AdminState state) {
		put(entry(playerId).withState(state));
	}

	/** The game mode to drop the player back into when they come off duty. */
	public GameType getLastNonCreativeMode(final UUID playerId) {
		return entry(playerId).lastNonCreativeMode();
	}

	public void setLastNonCreativeMode(final UUID playerId, final GameType mode) {
		if (mode != GameType.CREATIVE) {
			put(entry(playerId).withLastNonCreativeMode(mode));
		}
	}

	/**
	 * Takes back the gear this player was carrying when they entered {@code state}.
	 *
	 * <p>Per mode rather than one shared stash, so cycling through several modes returns exactly what
	 * you were holding before each of them instead of whatever the last one happened to save.
	 */
	public Optional<InventorySnapshot> takeStash(final UUID playerId, final AdminState state) {
		Entry entry = entry(playerId);
		Optional<InventorySnapshot> inventory = Optional.ofNullable(entry.stashes().get(state));

		if (inventory.isPresent()) {
			Map<AdminState, InventorySnapshot> stashes = new EnumMap<>(AdminState.class);
			stashes.putAll(entry.stashes());
			stashes.remove(state);
			put(entry.withStashes(stashes));
		}

		return inventory;
	}

	public void putStash(final UUID playerId, final AdminState state, final InventorySnapshot inventory) {
		Entry entry = entry(playerId);
		Map<AdminState, InventorySnapshot> stashes = new EnumMap<>(AdminState.class);
		stashes.putAll(entry.stashes());
		stashes.put(state, inventory);
		put(entry.withStashes(stashes));
	}

	/**
	 * The saved loadout for one slot, kept permanently so nobody restocks.
	 *
	 * <p>Keyed by mode <em>and</em> game mode: a mode entered in creative and the same mode entered in
	 * survival keep separate loadouts, which is what stops creative-spawned items being handed back as
	 * real ones.
	 */
	public Optional<InventorySnapshot> getLoadout(final UUID playerId, final LoadoutKey key) {
		return Optional.ofNullable(entry(playerId).loadouts().get(key));
	}

	public void putLoadout(final UUID playerId, final LoadoutKey key, final InventorySnapshot inventory) {
		Entry entry = entry(playerId);
		Map<LoadoutKey, InventorySnapshot> loadouts = new LinkedHashMap<>(entry.loadouts());
		loadouts.put(key, inventory);
		put(entry.withLoadouts(loadouts));
	}

	/** Where {@code /admin back} will send this player. Overwritten on every use, by design. */
	public Optional<SavedLocation> getReturnPoint(final UUID playerId) {
		return entry(playerId).returnPoint();
	}

	public void setReturnPoint(final UUID playerId, final SavedLocation location) {
		put(entry(playerId).withReturnPoint(Optional.of(location)));
	}

	/**
	 * Where {@code /back} will send this player: wherever they last teleported from, or where they last
	 * died. One field rather than two, so "most recent wins" needs no timestamps — whichever event
	 * happened last is simply the one that wrote it.
	 */
	public Optional<SavedLocation> getBackPoint(final UUID playerId) {
		return entry(playerId).backPoint();
	}

	public void setBackPoint(final UUID playerId, final SavedLocation location) {
		put(entry(playerId).withBackPoint(Optional.of(location)));
	}

	/** Whether the idle timer applies to this player. Toggled by {@code /afkon} and {@code /afkoff}. */
	public boolean getAfkEnabled(final UUID playerId) {
		return entry(playerId).afkEnabled();
	}

	public void setAfkEnabled(final UUID playerId, final boolean enabled) {
		put(entry(playerId).withAfkEnabled(enabled));
	}

	/** Whether this player has opted back into picking items up while vanished. Off by default. */
	public boolean getVanishPickups(final UUID playerId) {
		return entry(playerId).vanishPickups();
	}

	public void setVanishPickups(final UUID playerId, final boolean allowed) {
		put(entry(playerId).withVanishPickups(allowed));
	}

	/** Whether this player has opted back into world interaction while vanished. Off by default. */
	public boolean getVanishInteract(final UUID playerId) {
		return entry(playerId).vanishInteract();
	}

	public void setVanishInteract(final UUID playerId, final boolean allowed) {
		put(entry(playerId).withVanishInteract(allowed));
	}

	/** Extra blocks of reach in Build Mode, falling back to the configured default. */
	public int getReachBonus(final UUID playerId) {
		return entry(playerId).reachBonus().orElseGet(() -> EssentialsConfig.get().defaultBuildReach);
	}

	public void setReachBonus(final UUID playerId, final int bonus) {
		put(entry(playerId).withReachBonus(Optional.of(Mth.clamp(bonus, 0, AdminManager.MAX_REACH_BONUS))));
	}

	/** Whether /fly has granted this player the flight ability, independent of game mode. */
	public boolean getFlyEnabled(final UUID playerId) {
		return entry(playerId).flyEnabled();
	}

	public void setFlyEnabled(final UUID playerId, final boolean enabled) {
		put(entry(playerId).withFlyEnabled(enabled));
	}

	/** Flight speed multiplier, applied to creative flight as well. Falls back to the config default. */
	public int getFlySpeed(final UUID playerId) {
		return entry(playerId).flySpeed().orElseGet(() -> EssentialsConfig.get().defaultFlySpeed);
	}

	public void setFlySpeed(final UUID playerId, final int multiplier) {
		int clamped = Mth.clamp(multiplier, AdminManager.MIN_FLY_SPEED, AdminManager.MAX_FLY_SPEED);
		put(entry(playerId).withFlySpeed(Optional.of(clamped)));
	}

	/** Whether Build Mode grants this player night vision. Falls back to the config default. */
	public boolean getBuildNightVision(final UUID playerId) {
		return entry(playerId).buildNightVision().orElseGet(() -> EssentialsConfig.get().buildNightVisionAvailable);
	}

	public void setBuildNightVision(final UUID playerId, final boolean enabled) {
		put(entry(playerId).withBuildNightVision(Optional.of(enabled)));
	}

	/**
	 * Which set of named homes a call refers to. The two tiers are stored independently, so a staff
	 * location under {@link #ADMIN} never shows up in a player's own list.
	 *
	 * <p>Neither holds the unnamed home — that is the player's respawn point, which lives in vanilla
	 * player data, not here.
	 */
	public enum HomeTier {
		PLAYER,
		ADMIN
	}

	private static Map<String, SavedLocation> homesOf(final Entry entry, final HomeTier tier) {
		return tier == HomeTier.ADMIN ? entry.adminHomes() : entry.homes();
	}

	private static Entry withHomesOf(final Entry entry, final HomeTier tier, final Map<String, SavedLocation> homes) {
		return tier == HomeTier.ADMIN ? entry.withAdminHomes(homes) : entry.withHomes(homes);
	}

	public Optional<SavedLocation> getHome(final UUID playerId, final HomeTier tier, final String name) {
		return Optional.ofNullable(homesOf(entry(playerId), tier).get(name));
	}

	/** Insertion-ordered, so listings read back the way they were created. */
	public Set<String> getHomeNames(final UUID playerId, final HomeTier tier) {
		return homesOf(entry(playerId), tier).keySet();
	}

	public void setHome(final UUID playerId, final HomeTier tier, final String name, final SavedLocation location) {
		Entry entry = entry(playerId);
		Map<String, SavedLocation> homes = new LinkedHashMap<>(homesOf(entry, tier));
		homes.put(name, location);
		put(withHomesOf(entry, tier, homes));
	}

	/** @return whether a home by that name existed */
	public boolean removeHome(final UUID playerId, final HomeTier tier, final String name) {
		Entry entry = entry(playerId);

		if (!homesOf(entry, tier).containsKey(name)) {
			return false;
		}

		Map<String, SavedLocation> homes = new LinkedHashMap<>(homesOf(entry, tier));
		homes.remove(name);
		put(withHomesOf(entry, tier, homes));
		return true;
	}

	/**
	 * Which loadout slot a player is using: the mode, and whether they are in creative while in it.
	 *
	 * <p>The game mode is part of the key on purpose. Without it a mode that can be entered in either —
	 * and {@link AdminState#VANISH} is survival while {@link AdminState#ADMIN} is creative — would let a
	 * creative-spawned loadout be handed straight back in survival, which is an item duplication vector
	 * rather than a convenience.
	 */
	public record LoadoutKey(AdminState state, boolean creative) {
		public static LoadoutKey of(final AdminState state) {
			return new LoadoutKey(state, state.forcesCreative());
		}
	}

	/** One loadout slot, as stored on disk. */
	private record Loadout(AdminState state, boolean creative, InventorySnapshot inventory) {
		static final Codec<Loadout> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			AdminState.CODEC.fieldOf("state").forGetter(Loadout::state),
			// Defaults true when absent, which is exactly right for a pre-0.30 file: loadouts only ever
			// existed for Admin and Build, and both of those are creative.
			Codec.BOOL.optionalFieldOf("creative", true).forGetter(Loadout::creative),
			InventorySnapshot.CODEC.fieldOf("inventory").forGetter(Loadout::inventory)
		).apply(instance, Loadout::new));
	}

	/** One mode's stash of the player's own gear, as stored on disk. */
	private record Stash(AdminState state, InventorySnapshot inventory) {
		static final Codec<Stash> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			AdminState.CODEC.fieldOf("state").forGetter(Stash::state),
			InventorySnapshot.CODEC.fieldOf("inventory").forGetter(Stash::inventory)
		).apply(instance, Stash::new));
	}

	/** One named home, as stored on disk. */
	private record Home(String name, SavedLocation location) {
		static final Codec<Home> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(Home::name),
			SavedLocation.CODEC.fieldOf("location").forGetter(Home::location)
		).apply(instance, Home::new));
	}

	/**
	 * Per-player preferences, grouped so they occupy <strong>one</strong> slot in the entry codec.
	 *
	 * <p>{@link RecordCodecBuilder#mapCodec} writes these keys at the <em>same level</em> as the rest of
	 * the entry — {@code reach_bonus}, {@code fly_speed} and the others sit exactly where they always
	 * did. The grouping is a source-level change only, so no file needs migrating and no
	 * {@code DATA_VERSION} bump is involved. Verified byte-identical against the previous flat layout.
	 *
	 * <p>Most of these are absent-until-set rather than carrying a baked default, so that changing a
	 * default in the config moves every player who never chose their own. A codec default could not do
	 * that: it is captured at class-init, before the config is even read. {@code afk_enabled} is the
	 * exception — there is no config key for it to follow, so a plain default is right.
	 *
	 * <p><strong>Add new preferences here, not to {@link Entry}.</strong> {@link #isDefault()} is
	 * {@code equals(DEFAULT)}, so a new field is covered automatically instead of needing a clause
	 * nobody remembers to write.
	 */
	private record Preferences(
		Optional<Integer> reachBonus,
		boolean flyEnabled,
		Optional<Integer> flySpeed,
		Optional<Boolean> buildNightVision,
		boolean afkEnabled,
		boolean vanishPickups,
		boolean vanishInteract
	) {
		static final Preferences DEFAULT =
			new Preferences(Optional.empty(), false, Optional.empty(), Optional.empty(), true, false, false);

		static final MapCodec<Preferences> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.intRange(0, AdminManager.MAX_REACH_BONUS).optionalFieldOf("reach_bonus").forGetter(Preferences::reachBonus),
			Codec.BOOL.optionalFieldOf("fly_enabled", false).forGetter(Preferences::flyEnabled),
			Codec.intRange(AdminManager.MIN_FLY_SPEED, AdminManager.MAX_FLY_SPEED)
				.optionalFieldOf("fly_speed")
				.forGetter(Preferences::flySpeed),
			Codec.BOOL.optionalFieldOf("build_night_vision").forGetter(Preferences::buildNightVision),
			Codec.BOOL.optionalFieldOf("afk_enabled", true).forGetter(Preferences::afkEnabled),
			// Both default false, meaning blocked: Vanish is meant to leave no trace unless you ask it to.
			Codec.BOOL.optionalFieldOf("vanish_pickups", false).forGetter(Preferences::vanishPickups),
			Codec.BOOL.optionalFieldOf("vanish_interact", false).forGetter(Preferences::vanishInteract)
		).apply(instance, Preferences::new));

		boolean isDefault() {
			return this.equals(DEFAULT);
		}
	}

	/**
	 * Everywhere a player can be sent, grouped into one slot. Flattened the same way as
	 * {@link Preferences} — these keys stay at the entry's own level.
	 *
	 * <p>{@code return_point} and {@code back_point} are deliberately separate fields: the first belongs
	 * to the admin ping-pong and is rewritten by {@code /admin tp}, which would otherwise stamp over a
	 * player's own last position every time staff teleported.
	 */
	private record Locations(
		Optional<SavedLocation> returnPoint,
		Optional<SavedLocation> backPoint,
		Map<String, SavedLocation> homes,
		Map<String, SavedLocation> adminHomes
	) {
		static final Locations EMPTY = new Locations(Optional.empty(), Optional.empty(), Map.of(), Map.of());

		static final MapCodec<Locations> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			SavedLocation.CODEC.optionalFieldOf("return_point").forGetter(Locations::returnPoint),
			SavedLocation.CODEC.optionalFieldOf("back_point").forGetter(Locations::backPoint),
			HOMES_CODEC.optionalFieldOf("homes", Map.of()).forGetter(Locations::homes),
			HOMES_CODEC.optionalFieldOf("admin_homes", Map.of()).forGetter(Locations::adminHomes)
		).apply(instance, Locations::new));

		boolean isDefault() {
			return this.returnPoint.isEmpty() && this.backPoint.isEmpty() && this.homes.isEmpty() && this.adminHomes.isEmpty();
		}

		Locations withHomes(final HomeTier tier, final Map<String, SavedLocation> newHomes) {
			return tier == HomeTier.ADMIN
				? new Locations(this.returnPoint, this.backPoint, this.homes, orderedCopy(newHomes))
				: new Locations(this.returnPoint, this.backPoint, orderedCopy(newHomes), this.adminHomes);
		}
	}

	/**
	 * Fields no longer written, kept readable so an upgrade never silently drops them.
	 *
	 * <p>Read-only by construction: {@link Entry} hands {@link #NONE} to the codec, so these keys are
	 * parsed from an old file and then never written back. {@link Entry#fromDisk} folds them into their
	 * replacements.
	 */
	private record Legacy(Optional<SavedLocation> home, Optional<InventorySnapshot> adminInventory) {
		static final Legacy NONE = new Legacy(Optional.empty(), Optional.empty());

		static final MapCodec<Legacy> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			// The unnamed home became the player's respawn point, so a legacy one is folded into the named
			// homes under "home" rather than being thrown away.
			SavedLocation.CODEC.optionalFieldOf("home").forGetter(Legacy::home),
			// 1.0.x kept the admin loadout in its own field, before Build Mode made loadouts per-state.
			InventorySnapshot.CODEC.optionalFieldOf("admin_inventory").forGetter(Legacy::adminInventory)
		).apply(instance, Legacy::new));
	}

	/**
	 * One player's saved state.
	 *
	 * <p>The grouped components keep the codec well clear of DFU's 16-argument ceiling — this sits at
	 * eight. The accessors below delegate into those groups so the rest of this class, and every call
	 * site, still asks for {@code reachBonus()} or {@code homes()} directly.
	 */
	private record Entry(
		UUID playerId,
		AdminState state,
		Map<LoadoutKey, InventorySnapshot> loadouts,
		Map<AdminState, InventorySnapshot> stashes,
		GameType lastNonCreativeMode,
		Locations locations,
		Preferences preferences
	) {
		// Delegates into the grouped records. Purely so grouping stayed a change to this file alone.
		Optional<SavedLocation> returnPoint() {
			return this.locations.returnPoint();
		}

		Optional<SavedLocation> backPoint() {
			return this.locations.backPoint();
		}

		Map<String, SavedLocation> homes() {
			return this.locations.homes();
		}

		Map<String, SavedLocation> adminHomes() {
			return this.locations.adminHomes();
		}

		Optional<Integer> reachBonus() {
			return this.preferences.reachBonus();
		}

		boolean flyEnabled() {
			return this.preferences.flyEnabled();
		}

		Optional<Integer> flySpeed() {
			return this.preferences.flySpeed();
		}

		Optional<Boolean> buildNightVision() {
			return this.preferences.buildNightVision();
		}

		boolean afkEnabled() {
			return this.preferences.afkEnabled();
		}

		/**
		 * Builds an entry from disk, folding the two retired fields into their replacements rather than
		 * dropping them.
		 */
		static Entry fromDisk(
			final UUID playerId,
			final AdminState state,
			final Optional<InventorySnapshot> legacySurvivalInventory,
			final Map<LoadoutKey, InventorySnapshot> loadouts,
			final Map<AdminState, InventorySnapshot> stashes,
			final GameType lastNonCreativeMode,
			final Locations locations,
			final Preferences preferences,
			final Legacy legacy
		) {
			Map<LoadoutKey, InventorySnapshot> mergedLoadouts = new LinkedHashMap<>(loadouts);
			legacy.adminInventory().ifPresent(
				inventory -> mergedLoadouts.putIfAbsent(LoadoutKey.of(AdminState.ADMIN), inventory)
			);

			// The pre-0.30 single stash belongs to whichever mode the player is currently in, because that
			// is the only time one existed — it was written on the way into a loadout state and taken back
			// on the way out. If the state somehow does not use a loadout, it is filed under ADMIN rather
			// than dropped: a wrong drawer is recoverable with /admin and /admin off, lost gear is not.
			Map<AdminState, InventorySnapshot> mergedStashes = new EnumMap<>(AdminState.class);
			mergedStashes.putAll(stashes);
			legacySurvivalInventory.ifPresent(inventory -> mergedStashes.putIfAbsent(
				state.stashesInventory() ? state : AdminState.ADMIN, inventory
			));

			// Lands in the admin tier: named homes only ever existed for admins before the split, so
			// that is where a pre-split "home" belongs.
			Map<String, SavedLocation> mergedAdminHomes = new LinkedHashMap<>(locations.adminHomes());
			legacy.home().ifPresent(location -> mergedAdminHomes.putIfAbsent("home", location));

			Locations merged = new Locations(
				locations.returnPoint(), locations.backPoint(), orderedCopy(locations.homes()), orderedCopy(mergedAdminHomes)
			);

			return new Entry(
				playerId, state, Map.copyOf(mergedLoadouts), Map.copyOf(mergedStashes), lastNonCreativeMode, merged, preferences
			);
		}

		static Entry empty(final UUID playerId) {
			return new Entry(
				playerId,
				AdminState.NONE,
				Map.of(),
				Map.of(),
				GameType.SURVIVAL,
				Locations.EMPTY,
				Preferences.DEFAULT
			);
		}

		boolean isDefault() {
			return this.state == AdminState.NONE
				&& this.loadouts.isEmpty()
				&& this.stashes.isEmpty()
				&& this.lastNonCreativeMode == GameType.SURVIVAL
				&& this.locations.isDefault()
				&& this.preferences.isDefault();
		}

		Entry withState(final AdminState newState) {
			return new Entry(
				this.playerId, newState, this.loadouts, this.stashes, this.lastNonCreativeMode,
				this.locations, this.preferences
			);
		}

		Entry withStashes(final Map<AdminState, InventorySnapshot> newStashes) {
			return new Entry(
				this.playerId, this.state, this.loadouts, Map.copyOf(newStashes), this.lastNonCreativeMode,
				this.locations, this.preferences
			);
		}

		Entry withLoadouts(final Map<LoadoutKey, InventorySnapshot> newLoadouts) {
			return new Entry(
				this.playerId, this.state, Map.copyOf(newLoadouts), this.stashes, this.lastNonCreativeMode,
				this.locations, this.preferences
			);
		}

		Entry withLastNonCreativeMode(final GameType mode) {
			return new Entry(
				this.playerId, this.state, this.loadouts, this.stashes, mode,
				this.locations, this.preferences
			);
		}

		private Entry withLocations(final Locations newLocations) {
			return new Entry(
				this.playerId, this.state, this.loadouts, this.stashes, this.lastNonCreativeMode,
				newLocations, this.preferences
			);
		}

		private Entry withPreferences(final Preferences newPreferences) {
			return new Entry(
				this.playerId, this.state, this.loadouts, this.stashes, this.lastNonCreativeMode,
				this.locations, newPreferences
			);
		}

		Entry withReturnPoint(final Optional<SavedLocation> location) {
			return this.withLocations(
				new Locations(location, this.locations.backPoint(), this.locations.homes(), this.locations.adminHomes())
			);
		}

		Entry withBackPoint(final Optional<SavedLocation> location) {
			return this.withLocations(
				new Locations(this.locations.returnPoint(), location, this.locations.homes(), this.locations.adminHomes())
			);
		}

		Entry withHomes(final Map<String, SavedLocation> newHomes) {
			return this.withLocations(this.locations.withHomes(HomeTier.PLAYER, newHomes));
		}

		Entry withAdminHomes(final Map<String, SavedLocation> newHomes) {
			return this.withLocations(this.locations.withHomes(HomeTier.ADMIN, newHomes));
		}

		Entry withReachBonus(final Optional<Integer> bonus) {
			Preferences p = this.preferences;
			return this.withPreferences(new Preferences(bonus, p.flyEnabled(), p.flySpeed(), p.buildNightVision(), p.afkEnabled(), p.vanishPickups(), p.vanishInteract()));
		}

		Entry withFlyEnabled(final boolean enabled) {
			Preferences p = this.preferences;
			return this.withPreferences(new Preferences(p.reachBonus(), enabled, p.flySpeed(), p.buildNightVision(), p.afkEnabled(), p.vanishPickups(), p.vanishInteract()));
		}

		Entry withFlySpeed(final Optional<Integer> multiplier) {
			Preferences p = this.preferences;
			return this.withPreferences(new Preferences(p.reachBonus(), p.flyEnabled(), multiplier, p.buildNightVision(), p.afkEnabled(), p.vanishPickups(), p.vanishInteract()));
		}

		Entry withBuildNightVision(final Optional<Boolean> enabled) {
			Preferences p = this.preferences;
			return this.withPreferences(new Preferences(p.reachBonus(), p.flyEnabled(), p.flySpeed(), enabled, p.afkEnabled(), p.vanishPickups(), p.vanishInteract()));
		}

		Entry withAfkEnabled(final boolean enabled) {
			Preferences p = this.preferences;
			return this.withPreferences(new Preferences(
				p.reachBonus(), p.flyEnabled(), p.flySpeed(), p.buildNightVision(), enabled, p.vanishPickups(), p.vanishInteract()
			));
		}

		Entry withVanishPickups(final boolean allowed) {
			Preferences p = this.preferences;
			return this.withPreferences(new Preferences(
				p.reachBonus(), p.flyEnabled(), p.flySpeed(), p.buildNightVision(), p.afkEnabled(), allowed, p.vanishInteract()
			));
		}

		Entry withVanishInteract(final boolean allowed) {
			Preferences p = this.preferences;
			return this.withPreferences(new Preferences(
				p.reachBonus(), p.flyEnabled(), p.flySpeed(), p.buildNightVision(), p.afkEnabled(), p.vanishPickups(), allowed
			));
		}

		boolean vanishPickups() {
			return this.preferences.vanishPickups();
		}

		boolean vanishInteract() {
			return this.preferences.vanishInteract();
		}
	}
}
