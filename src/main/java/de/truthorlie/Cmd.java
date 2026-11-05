package de.truthorlie;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/**
 * Kleine Helfer, die Vanilla-Commands im Hintergrund ausführen (Sounds, Gamemode, Effekte, Mobs).
 * Commands sind über Versionen hinweg deutlich stabiler als interne Java-APIs.
 */
public final class Cmd {
	private Cmd() {
	}

	public static void run(MinecraftServer server, String command) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
		} catch (Exception e) {
			TruthOrLieMod.LOGGER.warn("Command fehlgeschlagen: {}", command, e);
		}
	}

	/** Spielt einen Sound nur für einen Spieler ab. */
	public static void sound(MinecraftServer server, UUID player, String soundId, float pitch) {
		run(server, "execute as " + player + " at @s run playsound " + soundId + " master @s ~ ~ ~ 1 " + pitch + " 1");
	}

	public static void gamemode(MinecraftServer server, UUID player, String mode) {
		run(server, "gamemode " + mode + " " + player);
	}

	public static void effect(MinecraftServer server, UUID player, String effect, int seconds, int amplifier) {
		run(server, "effect give " + player + " " + effect + " " + seconds + " " + amplifier + " true");
	}

	public static void clearEffects(MinecraftServer server, UUID player) {
		run(server, "effect clear " + player);
	}

	/** Entfernt alle Mobs, die das Spiel gespawnt hat (in allen drei Dimensionen). */
	public static void killTolMobs(MinecraftServer server) {
		for (String dim : new String[] { "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end" }) {
			run(server, "execute in " + dim + " run kill @e[tag=tol_mob]");
		}
	}

	/** Fallback-Titel für Spieler ohne Client-Mod. */
	public static void title(MinecraftServer server, UUID player, String title, String subtitle, String color) {
		run(server, "title " + player + " times 10 50 15");
		run(server, "title " + player + " subtitle {\"text\":\"" + esc(subtitle) + "\",\"color\":\"gray\"}");
		run(server, "title " + player + " title {\"text\":\"" + esc(title) + "\",\"color\":\"" + color + "\",\"bold\":true}");
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
