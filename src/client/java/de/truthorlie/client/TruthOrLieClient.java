package de.truthorlie.client;

import com.mojang.brigadier.Command;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import de.truthorlie.Net;

public class TruthOrLieClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Net.StatePayload.TYPE, (payload, context) -> ClientState.applyState(payload.data()));
		ClientPlayNetworking.registerGlobalReceiver(Net.BannerPayload.TYPE, (payload, context) -> ClientState.applyBanner(payload.data()));

		ClientTickEvents.END_CLIENT_TICK.register(client -> ClientState.tick++);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

		// Nach den Misc-Overlays: erbt damit die Regel "F1 blendet das HUD aus" und liegt unter Hotbar, Chat und Fadenkreuz.
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath("truthorlie", "hud"), TolHud::render);

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommands.literal("tolhud")
						.executes(context -> {
							context.getSource().sendFeedback(Component.literal("/tolhud on | off | scores  (HUD an/aus, Rangliste an/aus)"));
							return Command.SINGLE_SUCCESS;
						})
						.then(ClientCommands.literal("on").executes(context -> {
							ClientState.hudEnabled = true;
							context.getSource().sendFeedback(Component.literal("HUD an."));
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommands.literal("off").executes(context -> {
							ClientState.hudEnabled = false;
							context.getSource().sendFeedback(Component.literal("HUD aus."));
							return Command.SINGLE_SUCCESS;
						}))
						.then(ClientCommands.literal("scores").executes(context -> {
							ClientState.scoresEnabled = !ClientState.scoresEnabled;
							context.getSource().sendFeedback(Component.literal("Rangliste " + (ClientState.scoresEnabled ? "an." : "aus.")));
							return Command.SINGLE_SUCCESS;
						}))));
	}
}
