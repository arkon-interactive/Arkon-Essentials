package com.maxazarcon.arkonessentials;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client sync of everything the HUD indicator draws: the active state, whether flight is
 * currently granted (the /fly preference gated on a mode being active), whether the player is AFK, and
 * whether they are appearing offline. Purely cosmetic — the server never trusts anything back from
 * this.
 *
 * <p><strong>The channel name carries the protocol version, and that is the version negotiation.</strong>
 * A client only receives packets on channels it has registered a receiver for, and
 * {@code ServerPlayNetworking.canSend} reports exactly that — so a client built against a different
 * shape of this record has registered a different channel, is never sent one of these, and cannot
 * misread it. The failure mode is a blank indicator rather than a desync or a disconnect.
 *
 * <p>So: <strong>changing the fields means bumping {@link ArkonEssentials#PROTOCOL_VERSION}</strong>,
 * which changes the channel and makes the incompatibility structural rather than a matter of everyone
 * remembering to upgrade both jars. {@link HandshakePayload} is what turns that silent degradation into
 * an explanation the player can act on.
 */
public record AdminStatePayload(AdminState state, boolean flightActive, boolean afk, boolean appearingOffline)
	implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AdminStatePayload> TYPE = new CustomPacketPayload.Type<>(
		Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, "state_v" + ArkonEssentials.PROTOCOL_VERSION)
	);

	public static final StreamCodec<ByteBuf, AdminStatePayload> STREAM_CODEC = StreamCodec.composite(
		AdminState.STREAM_CODEC, AdminStatePayload::state,
		ByteBufCodecs.BOOL, AdminStatePayload::flightActive,
		ByteBufCodecs.BOOL, AdminStatePayload::afk,
		ByteBufCodecs.BOOL, AdminStatePayload::appearingOffline,
		AdminStatePayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
