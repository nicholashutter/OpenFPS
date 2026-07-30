# `:gdxshared` — the libGDX code that is not platform-specific

> Both launchers use libGDX. Neither of them should have to write the framebuffer
> presenter twice.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING |
| **Phase** | created 2026-07-28, when `:android` needed a presenter and a UI state machine that already existed in `:desktop` |
| **Tests** | 76 |
| **Registered** | consumed by `:desktop` and `:android`; depends on `:engine` and libGDX **core** only |
| **Verified** | 2026-07-28 |

**Built.** `FramebufferPresenter` (uploads R_'s finished `int[]` and draws it as
a fullscreen quad), the blocky welcome screen (`MainMenuScreen`, `BlockFont`,
`BlockTitle`, `BlockButton`, `MenuBackground`, `MenuPalette`), the menu command
interface and its default wiring (`MenuActions`, `DefaultMenuActions`,
`MenuButtonListener`), the UI state machine (`UiState`, `UiStateMachine`), and
`InputAccumulator`.

**Not built.** Nothing outstanding. The one asymmetry left is that the block
welcome screen is drawn by `:desktop` while `:android` still draws a Scene2D
menu of its own — the classes are here and shared-ready, but nothing has
switched Android over to them yet.

**Blocked on.** Nothing.

## Why this module exists

The rule that shapes this repository is that **`:engine` must never import
libGDX**. It is what keeps the engine buildable and testable on a machine with
no display and no Android SDK, and it is not negotiable.

The rule that does *not* follow from it — and that was quietly assumed until
Android needed a screen — is that everything touching libGDX must therefore be
platform-specific. It is not. libGDX core is plain Java. `Pixmap`, `Texture`,
`SpriteBatch` and Scene2D behave identically on the LWJGL3 backend and the
Android one, and the code built on them does not care which is underneath.

So there are three layers, not two:

| Layer | May import | Example |
|---|---|---|
| `:engine` | JDK only | `SoftwareRenderPort`, `Match`, `NetSession` |
| `:gdxshared` | JDK + libGDX **core** | `FramebufferPresenter`, `MainMenuScreen` |
| `:desktop` / `:android` | + one backend | `GdxWindowPort`, `AndroidLauncher` |

The middle layer is this module. What belongs here is anything that needs
libGDX and does not need to know which platform it is on; what does not belong
is anything naming `Lwjgl3Application`, `AndroidApplication`, GLFW or
`android.*`.

A **key code is platform-specific** and stays out. `Input.Keys.SPACE` is a
number whose meaning belongs to a backend, and a touch region is not a key at
all — so `DesktopBindings` lives in `:desktop`, `AndroidBindings` lives in
`:android`, and `:engine` ships no defaults whatsoever. See `hal/README.md`.

## What the presenter is worth sharing for

`FramebufferPresenter` is 140 lines of code and three subtle correctness
properties, and it is the second of those that makes duplication a bad idea:

- **De-padding.** The framebuffer's row stride is padded to a multiple of 16
  pixels to avoid false sharing between render workers. Uploading the colour
  buffer raw shears the image progressively down the screen, because stride is
  not width.
- **Byte order.** A `0xRRGGBBAA` int written through `getPixels().asIntBuffer()`
  lands as the bytes R, G, B, A, because libGDX hands out a big-endian buffer —
  and `glTexImage2D` then accepts them as `GL_RGBA` unchanged.
- **Orientation.** The viewport transform flips y once, and
  `SpriteBatch.draw(Texture, …)` flips it again. They cancel. **Adding an
  explicit flip is the bug, not the fix.**

Every one of those is identical on a phone. A second copy would be a second
place for them to drift, and drift in any of the three produces an image that is
wrong in a way that looks like a renderer bug rather than an upload bug.

## The render resolution is not the surface resolution

The quad above is a **fullscreen** quad — it covers the viewport whatever the
texture measures — so a smaller framebuffer is upscaled by the GPU for free.
`RenderMode` is the seam that uses that: the surface reports its own size, and
`SoftwareRenderPort.resize` is given a scaled one.

It matters because a software rasterizer's cost is per pixel and a display panel
is not a budget. A phone handed the panel's native 2400x1080 rasterized 2.59
megapixels per frame; `480P` renders 1067x480 instead, and on the
`OpenFPS_API36` emulator that took the same scene from **262 ms to 91 ms** and
the platform frame rate from 22 to 41 fps. The remaining floor is
resolution-independent — 7,844 triangles transformed and clipped per pass, and
eight publish/join boundaries — which is why 5.4x fewer pixels buys 2.9x rather
than 5.4x.

