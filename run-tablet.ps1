[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$BuildOnly,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'

try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # Ignorieren, falls Ausgabe umgeleitet ist
}

function Write-Info([string]$Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Success([string]$Message) {
    Write-Host "[OK]   $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Stop-WithError([string]$Message, [int]$ExitCode = 1) {
    Write-Host ""
    Write-Host "[FEHLER] $Message" -ForegroundColor Red
    exit $ExitCode
}

function Get-JavaMajorVersion([string]$JavaExe) {
    if (-not (Test-Path -LiteralPath $JavaExe)) {
        return $null
    }

    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $JavaExe
        $psi.Arguments = '-version'
        $psi.RedirectStandardError = $true
        $psi.RedirectStandardOutput = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true

        $proc = [System.Diagnostics.Process]::Start($psi)
        $stderr = $proc.StandardError.ReadToEnd()
        $stdout = $proc.StandardOutput.ReadToEnd()
        $proc.WaitForExit()

        if ($proc.ExitCode -ne 0) {
            return $null
        }

        $combined = "$stderr`n$stdout"
        if ($combined -match 'version\s+"(\d+)(?:\.(\d+))?') {
            $major = [int]$matches[1]
            if ($major -eq 1 -and $matches[2]) {
                return [int]$matches[2]
            }
            return $major
        }
    } catch {
        return $null
    }

    return $null
}

function Find-Jdk17Plus {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) {
        $cleaned = $env:JAVA_HOME.Trim().Trim('"').TrimEnd('\', '/')
        if ($cleaned) {
            $candidates.Add($cleaned)
        }
    }

    $pathJava = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if ($pathJava -and $pathJava.Source) {
        $binDir = Split-Path -Path $pathJava.Source -Parent
        $jdkRoot = Split-Path -Path $binDir -Parent
        if ($jdkRoot) {
            $candidates.Add($jdkRoot)
        }
    }

    $searchRoots = @(
        'C:\Program Files\Eclipse Adoptium\jdk*'
        'C:\Program Files\Java\jdk*'
        'C:\Program Files\Microsoft\jdk*'
        'C:\Program Files\Zulu\zulu*'
        'C:\Program Files\BellSoft\*'
        'C:\Program Files\Amazon Corretto\jdk*'
        'C:\Program Files\Android\Android Studio\jbr'
        'C:\Program Files\Android\Android Studio\jre'
    )
    if ($env:LOCALAPPDATA) {
        $searchRoots += (Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium\jdk*')
    }
    if ($env:USERPROFILE) {
        $searchRoots += (Join-Path $env:USERPROFILE '.jdks\*')
    }

    foreach ($pattern in $searchRoots) {
        $dirs = Get-Item -Path $pattern -ErrorAction SilentlyContinue | Where-Object { $_.PSIsContainer } | Sort-Object Name -Descending
        foreach ($d in $dirs) {
            $candidates.Add($d.FullName)
        }
    }

    $seen = @{}
    foreach ($dir in $candidates) {
        if (-not $dir) { continue }
        $norm = $dir.TrimEnd('\', '/')
        if ($seen.ContainsKey($norm.ToLowerInvariant())) { continue }
        $seen[$norm.ToLowerInvariant()] = $true

        $javaExe = [System.IO.Path]::Combine($norm, 'bin', 'java.exe')
        $major = Get-JavaMajorVersion $javaExe
        if ($null -ne $major -and $major -ge 17) {
            return @{
                Home    = $norm
                Version = $major
            }
        }
    }

    return $null
}

function Unescape-PropertiesPath([string]$Value) {
    $v = $Value.Trim()
    $v = $v.Replace('\:', ':').Replace('\\', '\')
    return $v
}

function Find-AndroidSdk([string]$ProjectRoot) {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:ANDROID_HOME) { $candidates.Add($env:ANDROID_HOME.Trim().Trim('"')) }
    if ($env:ANDROID_SDK_ROOT) { $candidates.Add($env:ANDROID_SDK_ROOT.Trim().Trim('"')) }

    $localProps = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        foreach ($line in (Get-Content -LiteralPath $localProps -Encoding UTF8)) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
                $candidates.Add((Unescape-PropertiesPath $matches[1]))
            }
        }
    }

    if ($env:LOCALAPPDATA) {
        $candidates.Add([System.IO.Path]::Combine($env:LOCALAPPDATA, 'Android', 'Sdk'))
    }

    foreach ($dir in $candidates) {
        if ($dir -and (Test-Path -LiteralPath $dir)) {
            return $dir.TrimEnd('\', '/')
        }
    }

    return $null
}

function Find-Adb([string]$SdkDir) {
    if ($SdkDir) {
        $candidate = [System.IO.Path]::Combine($SdkDir, 'platform-tools', 'adb.exe')
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $cmd = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) {
        return $cmd.Source
    }

    return $null
}

