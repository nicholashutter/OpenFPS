# `:android` — the Android launcher

> libGDX's Android backend, a menu, and the Room half of `I_UserProfilePort`.
> The APK assembles and installs. **It has never been launched.**

## Status

| Field | Value |
|---|---|
| **State** | PARTIAL |
| **Phase** | not a numbered phase; PLAN.md § 6 records it as "Built — APK assembles; not yet verified on a device or emulator" |
| **Tests** | **0** |
| **Registered** | provides the Android HAL (`AndroidAdapterFactory`) and the LAUNCHER Activity |
| **Verified** | 2026-07-28 |

**Built.** `AndroidLauncher` (the Activity), `AndroidWindowPort` (registers with
the framework loop rather than blocking), `AndroidAdapterFactory` (the null
backend with the real window and a Room profile port substituted),
`GdxLifecycleBridge`, `CompositeFrameCallback`, `MainMenuFrameCallback`,
`MenuSkinFactory`, and `persistence/` — `RoomUserProfilePort`,
`UserProfileEntity`, `UserProfileDao`, `OpenFpsDatabase`.

**Not built.** No tests of any kind — this is the only module in the repository
contributing nothing to the suite, and no instrumented (`androidTest`) coverage
exists either. There is no input port: `AndroidAdapterFactory` takes the null
one, so nothing on a touchscreen can move a camera yet. And there is no Android
counterpart to `FramebufferPresenter`, so the software rasterizer's output is
not presented here.

**Blocked on.** Nothing technical. It has simply never been run.

**Next step.** Launch it on the emulator and confirm the engine boots and logs.
Everything below is unverified until that happens.

## The single most important fact

**This module has never executed.** `gradlew :android:assembleDebug` produces an
APK and `adb install` accepts it, and that is the entire extent of what is
known. No frame has been drawn, no engine log line has been read off a device,
and no `onCreate` has returned on real hardware. Treat every claim in the
Javadoc here — the context-loss handling, the density scaling, the Room
round-trip — as a well-reasoned prediction rather than an observation.

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
debug an emulator bring-up against. Since bring-up is exactly the next step,
this binding is load-bearing.

## Build, install, run, watch

```
gradlew :android:assembleDebug
adb install -r android\build\outputs\apk\debug\android-debug.apk
adb shell am start -n com.openfps.android/com.openfps.android.AndroidLauncher
adb logcat -s OpenFPS:V
```

`OpenFPS` is the logcat tag `AndroidLauncher` uses for platform-side messages;
engine log lines arrive through `slf4j-android`, which tags by logger name, so
widen the filter once the launcher's own lines confirm the Activity started.

## Files

- `AndroidLauncher.java` — the LAUNCHER Activity and Android composition root
- `AndroidWindowPort.java` — `I_WindowPort` that registers instead of looping
- `AndroidAdapterFactory.java` — null backend, real window, Room profile port
- `GdxLifecycleBridge.java` — `ApplicationListener` → `I_FrameCallback`
- `CompositeFrameCallback.java` — one platform frame, two callbacks, in order
- `MainMenuFrameCallback.java` — the menu, in density-independent pixels
- `MenuSkinFactory.java` — the skin built in code, as *managed* GL resources
- `persistence/` — `RoomUserProfilePort` over `UserProfileEntity`,
  `UserProfileDao` and `OpenFpsDatabase`

**0 tests in this module.**
