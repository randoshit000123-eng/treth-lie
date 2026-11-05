package de.truthorlie.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import de.truthorlie.Phase;

/**
 * Minimalistisches HUD: nur eine kleine Zeile oben mittig (Punkt in Phasenfarbe, Timer, ggf. Erzähler
 * und deine Karte) und bei Bedarf ein kurzes Banner. Die Punkte stehen hinter den Namen über den Köpfen.
 */
public final class TolHud {
	private TolHud() {
	}

	private static final int TEXT = 0xFFF0F0F0;
	private static final int MUTED = 0xFFA3A3A3;
	private static final int PANEL = 0x55000000;
	private static final int TRUE_COLOR = 0xFF4ADE80;
	private static final int LIE_COLOR = 0xFFF87171;

	public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ClientState.hudEnabled) {
			return;
		}
		Font font = mc.font;
		if (ClientState.phase != Phase.IDLE) {
			int y = drawLine(g, font);
			drawVoteHint(g, font, y);
		}
		drawBanner(g, font);
	}

	// ------------------------------------------------------------------ Helfer

	private static void text(GuiGraphicsExtractor g, Font font, String s, float x, float y, int color, float scale) {
		g.pose().pushMatrix();
		g.pose().scale(scale);
		g.text(font, s, Math.round(x / scale), Math.round(y / scale), color);
		g.pose().popMatrix();
	}

	private static float width(Font font, String s, float scale) {
		return font.width(s) * scale;
	}

	private static int withAlpha(int argb, float alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static String time(int ticks) {
		int s = (ticks + 19) / 20;
		return String.format("%d:%02d", s / 60, s % 60);
	}

	private static String shorten(String n) {
		return n.length() > 12 ? n.substring(0, 11) + "…" : n;
	}

	// ------------------------------------------------------------------ Eine Zeile oben mittig

	/** @return y-Position unter der Zeile */
	private static int drawLine(GuiGraphicsExtractor g, Font font) {
		Phase p = ClientState.phase;
		int remaining = ClientState.remainingTicks();
		boolean urgent = !ClientState.paused && remaining > 0 && remaining <= 200
				&& (p == Phase.ADVENTURE || p == Phase.VOTE || p == Phase.STORY);

		String time = ClientState.paused ? "Pause" : time(remaining);
		String label = switch (p) {
			case STORY -> ClientState.isTeller ? "Du erzählst" : shorten(ClientState.teller);
			case VOTE -> "Abstimmung";
			case REVEAL -> "Auflösung";
			default -> "";
		};
		String card = ClientState.ownCard == 0 ? "" : (ClientState.ownCard == 1 ? "WAHR" : "LÜGE");
		int cardColor = ClientState.ownCard == 1 ? TRUE_COLOR : LIE_COLOR;
		int timeColor = urgent ? LIE_COLOR : TEXT;

		float s = 0.9f;
		float gap = 6;
		float w = 10; // Punkt + Rand
		if (!label.isEmpty()) {
			w += width(font, label, s) + gap;
		}
		w += width(font, time, s);
		if (!card.isEmpty()) {
			w += gap + width(font, card, s);
		}
		w += 8;

		float cx = g.guiWidth() / 2f;
		int x1 = Math.round(cx - w / 2f);
		int x2 = Math.round(cx + w / 2f);
		int y1 = 3;
		int y2 = y1 + 12;
		g.fill(x1 + 1, y1, x2 - 1, y2, PANEL);
		g.fill(x1, y1 + 1, x2, y2 - 1, PANEL);

		// kleiner Punkt in der Phasenfarbe
		g.fill(x1 + 5, y1 + 4, x1 + 8, y1 + 7, p.color);
		float x = x1 + 12;
		if (!label.isEmpty()) {
			text(g, font, label, x, y1 + 3, MUTED, s);
			x += width(font, label, s) + gap;
		}
		text(g, font, time, x, y1 + 3, timeColor, s);
		x += width(font, time, s);
		if (!card.isEmpty()) {
			x += gap;
			text(g, font, card, x, y1 + 3, cardColor, s);
		}
		return y2 + 3;
	}

	/** Nur in der Abstimmung, ohne Hintergrund, bis man abgestimmt hat. */
	private static void drawVoteHint(GuiGraphicsExtractor g, Font font, int y) {
		if (ClientState.phase != Phase.VOTE || ClientState.isTeller || ClientState.ownVote != 0 || ClientState.paused) {
			return;
		}
		String hint = "Schleimball = WAHR  ·  Redstone = LÜGE";
		float s = 0.75f;
		text(g, font, hint, g.guiWidth() / 2f - width(font, hint, s) / 2f, y, MUTED, s);
	}

	// ------------------------------------------------------------------ Banner (nur Karte, Auflösung, Gewinner)

	private static void drawBanner(GuiGraphicsExtractor g, Font font) {
		if (ClientState.bannerTicks <= 0) {
			return;
		}
		long age = ClientState.tick - ClientState.bannerStart;
		if (age < 0 || age >= ClientState.bannerTicks) {
			return;
		}
		float alpha = 0.85f * Math.min(1f, Math.min(age / 5f, (ClientState.bannerTicks - age) / 10f));
		if (alpha * 255 < 8) {
			return;
		}
		String title = ClientState.bannerTitle;
		float scale = 1.8f;
		float maxW = g.guiWidth() * 0.7f;
		while (scale > 1.2f && width(font, title, scale) > maxW) {
			scale -= 0.2f;
		}
		float cx = g.guiWidth() / 2f;
		float y = g.guiHeight() * 0.14f;
		int color = withAlpha(ClientState.bannerColor | 0xFF000000, alpha);
		text(g, font, title, cx - width(font, title, scale) / 2f, y, color, scale);
		if (!ClientState.bannerSub.isEmpty()) {
			float s = 0.8f;
			String sub = ClientState.bannerSub;
			text(g, font, sub, cx - width(font, sub, s) / 2f, y + 9 * scale + 3, withAlpha(0xFFE5E5E5, alpha), s);
		}
	}
}
