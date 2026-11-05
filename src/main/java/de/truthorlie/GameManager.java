package de.truthorlie;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import de.truthorlie.Scenarios.Placement;

/**
 * Die komplette Spiellogik als Zustandsmaschine:
 * COUNTDOWN -> ARRIVAL -> ADVENTURE (3 min) -> [STORY -> VOTE -> REVEAL] pro Spieler -> ROUND_END -> ... -> GAME_END
 */
public final class GameManager {
	public enum Card {
		TRUE, LIE
	}

	private static final int SLOT_TRUE = 3;
	private static final int SLOT_CLOCK = 4;
	private static final int SLOT_LIE = 5;

	public final MinecraftServer server;
	public final TolConfig cfg;

	private Phase phase = Phase.IDLE;
	private int ticksLeft;
	private int ticksTotal;
	private boolean paused;
	private int round;
	private int totalRounds;
	private int syncCounter;

	private final Set<UUID> optedOut = new HashSet<>();
	private final Map<UUID, Integer> scores = new LinkedHashMap<>();
	private final Map<UUID, Integer> fooledTotal = new HashMap<>();
	private final Map<UUID, Integer> correctTotal = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();

	private final List<UUID> participants = new ArrayList<>();
	private final Map<UUID, Card> cards = new HashMap<>();
	private final Map<UUID, Placement> placements = new HashMap<>();
	private final Set<UUID> died = new HashSet<>();
	private final Map<UUID, Scenarios.Type> lastType = new HashMap<>();
	private final ArrayDeque<UUID> planQueue = new ArrayDeque<>();
	private final ArrayDeque<UUID> tellerQueue = new ArrayDeque<>();
	private UUID teller;
	private final Map<UUID, Boolean> votes = new HashMap<>();
	private final Map<UUID, ItemStack[]> savedItems = new HashMap<>();
	private final List<Runnable> deferred = new ArrayList<>();
	private final List<Object[]> delayed = new ArrayList<>();

	public GameManager(MinecraftServer server, TolConfig cfg) {
		this.server = server;
		this.cfg = cfg;
	}

	// =================================================================== Zugriff

	public Phase phase() {
		return phase;
	}

	public boolean isRunning() {
		return phase != Phase.IDLE;
	}

	public boolean isParticipant(UUID id) {
		return participants.contains(id);
	}

	public int round() {
		return round;
	}

	public int totalRounds() {
		return totalRounds;
	}

	public boolean isPaused() {
		return paused;
	}

	private ServerPlayer player(UUID id) {
		return id == null ? null : server.getPlayerList().getPlayer(id);
	}

	private String name(UUID id) {
		ServerPlayer p = player(id);
		if (p != null) {
			names.put(id, p.getName().getString());
		}
		return names.getOrDefault(id, "???");
	}

	private List<ServerPlayer> onlineParticipants() {
		List<ServerPlayer> list = new ArrayList<>();
		for (UUID id : participants) {
			ServerPlayer p = player(id);
			if (p != null) {
				list.add(p);
			}
		}
		return list;
	}

	private List<UUID> eligibleVoters() {
		List<UUID> list = new ArrayList<>();
		for (UUID id : participants) {
			if (!id.equals(teller) && player(id) != null) {
				list.add(id);
			}
		}
		return list;
	}

	// =================================================================== Nachrichten

	private static MutableComponent prefix() {
		return Component.literal("[Wahr/Lüge] ").withStyle(ChatFormatting.GOLD);
	}

	public static MutableComponent text(String s, ChatFormatting... fmt) {
		return Component.literal(s).withStyle(fmt);
	}

	/** Chat-Infos sind bewusst aus. Nur Antworten auf Commands und die Todesmeldung erscheinen im Chat. */
	private static final boolean CHAT_INFO = false;

	public void tell(ServerPlayer p, Component c) {
		if (CHAT_INFO) {
			p.sendSystemMessage(prefix().append(c));
		}
	}

	private void tellForce(ServerPlayer p, Component c) {
		p.sendSystemMessage(prefix().append(c));
	}

	public void broadcast(Component c) {
		if (CHAT_INFO) {
			server.getPlayerList().broadcastSystemMessage(prefix().append(c), false);
		}
	}

	private void sound(UUID id, String sound, float pitch) {
		Cmd.sound(server, id, sound, pitch);
	}

	private void soundAll(String sound, float pitch) {
		for (ServerPlayer p : onlineParticipants()) {
			Cmd.sound(server, p.getUUID(), sound, pitch);
		}
	}

	private void bannerAll(String title, String sub, int argb, String color, int ticks) {
		for (ServerPlayer p : onlineParticipants()) {
			Net.banner(server, p, title, sub, argb, color, ticks);
		}
	}

	// =================================================================== Start / Stop

	public void setLobby(ServerPlayer p) {
		ServerLevel lvl = (ServerLevel) p.level();
		cfg.lobbyDim = Scenarios.dimOf(lvl);
		cfg.lobbyX = p.getX();
		cfg.lobbyY = p.getY();
		cfg.lobbyZ = p.getZ();
		cfg.lobbyYaw = p.getYRot();
		cfg.lobbySet = true;
		cfg.save();
	}

