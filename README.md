# Wahr oder Lüge – Fabric-Mod für Minecraft 26.3

Multiplayer-Minispiel nach dem Bastighg-Prinzip. Server und alle Spieler brauchen Fabric Loader, Fabric API 0.165.0+26.3 und diese Mod (Spieler ohne Mod können mitspielen, sehen aber kein HUD, nur Chat und Titel).

## Bauen
Java 25 installieren, dann: `./gradlew build` -> `build/libs/truth-or-lie-1.0.0.jar` in den mods-Ordner (Server und Clients).

HINWEIS: Der Code wurde ohne Compiler-Zugriff auf Minecraft geschrieben (nur gegen die Quellen von Fabric API / Fabric Docs für 26.3 geprüft). Falls der Build meckert, sind das die Stellen, die ich nicht belegen konnte:
- `Scenarios.java`: `ServerLevel.findNearestMapStructure(...)`
- `GameManager.java`: `PlayerList.getPlayer(UUID)`, `Style.withItalic`, `ItemStack.has(...)`
- `Cmd.java`: `CommandSourceStack.withSuppressedOutput()`
- `TolCommands.java`: `CommandSourceStack.getPlayer()`
- `TolHud.java`: `g.pose().scale(float)`
Schick mir einfach die Fehlermeldung, dann korrigiere ich es.

## Spielablauf
1. `/tol start [runden]` (OP) – deine Position wird die Lobby (oder vorher `/tol lobby set`).
2. VORBEREITUNG (10 s): Orte werden im Hintergrund gesucht, 5-4-3-2-1.
3. Alle werden zufällig verteilt: Struktur, Mob direkt vor dir, Höhle, Nether (auch Festung/Bastion von innen, Nether-Mobs), End (äußere Inseln, End-Stadt von innen, End-Mobs), Ozean (mit Boot) oder Wildnis.
4. ANKUNFT (6 s), dann wird die Karte gezogen: WAHR oder LÜGE (nur du siehst sie, Karten sind fair ~50/50 verteilt).
5. ABENTEUER: 3-Minuten-Timer. Stirbt jemand, wartet er in der Lobby (kein Respawn-Chaos).
6. Zurück in die Lobby (Kreis). Reihum: STORY (Uhr-Item oder `/tol done` beendet sie) -> ABSTIMMUNG (Schleimball = WAHR, Redstone = LÜGE, ändern möglich) -> AUFLÖSUNG (zeigt auch, was wirklich erlebt wurde).
7. Rundenende mit Zwischenstand, am Ende Gewinner, "Meister der Lügen" und "Lügendetektor".

Punkte: +1 für richtiges Raten, +1 für jeden, der bei deiner Story falsch lag (hereingelegt). Wer nicht abstimmt, bekommt nichts.

## Commands (`/tol`, Alias `/truthorlie`)
Alle: `status`, `scores`, `card`, `idea` (Lügen-Tipps), `done`, `vote true|lie`, `join`, `leave`, `rules`
OP: `start [n]`, `stop`, `skip`, `pause`, `resume`, `lobby set|tp`, `info` (zeigt Karten und Orte aller), `cleanup`, `points <spieler> set|add <n>`, `config` / `config set <key> <wert>`, `scenario <id> on|off`
Client: `/tolhud on|off|scores`

Einstellbar u. a.: adventure (Standard 180 s), story, vote, rounds, mindistance/maxdistance, mobs (0 harmlos, 1 normal, 2 brutal inkl. Warden), minplayers (1 = Solo-Test). Config-Datei: `config/truthorlie.json`.

## HUD
Minimal: eine kleine Zeile oben mittig (Punkt in Phasenfarbe, Timer, Erzähler, deine Karte). Kurze Banner nur bei Kartenreveal, Auflösung und Gewinner. Die Punkte stehen hinter dem Namen über dem Kopf: `Name (1)`. Im Chat steht nur noch "☠ Name ist gestorben". Abstimmen: Schleimball = WAHR, Redstone = LÜGE. F1 blendet alles aus, `/tolhud off` ebenso.

Stirbt man in der Runde, respawnt man am Startort der Runde. Unterirdische Strukturen (Prüfungskammer, Mine) werden von innen betreten. `/tol test <szenario>` testet ein Szenario sofort.
