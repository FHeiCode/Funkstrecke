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

**Aufzeichnung** – Start im Live-Tab. Ein Hintergrunddienst scannt auch bei ausgeschaltetem Bildschirm; die Benachrichtigung zeigt den Zustand und hat einen Stopp-Knopf. Ortung über den Google-Standortdienst (schneller Fix, Rückfall auf WLAN/Mobilfunk), ohne Google-Dienste über GPS und Netzwerk parallel.
- *In Bewegung* (GPS ≤ 30 m): Scan mit Messpunkt nach Intervall und Mindestabstand. Bewegung zählt erst, wenn sie größer ist als die GPS-Ungenauigkeit, damit Zittern im Stand keine Messpunkte erzeugt.
- *Stillstand*: einmal pro Minute nur die Zellen auffrischen.
- *Ungenaues GPS* (Zug, Tunnel): Zellen werden im normalen Takt weiter erfasst. Messpunkte bis 100 m Ungenauigkeit zählen für Schätzung und Abdeckung, aber schwächer gewichtet: Der Positionsfehler wird in einen Pegelfehler umgerechnet, der mit dem Abstand zum Mast kleiner wird (80 m Fehler sind bei einem 3 km entfernten Mast fast bedeutungslos).
- *Hohe Geschwindigkeit*: Die Position wird mit Geschwindigkeit und Fahrtrichtung auf den Zeitstempel der Zellmessung hochgerechnet (zwischen Fix und Messung liegen oft 1–6 s, bei 200 km/h bis zu 300 m).

**Pause** – Im Live-Tab „Pausieren …“ (bzw. „Automatik pausieren …“ bei aktiver Heimatbasis): 15 min, 30 min, 1 h, 2 h oder bis zum Fortsetzen. Während der Pause sind GPS und Zell-Scans aus, die Live-Ansicht ruht und die Heimatbasis startet keine Aufzeichnung. Danach geht es von selbst weiter. In der Benachrichtigung gibt es „Pause 30 min“ und „Fortsetzen“.

**Sprache** (Einstellungen → Sprache) – Systemsprache (Deutsch bei deutschem Gerät, sonst Englisch), Deutsch oder English. Gilt für Oberfläche, Benachrichtigungen und CSV-Spaltennamen.

**Einstellungen** (Zahnrad im Live-Tab) – Energiemodus *Genau* (alle 3 s), *Ausgewogen* (alle 10 s ab 15 m Bewegung, Standard) oder *Sparsam* (alle 30 s ab 50 m, Modem-Zwischenspeicher), oder einzeln: Scan-Intervall, Mindestabstand, Live-Takt, frische Messung an/aus. GPS wird nur so oft abgefragt, wie gescannt wird.

**Telefonate & Datenverkehr** (Einstellungen) – Zell-Scans bitten das Modem um zusätzliche Messungen, was während eines Gesprächs oder großer Downloads auf manchen Geräten stören kann. Funkstrecke erkennt Telefonate (auch VoLTE und WLAN-Call) über den Audio-Modus und starken mobilen Datenverkehr (ab etwa 256 kbit/s) über die Datenzähler (ohne den eigenen Verkehr der App, z. B. Kartenkacheln). Je Fall einstellbar: *pausieren*, *nur mitlesen* (keine Messanforderung an das Modem) oder *weiter scannen*. Standard: Telefonat → pausieren, Datenverkehr → nur mitlesen (aktive Datenverbindungen liefern Timing Advance).

**Heimatbasis** (Einstellungen → Heimatbasis) – Ort per langem Druck auf die Karte oder aktuelle Position, Radius 100 m–2 km. Verlässt du den Kreis, startet die Aufzeichnung automatisch, bei Rückkehr stoppt sie (abschaltbar). Nutzt Geofencing der Google-Play-Dienste und braucht „Standort: Immer zulassen“. Android meldet das Verlassen meist mit 1–3 Minuten Verzögerung. Nach einem Neustart wird die Heimatbasis automatisch neu angemeldet.

