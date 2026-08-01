package com.maxazarcon.arkonessentials.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.GiveCommand;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Frees the {@code /give} name so the mod's shorthand can own it.
 *
 * <p>Vanilla's {@code /give} takes a player first, and a player argument matches any bare word, so
 * {@code /give cobble} would be read as a player named "cobble". Adding branches cannot fix that:
 * Brigadier merges literals sharing a name and tries the existing node's children first, so vanilla's
 * player branch would always win the race.
 *
 * <p>Nothing is lost — {@code GiveCommand} reimplements every vanilla form, including
 * {@code /give &lt;player&gt; &lt;item&gt; &lt;count&gt;}, and additionally accepts loose item names. The
 * return value is discarded at the call site, so null is safe.
 */
@Mixin(GiveCommand.class)
public abstract class GiveCommandMixin {
	@Redirect(
		method = "register",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/brigadier/CommandDispatcher;register(Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;)Lcom/mojang/brigadier/tree/LiteralCommandNode;"
		)
	)
	private static @Nullable LiteralCommandNode<CommandSourceStack> arkonessentials$dropVanillaGive(
		final CommandDispatcher<CommandSourceStack> dispatcher,
		final LiteralArgumentBuilder<CommandSourceStack> builder
	) {
		return null;
	}
}
