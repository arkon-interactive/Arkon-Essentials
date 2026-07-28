package com.maxazarcon.arkonessentials;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: "here is what I am running."
 *
 * <p>Sent once on join. Its only job is to let the server tell a player <em>why</em> their indicator is
 * blank when the versions do not line up, since the safety mechanism itself — the versioned state
 * channel — is silent by design.
 *
 * <p><strong>This channel's shape must never change.</strong> It is the one thing both sides have to
 * agree on before they can discover that they disagree about anything else; versioning it would defeat
 * the purpose. Anything new belongs in a payload of its own, not appended here.
 */
public record HandshakePayload(int protocolVersion, String modVersion) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<HandshakePayload> TYPE = new CustomPacketPayload.Type<>(
		Identifier.fromNamespaceAndPath(ArkonEssentials.MOD_ID, "handshake")
	);

	public static final StreamCodec<ByteBuf, HandshakePayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, HandshakePayload::protocolVersion,
		ByteBufCodecs.STRING_UTF8, HandshakePayload::modVersion,
		HandshakePayload::new
	);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
