package com.maxazarcon.arkonessentials.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code Entity.DATA_SHARED_FLAGS_ID}, which is protected.
 *
 * <p>{@code /xray} needs it to build an entity-data packet by hand. Glowing lives in that shared flags
 * byte, and the byte is broadcast to everyone tracking the entity — so making one player glow for one
 * viewer means sending that viewer a packet of our own rather than setting the flag on the entity.
 */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("DATA_SHARED_FLAGS_ID")
	static EntityDataAccessor<Byte> arkonessentials$sharedFlags() {
		throw new AssertionError("replaced by the mixin at load time");
	}
}