**Stationen** – alle jemals gehörten Zellen, gruppiert nach Basisstation (4G: eNodeB mit seinen Sektoren), mit Filter nach Betreiber und Technik und Suche nach eNB, Cell-ID, PCI, TAC.

**PCI-Gruppen** – PCI = 3 · SSS-Gruppe + PSS-Index. Weil Sektoren eines Standorts meist dieselbe SSS-Gruppe nutzen, ordnet die App Nachbarzellen ohne Cell-ID einer bekannten Station mit gleicher Gruppe zu („vermutlich zugehörig“), geprüft über den Abstand der Messorte (≤ 3 km, „wahrscheinlich“ ≤ 1,5 km). Lose Zellen gleicher Gruppe an nahen Orten werden zu „vermuteten Stationen“ gebündelt. Im Live-Tab steht bei Nachbarzellen, wenn sie dieselbe Gruppe wie die verbundene Zelle haben.

**5G NSA (EN-DC)** – erkennt, ob die verbundene LTE-Zelle als Anker für 5G NSA dient. Quellen: Anzeige-Status (ab Android 11), Registrierungsstatus (NR-Zustand, `isEnDcAvailable`, `isNrAvailable`, `isDcNrRestricted`; aus dem ServiceState-Text gelesen, da Android diese Felder nicht öffentlich anbietet) und die Messwerte des NR-Zweigs (SS-RSRP/RSRQ/SINR). Zustände: kein 5G, gesperrt, verfügbar, verbunden, 5G SA.
- Live-Tab: EN-DC-Karte mit LTE-Anker, NR-Zustand und NR-Zweig (Band, PCI, ARFCN, Pegel)
- Stationen: Merkmal „5G-Anker“, EN-DC-Zähler je Sektor, NR-Zweige der Station
- Karte → Abdeckung → „5G NSA (EN-DC)“: bester 5G-Zustand je ~50-m-Feld
- Export: EN-DC je Messpunkt und Zähler je Zelle
Braucht die Freigabe „Telefon“. Die genaue Trägerkonfiguration (Physical Channel Config) ist nur System-Apps zugänglich.

**PCI-Konflikte** – Warnung nur, wenn zwei verschiedene Zellen dieselbe PCI auf demselben Träger (also im gleichen Band) nutzen und ihre Standorte höchstens 5 km auseinanderliegen: Dann können Handys sie verwechseln. Gleiche PCIs auf anderen Bändern oder Trägern sind übliche Planung (gleicher Sektor) und erscheinen nur als neutraler Hinweis.

**Geländemodell** – Mit dem Copernicus-Höhenmodell GLO-90 (90-m-Raster, frei bei AWS) prüft die Standortschätzung für jede Messung, ob ein Hügel zwischen Mast (Antenne 30 m über Grund angenommen) und Messort liegt (mit Erdkrümmung, 10 m Spielraum). Abgeschattete Messungen bekommen ±15 dB zusätzliche Unsicherheit und ziehen die Schätzung nicht mehr nach außen; die Methode nennt dann z. B. „Gelände: 12/80 abgeschattet“. Die Höhendaten liegen nur als ganze 1°-Kacheln vor (etwa 110 × 75 km, 3–4 MB Download, gespeichert 1–2 MB) und werden bei Bedarf für die Messorte geladen, standardmäßig nur über WLAN. Einstellungen → Geländemodell: ein/aus, nur WLAN, gespeicherte Gebiete, „Wirkung prüfen“ (vergleicht mit und ohne Gelände den Abstand zu nahen OSM-Masten).

