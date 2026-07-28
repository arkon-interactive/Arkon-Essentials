package com.maxazarcon.arkonessentials;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * A complete copy of a player's inventory, used for both halves of the swap: the gear they were
 * carrying before going on duty, and the creative loadout they build up while on it.
 *
 * <p>{@link Inventory#getContainerSize()} spans the main inventory <em>and</em> the equipment slots,
 * so a single index walk captures armour and offhand too.
 */
public record InventorySnapshot(List<ItemStack> items, int selectedSlot) {
	public static final Codec<InventorySnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		// OPTIONAL_CODEC, not ItemStack.CODEC. The plain one rejects empty stacks, and an inventory is
		// mostly empty slots — with it, the first gap in someone's hotbar would fail the whole decode, and
		// a failed decode is silent data loss (SavedDataStorage rebuilds empty and schedules an
		// overwrite). Position matters here, so empties have to round-trip as entries, not be skipped.
		ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(InventorySnapshot::items),
		Codec.INT.fieldOf("selected_slot").forGetter(InventorySnapshot::selectedSlot)
	).apply(instance, InventorySnapshot::new));

	public static InventorySnapshot capture(final ServerPlayer player) {
		Inventory inventory = player.getInventory();
		List<ItemStack> items = new ArrayList<>(inventory.getContainerSize());

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			// copy() is required, not defensive habit: ItemStack is mutable and the live one keeps being
			// modified after this. Storing the reference would let the snapshot drift as the player plays,
			// and clearing their inventory would empty the snapshot with it.
			items.add(inventory.getItem(slot).copy());
		}

		return new InventorySnapshot(items, inventory.getSelectedSlot());
	}

	/**
	 * Puts this snapshot back, replacing whatever is there now.
	 *
	 * <p>Callers are responsible for having stashed anything they cared about first — {@code clearContent}
	 * here is unconditional, and is what stops creative items walking into survival.
	 */
	public void restore(final ServerPlayer player) {
		Inventory inventory = player.getInventory();
		inventory.clearContent();

		// Bounded by both sizes rather than trusting the stored length. A snapshot written by a different
		// build — or a Minecraft version with a different slot count — would otherwise throw here, and
		// dropping the overflow silently is much better than an exception mid-restore that leaves the
		// player holding half their gear.
		int slots = Math.min(this.items.size(), inventory.getContainerSize());

		for (int slot = 0; slot < slots; slot++) {
			inventory.setItem(slot, this.items.get(slot).copy());
		}

		inventory.setSelectedSlot(this.selectedSlot);
	}
}
