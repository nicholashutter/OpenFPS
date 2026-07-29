# `:android` — the Android launcher

> libGDX's Android backend, the software rasterizer on a GLSurfaceView, a
> thumbstick, and the Room half of `I_UserProfilePort`. **It is playable.**

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING |
| **Phase** | not a numbered phase |
| **Tests** | 164 unique (328 executions — the suite runs against the debug and release variants). Plain JVM only; nothing device-backed |
| **Registered** | provides the Android HAL (`AndroidAdapterFactory`) and the LAUNCHER Activity |
| **Verified** | 2026-07-28 — **played on the OpenFPS_API36 emulator: menu, match, seven bots, a kill**. The gamepad path added since has **not** been run on a device or emulator with a controller attached — see "Gamepad hot-plug" below for exactly which parts are reasoned from the platform contracts rather than observed |

### What the emulator run established

The whole loop works on a phone. Measured on the OpenFPS_API36 AVD, API 36,
2400×1080 landscape at 2.625× density:

- The menu comes up and fits the screen.
- **Single Player enters the world**: the software rasterizer's frame is
  presented as a fullscreen quad, showing the real Kenney room read out of the
  APK's own assets — `source=KENNEY_KIT`, 301 world instances, GLES 3.0.
- **The touch controls drive the engine.** A drawn control pad — a stick with a
  thumb in it and three glyphed buttons — walks, turns, fires, jumps and leaves;
  the stick still anchors wherever the left thumb lands; the system back key
  leaves the match.
- **Combat works end to end**: `HIT entity 2`, `HIT entity 2`,
  `KILL entity 2 — 1 of 7 down`. Three shots kill a bot, exactly as designed,
  and the body is drawn lying flat afterwards.
- The bots patrol and shoot back, and the match gate holds them frozen while the
  menu is in front.

### What that run found, which is the more useful half

Running it by hand found **two defects that no test had**:

1. **The menu did not fit a landscape screen.** Four 72 dp buttons plus a title
   want 468 dp of height; a landscape phone at 2.625× has 411 dp. The title
   clipped off the top and Quit clipped off the bottom — on a screen whose only
   two useful controls are "start" and "leave". `MainMenuFrameCallback
   .layoutDensity` caps the layout density so the block always fits.

2. **The trigger never worked. On any platform.** `DemoGameplayPort.lastFireTic`
   was initialised to `Long.MIN_VALUE` — the obvious "has never fired" sentinel
   — and the cooldown test is a subtraction, `ticIndex - lastFireTic`. That
   overflows on the very first shot, wraps to a large negative number, and reads
   as "still cooling down"; and since `lastFireTic` is only written *after* the
   test passes, it never passed. Every unit around it was green:
   `Match.firePlayerShot` had tests, `Hitscan` had tests, the input path had
   tests. The defect lived exactly in the join, and it took firing at a bot by
   hand on a phone to find it. `DemoGameplayPortTest.Trigger` is the regression.

**Built.** `AndroidLauncher` (the Activity and composition root),
`AndroidWindowPort` (registers with the framework loop rather than blocking),
`AndroidAdapterFactory` (the null backend with the real window, touch input and
a Room profile port substituted), `AndroidUiFrameCallback` (menu or world, and
everything that follows), `AndroidInputPort` + `TouchLayout` + `TouchOverlay` +
`AndroidBindings` (the touch control scheme), `ApkModelSource` (the demo's
models, out of the APK), `GdxLifecycleBridge`, `CompositeFrameCallback`,
`MainMenuFrameCallback`, `MenuSkinFactory`, and `persistence/` —
`RoomUserProfilePort`, `UserProfileEntity`, `UserProfileDao`, `OpenFpsDatabase`.

**Not built.** No instrumented (`androidTest`) coverage — everything here is a
plain-JVM unit test, and everything that would need a real framework is
deliberately left uncovered rather than faked. No multiplayer: there is no
Android equivalent of `--net=`/`--peer=`, so the Multiplayer button enters the
same local match Single Player does. No sprint control, deliberately — see
`AndroidBindings`. The Android menu is still its own Scene2D layout rather than
the shared block welcome screen in `:gdxshared`.

**Blocked on.** Nothing.

**Next step.** Two, neither urgent: switch the menu over to `:gdxshared`'s block
screen so both platforms look the same, and give the touch scheme a settings
surface — `ActionBindings` is already rebindable at runtime and nothing exposes
it.