function Find-Gradle([string]$ProjectRoot) {
    $wrapperBat = Join-Path $ProjectRoot 'gradlew.bat'
    $wrapperJar = [System.IO.Path]::Combine($ProjectRoot, 'gradle', 'wrapper', 'gradle-wrapper.jar')
    if ((Test-Path -LiteralPath $wrapperBat) -and (Test-Path -LiteralPath $wrapperJar)) {
        return $wrapperBat
    }

    if ($env:USERPROFILE) {
        $distsDir = [System.IO.Path]::Combine($env:USERPROFILE, '.gradle', 'wrapper', 'dists')
        if (Test-Path -LiteralPath $distsDir) {
            $cached = Get-ChildItem -Path $distsDir -Recurse -Filter 'gradle.bat' -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending |
                Select-Object -First 1
            if ($cached) {
                return $cached.FullName
            }
        }
    }

    $pathGradle = Get-Command 'gradle.bat' -ErrorAction SilentlyContinue
    if ($pathGradle -and $pathGradle.Source) {
        return $pathGradle.Source
    }

    return $null
}

function Get-AppMetadata([string]$ProjectRoot) {
    $appId = $null
    $launcherActivity = $null

    foreach ($gradleFile in @('app\build.gradle.kts', 'app\build.gradle')) {
        $fullPath = Join-Path $ProjectRoot $gradleFile
        if (Test-Path -LiteralPath $fullPath) {
            $content = Get-Content -LiteralPath $fullPath -Raw -Encoding UTF8
            if ($content -match '(?m)^\s*applicationId\s*=?\s*["'']([^"'']+)["'']') {
                $appId = $matches[1]
                break
            }
            if ($content -match '(?m)^\s*namespace\s*=?\s*["'']([^"'']+)["'']') {
                $appId = $matches[1]
            }
        }
    }

    if (-not $appId) {
        Stop-WithError "Konnte 'applicationId' bzw. 'namespace' nicht aus app/build.gradle(.kts) ermitteln."
    }

    $manifestPath = [System.IO.Path]::Combine($ProjectRoot, 'app', 'src', 'main', 'AndroidManifest.xml')
    if (Test-Path -LiteralPath $manifestPath) {
        try {
            [xml]$xml = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8
            $androidNs = 'http://schemas.android.com/apk/res/android'
            $activities = @()
            if ($xml.manifest.application.activity) {
                $activities += @($xml.manifest.application.activity)
            }
            if ($xml.manifest.application.'activity-alias') {
                $activities += @($xml.manifest.application.'activity-alias')
            }

            foreach ($act in $activities) {
                $filters = @($act.'intent-filter')
                foreach ($filter in $filters) {
                    if (-not $filter) { continue }
                    $hasMain = $false
                    $hasLauncher = $false

                    foreach ($action in @($filter.action)) {
                        if ($action -and $action.GetAttribute('name', $androidNs) -eq 'android.intent.action.MAIN') {
                            $hasMain = $true
                        }
                    }
                    foreach ($cat in @($filter.category)) {
                        if ($cat -and $cat.GetAttribute('name', $androidNs) -eq 'android.intent.category.LAUNCHER') {
                            $hasLauncher = $true
                        }
                    }

                    if ($hasMain -and $hasLauncher) {
                        $launcherActivity = $act.GetAttribute('name', $androidNs)
                        break
                    }
                }
                if ($launcherActivity) { break }
            }
        } catch {
            Stop-WithError "AndroidManifest.xml konnte nicht gelesen werden: $($_.Exception.Message)"
        }
    }

    if (-not $launcherActivity) {
        Stop-WithError "Konnte keine Launcher-Activity (MAIN + LAUNCHER) in app/src/main/AndroidManifest.xml finden."
    }

    if (-not $launcherActivity.StartsWith('.') -and -not $launcherActivity.Contains('.')) {
        $launcherActivity = ".$launcherActivity"
    }

    return @{
        ApplicationId    = $appId
        LauncherActivity = $launcherActivity
    }
}

function Invoke-NativeCapture([string]$ExePath, [string[]]$Arguments) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $ExePath
    $escapedArgs = @()
    foreach ($a in $Arguments) {
        if ($a -match '[\s"]') {
            $escapedArgs += ('"' + ($a -replace '"', '\"') + '"')
        } else {
            $escapedArgs += $a
        }
    }
    $psi.Arguments = ($escapedArgs -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stderrTask = $proc.StandardError.ReadToEndAsync()
    $stdout = $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()
    $stderr = $stderrTask.GetAwaiter().GetResult()

    return @{
        ExitCode = $proc.ExitCode
        StdOut   = $stdout
        StdErr   = $stderr
    }
}

