package com.maxazarcon.arkonessentials;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Stops a vanished player disturbing anything.
 *
 * <p>Built on Fabric's interaction events rather than mixins: they exist precisely for this, they cover
 * both the client-predicted and server-authoritative halves, and cancelling through them makes the
 * client roll its prediction back — so a blocked break shows the block reappearing rather than the
 * player seeing it vanish and then pop back seconds later.
 *
 * <p>Every hook asks {@link AdminManager#interactionBlocked}, which folds the state's default together
 * with the player's own {@code /vanish interact} override, so there is one answer and one place to
 * change it.
 *
 * <h2>The door exception</h2>
 *
 * <p>Doors, trapdoors and fence gates stay usable, because a mode meant for moving quietly through a
 * building that cannot open a door is not much use. Note this only exempts the <em>interaction</em>:
 * the door still swings and still makes a noise for everyone nearby, so it is unhidden movement, not
 * silent movement. Making that invisible is a separate problem — the world state genuinely changes, so
 * suppressing the packet desyncs every other client rather than concealing anything.
 */
public final class InteractionGuard {
	private InteractionGuard() {
	}

	public static void register() {
		// Breaking, in both halves: the swing that starts it and the break that completes it. Registering
		// only the second would let the block's cracking animation play before the refusal.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			blocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !blocked(player));

		// Placing and using. The door exemption lives here, since opening a door is a "use".
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!blocked(player)) {
				return InteractionResult.PASS;
			}

			BlockState state = level.getBlockState(hit.getBlockPos());
			return passable(state) ? InteractionResult.PASS : InteractionResult.FAIL;
		});

		// Entities, both hitting them and interacting with them. Covers item frames, armour stands,
		// villager trading and the rest without naming any of them.
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			blocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			blocked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
	}

	/**
	 * Whether this interaction should be refused.
	 *
	 * <p>Guards on {@link ServerPlayer} because the events also fire client-side, where the state is
	 * only a HUD label and asking would give the wrong answer.
	 */
	private static boolean blocked(final Player player) {
		return player instanceof ServerPlayer serverPlayer && AdminManager.interactionBlocked(serverPlayer);
	}

	/** The things a vanished player may still operate: anything you would walk through. */
	private static boolean passable(final BlockState state) {
		return state.getBlock() instanceof DoorBlock
			|| state.getBlock() instanceof TrapDoorBlock
			|| state.getBlock() instanceof FenceGateBlock;
	}
}
