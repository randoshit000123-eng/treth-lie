package de.truthorlie;

/** Alle Spielphasen. Wird auch vom Client fürs HUD genutzt (Label + Farbe). */
public enum Phase {
	IDLE("", 0xFF9CA3AF),
	COUNTDOWN("VORBEREITUNG", 0xFFFBBF24),
	ARRIVAL("ANKUNFT", 0xFFFBBF24),
	ADVENTURE("ABENTEUER", 0xFF38BDF8),
	STORY("STORY", 0xFFA78BFA),
	VOTE("ABSTIMMUNG", 0xFFFB923C),
	REVEAL("AUFLÖSUNG", 0xFF4ADE80),
	ROUND_END("RUNDE VORBEI", 0xFFFBBF24),
	GAME_END("SPIELENDE", 0xFFFBBF24);

	public final String label;
	public final int color;

	Phase(String label, int color) {
		this.label = label;
		this.color = color;
	}

	public static Phase byOrdinal(int i) {
		Phase[] v = values();
		return i >= 0 && i < v.length ? v[i] : IDLE;
	}
}
