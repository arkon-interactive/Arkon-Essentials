package com.maxazarcon.arkonessentials;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: "stop colliding with the world."
 *
 * <p>Its own channel rather than another field on {@link AdminStatePayload}, and not merely for tidiness:
 * <strong>the server uses the client's registration of this channel as the capability test.</strong>
 * {@code ServerPlayNetworking.canSend} answers whether a receiver exists, which is exactly the question
 * {@link NoclipManager} needs — can this player phase, or must they get the spectator fallback? Folding
 * the flag into the state payload would have tied the answer to whether the HUD was installed.
 *
 * <p>Versioned in its channel name for the same reason the state channel is: a client built against a
 * different shape registered a different name, is never sent to, and falls back to spectator rather than
 * misreading a packet.
 */
public record NoclipPayload(boolean active) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<NoclipPayload> TYPE = new CustomPacketPayload.Type<>(
		Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, "noclip_v" + ArkonEssentials.PROTOCOL_VERSION)
	);

	public static final StreamCodec<ByteBuf, NoclipPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, NoclipPayload::active,
		NoclipPayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
