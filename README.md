FrogWeather
===========

Kleines Starter-Projekt für eine Wetter-App (Kotlin + Jetpack Compose).

Schnellstart

- Öffne das Projekt in Android Studio.
- Falls nötig: erzeug den Gradle Wrapper mit `gradle wrapper` oder verwende Android Studio zum Import.
- Sync und run auf einem Gerät oder Emulator.

## 1-Klick auf dem Tablet testen (ohne Android Studio)

Voraussetzung auf dem Tablet/Handy: **USB-Debugging** (oder **WLAN-ADB**) in den Entwickleroptionen aktivieren und das Gerät per USB-Kabel oder `adb connect` verbinden. JDK (>= 17), Android SDK/`adb.exe` und Gradle (`gradlew.bat` bzw. gecachte Distribution) werden automatisch erkannt.

- **VS Code:** `Strg + Umschalt + B` startet den Standard-Build-Task *„Auf Tablet bauen & starten“*. Über *Terminal -> Aufgabe ausführen* steht außerdem *„Nur APK auf Tablet installieren (ohne Build)“* bereit.
- **Windows Explorer:** Doppelklick auf `run-tablet.cmd` baut die App, installiert sie auf dem angeschlossenen Gerät und startet sie direkt (Fenster pausiert am Ende nur beim Doppelklick).
- **PowerShell / Terminal:** `.\run-tablet.ps1` bzw. `cmd /c run-tablet.cmd`.

Verfügbare Optionen für `run-tablet.ps1` / `run-tablet.cmd`:
- `-SkipBuild`: Installiert direkt die vorhandene Debug-APK (`app-debug.apk`) ohne erneuten Gradle-Build und startet die App.
- `-BuildOnly`: Führt nur den Gradle-Debug-Build (`assembleDebug`) aus, ohne dass ein Gerät verbunden sein muss.
- `-Serial <ID>`: Wählt bei mehreren angeschlossenen Geräten gezielt das Gerät mit der angegebenen ADB-Seriennummer aus (ohne Angabe erscheint ein Auswahlmenü).

Enthalten:
- Compose `MainActivity` mit einfacher Suchleiste.
- Gradle Kotlin DSL (Basis-Setup).

Nächste Schritte:
- Projekt zeigt beim Start jetzt das aktuelle Wetter am Aufenthaltsort.
  Eine animierte Frosch‑Emoji repräsentiert die Wetterlage (sonnig, regnerisch,
  bewölkt). Diese Logik liegt in `MainActivity` und `WeatherType`.
- Die Oberfläche wurde an das gewünschte Layout angelehnt (siehe Beispielfoto):
  dunkler Hintergrund, obere Statusleiste mit Standort-/Profilicon und Suchleiste,
  großer Temperaturwert, Froschanimation, horizontale Stunden‑ und vertikale
  Tagesübersichten.
- Klick auf das GPS‑Symbol oben links löst eine Standortsuche aus und aktualisiert
  das Wetter.
- Die Suchzeile ist jetzt in die obere Leiste integriert (zwischen den Icons).
  Man gibt den Ort ein und drückt die Lupe/Enter, um das Wetter für diesen
  Ort zu laden – die separate Textzeile unterhalb entfällt.
- Der aktuelle Ort erscheint direkt darüber in weißer Schrift.
- Die stündliche Vorschau zeigt die nächsten 4 Stunden im 30‑Min‑Rhythmus (8
  Einträge) mit Zeitstempeln – sie sollte wieder erscheinen, sobald Daten vorliegen.
- Die Tagesübersicht deckt die nächsten 7 Tage ab und zeigt das Datum im
  Format `dd.MM`.
- Wetter‑Emojis (Frosch‑Icons) werden für Vorhersageeinträge verwendet.
- Retrofit/Moshi verwendet, Schlüssel `YOUR_API_KEY_HERE` im Code ersetzen
  oder in `gradle.properties` auslagern (OpenWeatherMap oder eigener Service).
- Android 12+ erfordert Standortberechtigungen – die App fragt beim Start danach.
- Compose‑basierte UI; können weitere Grafiken oder echte Forecast‑Daten ergänzt
  werden.

Zusätzliche Ideen:
- Ersetze die Emojis durch echte Lottie/Vector-Animationen in `FrogAnimation`.
- Füge Caching/Offline-Unterstützung (Room) hinzu.
- Dark mode, weitere Wetterzustände, Einstellungen usw.

