package com.maxazarcon.arkonessentials;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArkonEssentials implements ModInitializer {
	public static final String MOD_ID = "arkonessentials";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Shape of the client sync packets, independent of the mod version.
	 *
	 * <p>Bump this whenever {@link AdminStatePayload} changes its fields. The number is baked into that
	 * channel's name, so an old client simply never receives the packet instead of misreading it — the
	 * incompatibility becomes structural rather than something both sides have to remember. Cosmetic and
	 * server-side changes do not touch it.
	 */
	public static final int PROTOCOL_VERSION = 2;

	/** Stamped into the saved data, so a downgrade can name the version that wrote the file. */
	public static final String VERSION = FabricLoader.getInstance()
		.getModContainer(MOD_ID)
		.map(container -> container.getMetadata().getVersion().getFriendlyString())
		.orElse("unknown");

	@Override
	public void onInitialize() {
		// Before anything reads a default from it.
		EssentialsConfig.load();

		PayloadTypeRegistry.clientboundPlay().register(AdminStatePayload.TYPE, AdminStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(HandshakePayload.TYPE, HandshakePayload.STREAM_CODEC);

		// Turns a blank indicator into an explanation. The versioned state channel already makes a
		// mismatch harmless, but harmless and silent looks identical to broken from the player's side.
		ServerPlayNetworking.registerGlobalReceiver(HandshakePayload.TYPE, (payload, context) -> {
			if (payload.protocolVersion() == PROTOCOL_VERSION) {
				return;
			}

			LOGGER.info(
				"{} joined with Arkon Essentials {} (protocol {}); this server is {} (protocol {}). Their indicator will not update.",
				context.player().getGameProfile().name(), payload.modVersion(), payload.protocolVersion(), VERSION, PROTOCOL_VERSION
			);

			// Sent as chat rather than through a payload of ours, so it arrives whatever the client can
			// or cannot decode.
			context.player().sendSystemMessage(
				Component.literal(
					"Your Arkon Essentials client (" + payload.modVersion() + ") does not match this server ("
						+ VERSION + "), so the mode indicator will stay blank. Everything else works normally."
				).withStyle(ChatFormatting.YELLOW)
			);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
			AdminCommand.register(dispatcher);
			TpsCommand.register(dispatcher);
			PingCommand.register(dispatcher);
			AfkCommand.register(dispatcher);
			TpCommand.register(dispatcher);
			PresenceCommand.register(dispatcher);
			ModeCommand.register(dispatcher);
			ArkonCommand.register(dispatcher);
		});

		// God Mode refuses the hit outright. Demigod deliberately does not appear here — it wants the
		// damage to land so the animation, knockback and particles all play, and blocks the health loss
		// in LivingEntityMixin instead. Fall damage while /fly is on needs no line either: mayfly makes
		// Player#causeFallDamage return false natively, so the event never even fires.
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (!(entity instanceof ServerPlayer player)) {
				return true;
			}

			if (AdminManager.getState(player).blocksDamageEntirely()) {
				return false;
			}

			// The one free landing owed to a player who lost flight mid-air.
			return !(source.is(DamageTypeTags.IS_FALL) && AdminManager.consumeSoftLanding(player));
		});

		// Retires soft-landing tickets once their fall ends harmlessly.
		ServerTickEvents.END_SERVER_TICK.register(AdminManager::tickSoftLandings);

		// Watches for the idle timeout, and for the activity that ends it.
		ServerTickEvents.END_SERVER_TICK.register(AfkManager::tick);

		// Backstop for both protected states. Health loss is already refused, but a source that kills
		// outright rather than by subtraction would otherwise slip straight past that.
		ServerLivingEntityEvents.ALLOW_DEATH.register(
			(entity, source, amount) -> !(entity instanceof ServerPlayer player)
				|| !AdminManager.getState(player).protectsPlayer()
		);

		// Touched at startup purely so the saved data is read while an operator is still watching the
		// log. Loading is otherwise lazy, which would defer the version check — and its error message —
		// until the first command, long after the console has scrolled past.
		ServerLifecycleEvents.SERVER_STARTED.register(EssentialsData::get);

		// State persists across logout, so a returning player needs their indicator back and needs any
		// hidden players stripped out of the tab list vanilla just sent them.
		ServerPlayConnectionEvents.JOIN.register(
			(handler, sender, server) -> {
				AdminManager.onJoin(handler.getPlayer());
				AfkManager.onJoin(handler.getPlayer());
			}
		);

		// AFK is in-memory and describes what someone is doing right now, so it retires with the session
		// rather than persisting. Leaving the entry behind would also leak a slot per disconnect.
		ServerPlayConnectionEvents.DISCONNECT.register(
			(handler, server) -> {
				AfkManager.onDisconnect(handler.getPlayer());
				PresenceManager.onDisconnect(handler.getPlayer());
			}
		);

		// Records where a player died so /back can return them to their belongings. Gated at the moment
		// of death rather than at /back, so a server that has not granted the node never even stores the
		// location — and a player without it keeps whatever /back point their last teleport left.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player && AdminPermissions.mayReturnToDeath(player)) {
				EssentialsData.get(player.level().getServer()).setBackPoint(player.getUUID(), SavedLocation.capture(player));
			}
		});
	}
}
