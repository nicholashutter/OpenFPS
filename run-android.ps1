<#
.SYNOPSIS
    Rebuilds the APK, brings the emulator up, installs, launches, and tails
    logcat for this app.

.DESCRIPTION
    The Android counterpart of run-desktop.ps1, and it exists for the same
    reason: a build you did not actually rebuild is indistinguishable from a
    feature that was never written. On this platform there is a second way to be
    fooled -- an APK that built fine but was never installed over the old one -- so
    the script reports the APK's own timestamp and reinstalls every run.

    What it does, in order:

      1. Prints the commit, the working-tree state and the SDK it found.
      2. Rebuilds :android:assembleDebug. A failed build stops the script; it
         never installs or launches a previous APK.
      3. Reuses a running emulator or device if there is one, otherwise starts
         the AVD and waits for sys.boot_completed.
      4. Force-stops the app, installs with -r, and launches the Activity.
      5. Tails logcat filtered to this app's process, so the engine's own log --
         not just the platform tag -- is on screen.

    -gpu host is NOT used and must not be: on this machine's Intel iGPU it dies
    with "Failed to find ColorBuffer", and the crash-consent dialog that follows
    blocks the NEXT boot indefinitely. The default here is
    -gpu swiftshader_indirect with no window, which is slower per frame and
    always comes up.

.PARAMETER RenderMode
    Internal render resolution: 480p (default), 720p or native. Delivered as an
    Intent extra, because an APK has no command line -- see LAUNCH_PROPERTIES in
    AndroidLauncher. -DebugOverlay's RES line is how you confirm it took effect;
    the value is also echoed in logcat as "Launch extra:".

.PARAMETER DebugOverlay
    Starts with the corner diagnostic overlay on: FRAME, RENDER and RES. Also an
    Intent extra.

.PARAMETER StartInGame
    Rejected on this platform, deliberately and loudly rather than silently
    ignored. See the note the script prints, and BUILD.md.

.PARAMETER Avd
    Which AVD to start. Defaults to OpenFPS_API36, the one configured for this
    project -- landscape, hardware keyboard, 6 cores, 4 GB.

.PARAMETER Window
    Show the emulator window. Off by default: headless is what survives this
    machine's GPU, and adb does everything the script needs.

.PARAMETER GpuMode
    Emulator -gpu argument. Defaults to swiftshader_indirect. `host` is accepted
    but warned about, because it crashes here.

.PARAMETER NoEmulator
    Do not start anything; require a device to be connected already. Use this
    for a physical phone.

.PARAMETER BootTimeoutSeconds
    How long to wait for sys.boot_completed. Default 300 -- a cold
    swiftshader boot on this machine takes about a minute, and more under load.

.PARAMETER Clean
    Deletes :android build output first, so the APK is assembled from scratch.

.PARAMETER NoLogcat
    Install and launch, then exit instead of tailing the log.

.PARAMETER Screenshot
    After launching, wait, capture the screen to this path and exit. Uses
    screencap + pull, never `adb exec-out > file`: PowerShell's redirection
    corrupts the PNG.

    This photographs the MENU, because nothing unattended can tap Single Player
    and a hardcoded tap coordinate would rot the moment the menu is laid out
    differently. The script prints the two adb commands that get you into the
    world and take a second shot, and those are what confirm the RES line.

.PARAMETER ScreenshotDelaySeconds
    How long to wait before capturing. Default 20. Under swiftshader the first
    frame can take ten seconds to reach the surface, and a capture taken before
    it does comes back a legitimately black PNG.

.PARAMETER NoLaunch
    Build and report, then stop. Touches no device.

.PARAMETER Help
    Prints this option list. `--help` and `-h` work too.

.EXAMPLE
    .\run-android.ps1
    Rebuild, boot the emulator if needed, install, launch, tail the log.

