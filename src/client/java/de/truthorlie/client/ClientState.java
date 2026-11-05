package de.truthorlie.client;

import java.util.ArrayList;
import java.util.List;

import de.truthorlie.Phase;

/** Vom Server empfangener Spielzustand fürs HUD. Wird nur auf dem Client-Thread benutzt. */
public final class ClientState {
	private ClientState() {
	}

	public record Row(String name, int points) {
	}

	public static long tick;

	public static boolean hudEnabled = true;
	public static boolean scoresEnabled = true;

	public static Phase phase = Phase.IDLE;
	public static int round;
	public static int totalRounds;
	public static int total = 1;
	public static boolean paused;
	public static long endTick;
	public static int pausedLeft;
	/** 0 = keine, 1 = WAHR, 2 = LÜGE */
	public static int ownCard;
	public static String teller = "";
	public static int votesIn;
	public static int votersTotal;
	public static int ownPoints;
	/** 0 = nicht abgestimmt, 1 = WAHR, 2 = LÜGE */
	public static int ownVote;
	public static boolean isTeller;
	public static boolean participant;
	public static List<Row> board = new ArrayList<>();

	public static String bannerTitle = "";
	public static String bannerSub = "";
	public static int bannerColor = 0xFFFFFFFF;
	public static long bannerStart;
	public static int bannerTicks;

	public static void reset() {
		phase = Phase.IDLE;
		board = new ArrayList<>();
		bannerTicks = 0;
		ownCard = 0;
	}

	public static int remainingTicks() {
		return paused ? pausedLeft : (int) Math.max(0, endTick - tick);
	}

	/** Format siehe GameManager#buildState. */
	public static void applyState(String data) {
		try {
			String[] lines = data.split("\n");
			String[] f = lines[0].split(";", -1);
			phase = Phase.byOrdinal(Integer.parseInt(f[0]));
			round = Integer.parseInt(f[1]);
			totalRounds = Integer.parseInt(f[2]);
			int left = Integer.parseInt(f[3]);
			total = Math.max(1, Integer.parseInt(f[4]));
			paused = "1".equals(f[5]);
			ownCard = Integer.parseInt(f[6]);
			teller = f[7];
			votesIn = Integer.parseInt(f[8]);
			votersTotal = Integer.parseInt(f[9]);
			ownPoints = Integer.parseInt(f[10]);
			ownVote = Integer.parseInt(f[11]);
			isTeller = "1".equals(f[12]);
			participant = "1".equals(f[13]);
			pausedLeft = left;
			endTick = tick + left;
			List<Row> rows = new ArrayList<>();
			for (int i = 1; i < lines.length; i++) {
				String[] r = lines[i].split(";", -1);
				if (r.length >= 2) {
					rows.add(new Row(r[0], Integer.parseInt(r[1])));
				}
			}
			board = rows;
		} catch (Exception ignored) {
			// kaputtes Paket ignorieren, nächstes Update korrigiert es
		}
	}

	public static void applyBanner(String data) {
		try {
			String[] f = data.split("\n", -1);
			bannerTitle = f[0];
			bannerSub = f[1];
			bannerColor = (int) Long.parseLong(f[2]);
			bannerTicks = Integer.parseInt(f[3]);
			bannerStart = tick;
		} catch (Exception ignored) {
			// ignorieren
		}
	}
}
