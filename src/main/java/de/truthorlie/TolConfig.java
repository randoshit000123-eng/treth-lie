package de.truthorlie;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Einstellungen, gespeichert in config/truthorlie.json. Alle Zeiten in Sekunden. */
public final class TolConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public int rounds = 3;
	public int minPlayers = 2;
	public int countdownSeconds = 10;
	public int arrivalSeconds = 6;
	/** Der 3-Minuten-Timer zu Rundenbeginn. */
	public int adventureSeconds = 180;
	public int storySeconds = 120;
	public int voteSeconds = 25;
	public int revealSeconds = 8;
	public int roundEndSeconds = 12;
	public int minDistance = 1500;
	public int maxDistance = 6000;
	public int protectSeconds = 8;
	/** 0 = harmlos, 1 = normal, 2 = brutal */
	public int mobDifficulty = 1;
	public int lobbyRadius = 3;

	public boolean lobbySet = false;
	/** overworld | the_nether | the_end */
	public String lobbyDim = "overworld";
	public double lobbyX;
	public double lobbyY;
	public double lobbyZ;
	public float lobbyYaw;

	public List<String> disabledScenarios = new ArrayList<>();

	/** Ein per Command einstellbarer Zahlenwert. */
	public record Key(String name, int min, int max, ToIntFunction<TolConfig> get, java.util.function.ObjIntConsumer<TolConfig> set, String description) {
	}

	public static final List<Key> KEYS = List.of(
			new Key("rounds", 1, 50, c -> c.rounds, (c, v) -> c.rounds = v, "Anzahl Runden"),
			new Key("minplayers", 1, 100, c -> c.minPlayers, (c, v) -> c.minPlayers = v, "Mindestspieler (1 = Solo-Test)"),
			new Key("countdown", 3, 120, c -> c.countdownSeconds, (c, v) -> c.countdownSeconds = v, "Vorbereitung vor dem Teleport (s)"),
			new Key("arrival", 2, 60, c -> c.arrivalSeconds, (c, v) -> c.arrivalSeconds = v, "Zeit bis die Karte gezogen wird (s)"),
			new Key("adventure", 10, 1800, c -> c.adventureSeconds, (c, v) -> c.adventureSeconds = v, "Abenteuer-Timer (s), Standard 180"),
			new Key("story", 10, 900, c -> c.storySeconds, (c, v) -> c.storySeconds = v, "Zeit pro Story (s)"),
			new Key("vote", 5, 300, c -> c.voteSeconds, (c, v) -> c.voteSeconds = v, "Abstimmungszeit (s)"),
			new Key("reveal", 3, 60, c -> c.revealSeconds, (c, v) -> c.revealSeconds = v, "Auflösungs-Anzeige (s)"),
			new Key("roundend", 3, 120, c -> c.roundEndSeconds, (c, v) -> c.roundEndSeconds = v, "Pause nach der Runde (s)"),
			new Key("mindistance", 100, 100000, c -> c.minDistance, (c, v) -> c.minDistance = v, "Min. Teleport-Entfernung zur Lobby"),
			new Key("maxdistance", 200, 200000, c -> c.maxDistance, (c, v) -> c.maxDistance = v, "Max. Teleport-Entfernung zur Lobby"),
			new Key("protect", 0, 60, c -> c.protectSeconds, (c, v) -> c.protectSeconds = v, "Schutz nach dem Teleport (s)"),
			new Key("mobs", 0, 2, c -> c.mobDifficulty, (c, v) -> c.mobDifficulty = v, "Mob-Schwierigkeit 0 harmlos / 1 normal / 2 brutal"),
			new Key("lobbyradius", 0, 20, c -> c.lobbyRadius, (c, v) -> c.lobbyRadius = v, "Kreisradius der Spieler in der Lobby"));

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("truthorlie.json");
	}

	public static TolConfig load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				TolConfig c = GSON.fromJson(Files.readString(p), TolConfig.class);
				if (c != null) {
					if (c.disabledScenarios == null) {
						c.disabledScenarios = new ArrayList<>();
					}
					return c;
				}
			}
		} catch (Exception e) {
			TruthOrLieMod.LOGGER.warn("Konnte Config nicht laden, nutze Standardwerte", e);
		}
		TolConfig c = new TolConfig();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this));
		} catch (Exception e) {
			TruthOrLieMod.LOGGER.warn("Konnte Config nicht speichern", e);
		}
	}
}
