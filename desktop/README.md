# `:desktop` — the windowed launcher

> The only module allowed to touch LWJGL3 and GLFW. It gives the engine a real
> window, a real mouse, and somewhere to put the pixels the software rasterizer
> produced.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING |
| **Phase** | 1.5 complete (§ 7); presentation landed with Phase 5 |
| **Tests** | 151 |
| **Registered** | provides the windowed HAL (`GdxAdapterFactory`) and the `main` behind `gradlew :desktop:run` |
| **Verified** | 2026-07-28 |

**Built.** `GdxWindowPort` (LWJGL3, 1280x720 default) with an explicit state
machine, `GdxInputPort` for mouse-look, WASD and a gamepad, `GlfwGamepad` behind
the `GamepadSource` seam, `DesktopBindings` (the only
file here that names a key or a pad button), `GdxFrameLoopListener` bridging libGDX's
`ApplicationListener` to the engine's `I_FrameCallback`, `GdxScreenshot` for
reading the window back off the GPU, `WindowIcon` and `IconFileMain` for the
native launcher's identity, `NetArgs` for `--net=`/`--peer=`, `GdxAdapterFactory`
decorating the headless desktop HAL, and `DesktopLauncher` on top of all of it.

**Moved out on 2026-07-28.** `FramebufferPresenter`, `InputAccumulator`,
`UiState`, `UiStateMachine`, the block welcome screen and the menu command
interface now live in **`:gdxshared`**, because `:android` needs every one of
them and none of them names a backend. Nothing changed about what they do; the
test count here dropped from 195 to 118 because 77 tests went with them. See
`gdxshared/README.md`.

**Not built.** Nothing outstanding for Phases 1.5 and 5. Start Game records intent
only — there is no map loader. Settings opens a real screen: an **Accessibility**
group holding the target outline (on by default, and the launcher pushes that
value into `SoftwareRenderPort` at composition time so the label cannot disagree
with the game), and a **Display &amp; Diagnostics** group holding the render mode
and the frame counter.

**Blocked on.** Nothing.

**Next step.** Nothing outstanding — this module follows the renderer. The two
open Phase 5 items (`render/README.md`) both surface here when they land.

## Why the window and input are not in `hal/adapter/desktop/`

They need libGDX, and `:engine` must stay platform-free — the module CI builds
and tests on a machine with no display and no Android SDK. So
`hal/adapter/desktop/` holds only what the JDK can satisfy alone
(`DesktopTimePort` on `System.nanoTime()`, `DesktopDatagramPort` on
`java.nio.channels.DatagramChannel`) and `DesktopAdapterFactory` returns
`NullWindowPort` / `NullInputPort`. PLAN.md § 7 calls that a "known seam"; it is
the design, not a gap.

`GdxAdapterFactory` closes it by **decoration, not a new backend**.
`AdapterFactorySelector` lives in `:engine` and could never name a `:desktop`
class without a module cycle, so there is no `HalBackend.DESKTOP_WINDOWED`.
The factory delegates every port `DesktopAdapterFactory` already gets right and
overrides exactly two. Window and input are overridden *together* because they
are one device: mouse-look needs a captured cursor, which only exists inside a
real window.

## Accumulate on one thread, latch on the other

Two clocks read the mouse and they do not agree. `GdxInputPort.pollDevice()`
runs on the LWJGL3 render thread once per presented frame — vsync-driven, so
60 Hz here and 144 Hz there, irregular whenever a frame runs long. `sampleInput`
runs on the game-loop thread once per tic at a fixed 30, 60 or 120 Hz. Neither
rate divides the other.

Sampling the mouse instantaneously at latch time is wrong in both directions. If
the render thread is faster, frames between two tics are discarded and the view
turns slower than the hand moved. If it is slower, the same unconsumed delta is
read twice and the view turns twice as far. **Relative motion is an integral,
not a level, and an integral must be consumed exactly once.**

So look motion is summed into an `AtomicInteger` of raw pixels and `latch()`
drains it with `getAndSet(0)`: every pixel counted once, in exactly one tic,
whatever the two rates are. Pixels rather than radians because integers make the
accumulator lock-free and exact — the single multiply by `radiansPerPixel`
happens once, at latch. Movement keys are stored as levels (a held key is still
held) and actions as level *plus* sticky flag, so a click shorter than one tic
still arrives.

The payoff is testability: `InputAccumulator` imports no platform type, so all
29 of its tests run headless. `GdxInputPort` keeps only the thin "ask GLFW what
the mouse did" layer that no test can cover.

## A controller, and why a stick is not a mouse

**A stick must not go through the pixel accumulator above.** A mouse reports a
displacement; a held stick reports a *position*, meaning "keep turning at this
rate". Summing it per poll would tie turn speed to vsync — 144 Hz would spin
twice as fast as 72 for the same thumb. So stick look is a level, converted once
per tic as `deflection × rad/s × ticDuration`, which is why `DesktopLauncher`
tells the port the configured frame rate. See `gdxshared/README.md`.

