package de.truthorlie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Sucht für jeden Spieler einen zufälligen Ort bzw. ein Ereignis ("Szenario").
 * Die Suche läuft vor dem Teleport (während des Countdowns), damit beim Start niemand laggt.
 */
public final class Scenarios {
	private Scenarios() {
	}

	public enum Type {
		STRUCTURE("structure", "Struktur", 4),
		MOB("mob", "Mob-Begegnung", 4),
		NETHER("nether", "Nether", 2),
		CAVE("cave", "Höhle", 2),
		OCEAN("ocean", "Ozean", 2),
		WILD("wild", "Wildnis", 2),
		NETHER_STRUCTURE("nether_structure", "Nether-Struktur", 2),
		NETHER_MOB("nether_mob", "Nether-Mob", 2),
		END("end", "End-Inseln", 1),
		END_STRUCTURE("end_structure", "End-Stadt", 2),
		END_MOB("end_mob", "End-Mob", 1);

		public final String id;
		public final String display;
		public final int weight;

		Type(String id, String display, int weight) {
			this.id = id;
			this.display = display;
			this.weight = weight;
		}

		public static Type byId(String id) {
			for (Type t : values()) {
				if (t.id.equals(id)) {
					return t;
				}
			}
			return null;
		}
	}

	/** Ergebnis der Planung: wohin, mit welchem Titel, und welche Commands nach dem Teleport laufen. */
	public record Placement(Type type, String dim, double x, double y, double z, float yaw, String title, List<String> commands) {
		public String coords() {
			return (int) x + " " + (int) y + " " + (int) z;
		}
	}

	// ------------------------------------------------------------------ Dimensionen

	public static ResourceKey<Level> dimKey(String dim) {
		return switch (dim) {
			case "the_nether" -> Level.NETHER;
			case "the_end" -> Level.END;
			default -> Level.OVERWORLD;
		};
	}

	public static String dimOf(ServerLevel level) {
		if (level.dimension() == Level.NETHER) {
			return "the_nether";
		}
		if (level.dimension() == Level.END) {
			return "the_end";
		}
		return "overworld";
	}

	private static String exec(String dim) {
		return "execute in minecraft:" + dim + " run ";
	}

	// ------------------------------------------------------------------ Planung

	public static Type pickType(TolConfig cfg, Type avoid) {
		List<Type> pool = new ArrayList<>();
		for (Type t : Type.values()) {
			if (cfg.disabledScenarios.contains(t.id)) {
				continue;
			}
			for (int i = 0; i < t.weight; i++) {
				pool.add(t);
			}
		}
		if (pool.isEmpty()) {
			return Type.WILD;
		}
		// Nicht zweimal hintereinander dasselbe Szenario, wenn es Alternativen gibt.
		List<Type> filtered = new ArrayList<>(pool);
		filtered.removeIf(t -> t == avoid);
		if (!filtered.isEmpty()) {
			pool = filtered;
		}
		return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}

	public static Placement plan(MinecraftServer server, TolConfig cfg, Type type) {
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		double cx = cfg.lobbyX;
		double cz = cfg.lobbyZ;
		if ("the_nether".equals(cfg.lobbyDim)) {
			cx *= 8;
			cz *= 8;
		} else if ("the_end".equals(cfg.lobbyDim)) {
			cx = 0;
			cz = 0;
		}
		Placement result = null;
		try {
			result = switch (type) {
				case STRUCTURE -> planStructure(overworld, cfg, cx, cz);
				case MOB -> planMob(overworld, cfg, cx, cz);
				case NETHER -> planNether(server, cfg, cx, cz);
				case CAVE -> planCave(overworld, cfg, cx, cz);
				case OCEAN -> planOcean(overworld, cfg, cx, cz);
				case WILD -> planWild(overworld, cfg, cx, cz);
				case NETHER_STRUCTURE -> planNetherStructure(server, cfg, cx, cz);
				case NETHER_MOB -> planDimMob(server, cfg, cx, cz, false);
				case END -> planEnd(server);
				case END_STRUCTURE -> planEndStructure(server);
				case END_MOB -> planDimMob(server, cfg, cx, cz, true);
			};
		} catch (Exception e) {
			TruthOrLieMod.LOGGER.warn("Szenario {} fehlgeschlagen, nutze Wildnis", type, e);
		}
		if (result == null) {
			result = planWild(overworld, cfg, cx, cz);
		}
		return result;
	}

	// ------------------------------------------------------------------ Hilfsfunktionen