**OpenStreetMap-Masten** – Funkmasten und -türme, die in OpenStreetMap eingetragen sind (`man_made=mast/tower` mit `tower:type=communication`, `man_made=communications_tower`, `communication:mobile_phone=yes`), lädt die App über die Overpass-API: automatisch für den sichtbaren Kartenausschnitt (Ansicht *Stationen*, Schalter „OSM-Masten“, ab Stadtteil-Zoom) und rund um eine geöffnete Station, für alle Stationen über Stationsliste → ⋮ → „Mit OpenStreetMap-Masten abgleichen“. Sie erscheinen als dunkle Dreiecke. Die Stationsansicht nennt den nächsten Mast im Umkreis von 1 km mit Abstand, Betreiber und Höhe; liegt er höchstens 100 m von der Schätzung entfernt, gilt die Station als zuverlässig. Gebiete von etwa 5,5 × 5,5 km werden höchstens alle 30 Tage neu abgefragt. OSM ist unvollständig (Dachstandorte und Kleinzellen fehlen meist); fehlende Masten sprechen nicht gegen eine Station. Daten: © OpenStreetMap-Mitwirkende, ODbL.

**OpenCelliD-Abgleich** – Mit einem kostenlosen API-Schlüssel von opencellid.org (Einstellungen → OpenCelliD) gleicht die App gefundene Zellen mit vollständiger Kennung (MCC, MNC, TAC/LAC, Cell-ID) ab: Stationsliste → ⋮ → „Mit OpenCelliD abgleichen“ oder einzeln in der Stationsansicht. Ergebnis je Station: „Nicht in OpenCelliD“ (neue oder nicht erfasste Zelle) oder der Abstand zwischen eigener Schätzung und OpenCelliD-Position; die OpenCelliD-Positionen erscheinen lila auf der Stationskarte. Anfragen sind gedrosselt (etwa eine pro Sekunde), Ergebnisse gelten 30 Tage; bei ungültigem Schlüssel oder erreichtem Tageslimit stoppt der Abgleich. Gesendet werden nur Zellkennungen, keine Positionen. Daten: OpenCelliD, CC BY-SA 4.0.
Histogramm in der Stationsliste: Abstand je Station zwischen eigener Mast-Schätzung und OpenCelliD-Schwerpunkt (Klassen <50 m bis >2 km), mit Median und Anteil innerhalb des eigenen Unsicherheitskreises; folgt den Betreiber-/Technikfiltern, ein Tipp auf einen Balken filtert die Liste auf diese Klasse.

**Bewertung** – jede erkannte Zelle wird eingestuft:
- *zuverlässig*: eigene Standortschätzung genauer als 200 m (95-%-Radius), höchstens 200 m vom OpenCelliD-Schwerpunkt der Station oder höchstens 100 m von einem Funkmast in OpenStreetMap entfernt
- *Kandidat*: eigene Schätzung genauer als 1 km
- *mögliches Artefakt*: alles Übrige (sehr ungenau oder gar nicht verortet)
Eine Station übernimmt die beste Bewertung ihrer Zellen. Stationsliste: Übersicht mit Balken und Zählern (antippen filtert), Plakette an jeder Station. Karte: zuverlässig = kräftiger Punkt mit Kreis, Kandidat = blasser Punkt, Artefakt = leerer Ring (standardmäßig ausgeblendet, per Chip zuschaltbar). Stationsansicht: Begründung und Spalte „Bewertung“ je Zelle; CSV-Export mit „Bewertung“ und „Begründung“.
Unplausible Treffer – weiter von der eigenen Schätzung entfernt als die von OpenCelliD angegebene Reichweite (mindestens 5 km) oder auf weniger als 3 Messungen beruhend und über 1 km entfernt – bestätigen keine Station, zählen nicht im Histogramm und erscheinen grau statt lila; die Stationsansicht nennt den Grund. Abgefragt wird immer mit vollständiger Kennung (MCC, MNC, TAC/LAC, Cell-ID); große Abstände kommen meist von veralteten Einträgen nach Umbauten, sehr wenigen Messungen oder falsch hochgeladenen Daten.
OpenCelliD liefert je Zelle eine Position; die App fasst sie je Station zu einem Schwerpunkt zusammen (plausible Zellen, gewichtet nach der Zahl der OpenCelliD-Messungen) und verwendet nur diesen – auf der Karte (Ansicht *Stationen*, Schalter „OpenCelliD“, ein Punkt mit dünner, lila gestrichelter Linie zur eigenen Schätzung ab 30 m Abstand), im Histogramm und für die Bewertung. Die Stationsansicht listet die einzelnen Zellen weiterhin auf.

