package de.truthorlie;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Inspiration für Lügner (/tol idea). */
public final class Ideas {
	private Ideas() {
	}

	private static final List<String> LIE_IDEAS = List.of(
			"Erwähne ein winziges, überflüssiges Detail (z. B. die Farbe von etwas) - das wirkt glaubwürdig.",
			"Erzähle eine Geschichte mit einem kleinen Missgeschick. Perfekte Stories wirken verdächtig.",
			"Baue ein Gefühl ein: Du hattest Angst, Panik oder hast dich total gefreut.",
			"Bleib bei der Wahrheit so nah wie möglich und ändere nur EIN großes Detail.",
			"Erzähle chronologisch: erst das, dann das, dann ... Struktur wirkt ehrlich.",
			"Nenne eine Koordinate, Uhrzeit oder Zahl - Lügner meiden oft konkrete Zahlen.",
			"Lass dich nicht zu schnell unterbrechen und bleib ruhig. Zu viel Rechtfertigung verrät dich.",
			"Behaupte, du hättest etwas gefunden/erlebt, das zur Umgebung passt, aber nicht zu offensichtlich ist.",
			"Mach eine kurze Pause, als würdest du dich erinnern.",
			"Erfinde einen Mob, dem du entkommen bist - aber nenne dessen Verhalten genau.",
			"Erzähle weniger, als du könntest. Wer zu viel redet, wirkt nervös.",
			"Lenke ab: Erwähne etwas Beiläufiges, das dir 'noch einfiel'.");

	public static String random() {
		return LIE_IDEAS.get(ThreadLocalRandom.current().nextInt(LIE_IDEAS.size()));
	}
}
