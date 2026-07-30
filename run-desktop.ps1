<#
.SYNOPSIS
    Rebuilds OpenFPS and launches the desktop game, proving what it launched.

.DESCRIPTION
    This script exists because of one specific failure: reporting a feature as
    "not there" while running a build that predates it. Every step below is aimed
    at that.

      * It recompiles on every invocation. There is no path through this script
        that launches without compiling first.
      * It prints the freshness evidence rather than asking you to take it on
        trust: the newest source timestamp beside the newest compiled-class
        timestamp. No compiled output at all refuses to launch.
      * It prints the commit, the working-tree state and the build result BEFORE
        the window opens, so what is on screen is always identifiable.
      * A failed compile stops the script. It never falls back to an old binary.

    Every option is passed through to the running game rather than needing a
    file edited. Options that map to a -D system property are only offered here
    if desktop/build.gradle.kts actually forwards that property into the forked
    JVM: a -D on a Gradle command line lands on the DAEMON, so an unforwarded
    property would look accepted and do nothing.

.PARAMETER RenderMode
    Internal render resolution: 480p (default), 720p or native. -DebugOverlay
    shows the resulting framebuffer size on the RES line, which is how you
    confirm it took effect.

.PARAMETER RenderFilter
    How the finished frame is blitted up to the window: linear (default, smooth)
    or nearest (crisp and blocky).

.PARAMETER DebugOverlay
    Starts with the corner diagnostic overlay on: FRAME, RENDER and RES.

.PARAMETER StartInGame
    Skips the main menu and opens straight into the world. A --args flag rather
    than a -D, because that is the only channel that reaches the application.

.PARAMETER Fps
    Simulation tic rate: 30, 60 (default) or 120.

.PARAMETER FpsLog
    Seconds between FramebufferPresenter's three-rate log lines (platform,
    presented, rendered). Omitted, the log is off.

.PARAMETER Workers
    Pins the render worker pool size instead of sizing it from the CPU count.

.PARAMETER Assets
    Model root. Defaults to assets/models.

.PARAMETER Model
    Draws a single .ofm file on the orbit camera instead of the demo room.

.PARAMETER Net
    This peer's identity and local UDP port, as <playerId>:<port>. 0 asks the
    OS for a free port.

.PARAMETER Peer
    A peer to connect to, as <playerId>@<host>:<port>. May be repeated.

.PARAMETER Screenshot
    Captures the window to this PNG and exits, instead of waiting for you to
    close it. Pair with -DebugOverlay to photograph the RES and RENDER figures --
    that is how the render mode is confirmed without a human at the keyboard.

.PARAMETER ScreenshotFrame
    Which frame -Screenshot captures. Default 90, about a second and a half in,
    which is long enough for the first frame's texture upload to settle.

.PARAMETER Clean
    Deletes the compiled output first, so the recompile is from scratch. Slower;
    for when you suspect the build itself rather than the code.

.PARAMETER NoLaunch
    Builds and reports, then stops without opening a window.

.PARAMETER Help
    Prints this option list. `--help` and `-h` work too.

.EXAMPLE
    .\run-desktop.ps1
    Rebuild and play.

.EXAMPLE
    .\run-desktop.ps1 -StartInGame -DebugOverlay -RenderMode native
    Straight into the world at the window's own resolution, with the overlay on
    so RES and RENDER are visible.

.EXAMPLE
    .\run-desktop.ps1 -Clean -FpsLog 2
    Full recompile, then log the three frame rates every two seconds.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    # PositionalBinding above is $false so that a bare `--help` lands in $Rest
    # rather than being bound to the first parameter and rejected by its
    # ValidateSet. Anything unnamed is then an error we can report ourselves.

    [ValidateSet('480p', '720p', 'native')]
    [string] $RenderMode,

    [ValidateSet('linear', 'nearest')]
    [string] $RenderFilter,

    [switch] $DebugOverlay,

    [switch] $StartInGame,

    [ValidateSet('30', '60', '120')]
    [string] $Fps,

    [int] $FpsLog,

    [int] $Workers,

    [string] $Assets,

    [string] $Model,

    [string] $Net,

    [string[]] $Peer,

    [string] $Screenshot,

    [int] $ScreenshotFrame = 90,

    [switch] $Clean,

    [switch] $NoLaunch,

    [switch] $Help,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest
)

