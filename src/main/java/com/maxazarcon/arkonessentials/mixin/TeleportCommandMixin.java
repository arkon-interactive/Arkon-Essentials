package com.maxazarcon.arkonessentials.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.TeleportCommand;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Frees the {@code /tp} name so this mod can own it.
 *
 * <p>Vanilla registers {@code /teleport} and then {@code /tp} as a redirect alias, both requiring op
 * level 2. Simply registering our own {@code /tp} would not work: Brigadier <em>merges</em> literals
 * that share a name and keeps the existing node's requirement, so our subcommands would inherit the
 * op-2 gate and be unreachable by exactly the people the permission nodes exist for.
 *
 * <p>Only the alias is dropped. {@code /teleport} is left completely alone, so every vanilla form —
 * entity selectors, {@code facing}, explicit rotation — is still there for operators. This trades a
 * three-letter alias for a permission-aware command, not for functionality.
 *
 * <p>Targets the second of the two {@code register} calls in the method; the first builds
 * {@code /teleport} itself and must be left to run. The return value is discarded at the call site, so
 * null is safe.
 */
@Mixin(TeleportCommand.class)
public abstract class TeleportCommandMixin {
	@Redirect(
		method = "register",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/brigadier/CommandDispatcher;register(Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;)Lcom/mojang/brigadier/tree/LiteralCommandNode;",
			ordinal = 1
		)
	)
	private static @Nullable LiteralCommandNode<CommandSourceStack> arkonessentials$dropVanillaTpAlias(
		final CommandDispatcher<CommandSourceStack> dispatcher,
		final LiteralArgumentBuilder<CommandSourceStack> builder
	) {
		return null;
	}
}
