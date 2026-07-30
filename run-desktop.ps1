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

.PARAMETER TwoPeers
    Launches TWO instances wired to each other on localhost, which is the whole
    of setting up a multiplayer test. Player 1 binds -BasePort and player 2 binds
    the port above it; each gets its own profile database, and each gets its own
    screenshot path when -Screenshot is given.

    It does NOT use `gradlew :desktop:run` twice. Two concurrent Gradle
    invocations in one project directory contend on Gradle's own locks, so the
    second peer would block until the first exited -- which is exactly never for
    a game you are playing. Instead this builds an installDist image once and
    starts two plain JVMs from it. That also side-steps the -D problem the rest
    of this script has to work around, because a property on a `java` command
    line reaches the application by definition.

    Keyboard and mouse go to whichever window has focus -- that is the OS, not
    the game. Click between them to drive each peer in turn.

.PARAMETER BasePort
    The UDP port player 1 binds under -TwoPeers; player 2 takes the next one up.
    Default 5021, which is Constants.DEFAULT_NET_PORT.

.PARAMETER Screenshot
    Captures the window to this PNG and exits, instead of waiting for you to
    close it. Pair with -DebugOverlay to photograph the RES and RENDER figures --
    that is how the render mode is confirmed without a human at the keyboard.

.PARAMETER ScreenshotFrame
    Which frame -Screenshot captures. Default 90, about a second and a half in,
    which is long enough for the first frame's texture upload to settle.

.PARAMETER ScreenshotCount
    How many CONSECUTIVE frames to capture, starting at -ScreenshotFrame.
    Default 1. Above 1 the files get a -0001 index before the extension.

    This is the only honest way to photograph anything that moves. A tracer
    lives 8 tics and a puff of smoke 36, so whether either is perceptible is a
    question about a sequence -- and launching the game once per frame does not
    answer it, because each launch is a separate process and "frame 300" of one
    run is not adjacent to "frame 301" of the next.

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

.EXAMPLE
    .\run-desktop.ps1 -TwoPeers -StartInGame
    Two windows, already talking to each other, both in the world. Walk in one
    and watch the body move in the other.
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

    [switch] $TwoPeers,

    [int] $BasePort = 5021,

    [string] $Screenshot,

    [int] $ScreenshotFrame = 90,

    [int] $ScreenshotCount = 1,

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

