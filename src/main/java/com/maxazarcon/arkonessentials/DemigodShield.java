package com.maxazarcon.arkonessentials;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Demigod's regenerating shield.
 *
 * <p>Demigod used to be invulnerable — the hit landed in full and the health loss was simply refused.
 * It is now <em>durable</em> instead: a pool of absorption that soaks damage and refills over time, so a
 * Demigod can be worn down by sustained pressure and killed, but shrugs off anything brief.
 *
 * <p>Built on vanilla absorption rather than a bespoke damage hook, which buys a lot for free:
 * {@code LivingEntity#actuallyHurt} already drains absorption before health, the client already draws
 * it as gold hearts above the health bar, and it already survives a relog. Two attributes are involved
 * and both are needed — {@code setAbsorptionAmount} clamps to {@link Attributes#MAX_ABSORPTION}, so
 * raising the ceiling has to come first or the pool silently stays at zero.
 *
 * <p>The pool is sized by experience level, reaching its cap at {@code demigodShieldLevels}. That makes
 * the mode grow with the player rather than handing a fresh account the same protection as a veteran.
 *
 * <p><strong>Regeneration pauses after a hit.</strong> Without that the pool refills faster than anyone
 * can spend it and Demigod is invulnerable again by another name — which is the exact thing this
 * replaced.
 */
public final class DemigodShield {
	private static final Identifier SHIELD_MODIFIER =
		Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, "demigod_shield");

	/** Server time of each Demigod's last hit, for the regeneration pause. Memory only. */
	private static final Map<UUID, Long> LAST_HIT = new ConcurrentHashMap<>();

	private DemigodShield() {
	}

	/** How large this player's pool should be, in absorption points. Two per heart. */
	public static float capacityFor(final ServerPlayer player) {
		EssentialsConfig config = EssentialsConfig.get();
		int levels = Math.max(config.demigodShieldLevels, 1);
		float fraction = Math.min((float) player.experienceLevel / levels, 1.0F);

		return (float) config.demigodShieldCap * fraction;
	}

	/** Notes a hit, so the pool stops refilling for a moment. Called from the damage event. */
	public static void noteHit(final ServerPlayer player) {
		LAST_HIT.put(player.getUUID(), player.level().getGameTime());
	}

	public static void onDisconnect(final ServerPlayer player) {
		LAST_HIT.remove(player.getUUID());
	}

	/**
	 * Keeps every Demigod's ceiling in step with their level and tops the pool up.
	 *
	 * <p>Runs on the server tick because experience changes have no event worth hooking — a level can
	 * move from an anvil, a furnace, a command or a death, and re-deriving it costs one comparison.
	 */
	public static void tick(final MinecraftServer server) {
		EssentialsConfig config = EssentialsConfig.get();
		long now = server.overworld().getGameTime();
		long pause = (long) config.demigodShieldRegenDelaySeconds * 20L;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!AdminManager.getState(player).hasDemigodShield()) {
				continue;
			}

			float capacity = capacityFor(player);
			applyCeiling(player, capacity);

			if (capacity <= 0.0F) {
				continue;
			}

			Long lastHit = LAST_HIT.get(player.getUUID());

			if (lastHit != null && now - lastHit < pause) {
				continue;
			}

			float current = player.getAbsorptionAmount();

			if (current >= capacity) {
				continue;
			}

			// Per second rather than per tick, so the rate reads the same in the config as it does in
			// game. A fractional step per tick would refill in a way nobody could reason about.
			float perTick = (float) config.demigodShieldRegenPerSecond / 20.0F;
			player.setAbsorptionAmount(Math.min(current + perTick, capacity));
		}
	}

	/**
	 * Raises {@link Attributes#MAX_ABSORPTION} to the pool size.
	 *
	 * <p>Transient, like every other modifier this mod applies: a permanent one is written into player
	 * data and would strand itself on players if the mod were removed.
	 */
	private static void applyCeiling(final ServerPlayer player, final float capacity) {
		AttributeInstance instance = player.getAttribute(Attributes.MAX_ABSORPTION);

		if (instance == null) {
			return;
		}

		AttributeModifier existing = instance.getModifier(SHIELD_MODIFIER);

		if (existing != null && existing.amount() == capacity) {
			return;
		}

		instance.removeModifier(SHIELD_MODIFIER);

		if (capacity > 0.0F) {
			instance.addTransientModifier(
				new AttributeModifier(SHIELD_MODIFIER, capacity, AttributeModifier.Operation.ADD_VALUE)
			);
		}
	}

	/**
	 * Takes the shield away.
	 *
	 * <p>The pool is cleared as well as the ceiling: vanilla clamps absorption down when the maximum
	 * falls, but only on its own schedule, and leaving gold hearts on someone who is no longer a Demigod
	 * would be a free extra life.
	 */
	public static void clear(final ServerPlayer player) {
		AttributeInstance instance = player.getAttribute(Attributes.MAX_ABSORPTION);

		if (instance != null) {
			instance.removeModifier(SHIELD_MODIFIER);
		}

		if (player.getAbsorptionAmount() > 0.0F) {
			player.setAbsorptionAmount(0.0F);
		}

		LAST_HIT.remove(player.getUUID());
	}

	/**
	 * Whether this player takes half damage for want of armour.
	 *
	 * <p>The floor under the shield: a Demigod caught without gear is still hard to kill, which is what
	 * separates the mode from simply having good armour.
	 */
	public static boolean halvesDamage(final ServerPlayer player) {
		return AdminManager.getState(player).hasDemigodShield() && player.getArmorValue() <= 0;
	}
}
