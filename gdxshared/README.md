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

## Files

| File | What it does |
|---|---|
| `FramebufferPresenter.java` | Uploads R_'s finished frame and draws it fullscreen |
| `InputAccumulator.java` | Accumulate-and-latch between platform and tic rates |
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