**Standortschätzung** – pro Station aus allen GPS-markierten Messungen:
- Pegelmodell mit eigenem Sendepegel je Sektor, gelöst per Levenberg-Marquardt
- Timing-Advance-Werte als zusätzliche Abstandsmessungen
- Unsicherheitsradius (95 %) aus der Kovarianz; er schrumpft, je mehr Richtungen die Messungen abdecken
- Liegen alle Messungen auf einer Linie (eine Straße), ist die Lage spiegelbildlich mehrdeutig. Die App sagt das und hält den Kreis groß, bis du aus einer anderen Richtung misst.
- Detailansicht mit Karte: Messorte (grün = stark, rot = schwach), geschätzter Mast und Kreis

In einer Simulation (3 Sektoren, Messrauschen 5 dB) lag der Fehler nach zwei Straßen bei etwa 20 m, mit Timing Advance bei etwa 5 m. In echter Umgebung verfälschen Gebäude und Antennendiagramme die Pegel, rechne eher mit 50–200 m. Die Schätzung ist ein Suchgebiet.

**Karte** – Umschalter *Stationen / Abdeckung / TA-Grenzen*.
- *TA-Grenzen*: Tracking Areas (TAC, bei 2G/3G Location Areas mit LAC) je Betreiber und Technik. Jedes ~50-m-Feld erhält die TAC der verbundenen Zelle, die dort am häufigsten gemessen wurde (eigene Farbe je TAC). Linien zeigen Grenzen zwischen gemessenen Nachbarfeldern; dunkle Marker die Stellen, an denen das Handy unterwegs die TAC gewechselt hat (Tracking Area Update) – mit Uhrzeit und Genauigkeit der Grenzlage.
- *Stationen*: alle verorteten Stationen in Betreiberfarbe mit Unsicherheitskreis (OpenStreetMap). Schalter „Live“: Die Station der verbundenen Zelle ist grün, Stationen der gerade gemessenen Nachbarzellen hellgrün (Nachbarzellen ohne Cell-ID über Technik, ARFCN und PCI einer bekannten Zelle zugeordnet, bei mehreren Treffern die nächste zur verbundenen Station; nur verortete Stationen). Der Schalter zeigt, wie viele der gehörten Nachbarzellen auf der Karte stehen. Ohne Aufzeichnung scannt die Karte dafür im Takt des Live-Tabs.
- *Abdeckung*: Abdeckungskarte je Betreiber. Genaue Messpunkte (≤ 30 m) werden auf Felder von etwa 50 × 50 m zusammengefasst; jedes Feld zeigt das beste gemessene Signal aller Zellen des Betreibers (grün = sehr gut bis rot = sehr schwach). Filter nach Technik oder „Beste Technik“, Legende mit Anteil der Felder je Stufe. Andere Betreiber erscheinen nur mit einer zweiten SIM.

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
   Für einen Download unter **Releases**: auf GitHub **Releases → Draft a new release**, Tag z. B. `v2.0` (oder `2.0`) anlegen, **Publish release**. Der Workflow baut genau einmal, übernimmt die Versionsnummer aus dem Tag und hängt `funkstrecke-v2.0.apk` und `funkstrecke.apk` nach 2–3 Minuten an das Release; Titel und Text bleiben, wie eingegeben. Von der Kommandozeile geht das mit `gh release create v2.0 --title "…" --notes "…"`. Ein bloß gepushter Tag startet keinen Build.
   Direkter Download der neuesten Version (z. B. in Chrome auf dem Handy): https://github.com/FHeiCode/funkstrecke/releases/latest/download/funkstrecke.apk
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