# -TwoPeers assigns both identities and both ports itself, which is the entire
# point of it. Honouring a -Net or -Peer alongside would mean two sources
# deciding who player 1 is, and the losing one would fail as a bind conflict or
# as a peer that never answers -- neither of which names its own cause.
if ($TwoPeers -and ($Net -or $Peer))
{
    Write-Host '-TwoPeers sets --net and --peer for both instances itself.' -ForegroundColor Red
    Write-Host 'Drop -Net/-Peer, or drop -TwoPeers and launch each peer yourself.' -ForegroundColor Yellow
    exit 2
}
if ($TwoPeers -and $Model)
{
    # --model replaces the demo world with a single model on an orbit camera, and
    # DesktopLauncher attaches no gameplay port for it -- so there is no match to
    # network and openNetwork returns null. Two of those would be two unconnected
    # model viewers, reported here rather than discovered from a silent log.
    Write-Host '-TwoPeers needs the demo world; -Model has no match to network.' -ForegroundColor Red
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

# -TwoPeers launches plain JVMs rather than `gradlew :desktop:run`, so it needs a
# runnable image with every dependency jar beside it. installDist produces exactly
# that and depends on classes, so the freshness evidence below is unaffected.
if ($TwoPeers)
{
    $compileTask = ':desktop:installDist'
}
else
{
    $compileTask = ':desktop:classes'
}

Write-Host ''
Write-Host "Compiling :engine, :gdxshared, :desktop ($compileTask) ..." -ForegroundColor Cyan
$buildStart = Get-Date
& .\gradlew.bat $compileTask --console=plain
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

# A -Screenshot path is relative to where the CALLER was standing, not to the
# repository root this script moved to. Resolved once here because both launch
# paths below need it, and resolving it twice is how they would come to disagree.
$shotBase = $null
if ($Screenshot)
{
    $shotBase = $Screenshot
    if (-not [System.IO.Path]::IsPathRooted($shotBase))
    {
        $shotBase = Join-Path $callerDir $shotBase
    }
}

# --------------------------------------------------------------------------
# 5a. Two peers, wired to each other
# --------------------------------------------------------------------------
# Deliberately NOT two `gradlew :desktop:run` invocations. Two concurrent Gradle
# builds in one project directory serialise on Gradle's locks, so the second peer
# would sit waiting for the first to exit -- which for a game you are playing is
# never. Running the installDist image directly also means a -D reaches the
# application rather than the daemon, so the forwarding list in
# desktop/build.gradle.kts is not involved at all on this path.
if ($TwoPeers)
{
    $libDir = Join-Path $repo 'desktop\build\install\desktop\lib'
    if (-not (Test-Path $libDir))
    {
        Write-Banner 'NO RUNNABLE IMAGE -- installDist produced no lib directory' 'Red'
        Write-Host ('  Expected jars under {0}.' -f $libDir)
        exit 5
    }
    # Per-peer scratch. Each peer needs its OWN profile database: both processes
    # resolve ~/.openfps/profile.db by default, and two SQLite writers on one file
    # is a corruption risk that surfaces on some later launch rather than now.
    $peerDir = Join-Path $repo 'desktop\build\net-peers'
    New-Item -ItemType Directory -Force $peerDir | Out-Null

    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')))
    {
        $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    else
    {
        $javaExe = 'java'
    }

    $peerTwoPort = $BasePort + 1
    Write-Host ''
    Write-Host ('  two peers   : player 1 on UDP {0}, player 2 on UDP {1}' -f `
        $BasePort, $peerTwoPort) -ForegroundColor Cyan
    Write-Host ('  java        : {0}' -f $javaExe)
    Write-Host ('  profiles    : {0}' -f $peerDir)
    Write-Host '  input       : goes to whichever window has focus -- click between them.'
    Write-Host ''

    $started = @()
    foreach ($id in 1, 2)
    {
        if ($id -eq 1)
        {
            $myPort = $BasePort
            $otherId = 2
            $otherPort = $peerTwoPort
        }
        else
        {
            $myPort = $peerTwoPort
            $otherId = 1
            $otherPort = $BasePort
        }

        $jvmArgs = @('-cp', (Join-Path $libDir '*'))
        # Its own database, named after the peer so a stale one is obvious.
        $jvmArgs += ('-Dopenfps.profile.db=' + (Join-Path $peerDir "peer$id-profile.db"))
        if ($RenderMode) { $jvmArgs += "-Dopenfps.renderMode=$RenderMode" }
        if ($RenderFilter) { $jvmArgs += "-Dopenfps.renderFilter=$RenderFilter" }
        if ($DebugOverlay) { $jvmArgs += '-Dopenfps.debugOverlay=true' }
        if ($PSBoundParameters.ContainsKey('FpsLog')) { $jvmArgs += "-Dopenfps.fpsLog=$FpsLog" }
        if ($PSBoundParameters.ContainsKey('Workers')) { $jvmArgs += "-Dopenfps.workers=$Workers" }
        if ($Screenshot)
        {
            # One path per peer, for the same reason as the database: a shared path
            # means whichever process wrote second is the only capture that
            # survives, and the two would look like one run.
            $stem = [IO.Path]::GetFileNameWithoutExtension($shotBase)
            $ext = [IO.Path]::GetExtension($shotBase)
            $peerShot = Join-Path (Split-Path $shotBase -Parent) ("{0}-peer{1}{2}" -f $stem, $id, $ext)
            $jvmArgs += "-Dopenfps.screenshot=$peerShot"
            $jvmArgs += "-Dopenfps.screenshotFrame=$ScreenshotFrame"
            $jvmArgs += "-Dopenfps.screenshotCount=$ScreenshotCount"
            $jvmArgs += '-Dopenfps.screenshotExit=true'
        }
        $jvmArgs += 'com.openfps.desktop.DesktopLauncher'
        if ($Fps) { $jvmArgs += "--fps=$Fps" }
        if ($StartInGame) { $jvmArgs += '--start-in-game' }
        if ($Assets) { $jvmArgs += "--assets=$Assets" }
        $jvmArgs += "--net=$($id):$myPort"
        $jvmArgs += "--peer=$otherId@127.0.0.1:$otherPort"

        Write-Host ('  peer {0}      : --net={0}:{1} --peer={2}@127.0.0.1:{3}' -f `
            $id, $myPort, $otherId, $otherPort)
        # WorkingDirectory is the repository root, because assets/models is
        # resolved relative to it exactly as :desktop:run's workingDir arranges.
        $started += Start-Process -FilePath $javaExe -ArgumentList $jvmArgs `
            -WorkingDirectory $repo -PassThru
    }

    Write-Host ''
    Write-Host ('Both peers started (pids {0}).' -f (($started | ForEach-Object { $_.Id }) -join ', ')) `
        -ForegroundColor Green
    Write-Host 'Waiting for both windows to close.'
    $started | Wait-Process
    $peerExits = ($started | ForEach-Object { $_.ExitCode }) -join ', '
    Write-Host ''
    Write-Host ('Both peers exited (codes {0}). That was commit {1} ({2}).' -f `
        $peerExits, $commit, $treeState) -ForegroundColor Green
    foreach ($proc in $started)
    {
        if ($proc.ExitCode -ne 0)
        {
            Write-Banner "A peer exited non-zero ($($proc.ExitCode))" 'Yellow'
            Write-Host 'DesktopLauncher exit codes: 3 = no demo art, 4 = bad --net/--peer.'
            exit $proc.ExitCode
        }
    }
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
    # Already made absolute above, because :desktop:run's working directory is the
    # repository root rather than wherever the caller happened to be standing.
    $shotPath = $shotBase
    $props += "-Dopenfps.screenshot=$shotPath"
    $props += "-Dopenfps.screenshotFrame=$ScreenshotFrame"
    $props += "-Dopenfps.screenshotCount=$ScreenshotCount"
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
    # A burst numbers its files, so what to look for depends on the count. The
    # pattern is built the same way GdxScreenshot.fileFor builds the names:
    # index before the extension, so the results are still .png files.
    $shotGlob = $shotPath
    if ($ScreenshotCount -gt 1)
    {
        $shotDir = Split-Path $shotPath -Parent
        $stem = [IO.Path]::GetFileNameWithoutExtension($shotPath)
        $ext = [IO.Path]::GetExtension($shotPath)
        $shotGlob = Join-Path $shotDir ("{0}-*{1}" -f $stem, $ext)
    }
    $shotsWritten = @(Get-ChildItem $shotGlob -ErrorAction SilentlyContinue)
    if ($shotsWritten.Count -gt 0)
    {
        Write-Host ''
        Write-Host ('  screenshot  : {0} file(s) matching {1}' -f $shotsWritten.Count, $shotGlob) -ForegroundColor Green
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
