package de.truthorlie;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * /tol (Alias: /truthorlie)
 *
 * Für alle: status, scores, rules, join, leave, vote, done, card, idea
 * Für Admins (OP): start, stop, skip, pause, resume, lobby, config, scenario, points, info, cleanup
 */
public final class TolCommands {
	private TolCommands() {
	}

	private static boolean admin(CommandSourceStack s) {
		return s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private static int ok(CommandContext<CommandSourceStack> c, String s) {
		c.getSource().sendSuccess(() -> Component.literal(s), false);
		return 1;
	}

	private static int fail(CommandContext<CommandSourceStack> c, String s) {
		c.getSource().sendFailure(Component.literal(s).withStyle(ChatFormatting.RED));
		return 0;
	}

	private static GameManager g(CommandContext<CommandSourceStack> c) {
		return TruthOrLieMod.game();
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		TolConfig cfg = TruthOrLieMod.config();

		LiteralArgumentBuilder<CommandSourceStack> root = literal("tol");

		// ---------------------------------------------------------------- Admin: Spielsteuerung
		root.then(literal("start").requires(TolCommands::admin)
				.executes(c -> start(c, 0))
				.then(argument("rounds", IntegerArgumentType.integer(1, 50)).executes(c -> start(c, IntegerArgumentType.getInteger(c, "rounds")))));

		root.then(literal("stop").requires(TolCommands::admin).executes(c -> {
			if (g(c) == null || !g(c).isRunning()) {
				return fail(c, "Es läuft kein Spiel.");
			}
			g(c).stop(true);
			return 1;
		}));

		root.then(literal("skip").requires(TolCommands::admin).executes(c -> {
			if (g(c) == null || !g(c).isRunning()) {
				return fail(c, "Es läuft kein Spiel.");
			}
			g(c).skip();
			return ok(c, "Phase übersprungen.");
		}));

		root.then(literal("pause").requires(TolCommands::admin).executes(c -> {
			if (g(c) == null || !g(c).isRunning()) {
				return fail(c, "Es läuft kein Spiel.");
			}
			g(c).setPaused(true);
			g(c).broadcast(GameManager.text("Pausiert.", ChatFormatting.YELLOW));
			return 1;
		}));

		root.then(literal("resume").requires(TolCommands::admin).executes(c -> {
			if (g(c) == null || !g(c).isRunning()) {
				return fail(c, "Es läuft kein Spiel.");
			}
			g(c).setPaused(false);
			g(c).broadcast(GameManager.text("Weiter geht's!", ChatFormatting.GREEN));
			return 1;
		}));

		root.then(literal("cleanup").requires(TolCommands::admin).executes(c -> {
			if (g(c) == null) {
				return fail(c, "Server nicht bereit.");
			}
			g(c).cleanup();
			return ok(c, "Spiel-Mobs entfernt.");
		}));

		root.then(literal("info").requires(TolCommands::admin).executes(c -> {
			if (g(c) == null) {
				return fail(c, "Server nicht bereit.");
			}
			for (String line : g(c).infoLines()) {
				ok(c, line);
			}
			return 1;
		}));

		// ---------------------------------------------------------------- Admin: Lobby
		root.then(literal("lobby").requires(TolCommands::admin)
				.then(literal("set").executes(c -> {
					ServerPlayer p = c.getSource().getPlayer();
					if (p == null || g(c) == null) {
						return fail(c, "Nur als Spieler nutzbar.");
					}
					g(c).setLobby(p);
					return ok(c, "Lobby gesetzt: " + (int) p.getX() + " " + (int) p.getY() + " " + (int) p.getZ() + " (" + cfg.lobbyDim + ")");
				}))
				.then(literal("tp").executes(c -> {
					ServerPlayer p = c.getSource().getPlayer();
					if (p == null || g(c) == null) {
						return fail(c, "Nur als Spieler nutzbar.");
					}
					if (!cfg.lobbySet) {
						return fail(c, "Noch keine Lobby gesetzt.");
					}
					g(c).teleportToLobby(p, 0, 1);
					return 1;
				})));

		// ---------------------------------------------------------------- Admin: Config
		LiteralArgumentBuilder<CommandSourceStack> config = literal("config").requires(TolCommands::admin).executes(c -> {
			for (TolConfig.Key k : TolConfig.KEYS) {
				ok(c, k.name() + " = " + k.get().applyAsInt(cfg) + "   (" + k.description() + ")");
			}
			return 1;
		});
		LiteralArgumentBuilder<CommandSourceStack> set = literal("set");
		for (TolConfig.Key k : TolConfig.KEYS) {
			set.then(literal(k.name()).then(argument("value", IntegerArgumentType.integer(k.min(), k.max())).executes(c -> {
				int v = IntegerArgumentType.getInteger(c, "value");
				k.set().accept(cfg, v);
				cfg.save();
				return ok(c, k.name() + " = " + v);
			})));
		}
		config.then(set);
		root.then(config);

		LiteralArgumentBuilder<CommandSourceStack> scenario = literal("scenario").requires(TolCommands::admin).executes(c -> {
			for (Scenarios.Type t : Scenarios.Type.values()) {
				ok(c, t.id + " (" + t.display + "): " + (cfg.disabledScenarios.contains(t.id) ? "AUS" : "an"));
			}
			return 1;
		});
		for (Scenarios.Type t : Scenarios.Type.values()) {
			scenario.then(literal(t.id)
					.then(literal("on").executes(c -> {
						cfg.disabledScenarios.remove(t.id);
						cfg.save();
						return ok(c, t.display + " aktiviert.");
					}))
					.then(literal("off").executes(c -> {
						if (!cfg.disabledScenarios.contains(t.id)) {
							cfg.disabledScenarios.add(t.id);
						}
						cfg.save();
						return ok(c, t.display + " deaktiviert.");
					})));
		}
		root.then(scenario);

		LiteralArgumentBuilder<CommandSourceStack> test = literal("test").requires(TolCommands::admin);
		for (Scenarios.Type t : Scenarios.Type.values()) {
			test.then(literal(t.id).executes(c -> {
				ServerPlayer p = c.getSource().getPlayer();
				if (p == null || g(c) == null) {
					return fail(c, "Nur als Spieler nutzbar.");
				}
				g(c).testScenario(p, t);
				return 1;
			}));
		}
		root.then(test);

		// ---------------------------------------------------------------- Admin: Punkte
		root.then(literal("points").requires(TolCommands::admin)
				.then(argument("player", EntityArgument.player())
						.then(literal("set").then(argument("amount", IntegerArgumentType.integer(0, 9999)).executes(c -> {
							if (g(c) == null) {
								return fail(c, "Server nicht bereit.");
							}
							ServerPlayer t = EntityArgument.getPlayer(c, "player");
							g(c).setPoints(t.getUUID(), IntegerArgumentType.getInteger(c, "amount"));
							return ok(c, t.getName().getString() + " hat jetzt " + g(c).getPoints(t.getUUID()) + " Punkte.");
						})))
						.then(literal("add").then(argument("amount", IntegerArgumentType.integer(-9999, 9999)).executes(c -> {
							if (g(c) == null) {
								return fail(c, "Server nicht bereit.");
							}
							ServerPlayer t = EntityArgument.getPlayer(c, "player");
							int now = Math.max(0, g(c).getPoints(t.getUUID()) + IntegerArgumentType.getInteger(c, "amount"));
							g(c).setPoints(t.getUUID(), now);
							return ok(c, t.getName().getString() + " hat jetzt " + now + " Punkte.");
						})))));

		// ---------------------------------------------------------------- Für alle
		root.then(literal("status").executes(c -> g(c) == null ? fail(c, "Server nicht bereit.") : ok(c, g(c).statusLine())));

		root.then(literal("scores").executes(c -> {
			ServerPlayer p = c.getSource().getPlayer();
			if (g(c) == null) {
				return fail(c, "Server nicht bereit.");
			}
			if (p == null) {
				return ok(c, "Rangliste nur als Spieler sichtbar.");
			}
			g(c).printRanking(p, 15);
			return 1;
		}));

		root.then(literal("join").executes(c -> {
			ServerPlayer p = c.getSource().getPlayer();
			if (p == null || g(c) == null) {
				return fail(c, "Nur als Spieler nutzbar.");
			}
			g(c).optIn(p.getUUID());
			return ok(c, "Du spielst ab der nächsten Runde mit.");
		}));

		root.then(literal("leave").executes(c -> {
			ServerPlayer p = c.getSource().getPlayer();
			if (p == null || g(c) == null) {
				return fail(c, "Nur als Spieler nutzbar.");
			}
			g(c).optOut(p.getUUID());
			return ok(c, "Du spielst nicht mehr mit (Zuschauer). Zurück mit /tol join.");
		}));

		root.then(literal("vote")
				.then(literal("true").executes(c -> vote(c, true)))
				.then(literal("lie").executes(c -> vote(c, false))));

		root.then(literal("done").executes(c -> {
			ServerPlayer p = c.getSource().getPlayer();
			if (p == null || g(c) == null) {
				return fail(c, "Nur als Spieler nutzbar.");
			}
			String err = g(c).doneTelling(p);
			return err == null ? 1 : fail(c, err);
		}));

		root.then(literal("card").executes(c -> {
			ServerPlayer p = c.getSource().getPlayer();
			if (p == null || g(c) == null) {
				return fail(c, "Nur als Spieler nutzbar.");
			}
			if (!g(c).canSeeCard(p.getUUID())) {
				return fail(c, "Du hast gerade keine Karte, die du ansehen darfst.");
			}
			GameManager.Card card = g(c).cardOf(p.getUUID());
			return ok(c, "Deine Karte: " + (card == GameManager.Card.TRUE ? "WAHR" : "LÜGE"));
		}));

		root.then(literal("idea").executes(c -> ok(c, "Idee: " + Ideas.random())));

		root.then(literal("rules").executes(TolCommands::rules));
		root.then(literal("help").executes(TolCommands::rules));
		root.executes(TolCommands::rules);

		LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
		dispatcher.register(literal("truthorlie").redirect(node));
	}

	private static int start(CommandContext<CommandSourceStack> c, int rounds) {
		if (g(c) == null) {
			return fail(c, "Server nicht bereit.");
		}
		String err = g(c).start(c.getSource().getPlayer(), rounds);
		return err == null ? 1 : fail(c, err);
	}

	private static int vote(CommandContext<CommandSourceStack> c, boolean truth) {
		ServerPlayer p = c.getSource().getPlayer();
		if (p == null || g(c) == null) {
			return fail(c, "Nur als Spieler nutzbar.");
		}
		String err = g(c).castVote(p, truth);
		return err == null ? 1 : fail(c, err);
	}

	private static int rules(CommandContext<CommandSourceStack> c) {
		String[] lines = {
				"=== Wahr oder Lüge ===",
				"1) Alle werden zufällig verteilt (Struktur, Mob, Höhle, Nether, Ozean ...).",
				"2) Kurz nach der Ankunft erfährst du deine Karte: WAHR oder LÜGE (nur du siehst sie).",
				"3) Nach dem Timer geht's zurück in die Lobby. Jeder erzählt seine Story - wahr oder erfunden.",
				"4) Die anderen stimmen mit den Items ab: grün = WAHR, rot = LÜGE (oder /tol vote true|lie).",
				"5) Punkte: +1 fürs richtige Raten, +1 für jeden, den du hereingelegt hast.",
				"Befehle: /tol status, scores, card, idea, done, join, leave",
				"Client-HUD: /tolhud on|off|scores" };
		for (String l : lines) {
			ok(c, l);
		}
		return 1;
	}
}
