package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps /fly alive across everything that recomputes abilities.
 *
 * <p>{@code setGameModeForPlayer} is the single method every ability wipe flows through — game-mode
 * changes, respawn, dimension transfer, and initial join construction all end in
 * {@code GameType#updatePlayerAbilities} hard-setting {@code mayfly = false} for non-creative modes.
 * Re-asserting at its tail means flight never observably drops, rather than being healed later.
 *
 * <p>Safe against the extra {@code flying} clear in {@code changeGameModeForPlayer}: that runs after
 * this returns but only touches {@code flying}, never {@code mayfly}, and its own
 * {@code onUpdateAbilities()} call syncs whatever we set here.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@Inject(method = "setGameModeForPlayer", at = @At("TAIL"))
	private void arkonessentials$reassertFlight(
		final GameType gameModeForPlayer,
		final @Nullable GameType previousGameModeForPlayer,
		final CallbackInfo info
	) {
		AdminManager.onGameModeChanged(this.player, previousGameModeForPlayer);
	}
}
