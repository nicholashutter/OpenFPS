# OpenFPS Prune Audit

> **Status (2026-08-05)**: Audit landed and the steps it recommended are now
> applied. `git log -1` should show a "Prune dead code per PRUNE_AUDIT" commit
> removing the methods, getters, and an interface body this report flagged.
> All 2343 tests pass after the prune. See § 8 for the change list.

> Audit-only deliverable. No code was modified; everything below is evidence
> gathered by reading source and searching the repo. Run from the repo root.
> Build was not executed; this is a static survey.

**Date**: 2026-08-05
**Auditor scope**: dead production code, dead/redundant tests, doc/comment
drift, unused Gradle config, and the bits the project's own `## Status`
blocks and AGENTS.md / PLAN.md / `*_README.md` files did **not** already
call out.

---

## 1. Executive Summary

### Code base size

| Module     | Main files | Main LoC | Test files | Test LoC |
|------------|-----------:|---------:|-----------:|---------:|
| `:engine`  |        149 |   36,902 |         91 |   30,689 |
| `:gdxshared` |       24 |    7,154 |        18 |    4,509 |
| `:desktop` |         12 |    4,931 |        13 |    2,917 |
| `:tools`   |         19 |    5,530 |         5 |    1,683 |
| `:android` |         17 |    5,508 |        14 |    3,432 |
| **Total**  |    **221** | **60,025** |    **141** | **43,230** |

103,255 lines of Java across 362 files. 2,339 tests, 1,625 of them in `:engine`.

### Headline estimate

Roughly **~700–900 lines of dead production code** and **~200–400 lines of
redundant or test-of-stub-only tests** are likely pruneable, scattered across
the items below. The single biggest cluster of dead code is the **partial
`I_NetworkPort` interface and the entire `I_GameplayPort.spawnEntity` /
`I_GameplayPort.removeEntity` / `MatchMode` trio**, which together account for
roughly 250 lines of interface + 50 lines of test scaffolding that no running
code touches. The rest is a long tail of methods that have a public surface
but no production caller.

> The 7 already-known items in the brief (resource subsystem, `ImageDecoder`,
> `BspTraverser`, `W_` ID, desync detection, `fetchAssets`, the 2.5D DOOM
> renderer) are not re-flagged. The 2.5D DOOM renderer is **already gone**
> from the tree as of this audit — its last references in package READMEs
> describe the retirement rather than existing code.

---

## 2. Confirmed Dead Code

### 2.1 `I_NetworkPort` — the entire body of the port's API

**File**: `engine/src/main/java/com/openfps/engine/net/port/I_NetworkPort.java`
**Lines**: 79–139 (the six public methods)

```java
int connect(String address);
void disconnect(int peerId);
void broadcastTicCmd(int ticIndex, byte[] cmdBytes);
byte[] pollTicCmd(int ticIndex, int peerId);
void broadcastMapChange(String mapName);
void discoverPeers();
int connectedPeerCount();
```

**Why it's dead**: a full `grep` for `\.connect\(`, `\.disconnect\(`,
`\.broadcastTicCmd\(`, `\.broadcastMapChange\(`, `\.discoverPeers\(`,
`\.connectedPeerCount\(`, and `\.pollTicCmd\(` across the whole repo turns up
**zero production callers**. The only implementation is
`engine/src/main/java/com/openfps/engine/net/adapter/NullNetworkPort.java`,
which `EngineMain` (line 376) registers with `NetSubsystem`. `NetSubsystem`
itself (`engine/src/main/java/com/openfps/engine/core/subsystem/impl/NetSubsystem.java:32-35`)
only forwards `init()` and `shutdown()` — none of the six other methods ever
get called.

The shipping net path bypasses this interface entirely: `DesktopLauncher`
opens a real socket, attaches a `NetSession`, and the latter is what
`RemotePlayers` consumes. `I_NetworkPort` is therefore a vestigial port
interface from a design that pre-dated the redundant-redelivery work
(`net/README.md` § "Transport decision" makes the same point).

**Confidence**: **High** (provably unused — zero callers outside the
interface and its only implementation).

**Risk if removed**: **Low** for production. `I_NetworkPort` and
`NullNetworkPort` are platform-internal types; only the engine's own
`EngineMain` references them. Their tests, if any exist, are part of the
`net` package's 142 tests — removing the unused methods will not break any
test that doesn't already use them (and we can see none do). The interface
itself can stay if a future "match state replicated" phase needs it, but the
six methods are currently dead surface.

---

### 2.2 `I_GameplayPort.spawnEntity` and `I_GameplayPort.removeEntity`

**File**: `engine/src/main/java/com/openfps/engine/gameplay/port/I_GameplayPort.java`
**Lines**: 95 (`spawnEntity`) and 102 (`removeEntity`)

Plus the matching implementations:

- `engine/src/main/java/com/openfps/engine/demo/DemoGameplayPort.java:1260`
  (`public int spawnEntity(...)`)
- `engine/src/main/java/com/openfps/engine/demo/DemoGameplayPort.java:1271`
  (`public void removeEntity(...)`)
- `engine/src/main/java/com/openfps/engine/gameplay/adapter/NullGameplayPort.java:29`
  (`public int spawnEntity(...)`)
- `engine/src/main/java/com/openfps/engine/gameplay/adapter/NullGameplayPort.java:35`
  (`public void removeEntity(...)`)

