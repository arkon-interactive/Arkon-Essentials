package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.EssentialsData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembers the last game mode a player was in that wasn't creative, so coming off duty puts them
 * back where they were playing rather than guessing survival.
 *
 * <p>Without this, someone who ran {@code /gamemode creative} by hand before {@code /admin} would
 * have creative recorded as their "previous" mode and would never get dropped back out of it.
 *
 * <p>Hooks {@code setGameMode} rather than watching the commands, so it catches every route into a game
 * mode — {@code /gamemode}, other mods, datapacks, the default applied on first join. Spectator counts
 * as non-creative deliberately: someone who was spectating before going on duty expects to be
 * spectating again afterwards.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	/**
	 * At HEAD, so {@code mode} is the mode being moved <em>to</em> and the field still holds the old one.
	 *
	 * <p>Not cancellable and returns nothing — this only observes. The {@code CallbackInfoReturnable} is
	 * required by the signature of the target, not because the result is touched.
	 */
	@Inject(method = "setGameMode", at = @At("HEAD"))
	private void arkonessentials$rememberNonCreativeMode(final GameType mode, final CallbackInfoReturnable<Boolean> info) {
		// The whole point: creative is never recorded, so the stored value stays the last mode the player
		// was actually playing in and /admin off has somewhere real to put them back.
		if (mode == GameType.CREATIVE) {
			return;
		}

		ServerPlayer player = (ServerPlayer) (Object) this;
		MinecraftServer server = player.level().getServer();

		// Nullable during construction, before the player is attached to a level — this fires on the join
		// path too, so the check is load-bearing rather than defensive.
		if (server != null) {
			EssentialsData.get(server).setLastNonCreativeMode(player.getUUID(), mode);
		}
	}
}