function Get-AdbDevices([string]$AdbPath) {
    $res = Invoke-NativeCapture -ExePath $AdbPath -Arguments @('devices', '-l')
    if ($res.ExitCode -ne 0) {
        $detail = ($res.StdErr + "`n" + $res.StdOut).Trim()
        Stop-WithError "adb devices konnte nicht ausgefuehrt werden. $detail"
    }

    $devices = New-Object System.Collections.Generic.List[object]
    $lines = $res.StdOut -split "`r?`n"
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed -like 'List of devices attached*') { continue }
        if ($trimmed -like '* daemon *') { continue }

        if ($trimmed -match '^(\S+)\s+(\S+)(.*)$') {
            $id = $matches[1]
            $state = $matches[2]
            $extra = $matches[3]
            $model = ''
            if ($extra -match 'model:(\S+)') {
                $model = $matches[1]
            }
            $devices.Add([PSCustomObject]@{
                Serial = $id
                State  = $state
                Model  = $model
            })
        }
    }

    return ,$devices.ToArray()
}

function Select-TargetDevice([string]$AdbPath, [string]$RequestedSerial) {
    $devices = @(Get-AdbDevices -AdbPath $AdbPath)

    if ($RequestedSerial) {
        $match = $devices | Where-Object { $_.Serial -eq $RequestedSerial } | Select-Object -First 1
        if (-not $match) {
            $foundText = if ($devices.Count -gt 0) {
                ' Gefundene Geraete: ' + (($devices | ForEach-Object { "$($_.Serial) ($($_.State))" }) -join ', ')
            } else {
                ' Es sind aktuell keine Geraete per ADB verbunden.'
            }
            Stop-WithError "Geraet mit Seriennummer '$RequestedSerial' wurde nicht gefunden.$foundText"
        }

        if ($match.State -eq 'unauthorized') {
            Stop-WithError "Das Geraet '$RequestedSerial' ist nicht autorisiert (unauthorized). Bitte auf dem Tablet/Handy den Dialog 'USB-Debugging zulassen' bestaetigen."
        }
        if ($match.State -eq 'offline') {
            Stop-WithError "Das Geraet '$RequestedSerial' ist offline. Bitte USB-Kabel oder WLAN-ADB-Verbindung trennen und neu verbinden."
        }
        if ($match.State -ne 'device') {
            Stop-WithError "Das Geraet '$RequestedSerial' hat den Status '$($match.State)' und ist nicht bereit."
        }

        return $match
    }

    $ready = @($devices | Where-Object { $_.State -eq 'device' })
    $unauthorized = @($devices | Where-Object { $_.State -eq 'unauthorized' })
    $offline = @($devices | Where-Object { $_.State -eq 'offline' })

    if ($ready.Count -eq 0) {
        if ($unauthorized.Count -gt 0) {
            $ids = ($unauthorized | ForEach-Object { $_.Serial }) -join ', '
            Stop-WithError "Geraet gefunden ($ids), aber USB-Debugging ist nicht autorisiert (unauthorized). Bitte auf dem Tablet/Handy 'USB-Debugging zulassen' bestaetigen."
        }
        if ($offline.Count -gt 0) {
            $ids = ($offline | ForEach-Object { $_.Serial }) -join ', '
            Stop-WithError "Geraet gefunden ($ids), aber der Status ist 'offline'. Bitte USB-Kabel oder WLAN-ADB neu verbinden."
        }

        Stop-WithError "Kein Android-Geraet per ADB verbunden. Bitte Tablet/Handy per USB oder WLAN-ADB verbinden und USB-Debugging aktivieren (oder '-BuildOnly' verwenden, um nur zu bauen)."
    }

    if ($ready.Count -eq 1) {
        return $ready[0]
    }

    Write-Host ""
    Write-Host "Mehrere Android-Geraete verbunden:" -ForegroundColor Yellow
    for ($i = 0; $i -lt $ready.Count; $i++) {
        $d = $ready[$i]
        $modelInfo = if ($d.Model) { " ($($d.Model))" } else { '' }
        Write-Host ("  [{0}] {1}{2}" -f ($i + 1), $d.Serial, $modelInfo)
    }

    try {
        $choice = Read-Host "Bitte Geraet waehlen (1-$($ready.Count))"
    } catch {
        Stop-WithError "Keine interaktive Eingabe moeglich. Bitte gewuenschtes Geraet mit '-Serial <ID>' angeben."
    }

    $index = 0
    if (-not [int]::TryParse($choice, [ref]$index) -or $index -lt 1 -or $index -gt $ready.Count) {
        Stop-WithError "Ungueltige Auswahl '$choice'. Abbruch."
    }

    return $ready[$index - 1]
}

# --- Hauptablauf ---
if ($SkipBuild -and $BuildOnly) {
    Stop-WithError "Die Parameter '-SkipBuild' und '-BuildOnly' koennen nicht gleichzeitig verwendet werden."
}

