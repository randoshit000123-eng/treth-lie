package de.truthorlie;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Netzwerk: Der Server schickt dem Client Spielzustand (fürs HUD) und Banner (große Einblendungen).
 * Beides sind bewusst einfache Text-Payloads, damit das Format leicht erweiterbar bleibt.
 */
public final class Net {
	private Net() {
	}

	/** Spielzustand fürs HUD. Format siehe GameManager#buildState. */
	public record StatePayload(String data) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<StatePayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("truthorlie", "state"));
		public static final StreamCodec<FriendlyByteBuf, StatePayload> CODEC = ByteBufCodecs.STRING_UTF8.map(StatePayload::new, StatePayload::data).cast();

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Große Einblendung: "titel \n untertitel \n argbFarbe \n ticks". */
	public record BannerPayload(String data) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<BannerPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("truthorlie", "banner"));
		public static final StreamCodec<FriendlyByteBuf, BannerPayload> CODEC = ByteBufCodecs.STRING_UTF8.map(BannerPayload::new, BannerPayload::data).cast();

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BannerPayload.TYPE, BannerPayload.CODEC);
	}

	public static boolean hasMod(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, StatePayload.TYPE);
	}

	public static void sendState(ServerPlayer player, String data) {
		if (hasMod(player)) {
			ServerPlayNetworking.send(player, new StatePayload(data));
		}
	}

	/**
	 * Zeigt ein Banner. Spieler mit Mod sehen das HUD-Banner, alle anderen den normalen Vanilla-Titel.
	 *
	 * @param argb  Farbe des Titels (0xAARRGGBB)
	 * @param color Vanilla-Farbname fürs Fallback (z. B. "green")
	 */
	public static void banner(net.minecraft.server.MinecraftServer server, ServerPlayer player, String title, String subtitle, int argb, String color, int ticks) {
		if (hasMod(player)) {
			ServerPlayNetworking.send(player, new BannerPayload(title + "\n" + subtitle + "\n" + argb + "\n" + ticks));
		} else {
			Cmd.title(server, player.getUUID(), title, subtitle, color);
		}
	}
}
