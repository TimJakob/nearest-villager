package dev.nearestvillager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serverseitige Mod: Wer eine Workstation platziert, bestimmt nicht den Zufall, sondern die Naehe.
 * Keine Mixins - nur Fabric-API-Events und oeffentliche Vanilla-Methoden.
 */
public class NearestVillagerMod implements ModInitializer {
	public static final String MOD_ID = "nearestvillager";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		Config.load();

		// Erkennt das Platzieren einer Workstation (und Shift+Rechtsklick fuers Highlighten).
		UseBlockCallback.EVENT.register(PlacementListener::onUseBlock);

		// Arbeitet die Reservierungen ab.
		ServerTickEvents.END_SERVER_TICK.register(JobArbiter::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> JobArbiter.clear());

		LOG.info("Nearest Villager geladen (Radius {}, Verzoegerung {} Ticks).",
				Config.get().searchRadius, Config.get().claimDelayTicks);
	}
}
