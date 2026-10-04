# KanalFadenBeobachter

KanalFadenBeobachter ist eine Android-App zum Beobachten und lokalen Archivieren von Kohlchan-Fäden. Sie prüft Fäden auf Änderungen, speichert Fassungen als HTML und lädt die zugehörigen Medien in einen frei gewählten Ordner. So bleiben bereits erfasste Beiträge und Dateien auch nach ihrer Entfernung vom Server verfügbar. Benötigt wird Android 8 oder neuer.

Die App ist **100% schwingungscodiert**.

## Einrichtung und Einstellungen

Beim ersten Start wählst du einen beschreibbaren Archivordner, das Dateinamensformat, die Sichtbarkeit der Medien in Bilderübersichten und die zufällige Downloadpause. Anschließend kannst du Fadenadressen einfügen oder über Androids Teilen-Funktion übergeben. Erlaube Benachrichtigungen, damit der Abrufstatus angezeigt werden kann.

| Einstellung | Voreinstellung | Bedeutung |
| --- | --- | --- |
| Archivordner | Auswahl beim ersten Start | Hier liegen HTML-Fassungen und Medien. In den Einstellungen lässt sich der Ordner auswählen oder erneut freigeben. Ein Ordnerwechsel verschiebt kein vorhandenes Archiv. |
| Aktive Sitzung: Intervall | 60 Sekunden | Pause nach einer vollständigen Abrufrunde einschließlich Medien. Einstellbar von 10 bis 86.400 Sekunden. |
| Hintergrundplan: Intervall | 15 Minuten | Gewünschter Abstand für Androids Hintergrundaufträge, von 15 bis 1.440 Minuten. Android kann die Ausführung verzögern. |
| Lesewartezeit | 180 Sekunden | Netzwerk-Lesetimeout bei ausbleibenden Daten, von 30 bis 1.800 Sekunden. Keine Begrenzung der Gesamtdauer eines Downloads. |
| Zufällige Wartezeit zwischen Medien | Höchstens 3 Sekunden | Jede Pause wird zwischen 0 und dem gewählten Höchstwert ausgelost. Einstellbar von 0 bis 10 Sekunden; 0 deaktiviert die Pause. Bereits vorhandene, übersprungene Dateien verursachen keine Pause. |
| Medien ausblenden (`.nomedia`) | Ein | Kennzeichnet Medienordner für Androids Mediensuche, damit Archivmedien nicht in Bilderübersichten erscheinen. Die Dateien bleiben zugänglich. Beim Speichern werden auch bestehende Medienordner angepasst. |
| Dateinamen | Prüfsumme der Adresse | Wahlweise Prüfsumme der Quelladresse oder `Beitragsnummer_Originaldateiname`. Bereits zugeordnete Dateinamen bleiben erhalten. |
| Nur ungetaktete Netze | Aus | Beschränkt Abrufe auf Netze, die Android als ungetaktet einstuft, beispielsweise WLAN. |

Weitere Aktionen in den Einstellungen:

- **Fehlgeschlagene Medien erneut versuchen:** Stellt fehlgeschlagene Downloads für einen folgenden Abruf wieder in die Warteschlange.
- **Entfernte Fäden wiederherstellen:** Holt zuvor aus der Liste entfernte Fäden zurück.
- **Protokoll ausgeben:** Speichert das Abrufprotokoll als Textdatei.

### Abrufe starten

**Einzelabruf** prüft die aktiven Fäden einmal und lädt ausstehende Medien nacheinander. **Intervallabruf** bietet zwei Betriebsarten:

- **Aktive Sitzung:** Wiederholt vollständige Abrufrunden mit dem eingestellten Sekundenintervall. Sie läuft höchstens fünf Stunden; Android kann sie früher beenden.
- **Hintergrundplan:** Lässt Android die Abrufe planen. Eine Ausführung arbeitet höchstens acht Minuten; offene Medien bleiben vorgemerkt. Der Plan bleibt über Geräteneustarts erhalten. Die angezeigte nächste Ausführung ist ein Richtwert.

**Anhalten** beendet beide Betriebsarten. Gleichzeitig laufen keine überlappenden Abrufrunden. Beiträge, die zwischen zwei Prüfungen erscheinen und wieder verschwinden, können nicht erfasst werden.

### Vorhandene Archive

