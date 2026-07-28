package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a hidden player's entity from ever being sent to clients that should not see them.
 *
 * <p>Working at the tracker level rather than applying an invisibility effect means the client is
 * never told the player exists, so there is nothing to render, hit, or read out of the entity list.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
	@Shadow
	@Final
	private Entity entity;

	@Shadow
	public abstract void removePlayer(ServerPlayer player);

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$hideFromPlayers(final ServerPlayer viewer, final CallbackInfo info) {
		if (this.entity instanceof ServerPlayer target && !AdminManager.canSee(viewer, target)) {
			// Tear down any pairing that already exists, otherwise someone who vanishes while being
			// watched stays rendered until the viewer happens to walk out of tracking range.
			this.removePlayer(viewer);
			info.cancel();
		}
	}
}