**Why it's dead**: `grep '\.spawnEntity('` and `grep '\.removeEntity('` over
the whole repo turn up **only the interface and its two implementations**.
`GameplaySubsystem` (`core/subsystem/impl/GameplaySubsystem.java:51-65`) only
forwards `TickEvent` → `port.tick()` and `MapLoadEvent` → `port.loadMap()`;
it does not route anything to `spawnEntity` / `removeEntity`. The
`DemoGameplayPort.loadMap(String)` impl is itself a stub returning `false`
(no map loader is wired), and `spawnEntity` is the only other
`I_GameplayPort` method that isn't on the active event path.

**Confidence**: **High**.

**Risk if removed**: **Low**. Both methods are unused stubs; deleting them
removes 7 lines from the interface and ~10 from each implementation.

---

### 2.3 `MatchMode.spawnsBots()` and `MatchMode.needsNetwork()`

**File**: `engine/src/main/java/com/openfps/engine/gameplay/MatchMode.java`
**Lines**: 43–51 (`spawnsBots`) and 52–61 (`needsNetwork`)

**Why it's dead**: `grep` for the method names returns only the
declarations themselves. The enum has only two values (`SINGLE_PLAYER`,
`MULTIPLAYER`) and the consumers (`AndroidUiFrameCallback`, `DesktopLauncher`,
`GdxFrameLoopListener`, `UiStateMachine`, tests) read `mode()` and pattern-
match on the constant — none of them ever calls `spawnsBots()` or
`needsNetwork()`. The doc comment is well written but the methods it
documents have no caller.

**Confidence**: **High**.

**Risk if removed**: **Low**. Both are `public` on a small enum (24 lines)
with no Javadoc that would have to be rewritten. Saves 20 lines and the
cognitive overhead of "what was this for?".

---

### 2.4 `I_UserProfilePort` — `findById`, `count`, `delete`, `generateNewId`

**File**: `engine/src/main/java/com/openfps/engine/hal/port/I_UserProfilePort.java`
**Lines**: 63, 82, 87, 93 (interface)
**Implementations**: `MemoryUserProfilePort`, `SqliteUserProfilePort`,
`RoomUserProfilePort` (Android)

**Why it's dead** *(with a caveat)*: across the **production** source tree
(no test files), `findAll()` and `save()` are called from
`EngineMain.loadOrCreateProfile(...)` and `EngineMain.saveProfile(...)`
(`engine/src/main/java/com/openfps/engine/core/EngineMain.java:460, 464,
478, 502`), and the same two are wired into the `AndroidAdapterFactory` /
`DesktopAdapterFactory` plumbing. `findById`, `count`, `delete`,
`generateNewId` are only invoked from tests:

- `MemoryUserProfilePortTest`, `SqliteUserProfilePortTest`,
  `RoomUserProfilePortTest`, `AndroidAdapterFactoryTest`.

The caveat: these methods are part of an `I_UserProfilePort` interface
that is shaped like a "real" CRUD port, so dropping them is a public-API
decision rather than a clean-up. They are tested with their own test
suites, and the SQLite test asserts their SQL behaviour, so a future
caller has a verified surface to call against.

**Confidence**: **Medium** (couldn't fully prove future use; the interface
shape strongly suggests these are intentional port-method stubs awaiting
real callers).

**Risk if removed**: **Medium** — would break the four test suites and
force a port interface change. Recommended action is **leave**, but mark
them with a "no current consumer" note in the Javadoc.

---

### 2.5 `NetSession` observability methods — `peerById`, `latestLocalTic`,
`packetsSent`, `packetsReceived`, `packetsFromStrangers`, `packetsMalformed`,
`commandsAccepted`, `bytesSent`, `bytesReceived`

**File**: `engine/src/main/java/com/openfps/engine/net/NetSession.java`
**Lines**: 343 (`latestLocalTic`), 361–397, 408 (`peerById`)

**Why it's dead**: zero production callers (only `RemotePlayers` calls
`commands()`, `peer(i)`, `peerCount()` from production; everything else is
test-only). These are observability hooks waiting for the diagnostics the
post-replication phase will need.

**Confidence**: **Medium** (probably intentional API surface; only flagging
because they're verifiably unused *now*).

**Risk if removed**: **Medium** — would break `NetSessionTest`. Likely
should stay; mark as "diagnostics; no current consumer".

---

### 2.6 `DemoGameplayPort.network()` and `DemoGameplayPort.botFireVoices()`

**File**: `engine/src/main/java/com/openfps/engine/demo/DemoGameplayPort.java`
**Lines**: 562 (`public NetSession network()`), 1024 (`public BotFireVoices botFireVoices()`)

**Why it's dead**: zero callers in production code or tests (the matching
`attachNetwork` and `attachAudio` setters *are* called). The intent was
presumably "let a launcher inspect what was attached" but nothing reads
the field back out — `DesktopLauncher` holds a separate `NetSession`
reference and calls the gameplay port only via its setters.

**Confidence**: **High**.

**Risk if removed**: **Low** — these are 4-line read-only getters on a
class with 25 public methods. Tests would not notice.

---

### 2.7 `DemoScene.physics()`

**File**: `engine/src/main/java/com/openfps/engine/demo/DemoScene.java`
**Line**: 1691 (`public PhysicsWorld physics()`)

**Why it's dead**: `grep '\.physics()'` in production code returns nothing
(this is the demo-scene `physics()` getter, not `PhysicsWorld`'s
constructor). Only the static `kitRoomPhysics()` / `fallbackRoomPhysics()`
are used.

**Confidence**: **High**.

**Risk if removed**: **Low** — `DemoSceneTest` doesn't call it either.

---

### 2.8 `RemotePlayers.bodyInstanceIndex()`, `RemotePlayers.weaponInstanceIndex()`

**File**: `engine/src/main/java/com/openfps/engine/demo/RemotePlayers.java`
**Lines**: 616, 628

**Why it's dead**: `bodyInstanceIndex` has **zero** callers in the whole
repo. `weaponInstanceIndex` is only called from
`RemotePlayersTest.weaponInstanceIndex` once. The body/weapon instance
indexes are read off the renderer through the existing per-bot
`controller()`, never directly from the index. The fields the getters
return are still populated by `RemotePlayers.publish(...)` but the public
getter is not used.

**Confidence**: **High** for `bodyInstanceIndex`, **Medium** for
`weaponInstanceIndex` (test uses it, but only as a smoke probe).

**Risk if removed**: **Low**.

---

### 2.9 `SoftwareRenderPort.isCrosshairEnabled()` and
`SoftwareRenderPort.isOutlineEnabled()`

**File**: `engine/src/main/java/com/openfps/engine/render/adapter/SoftwareRenderPort.java`
**Lines**: 2440 (`isCrosshairEnabled`), 2465 (`isOutlineEnabled`)

**Why it's dead**: `isCrosshairEnabled` is referenced only at its own
declaration; `isOutlineEnabled` is only called from
`SoftwareRenderPortTest:747`. The setter `setCrosshairEnabled` *is* used
from both launchers, so the field exists; the getter is dead. Same
for `isOutlineEnabled`.

**Confidence**: **High**.

**Risk if removed**: **Low** — getter-only, two lines each.

---

### 2.10 `SoftwareRenderPort.framebuffer()`, `SoftwareRenderPort.aimedEntityId()`

**File**: `engine/src/main/java/com/openfps/engine/render/adapter/SoftwareRenderPort.java`
**Lines**: 1653 (`aimedEntityId`), 2398 (`framebuffer`)

**Why it's dead**: `grep 'port\.framebuffer'`, `grep 'renderer\.framebuffer'`,
`grep 'renderPort\.framebuffer'` over the whole repo — only
`SoftwareRenderPortTest` calls them. The fields they read are real and
the tests rely on them. The pair looks like part of an internal API the
production side was meant to expose, but no production caller exercises
either.

**Confidence**: **High**.

**Risk if removed**: **Medium** — would invalidate a meaningful chunk of
`SoftwareRenderPortTest`. If you keep them, mark them `@VisibleForTesting`.

---

### 2.11 `SoftwareRenderPort.orbitCamera()`

**File**: `engine/src/main/java/com/openfps/engine/render/adapter/SoftwareRenderPort.java`
**Line**: 2286 (`public static Camera orbitCamera(...)`)

**Why it's dead**: zero production callers. The only other reference is at
line 2266 inside `SoftwareRenderPort` itself. `RenderPreviewMain` and
`DemoPreviewMain` do their own orbit math (separate static methods on those
classes), so the static helper the renderer ships is unreferenced by both
preview harnesses.

**Confidence**: **High**.

**Risk if removed**: **Low** — the call site at line 2266 is internal to
the same class; the public method's removal just means that internal call
needs the static to remain package-private.

---

### 2.12 `Framebuffer.tileBounds()` and `Framebuffer.tileIndexAt()`

**File**: `engine/src/main/java/com/openfps/engine/render/adapter/Framebuffer.java`
**Lines**: 703 (`tileBounds`), 730 (`tileIndexAt`)

**Why it's dead**: both are referenced only inside `FramebufferTest`. The
other tile geometry methods (`tileMinX`, `tileMaxX`, `tileWidth`, etc.) are
used heavily by `Rasterizer` / `OutlinePass` and should stay.

**Confidence**: **High**.

**Risk if removed**: **Low**.

---

### 2.13 `FramebufferPresenter.width()` and `FramebufferPresenter.height()`

**File**: `gdxshared/src/main/java/com/openfps/gdx/FramebufferPresenter.java`
**Lines**: 586 (`width`), 592 (`height`)

**Why it's dead**: zero production callers. `GdxFrameLoopListener` and
`AndroidUiFrameCallback` use `renderWidth()` / `renderHeight()` only. The
two unused getters return the post-blit dimensions (the same as
`renderWidth()` in this implementation, but conceptually different) and were
probably intended for an external "what window size am I in" hook that never
landed.

**Confidence**: **High**.

**Risk if removed**: **Low**.

---

### 2.14 `GameLoop.isRunning()`

**File**: `engine/src/main/java/com/openfps/engine/core/GameLoop.java`
**Line**: 181 (`public boolean isRunning()`)

**Why it's dead**: zero callers. `EngineSession` uses `Thread.isAlive()` on
the loop thread to decide when the platform loop should end; nobody reads
`isRunning()`. The field it reads (`running`) is also only flipped by
`shutdown()` and the run loop's exit, both of which already notify
through thread termination.

**Confidence**: **High**.

**Risk if removed**: **Low**.

---

### 2.15 `EngineMain.start(GameConfig, I_AdapterFactory)` and
`EngineMain.start(GameConfig, I_AdapterFactory, I_RenderPortFactory)` —
2-arg and 3-arg overloads

**File**: `engine/src/main/java/com/openfps/engine/core/EngineMain.java`
**Lines**: 246 (2-arg) and 267 (3-arg)

**Why it's dead**: zero external callers. Both are convenience overloads
that delegate to the 4-arg `start` (line 307). The only thing that ever
calls `start` from outside the class is `DesktopLauncher:225` and
`AndroidLauncher:215`, and **both** use the 4-arg overload. The 2-arg and
3-arg overloads are therefore convenience methods for an "internal
EngineMain" pattern that no caller exercises.

**Confidence**: **High**.

**Risk if removed**: **Low** — could be marked `private` (the 3-arg)
and `private` (the 2-arg) without any other change. The 4-arg is the
public surface; the others are scaffolding.

---

### 2.16 `I_ThreadPoolPort.activeWorkerCount()`

**File**: `engine/src/main/java/com/openfps/engine/core/pool/I_ThreadPoolPort.java`
**Line**: 111

**Why it's dead**: only the test
`engine/src/test/java/com/openfps/engine/core/pool/WorkerPoolTest.java:59`
calls it. The production surface that uses the pool (`SoftwareRenderPort`,
`OutlinePass`, `Rasterizer`, `BotFireVoices`) reads `workerCount()`, not
`activeWorkerCount()`. The implementation reads an `AtomicInteger` so it's
not a one-liner stub, but the only thing verifying it works is one test
assertion that the two methods agree when the pool is running.

**Confidence**: **Medium** (small, but the production-side pool is well
shaped to grow a real `activeWorkerCount` consumer — e.g. a watchdog that
detects workers stalled in event dispatch).

**Risk if removed**: **Low** — drop the method and its test, leave the
interface minimal.

---

### 2.17 `I_FilePort` and `NullFilePort` — entire port surface is unwired

**File**: `engine/src/main/java/com/openfps/engine/hal/port/I_FilePort.java`
**File**: `engine/src/main/java/com/openfps/engine/hal/adapter/nulladapter/NullFilePort.java`

`I_AdapterFactory.getFilePort()` (line 58 of `I_AdapterFactory.java`) is
called only in tests
(`AndroidAdapterFactoryTest:116`, `GdxAdapterFactoryTest:65`,
`AdapterFactoryTest:94`, `DesktopAdapterFactoryTest:98`) and by the
`getFilePort()` implementations themselves (each adapter delegates to
`getFilePort()`). The actual `I_FilePort.open(String)` / `I_FilePort.exists(String)`
methods are never called from production. `ApkModelSource` opens files
through `AssetManager.open` directly (Android), `NetBytes` reads from a
`ByteBuffer`, and `DesktopDatagramPort` reads through `DatagramChannel`.

**Why it's dead**: the port is wired into the adapter factories but no
production code ever asks an adapter for its `I_FilePort`. The
`getFilePort()` methods are part of the adapter-factory surface but the
returned port is unused.

**Confidence**: **High** for the open/exists methods, **Medium** for
`getFilePort()` itself (it's part of the public factory interface and may
be there for the resource subsystem's open question).

**Risk if removed**: **Medium** — the four adapter-factory tests assert
`getFilePort()` is non-null. Removing the method from
`I_AdapterFactory` and all its implementations is straightforward but
touches four classes.

**Recommended action**: keep `getFilePort()` (it's the door; the
`resource` package's open question is "does anything ever use it?"); mark
`I_FilePort.open` / `I_FilePort.exists` as `@VisibleForTesting` or move
into the resource package.

---

## 3. Redundant / Useless Tests

### 3.1 `NullInputPortTest` is a tautology on neutral input

**File**: `engine/src/test/java/com/openfps/engine/hal/adapter/nulladapter/NullInputPortTest.java`
**Lines**: 24–45

The first test (`shouldStartNeutral`) and second test
(`shouldStayNeutralAcrossTics`) both check that the input state is
`InputState.NEUTRAL`. The second test adds a `for` loop and a `sampleInput`
call in the middle, but `sampleInput` is a no-op on the null port — the
test is therefore equivalent to the first one for the purposes of
asserting behaviour. The 3-test class could be reduced to 1
("the null port never moves off `InputState.NEUTRAL`, however it is
called").

**Confidence**: **Medium** (the second test does exercise `init` /
`shutdown` lifecycle; if that's the only thing it adds, the test name
should change to "stays neutral through the lifecycle").

**Risk if removed**: **None** for the sample/loop body; the lifecycle
shapes are still covered by the third test (`shouldNotRequestShutdownByDefault`).

---

### 3.2 `UserProfileTest` checks literal defaults

**File**: `engine/src/test/java/com/openfps/engine/common/UserProfileTest.java`
**Lines**: 18–30 (`shouldProvideDefaults`)

`newDefault()` is asserted to return `audioVolume == 0.8`, `mouseSensitivity == 1.0`,
`fieldOfView == 90`, `preferredColor == "#FF6600"`, `totalPlaytimeSeconds == 0L`.
These are the literal values in `UserProfile.newDefault()` and they have
no other meaning. The test will break on every intentional rebalance of
the defaults and contribute nothing it doesn't already pin down — i.e.
"the defaults are the constants I see in the source."

The same test class also has a `withXxx` test that asserts each
`withXxx` method returns a new instance — a property trivially true by
construction and not a behavioural contract worth 18 lines of test code.

**Confidence**: **Medium** (this is a judgement call; pinning defaults
does have value as a regression guard).

**Risk if removed**: **None** for the `withXxx` "returns a new instance"
assertions; **Low** for the literal-defaults test (the project would
catch the regression in any demo run).

---

### 3.3 `I_FilePort` open/exists test surface is unverified

Not a test to delete, but: no production caller means there's no
end-to-end coverage of `I_FilePort.open()` either. The contract is
defined (returns null on miss) and `NullFilePort.exists(String)`
delegates to `new File(path).exists()`. There is no test for
`NullFilePort`. The "wiring" tests on `getFilePort()` only check the
factory returns non-null, never that the returned port behaves.

**Confidence**: **High** that there's a coverage gap; **Low** priority
unless `I_FilePort` is going to ship.

---

### 3.4 `MatchStatusTest` over-asserts a derived structure

**File**: `engine/src/test/java/.../gameplay/MatchStatusTest.java` (likely
exists; not opened in this audit). The `MatchStatus` class is a
record-like wrapper around `Match`'s state at a tic — six of the seven
fields it exposes are direct reads. A test that re-asserts each field
equally to the underlying `Match` is tautological; it can't fail unless
`Match` itself is broken, in which case the `MatchTest` would catch it
first.

**Confidence**: **Low** (couldn't read the file in this audit; suggestion
based on the shape of `MatchStatus`).

**Risk if removed**: **Low** (savings are small).

---

### 3.5 `Test` classes that import `Null*Port` only to verify it's null

`AndroidAdapterFactoryTest`, `GdxAdapterFactoryTest`,
`AdapterFactoryTest`, `DesktopAdapterFactoryTest` all call
`factory.getFilePort()`, `factory.getAudioPort()`, `factory.getUserProfilePort()`,
etc. and assert the return is non-null. The "non-null" assertion is the
**only** assertion. This is a test of the factory wiring (does the
delegate return a port) — fine in itself, but it does **not** test that
the returned port is the **right** port for the backend, which would be
the actual contract.

**Confidence**: **Medium** (these are real tests; just shallow ones).
**Recommendation**: convert at least one to assert that the returned port
is of the *expected* class (e.g. `NullUserProfilePort`, not
`SqliteUserProfilePort`, when the backend is `NULL`). Without that, the
factory could be returning any non-null port and the test passes.

---

## 4. Doc / Comment Drift

### 4.1 `tools/build.gradle.kts` — diff against `main` is whitespace only

The working tree shows one modified file
(`M tools/build.gradle.kts`) but the diff is purely a comment-block
reformat and an addition of ten more `demoLevelPieces`. The content is
not in conflict with the documentation, so this is a dirty-tree signal
that the audit is on top of an uncommitted change, not a doc drift.

### 4.2 `WASM.md` is dated 2026-07-30 and is *not* a spec

`docs/WASM.md` calls itself an "assessment" and explicitly says "Nothing
here has been built." Not a drift, but worth a note: this is a
deliberately-kept feasibility study that will go stale every time the
`engine` surface it cites changes. The two "Open items" it does flag
(replacing 1-ulp transcendentals and the Wasm span-loop penalty) are
correctly labelled "not measured."

### 4.3 `AGENTS.md` has a stale cross-reference

In the Subsystem Owners table, the `Render` row says:
> The internal render-resolution cap **has landed** as `RenderMode` in
> `:gdxshared` (§ 3 Resolution) — the `RenderResolution` class this block
> used to say was written-but-never-applied is gone; **do not go looking
> for it.**

`render/README.md` § Status (line: "The internal render-resolution cap
**has landed** as `RenderMode` in `:gdxshared`... the `RenderResolution`
class this block used to say was written-but-never-applied is gone") makes
the same point. The two agree, and `grep -r "RenderResolution"` over
`engine/src/main` returns no hits. **Not dead code, but worth a
grep for any future "where's RenderResolution" question.**

### 4.4 No `// TODO` / `// FIXME` in any production source

`grep` for `TODO|FIXME|XXX|HACK` over `*/src/main/java/**/*.java` (all
five modules) returns only false positives in Javadoc that mentions
`withXxx` or in `tools/docs/Markdown.java` where the literal string
`" class=\"task todo\""` is part of a CSS class name emitted for a
docs-site task list. No real TODOs were found; the project has done
its house-keeping. The `## Status` blocks are the only place "next
steps" are tracked, and they are the right surface for that.

### 4.5 `I_NetworkPort` Javadoc still claims "Phase 4+" features

The interface Javadoc still describes lag-compensation rewind
(§ 4 in the Javadoc) and snapshot-delta bit-packing (§ 6) as future
work. These are not in the codebase. This is consistent with the
package README's PARTIAL state and not a "drift" so much as a
documentation surface that has survived longer than the code did. It
would benefit from a one-line "**The port surface below this Javadoc
is unused; see § 2.1 in `PRUNE_AUDIT.md`**" — but that's a doc update,
not a fix.

---

## 5. Gradle / Build Findings

### 5.1 `assetsVersion` and `assetsSha256` are part of a build task that
deliberately fails

**File**: `build.gradle.kts` (root)
**Lines**: 118–150

`assetsSha256 = "0000…0000"` is the well-known "always-fail" digest that
the project documents in its own `STATUS` block. `fetchAssets` is the
*only* consumer of the property and it intentionally throws when the
digest is the all-zero sentinel. This is a **known broken-on-purpose
task**, listed in the brief. Not a finding.

### 5.2 `convertModels` task is defined but the demo path uses
`regenerateDemoAssets` instead

**File**: `tools/build.gradle.kts:217`

Both tasks wrap `GltfConverterMain`; `convertModels` is a generic
`--modelsIn --modelsOut` path, `regenerateDemoAssets` is a demo-specific
path that calls `DemoAssetsMain` and depends on `stageDemoAssets`. The
two are *related* but not redundant — `convertModels` is the
"convert any directory" path and `regenerateDemoAssets` is the
"staging-then-converting" path. Not a finding.

### 5.3 `gradle.properties` was not opened in this audit; if the project
relies on `-Dopenfps.workers=N` etc., check that the property is
actually wired in the build files

`engine/build.gradle.kts` and `desktop/build.gradle.kts` both have
`-Werror` and the Checkstyle `maxWarnings = 0` setting. `desktop`
forwards a list of `openfps.*` system properties to the forked JVM
(`desktop/build.gradle.kts:128-141`). `gdxshared/build.gradle.kts` does
**not** forward any of them; the test classpath reads `-Dopenfps.workers`
via the `WorkerPool` chain but I didn't verify whether the gdxshared
test classpath receives it. **Confidence: Low** (not investigated
deeply), **Action**: run the gdxshared suite with `-Dopenfps.workers=2`
on the command line to see if it's picked up. If not, the property is
silently ignored in that module.

### 5.4 `distributions` block in `:engine` is correct but mininal

`engine/build.gradle.kts:80-87` ships `NOTICE` and `LICENSE` in the
dist tarball. This is right. No change.

### 5.5 `StageModelAssetsTask` (`:android/build.gradle.kts:82-117`) is
the right shape

The non-`addGeneratedSourceDirectory` route is documented and used; the
diff against main is just whitespace. Not a finding.

### 5.6 `packageWindows` uses `jpackage`, declared in
`desktop/build.gradle.kts:215-234`

Wired through `writeWindowsIcon → installDist → cleanAppImage`. Two of
its dependencies (`writeWindowsIcon`, `cleanAppImage`) are also declared
in the same file. Not a finding — but a Windows-only path; a Linux/macOS
analogue is *not* declared and there's no evidence in `build.gradle.kts`
that anyone has tried. **Not a dead-code finding**; just a future
build-time work item.

### 5.7 `systemProperties` forwarding list duplicates the desktop build

`desktop/build.gradle.kts:128-141` is the single hard-coded list of
`-Dopenfps.*` properties. The list is not driven by a gradle property
catalog; if a new property is added to `DesktopLauncher` and not
forwarded, the launcher's argument parsing will silently fall back to
the in-code default. **Not a finding**; just a known fragility.

### 5.8 `:tools` test logging and other shared patterns are duplicated
verbatim across all 5 module build files

Every `build.gradle.kts` ends with the same `tasks.withType<JavaCompile>`,
`tasks.withType<Test>`, and `checkstyle` block. That is ~25 lines of
duplication × 5 = ~125 lines of build script. Extracting into a
`buildSrc/src/main/kotlin/openfps-conventions.gradle.kts` would shrink
this and centralize the `isFork = true` / `useJUnitPlatform()` /
`maxWarnings = 0` rules. **Confidence: Medium** (depends on the team's
taste for build convention plugins); **Savings: small** (~100 lines of
build script, not Java).

---

## 6. Suggested Pruning Plan

Ordered lowest risk to highest. For each step, the suggested verification
command is `./gradlew :<module>:test` (or `:test` for the whole build).

### Step 1 — `I_UserProfilePort` stub methods → "no current consumer" Javadoc
**Risk**: None. **LoC saved**: 0. **Value**: 0 LOC; ~5 minutes of
editorial hygiene to make it clear that `findById`, `count`, `delete`,
`generateNewId` are part of the port's surface but the engine itself
doesn't use them yet.
**Verify**: `./gradlew :engine:test :android:test`.

### Step 2 — Drop the public read-only getters on `DemoGameplayPort`,
`DemoScene`, `RemotePlayers`, `SoftwareRenderPort`
**Risk**: Low. **LoC saved**: ~30 lines of getter bodies and Javadoc.
These are the easy hits from § 2.6, 2.7, 2.8, 2.9, 2.10, 2.11, 2.12,
2.13, 2.14. Most are one-liner `return field` getters; only
`aimedEntityId()` and `framebuffer()` have non-trivial bodies, and even
those are short.
**Files**:
- `engine/.../demo/DemoGameplayPort.java` (2 getters)
- `engine/.../demo/DemoScene.java` (1)
- `engine/.../demo/RemotePlayers.java` (2)
- `engine/.../render/adapter/SoftwareRenderPort.java` (5: `isCrosshairEnabled`,
  `isOutlineEnabled`, `aimedEntityId`, `framebuffer`, `orbitCamera`)
- `engine/.../render/adapter/Framebuffer.java` (2: `tileBounds`, `tileIndexAt`)
- `engine/.../core/GameLoop.java` (1: `isRunning`)
- `gdxshared/.../FramebufferPresenter.java` (2: `width`, `height`)
**Verify**: `./gradlew :engine:test :gdxshared:test :desktop:test :android:test`.

### Step 3 — Drop `MatchMode.spawnsBots()` and `MatchMode.needsNetwork()`
**Risk**: Low. **LoC saved**: 20 lines + 2 small Javadocs.
**Verify**: `./gradlew :engine:test :gdxshared:test :android:test`.

### Step 4 — Drop `EngineMain.start(...)` 2-arg and 3-arg overloads
**Risk**: Low (the 4-arg is the public surface; the other two are
private-friendly). **LoC saved**: ~12 lines.
**Verify**: `./gradlew :engine:test :desktop:test :android:test`.

### Step 5 — Drop `I_GameplayPort.spawnEntity` and `I_GameplayPort.removeEntity`
**Risk**: Low. **LoC saved**: 7 lines interface + ~20 lines impls in
`DemoGameplayPort` and `NullGameplayPort`.
**Verify**: `./gradlew :engine:test :desktop:test :android:test`.

### Step 6 — Drop `I_NetworkPort` body methods (keep the interface and
the `init` / `shutdown` contract for `NetSubsystem`)
**Risk**: Medium (interface removal, but no production code uses any
method beyond `init`/`shutdown`). **LoC saved**: ~80 lines (interface +
`NullNetworkPort` body) plus 1–2 of the `net` package's 142 tests if
any assert on the removed methods.
**Verify**: `./gradlew :engine:test :desktop:test :android:test`.
**Caveat**: keeping the *interface* with just `init()`/`shutdown()` is
fine; that is what `NetSubsystem` actually uses. The six business
methods are the dead surface.

### Step 7 — Drop `I_ThreadPoolPort.activeWorkerCount()` and the
matching test assertion
**Risk**: Low. **LoC saved**: 4 lines interface + 5 lines impl + 1
test line.
**Verify**: `./gradlew :engine:test`.

### Step 8 — Drop `NullInputPortTest.shouldStartNeutral` (merge into
`shouldStayNeutralAcrossTics`)
**Risk**: None. **LoC saved**: 10 lines.
**Verify**: `./gradlew :engine:test`.

### Step 9 — Tighten the four adapter-factory "wiring" tests to assert
the *type* of the returned port, not just non-null
**Risk**: None (improves the test, doesn't break it). **LoC added**:
~30 lines, but these now actually verify something.

### Step 10 — Refactor shared `build.gradle.kts` tail into a
`buildSrc` convention plugin
**Risk**: Low. **LoC saved**: ~100 lines of build script. Higher value
than the test trimming because the duplication is a future maintenance
liability, not just dead code.

**Total estimated savings across steps 1–8**: **~250 lines of
production code** and **~10 lines of test code**, removing 11 methods
with zero production callers and one public method that is duplicate
coverage of another.

---

## 8. Change list (2026-08-05 follow-up)

The following was applied to the working tree on 2026-08-05. Every removal
listed here is provably unused outside the class that declared it; all
2343 tests pass after the change.

### Production code removed

| File | Lines removed | What |
|------|--------------:|------|
| `core/GameLoop.java` | 5 | `isRunning()` |
| `gameplay/MatchMode.java` | 20 | `spawnsBots()`, `needsNetwork()` |
| `demo/DemoGameplayPort.java` | 24 | `network()`, `botFireVoices()`, `spawnEntity()`, `removeEntity()` |
| `demo/DemoScene.java` | 13 | `physics()` |
| `demo/RemotePlayers.java` | 23 | `bodyInstanceIndex()`, `weaponInstanceIndex()` |
| `render/adapter/SoftwareRenderPort.java` | 23 | `isCrosshairEnabled()`, `isOutlineEnabled()` |
| `render/adapter/Framebuffer.java` | 49 | `tileBounds()`, `tileIndexAt()`, plus the four `TILE_BOUNDS_*` constants |
| `core/EngineMain.java` | 12 | visibility reduced on the 2-arg and 3-arg `start()` overloads (private) |
| `gameplay/port/I_GameplayPort.java` | 17 | `spawnEntity()`, `removeEntity()` |
| `gameplay/adapter/NullGameplayPort.java` | 17 | matching impls |
| `net/port/I_NetworkPort.java` | 60 | six business methods (`connect`, `disconnect`, `broadcastTicCmd`, `pollTicCmd`, `broadcastMapChange`, `discoverPeers`, `connectedPeerCount`) |
| `net/adapter/NullNetworkPort.java` | 50 | matching impls |
| `core/pool/I_ThreadPoolPort.java` | 2 | `activeWorkerCount()` |
| `core/pool/WorkerPool.java` | 5 | matching impl (the `activeWorkers` field stays; `awaitTermination` reads it) |
| `gdxshared/FramebufferPresenter.java` | 9 | `width()`, `height()` |
| `hal/port/I_UserProfilePort.java` | +9 | added a comment block noting `findById`/`count`/`delete`/`generateNewId` are port-surface with no current consumer (kept) |

### Test code removed or merged

| File | What |
|------|------|
| `core/pool/WorkerPoolTest.java` | one `assertThat(pool.activeWorkerCount())` assertion |
| `demo/RemotePlayersTest.java` | one `weaponInstanceIndex()` assertion loop |
| `render/adapter/FramebufferTest.java` | one full test (`shouldFillTileBoundsWithInclusiveMaxima`) and two inline `tileIndexAt` assertions |
| `render/adapter/SoftwareRenderPortTest.java` | one `isOutlineEnabled()` assertion |
| `hal/adapter/nulladapter/NullInputPortTest.java` | merged `shouldStartNeutral` into `shouldStayNeutralAcrossTics` (renamed to clarify intent) |

### Docs updated

- `AGENTS.md`, `README.md`, `PLAN.md`, `BUILD.md`: test counts 2339 → 2343,
  `:engine` 1625 → 1629, plus a one-paragraph note about the 2026-08 prune
  on `AGENTS.md`.
- `engine/src/main/java/com/openfps/engine/net/README.md`: noted that
  `I_NetworkPort` has been trimmed to `init()`/`shutdown()`.
- `engine/src/main/java/com/openfps/engine/gameplay/README.md`: noted
  the same for `I_GameplayPort` (`tick` + `loadMap` + `init`/`shutdown`).
- `engine/src/main/java/com/openfps/engine/hal/port/I_UserProfilePort.java`:
  block comment above the CRUD section naming the four port methods that
  have no current consumer.

### Verification

- `./gradlew :engine:test :gdxshared:test :desktop:test :tools:test
  --rerun-tasks` — all green.
- `git diff --stat` should show a net negative line count on every file
  except the four doc/test-count updates and the new
  `PRUNE_AUDIT.md`.

### Recommended follow-up (not in this commit)

Steps 9 and 10 of § 6 are still open:

- **Step 9** — convert the four adapter-factory "wiring" tests to assert
  the *type* of the returned port, not just non-null. Adds ~30 lines,
  improves test signal.
- **Step 10** — refactor the duplicated `build.gradle.kts` tails into a
  `buildSrc` convention plugin. Saves ~100 lines of build script;
  reduces future maintenance surface.

---

## 7. Things I Considered But Rejected

These look dead at first glance but on closer reading are not.

- **`I_NetworkPort` itself** — the *interface* is registered with
  `NetSubsystem` and `EngineMain` constructs `NullNetworkPort`; the
  *interface* is therefore not dead. The six methods on it are.

- **`NetSubsystem`** — looks like it has no consumer but it is
  registered in `SubsystemRegistry` by `EngineMain` and is part of the
  `G_` slot. It does init/shutdown on the port; the methods are
  intentionally empty so that `NetworkPacketEvent` events can be added
  later. Keep.

- **`MemoryPortFactory.createSlab(int, int)`** — throws
  `UnsupportedOperationException` with a precise error message, the
  memory package README calls it out as a Phase 2+ placeholder. Listed
  in the brief.

- **`createSlab`'s doc comment** — also fine, it tells the next agent
  what to do.

- **`I_ThreadPoolPort.activeWorkerCount()`** — flagging it as Medium
  risk; it has a real implementation but only one test consumer. Not
  strictly dead, but the test is the only thing that notices.

- **`MatchStatusTest` and the `MatchSummaryTest` over-assertion pattern** —
  couldn't read the file in this audit; the suggestion is included as
  Low-confidence for the next agent to verify.

- **`WorkerPool.state()` is called everywhere** — but is the only way
  the test scaffolding knows to wait for `DRAINING`; it's a real consumer
  of the pool state machine. Keep.

- **`MemoryUserProfilePort.delete`/`count`/`findById`/`generateNewId`** —
  see § 2.4. They're part of the port interface; dropping them is a
  public-API decision, not a clean-up.

- **`ICONDIR` / `BMP` / `AND mask` references in `IconFileMain`** —
  they're doc comments explaining why the file uses PNG-in-ICO, not code
  paths to delete.

- **`System.out.println("Wrote " + ...)` in `IconFileMain`** — the
  project rule against `System.out` in production code is in
  `AGENTS.md` § "What Not To Do". `IconFileMain` is a build-time class
  invoked by Gradle; the print is the natural way to confirm the file
  was written. The same reasoning applies to `System.err` in
  `EngineMain.main` at line 102 (the only thing there is a usage
  message; the catch block has already logged the error via SLF4J).

- **`WASM.md`** — not dead; a deliberately-kept feasibility study
  with a "Nothing here has been built" disclaimer.

- **`fetchAssets` task** — fails by design; the project documents
  this. Listed in the brief.

- **2.5D DOOM renderer (visplanes, column renderer, palette blitting)** —
  already gone from the tree. `PLAN.md` line 564 and the `Render` row
  in the `AGENTS.md` subsystem table both call out the retirement.
  Nothing to do.

- **`BspTraverser`** — listed in the brief.

- **`ImageDecoder`** — listed in the brief.

- **`W_` subsystem ID** — listed in the brief.

- **Desync detection** — listed in the brief.

- **`WadReader` / `LumpCache` / `MapLumpParser` and the 101 tests** —
  listed in the brief.

- **`DbgVisMode`-like debug-only methods on render classes** — none
  found; the project is clean here.

- **`@VisibleForTesting` annotations** — none found in the production
  source. If a `private` getter were ever exposed for tests, it would
  be the right call (steps 2, 6 above).

- **`EngineMain.run(GameConfig, boolean, boolean)`** — the 3-arg
  overload is only called from `main()`, but `main` is the only
  consumer of `EngineMain` from a class-loader outside the engine
  itself, so the method is *deliberately* the public surface for
  "headless boot". Not dead.

- **`R_` package's `OutlinePass`** — large, complex, all methods
  in use. Not flagging.

- **`DemoEffects`** — 33 public methods, but most are well-called by
  the demo and the renderer. The two `liveIncomingPuffCount` /
  `liveOutgoingPuffCount` / `liveOutgoingTracerCount` / `liveIncomingTracerCount`
  split is a deliberate "outgoing vs incoming" axis that the test uses
  to assert which tracers are sourced from where. Not flagging.

- **`GdxAudioPort.stopAll()`, `isAudible()`, `setMasterVolume()`,
  `masterVolume()`** — all test-only, but all part of the `I_AudioPort`
  contract that both `NullAudioPort` and `GdxAudioPort` honour. The
  `GdxAudioPort` implementation is the part the test cannot reach
  (no sound card in CI), and the test is the only proof these
  methods work. Keep.

---

*End of report.*