## What is still not verified on a device

Everything above was observed. These were not, and remain well-reasoned
predictions rather than observations:

- **GL context loss.** Android can destroy and rebuild the EGL context while the
  process lives. Every GL resource here is managed, so libGDX should re-upload
  it; that path has not been exercised.
- **Rotation.** The manifest pins landscape, so no rotation has ever happened.
- **Real hardware.** The emulator is x86_64 with host GPU. No ARM device has run
  this, and the software rasterizer's throughput on a real phone is unmeasured.
- **The Room round trip across process death.** The profile loads; nothing has
  killed the process and checked that it came back.

## A green build does not mean this module compiles

`settings.gradle.kts` includes `:android` **only when an Android SDK is
present**, checking `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `local.properties` in
that order. Without one, the module is not in the build at all.

The guard is not an oversight: without it, `gradlew :engine:build` on a machine
with no SDK fails during *settings* evaluation, before it can reach the module
that does not need one. But the consequence is sharp — **a green `gradlew build`
on a machine without the SDK proves nothing about `:android`.** With the
configuration cache on, which is the default, even the "skipping :android"
lifecycle notice goes unprinted on a cached run, so nothing signals that a whole
module was omitted.

Before changing anything here, confirm it is actually being built:

```
gradlew projects        # :android should be listed
```

## `onCreate` starts the session and returns

`EngineMain.start()` brings up memory, the HAL, the bus, subsystems, the worker
pool and the game-loop thread, and then **returns**, handing back an
`EngineSession`. That is the whole reason `start` / `stop` exists as a pair
separate from `run`.

The older `run()` blocked its caller for the entire session. On a desktop `main`
that is correct. On the Android UI thread it is an instant ANR: the very thread
that must return to the looper to schedule frames would be parked. So `onCreate`
starts and returns, `onDestroy` stops — the same pair desktop uses, minus
`awaitPlatformLoop()`, because the Android framework owns the loop.

`AndroidWindowPort.runFrameLoop` follows the same rule. It does not loop; it
*registers*, wrapping the engine's `I_FrameCallback` in a `GdxLifecycleBridge`
and handing it to `AndroidApplication.initialize`, which installs the content
view and returns. The port contract's "returns when the app is destroyed" is
honoured by the Activity lifecycle instead. The port takes exactly one callback
and two things need the frame, so `CompositeFrameCallback` fans it out — engine
first, menu second, and that order is load-bearing on the way down as well as
up.

## Two dependency decisions that are not preferences

**Room stands in for sqlite-jdbc.** `sqlite-jdbc` bundles precompiled natives
for roughly twenty desktop triplets, none of which Android can load, so it is
excluded from `:engine`'s coordinates here and `HalBackend.SQLITE` is never
selected. `RoomUserProfilePort` wraps the SQLite already in the OS instead. The
engine sees neither — it only ever holds `I_UserProfilePort`. `UserProfileEntity`
exists as a separate storage shape because annotating `UserProfile` with
`@Entity` would drag Room into `:engine`, which must compile with no Android SDK
at all; column names match the desktop schema so the two platforms describe the
same data the same way.

**`slf4j-android` was just added, and it is not an extra.** `logback-classic` is
excluded because it does not work on Android. SLF4J 2.x with no provider on the
classpath binds to the **NOP logger** and silently discards every engine log
line — including `LOG.error` from worker dispatch failures. The failure mode is
an app that looks fine and reports nothing, which is the worst possible one to
debug an emulator bring-up against. It earned its place on the first run: the
lines that identified the broken trigger — `HIT entity 2`, and their absence
before the fix — come out of `:engine` through this binding and nowhere else.

## Where the world in the APK comes from

The demo's models live at the repository root in `assets/models`, written by
`gradlew :tools:regenerateDemoAssets`. Desktop reads them straight off disk. An
APK cannot: the app has no access to the developer's filesystem, so the files
have to be **inside** it.

`stageModelAssets` syncs `assets/models/**/*.ofm` into a generated directory
that is registered as an asset source set, and `ApkModelSource` reads them back
through the platform `AssetManager`. That is the whole reason
`DemoModels.load` takes a `ModelSource` rather than a `Path` — an APK entry has
no filesystem path for `Files.readAllBytes` to open.

`assets/models` is gitignored (`docs/ASSETS.md` § 6 keeps upstream art out of
git), so **an APK built from a fresh clone contains no models**. That is the
intended outcome, not a failure: `AndroidLauncher` reports it at `ERROR`, naming
the regenerate command, and comes up as a menu with nothing behind it.

The staged models are about 36 MB uncompressed and add roughly 4 MB to the APK —
`.ofm` texture data deflates extremely well. See `android/build.gradle.kts` for
why all eighteen character models ship when only seven are used.

## Build, install, run, watch

```
gradlew :android:assembleDebug
adb install -r android\build\outputs\apk\debug\android-debug.apk
adb shell am start -n com.openfps.android/com.openfps.android.AndroidLauncher
adb logcat -s OpenFPS:V
```

`OpenFPS` is the logcat tag the platform classes use. Engine log lines arrive
through `slf4j-android`, which tags by logger name and **truncates to Android's
23-character limit** — `com.openfps.engine.demo.DemoGameplayPort` shows up as
`coed.DemoGameplayPort`, which looks like corruption and is not. Drop the
`-s OpenFPS:V` filter to see them.

## The controls

| Control | Where | Drawn as |
|---|---|---|
| Move | anywhere on the left half — the stick appears under your thumb | a ring with a thumb in it, resting at the lower left |
| Look | anywhere on the right half — drag to turn | nothing; the whole half is the control |
| Fire | the large button, bottom right | warm orange, crosshair |
| Jump | the smaller button, inboard of fire | green, chevron over a bar |
| Leave the match | the button top right, or the system back key | grey, cross |

A **controller** works too, at the same time and without a mode switch: left
stick moves, right stick looks, right trigger or R1 fires, A jumps, L1 sprints,
Start or Select leaves. Every one of those is an *additional* binding on an
action that already had a touch one, so a phone with a pad clipped to it can
still be steered with a stick and fired with a thumb on the screen button. That
works because multiple bindings are alternates rather than a chord, and because
`InputAccumulator` keeps the pad's readings in a channel of their own and sums
them with the touch channel instead of overwriting it.

The stick **floats**: it anchors wherever the left thumb lands rather than at a
painted spot. A fixed stick makes the player look at their thumb to find it, and
on a screen they are also trying to aim at, that is the difference between a
control and an obstacle. It is nevertheless *drawn* at a resting place while
nobody is holding it — `TouchLayout.stickHomeX()`/`stickHomeY()` — because an
invisible control is indistinguishable from a broken one, and the first thing a
new player does is look for the buttons. Touching down anywhere in the left half
still works, and still anchors under the thumb rather than at the resting place.

Every button is four sprites: a dark halo, a tinted fill, a near-white rim at
exactly the radius the finger is tested against, and a glyph. The halo is what
makes a translucent HUD readable over a brightly lit wall; the rim is what makes
the edge you aim at the edge that works. Holding a button grows it by
`TouchLayout.PRESSED_SCALE` and takes the fill, rim and glyph to full opacity —
a phone gives no click, so a press that changes nothing on screen cannot be told
from a miss. The growth is drawn only: the hit radius never moves, or a held
button would start stealing its neighbour from a second finger.

All of it — disc, ring and the three glyphs — is generated in code at the first
resize. No PNGs, no density variants, nothing to go missing from an APK.

There is no sprint **button**, deliberately. Screen space beside the fire button
is the scarcest thing on a phone, and a modifier held while aiming and firing
wants a thumb nobody has. A player on touch alone therefore never sprints, and
`AndroidInputPort.init()` says so in logcat rather than leaving it to be
discovered.

A controller changes that calculus completely, so it gets one: a shoulder button
costs no screen area and is not under the aiming thumb, which is the entire
objection above. Sprint is bound to L1 and to nothing else — the action is
reachable exactly when the hardware makes it reachable. `TOGGLE_INVERT_LOOK` is
now the only unbound action on this platform, and a test asserts that, so the gap
stays a decision rather than becoming an accident.

## Gamepad hot-plug is worse here than on desktop

Desktop polls, so a poll simply stops finding the pad. Android **pushes**, and a
Bluetooth controller going out of range or running its battery flat — the common
case on a phone, not the exotic one — sends no final event at all. The last stick
position it reported is a level, and it would sit in the accumulator forever,
walking the player into a wall with nobody touching anything.
`InputManager.InputDeviceListener` is the only thing that notices, so
`AndroidLauncher` registers one and calls `onGamepadDisconnected`, which clears
the gamepad channel alone and leaves a thumb that is still on the screen alone.
The listener is unregistered in `onDestroy` — it holds the Activity, and would
otherwise leak it on every rotation.

Axes arrive through `dispatchGenericMotionEvent` rather than
`onGenericMotionEvent`, and that is load-bearing: libGDX registers its own
view-level generic-motion listener for mouse scroll, and a view listener runs
first — so anything it consumed would never reach the Activity callback, making
controller support depend on an implementation detail of somebody else's backend.
Dispatch sees every event first and still calls `super`. The extraction there is
five `getAxisValue` calls and a source check, because a `MotionEvent` cannot be
constructed in a local unit test: everything that can be wrong happens in
`onGamepadAxes`, which takes plain floats and is fully covered.

**What is not verified.** No device or emulator has been driven with a controller
attached, so four things here are reasoned from the platform contracts rather
than observed: the `MotionEvent` axis numbering (`AXIS_Z`/`AXIS_RZ` being the
right stick), the `SOURCE_JOYSTICK` check classifying real pads correctly,
`InputDeviceListener` firing on a Bluetooth drop, and libGDX forwarding
`BUTTON_*` key events from the surface. Everything on this side of those calls —
the sign of every axis, the trigger threshold, hot-plug clearing state, and touch
and pad working simultaneously — is covered by tests that need none of them.

## Files

- `AndroidLauncher.java` — the LAUNCHER Activity and Android composition root
- `AndroidWindowPort.java` — `I_WindowPort` that registers instead of looping
- `AndroidAdapterFactory.java` — null backend, real window, Room profile port
- `GdxLifecycleBridge.java` — `ApplicationListener` → `I_FrameCallback`
- `CompositeFrameCallback.java` — one platform frame, two callbacks, in order
- `AndroidUiFrameCallback.java` — menu or world, the input processor, the match
  gate, and leaving a match
- `MainMenuFrameCallback.java` — the menu, in density-independent pixels, capped
  so it fits the surface it is given
- `MenuSkinFactory.java` — the skin built in code, as *managed* GL resources
- `AndroidInputPort.java` — touch events, pad buttons and stick axes →
  `InputState`, one finger at a time and two devices at once
- `TouchLayout.java` — where the controls are, how big they are drawn, and how
  far the stick is pushed. **Imports nothing**, so all of it is unit-tested
- `TouchOverlay.java` — draws the control pad from generated discs, rings and
  glyphs; owns no geometry of its own
- `AndroidBindings.java` — the default touch *and gamepad* scheme; the only file
  here that names a control. Its `GAMEPAD_BUTTON` codes are libGDX **key**
  constants, because on Android a pad button genuinely is a key event — the same
  physical button is a different number on desktop, which is exactly what
  `InputBinding` means by a code being platform-owned
- `ApkModelSource.java` — the demo's models, read through the platform
  `AssetManager` rather than a filesystem path that does not exist
- `persistence/` — `RoomUserProfilePort` over `UserProfileEntity`,
  `UserProfileDao` and `OpenFpsDatabase`

**164 tests in this module** — all plain JVM unit tests, no Robolectric and no
instrumentation. They cover what can honestly be covered off a device: the whole
touch-gesture layer (which control a pixel belongs to, stick deflection and its
dead zone, the drawn thumb clamped to its ring, the resting stick clearing the
buttons on the smallest screen still in use, no two buttons touching even at
their held size, every button reporting itself held, and every button having a
glyph on it, two fingers not interfering, a tap shorter than one tic still
firing, a cancelled touch releasing), the UI transitions and the match gate,
the menu's
fit rule, the frame-callback fan-out and its load-bearing ordering, the entity
mapping in both directions, the window port's close-flag and re-arm semantics,
the adapter factory's delegation and its profile-closed-before-delegate
ordering, the lifecycle bridge's resize filtering, and the profile port's
off-READY refusals.

What they deliberately do **not** cover is anything needing a real device: the
Room round-trip against platform SQLite, `requestClose()` actually finishing the
Activity, and `MenuSkinFactory`'s managed-texture claim — whose failure mode is a
menu that renders as white rectangles after the first task-switch. Those need an
emulator that can drop an EGL context, and faking them would be worse than
leaving them uncovered.