.EXAMPLE
    .\run-android.ps1 -RenderMode native -DebugOverlay
    Same, at the panel's own resolution with the overlay on -- the pair that
    shows what a software rasterizer costs at 2.59 megapixels.

.EXAMPLE
    .\run-android.ps1 -DebugOverlay -Screenshot C:\tmp\phone.png
    Launch, capture one frame, exit.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    # PositionalBinding above is $false so that a bare `--help` lands in $Rest
    # rather than being bound to the first parameter and rejected by its
    # ValidateSet. Anything unnamed is then an error we can report ourselves.

    [ValidateSet('480p', '720p', 'native')]
    [string] $RenderMode,

    [switch] $DebugOverlay,

    [switch] $StartInGame,

    [string] $Avd = 'OpenFPS_API36',

    [switch] $Window,

    [string] $GpuMode = 'swiftshader_indirect',

    [switch] $NoEmulator,

    [int] $BootTimeoutSeconds = 300,

    [switch] $Clean,

    [switch] $NoLogcat,

    [string] $Screenshot,

    [int] $ScreenshotDelaySeconds = 20,

    [switch] $NoLaunch,

    [switch] $Help,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest
)

# Deliberately NOT 'Stop'. Windows PowerShell wraps every stderr line a native
# executable writes into an ErrorRecord, and under 'Stop' the first one
# terminates the script -- so a failed Gradle build, or any of adb's routine
# chatter, would raise a NativeCommandError instead of reaching the banners
# below. Every external call here has its $LASTEXITCODE checked explicitly.
$ErrorActionPreference = 'Continue'

$package = 'com.openfps.android'
$activity = 'com.openfps.android/com.openfps.android.AndroidLauncher'

if ($Help -or ($Rest -contains '--help') -or ($Rest -contains '-h') -or ($Rest -contains '/?'))
{
    Get-Help -Full $PSCommandPath
    exit 0
}
if ($Rest)
{
    Write-Host "Unrecognised argument: $($Rest -join ' ')" -ForegroundColor Red
    Write-Host "Run  .\run-android.ps1 --help  for the option list." -ForegroundColor Yellow
    exit 2
}

$repo = $PSScriptRoot
Set-Location $repo

function Write-Banner([string] $text, [string] $colour)
{
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor $colour
    Write-Host "  $text" -ForegroundColor $colour
    Write-Host ('=' * 72) -ForegroundColor $colour
}

# --------------------------------------------------------------------------
# 0. --start-in-game: refused, with the reason
# --------------------------------------------------------------------------
# Accepting it and doing nothing is the exact failure this pair of scripts was
# written to remove, so it is a hard error instead.
if ($StartInGame)
{
    Write-Banner '-StartInGame is desktop-only -- refusing rather than ignoring it' 'Red'
    Write-Host 'On desktop this sets openfps.startInGame, which GdxFrameLoopListener reads in'
    Write-Host 'its constructor. The Android UI has no equivalent seam: entering the world'
    Write-Host 'fires the match gate, and the match gate is also what preloads the weapon'
    Write-Host 'sound -- which needs a live Gdx.audio that does not exist until the frame loop'
    Write-Host 'has started. Starting in-game before that point crashes onCreate.'
    Write-Host ''
    Write-Host 'Tap Single Player. It is one tap, and it is the path a player takes.'
    exit 2
}

# --------------------------------------------------------------------------
# 1. What is about to run
# --------------------------------------------------------------------------
Write-Banner 'OpenFPS -- Android' 'Cyan'

$commit = (& git rev-parse --short HEAD 2>$null)
$branch = (& git rev-parse --abbrev-ref HEAD 2>$null)
$dirty = (& git status --porcelain 2>$null)
if ($dirty)
{
    $treeState = "$(($dirty | Measure-Object).Count) file(s) modified -- NOT what $commit alone contains"
}
else
{
    $treeState = 'clean'
}

