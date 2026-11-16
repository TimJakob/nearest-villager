# Nearest Villager (Fabric, Minecraft 26.3)

Serverseitige Fabric-Mod: Der **naheste** Villager bekommt den Job, nicht der, dessen KI zufaellig zuerst dran ist.
Keine Mixins, kein Client-Mod noetig (Clients ohne Mod koennen normal joinen).

## So funktioniert es
1. Du platzierst eine Workstation -> ihr "Ticket" wird sofort gesperrt. Kein anderer Villager kann sie mehr greifen.
2. Nach `claimDelayTicks` (Standard 10 = 0,5 s) wird der naheste passende, erreichbare Villager bestimmt.
3. Er bekommt `POTENTIAL_JOB_SITE` gesetzt (derselbe Zustand wie bei Vanilla) und laeuft los; Beruf-Zuweisung,
   Freigabe bei Tod/Abbau etc. macht weiter Vanilla.
4. Findet sich keiner, wird das Ticket freigegeben und Vanilla verhaelt sich wie immer.

Zusaetzlich:
- Nur Erwachsene ohne Arbeitsplatz; Nitwits und Babys sind raus.
- Erreichbarkeit wird geprueft -> Villager in Nachbarzellen einer Trading Hall ohne Weg zur Station werden uebersprungen.
- Villager mit passendem Beruf, aber ohne Workstation (z.B. Bibliothekar mit Trades), haben innerhalb 16 Bloecken Vorrang.
- Shift + Rechtsklick (beide Haende leer) auf eine Workstation laesst den Besitzer leuchten.

## Bauen
Voraussetzung: JDK 25.
1. Wrapper-Dateien (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) aus dem Fabric Example Mod (Branch fuer 26.3) hierher kopieren
   oder `gradle wrapper` ausfuehren.
2. Versionen in `gradle.properties` mit https://fabricmc.net/develop abgleichen (Loader, Loom, Fabric API).
3. `./gradlew build` -> `build/libs/nearest-villager-fabric-1.0.0.jar` in `mods/` legen (zusammen mit Fabric API).

## Konfiguration (`config/nearestvillager.json`)
| Option | Standard | Bedeutung |
|---|---|---|
| searchRadius | 32 | Umkreis um die Workstation (4-64) |
| claimDelayTicks | 10 | Verzoegerung bis zur Entscheidung, 0 = sofort |
| requirePath | true | nur Villager mit Weg zur Station |
| maxPathChecks | 6 | wie viele der naechsten Villager auf Weg geprueft werden |
| preferMatchingProfession | true | Villager mit passendem Beruf bevorzugen |
| highlightEnabled | true | Shift-Rechtsklick-Highlight |
| highlightDurationTicks | 60 | Leuchtdauer |
| debugLog | false | Zuweisungen ins Log schreiben |

## Grenzen
- Erkannt werden Workstations, die Spieler per Rechtsklick platzieren. `/setblock`, `/fill`, WorldEdit usw. laufen weiter ueber Vanilla.
- Bereits vorhandene, freie Workstations ohne Platzier-Ereignis werden von Vanilla vergeben (Zufall unter den Villagern in Reichweite).