# Deliberately NOT 'Stop'.
#
# Windows PowerShell wraps every stderr line a native executable writes into an
# ErrorRecord, and under 'Stop' the first one terminates the script. Gradle
# writes compiler diagnostics to stderr, so 'Stop' turned a failed build into a
# NativeCommandError raised at the `& .\gradlew.bat` line -- a PowerShell stack
# trace in place of the "BUILD FAILED, nothing was launched" banner below, which
# is the single most important message this script prints. Every external call
# here has its $LASTEXITCODE checked explicitly instead.
$ErrorActionPreference = 'Continue'

# --------------------------------------------------------------------------
# Help, including the two spellings PowerShell does not bind itself
# --------------------------------------------------------------------------
if ($Help -or ($Rest -contains '--help') -or ($Rest -contains '-h') -or ($Rest -contains '/?'))
{
    Get-Help -Full $PSCommandPath
    exit 0
}
if ($Rest)
{
    Write-Host "Unrecognised argument: $($Rest -join ' ')" -ForegroundColor Red
    Write-Host "Run  .\run-desktop.ps1 --help  for the option list." -ForegroundColor Yellow
    exit 2
}

# Run from the repository root whatever directory the caller was in. gradlew.bat
# and assets/models are both resolved relative to it. The caller's directory is
# kept first, because a relative -Screenshot path means "relative to where I am
# standing" and would otherwise silently land at the repository root.
$callerDir = (Get-Location).Path
$repo = $PSScriptRoot
Set-Location $repo

function Write-Banner([string] $text, [string] $colour)
{
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor $colour
    Write-Host "  $text" -ForegroundColor $colour
    Write-Host ('=' * 72) -ForegroundColor $colour
}

function Get-NewestWriteTime([string] $path, [string] $filter)
{
    if (-not (Test-Path $path))
    {
        return $null
    }
    $newest = Get-ChildItem -Path $path -Filter $filter -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $newest)
    {
        return $null
    }
    return $newest
}

# --------------------------------------------------------------------------
# 1. What is about to run
# --------------------------------------------------------------------------
Write-Banner 'OpenFPS -- desktop' 'Cyan'

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

