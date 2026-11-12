# Funkstrecke – Arbeitsregeln für Claude

Android-App (Kotlin, Jetpack Compose, Material3, JDK 17). Antworten und Kommentare auf Deutsch, in einfacher Sprache.

## Branches
- Funktionen: `feature/<kurzname>` (kommen aus dem Chat).
- Fehlerbehebungen: `fix/<Issue-Nr>-<kurzname>` (kommen als GitHub-Issue).
- Immer vom aktuellen `master` abzweigen. Nie direkt auf `master` pushen.

## Fehlerbehebung aus einem Issue
1. Issue samt Kommentaren lesen. Unklar → im Issue nachfragen, nichts ändern.
2. Ursache im Code suchen, bevor geändert wird. Die Ursache im Commit, im PR-Text und als Code-Kommentar mit „Issue #N“ nennen.
3. Branch `fix/<N>-<kurzname>` anlegen, ändern, README nachziehen. `version.txt` und `release-notes.md` **nicht** ändern.
4. Lokal testen: `gradle assembleDebug` muss durchlaufen; reine Logik mit kleinen Tests prüfen.
5. Commit mit `Fixes #N`, pushen, Pull Request gegen `master` mit `Fixes #N` im Text.
6. Der Push startet den Testbuild „Android-Build“ auf dem Branch. Im PR kommentieren: Ursache in einem Satz, was auf dem Handy zu prüfen ist, Link zu den Build-Läufen des Branches (`https://github.com/<repo>/actions?query=branch%3A<branch>`), und die Release-Frage:
   > Release erstellen? Antworte mit `@claude release`, `@claude release major` oder `@claude kein release`.
7. Nicht selbst mergen. Gemergt wird vom Repo-Besitzer.

## Antwort auf die Release-Frage (Kommentar im PR)
- `@claude kein release`: nichts an der Version ändern, kurz bestätigen, dass der PR gemergt werden kann.
- `@claude release` oder `@claude release major`: auf dem PR-Branch
  1. neue Version nach der Versionsregel berechnen, in `version.txt` schreiben,
  2. `release-notes.md` neu schreiben: erste Zeile Titel, Leerzeile, dann kurze Stichpunkte zu allen Änderungen seit dem letzten Release (`gh release view --json tagName` und `git log v<letzte>..HEAD`), einfache Sprache,
  3. committen, pushen, im PR „Version X, bereit zum Mergen“ kommentieren.
  Nach dem Merge legt „Android-Build“ auf `master` das Release `v<Version>` mit APK selbst an.

## Versionsregel (MAJOR.MINOR.PATCH)
Ausgangspunkt ist das letzte Release (`gh release view --json tagName`), sonst `version.txt`.
- `fix/…` → letzte Stelle +1 (2.22.3 → 2.22.4)
- `feature/…` → mittlere Stelle +1, letzte auf 0 (2.22.3 → 2.23.0)
- Major (`@claude release major`) → erste Stelle +1, andere auf 0 (2.22.3 → 3.0.0)
- Wurde seit dem letzten Release schon ohne Release gemergt, gilt die höchste Stufe aller Änderungen; nur einmal hochzählen.

## Code
- Schichten: `data/` (Persistenz, Modelle, Logik), `ui/` (ViewModel, Screens), `radio/`, `service/`, `util/`.
- `CancellationException` immer weiterwerfen; Fehler als verständliche Meldung statt Absturz.
- Texte zweisprachig über `tr("deutsch", "english")`.
- Keine fremden Marken oder Logos nachbauen.
