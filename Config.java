package dev.nearestvillager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Einfache JSON-Konfiguration: config/nearestvillager.json (wird beim Start neu geschrieben, neue Optionen erscheinen automatisch). */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config instance = new Config();

	/** Umkreis (Bloecke) um die platzierte Workstation, in dem Villager beruecksichtigt werden. Vanilla sucht bis 48. */
	public int searchRadius = 32;

	/**
	 * Kurze Verzoegerung (Ticks, 20 = 1 s) zwischen Platzieren und Entscheidung.
	 * Die Workstation ist ab dem Platzieren sofort fuer alle anderen gesperrt - die Verzoegerung
	 * gibt nur Zeit, damit z.B. mehrere schnell hintereinander gesetzte Stationen sauber zugeordnet werden.
	 * 0 = sofort.
	 */
	public int claimDelayTicks = 10;

	/** Nur Villager, die die Workstation wirklich erreichen koennen (wie Vanilla; wichtig fuer Zellen in Trading Halls). */
	public boolean requirePath = true;

	/** Wie viele der naechsten Villager maximal auf Erreichbarkeit geprueft werden. */
	public int maxPathChecks = 6;

	/**
	 * Villager, die schon den passenden Beruf haben, aber ihre Workstation verloren haben
	 * (z.B. Bibliothekar mit Trades und neuem Lesepult), haben innerhalb von 16 Bloecken Vorrang.
	 */
	public boolean preferMatchingProfession = true;

	/** Shift + Rechtsklick (leere Hand) auf eine Workstation laesst den Besitzer leuchten. */
	public boolean highlightEnabled = true;
	public int highlightDurationTicks = 60;

	public boolean debugLog = false;

	public static Config get() {
		return instance;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("nearestvillager.json");
		Config loaded = null;

		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				loaded = GSON.fromJson(reader, Config.class);
			} catch (Exception e) {
				NearestVillagerMod.LOG.error("Konnte {} nicht lesen, verwende Standardwerte.", file, e);
			}
		}

		if (loaded == null) {
			loaded = new Config();
		}
		loaded.sanitize();
		instance = loaded;

		try (Writer writer = Files.newBufferedWriter(file)) {
			GSON.toJson(loaded, writer);
		} catch (IOException e) {
			NearestVillagerMod.LOG.warn("Konnte {} nicht schreiben.", file, e);
		}
	}

	private void sanitize() {
		searchRadius = Math.max(4, Math.min(64, searchRadius));
		claimDelayTicks = Math.max(0, Math.min(200, claimDelayTicks));
		maxPathChecks = Math.max(1, Math.min(32, maxPathChecks));
		highlightDurationTicks = Math.max(1, Math.min(1200, highlightDurationTicks));
	}
}
