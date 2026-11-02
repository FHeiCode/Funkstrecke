# Funkstrecke

Android-App (Kotlin, Jetpack Compose), die die Mobilfunk-Basisstationen in der Umgebung scannt, klassifiziert und ihren Standort aus deinen Messungen schätzt. Alle Daten bleiben auf dem Gerät.

## Funktionen

**Live** – alle Zellen, die das Handy gerade hört, alle 3 Sekunden aktualisiert:
- Technik (2G/3G/4G/5G), Betreiber, MCC, MNC, TAC/LAC
- Cell-ID (ECI/NCI/CID), bei 4G zerlegt in eNodeB-ID und Sektor
- PCI/PSC/BSIC, (E/NR-)ARFCN, Band und Downlink-Frequenz
- Pegel (RSRP/SS-RSRP/RSCP/RSSI), Qualität (RSRQ/Ec/No), SINR
- Timing Advance mit Entfernung (4G: 78 m pro Schritt, 2G: 554 m)
- Dual-SIM: beide SIMs werden gescannt, wenn „Telefon“ erlaubt ist

**Aufzeichnung** – Start im Live-Tab. Ein Hintergrunddienst nimmt bei GPS-Positionen einen Scan auf, auch bei ausgeschaltetem Bildschirm. Eine Benachrichtigung zeigt den Fortschritt und hat einen Stopp-Knopf.

**Einstellungen** (Zahnrad im Live-Tab) – Energiemodus *Genau* (alle 3 s), *Ausgewogen* (alle 10 s ab 15 m Bewegung, Standard) oder *Sparsam* (alle 30 s ab 50 m, Modem-Zwischenspeicher), oder einzeln: Scan-Intervall, Mindestabstand, Live-Takt, frische Messung an/aus. GPS wird nur so oft abgefragt, wie gescannt wird.

**Heimatbasis** (Einstellungen → Heimatbasis) – Ort per langem Druck auf die Karte oder aktuelle Position, Radius 100 m–2 km. Verlässt du den Kreis, startet die Aufzeichnung automatisch, bei Rückkehr stoppt sie (abschaltbar). Nutzt Geofencing der Google-Play-Dienste und braucht „Standort: Immer zulassen“. Android meldet das Verlassen meist mit 1–3 Minuten Verzögerung. Nach einem Neustart wird die Heimatbasis automatisch neu angemeldet.

**Stationen** – alle jemals gehörten Zellen, gruppiert nach Basisstation (4G: eNodeB mit seinen Sektoren), mit Filter nach Betreiber und Technik und Suche nach eNB, Cell-ID, PCI, TAC.

**PCI-Gruppen** – PCI = 3 · SSS-Gruppe + PSS-Index. Weil Sektoren eines Standorts meist dieselbe SSS-Gruppe nutzen, ordnet die App Nachbarzellen ohne Cell-ID einer bekannten Station mit gleicher Gruppe zu („vermutlich zugehörig“), geprüft über den Abstand der Messorte (≤ 3 km, „wahrscheinlich“ ≤ 1,5 km). Lose Zellen gleicher Gruppe an nahen Orten werden zu „vermuteten Stationen“ gebündelt. Im Live-Tab steht bei Nachbarzellen, wenn sie dieselbe Gruppe wie die verbundene Zelle haben.

**Standortschätzung** – pro Station aus allen GPS-markierten Messungen:
- Pegelmodell mit eigenem Sendepegel je Sektor, gelöst per Levenberg-Marquardt
- Timing-Advance-Werte als zusätzliche Abstandsmessungen
- Unsicherheitsradius (95 %) aus der Kovarianz; er schrumpft, je mehr Richtungen die Messungen abdecken
- Liegen alle Messungen auf einer Linie (eine Straße), ist die Lage spiegelbildlich mehrdeutig. Die App sagt das und hält den Kreis groß, bis du aus einer anderen Richtung misst.
- Detailansicht mit Karte: Messorte (grün = stark, rot = schwach), geschätzter Mast und Kreis

In einer Simulation (3 Sektoren, Messrauschen 5 dB) lag der Fehler nach zwei Straßen bei etwa 20 m, mit Timing Advance bei etwa 5 m. In echter Umgebung verfälschen Gebäude und Antennendiagramme die Pegel, rechne eher mit 50–200 m. Die Schätzung ist ein Suchgebiet.

**Karte** – alle verorteten Stationen in Betreiberfarbe mit Unsicherheitskreis (OpenStreetMap).

**Export** – Zellen und vollständiges Messprotokoll als CSV (Semikolon, Dezimalkomma, öffnet direkt in Excel).

## Grenzen von Android

- Das Handy meldet nur Zellen des Netzes seiner SIM(s), andere Betreiber nur mit einer zweiten SIM.
- Nachbarzellen melden oft nur PCI und ARFCN, keine Cell-ID. MCC/MNC übernimmt die App dann von der verbundenen Zelle (mit * markiert).
- Timing Advance gibt es nur für die verbundene Zelle.
- Bei 5G NSA melden viele Geräte keine NR-Zellidentität.
- „Genauer Standort“ ist Pflicht, sonst liefert Android keine Zelldaten.

## Bauen auf GitHub

1. Inhalt dieses Ordners in ein GitHub-Repository pushen.
2. Der Workflow `.github/workflows/android.yml` baut bei jedem Push automatisch.
3. Unter **Actions → letzter Lauf → Artifacts** liegt `funkstrecke-apk` (ZIP mit der APK).
   Für einen Download unter **Releases**: auf GitHub **Releases → Draft a new release**, Tag z. B. `v2.0` anlegen, **Publish release**. Der Workflow baut dann und hängt `funkstrecke-v2.0.apk` nach 3–5 Minuten an das Release.
4. APK aufs Handy kopieren und installieren. Play Protect warnt bei Apps außerhalb des Play Stores, besonders wenn sie Standort und Telefonstatus nutzen: „Weitere Details → Trotzdem installieren“.

Das Projekt enthält bewusst keine `gradlew`/`gradle-wrapper.jar` (ausführbare Dateien in ZIPs lösen manche Virenscanner aus). Für Android Studio einmal `gradle wrapper --gradle-version 8.11.1` im Projektordner ausführen oder das Projekt mit einer installierten Gradle-Version öffnen.

## Projektaufbau

```
app/src/main/java/de/funkstrecke/
├─ MainActivity.kt        Navigation (Live, Stationen, Karte, Detail)
├─ radio/                 Zell-Scanner, Band-/Frequenztabellen, Betreiber (MCC/MNC)
├─ data/                  Room-Datenbank (Zellen, Messungen, Schätzungen), Recorder, Gruppierung
├─ locate/                Standortschätzung (Multilateration)
├─ service/               Hintergrund-Aufzeichnung mit GPS
├─ util/                  CSV-Export, Formatierung, Berechtigungen
└─ ui/                    Oberfläche
```
