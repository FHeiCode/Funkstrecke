# Funkstrecke

Android-App (Kotlin, Jetpack Compose) zur Erfassung von Mobilfunk-Basisstationen. Alle Daten bleiben lokal auf dem Gerät.

## Funktionen

- **Standortliste** mit Suche (Name, ID, Adresse, Cell-ID, eNB-ID, BNetzA-Nr.), Statusfilter und Hinweis auf fällige Wartungen
- **Erfassung** pro Basisstation:
  - Stammdaten: Name, Standort-ID, Betreiber, Status (Planung → Außer Betrieb)
  - Standort: Adresse, Koordinaten per **GPS-Knopf** oder Einfügen von „49.4521, 11.0767“
  - Funktechnik: 2G/4G/5G, Bänder (700–3600 MHz), MCC/MNC, TAC, eNB/gNB-ID, Cell-IDs, PCI, Sektoren, Azimute
  - **Zell-Scan**: liest die vom Handy empfangenen Zellen (verbunden + Nachbarn) mit Band, Frequenz, Betreiber und Pegel aus; ein Tipp übernimmt die Werte
  - Bauliches: Masttyp, Gesamt- und Antennenhöhe, Nummer der BNetzA-Standortbescheinigung
  - Fotos aus Kamera oder Galerie
  - Termine: Inbetriebnahme, letzte Begehung, nächste Wartung; Notizen
- **Karte** (OpenStreetMap) mit allen Standorten; Tipp auf einen Marker öffnet ihn
- **CSV-Export** (Semikolon, Dezimalkomma; öffnet sich direkt in Excel) über das Teilen-Menü

## Bauen auf GitHub

1. Inhalt dieses Ordners in ein GitHub-Repository pushen.
2. Der Workflow `.github/workflows/android.yml` startet bei jedem Push automatisch.
3. Unter **Actions → letzter Lauf → Artifacts** liegt `funkstrecke-apk` (ZIP mit `app-debug.apk`).
4. APK aufs Handy kopieren und installieren („Installation aus unbekannten Quellen“ für den Dateimanager erlauben).

Lokal geht es genauso mit `./gradlew assembleDebug` oder in Android Studio.

## Voraussetzungen und Hinweise

- Android 8.0 (API 26) oder neuer
- Der Zell-Scan braucht die Berechtigung **„Genauer Standort“** und eine aktive SIM.
- Die eNodeB-ID wird bei 4G aus der ECI berechnet (ECI ÷ 256, Rest = Sektor).
- Bei 5G NSA melden viele Geräte keine NR-Zellidentität; dann erscheinen nur Band und Pegel.
- Die Karte lädt Kacheln von OpenStreetMap und braucht dafür Internet.

## Projektaufbau

```
app/src/main/java/de/funkstrecke/
├─ MainActivity.kt           Navigation
├─ data/                     Room-Datenbank, Datenmodell, Auswahllisten
├─ radio/                    Zell-Scanner, Band-/Frequenztabellen, Betreiber (MCC/MNC)
├─ util/                     GPS, Fotos, CSV-Export
└─ ui/                       Liste, Erfassungsformular, Karte, Theme
```