Nach einer Neuinstallation kannst du den bisherigen Archivordner wählen. Fäden werden daraus **nicht automatisch importiert**: Füge ihre Adressen erneut hinzu. Vorhandene lesbare, nicht leere Mediendateien werden wiederverwendet; bekannte Dateigrößen müssen übereinstimmen. Die Fadenseite wird weiterhin abgerufen, um Änderungen zu erkennen.

Je Faden enthält das Archiv die aktuelle HTML-Fassung unter `res########/html/`, ältere Fassungen unter `html/old/` und Medien unter `media/`. Die Datei `media/media.db.csv` enthält die Medienzuordnung. Zum Lesen am Rechner den gesamten Fadenordner kopieren, damit relative Medienpfade erhalten bleiben. Auf Android muss der verwendete Browser lokale relative Dateizugriffe unterstützen.

## Selbst bauen

### Voraussetzungen für alle Systeme

- **JDK 17** für den Build.
- **Android SDK Platform 36** und **Android SDK Build-Tools 35.0.0**.
- Internetzugang beim ersten Build zum Herunterladen von Gradle und Abhängigkeiten.

Das Projekt verwendet Kotlin 2.1.20, Android Gradle Plugin 8.11.1 und den mitgelieferten Gradle Wrapper 8.13. Eine separate Gradle-Installation ist nicht nötig.

Am einfachsten installierst du [Android Studio](https://developer.android.com/studio), öffnest den entpackten Projektordner mit `settings.gradle.kts` und installierst die genannten SDK-Komponenten im **SDK Manager**. Aktiviere dort bei Bedarf **Show Package Details**, um Build-Tools 35.0.0 auszuwählen. Für die folgenden Terminalbefehle muss zusätzlich JDK 17 installiert sein. Wähle für Builds innerhalb von Android Studio ebenfalls JDK 17 als **Gradle JDK**.

Die folgenden SDK-Pfade sind die üblichen Standardpfade. Falls der SDK Manager einen anderen Speicherort zeigt, passe `ANDROID_HOME` entsprechend an.

### Linux

Auf Arch Linux:

```bash
sudo pacman -Syu --needed jdk17-openjdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Android/Sdk"
cd /pfad/zu/KanalFadenBeobachter
chmod +x gradlew
./gradlew assembleDebug
```

Unter anderen Distributionen JDK 17 über deren Paketverwaltung installieren und `JAVA_HOME` an den dortigen Installationspfad anpassen. Die übrigen Schritte sind gleich.

### macOS

Installiere ein JDK 17 für die Architektur deines Macs, etwa [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17), und richte das Android SDK wie oben beschrieben ein. Im Terminal:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
cd /pfad/zu/KanalFadenBeobachter
chmod +x gradlew
./gradlew assembleDebug
```

### Windows

Installiere JDK 17 und richte das Android SDK wie oben beschrieben ein. Öffne PowerShell; ersetze den Beispielpfad für das JDK durch dessen tatsächlichen Installationsordner:

```powershell
$env:JAVA_HOME = 'C:\Pfad\zum\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
Set-Location 'C:\Pfad\zu\KanalFadenBeobachter'
.\gradlew.bat assembleDebug
```

### Ergebnis und Signatur

Die installierbare Debug-APK liegt auf allen Systemen unter:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Ohne weitere Konfiguration verwendet der Build den lokalen Android-Debugschlüssel. Optional kann `KANALFADEN_SCHLUESSEL` auf eine vorhandene Schlüsseldatei zeigen; die aktuelle Debug-Konfiguration erwartet Alias `androiddebugkey` und die Passwörter `android`. Das ist ausschließlich eine Testsignatur.

Für eine mit eigenem Release-Schlüssel signierte APK kannst du in Android Studio **Build → Generate Signed App Bundle or APK → APK** verwenden und einen eigenen Keystore auswählen oder erstellen. `assembleRelease` allein erzeugt mit dieser Projektkonfiguration eine unsignierte APK. Bewahre den Release-Schlüssel für spätere Updates auf und veröffentliche ihn nicht. Ein Update einer bestehenden Installation benötigt eine passende Signatur; ein eigener Build kann deshalb nicht zwangsläufig über die mitgelieferte Test-APK installiert werden.

Optional lassen sich die vorhandenen Prüfungen mit `./gradlew testDebugUnitTest lintDebug` beziehungsweise `.\gradlew.bat testDebugUnitTest lintDebug` ausführen.