	private static BlockPos ringPoint(TolConfig cfg, double cx, double cz, double scale) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		double min = Math.min(cfg.minDistance, cfg.maxDistance) * scale;
		double max = Math.max(cfg.minDistance, cfg.maxDistance) * scale;
		double angle = r.nextDouble() * Math.PI * 2;
		double dist = min + r.nextDouble() * Math.max(1, max - min);
		return new BlockPos((int) (cx + Math.cos(angle) * dist), 0, (int) (cz + Math.sin(angle) * dist));
	}

	/**
	 * Oberfläche an (x,z). Der Chunk wird zuerst vollständig erzeugt, sonst liefert die Höhenkarte
	 * nur die Weltuntergrenze (-64) und man landet im Boden.
	 */
	private static BlockPos surface(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
		if (top.getY() > -60) {
			return top;
		}
		// Notlösung: von oben nach unten den ersten festen Block suchen.
		for (int y = 319; y > -60; y--) {
			BlockPos p = new BlockPos(x, y, z);
			if (!level.getBlockState(p).isAir()) {
				return p.above();
			}
		}
		return top;
	}

	private static boolean isWet(ServerLevel level, BlockPos top) {
		return !level.getFluidState(top.below()).isEmpty() || !level.getFluidState(top).isEmpty();
	}

	private static boolean dangerousGround(ServerLevel level, BlockPos top) {
		BlockState g = level.getBlockState(top.below());
		return g.is(Blocks.CACTUS) || g.is(Blocks.MAGMA_BLOCK) || g.is(Blocks.FIRE) || g.is(Blocks.SOUL_FIRE);
	}

	private static boolean isFree(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return s.isAir() && s.getFluidState().isEmpty();
	}

	private static boolean isGround(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return !s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.MAGMA_BLOCK) && !s.is(Blocks.CACTUS)
				&& !s.is(Blocks.FIRE) && !s.is(Blocks.SOUL_FIRE) && !s.is(Blocks.BEDROCK);
	}

	private static float randomYaw() {
		return ThreadLocalRandom.current().nextFloat() * 360f - 180f;
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	// ------------------------------------------------------------------ Wildnis

	private static Placement planWild(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		BlockPos last = null;
		for (int i = 0; i < 25; i++) {
			BlockPos p = ringPoint(cfg, cx, cz, 1);
			BlockPos top = surface(overworld, p.getX(), p.getZ());
			last = top;
			if (!isWet(overworld, top) && !dangerousGround(overworld, top)) {
				break;
			}
		}
		return new Placement(Type.WILD, "overworld", last.getX() + 0.5, last.getY(), last.getZ() + 0.5, randomYaw(),
				"Mitten in der Wildnis", new ArrayList<>());
	}

	// ------------------------------------------------------------------ Struktur

	private record Struct(String tag, String name) {
	}

	private static final List<Struct> STRUCTS = List.of(
			new Struct("village", "Dorf"),
			new Struct("on_woodland_explorer_maps", "Waldanwesen"),
			new Struct("on_ocean_explorer_maps", "Ozeanmonument"),
			new Struct("on_trial_chambers_maps", "Prüfungskammer"),
			new Struct("on_desert_explorer_maps", "Wüstentempel"),
			new Struct("on_jungle_explorer_maps", "Dschungeltempel"),
			new Struct("on_swamp_explorer_maps", "Hexenhütte"),
			new Struct("ruined_portal", "Zerstörtes Portal"),
			new Struct("shipwreck", "Schiffswrack"),
			new Struct("ocean_ruin", "Ozean-Ruine"),
			new Struct("mineshaft", "Verlassene Mine"));

	/** Unterirdische Strukturen: {obere Suchhöhe, untere Suchhöhe}. */
	private static final Map<String, int[]> UNDERGROUND = Map.of(
			"on_trial_chambers_maps", new int[] { -8, -48 },
			"mineshaft", new int[] { 50, -50 });

	/** Sucht von der Strukturmitte spiralförmig nach außen einen begehbaren Hohlraum. */
	private static BlockPos findInside(ServerLevel level, BlockPos center, int yMax, int yMin) {
		for (int r = 0; r <= 36; r += 3) {
			for (int dx = -r; dx <= r; dx += 3) {
				for (int dz = -r; dz <= r; dz += 3) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					int x = center.getX() + dx;
					int z = center.getZ() + dz;
					level.getChunk(x >> 4, z >> 4);
					for (int y = yMax; y > yMin; y--) {
						BlockPos pos = new BlockPos(x, y, z);
						if (isFree(level, pos) && isFree(level, pos.above()) && isGround(level, pos.below())) {
							return pos;
						}
					}
				}
			}
		}
		return null;
	}

	private static Placement planStructure(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		List<Struct> order = new ArrayList<>(STRUCTS);
		Collections.shuffle(order);
		for (int i = 0; i < Math.min(6, order.size()); i++) {
			Struct s = order.get(i);
			BlockPos origin = ringPoint(cfg, cx, cz, 1);
			TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath("minecraft", s.tag()));
			BlockPos found = overworld.findNearestMapStructure(tag, origin, 100, false);
			if (found == null) {
				TruthOrLieMod.LOGGER.info("Keine Struktur gefunden für Tag {}", s.tag());
				continue;
			}
			int[] range = UNDERGROUND.get(s.tag());
			if (range != null) {
				BlockPos inside = findInside(overworld, found, range[0], range[1]);
				if (inside != null) {
					return new Placement(Type.STRUCTURE, "overworld", inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, randomYaw(),
							"Struktur: " + s.name() + " (innen)", new ArrayList<>());
				}
				TruthOrLieMod.LOGGER.info("Kein Platz im Inneren von {} gefunden, nutze Oberfläche", s.tag());
			}
			BlockPos top = surface(overworld, found.getX(), found.getZ());
			List<String> cmds = new ArrayList<>();
			if (isWet(overworld, top)) {
				cmds.add(exec("overworld") + "summon minecraft:oak_boat " + fmt(top.getX() + 0.5) + " " + top.getY() + " " + fmt(top.getZ() + 0.5) + " {Tags:[\"tol_mob\"]}");
			}
			return new Placement(Type.STRUCTURE, "overworld", top.getX() + 0.5, top.getY(), top.getZ() + 0.5, randomYaw(),
					"Struktur: " + s.name(), cmds);
		}
		return null;
	}

	// ------------------------------------------------------------------ Mob-Begegnung

	private record MobDef(String name, String id, int count, int minDifficulty, int weight) {
	}

	private static final List<MobDef> MOBS = List.of(
			new MobDef("Eine Kuh", "cow", 1, 0, 2),
			new MobDef("Ein Rudel Pandas", "panda", 3, 0, 2),
			new MobDef("Ein Dorfbewohner", "villager", 1, 0, 2),
			new MobDef("Ein Papagei", "parrot", 1, 0, 1),
			new MobDef("Ein Creeper", "creeper", 1, 1, 4),
			new MobDef("Zombie-Horde", "zombie", 5, 1, 4),
			new MobDef("Skelett-Trupp", "skeleton", 3, 1, 3),
			new MobDef("Eine Spinne", "spider", 1, 1, 2),
			new MobDef("Ein Enderman", "enderman", 1, 1, 3),
			new MobDef("Eine Hexe", "witch", 1, 1, 2),
			new MobDef("Ein Wolfsrudel", "wolf", 4, 1, 2),
			new MobDef("Ein Eisengolem", "iron_golem", 1, 1, 2),
			new MobDef("Plünderer-Trupp", "pillager", 4, 1, 2),
			new MobDef("Ein Verwüster", "ravager", 1, 2, 3),
			new MobDef("Ein Evoker", "evoker", 1, 2, 2),
			new MobDef("Ein Warden", "warden", 1, 2, 1));

	private static Placement planMob(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		List<MobDef> pool = new ArrayList<>();
		for (MobDef m : MOBS) {
			if (m.minDifficulty() <= cfg.mobDifficulty) {
				for (int i = 0; i < m.weight(); i++) {
					pool.add(m);
				}
			}
		}
		if (pool.isEmpty()) {
			return null;
		}
		ThreadLocalRandom r = ThreadLocalRandom.current();
		MobDef mob = pool.get(r.nextInt(pool.size()));
		Placement base = planWild(overworld, cfg, cx, cz);
		float yaw = randomYaw();
		// Blickrichtung: yaw 0 = +Z, yaw 90 = -X
		double fx = -Math.sin(Math.toRadians(yaw));
		double fz = Math.cos(Math.toRadians(yaw));
		List<String> cmds = new ArrayList<>();
		for (int i = 0; i < mob.count(); i++) {
			double mx = base.x() + fx * 4 + (mob.count() > 1 ? r.nextDouble(-2, 2) : 0);
			double mz = base.z() + fz * 4 + (mob.count() > 1 ? r.nextDouble(-2, 2) : 0);
			BlockPos ground = surface(overworld, (int) Math.floor(mx), (int) Math.floor(mz));
			cmds.add(exec("overworld") + "summon minecraft:" + mob.id() + " " + fmt(mx) + " " + ground.getY() + " " + fmt(mz) + " {Tags:[\"tol_mob\"],NoAI:1b,PersistenceRequired:1b}");
		}
		return new Placement(Type.MOB, "overworld", base.x(), base.y(), base.z(), yaw, "Mob-Begegnung: " + mob.name(), cmds);
	}

	// ------------------------------------------------------------------ Nether

	private static Placement planNether(MinecraftServer server, TolConfig cfg, double cx, double cz) {
		ServerLevel nether = server.getLevel(Level.NETHER);
		if (nether == null) {
			return null;
		}
		for (int attempt = 0; attempt < 12; attempt++) {
			BlockPos p = ringPoint(cfg, cx / 8.0, cz / 8.0, 1.0 / 8.0);
			for (int y = 100; y > 34; y--) {
				BlockPos pos = new BlockPos(p.getX(), y, p.getZ());
				if (isFree(nether, pos) && isFree(nether, pos.above()) && isGround(nether, pos.below())) {
					return new Placement(Type.NETHER, "the_nether", pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, randomYaw(),
							"Im Nether", new ArrayList<>());
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Höhle

	private static Placement planCave(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		for (int attempt = 0; attempt < 4; attempt++) {
			BlockPos base = ringPoint(cfg, cx, cz, 1);
			for (int dx = -6; dx <= 6; dx += 2) {
				for (int dz = -6; dz <= 6; dz += 2) {
					int x = base.getX() + dx;
					int z = base.getZ() + dz;
					int surfaceY = surface(overworld, x, z).getY();
					for (int y = Math.min(surfaceY - 10, 45); y > -55; y--) {
						BlockPos pos = new BlockPos(x, y, z);
						if (isFree(overworld, pos) && isFree(overworld, pos.above()) && isGround(overworld, pos.below())) {
							return new Placement(Type.CAVE, "overworld", x + 0.5, y, z + 0.5, randomYaw(), "Tief in einer Höhle", new ArrayList<>());
						}
					}
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Ozean

	private static Placement planOcean(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		for (int attempt = 0; attempt < 30; attempt++) {
			BlockPos p = ringPoint(cfg, cx, cz, 1);
			BlockPos top = surface(overworld, p.getX(), p.getZ());
			BlockPos below = top.below();
			if (!overworld.getFluidState(below).isEmpty() && !overworld.getFluidState(below.below(3)).isEmpty()) {
				List<String> cmds = new ArrayList<>();
				cmds.add(exec("overworld") + "summon minecraft:oak_boat " + fmt(top.getX() + 0.5) + " " + top.getY() + " " + fmt(top.getZ() + 0.5) + " {Tags:[\"tol_mob\"]}");
				return new Placement(Type.OCEAN, "overworld", top.getX() + 0.5, top.getY(), top.getZ() + 0.5, randomYaw(),
						"Mitten im Ozean", cmds);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Nether- und End-Strukturen
	// Die Tags truthorlie:nether_fortress / bastion / end_city liegen in den Mod-Daten (data/truthorlie/tags/worldgen/structure).

	private static TagKey<Structure> modTag(String path) {
		return TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath("truthorlie", path));
	}

	private record DimStruct(String tag, String name, int yMax, int yMin) {
	}

	private static Placement planNetherStructure(MinecraftServer server, TolConfig cfg, double cx, double cz) {
		ServerLevel nether = server.getLevel(Level.NETHER);
		if (nether == null) {
			return null;
		}
		List<DimStruct> list = new ArrayList<>(List.of(
				new DimStruct("nether_fortress", "Netherfestung", 110, 30),
				new DimStruct("bastion", "Bastion-Ruine", 110, 30)));
		Collections.shuffle(list);
		for (DimStruct st : list) {
			BlockPos origin = ringPoint(cfg, cx / 8.0, cz / 8.0, 1.0 / 8.0);
			BlockPos found = nether.findNearestMapStructure(modTag(st.tag()), origin, 100, false);
			if (found == null) {
				TruthOrLieMod.LOGGER.info("Keine Nether-Struktur gefunden: {}", st.tag());
				continue;
			}
			BlockPos inside = findInside(nether, found, st.yMax(), st.yMin());
			if (inside != null) {
				return new Placement(Type.NETHER_STRUCTURE, "the_nether", inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, randomYaw(),
						"Struktur: " + st.name() + " (innen)", new ArrayList<>());
			}
		}
		return null;
	}

	private static BlockPos endRingPoint() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		double angle = r.nextDouble() * Math.PI * 2;
		double dist = 1300 + r.nextDouble() * 2200;
		return new BlockPos((int) (Math.cos(angle) * dist), 0, (int) (Math.sin(angle) * dist));
	}

	private static Placement planEndStructure(MinecraftServer server) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) {
			return null;
		}
		for (int attempt = 0; attempt < 3; attempt++) {
			BlockPos found = end.findNearestMapStructure(modTag("end_city"), endRingPoint(), 100, false);
			if (found == null) {
				TruthOrLieMod.LOGGER.info("Keine End-Stadt gefunden");
				continue;
			}
			BlockPos inside = findInside(end, found, 110, 35);
			if (inside == null) {
				BlockPos top = surface(end, found.getX(), found.getZ());
				if (top.getY() <= 0) {
					continue;
				}
				inside = top;
			}
			return new Placement(Type.END_STRUCTURE, "the_end", inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, randomYaw(),
					"Struktur: End-Stadt", new ArrayList<>());
		}
		return null;
	}

	private static Placement planEnd(MinecraftServer server) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) {
			return null;
		}
		for (int attempt = 0; attempt < 30; attempt++) {
			BlockPos p = endRingPoint();
			BlockPos top = surface(end, p.getX(), p.getZ());
			if (top.getY() > 0 && isGround(end, top.below())) {
				return new Placement(Type.END, "the_end", top.getX() + 0.5, top.getY(), top.getZ() + 0.5, randomYaw(),
						"Auf einer äußeren End-Insel", new ArrayList<>());
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Nether-/End-Mobs (eingefroren)

	private record DimMob(String name, String id, int count) {
	}

	private static final List<DimMob> NETHER_MOBS = List.of(
			new DimMob("Eine Blaze", "blaze", 1),
			new DimMob("Wither-Skelette", "wither_skeleton", 3),
			new DimMob("Ein Piglin-Brute", "piglin_brute", 1),
			new DimMob("Ein Hoglin", "hoglin", 1),
			new DimMob("Zombifizierte Piglins", "zombified_piglin", 4),
			new DimMob("Magmawürfel", "magma_cube", 2),
			new DimMob("Piglins", "piglin", 3),
			new DimMob("Ein Strider", "strider", 1));

	private static final List<DimMob> END_MOBS = List.of(
			new DimMob("Endermen", "enderman", 4),
			new DimMob("Endermiten", "endermite", 3),
			new DimMob("Ein Shulker", "shulker", 2));

	/** Zufälliger freier Platz (2 Blöcke hoch) in der Nähe, damit eingefrorene Mobs nicht in Wänden stecken. */
	private static BlockPos freeNear(ServerLevel level, double bx, double by, double bz) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (int i = 0; i < 16; i++) {
			BlockPos p = BlockPos.containing(bx + r.nextInt(-3, 4), by, bz + r.nextInt(-3, 4));
			if (isFree(level, p) && isFree(level, p.above())) {
				return p;
			}
		}
		return null;
	}

	private static Placement planDimMob(MinecraftServer server, TolConfig cfg, double cx, double cz, boolean end) {
		ServerLevel level = server.getLevel(end ? Level.END : Level.NETHER);
		if (level == null) {
			return null;
		}
		Placement base = end ? planEnd(server) : planNether(server, cfg, cx, cz);
		if (base == null) {
			return null;
		}
		List<DimMob> list = end ? END_MOBS : NETHER_MOBS;
		DimMob mob = list.get(ThreadLocalRandom.current().nextInt(list.size()));
		String dim = end ? "the_end" : "the_nether";
		List<String> cmds = new ArrayList<>();
		for (int i = 0; i < mob.count(); i++) {
			BlockPos p = freeNear(level, base.x(), base.y(), base.z());
			if (p != null && (Math.abs(p.getX() + 0.5 - base.x()) > 0.9 || Math.abs(p.getZ() + 0.5 - base.z()) > 0.9)) {
				cmds.add(exec(dim) + "summon minecraft:" + mob.id() + " " + fmt(p.getX() + 0.5) + " " + p.getY() + " " + fmt(p.getZ() + 0.5)
						+ " {Tags:[\"tol_mob\"],NoAI:1b,PersistenceRequired:1b}");
			}
		}
		return new Placement(end ? Type.END_MOB : Type.NETHER_MOB, dim, base.x(), base.y(), base.z(), base.yaw(),
				(end ? "End-Mob: " : "Nether-Mob: ") + mob.name(), cmds);
	}
}
