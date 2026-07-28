package com.maxazarcon.arkonessentials;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Somewhere a player can be sent back to. Used both for the {@code /admin back} return point and for
 * {@code /admin home}.
 *
 * <p>Stores the dimension as well as the position, so a saved spot survives an admin being called
 * into the Nether and back.
 */
public record SavedLocation(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {
	public static final Codec<SavedLocation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(SavedLocation::dimension),
		Vec3.CODEC.fieldOf("position").forGetter(SavedLocation::position),
		Codec.FLOAT.fieldOf("y_rot").forGetter(SavedLocation::yRot),
		Codec.FLOAT.fieldOf("x_rot").forGetter(SavedLocation::xRot)
	).apply(instance, SavedLocation::new));

	public static SavedLocation capture(final ServerPlayer player) {
		return new SavedLocation(player.level().dimension(), player.position(), player.getYRot(), player.getXRot());
	}

	/**
	 * @return false if the dimension has since gone away, which a datapack change can cause between
	 *         saving the location and using it
	 */
	public boolean teleport(final ServerPlayer player, final MinecraftServer server) {
		ServerLevel level = server.getLevel(this.dimension);

		if (level == null) {
			return false;
		}

		player.teleportTo(level, this.position.x, this.position.y, this.position.z, Set.of(), this.yRot, this.xRot, false);
		return true;
	}
}
