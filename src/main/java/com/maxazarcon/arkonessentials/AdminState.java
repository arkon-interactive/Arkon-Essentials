package com.maxazarcon.arkonessentials;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * The states a player can be in. Only one is ever active at a time, which is what lets the HUD get
 * away with a single indicator.
 *
 * <ul>
 *   <li>{@link #ADMIN} — hidden from players and mobs, in creative, on the admin loadout.
 *   <li>{@link #PASSIVE} — ignored by mobs only; still perfectly visible to other players.
 *   <li>{@link #BUILD} — creative on its own loadout, with extended reach and night vision. Not
 *       hidden: creative already makes a player invulnerable, so hostile mobs ignore them anyway,
 *       and staying visible is the point when you are building in front of people.
 *   <li>{@link #GOD} — nothing lands at all: damage, knockback and harmful effects are refused.
 *   <li>{@link #DEMIGOD} — everything lands except the consequence. The hit plays out in full —
 *       animation, knockback, particles — and only the health loss is refused.
 *   <li>{@link #GHOST} — {@link #GOD} plus fully vanished: untouchable and unseen, but still in
 *       survival with your own gear, which is what separates it from {@link #ADMIN}.
 * </ul>
 *
 * <p>{@link #GOD}, {@link #DEMIGOD} and {@link #GHOST} leave game mode and inventory alone, so they
 * layer over whatever the player was already doing rather than replacing it.
 *
 * <p>There was once a HIDDEN state (vanish without protection); it was removed as redundant with
 * {@link #GHOST}, and {@link #CODEC} still folds the legacy {@code "hidden"} save value into GHOST.
 * Do not reuse {@code "hidden"} as a serialized name.
 *
 * <p>New constants go on the end and removed ones must never be cut from the middle without care:
 * {@link #STREAM_CODEC} is ordinal-based, and while that only ever travels between a matched client
 * and server, keeping the order stable avoids nasty surprises.
 */
public enum AdminState implements StringRepresentable {
	NONE("none", "", 0),
	ADMIN("admin", "Admin Mode", 0xFFFF5555),
	PASSIVE("passive", "Passive Mode", 0xFF55FF55),
	BUILD("build", "Build Mode", 0xFF55FFFF),
	GOD("god", "God Mode", 0xFFFFAA00),
	DEMIGOD("demigod", "Demigod", 0xFFC0C0C0),
	GHOST("ghost", "Ghost", 0xFF808080),

	/**
	 * Observation with no footprint: vanished, protected, and unable to disturb anything.
	 *
	 * <p>Unlike {@link #ADMIN} it leaves you in survival — it swaps to the admin loadout without
	 * touching your game mode — and it additionally refuses item pickups and world interaction, so
	 * watching an area cannot accidentally change it. Both of those are toggleable per player via
	 * {@code /vanish pickups} and {@code /vanish interact}.
	 */
	VANISH("vanish", "Vanish", 0xFFFF55FF);

	private static final StringRepresentable.EnumCodec<AdminState> BASE_CODEC = StringRepresentable.fromEnum(AdminState::values);

	/**
	 * Like the plain enum codec, but folds the pre-0.10 {@code "hidden"} value into {@link #GHOST}.
	 * A save written before the removal must keep parsing — a decode failure would make
	 * {@code SavedDataStorage} rebuild the data empty and overwrite the file.
	 */
	public static final Codec<AdminState> CODEC = Codec.STRING.comapFlatMap(
		name -> {
			if ("hidden".equals(name)) {
				return DataResult.success(GHOST);
			}

			AdminState state = BASE_CODEC.byName(name);
			return state != null ? DataResult.success(state) : DataResult.error(() -> "Unknown admin state: " + name);
		},
		AdminState::getSerializedName
	);

	public static final StreamCodec<ByteBuf, AdminState> STREAM_CODEC = ByteBufCodecs.VAR_INT
		.map(id -> values()[id], AdminState::ordinal);

	private final String serializedName;
	private final String label;
	private final int color;

	AdminState(final String serializedName, final String label, final int color) {
		this.serializedName = serializedName;
		this.label = label;
		this.color = color;
	}

	@Override
	public String getSerializedName() {
		return this.serializedName;
	}

	/** Text shown in the HUD indicator. Empty for {@link #NONE}, which draws nothing. */
	public String label() {
		return this.label;
	}

	/** Packed ARGB colour of the HUD indicator. */
	public int color() {
		return this.color;
	}

	/**
	 * Plain-language summary of what this state actually does, for {@code /mode}.
	 *
	 * <p>Lives here rather than in the command so the description cannot drift away from the behaviour
	 * flags above it — anyone changing what a state does is looking straight at the sentence describing
	 * it.
	 */
	public String description() {
		return switch (this) {
			case NONE -> "No mode active — ordinary play.";
			case ADMIN -> "Hidden from players and mobs, in creative, on the admin loadout. Your survival gear is stashed.";
			case PASSIVE -> "Mobs ignore you. Other players still see you, and you still take damage.";
			case BUILD -> "Creative on its own loadout, with extended reach and night vision. Deliberately visible to everyone.";
			case GOD -> "Nothing lands: damage, knockback and harmful effects are all refused. Game mode and inventory untouched. Flight available.";
			case DEMIGOD -> "Hits land in full — animation, knockback, particles — but never cost you health. Game mode and inventory untouched. Flight if permitted.";
			case GHOST -> "Invisible God Mode, still in survival. Unseen and untouchable, holding your own gear, game mode untouched. Flight available.";
			case VANISH -> "Unseen, untouched and leaving no trace: survival with the admin loadout, no damage, no effects, "
				+ "and item pickups and world interaction both refused. Night vision and flight included. "
				+ "Use /vanish pickups and /vanish interact to allow either.";
		};
	}

	/**
	 * Whether other players (ops excepted) should be unable to see this player at all.
	 *
	 * <p>{@link #ADMIN} is a vanished creative, so it hides too — it differs from {@link #GHOST} in
	 * game mode and loadout, not visibility.
	 */
	public boolean hiddenFromPlayers() {
		return this == ADMIN || this == GHOST || this == VANISH;
	}

	/** Whether mobs should refuse to target this player. */
	public boolean hiddenFromMobs() {
		return this == ADMIN || this == PASSIVE || this == GHOST || this == VANISH;
	}

	/**
	 * Whether entering this state swaps the player onto a stored loadout, stashing their own gear.
	 *
	 * <p>Deliberately <strong>separate from {@link #forcesCreative()}</strong>. The two used to be one
	 * test, which made "a loadout" and "creative" inseparable; {@link #VANISH} needs the first without
	 * the second.
	 */
	public boolean stashesInventory() {
		return this == ADMIN || this == BUILD || this == VANISH;
	}

	/** Whether entering this state puts the player in creative, and leaving it takes them back out. */
	public boolean forcesCreative() {
		return this == ADMIN || this == BUILD;
	}

	/**
	 * Which state's loadout slot this one uses.
	 *
	 * <p>{@link #VANISH} shares {@link #ADMIN}'s, so tools set up on duty are already to hand when
	 * vanishing — they are the same job. Every other state keeps its own, which is what stops admin
	 * tools and building tools mixing.
	 */
	public AdminState loadoutKey() {
		return this == VANISH ? ADMIN : this;
	}

	/** Whether this state refuses item pickups by default. Overridable per player. */
	public boolean blocksPickupsByDefault() {
		return this == VANISH;
	}

	/**
	 * Whether this state refuses world interaction by default — breaking, placing, attacking, and using
	 * blocks or entities. Overridable per player.
	 */
	public boolean blocksInteractionByDefault() {
		return this == VANISH;
	}

	/** Whether this state grants permanent night vision, as Build Mode does for its own reasons. */
	public boolean grantsNightVision() {
		return this == BUILD || this == VANISH;
	}

	/**
	 * Whether entering this state swaps the player into creative on a loadout of its own. Each such
	 * state keeps a separate inventory, so admin tools and building tools never mix — and neither
	 * touches the player's own survival gear.
	 */

	/** Health and hunger are pinned full, and carried gear takes no durability damage. */
	public boolean protectsPlayer() {
		return this == GOD || this == DEMIGOD || this == GHOST || this == VANISH;
	}

	/**
	 * Incoming damage, knockback and harmful effects are refused outright.
	 *
	 * <p>This is the whole difference between the two protected states. God Mode stops the hit before
	 * it lands; Demigod lets it land in full and only refuses the health loss, so the player still
	 * sees and feels everything.
	 */
	public boolean blocksDamageEntirely() {
		return this == GOD || this == GHOST || this == VANISH;
	}

	/**
	 * Whether this state grants flight unconditionally.
	 *
	 * <p>{@link #DEMIGOD} is absent on purpose: it grants flight only when the config or the
	 * {@code arkonessentials:fly.demigod} permission allows, so that decision needs a player and lives
	 * in {@code AdminManager.stateGrantsFlight}. {@link #ADMIN} and {@link #BUILD} already fly natively
	 * through creative, and {@link #PASSIVE} is not meant to.
	 */
	public boolean alwaysGrantsFlight() {
		return this == GOD || this == GHOST || this == VANISH;
	}
}
