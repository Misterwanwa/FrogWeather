# Automatischer Android-Build & Tablet-Deploy
Nach **jeder** Code- oder Ressourcenänderung (z. B. unter `app/`, `build.gradle*`, `settings.gradle.kts`, `gradle*`) muss automatisch folgender Ablauf ausgeführt werden:

1. **ADB-Gerätestatus prüfen:**
   - Prüfe per `adb.exe devices` (`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` bzw. `sdk.dir` aus `local.properties`), ob **genau ein** autorisiertes Gerät (Status `device`) verbunden ist.
   - Falls **ja**, führe automatisch `cmd /c run-tablet.cmd` aus (baut die Debug-APK, installiert sie auf dem Gerät und startet die Launcher-Activity).
2. **Fallback ohne eindeutiges Gerät:**
   - Falls kein autorisiertes Gerät bereit ist (0 Geräte, nur `unauthorized`/`offline` oder mehrere Geräte ohne eindeutiges Ziel), führe mindestens `cmd /c run-tablet.cmd -BuildOnly` zur Build-Verifikation aus.
3. **Hinweis in der Abschlussantwort:**
   - Erinnere den Nutzer in der Abschlussantwort **immer** kurz an die 1-Klick-Wege zum Testen auf dem Tablet/Handy:
     - `Strg + Umschalt + B` in VS Code oder Doppelklick auf `run-tablet.cmd` (Voraussetzung: USB-Debugging oder WLAN-ADB).

# Git & Deployment (GitHub-Push)
Nach Code-Änderungen müssen die Änderungen sauber committet und auf GitHub in den konfigurierten Remote-Branch gepusht werden:

1. Erfolgreichen Android-Build (bzw. Tablet-Deploy) gemäß obiger Regel verifizieren.
2. Alle relevanten geänderten Dateien stagen (`git add ...`).
3. Einen aussagekräftigen Commit-Text verfassen und committen.
4. `git push` ausführen, um die Änderungen auf GitHub zu sichern.