Write-Host ('  commit      : {0} ({1})' -f $commit, $branch)
Write-Host ('  working tree: {0}' -f $treeState)
Write-Host ('  started     : {0}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Host ('  java        : {0}' -f $env:JAVA_HOME)

# --------------------------------------------------------------------------
# 2. Art, checked before the build rather than after the exit code
# --------------------------------------------------------------------------
# DesktopLauncher exits 3 when the demo has no geometry, and Gradle reports that
# as "finished with non-zero exit value 3" -- a number nobody should have to look
# up. Checked here so the message names the task that fixes it.
if ($Assets)
{
    $assetRoot = $Assets
}
else
{
    $assetRoot = Join-Path $repo 'assets\models'
}
if (-not $Model)
{
    $ofmCount = 0
    if (Test-Path $assetRoot)
    {
        $ofmCount = (Get-ChildItem -Path $assetRoot -Filter '*.ofm' -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object).Count
    }
    Write-Host ('  models      : {0} .ofm under {1}' -f $ofmCount, $assetRoot)
    if ($ofmCount -eq 0)
    {
        Write-Banner 'NO DEMO ART -- the game would exit with code 3, not open a window' 'Red'
        Write-Host 'The demo art is not in the repository; assets/models is gitignored.'
        Write-Host 'Convert the two CC0 Kenney packs named in docs/DEMO_ASSETS.md first:'
        Write-Host ''
        Write-Host '    .\gradlew.bat :tools:regenerateDemoAssets -PkenneyRaw=C:\path\to\unzipped\packs' -ForegroundColor Yellow
        Write-Host ''
        Write-Host 'Without -PkenneyRaw the task still succeeds and emits a 60-triangle greybox'
        Write-Host 'room with no weapon -- a room to stand in, not the demo.'
        Write-Host 'Or pass -Model <file>.ofm to look at a single converted model instead.'
        exit 3
    }
}

# --------------------------------------------------------------------------
# 3. Compile -- every run, no exceptions
# --------------------------------------------------------------------------
$classesDir = Join-Path $repo 'desktop\build\classes\java\main'

if ($Clean)
{
    Write-Host ''
    Write-Host '  -Clean: deleting compiled output first' -ForegroundColor Yellow
    & .\gradlew.bat ':engine:clean' ':gdxshared:clean' ':desktop:clean' --console=plain -q
    if ($LASTEXITCODE -ne 0)
    {
        Write-Banner "CLEAN FAILED (gradle exit $LASTEXITCODE) -- nothing was launched" 'Red'
        exit $LASTEXITCODE
    }
}

Write-Host ''
Write-Host 'Compiling :engine, :gdxshared, :desktop ...' -ForegroundColor Cyan
$buildStart = Get-Date
& .\gradlew.bat ':desktop:classes' --console=plain
$buildExit = $LASTEXITCODE
$buildSeconds = [math]::Round(((Get-Date) - $buildStart).TotalSeconds, 1)

if ($buildExit -ne 0)
{
    Write-Banner "BUILD FAILED (gradle exit $buildExit) -- nothing was launched" 'Red'
    Write-Host 'The compiler output is above. The game was NOT started, so whatever is'
    Write-Host 'on your screen from an earlier run is an OLD build.'
    Write-Host ''
    Write-Host 'Compilation uses -Werror, so a warning is a failure. Fix it and re-run.'
    exit $buildExit
}

# --------------------------------------------------------------------------
# 4. Freshness, shown rather than assumed
# --------------------------------------------------------------------------
# The compile above is what guarantees freshness -- Gradle compares source
# CONTENT, not timestamps, so "up-to-date" means the classes really do match the
# sources. What this section adds is EVIDENCE, printed where the user is already
# looking: the two timestamps side by side.
#
# An absent classes directory is a genuine failure and refuses to launch. A
# source file merely newer than every class is only a warning, because the
# common cause is innocent -- reverting an edit leaves the file's mtime in the
# future of a build Gradle correctly declined to redo -- and refusing to start
# the game over that would be a worse bug than the one this script prevents.
$newestSource = $null
foreach ($module in @('engine', 'gdxshared', 'desktop'))
{
    $candidate = Get-NewestWriteTime (Join-Path $repo "$module\src\main\java") '*.java'
    if ($null -ne $candidate -and ($null -eq $newestSource -or
        $candidate.LastWriteTimeUtc -gt $newestSource.LastWriteTimeUtc))
    {
        $newestSource = $candidate
    }
}
$newestClass = Get-NewestWriteTime $classesDir '*.class'

Write-Host ''
Write-Host ('  build       : OK in {0}s' -f $buildSeconds) -ForegroundColor Green
if ($null -ne $newestSource)
{
    Write-Host ('  newest src  : {0}  {1}' -f `
        $newestSource.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), $newestSource.Name)
}
if ($null -ne $newestClass)
{
    Write-Host ('  newest class: {0}  {1}' -f `
        $newestClass.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'), $newestClass.Name)
}

if ($null -eq $newestClass)
{
    Write-Banner 'NO COMPILED OUTPUT -- the build reported success but produced no classes' 'Red'
    Write-Host ('  Nothing matching *.class under {0}.' -f $classesDir)
    Write-Host '  Refusing to launch. Re-run with -Clean; if that also produces nothing,'
    Write-Host '  the build script itself is at fault rather than the code.'
    exit 4
}

if ($null -ne $newestSource -and $newestSource.LastWriteTimeUtc -gt $newestClass.LastWriteTimeUtc)
{
    Write-Host ''
    Write-Host ('  NOTE: {0} is newer than every compiled class.' -f $newestSource.Name) `
        -ForegroundColor Yellow
    Write-Host '        Gradle compares file CONTENT, so this normally means an edit was'
    Write-Host '        reverted and there was nothing to recompile. If you doubt it, the'
    Write-Host '        answer is  .\run-desktop.ps1 -Clean  -- that settles it in one run.'
}

# --------------------------------------------------------------------------
# 5. Launch
# --------------------------------------------------------------------------
if ($NoLaunch)
{
    Write-Host ''
    Write-Host '-NoLaunch: built and verified, not starting the game.' -ForegroundColor Yellow
    exit 0
}

# Application arguments. DesktopLauncher parses these; --args is passed to the
# forked JVM verbatim, which is why --start-in-game is a flag and not a -D.
$appArgs = @()
if ($Fps) { $appArgs += "--fps=$Fps" }
if ($StartInGame) { $appArgs += '--start-in-game' }
if ($Assets) { $appArgs += "--assets=$Assets" }
if ($Model) { $appArgs += "--model=$Model" }
if ($Net) { $appArgs += "--net=$Net" }
foreach ($p in $Peer) { $appArgs += "--peer=$p" }

# System properties. EVERY name here appears in the forwarding list in
# desktop/build.gradle.kts -- check there before adding one, or it will reach the
# Gradle daemon and never the game.
$props = @()
if ($RenderMode) { $props += "-Dopenfps.renderMode=$RenderMode" }
if ($RenderFilter) { $props += "-Dopenfps.renderFilter=$RenderFilter" }
if ($DebugOverlay) { $props += '-Dopenfps.debugOverlay=true' }
if ($PSBoundParameters.ContainsKey('FpsLog')) { $props += "-Dopenfps.fpsLog=$FpsLog" }
if ($PSBoundParameters.ContainsKey('Workers')) { $props += "-Dopenfps.workers=$Workers" }
if ($Screenshot)
{
    # Absolute, because :desktop:run's working directory is the repository root
    # rather than wherever the caller happened to be standing.
    $shotPath = $Screenshot
    if (-not [System.IO.Path]::IsPathRooted($shotPath))
    {
        $shotPath = Join-Path $callerDir $shotPath
    }
    $props += "-Dopenfps.screenshot=$shotPath"
    $props += "-Dopenfps.screenshotFrame=$ScreenshotFrame"
    # Exit once the frame is written. A capture run that then sat waiting for a
    # window to be closed would hang any script or task that called this one.
    $props += '-Dopenfps.screenshotExit=true'
}

$gradleArgs = @(':desktop:run')
if ($appArgs.Count -gt 0)
{
    # One --args, quoted as a single token: PowerShell 5.1 splits an unquoted
    # --args=a b into two arguments and Gradle then reports a nonexistent task.
    $gradleArgs += "--args=$($appArgs -join ' ')"
}
$gradleArgs += $props

Write-Host ''
Write-Host ('  launching   : .\gradlew.bat {0}' -f ($gradleArgs -join ' ')) -ForegroundColor Cyan
if ($props.Count -eq 0 -and $appArgs.Count -eq 0)
{
    Write-Host '  options     : none -- defaults (480p, no overlay, menu first)'
}
Write-Host ''

& .\gradlew.bat @gradleArgs --console=plain
$runExit = $LASTEXITCODE

if ($runExit -ne 0)
{
    Write-Banner "The game exited non-zero (gradle exit $runExit)" 'Yellow'
    Write-Host 'DesktopLauncher exit codes: 3 = no demo art (see above), 4 = bad --net/--peer.'
    Write-Host 'Gradle reports them as "finished with non-zero exit value <n>".'
    exit $runExit
}

if ($Screenshot)
{
    if (Test-Path $shotPath)
    {
        Write-Host ''
        Write-Host ('  screenshot  : {0}' -f (Resolve-Path $shotPath)) -ForegroundColor Green
    }
    else
    {
        Write-Banner 'NO SCREENSHOT WAS WRITTEN' 'Red'
        Write-Host ('  Expected {0}. GdxScreenshot disables itself when the path is unset,' -f $shotPath)
        Write-Host '  so an empty file here means the frame number was never reached --'
        Write-Host '  lower -ScreenshotFrame, or check the log above for a load failure.'
        exit 8
    }
}

Write-Host ''
Write-Host ('OpenFPS exited normally. That was commit {0} ({1}).' -f $commit, $treeState) -ForegroundColor Green