# The SDK, from local.properties first: settings.gradle.kts reads that file, and
# a shell started before ANDROID_HOME was set never inherited the variable.
$sdk = $null
$localProps = Join-Path $repo 'local.properties'
if (Test-Path $localProps)
{
    $line = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
    if ($null -ne $line)
    {
        # local.properties is a Java properties file, so the colon and the
        # backslashes in a Windows path arrive escaped.
        $sdk = ($line.Line -replace '^\s*sdk\.dir\s*=', '').Trim().Replace('\\', '\').Replace('\:', ':')
    }
}
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }

Write-Host ('  commit      : {0} ({1})' -f $commit, $branch)
Write-Host ('  working tree: {0}' -f $treeState)
Write-Host ('  started     : {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host ('  sdk         : {0}' -f $sdk)

if (-not $sdk -or -not (Test-Path $sdk))
{
    Write-Banner 'NO ANDROID SDK -- :android would be skipped silently' 'Red'
    Write-Host 'settings.gradle.kts includes :android only when ANDROID_HOME, ANDROID_SDK_ROOT'
    Write-Host 'or local.properties names an SDK. Without one, gradlew exits 0 having built no'
    Write-Host 'Android code at all -- a green build that proves nothing.'
    Write-Host ''
    Write-Host 'Write local.properties at the repository root (it is gitignored):'
    Write-Host '    sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk' -ForegroundColor Yellow
    exit 2
}

$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulatorExe = Join-Path $sdk 'emulator\emulator.exe'
foreach ($tool in @($adb, $emulatorExe))
{
    if (-not (Test-Path $tool))
    {
        Write-Banner "MISSING SDK TOOL: $tool" 'Red'
        Write-Host 'Install it with sdkmanager ("platform-tools", "emulator").'
        exit 2
    }
}

# --------------------------------------------------------------------------
# 2. Art -- a warning here, not an error
# --------------------------------------------------------------------------
# Unlike desktop, a modelless APK still runs: AndroidLauncher reports it and
# comes up as a menu. Worth saying loudly, because "Single Player opens a black
# room" looks like a rendering bug and is not one.
$assetRoot = Join-Path $repo 'assets\models'
$ofmCount = 0
if (Test-Path $assetRoot)
{
    $ofmCount = (Get-ChildItem -Path $assetRoot -Filter '*.ofm' -Recurse -File -ErrorAction SilentlyContinue |
        Measure-Object).Count
}
Write-Host ('  models      : {0} .ofm to stage into the APK' -f $ofmCount)
if ($ofmCount -eq 0)
{
    Write-Host ''
    Write-Host '  WARNING: no models -- this APK will have no world in it.' -ForegroundColor Yellow
    Write-Host '  The menu comes up, Single Player enters an empty room, and logcat says so'
    Write-Host '  at ERROR. Stage the art first if that is not what you want:'
    Write-Host '      .\gradlew.bat :tools:regenerateDemoAssets -PkenneyRaw=C:\path\to\packs' -ForegroundColor Yellow
}

# --------------------------------------------------------------------------
# 3. Build -- every run
# --------------------------------------------------------------------------
$apk = Join-Path $repo 'android\build\outputs\apk\debug\android-debug.apk'

if ($Clean)
{
    Write-Host ''
    Write-Host '  -Clean: deleting :android build output first' -ForegroundColor Yellow
    & .\gradlew.bat ':android:clean' --console=plain -q
    if ($LASTEXITCODE -ne 0)
    {
        Write-Banner "CLEAN FAILED (gradle exit $LASTEXITCODE) -- nothing was installed" 'Red'
        exit $LASTEXITCODE
    }
}

Write-Host ''
Write-Host 'Assembling the debug APK ...' -ForegroundColor Cyan
$buildStart = Get-Date
& .\gradlew.bat ':android:assembleDebug' --console=plain
$buildExit = $LASTEXITCODE
$buildSeconds = [math]::Round(((Get-Date) - $buildStart).TotalSeconds, 1)

if ($buildExit -ne 0)
{
    Write-Banner "BUILD FAILED (gradle exit $buildExit) -- nothing was installed" 'Red'
    Write-Host 'The compiler output is above. The APK on the device is UNCHANGED, so whatever'
    Write-Host 'is running there is the OLD build.'
    exit $buildExit
}

if (-not (Test-Path $apk))
{
    Write-Banner 'BUILD SUCCEEDED BUT THERE IS NO APK' 'Red'
    Write-Host ('  Expected {0}' -f $apk)
    exit 4
}

$apkFile = Get-Item $apk
Write-Host ''
Write-Host ('  build       : OK in {0}s' -f $buildSeconds) -ForegroundColor Green
Write-Host ('  apk         : {0}  {1:N1} MB  built {2}' -f `
    $apkFile.Name, ($apkFile.Length / 1MB), $apkFile.LastWriteTime.ToString('HH:mm:ss'))

if ($NoLaunch)
{
    Write-Host ''
    Write-Host '-NoLaunch: built and verified, touching no device.' -ForegroundColor Yellow
    exit 0
}

# --------------------------------------------------------------------------
# 4. A device to install onto
# --------------------------------------------------------------------------
function Get-ReadyDevice
{
    $lines = & $adb devices 2>$null
    foreach ($line in $lines)
    {
        if ($line -match '^(\S+)\s+device$')
        {
            return $matches[1]
        }
    }
    return $null
}

Write-Host ''
& $adb start-server 2>&1 | Out-Null
$serial = Get-ReadyDevice

if ($serial)
{
    Write-Host ('  device      : {0} (already up -- reusing it)' -f $serial) -ForegroundColor Green
}
elseif ($NoEmulator)
{
    Write-Banner 'NO DEVICE -- and -NoEmulator was given' 'Red'
    Write-Host 'Connect a phone with USB debugging on, or drop -NoEmulator to boot the AVD.'
    exit 5
}
else
{
    $avds = & $emulatorExe -list-avds 2>$null
    if ($avds -notcontains $Avd)
    {
        Write-Banner "NO SUCH AVD: $Avd" 'Red'
        Write-Host 'Available:'
        foreach ($a in $avds) { Write-Host "    $a" }
        Write-Host ''
        Write-Host 'Create the project AVD with:'
        Write-Host ('    avdmanager create avd -n {0} -k "system-images;android-36;google_apis;x86_64" -d pixel_7' -f $Avd) -ForegroundColor Yellow
        Write-Host 'then set hw.gpu.enabled=yes, hw.initialOrientation=landscape and'
        Write-Host 'hw.keyboard=yes in its config.ini -- see BUILD.md.'
        exit 5
    }

    if ($GpuMode -eq 'host')
    {
        Write-Host '  WARNING: -GpuMode host crashes on this machine''s Intel iGPU with' -ForegroundColor Yellow
        Write-Host '  "Failed to find ColorBuffer", and the crash-consent dialog it leaves' -ForegroundColor Yellow
        Write-Host '  behind blocks the NEXT boot until it is dismissed by hand.' -ForegroundColor Yellow
    }

    $emuArgs = @('-avd', $Avd, '-gpu', $GpuMode, '-no-snapshot', '-no-boot-anim', '-no-audio')
    if (-not $Window)
    {
        $emuArgs += '-no-window'
    }
    Write-Host ('  starting    : emulator {0}' -f ($emuArgs -join ' ')) -ForegroundColor Cyan

    # Detached, with its console noise sent to a log rather than into this one:
    # the emulator writes a steady stream of warnings that would bury the boot
    # progress and then the app's own log.
    $emuLog = Join-Path $env:TEMP 'openfps-emulator.log'
    Start-Process -FilePath $emulatorExe -ArgumentList $emuArgs `
        -RedirectStandardOutput $emuLog -RedirectStandardError "$emuLog.err" `
        -WindowStyle Hidden | Out-Null
    Write-Host ('  emulator log: {0}' -f $emuLog)

    Write-Host '  waiting for the device to appear ...'
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    while ($null -eq $serial -and (Get-Date) -lt $deadline)
    {
        Start-Sleep -Seconds 2
        $serial = Get-ReadyDevice
    }
    if ($null -eq $serial)
    {
        Write-Banner "EMULATOR NEVER APPEARED within ${BootTimeoutSeconds}s" 'Red'
        Write-Host ('Read {0} and {0}.err for why.' -f $emuLog)
        Write-Host 'A crash-consent dialog left by an earlier -gpu host run is the usual cause;'
        Write-Host 'it has to be dismissed by hand, or the AVD wiped with  emulator -avd'
        Write-Host ("$Avd -wipe-data.")
        exit 5
    }
    Write-Host ('  device      : {0}' -f $serial) -ForegroundColor Green

    # `adb devices` says "device" as soon as adbd answers, which is well before
    # the framework is up -- an `am start` in that window fails with "Can't find
    # service: activity". sys.boot_completed is the real gate.
    Write-Host '  waiting for sys.boot_completed ...'
    $booted = $false
    while (-not $booted -and (Get-Date) -lt $deadline)
    {
        $prop = (& $adb -s $serial shell getprop sys.boot_completed 2>$null)
        if ("$prop".Trim() -eq '1')
        {
            $booted = $true
            break
        }
        Start-Sleep -Seconds 3
    }
    if (-not $booted)
    {
        Write-Banner "BOOT DID NOT COMPLETE within ${BootTimeoutSeconds}s" 'Red'
        Write-Host ('The device is visible as {0} but the framework never came up.' -f $serial)
        Write-Host ('Read {0} for why. Raise -BootTimeoutSeconds if the machine is loaded.' -f $emuLog)
        exit 5
    }
    Write-Host '  boot        : complete' -ForegroundColor Green
}

# --------------------------------------------------------------------------
# 5. Install and launch
# --------------------------------------------------------------------------
# Force-stopped first, and this is not cosmetic. The Activity reads its launch
# extras in onCreate; a process already running would be brought to the front
# with no onCreate at all, so -RenderMode and -DebugOverlay would appear to be
# ignored. Stopping it also guarantees the NEW code is what starts.
Write-Host ''
Write-Host '  force-stopping any running instance ...'
& $adb -s $serial shell am force-stop $package 2>&1 | Out-Null

Write-Host ('  installing  : {0}' -f $apkFile.Name)
$installOut = & $adb -s $serial install -r $apk 2>&1
if ($LASTEXITCODE -ne 0 -or ($installOut -match 'Failure|Error'))
{
    Write-Banner 'INSTALL FAILED -- the app on the device is still the OLD build' 'Red'
    Write-Host ($installOut -join [Environment]::NewLine)
    Write-Host ''
    Write-Host 'INSTALL_FAILED_UPDATE_INCOMPATIBLE means a differently-signed copy is present;'
    Write-Host ('uninstall it with  adb uninstall {0}  and re-run.' -f $package)
    exit 6
}
Write-Host '  install     : OK' -ForegroundColor Green

# Cleared right before the launch so the tail below starts at this run and not
# at whatever the device has been saying since it booted.
& $adb -s $serial logcat -c 2>&1 | Out-Null

# Intent extras rather than -D: an APK has no command line. AndroidLauncher
# copies each of these into the matching system property before it builds the
# objects that read them -- see LAUNCH_PROPERTIES there. Every name below is in
# that list; one that is not would be dropped on the floor.
$startArgs = @('-s', $serial, 'shell', 'am', 'start', '-W', '-n', $activity)
$extras = @()
if ($RenderMode) { $extras += @('--es', 'openfps.renderMode', $RenderMode) }
if ($DebugOverlay) { $extras += @('--es', 'openfps.debugOverlay', 'true') }
$startArgs += $extras

Write-Host ('  launching   : am start -n {0} {1}' -f $activity, ($extras -join ' ')) -ForegroundColor Cyan
$startOut = & $adb @startArgs 2>&1
Write-Host ($startOut -join [Environment]::NewLine)
if ($startOut -match 'Error')
{
    Write-Banner 'am start REPORTED AN ERROR' 'Red'
    exit 7
}

# The PID is what makes the log filter useful. slf4j-android tags by logger
# name, so the engine's lines arrive as `coed.DemoGameplayPort` and friends
# rather than as `OpenFPS` -- `-s OpenFPS:V` would show the platform classes and
# hide the engine, which is the half you usually want.
$pid_ = $null
for ($i = 0; $i -lt 10 -and $null -eq $pid_; $i++)
{
    $found = (& $adb -s $serial shell pidof $package 2>$null)
    if ("$found".Trim())
    {
        $pid_ = "$found".Trim().Split(' ')[0]
        break
    }
    Start-Sleep -Milliseconds 500
}

if ($pid_)
{
    Write-Host ('  app pid     : {0}' -f $pid_) -ForegroundColor Green
}
else
{
    Write-Host '  app pid     : not found -- the process may have died on launch' -ForegroundColor Yellow
}

# --------------------------------------------------------------------------
# 6. A screenshot, if that is all that was wanted
# --------------------------------------------------------------------------
if ($Screenshot)
{
    Write-Host ''
    Write-Host ('  waiting {0}s for the first frame to reach the surface ...' -f $ScreenshotDelaySeconds)
    Start-Sleep -Seconds $ScreenshotDelaySeconds
    # screencap + pull, never `adb exec-out screencap -p > file`: PowerShell's
    # redirection injects a BOM and rewrites LF as CRLF, which corrupts the PNG.
    & $adb -s $serial shell screencap -p /sdcard/openfps-shot.png
    & $adb -s $serial pull /sdcard/openfps-shot.png $Screenshot
    & $adb -s $serial shell rm -f /sdcard/openfps-shot.png
    if (Test-Path $Screenshot)
    {
        Write-Host ('  screenshot  : {0}' -f (Resolve-Path $Screenshot)) -ForegroundColor Green
        Write-Host '  That is the MENU. To photograph the world -- and with -DebugOverlay the'
        Write-Host '  RES line that proves the render mode took effect -- tap Single Player and'
        Write-Host '  capture again:'
        Write-Host ('    adb -s {0} shell input tap 1200 358' -f $serial) -ForegroundColor Yellow
        Write-Host ('    adb -s {0} shell screencap -p /sdcard/x.png' -f $serial) -ForegroundColor Yellow
        Write-Host ('    adb -s {0} pull /sdcard/x.png world.png' -f $serial) -ForegroundColor Yellow
        Write-Host '  (1200 358 is the first menu button on a 2400x1080 landscape panel.)'
    }
    else
    {
        Write-Host '  screenshot  : FAILED -- nothing was pulled' -ForegroundColor Red
        exit 8
    }
    exit 0
}

# --------------------------------------------------------------------------
# 7. Logcat -- the same diagnostics the desktop console gives
# --------------------------------------------------------------------------
if ($NoLogcat)
{
    Write-Host ''
    Write-Host ('-NoLogcat: running on {0}. Read the log yourself with:' -f $serial) -ForegroundColor Yellow
    Write-Host ('    adb -s {0} logcat --pid={1}' -f $serial, $pid_)
    exit 0
}

Write-Banner "logcat -- commit $commit, $treeState (Ctrl+C to stop)" 'Cyan'
if ($pid_)
{
    & $adb -s $serial logcat --pid=$pid_ -v brief
}
else
{
    # No pid to filter on, so fall back to the platform tag plus anything that
    # died. Better than the unfiltered firehose when the app crashed on launch.
    & $adb -s $serial logcat -v brief OpenFPS:V AndroidRuntime:E ActivityManager:W '*:S'
}
