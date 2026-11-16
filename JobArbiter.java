package dev.nearestvillager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kernlogik.
 *
 * Warum Vanilla "falsche" Villager waehlt: Jeder arbeitslose Villager fragt alle 20-40 Ticks (zufaellig
 * versetzt) nach freien Workstations im Umkreis von 48 Bloecken und nimmt sich die erste freie. Wer zufaellig
 * zuerst dran ist, gewinnt - egal wie weit er weg ist.
 *
 * Loesung:
 *  1. Beim Platzieren wird das "Ticket" der Workstation sofort selbst genommen (PoiManager.take). Solange es
 *     belegt ist, findet kein Villager sie mehr (Vanilla sucht nur Stationen mit freiem Ticket).
 *  2. Nach der kurzen Verzoegerung wird der naheste geeignete, erreichbare Villager bestimmt.
 *  3. Dem Gewinner wird POTENTIAL_JOB_SITE gesetzt - exakt der Zustand, den Vanillas AcquirePoi herstellt.
 *     Danach laufen Hinlaufen, Beruf-Zuweisung und Ticket-Freigabe (Tod, Abbau, ...) ganz normal ueber Vanilla.
 *  4. Findet sich keiner, wird das Ticket zurueckgegeben und Vanilla uebernimmt wie gewohnt.
 */
public final class JobArbiter {
	/** So lange (Ticks) warten wir darauf, dass Vanilla die Workstation als POI registriert. */
	private static final int WAIT_FOR_POI_TICKS = 40;
	/** Innerhalb dieses Radius (quadriert) haben Villager mit passendem Beruf Vorrang. */
	private static final double PROFESSION_PRIORITY_RADIUS_SQR = 16.0 * 16.0;

	private static final Map<GlobalPos, Pending> PENDING = new HashMap<>();

	private JobArbiter() {
	}

	private enum Reserve {
		RESERVED, MISSING, GIVE_UP
	}

	private static final class Pending {
		final ServerLevel level;
		final BlockPos pos;
		final long createdTick;
		boolean reserved;
		long decideTick;

		Pending(ServerLevel level, BlockPos pos, long createdTick) {
			this.level = level;
			this.pos = pos;
			this.createdTick = createdTick;
		}
	}

	private record Candidate(Villager villager, double distSqr, boolean priority) {
	}

	// ------------------------------------------------------------------------------------------------------------