**No gamepad library is shipped.** `gdx-controllers`' desktop backend is Jamepad
and therefore SDL — megabytes of native library per platform to reach the SDL
mapping database GLFW already has loaded in this very process. `GlfwGamepad`
calls GLFW's copy: six functions, no new dependency, distribution unchanged.
`NOTICE` records that decision and the mapping table's provenance.

It is an **extra path, never a mode**. Keyboard, mouse and pad are all polled
every frame into the same accumulator, so you can walk with the left stick, aim
with the mouse and fire with a trigger in one tic. Pad *buttons* resolve through
the ordinary `ActionBindings` lookup (alternates, not chords — "left mouse OR
right trigger" is just a row); pad *sticks* go into a second accumulator channel
that is summed with the keyboard's. A stick axis reports "not held" on purpose,
so `MOVE_FORWARD` naming the left stick — which it does, so the scheme stays
reportable — does not also read as a held key whenever the player strafes.

Hot-plug is the failure that matters: a pad unplugged at full deflection would
leave a level nothing ever overwrites, walking the player into a wall forever.
`pollGamepad` clears the gamepad channel alone, leaving the keyboard the player
still has a hand on. `GamepadSource` exists so that path is an ordinary unit test
rather than something only a human with a USB cable can check.

## `DesktopLauncher` is a composition root

`STYLE.md` § 1.1 lets a composition root name concrete types, and this one does:
it constructs `SoftwareRenderPort`, `GdxWindowPort`, `DemoScene` and
`DemoGameplayPort` by name. The wiring has to be spelled out somewhere, and
confining it to one class is what keeps everything else here on interfaces.

It uses `start` / `awaitPlatformLoop` / `stop` rather than `EngineMain.run`,
because the renderer must be attached to the window in the gap between the
bootstrap and the frame loop: it does not exist before `start` (it needs the
worker pool) and it is too late once `runFrameLoop` has built its listener.

**Missing assets exit, they do not open a window.** The demo is built *before*
`GdxWindowPort` is constructed, so a `DemoAssetException` is logged and followed
by `System.exit(EXIT_NO_ASSETS)` — status **3**. A missing model set should
report at a console the user is already looking at, not behind a black GLFW
window they then have to close to read the reason.

## Arguments

| Argument | Effect |
|---|---|
| `--fps=30\|60\|120` | simulation rate, default 60 (parsed by `EngineMain.parseFpsArg`) |
| `--assets=<dir>` | model root for the demo scene, default `assets/models` |
| `--model=<path>` | draw one `.ofm` on the orbit camera instead of the demo |

`--no-sqlite` and `--headless` are **`:engine:run` arguments only**.
`DesktopLauncher` never parses them: it always supplies its own
`GdxAdapterFactory`, so there is no null-backend or profile-backend choice left
to make.

Diagnostics are system properties rather than arguments, and `:desktop`'s
`build.gradle.kts` forwards them explicitly because a `-D` on the Gradle command
line lands on the daemon and never reaches the forked JVM:
`-Dopenfps.fpsLog=2` logs platform, presented and rendered frame rates every two
seconds — the only way to measure the *windowed* rate — and
`-Dopenfps.screenshot=<path>` writes a frame to PNG.
`-Dopenfps.workers=N` pins the worker pool, which `ThreadPoolFactory` otherwise
sizes from the processor count; the boot log says which of the two happened.

Two of them **script the player**, and they exist because an unattended capture
run has no hands: `GdxInputPort.pollDevice` refuses to read the device unless the
cursor is caught and the window focused, neither of which a screenshot run can
promise. `-Dopenfps.autoWalkTics=N` (with `autoWalkForward` / `autoWalkStrafe`)
holds a movement axis, which is what makes collision photographable;
`-Dopenfps.autoFireTics=N` holds the **trigger**, which is what makes a kill
streak photographable. The walk takes precedence while it lasts, so the pair
reads as a sequence — line a target up, then stand still and shoot. Both are off
by default and both log loudly when on, because a run that moves or fires by
itself looks like a stuck key rather than like a switch somebody set.
`engine/gameplay/README.md` has the two recipes and the figures they produced.

## Files

- `DesktopLauncher.java` — composition root and `main`
- `GdxAdapterFactory.java` — decorates the headless desktop HAL (7 tests)
- `GdxWindowPort.java` — LWJGL3 window and frame loop (22 tests)
- `GdxInputPort.java` — the platform half of input, plus the scripted walk and
  scripted trigger the capture harness drives it with (12 tests)
- `GamepadSource.java` — the controller seam, so hot-plug is a unit test
- `GlfwGamepad.java` — the whole desktop gamepad backend: six GLFW calls, no
  new dependency. See `NOTICE` on why not `gdx-controllers`/SDL
- `InputAccumulator.java` — the testable half: accumulate and latch (29 tests)
- `GdxFrameLoopListener.java` — `ApplicationListener` → `I_FrameCallback` (7 tests)
- `FramebufferPresenter.java` — uploads and draws the finished frame
- `GdxScreenshot.java` — `glReadPixels` → PNG, opt-in
- `MainMenuScreen.java` / `MenuActions.java` / `DefaultMenuActions.java` /
  `MenuButtonListener.java` — the Scene2D menu and its testable seam (9 tests)

**152 tests in this module.** Run with `.\gradlew.bat :desktop:test`.