Three modes, cycled from the settings screen and changeable mid-session:
`480P` (the default on **both** platforms), `720P`, and `NATIVE`. A mode names
the **short** edge and is a **ceiling**: a window already smaller than the mode
is left alone, because rasterizing 480 rows into a 320-row window would be
slower and blurrier at once.

The UI, the touch pad and the debug counter are drawn separately at the surface
size and stay sharp — that is the payoff of scaling the world alone rather than
the whole framebuffer.

## `InputAccumulator` imports nothing

It is in this module because both input ports use it, not because it needs
libGDX — it does not import anything at all. That is deliberate: the tricky part
of input is the accumulate-and-latch bookkeeping between a platform's frame rate
and the simulation's fixed one, and keeping it free of toolkit types is what
makes it the part CI can actually test.

It stores movement as **two floats, not four booleans**. A key is a stick that
only ever reaches its stops, so `setMovementKeys` reduces to `setMovementAxes`
and the Android thumbstick uses the analog path directly. Nothing downstream can
tell which produced a given `InputState`.

A gamepad is a **second channel, not a second mode**: every pad reading is stored
beside the keyboard/touch reading and `latch()` sums the two, so a player may
walk with the stick and turn with the mouse in one tic and neither device can
zero the other by being polled second. `clearGamepad()` empties that channel
alone — which is what a controller unplugged at full deflection needs, and why it
is not simply `clearAll()`.

## A mouse and a stick are different kinds of quantity

The one thing in this module most expensive to get wrong. A mouse reports a
**displacement** — an integral, so it is summed and drained exactly once by
`getAndSet`. A held stick reports a **position**, meaning "keep turning at this
rate", so it is *overwritten* by each poll and converted exactly once per tic:

    radians = deflection × GAMEPAD_LOOK_RADIANS_PER_SECOND × ticDuration

The poll count cancels out of that expression entirely, which is the whole point.
Push a held stick through the pixel accumulator instead and a 144 Hz machine
banks twice the contributions of a 72 Hz one — so the player spins twice as fast
for the same thumb, on the same game, because of vsync. `latch()` is the right
home for the conversion because it is the only thing here that happens exactly
once per tic, which is the interval being integrated over.

`setTicDuration` is how the accumulator learns that interval. It cannot ask: the
frame rate is `engine.core` configuration and this module is a platform adapter,
so each launcher tells it.

## `AnalogStick` holds every number a player can feel

The dead zone is **radial, on the pair** — never per-axis. A per-axis threshold
makes the ignored region a square, so a stick pushed diagonally escapes it while
one pushed the same physical distance along a cardinal does not; the player
experiences that as a smaller dead zone diagonally than straight ahead, and as
diagonals snapping to a cardinal as they cross the boundary. A circle has no
corners.

It is **rescaled rather than clipped**, so the dead zone costs resolution instead
of range and output rises continuously from zero. A resting stick returns
*exactly* `0.0f`, not something small — a value that merely rounds to nothing
still fails `InputState.isNeutral()`, which would send a non-neutral tic command
every tic of an idle player.

## Files

| File | What it does |
|---|---|
| `FramebufferPresenter.java` | Uploads R_'s finished frame and draws it fullscreen |
| `RenderMode.java` | 480p / 720p / native, and the surface-to-framebuffer arithmetic |
| `RenderSettings.java` | The switch the settings screen cycles; the presenter listens |
| `AccessibilitySettings.java` | The target outline, on by default; the renderer's outline pass listens |
| `DebugSettings.java` | The frame counter, off by default; deliberately NOT where the outline lives |
| `SettingsScreen.java` | Accessibility and diagnostics, in two named groups |
| `DebugOverlay.java` | The corner FPS / frame time / resolution readout |
| `InputAccumulator.java` | Accumulate-and-latch between platform and tic rates; mouse displacement and stick rate |
| `AnalogStick.java` | Radial dead zone, response curve, trigger threshold — no toolkit imports |
| `UiState.java` / `UiStateMachine.java` | Menu or game, and what each permits |
| `MenuActions.java` | What the menu can ask the application to do |
| `DefaultMenuActions.java` | The default wiring — Quit goes through `I_WindowPort` |
| `MenuButtonListener.java` | One Scene2D change event to one `Runnable` |
| `MainMenuScreen.java` | The welcome screen's layout and lifecycle |
| `BlockFont.java` | A 5×7 block alphabet, no assets, headlessly testable |
| `BlockTitle.java` | The cycling blocky title |
| `BlockButton.java` | A beveled key with a hover lift |
| `MenuBackground.java` | The checkered field and its diagonal sheen |
| `MenuPalette.java` | Every colour the menu uses, in one place |
