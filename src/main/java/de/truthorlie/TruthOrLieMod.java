package de.truthorlie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class TruthOrLieMod implements ModInitializer {
	public static final String MOD_ID = "truthorlie";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static TolConfig config;
	private static GameManager game;

	public static TolConfig config() {
		return config;
	}

	/** Kann null sein, solange der Server noch nicht gestartet ist. */
	public static GameManager game() {
		return game;
	}

	@Override
	public void onInitialize() {
		config = TolConfig.load();
		Net.registerPayloads();

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> TolCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(server -> game = new GameManager(server, config));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (game != null) {
				game.stop(false);
				game = null;
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (game != null) {
				game.tick();
			}
		});

		// Rechtsklick mit den Spielitems (Abstimmung / Story beenden)
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || game == null || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			return game.handleItemUse(sp, sp.getItemInHand(hand)) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});

		// Während des Spiels stirbt niemand "richtig": Wer im Abenteuer stirbt, wartet in der Lobby.
		ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> game == null || game.allowDeath(player));

		// In den Lobby-Phasen gibt es keinen Schaden (kein PvP / kein Versehen).
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !(entity instanceof ServerPlayer sp && game != null && game.isDamageProtected(sp)));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (game != null) {
				game.onJoin(handler.getPlayer());
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (game != null) {
				game.onDisconnect(handler.getPlayer());
			}
		});

		LOGGER.info("Wahr oder Lüge geladen. Start mit /tol start");
	}
}
