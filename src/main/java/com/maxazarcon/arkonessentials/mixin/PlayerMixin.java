package com.maxazarcon.arkonessentials.mixin;

import com.maxazarcon.arkonessentials.AdminManager;
import com.maxazarcon.arkonessentials.NoclipManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two leaks in {@code Player}, both of them single chokepoints.
 *
 * <p>Freezing hunger: exhaustion is the only source of hunger depletion, so refusing it here pins the
 * bar without having to write to it every tick. Vanilla already skips this for creative players; this
 * extends the same courtesy to God Mode and Demigod, which do not change game mode, and to anyone AFK.
 *
 * <p>Phasing: see the redirect below.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
	private void arkonessentials$freezeHunger(final float amount, final CallbackInfo info) {
		if ((Object) this instanceof ServerPlayer player && AdminManager.hungerFrozen(player)) {
			info.cancel();
		}
	}

	/**
	 * Lets a noclipping player through blocks without putting them in spectator.
	 *
	 * <p>{@code Player.tick} opens with {@code noPhysics = isSpectator()}, which is both the reason
	 * noclip used to mean spectator and the exact place to change it. The write is redirected rather than
	 * injected around, because <strong>the assignment is the first thing in the method and the movement
	 * for the tick happens later inside it</strong> — setting the field at TAIL would be overwritten at
	 * the head of the next tick before anything read it, so the flag would never once be true during a
	 * move.
	 *
	 * <p>This lives in the shared source set on purpose. Collision for the player you control is
	 * simulated on your own machine, so the client's copy of this method is the one that matters; the
	 * server's copy matters too, for the movement validation in {@code ServerGamePacketListenerImpl},
	 * which waves through any position when {@code noPhysics} is set. One redirect covers both, because
	 * {@link NoclipManager} takes a bare UUID and each side fills its own set.
	 *
	 * <p>The field is declared on {@code Entity}, but javac emits the reference against {@code Player} —
	 * the static type of the expression — so that is what the target descriptor has to name. Verified
	 * against the constant pool; naming {@code Entity} scans zero targets and fails the injection check.
	 */
	@Redirect(
		method = "tick",
		at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z", opcode = Opcodes.PUTFIELD)
	)
	private void arkonessentials$phaseThroughBlocks(final Player self, final boolean spectating) {
		self.noPhysics = spectating || NoclipManager.isPhasing(self.getUUID());
	}
}