	public static boolean isJobSite(BlockState state) {
		return PoiTypes.forState(state).filter(type -> type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE)).isPresent();
	}

	public static void clear() {
		PENDING.clear();
	}

	/** Wird aufgerufen, kurz bevor Vanilla die Workstation platziert. */
	public static void onWorkstationPlaced(ServerLevel level, BlockPos pos) {
		MinecraftServer server = level.getServer();
		BlockPos immutable = pos.immutable();
		GlobalPos key = GlobalPos.of(level.dimension(), immutable);

		Pending pending = new Pending(level, immutable, server.getTickCount());
		PENDING.put(key, pending);

		// Vanilla registriert den POI ueber eine Aufgabe, die waehrend des Platzierens in die Server-Warteschlange
		// gestellt wird. Unsere Aufgabe stellt sich beim zweiten execute() dahinter an: Sie laeuft also nach der
		// POI-Registrierung, aber noch vor dem naechsten Tick - kein Villager-AI-Tick kommt uns dazwischen.
		// Klappt das aus irgendeinem Grund nicht, versucht es tick() in den naechsten Ticks erneut.
		server.execute(() -> server.execute(() -> {
			if (PENDING.get(key) == pending && !pending.reserved) {
				tryReserve(pending, server.getTickCount());
			}
		}));
	}

	public static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}

		long now = server.getTickCount();
		Iterator<Pending> iterator = PENDING.values().iterator();

		while (iterator.hasNext()) {
			Pending pending = iterator.next();

			if (pending.level.getServer() != server) {
				iterator.remove();
				continue;
			}

			if (!pending.reserved) {
				Reserve result = tryReserve(pending, now);
				if (result == Reserve.GIVE_UP) {
					iterator.remove();
					continue;
				}
				if (result == Reserve.MISSING) {
					if (now - pending.createdTick > WAIT_FOR_POI_TICKS) {
						iterator.remove();
					}
					continue;
				}
			}

			if (now >= pending.decideTick) {
				try {
					decide(pending);
				} catch (RuntimeException e) {
					NearestVillagerMod.LOG.error("Fehler beim Zuweisen der Workstation {}", pending.pos, e);
					pending.level.getPoiManager().release(pending.pos);
				}
				iterator.remove();
			}
		}
	}

	// ------------------------------------------------------------------------------------------------------------

	private static Reserve tryReserve(Pending pending, long now) {
		PoiManager poi = pending.level.getPoiManager();

		Optional<Holder<PoiType>> type = poi.getType(pending.pos);
		if (type.isEmpty()) {
			return Reserve.MISSING; // noch nicht registriert (oder Platzieren fehlgeschlagen)
		}
		if (!type.get().is(PoiTypeTags.ACQUIRABLE_JOB_SITE)) {
			return Reserve.GIVE_UP;
		}

		// Ticket nehmen: ab jetzt "belegt" - kein anderer Villager kann sie sich holen.
		Optional<BlockPos> taken = poi.take(t -> true, (t, p) -> p.equals(pending.pos), pending.pos, 1);
		if (taken.isEmpty()) {
			return Reserve.GIVE_UP; // war schon belegt, nicht unsere Sache
		}

		pending.reserved = true;
		pending.decideTick = now + Config.get().claimDelayTicks;
		return Reserve.RESERVED;
	}

	private static void decide(Pending pending) {
		ServerLevel level = pending.level;
		PoiManager poi = level.getPoiManager();

		Optional<Holder<PoiType>> type = poi.getType(pending.pos);
		if (type.isEmpty()) {
			return; // Workstation inzwischen abgebaut - der POI-Eintrag samt unserem Ticket ist weg
		}

		Villager winner = pickWinner(level, pending.pos, type.get());
		if (winner == null) {
			poi.release(pending.pos);
			debug("Kein geeigneter Villager fuer {} - Ticket freigegeben, Vanilla uebernimmt.", pending.pos.toShortString());
			return;
		}

		winner.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(level.dimension(), pending.pos));
		debug("Workstation {} -> Villager {} ({} Bloecke entfernt)", pending.pos.toShortString(), winner.getUUID(),
				String.format("%.1f", Math.sqrt(winner.distanceToSqr(Vec3.atCenterOf(pending.pos)))));
	}

	private static Villager pickWinner(ServerLevel level, BlockPos pos, Holder<PoiType> type) {
		Config cfg = Config.get();
		Vec3 center = Vec3.atCenterOf(pos);
		double maxDistSqr = (double) cfg.searchRadius * cfg.searchRadius;
		AABB area = new AABB(pos).inflate(cfg.searchRadius);

		List<Candidate> candidates = new ArrayList<>();
		for (Villager villager : level.getEntitiesOfClass(Villager.class, area)) {
			if (!isEligible(villager, type)) {
				continue;
			}
			double distSqr = villager.distanceToSqr(center);
			if (distSqr > maxDistSqr) {
				continue;
			}
			boolean priority = cfg.preferMatchingProfession
					&& hasProfession(villager)
					&& distSqr <= PROFESSION_PRIORITY_RADIUS_SQR;
			candidates.add(new Candidate(villager, distSqr, priority));
		}

		// Erst die Villager mit Vorrang (passender Beruf), dann nach Entfernung.
		candidates.sort(Comparator.comparing((Candidate c) -> !c.priority()).thenComparingDouble(Candidate::distSqr));

		int checks = 0;
		for (Candidate candidate : candidates) {
			if (!cfg.requirePath) {
				return candidate.villager();
			}
			if (checks++ >= cfg.maxPathChecks) {
				break;
			}
			if (canReach(candidate.villager(), pos)) {
				return candidate.villager();
			}
		}
		return null;
	}

	/** Erwachsen, lebendig, kein (potenzieller) Arbeitsplatz, und die Workstation passt zu seinem (Nicht-)Beruf. */
	private static boolean isEligible(Villager villager, Holder<PoiType> type) {
		if (!villager.isAlive() || villager.isBaby()) {
			return false;
		}
		Brain<Villager> brain = villager.getBrain();
		if (brain.hasMemoryValue(MemoryModuleType.JOB_SITE) || brain.hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE)) {
			return false;
		}
		// Arbeitslos: alle Job-Sites erlaubt. Hat Beruf: nur die eigene. Nitwit: keine.
		return villager.getVillagerData().profession().value().acquirableJobSite().test(type);
	}

	private static boolean hasProfession(Villager villager) {
		return !villager.getVillagerData().profession().is(VillagerProfession.NONE);
	}

	private static boolean canReach(Villager villager, BlockPos pos) {
		Path path = villager.getNavigation().createPath(pos, 1);
		return path != null && path.canReach();
	}

	// ------------------------------------------------------------------------------------------------------------
	// Shift + Rechtsklick: Besitzer anzeigen

	public static boolean highlight(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (!isJobSite(level.getBlockState(pos))) {
			return false;
		}

		Config cfg = Config.get();
		GlobalPos target = GlobalPos.of(level.dimension(), pos);
		int owners = 0;

		for (Villager villager : level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(64))) {
			if (isOwner(villager, target)) {
				villager.addEffect(new MobEffectInstance(MobEffects.GLOWING, cfg.highlightDurationTicks, 0, false, false));
				owners++;
			}
		}

		player.displayClientMessage(Component.literal(owners > 0
				? "Diese Workstation gehoert " + (owners == 1 ? "einem Villager (leuchtet)." : owners + " Villagern (leuchten).")
				: "Diese Workstation gehoert keinem Villager."), true);
		return true;
	}

	private static boolean isOwner(Villager villager, GlobalPos target) {
		Brain<Villager> brain = villager.getBrain();
		return brain.getMemory(MemoryModuleType.JOB_SITE).filter(target::equals).isPresent()
				|| brain.getMemory(MemoryModuleType.POTENTIAL_JOB_SITE).filter(target::equals).isPresent();
	}

	private static void debug(String message, Object... args) {
		if (Config.get().debugLog) {
			NearestVillagerMod.LOG.info(message, args);
		}
	}
}