	/** @return Fehlermeldung oder null bei Erfolg */
	public String start(ServerPlayer host, int rounds) {
		if (isRunning()) {
			return "Es läuft bereits ein Spiel. Mit /tol stop abbrechen.";
		}
		if (!cfg.lobbySet) {
			if (host == null) {
				return "Setze zuerst die Lobby mit /tol lobby set.";
			}
			setLobby(host);
		}
		int available = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!optedOut.contains(p.getUUID())) {
				available++;
			}
		}
		if (available < cfg.minPlayers) {
			return "Zu wenige Spieler (" + available + "/" + cfg.minPlayers + "). Mindestanzahl: /tol config minplayers <n>";
		}
		scores.clear();
		fooledTotal.clear();
		correctTotal.clear();
		lastType.clear();
		totalRounds = rounds > 0 ? rounds : cfg.rounds;
		round = 0;
		paused = false;
		broadcast(text("Das Spiel startet! ", ChatFormatting.YELLOW).append(text(totalRounds + " Runden. ", ChatFormatting.WHITE))
				.append(text("Ziel: Überzeuge die anderen - oder durchschaue sie!", ChatFormatting.GRAY)));
		beginRound();
		return null;
	}

	public void stop(boolean announce) {
		if (!isRunning()) {
			return;
		}
		Cmd.killTolMobs(server);
		for (UUID id : new ArrayList<>(participants)) {
			ServerPlayer p = player(id);
			if (p == null) {
				continue;
			}
			restoreItems(p);
			Cmd.clearEffects(server, id);
			Cmd.gamemode(server, id, "survival");
			if (phase == Phase.ARRIVAL || phase == Phase.ADVENTURE) {
				teleportToLobby(p, participants.indexOf(id), participants.size());
			}
		}
		savedItems.clear();
		removeNametags();
		phase = Phase.IDLE;
		teller = null;
		votes.clear();
		planQueue.clear();
		tellerQueue.clear();
		participants.clear();
		syncAll();
		if (announce) {
			broadcast(text("Das Spiel wurde beendet.", ChatFormatting.RED));
		}
	}

	public void skip() {
		if (!isRunning()) {
			return;
		}
		if (phase == Phase.COUNTDOWN) {
			while (!planQueue.isEmpty()) {
				planNext();
			}
		}
		ticksLeft = 0;
	}

	public void setPaused(boolean p) {
		this.paused = p;
		syncAll();
	}

	public void optOut(UUID id) {
		optedOut.add(id);
		participants.remove(id);
		tellerQueue.remove(id);
		planQueue.remove(id);
	}

	public void optIn(UUID id) {
		optedOut.remove(id);
	}

	public boolean isOptedOut(UUID id) {
		return optedOut.contains(id);
	}

	// =================================================================== Rundenablauf

	private void setPhase(Phase p, int ticks) {
		phase = p;
		ticksTotal = Math.max(1, ticks);
		ticksLeft = ticksTotal;
		syncAll();
	}

	private void beginRound() {
		round++;
		participants.clear();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!optedOut.contains(p.getUUID())) {
				participants.add(p.getUUID());
				names.put(p.getUUID(), p.getName().getString());
				scores.putIfAbsent(p.getUUID(), 0);
			}
		}
		if (participants.size() < cfg.minPlayers) {
			broadcast(text("Zu wenige Spieler für eine neue Runde - das Spiel endet.", ChatFormatting.RED));
			endGame();
			return;
		}
		cards.clear();
		placements.clear();
		died.clear();
		votes.clear();
		teller = null;

		// Karten fair verteilen: etwa die Hälfte WAHR, die andere LÜGE.
		List<UUID> shuffled = new ArrayList<>(participants);
		Collections.shuffle(shuffled);
		int trues = shuffled.size() / 2;
		if (shuffled.size() % 2 == 1 && ThreadLocalRandom.current().nextBoolean()) {
			trues++;
		}
		for (int i = 0; i < shuffled.size(); i++) {
			cards.put(shuffled.get(i), i < trues ? Card.TRUE : Card.LIE);
		}
		tellerQueue.clear();
		List<UUID> order = new ArrayList<>(participants);
		Collections.shuffle(order);
		tellerQueue.addAll(order);

		// Alle in die Lobby holen.
		int n = participants.size();
		for (int i = 0; i < n; i++) {
			ServerPlayer p = player(participants.get(i));
			if (p == null) {
				continue;
			}
			Cmd.clearEffects(server, p.getUUID());
			Cmd.gamemode(server, p.getUUID(), "adventure");
			healFully(p);
			teleportToLobby(p, i, n);
		}

		planQueue.clear();
		planQueue.addAll(participants);
		setPhase(Phase.COUNTDOWN, cfg.countdownSeconds * 20);
		broadcast(text("Runde " + round + "/" + totalRounds + " ", ChatFormatting.YELLOW)
				.append(text("- gleich werdet ihr zufällig verteilt. Danach erfährst du, ob du WAHR oder LÜGE gezogen hast.", ChatFormatting.GRAY)));
		soundAll("minecraft:block.note_block.bell", 1.0f);
	}

	private void planNext() {
		UUID id = planQueue.poll();
		if (id == null || player(id) == null) {
			return;
		}
		Scenarios.Type t = Scenarios.pickType(cfg, lastType.get(id));
		Placement pl = Scenarios.plan(server, cfg, t);
		lastType.put(id, pl.type());
		placements.put(id, pl);
	}

	private void teleportAll() {
		for (UUID id : participants) {
			ServerPlayer p = player(id);
			Placement pl = placements.get(id);
			if (p == null || pl == null) {
				continue;
			}
			ServerLevel lvl = server.getLevel(Scenarios.dimKey(pl.dim()));
			if (lvl == null) {
				lvl = server.overworld();
			}
			p.teleport(new TeleportTransition(lvl, new Vec3(pl.x(), pl.y(), pl.z()), Vec3.ZERO, pl.yaw(), 0.0F, TeleportTransition.DO_NOTHING));
			Cmd.gamemode(server, id, "survival");
			if (cfg.protectSeconds > 0) {
				Cmd.effect(server, id, "minecraft:resistance", cfg.protectSeconds, 4);
			}
			sound(id, "minecraft:entity.enderman.teleport", 1.0f);
		}
		setPhase(Phase.ARRIVAL, cfg.arrivalSeconds * 20);
	}

	/** Mobs/Boote erscheinen kurz nach der Ankunft, wenn der Chunk sicher geladen ist. */
	private void runPostCommands() {
		for (UUID id : participants) {
			Placement pl = placements.get(id);
			if (pl != null) {
				for (String c : pl.commands()) {
					Cmd.run(server, c);
				}
			}
		}
	}

	private void revealCards() {
		setPhase(Phase.ADVENTURE, cfg.adventureSeconds * 20);
		for (ServerPlayer p : onlineParticipants()) {
			UUID id = p.getUUID();
			Card c = cards.get(id);
			if (c == null) {
				continue;
			}
			boolean truth = c == Card.TRUE;
			Net.banner(server, p, truth ? "WAHR" : "LÜGE", truth ? "Du musst später die Wahrheit erzählen" : "Du musst später eine Lüge erzählen",
					truth ? 0xFF4ADE80 : 0xFFF87171, truth ? "green" : "red", 60);
			tell(p, text("Deine Karte: ", ChatFormatting.GRAY).append(text(truth ? "WAHR" : "LÜGE", truth ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD)));
			if (truth) {
				tell(p, text("Erlebe etwas und erzähl nachher genau, was passiert ist.", ChatFormatting.GRAY));
			} else {
				tell(p, text("Erlebe etwas - und erzähl nachher eine überzeugende Geschichte, die NICHT stimmt. Tipp: /tol idea", ChatFormatting.GRAY));
			}
			sound(id, truth ? "minecraft:entity.player.levelup" : "minecraft:entity.wither.ambient", truth ? 1.2f : 0.8f);
		}
		for (UUID id : participants) {
			Placement pl = placements.get(id);
			if (pl != null) {
				TruthOrLieMod.LOGGER.info("Runde {}: {} -> {} ({}) Karte {}", round, name(id), pl.title(), pl.coords(), cards.get(id));
			}
		}
	}

	private void returnToLobby() {
		Cmd.killTolMobs(server);
		int n = participants.size();
		for (int i = 0; i < n; i++) {
			ServerPlayer p = player(participants.get(i));
			if (p == null) {
				continue;
			}
			Cmd.clearEffects(server, p.getUUID());
			Cmd.gamemode(server, p.getUUID(), "adventure");
			healFully(p);
			teleportToLobby(p, i, n);
		}
		soundAll("minecraft:entity.enderman.teleport", 0.9f);
		broadcast(text("Zeit um! Alle sind zurück in der Lobby.", ChatFormatting.YELLOW));
		startStory();
	}

	private void startStory() {
		restoreAllItems();
		teller = null;
		while (!tellerQueue.isEmpty()) {
			UUID next = tellerQueue.poll();
			if (participants.contains(next) && player(next) != null) {
				teller = next;
				break;
			}
		}
		if (teller == null) {
			endRound();
			return;
		}
		votes.clear();
		ServerPlayer t = player(teller);
		saveSlots(t);
		t.getInventory().setItem(SLOT_CLOCK, clockItem());
		setPhase(Phase.STORY, cfg.storySeconds * 20);

		String tn = name(teller);
		Card c = cards.get(teller);
		broadcast(text("Erzähler: ", ChatFormatting.GRAY).append(text(tn, ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
				.append(text("  (Story " + (participants.size() - tellerQueue.size()) + "/" + participants.size() + ")", ChatFormatting.DARK_GRAY)));
		soundAll("minecraft:block.note_block.chime", 1.2f);
	}

	public String doneTelling(ServerPlayer p) {
		if (phase != Phase.STORY) {
			return "Gerade erzählt niemand.";
		}
		if (!p.getUUID().equals(teller)) {
			return "Nur der aktuelle Erzähler kann die Story beenden.";
		}
		ticksLeft = 0;
		return null;
	}

	private void startVote() {
		ServerPlayer t = player(teller);
		if (t != null) {
			restoreItems(t);
		}
		List<UUID> voters = eligibleVoters();
		if (voters.isEmpty()) {
			startReveal();
			return;
		}
		for (UUID v : voters) {
			ServerPlayer p = player(v);
			saveSlots(p);
			giveVoteItems(p, null);
			sound(v, "minecraft:block.note_block.pling", 1.5f);
		}
		setPhase(Phase.VOTE, cfg.voteSeconds * 20);
		broadcast(text("Abstimmung! ", ChatFormatting.GOLD).append(text("Hat " + name(teller) + " die Wahrheit gesagt? Nutze die Items (grün = WAHR, rot = LÜGE) oder /tol vote true|lie.", ChatFormatting.GRAY)));
	}

	/** @return Fehlermeldung oder null */
	public String castVote(ServerPlayer p, boolean truth) {
		UUID id = p.getUUID();
		if (phase != Phase.VOTE) {
			return "Gerade läuft keine Abstimmung.";
		}
		if (!participants.contains(id)) {
			return "Du spielst in dieser Runde nicht mit.";
		}
		if (id.equals(teller)) {
			return "Du kannst nicht über deine eigene Story abstimmen.";
		}
		votes.put(id, truth);
		giveVoteItems(p, truth);
		tell(p, text("Du hast für ", ChatFormatting.GRAY).append(text(truth ? "WAHR" : "LÜGE", truth ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD))
				.append(text(" gestimmt. (Du kannst noch ändern)", ChatFormatting.GRAY)));
		sound(id, "minecraft:ui.button.click", 1.0f);
		if (votes.size() >= eligibleVoters().size()) {
			ticksLeft = Math.min(ticksLeft, 40); // 2 Sekunden Gnadenfrist zum Umentscheiden
		}
		syncAll();
		return null;
	}

	private void startReveal() {
		restoreAllItems();
		Card c = cards.get(teller);
		boolean truth = c == Card.TRUE;
		String tn = name(teller);

		List<String> right = new ArrayList<>();
		List<String> wrong = new ArrayList<>();
		List<String> abstained = new ArrayList<>();
		for (UUID v : eligibleVoters()) {
			Boolean vote = votes.get(v);
			if (vote == null) {
				abstained.add(name(v));
			} else if (vote == truth) {
				right.add(name(v));
				scores.merge(v, 1, Integer::sum);
				correctTotal.merge(v, 1, Integer::sum);
			} else {
				wrong.add(name(v));
			}
		}
		int fooled = wrong.size();
		scores.merge(teller, fooled, Integer::sum);
		fooledTotal.merge(teller, fooled, Integer::sum);

		MutableComponent head = text(tn, ChatFormatting.WHITE, ChatFormatting.BOLD).append(text(" hat ", ChatFormatting.GRAY))
				.append(text(truth ? "die WAHRHEIT" : "GELOGEN", truth ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD))
				.append(text("!", ChatFormatting.GRAY));
		broadcast(head);
		if (!right.isEmpty()) {
			broadcast(text("  ✔ Richtig geraten (+1): ", ChatFormatting.GREEN).append(text(String.join(", ", right), ChatFormatting.WHITE)));
		}
		if (!wrong.isEmpty()) {
			broadcast(text("  ✘ Hereingelegt: ", ChatFormatting.RED).append(text(String.join(", ", wrong), ChatFormatting.WHITE))
					.append(text("  -> " + tn + " +" + fooled, ChatFormatting.GOLD)));
		}
		if (!abstained.isEmpty()) {
			broadcast(text("  - Nicht abgestimmt: " + String.join(", ", abstained), ChatFormatting.DARK_GRAY));
		}
		Placement pl = placements.get(teller);
		if (pl != null) {
			String extra = died.contains(teller) ? "  (☠ gestorben)" : "";
			broadcast(text("  Erlebt wurde: ", ChatFormatting.GRAY).append(text(pl.title(), ChatFormatting.AQUA)).append(text(" bei " + pl.coords() + extra, ChatFormatting.DARK_GRAY)));
		}

		String sub;
		if (right.isEmpty() && wrong.isEmpty()) {
			sub = tn;
		} else if (fooled > 0) {
			sub = tn + " hat " + fooled + " Spieler reingelegt";
		} else {
			sub = truth ? tn + " wurde geglaubt" : tn + " wurde durchschaut";
		}
		bannerAll(truth ? "WAHR" : "LÜGE", sub, truth ? 0xFF4ADE80 : 0xFFF87171, truth ? "green" : "red", 60);
		soundAll(truth ? "minecraft:entity.player.levelup" : "minecraft:entity.villager.no", truth ? 1.0f : 0.9f);
		setPhase(Phase.REVEAL, cfg.revealSeconds * 20);
	}

	private void endRound() {
		restoreAllItems();
		teller = null;
		votes.clear();
		if (round >= totalRounds) {
			endGame();
			return;
		}
		broadcast(text("Runde " + round + " geschafft! Zwischenstand:", ChatFormatting.YELLOW));
		printRanking(null, 5);
		soundAll("minecraft:ui.toast.challenge_complete", 1.0f);
		setPhase(Phase.ROUND_END, cfg.roundEndSeconds * 20);
	}

	private void endGame() {
		restoreAllItems();
		teller = null;
		List<UUID> rank = ranking();
		broadcast(text("=== SPIELENDE ===", ChatFormatting.GOLD, ChatFormatting.BOLD));
		printRanking(null, 10);
		if (!rank.isEmpty()) {
			int best = scores.getOrDefault(rank.get(0), 0);
			List<String> winners = new ArrayList<>();
			for (UUID id : rank) {
				if (scores.getOrDefault(id, 0) == best) {
					winners.add(name(id));
				}
			}
			bannerAll(winners.size() == 1 ? "Gewinner: " + winners.get(0) : "Gewinner: " + String.join(" & ", winners), best + " Punkte", 0xFFFBBF24, "gold", 120);
			broadcast(text("Gewinner: ", ChatFormatting.GOLD).append(text(String.join(", ", winners), ChatFormatting.YELLOW, ChatFormatting.BOLD)).append(text(" (" + best + " Punkte)", ChatFormatting.GRAY)));
			UUID liar = topOf(fooledTotal);
			UUID detective = topOf(correctTotal);
			if (liar != null) {
				broadcast(text("Meister der Lügen: ", ChatFormatting.RED).append(text(name(liar) + " (" + fooledTotal.get(liar) + " hereingelegt)", ChatFormatting.WHITE)));
			}
			if (detective != null) {
				broadcast(text("Lügendetektor: ", ChatFormatting.AQUA).append(text(name(detective) + " (" + correctTotal.get(detective) + " richtig)", ChatFormatting.WHITE)));
			}
		}
		soundAll("minecraft:ui.toast.challenge_complete", 1.0f);
		setPhase(Phase.GAME_END, 15 * 20);
	}

	private UUID topOf(Map<UUID, Integer> m) {
		UUID best = null;
		int v = 0;
		for (Map.Entry<UUID, Integer> e : m.entrySet()) {
			if (e.getValue() > v) {
				v = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	private void finish() {
		for (UUID id : participants) {
			if (player(id) != null) {
				Cmd.gamemode(server, id, "survival");
			}
		}
		removeNametags();
		participants.clear();
		phase = Phase.IDLE;
		syncAll();
	}

	private void advance() {
		switch (phase) {
			case COUNTDOWN -> teleportAll();
			case ARRIVAL -> revealCards();
			case ADVENTURE -> returnToLobby();
			case STORY -> startVote();
			case VOTE -> startReveal();
			case REVEAL -> startStory();
			case ROUND_END -> beginRound();
			case GAME_END -> finish();
			default -> {
			}
		}
	}

	// =================================================================== Tick

	public void defer(Runnable r) {
		deferred.add(r);
	}

	/** Führt r nach ticks Server-Ticks aus (auch wenn kein Spiel läuft). */
	public void later(int ticks, Runnable r) {
		delayed.add(new Object[] { ticks, r });
	}

	/** Testet ein einzelnes Szenario sofort an dir selbst und meldet das Ergebnis im Chat. */
	public void testScenario(ServerPlayer p, Scenarios.Type type) {
		Placement pl = Scenarios.plan(server, cfg, type);
		boolean fallback = pl.type() != type;
		tell(p, text("Test " + type.display + ": ", ChatFormatting.YELLOW)
				.append(text(pl.title() + " @ " + pl.dim() + " " + pl.coords(), ChatFormatting.WHITE)));
		if (fallback) {
			tell(p, text("Das Szenario ist fehlgeschlagen, es wurde auf '" + pl.type().display + "' ausgewichen (Details im Server-Log).", ChatFormatting.RED));
		}
		tell(p, text("Befehle nach Ankunft: " + pl.commands().size(), ChatFormatting.GRAY));
		ServerLevel lvl = server.getLevel(Scenarios.dimKey(pl.dim()));
		if (lvl == null) {
			lvl = server.overworld();
		}
		p.teleport(new TeleportTransition(lvl, new Vec3(pl.x(), pl.y(), pl.z()), Vec3.ZERO, pl.yaw(), 0.0F, TeleportTransition.DO_NOTHING));
		later(10, () -> {
			for (String c : pl.commands()) {
				TruthOrLieMod.LOGGER.info("Test-Befehl: {}", c);
				Cmd.run(server, c);
			}
		});
	}

	public void tick() {
		if (!delayed.isEmpty()) {
			List<Object[]> due = new ArrayList<>();
			for (Object[] d : delayed) {
				d[0] = (Integer) d[0] - 1;
				if ((Integer) d[0] <= 0) {
					due.add(d);
				}
			}
			delayed.removeAll(due);
			for (Object[] d : due) {
				((Runnable) d[1]).run();
			}
		}
		if (!deferred.isEmpty()) {
			List<Runnable> copy = new ArrayList<>(deferred);
			deferred.clear();
			copy.forEach(Runnable::run);
		}
		if (phase == Phase.IDLE) {
			return;
		}

		boolean waitingForPlan = phase == Phase.COUNTDOWN && !planQueue.isEmpty();
		if (waitingForPlan) {
			planNext(); // ein Spieler pro Tick, damit der Server nicht hängt
			waitingForPlan = !planQueue.isEmpty();
		}

		// Erzähler weg? Dann nächste Story.
		if ((phase == Phase.STORY || phase == Phase.VOTE) && player(teller) == null) {
			broadcast(text("Der Erzähler hat das Spiel verlassen - nächste Story.", ChatFormatting.RED));
			startStory();
			return;
		}

		if (!paused) {
			if (waitingForPlan) {
				if (ticksLeft > 1) {
					ticksLeft--;
				}
			} else if (ticksLeft > 0) {
				ticksLeft--;
			}
			timedEvents();
		}

		if (ticksLeft <= 0 && !waitingForPlan) {
			advance();
			return;
		}

		if (phase == Phase.ARRIVAL && ticksTotal - ticksLeft == 5 && !paused) {
			runPostCommands();
		}
		if ((phase == Phase.VOTE || phase == Phase.STORY) && ticksLeft % 10 == 0) {
			enforceItems();
		}
		if (++syncCounter >= 20) {
			syncCounter = 0;
			syncAll();
		}
	}

	private void timedEvents() {
		if (ticksLeft % 20 != 0) {
			return;
		}
		int s = ticksLeft / 20;
		if (phase == Phase.COUNTDOWN && s >= 1 && s <= 5) {
			soundAll("minecraft:block.note_block.hat", 1.0f + (5 - s) * 0.1f);
		} else if (phase == Phase.ADVENTURE) {
			if (s == 60 || s == 30 || s == 10) {
				for (ServerPlayer p : onlineParticipants()) {
					tell(p, text("Noch " + s + " Sekunden!", ChatFormatting.YELLOW));
				}
				soundAll("minecraft:block.note_block.pling", 1.0f);
			} else if (s >= 1 && s <= 5) {
				soundAll("minecraft:block.note_block.hat", 1.4f);
			}
		} else if (phase == Phase.VOTE && s >= 1 && s <= 3) {
			soundAll("minecraft:block.note_block.hat", 1.0f);
		}
	}

	// =================================================================== Items

	private ItemStack voteItem(boolean truth, Boolean chosen) {
		ItemStack s = new ItemStack(truth ? Items.SLIME_BALL : Items.REDSTONE);
		MutableComponent n = text(truth ? "✔ WAHR" : "✘ LÜGE", truth ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD);
		if (chosen != null && chosen == truth) {
			n.append(text("   ◀ gewählt", ChatFormatting.GRAY));
		}
		s.set(DataComponents.CUSTOM_NAME, n.withStyle(style -> style.withItalic(false)));
		return s;
	}

	private ItemStack clockItem() {
		ItemStack s = new ItemStack(Items.CLOCK);
		s.set(DataComponents.CUSTOM_NAME, text("» Story beenden «", ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD).withStyle(style -> style.withItalic(false)));
		return s;
	}

	private static boolean isGameItem(ItemStack st) {
		if (st.isEmpty() || !st.has(DataComponents.CUSTOM_NAME)) {
			return false;
		}
		if (!(st.is(Items.SLIME_BALL) || st.is(Items.REDSTONE) || st.is(Items.CLOCK))) {
			return false;
		}
		String n = st.getHoverName().getString();
		return n.startsWith("✔ WAHR") || n.startsWith("✘ LÜGE") || n.startsWith("» Story");
	}

	private void saveSlots(ServerPlayer p) {
		savedItems.computeIfAbsent(p.getUUID(), k -> {
			Inventory inv = p.getInventory();
			return new ItemStack[] { inv.getItem(SLOT_TRUE).copy(), inv.getItem(SLOT_CLOCK).copy(), inv.getItem(SLOT_LIE).copy() };
		});
	}

	private void giveVoteItems(ServerPlayer p, Boolean chosen) {
		Inventory inv = p.getInventory();
		inv.setItem(SLOT_TRUE, voteItem(true, chosen));
		inv.setItem(SLOT_LIE, voteItem(false, chosen));
	}

	public void restoreItems(ServerPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (isGameItem(inv.getItem(i))) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
		ItemStack[] s = savedItems.remove(p.getUUID());
		if (s != null) {
			inv.setItem(SLOT_TRUE, s[0]);
			inv.setItem(SLOT_CLOCK, s[1]);
			inv.setItem(SLOT_LIE, s[2]);
		}
	}

	private void restoreAllItems() {
		for (UUID id : new ArrayList<>(savedItems.keySet())) {
			ServerPlayer p = player(id);
			if (p != null) {
				restoreItems(p);
			} else {
				savedItems.remove(id);
			}
		}
	}

	/** Verhindert, dass Spieler die Spielitems wegwerfen oder verschieben. */
	private void enforceItems() {
		for (ServerPlayer p : onlineParticipants()) {
			UUID id = p.getUUID();
			Inventory inv = p.getInventory();
			if (phase == Phase.VOTE && !id.equals(teller)) {
				for (int i = 0; i < inv.getContainerSize(); i++) {
					if (i != SLOT_TRUE && i != SLOT_LIE && isGameItem(inv.getItem(i))) {
						inv.setItem(i, ItemStack.EMPTY);
					}
				}
				Boolean chosen = votes.get(id);
				ItemStack a = inv.getItem(SLOT_TRUE);
				ItemStack b = inv.getItem(SLOT_LIE);
				if (!(isGameItem(a) && a.is(Items.SLIME_BALL)) || !(isGameItem(b) && b.is(Items.REDSTONE))) {
					saveSlots(p);
					giveVoteItems(p, chosen);
				}
			} else if (phase == Phase.STORY && id.equals(teller)) {
				for (int i = 0; i < inv.getContainerSize(); i++) {
					if (i != SLOT_CLOCK && isGameItem(inv.getItem(i))) {
						inv.setItem(i, ItemStack.EMPTY);
					}
				}
				if (!(isGameItem(inv.getItem(SLOT_CLOCK)) && inv.getItem(SLOT_CLOCK).is(Items.CLOCK))) {
					saveSlots(p);
					inv.setItem(SLOT_CLOCK, clockItem());
				}
			}
		}
	}

	/** Rechtsklick mit einem Spielitem. @return true, wenn das Item verarbeitet wurde. */
	public boolean handleItemUse(ServerPlayer p, ItemStack st) {
		UUID id = p.getUUID();
		if (phase == Phase.VOTE && participants.contains(id) && !id.equals(teller) && isGameItem(st)) {
			if (st.is(Items.SLIME_BALL) || st.is(Items.REDSTONE)) {
				String err = castVote(p, st.is(Items.SLIME_BALL));
				if (err != null) {
					tell(p, text(err, ChatFormatting.RED));
				}
				return true;
			}
		}
		if (phase == Phase.STORY && id.equals(teller) && isGameItem(st) && st.is(Items.CLOCK)) {
			doneTelling(p);
			return true;
		}
		return false;
	}

	// =================================================================== Tod / Schutz

	/** @return true = Tod normal zulassen, false = Tod verhindern */
	public boolean allowDeath(ServerPlayer p) {
		UUID id = p.getUUID();
		if (phase == Phase.IDLE || !participants.contains(id)) {
			return true;
		}
		p.setHealth(p.getMaxHealth());
		p.clearFire();
		if (phase == Phase.ARRIVAL || phase == Phase.ADVENTURE) {
			died.add(id);
			// Nur die Tatsache, nicht die Ursache.
			server.getPlayerList().broadcastSystemMessage(Component.literal("☠ " + name(id) + " ist gestorben").withStyle(ChatFormatting.GRAY), false);
			defer(() -> {
				ServerPlayer pl = player(id);
				if (pl == null) {
					return;
				}
				Placement pp = placements.get(id);
				if (pp == null) {
					teleportToLobby(pl, Math.max(0, participants.indexOf(id)), participants.size());
					return;
				}
				ServerLevel lvl = server.getLevel(Scenarios.dimKey(pp.dim()));
				if (lvl == null) {
					lvl = server.overworld();
				}
				pl.teleport(new TeleportTransition(lvl, new Vec3(pp.x(), pp.y(), pp.z()), Vec3.ZERO, pp.yaw(), 0.0F, TeleportTransition.DO_NOTHING));
				Cmd.clearEffects(server, id);
				if (cfg.protectSeconds > 0) {
					Cmd.effect(server, id, "minecraft:resistance", cfg.protectSeconds, 4);
				}
			});
		}
		return false;
	}

	/** Schaden wird in den Lobby-Phasen komplett verhindert (kein PvP, kein Hunger-Tod). */
	public boolean isDamageProtected(ServerPlayer p) {
		return phase != Phase.IDLE && phase != Phase.ADVENTURE && phase != Phase.ARRIVAL && participants.contains(p.getUUID());
	}

	public void onJoin(ServerPlayer p) {
		names.put(p.getUUID(), p.getName().getString());
		if (isRunning()) {
			Net.sendState(p, buildState(p));
		}
	}

	public void onDisconnect(ServerPlayer p) {
		restoreItems(p);
		names.put(p.getUUID(), p.getName().getString());
	}

	// =================================================================== Lobby-Teleport / Heilung

	private void healFully(ServerPlayer p) {
		p.setHealth(p.getMaxHealth());
		p.clearFire();
		Cmd.effect(server, p.getUUID(), "minecraft:saturation", 1, 10);
	}

	public void teleportToLobby(ServerPlayer p, int index, int count) {
		ServerLevel lvl = server.getLevel(Scenarios.dimKey(cfg.lobbyDim));
		if (lvl == null) {
			lvl = server.overworld();
		}
		double x = cfg.lobbyX;
		double z = cfg.lobbyZ;
		float yaw = cfg.lobbyYaw;
		if (count > 1 && cfg.lobbyRadius > 0 && index >= 0) {
			double a = Math.PI * 2 * index / count;
			double px = cfg.lobbyX + Math.cos(a) * cfg.lobbyRadius;
			double pz = cfg.lobbyZ + Math.sin(a) * cfg.lobbyRadius;
			BlockPos bp = BlockPos.containing(px, cfg.lobbyY, pz);
			if (lvl.getBlockState(bp).isAir() && lvl.getBlockState(bp.above()).isAir()) {
				x = px;
				z = pz;
				yaw = (float) Math.toDegrees(Math.atan2(-(cfg.lobbyX - px), cfg.lobbyZ - pz));
			}
		}
		p.teleport(new TeleportTransition(lvl, new Vec3(x, cfg.lobbyY, z), Vec3.ZERO, yaw, 0.0F, TeleportTransition.DO_NOTHING));
	}

	// =================================================================== Punkte

	public List<UUID> ranking() {
		List<UUID> list = new ArrayList<>(scores.keySet());
		list.sort(Comparator.<UUID>comparingInt(id -> -scores.getOrDefault(id, 0))
				.thenComparingInt(id -> -fooledTotal.getOrDefault(id, 0))
				.thenComparing(this::name));
		return list;
	}

	public void printRanking(ServerPlayer to, int limit) {
		List<UUID> rank = ranking();
		if (rank.isEmpty()) {
			Component c = text("Noch keine Punkte.", ChatFormatting.GRAY);
			if (to != null) {
				tellForce(to, c);
			} else {
				broadcast(c);
			}
			return;
		}
		for (int i = 0; i < Math.min(limit, rank.size()); i++) {
			UUID id = rank.get(i);
			int pts = scores.getOrDefault(id, 0);
			ChatFormatting col = i == 0 ? ChatFormatting.GOLD : i == 1 ? ChatFormatting.GRAY : i == 2 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY;
			MutableComponent line = text("  " + (i + 1) + ". ", col).append(text(name(id), ChatFormatting.WHITE))
					.append(text("  " + pts + (pts == 1 ? " Punkt" : " Punkte"), col));
			if (to != null) {
				tellForce(to, line);
			} else {
				broadcast(line);
			}
		}
	}

	public void setPoints(UUID id, int pts) {
		scores.put(id, pts);
		names.put(id, name(id));
		syncAll();
	}

	public int getPoints(UUID id) {
		return scores.getOrDefault(id, 0);
	}

	// =================================================================== Info für den Host

	public List<String> infoLines() {
		List<String> lines = new ArrayList<>();
		if (!isRunning()) {
			lines.add("Kein Spiel aktiv.");
			return lines;
		}
		lines.add("Runde " + round + "/" + totalRounds + ", Phase " + phase.label + (paused ? " (pausiert)" : "") + ", noch " + (ticksLeft / 20) + "s");
		for (UUID id : participants) {
			Placement pl = placements.get(id);
			lines.add(" - " + name(id) + ": " + cards.get(id) + " | " + (pl == null ? "?" : pl.title() + " @ " + pl.dim() + " " + pl.coords()) + (died.contains(id) ? " | gestorben" : ""));
		}
		return lines;
	}

	public String statusLine() {
		if (!isRunning()) {
			return "Kein Spiel aktiv. Starte mit /tol start.";
		}
		return "Runde " + round + "/" + totalRounds + " - " + phase.label + (paused ? " (pausiert)" : "") + " - noch " + (ticksLeft / 20) + " s";
	}

	public Card cardOf(UUID id) {
		return cards.get(id);
	}

	/** Darf dieser Spieler gerade seine eigene Karte sehen? (Schutz gegen Spoiler) */
	public boolean canSeeCard(UUID id) {
		return participants.contains(id) && cards.containsKey(id)
				&& (phase == Phase.ADVENTURE || (id.equals(teller) && (phase == Phase.STORY || phase == Phase.VOTE)));
	}

	public void cleanup() {
		Cmd.killTolMobs(server);
	}

	// =================================================================== Sync zum Client

	/**
	 * Zeile 1: phase;round;totalRounds;ticksLeft;ticksTotal;paused;ownCard;tellerName;votesIn;votersTotal;ownPoints;ownVote;isTeller;isParticipant
	 * Weitere Zeilen: name;punkte (Rangliste, max. 8)
	 */
	private String buildState(ServerPlayer viewer) {
		UUID id = viewer.getUUID();
		boolean part = participants.contains(id);
		int ownCard = 0;
		Card c = cards.get(id);
		if (part && c != null) {
			boolean show = phase == Phase.ADVENTURE || (id.equals(teller) && (phase == Phase.STORY || phase == Phase.VOTE));
			if (show) {
				ownCard = c == Card.TRUE ? 1 : 2;
			}
		}
		Boolean v = votes.get(id);
		int ownVote = v == null ? 0 : (v ? 1 : 2);
		StringBuilder sb = new StringBuilder();
		sb.append(phase.ordinal()).append(';').append(round).append(';').append(totalRounds).append(';')
				.append(ticksLeft).append(';').append(ticksTotal).append(';').append(paused ? 1 : 0).append(';')
				.append(ownCard).append(';').append(teller == null ? "" : name(teller)).append(';')
				.append(votes.size()).append(';').append(eligibleVoters().size()).append(';')
				.append(scores.getOrDefault(id, 0)).append(';').append(ownVote).append(';')
				.append(id.equals(teller) ? 1 : 0).append(';').append(part ? 1 : 0);
		List<UUID> rank = ranking();
		for (int i = 0; i < Math.min(8, rank.size()); i++) {
			sb.append('\n').append(name(rank.get(i))).append(';').append(scores.getOrDefault(rank.get(i), 0));
		}
		return sb.toString();
	}

	// =================================================================== Punkte über dem Kopf: "Name (1)"

	private final Map<UUID, Integer> tagShown = new HashMap<>();

	private static String teamName(UUID id) {
		return "tol_" + id.toString().substring(0, 8);
	}

	/** Setzt per Scoreboard-Team den Suffix " (Punkte)" hinter den Namen. Es werden nur Änderungen gesendet. */
	private void updateNametags() {
		if (phase == Phase.IDLE) {
			return;
		}
		for (UUID id : participants) {
			if (player(id) == null) {
				continue;
			}
			int pts = scores.getOrDefault(id, 0);
			Integer shown = tagShown.get(id);
			if (shown != null && shown == pts) {
				continue;
			}
			String team = teamName(id);
			if (shown == null) {
				Cmd.run(server, "team add " + team);
				Cmd.run(server, "team join " + team + " " + id);
			}
			Cmd.run(server, "team modify " + team + " suffix {\"text\":\" (" + pts + ")\",\"color\":\"gold\"}");
			tagShown.put(id, pts);
		}
	}

	private void removeNametags() {
		for (UUID id : tagShown.keySet()) {
			Cmd.run(server, "team remove " + teamName(id));
		}
		tagShown.clear();
	}

	public void syncAll() {
		updateNametags();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Net.sendState(p, buildState(p));
		}
	}
}
