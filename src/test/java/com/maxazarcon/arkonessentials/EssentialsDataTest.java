package com.maxazarcon.arkonessentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.maxazarcon.arkonessentials.EssentialsData.HomeTier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the whole persistence surface through the real codecs.
 *
 * <p>This exists because the failure mode it guards against is silent: {@code SavedDataStorage}
 * swallows a decode failure, builds fresh empty data, and schedules an overwrite of the file it could
 * not read. A field-order slip in the codec group — trivially easy with {@code return_point} and
 * {@code home} being the same type side by side — would compile cleanly and destroy player data on the
 * next save. Here it fails the build instead.
 */
class EssentialsDataTest {
	private static RegistryOps<Tag> ops;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		// As of 26.x, items get their default components in a second phase: registration only queues
		// initializers, and a real server applies them during resource load with the full registry
		// context (ReloadableServerResources#updateComponentsAndStaticRegistryTags). Without this,
		// constructing any ItemStack dies with "Components not bound yet".
		HolderLookup.Provider registries = VanillaRegistries.createLookup();
		BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);

		ops = registries.createSerializationContext(NbtOps.INSTANCE);
	}

	private static Tag encode(final EssentialsData data) {
		return EssentialsData.CODEC.encodeStart(ops, data).getOrThrow();
	}

	private static EssentialsData decode(final Tag tag) {
		return EssentialsData.CODEC.parse(ops, tag).getOrThrow();
	}

	private static void assertSameStack(final ItemStack expected, final ItemStack actual) {
		assertTrue(
			ItemStack.isSameItemSameComponents(expected, actual),
			() -> "expected " + expected + " but got " + actual
		);
		assertEquals(expected.getCount(), actual.getCount());
	}

	/**
	 * The main guard. Every persisted field is given a distinctive value, encoded, decoded, and checked.
	 *
	 * <p>When adding a field, extend this — and give it a value that <strong>cannot be confused with its
	 * neighbours</strong>. Two same-typed fields side by side in the codec group are the realistic
	 * failure: swapping them compiles, runs, and quietly writes each into the other's slot.
	 */
	@Test
	void fullyPopulatedDataSurvivesRoundTrip() {
		UUID id = UUID.randomUUID();

		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		pickaxe.setDamageValue(120);
		pickaxe.set(DataComponents.CUSTOM_NAME, Component.literal("Prodder"));
		ItemStack torches = new ItemStack(Items.TORCH, 64);
		ItemStack scaffolding = new ItemStack(Items.SCAFFOLDING, 33);

		EssentialsData data = new EssentialsData();
		data.setState(id, AdminState.GHOST);
		data.putSurvivalInventory(id, new InventorySnapshot(List.of(pickaxe, ItemStack.EMPTY, torches), 2));
		data.putLoadout(id, AdminState.ADMIN, new InventorySnapshot(List.of(torches.copy()), 0));
		data.putLoadout(id, AdminState.BUILD, new InventorySnapshot(List.of(scaffolding), 0));
		data.setLastNonCreativeMode(id, GameType.ADVENTURE);
		data.setReturnPoint(id, new SavedLocation(Level.NETHER, new Vec3(1.5, 64.0, -7.25), 90.0F, -12.5F));
		// Deliberately a different dimension and position from the return point: they are the same type,
		// and the admin ping-pong point and the player's /back point must never be confused for one
		// another.
		data.setBackPoint(id, new SavedLocation(Level.OVERWORLD, new Vec3(-200.5, 12.0, 88.75), 180.0F, 45.0F));
		data.setHome(id, HomeTier.PLAYER, "base", new SavedLocation(Level.END, new Vec3(100.0, 49.0, 0.0), 0.0F, 10.0F));
		data.setHome(id, HomeTier.PLAYER, "mine", new SavedLocation(Level.OVERWORLD, new Vec3(-8.0, 12.0, 40.0), 45.0F, 0.0F));
		// Same name in the other tier, deliberately at a different place: the two sets must not bleed.
		data.setHome(id, HomeTier.ADMIN, "base", new SavedLocation(Level.NETHER, new Vec3(7.0, 30.0, 7.0), 0.0F, 0.0F));
		// The two ints and the two booleans are deliberately given values that differ from each other's
		// defaults, so a transposed pair in a wither or the codec group cannot cancel out.
		data.setReachBonus(id, 7);
		data.setFlyEnabled(id, true);
		data.setFlySpeed(id, 3);
		data.setBuildNightVision(id, false);
		// Defaults true, so false is the only value that proves it round-tripped rather than being
		// reconstructed from the codec default.
		data.setAfkEnabled(id, false);
		// Two adjacent booleans with the same default, so give them opposite values — transposing them
		// would otherwise round-trip cleanly and the swap would only show up in game.
		data.setVanishPickups(id, true);
		data.setVanishInteract(id, false);

		EssentialsData decoded = decode(encode(data));

		assertEquals(AdminState.GHOST, decoded.getState(id));
		assertEquals(GameType.ADVENTURE, decoded.getLastNonCreativeMode(id));
		assertEquals(7, decoded.getReachBonus(id));
		assertTrue(decoded.getFlyEnabled(id));
		assertEquals(3, decoded.getFlySpeed(id));
		assertFalse(decoded.getBuildNightVision(id));
		assertFalse(decoded.getAfkEnabled(id));
		assertTrue(decoded.getVanishPickups(id));
		assertFalse(decoded.getVanishInteract(id));

		InventorySnapshot survival = decoded.takeSurvivalInventory(id).orElseThrow();
		assertEquals(3, survival.items().size());
		assertSameStack(pickaxe, survival.items().get(0));
		assertSameStack(ItemStack.EMPTY, survival.items().get(1));
		assertSameStack(torches, survival.items().get(2));
		assertEquals(2, survival.selectedSlot());

		assertSameStack(torches, decoded.getLoadout(id, AdminState.ADMIN).orElseThrow().items().get(0));
		assertSameStack(scaffolding, decoded.getLoadout(id, AdminState.BUILD).orElseThrow().items().get(0));

		// return_point and home are the same type, adjacent in the codec group. This pair of asserts is
		// the one that catches them swapped.
		SavedLocation returnPoint = decoded.getReturnPoint(id).orElseThrow();
		assertEquals(Level.NETHER, returnPoint.dimension());
		assertEquals(new Vec3(1.5, 64.0, -7.25), returnPoint.position());
		assertEquals(90.0F, returnPoint.yRot());
		assertEquals(-12.5F, returnPoint.xRot());

		SavedLocation backPoint = decoded.getBackPoint(id).orElseThrow();
		assertEquals(Level.OVERWORLD, backPoint.dimension());
		assertEquals(new Vec3(-200.5, 12.0, 88.75), backPoint.position());
		assertEquals(180.0F, backPoint.yRot());
		assertEquals(45.0F, backPoint.xRot());

		SavedLocation base = decoded.getHome(id, HomeTier.PLAYER, "base").orElseThrow();
		assertEquals(Level.END, base.dimension());
		assertEquals(new Vec3(100.0, 49.0, 0.0), base.position());
		assertEquals(Level.OVERWORLD, decoded.getHome(id, HomeTier.PLAYER, "mine").orElseThrow().dimension());

		// The two tiers are separate storage: same name, different place, neither overwriting the other.
		assertEquals(Level.NETHER, decoded.getHome(id, HomeTier.ADMIN, "base").orElseThrow().dimension());
		assertEquals(List.of("base"), List.copyOf(decoded.getHomeNames(id, HomeTier.ADMIN)));

		// Insertion order is what listings read back, so it has to survive the round trip.
		assertEquals(List.of("base", "mine"), List.copyOf(decoded.getHomeNames(id, HomeTier.PLAYER)));
	}

	@Test
	void legacyUnnamedHomeBecomesANamedHome() {
		UUID id = UUID.randomUUID();
		Tag uuidTag = net.minecraft.core.UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, id).getOrThrow();
		SavedLocation legacy = new SavedLocation(Level.OVERWORLD, new Vec3(3.0, 70.0, 4.0), 0.0F, 0.0F);

		// Pre-0.12: a single unnamed "home" field. The unnamed home is now the respawn point, which no
		// codec can write to, so the old value has to land somewhere addressable instead of vanishing.
		CompoundTag entry = new CompoundTag();
		entry.put("player", uuidTag);
		entry.put("home", SavedLocation.CODEC.encodeStart(ops, legacy).getOrThrow());
		ListTag players = new ListTag();
		players.add(entry);
		CompoundTag root = new CompoundTag();
		root.putInt("data_version", 2);
		root.put("players", players);

		EssentialsData decoded = decode(root);

		// Lands in the admin tier — named homes only ever existed for admins before the split.
		assertEquals(new Vec3(3.0, 70.0, 4.0), decoded.getHome(id, HomeTier.ADMIN, "home").orElseThrow().position());
		assertTrue(decoded.getHomeNames(id, HomeTier.PLAYER).isEmpty(), "a legacy home is not a player home");

		CompoundTag reencoded = (CompoundTag) encode(decoded);
		CompoundTag reencodedEntry = (CompoundTag) reencoded.getListOrEmpty("players").get(0);
		assertTrue(reencodedEntry.getCompound("home").isEmpty(), "legacy field must not be written back");
		assertFalse(reencodedEntry.getListOrEmpty("admin_homes").isEmpty());
	}

	/**
	 * Absent-until-set really means absent: an unwritten preference reports the live config default
	 * rather than a value baked in at class-init. This is what lets editing a config default move every
	 * player who never chose their own.
	 */
	@Test
	void unsetPreferencesFollowTheConfigDefaults() {
		UUID id = UUID.randomUUID();
		EssentialsData data = new EssentialsData();

		// Nothing set: the values come from config, not from a default baked into the codec.
		assertEquals(EssentialsConfig.get().defaultBuildReach, data.getReachBonus(id));
		assertEquals(EssentialsConfig.get().defaultFlySpeed, data.getFlySpeed(id));
		assertEquals(EssentialsConfig.get().buildNightVisionAvailable, data.getBuildNightVision(id));

		// An explicit choice survives a round trip and stops tracking the default.
		data.setFlySpeed(id, AdminManager.MAX_FLY_SPEED);
		assertEquals(AdminManager.MAX_FLY_SPEED, decode(encode(data)).getFlySpeed(id));
	}

	@Test
	void legacyAdminInventoryFoldsIntoLoadouts() {
		UUID id = UUID.randomUUID();
		ItemStack relic = new ItemStack(Items.NETHERITE_AXE);

		Tag uuidTag = net.minecraft.core.UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, id).getOrThrow();
		Tag inventoryTag = InventorySnapshot.CODEC.encodeStart(ops, new InventorySnapshot(List.of(relic), 0)).getOrThrow();

		// The 1.0.x shape: a bare admin_inventory field, no loadouts list.
		CompoundTag entry = new CompoundTag();
		entry.put("player", uuidTag);
		entry.put("admin_inventory", inventoryTag);
		ListTag players = new ListTag();
		players.add(entry);
		CompoundTag root = new CompoundTag();
		root.putInt("data_version", 2);
		root.put("players", players);

		EssentialsData decoded = decode(root);

		assertSameStack(relic, decoded.getLoadout(id, AdminState.ADMIN).orElseThrow().items().get(0));

		// Writing back must emit the modern shape only, so the legacy field disappears after one cycle.
		CompoundTag reencoded = (CompoundTag) encode(decoded);
		CompoundTag reencodedEntry = (CompoundTag) reencoded.getListOrEmpty("players").get(0);
		assertTrue(reencodedEntry.getCompound("admin_inventory").isEmpty(), "legacy field must not be written back");
		assertFalse(reencodedEntry.getListOrEmpty("loadouts").isEmpty());
	}

	@Test
	void legacyHiddenStateFoldsIntoGhost() {
		UUID id = UUID.randomUUID();
		Tag uuidTag = net.minecraft.core.UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, id).getOrThrow();

		// A pre-0.10 save: the HIDDEN state existed then. Failing to parse it would make
		// SavedDataStorage rebuild the data empty and overwrite the file.
		CompoundTag entry = new CompoundTag();
		entry.put("player", uuidTag);
		entry.putString("state", "hidden");
		ListTag players = new ListTag();
		players.add(entry);
		CompoundTag root = new CompoundTag();
		root.putInt("data_version", 2);
		root.put("players", players);

		EssentialsData decoded = decode(root);
		assertEquals(AdminState.GHOST, decoded.getState(id));

		// Writing back must emit the modern name, so the legacy value disappears after one cycle.
		CompoundTag reencoded = (CompoundTag) encode(decoded);
		CompoundTag reencodedEntry = (CompoundTag) reencoded.getListOrEmpty("players").get(0);
		assertEquals(Optional.of("ghost"), reencodedEntry.getString("state"));
	}

	/**
	 * The downgrade guard: a file from a newer build loads empty, locked, and refuses writes.
	 *
	 * <p>The assertion that matters is the <em>refusal</em>, not the lock flag. Staying clean is what
	 * keeps the newer file intact, because anything that marks the data dirty schedules an overwrite.
	 */
	@Test
	void futureSchemaLocksAndRefusesWrites() {
		CompoundTag root = new CompoundTag();
		root.putInt("data_version", 99);
		root.putString("written_by", "9.9.9");

		EssentialsData decoded = decode(root);

		assertTrue(decoded.isLocked());
		assertFalse(decoded.isDirty(), "a locked instance must never start dirty");

		UUID id = UUID.randomUUID();
		decoded.setState(id, AdminState.GOD);
		assertEquals(AdminState.NONE, decoded.getState(id), "locked data must ignore writes");
		assertFalse(decoded.isDirty(), "refused writes must not schedule a save");

		decoded.setDirty();
		assertFalse(decoded.isDirty(), "setDirty is the load-bearing no-op; SavedDataStorage calls it on set()");
	}

	@Test
	void reachBonusIsClampedToCommandRange() {
		UUID id = UUID.randomUUID();
		EssentialsData data = new EssentialsData();

		data.setReachBonus(id, 999);
		assertEquals(AdminManager.MAX_REACH_BONUS, data.getReachBonus(id));

		data.setReachBonus(id, -3);
		assertEquals(0, data.getReachBonus(id));

		data.setFlySpeed(id, 99);
		assertEquals(AdminManager.MAX_FLY_SPEED, data.getFlySpeed(id));

		data.setFlySpeed(id, 0);
		assertEquals(AdminManager.MIN_FLY_SPEED, data.getFlySpeed(id));
	}

	/**
	 * An entry holding nothing is pruned rather than written, so the file stays proportional to the
	 * players who actually have something saved. Depends on {@code Entry.isDefault()} covering every
	 * field — a new field left out of it is invisible here, so check both together.
	 */
	@Test
	void defaultEntriesAreDroppedFromDisk() {
		UUID id = UUID.randomUUID();
		EssentialsData data = new EssentialsData();

		// Touch the entry, then return every field to its default. The three preference fields are
		// absent-until-set, so setting one to the config default still persists it — only state and
		// homes can return to genuinely empty here.
		data.setState(id, AdminState.PASSIVE);
		data.setState(id, AdminState.NONE);
		data.setHome(id, HomeTier.PLAYER, "temp", new SavedLocation(Level.OVERWORLD, Vec3.ZERO, 0.0F, 0.0F));
		data.removeHome(id, HomeTier.PLAYER, "temp");
		data.setFlyEnabled(id, false);

		CompoundTag encoded = (CompoundTag) encode(data);
		assertTrue(encoded.getListOrEmpty("players").isEmpty(), "an all-defaults entry should not be persisted");
		assertEquals(Optional.of(EssentialsData.DATA_VERSION), encoded.getInt("data_version"));
	}
}
