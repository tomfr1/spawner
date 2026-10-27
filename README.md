# HugoSMP AutoSpawner (Fabric 1.21.11, Client-Mod)

## Wichtige Hinweise vorab
- **Serverregeln pruefen:** Automatisierung/Makros sind auf vielen Servern verboten. Kontrolliere die Regeln von hugosmp.net, bevor du die Mod benutzt. Nutzung auf eigene Verantwortung.
- **Nicht kompiliert/getestet:** Der Code wurde ohne Minecraft-Umgebung geschrieben. Methodennamen (Yarn-Mappings 1.21.11) koennen minimal abweichen; Compiler-Fehler lassen sich meist mit einer Umbenennung beheben.
- **Menue-Layouts sind serverspezifisch.** Slot-Nummern fuer Naechste-Seite-/Bestaetigen-/Sell-Buttons kennt die Mod nicht. Mit `/hugosmp debug` ermittelst du sie (siehe unten).

## Bauen
1. JDK 21 installieren.
2. Auf https://fabricmc.net/develop/ Minecraft 1.21.11 waehlen und die Werte `yarn_mappings`, `loader_version`, `fabric_version` (und ggf. die Loom-Version) in `gradle.properties` / `build.gradle` uebernehmen.
3. Gradle-Wrapper fehlt in diesem Paket. Einfachste Loesung: Fabric-Template von der Seite oben generieren und den Ordner `src/` sowie `build.gradle`/`gradle.properties` durch die aus diesem Paket ersetzen. Alternativ: `gradle wrapper` mit installiertem Gradle.
4. `./gradlew build` -> die Mod liegt in `build/libs/hugosmp-autospawner-1.0.0.jar`.

## Installieren
1. Fabric Loader fuer 1.21.11 installieren, **Fabric API** in `mods/` legen.
2. Die JAR in `.minecraft/mods/` legen und starten.
3. Auf hugosmp.net joinen. Konfiguration: `/hugosmp gui` oder `config/hugosmp-autospawner.json`.

## Befehle (clientseitig)
`/hugosmp gui` - Einstellungen | `start` - Lauf sofort | `stop` - Abbruch | `status` | `reload` | `debug`

## Funktionsweise
- **Spawner-Lauf** (alle `intervalHours`, Standard 5,5 h): Steh so, dass alle Spawner in Reichweite (4,5 Bloecke) liegen. Die Mod oeffnet jeden Spawner, nimmt Items aus deiner Liste heraus und verarbeitet sie:
  - `SELL`: `/sell`-Menue oeffnen, Items per Shift-Klick hineinlegen, schliessen (optional `sellConfirmSlot`). Alternativ `spawnerMenuSellButtonSlot` setzen, um den Verkaufs-Button des Spawner-Menues zu klicken (dann werden nur ORDER-Items entnommen).
  - `ORDER`: `/order <Name>` senden, in der Liste die Order mit dem passenden Item waehlen (**HIGHEST** = hoechster `$`-Betrag in der Lore, **SPECIFIC** = Name/Lore enthaelt `specificOrderText`), dann im Abgabe-Menue erst "Ganzes Inventar liefern", danach "Bestaetigen" klicken.
- **Mehrere Runden:** Wird das Inventar waehrend des Entnehmens voll ("Kein Platz im Inventar"), verarbeitet der Bot zuerst das Inventar (/sell bzw. Orders) und startet dann automatisch die naechste Runde, bis die Spawner leer sind (max. `maxRounds`, Standard 100). Macht eine Runde keine Fortschritte, stoppt der Lauf. Sind nach einer Order-Lieferung noch Items uebrig (Order voll), probiert er im Modus HIGHEST bis zu `orderMaxRepeats` Mal die naechste Order.
- **Order-Spielername:** global (`orderPlayer`) oder pro Item in der JSON (`items[].orderPlayer`).
- **Trusted-Schutz:** Ist ein nicht-trusted Spieler innerhalb `guardRadius` (Standard 30), bricht die Mod den Lauf ab und **sneakt dauerhaft**. Sie baut alle Spawner in Reichweite mit der Silk-Touch-Spitzhacke ab (Hotbar, oder fester Slot `silkPickaxeHotbarSlot`) und erneuert den Sneak alle ~9 s (`sneakWindowSec`, Standard 10). Danach sammelt sie die Spawner-Drops ein, bis alle im Inventar sind, sendet `/spawn`, wartet auf die Teleportation (Ortswechsel > 10 Bloecke; bei Ausbleiben bis zu 3x `/spawn`) und loggt am Spawn aus.
- **Sicherheit/Anti-Spam:** Es werden nur `/sell`, `/order <Name>` und `/spawn` gesendet; Namen werden validiert; Mindestabstand `commandCooldownMs` und max. 20 Befehle/Minute (nur `/spawn` im Notfall ist ausgenommen). Fehler brechen den Lauf sauber ab und werden ins Log geschrieben.

## Menue-Slots ermitteln
1. `/hugosmp debug` eingeben und innerhalb von 5 Sekunden das gewuenschte Menue oeffnen (Spawner, `/sell`, `/order`).
2. In `logs/latest.log` stehen alle Slots mit Nummer, Item, Name/Lore und erkanntem Preis.
3. Trage die Nummern in die Config ein und `/hugosmp reload`.

## Config (Auszug)
| Feld | Bedeutung |
|---|---|
| `intervalHours` | Intervall, Standard 5.5 |
| `items[]` | `item`, `mode` (`SELL`/`ORDER`), optional `orderPlayer` |
| `orderMode` | `HIGHEST` oder `SPECIFIC` |
| `trustedPlayers`, `guardRadius` | Trusted-Liste, Radius (30) |
| `requireSilkTouch`, `silkPickaxeHotbarSlot` | Spitzhacken-Pruefung bzw. fester Hotbar-Slot (0-8, -1 = Auto) |
| `spawnerMenu*Slot`, `sellConfirmSlot`, `orderNextPageSlot` | Server-Menue-Slots, `-1` = aus |
| `orderDeliverAllText`, `orderConfirmText` | Namen der Buttons im Order-Menue ("Ganzes Inventar liefern", "Bestaetigen"); Ersatz-Slots: `orderDeliverAllButtonSlot` (6), `orderConfirmButtonSlot` (8) |
| `inventoryFullText` | Server-Meldung, bei der das Entnehmen aus dem Spawner stoppt ("Kein Platz im Inventar") |

## Bekannte Grenzen
- Spawner ausserhalb der Reichweite (4,5 Bloecke) werden nicht bearbeitet (kein Pathfinding).
- Das Einsammeln der Spawner-Drops laeuft per einfachem Geradeauslaufen; bei Hindernissen kann es scheitern.
- Hat der Server ein Anti-Cheat/Makro-Verbot, kann die Nutzung zu Sanktionen fuehren.