$ProjectRoot = $PSScriptRoot
$meta = Get-AppMetadata -ProjectRoot $ProjectRoot
Write-Info "Projekt: $($meta.ApplicationId) | Launcher: $($meta.LauncherActivity)"

if (-not $SkipBuild) {
    $jdk = Find-Jdk17Plus
    if (-not $jdk) {
        Stop-WithError "Kein gueltiges JDK >= 17 gefunden. Bitte JDK 17+ installieren oder JAVA_HOME setzen."
    }
    $env:JAVA_HOME = $jdk.Home
    $env:PATH = (Join-Path $jdk.Home 'bin') + ';' + $env:PATH
    Write-Info "JDK $($jdk.Version): $($jdk.Home)"
}

$sdkDir = Find-AndroidSdk -ProjectRoot $ProjectRoot
if ($sdkDir) {
    $env:ANDROID_HOME = $sdkDir
    $env:ANDROID_SDK_ROOT = $sdkDir
    Write-Info "Android SDK: $sdkDir"
}

$adbPath = Find-Adb -SdkDir $sdkDir
if (-not $BuildOnly -and -not $adbPath) {
    Stop-WithError "adb.exe wurde nicht gefunden. Bitte Android SDK Platform-Tools installieren oder sdk.dir in local.properties / ANDROID_HOME setzen."
}

$gradleCmd = $null
if (-not $SkipBuild) {
    $gradleCmd = Find-Gradle -ProjectRoot $ProjectRoot
    if (-not $gradleCmd) {
        Stop-WithError "Weder gradlew.bat noch eine gecachte Gradle-Distribution wurde gefunden."
    }
    Write-Info "Gradle: $gradleCmd"
}

$targetDevice = $null
if (-not $BuildOnly) {
    $targetDevice = Select-TargetDevice -AdbPath $adbPath -RequestedSerial $Serial
    $modelLabel = if ($targetDevice.Model) { " ($($targetDevice.Model))" } else { '' }
    Write-Info "Zielgeraet: $($targetDevice.Serial)$modelLabel"
}

Push-Location -LiteralPath $ProjectRoot
try {
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($BuildOnly) {
            Write-Info "Starte Gradle-Build (assembleDebug)..."
            & $gradleCmd assembleDebug
            $gradleExit = $LASTEXITCODE
            if ($gradleExit -ne 0) {
                Stop-WithError "Gradle-Build (assembleDebug) fehlgeschlagen (Exit-Code $gradleExit)." $gradleExit
            }

            $apkPath = [System.IO.Path]::Combine($ProjectRoot, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
            Write-Success "Debug-Build erfolgreich abgeschlossen: $apkPath"
            exit 0
        }

        if ($SkipBuild) {
            $apkPath = [System.IO.Path]::Combine($ProjectRoot, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
            if (-not (Test-Path -LiteralPath $apkPath)) {
                Stop-WithError "Keine fertige Debug-APK gefunden ($apkPath). Bitte zuerst ohne '-SkipBuild' oder mit '-BuildOnly' bauen."
            }

            Write-Info "Installiere vorhandene Debug-APK auf $($targetDevice.Serial)..."
            & $adbPath -s $targetDevice.Serial install -r -t $apkPath
            $installExit = $LASTEXITCODE
            if ($installExit -ne 0) {
                Stop-WithError "APK-Installation auf '$($targetDevice.Serial)' fehlgeschlagen (Exit-Code $installExit)." $installExit
            }
        } else {
            $env:ANDROID_SERIAL = $targetDevice.Serial
            Write-Info "Baue und installiere Debug-APK auf $($targetDevice.Serial) (installDebug)..."
            & $gradleCmd installDebug
            $gradleExit = $LASTEXITCODE
            if ($gradleExit -ne 0) {
                Stop-WithError "Gradle installDebug fehlgeschlagen (Exit-Code $gradleExit)." $gradleExit
            }
        }

        $component = "$($meta.ApplicationId)/$($meta.LauncherActivity)"
        Write-Info "Starte App ($component)..."
        $startRes = Invoke-NativeCapture -ExePath $adbPath -Arguments @('-s', $targetDevice.Serial, 'shell', 'am', 'start', '-n', $component)
        if ($startRes.StdOut) { Write-Host $startRes.StdOut.Trim() }
        if ($startRes.ExitCode -ne 0 -or ($startRes.StdOut -match 'Error:')) {
            $errDetail = ($startRes.StdErr + "`n" + $startRes.StdOut).Trim()
            Stop-WithError "Starten der App '$component' auf '$($targetDevice.Serial)' fehlgeschlagen. $errDetail"
        }

        Write-Success "App '$component' laeuft auf '$($targetDevice.Serial)'."
    } finally {
        $ErrorActionPreference = $prevPref
    }
} finally {
    Pop-Location
}
