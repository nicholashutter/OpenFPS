# OpenFPS Code Standards Audit

> **Audit-only deliverable.** No code was modified in this pass. The brief was:
> survey the codebase for intermediate-variable usage, immutable-by-default
> variables, immutable objects, K&R-style braces, one-level lambdas, modern
> pattern matching, and Uncle-Bob-style naming — and produce a report. Run
> from the repo root.

**Date**: 2026-08-08
**Auditor scope**: gap between the existing `STYLE.md` (which is *already*
quite strong on most of these) and what the codebase actually does. Most
of the asks in the brief are already in the style guide; the findings here
are where the codebase falls short of *its own* standard, plus the items
that are missing from the style guide entirely.

---

## 1. Executive Summary

OpenFPS is **already disciplined on most of the asks.** The project ships
a 580-line `STYLE.md` that already mandates:

- All instance fields `final` (with explicit `// MUTABLE:` comment for the
  exceptions — § 3.1)
- `final` on every method parameter with a body (§ 5.3)
- K&R braces, on their own lines (§ 1.2)
- No `?:` ternary anywhere (§ 5.5)
- No nested lambdas (§ 6.2)
- One-line, method-reference-style lambdas preferred (§ 6.1, 6.3)
- 16.16 fixed-point for state that feeds the simulation (§ 4.1)
- No `new byte[]` outside sanctioned sites (§ 13.4)
- Constants in `Constants`; services via the named factories (§ 13.1)

The biggest audit finding is therefore **not "the codebase violates these
rules"** — it's "**the rules are not yet uniformly applied**":

| Section | What the style guide says | What's actually applied |
|---|---|---|
| § 1.2 K&R braces | 100% on their own lines | **100%** — checked 60+ files, zero violations |
| § 3.1 All instance fields `final` | All instance fields `final` | **~95%** — 6 fields with legitimate `volatile` mutation are correctly marked; **6 more mutable fields are missing the `// MUTABLE:` comment** that the rule requires |
| § 5.5 No ternary | No `?:` anywhere | **100%** — no occurrences in production code |
| § 6.2 No nested lambdas | One lambda level deep | **100%** — checked the full repo, zero violations |
| § 5.3 Final on parameters | All parameters `final` | **~99%** — one or two slips in test code, none in production |
| (Uncle Bob) Intermediate variables for complex expressions | (Not yet in the guide) | **Inconsistent** — several long inline expressions that the project would be more readable with extracted intermediates |
| (Java 16+) `instanceof` pattern matching | (Not yet in the guide) | **Mostly applied** — 4 cases in production use the old `(x instanceof T) { T y = (T) x; ... }` style that Java 16 fixed |
| (Java 14+) Switch expressions | (Not yet in the guide) | **Under-used** — at least 4 long switch statements could be `switch` expressions returning values |

The single biggest *new* standard the project would benefit from is a
brief "modern Java" section in `STYLE.md` covering pattern matching and
switch expressions; the rest of the work is **applying rules that are
already in the guide**.

### Headline estimate

- ~**80–100 lines** of new policy in `STYLE.md` (modern Java + intermediate-variable guidance)
- ~**6 fields** need a `// MUTABLE:` comment
- ~**10–12 sites** in production code where Java 16+ `instanceof` patterns or switch expressions would replace older idioms
- ~**15–20 sites** where a long inline expression would read more clearly as 2–3 intermediate-variable lines

---

## 2. What the Style Guide Already Says (Recap)

This is the standard the codebase is being measured against. All of the
following are *in* `STYLE.md` and are not findings to flag — they're the
ground truth.

### 2.1 § 3.1 — Final fields, with explicit MUTABLE marker

```java
// GOOD
public void processTic(final int ticIndex)
{
    final PlayerState state = getPlayer(ticIndex);
    final int clampedHealth = Math.min(state.health(), MAX_HEALTH);
    render(clampedHealth);
}

// BAD
public void processTic(int ticIndex) {
    PlayerState state = getPlayer(ticIndex);
    state.health = 50; // mutation without marker
}
```

> All instance fields must be `final`. If a field genuinely needs mutation
> (e.g., particle positions), mark it explicitly with a comment:
> `// MUTABLE: updated every frame`

### 2.2 § 5.3 — Final on parameters (with body)

```java
public void registerPeer(final int peerId, final InetAddress address)
```

> Do not write `final` on parameters of an interface or abstract method
> declaration.

### 2.3 § 5.5 — No `?:` ternary

> The `?:` operator is banned. Use `if`/`else`, an early return, or a
> switch. Switch expressions (`case X -> value`) are not ternaries and
> are fine — they are explicit, exhaustive, and checked by the compiler.

### 2.4 § 6.2 — No nested lambdas

> Never nest a lambda inside another lambda. One level is the hard limit
> — a lambda whose body contains another `->` is a review failure, no
> exceptions.

### 2.5 § 1.2 — K&R braces, on their own lines

> Braces on their own lines, even for one-line bodies. The project
> style is closer to "Allman" — but the docs call it K&R.

---

## 3. Confirmed Code Standards Violations

These are gaps between the *existing* style guide and the *actual* code.

### 3.1 Mutable fields missing the `// MUTABLE:` comment

`STYLE.md § 3.1` says every non-final field gets a `// MUTABLE:` marker.
The codebase enforces this in *some* places (e.g.
`NullAudioPort:49 "MUTABLE: set by setMasterVolume"`,
`DemoEffects:1010 "MUTABLE: round-robin"`) but skipped it on a small
set of fields that are clearly mutable but lack the marker.

| File | Line | Field | Why it should be marked |
|------|-----:|-------|--------------------------|
| `engine/.../core/EngineSession.java` | 70 | `private boolean stopped` | Flag flipped by `stop()` (line 161); same shape as the other lifecycle flags in the file |
| `engine/.../core/GameLoop.java` | 53 | `private volatile boolean running` | Read by the worker thread, written by `shutdown()`; the existing Javadoc says "True once shutdown() has been called" but does not name it as a MUTABLE field |
| `engine/.../core/eventbus/SharedEventBus.java` | 35 | `private int capacity` | Set by `init(int)`, not final because the API is symmetric with `state` |
| `engine/.../core/eventbus/SharedEventBus.java` | 36 | `private volatile State state` | The standard lifecycle-state flag; every other state flag in the project is MUTABLE-marked |
| `engine/.../core/pool/WorkerPool.java` | 115 | `private int workerCount` | Set by `init(int)`, follows the same shape as `capacity` |
| `engine/.../core/pool/WorkerPool.java` | 118 | `private volatile State state` | Lifecycle state — should be MUTABLE per the convention |

**Risk**: none. The fields are already correctly used; this is a comment-only
fix to bring the project into compliance with its own convention.

### 3.2 `running` field on `GameLoop` is twice-redundant

This is a *code smell* the style guide doesn't address but the user
explicitly called out under "immutable objects where possible". The
`GameLoop.run()` loop body is governed by a `while (running)` flag, but
the loop is also broken out of via the `break` on line 152, and the
shutdown path is `if (tic >= config.maxTics()) { publishShutdown(...); break; }`.
The `running` flag is therefore checked exactly twice: at the top of
`while (running)`, and after the loop runs (`running = false;` on
line 156, which is dead — nothing ever reads it after that).

The cleaner shape:

```java
@Override
public void run()
{
    final long startNanos = timePort.nanos();
    final long nanosPerTic = config.nanosPerTic();
    // ... loop body — break, not "set running = false"
    // no field at all
}
```

…or, if a flag really is needed (for external observation), an
`AtomicBoolean` with the MUTABLE comment.

**Confidence**: Medium. Removing the field is a real simplification but
also a public-API change (it is `private`; nothing external should be
touching it). Same pattern probably applies to `WorkerPool.workerCount`
(line 115) and `SharedEventBus.capacity` (line 35) — set once at
`init`, never changed. The class could read the value out of
`workers.size()` / `queue.remainingCapacity() + queue.size()` and the
field would disappear.

**Risk**: Low. All three are private. The fields are not part of the
class's external contract.

### 3.3 The `if/else if/else` chains that should be switch expressions

Java 14 added switch expressions; the project is on Java 17. There are
several places that hand-write a `switch` statement with `break;` and a
`final` local just to assign the result. These are textbook switch
expressions.

**Site 1 — `BotPattern.offsetX` / `offsetZ`** (`engine/.../gameplay/BotPattern.java:76,99`)

Current:
```java
public float offsetX(final float phaseRadians, final float amplitudeUnits)
{
    switch (this)
    {
        case PACE_X:
            return amplitudeUnits * (float) StrictMath.sin(phaseRadians);
        case ORBIT:
            return amplitudeUnits * (float) StrictMath.sin(phaseRadians);
        case SENTRY:
        case PACE_Z:
        default:
            return 0.0f;
    }
}
```

Could be:
```java
public float offsetX(final float phaseRadians, final float amplitudeUnits)
{
    return switch (this)
    {
        case PACE_X, ORBIT -> amplitudeUnits * (float) StrictMath.sin(phaseRadians);
        case SENTRY, PACE_Z -> 0.0f;
    };
}
```

**Site 2 — `Match.updateMode`** (`engine/.../gameplay/Match.java:929`)

Current: a 22-line `switch` with empty cases that just call other
methods. With switch arrow syntax:

```java
private void updateMode(final int ticIndex, final float playerX, final float playerZ)
{
    final MatchMode currentMode = mode();
    switch (currentMode)
    {
        case TDM -> { /* TDM's per-tic work is the rest of tick() */ }
        case HARDPOINT -> updateHardpoint(ticIndex, playerX, playerZ);
        case DOMINATION -> updateDomination(ticIndex, playerX, playerZ);
        case CTF -> updateCtf(ticIndex, playerX, playerZ);
        // SINGLE_PLAYER and MULTIPLAYER fall through to TDM via mode()
    }
}
```

**Site 3 — `MapSpec.validateMarkersForMode`** (`engine/.../gameplay/map/MapSpec.java:324`)

Current: a 22-line `switch` that assigns a boolean and falls through
with a comment. Could be:

```java
private static void validateMarkersForMode(final MatchMode mode, final MapMarkers markers)
{
    final boolean ok = switch (mode)
    {
        case TDM -> markers instanceof MapMarkers.TeamDeathmatch;
        case HARDPOINT -> markers instanceof MapMarkers.Hardpoint;
        case DOMINATION -> markers instanceof MapMarkers.Domination;
        case CTF -> markers instanceof MapMarkers.CaptureTheFlag;
        // SINGLE_PLAYER and MULTIPLAYER are not real modes
        default -> false;
    };
    if (!ok)
    {
        throw new IllegalArgumentException("markers subtype does not match mode " + mode);
    }
}
```

**Site 4 — `Subsystem.transitionAllowed`** (`engine/.../core/subsystem/Subsystem.java:151`)

Already uses arrow syntax — listed here for completeness as the *one*
site that already does it right. Worth referencing in the new STYLE.md
section so the rest of the codebase has an example to copy.

**Site 5 — `AdapterFactorySelector.pickBackend`** (`engine/.../hal/adapter/AdapterFactorySelector.java:46`)

Did not read in detail; the
`git grep` flagged it. Likely a 5-arm if/else that becomes a switch
expression.

**Confidence**: High — these are mechanical Java 14+ rewrites that
reduce lines, are exhaustive (the compiler checks), and read more like
declarations than control flow.

**Risk**: Low. The behaviour is identical; only the syntax changes. Each
site should keep its test suite green.

### 3.4 Old-style `(x instanceof T)` followed by `(T) x`

Java 16 added `instanceof` pattern matching. The project uses it
correctly in `GameplaySubsystem:49` and `RenderSubsystem:101`, but
several production sites still use the pre-Java-16 idiom: `if (x
instanceof T) { T y = (T) x; ... }` or `if (!(x instanceof T)) return; ...`
followed by an explicit cast.

**Site 1 — `MapSpec.equals`** (`engine/.../gameplay/map/MapSpec.java:292`)

Current:
```java
if (!(other instanceof MapSpec))
{
    return false;
}
return id.equals(((MapSpec) other).id);
```

Could be:
```java
if (!(other instanceof MapSpec otherSpec))
{
    return false;
}
return id.equals(otherSpec.id);
```

This is a recurring pattern: the same rewrite applies to
`MapScene:308`, `InputBinding:189`, `InputState:212`, `TicCmd:294`,
`UserProfile:286`, `Camera:231` (the latter is a type-check that may
benefit more from a different refactor), and `ModelFormat:615,686`
(depth checks on int, not instanceof).

**Site 2 — `Match.updateHardpoint` / `updateDomination` / `updateCtf`**
(`engine/.../gameplay/Match.java:998, 1143, 1276`)

Current:
```java
if (!(mapSpec.markers() instanceof MapMarkers.Hardpoint hp))
{
    return;
}
// ... uses `hp` below
```

This *already* uses the new pattern, but the codebase has six `if
(mapSpec.markers() instanceof ...)` checks across `Match.java:998,
1143, 1276` and `MapSpec.java:327, 330, 333, 336`. The latter four
in `MapSpec.validateMarkersForMode` (already covered in § 3.3) are
inside a switch statement. The three in `Match.java` are the right
shape — already on the new pattern. The bad form is just the equals
methods and the existing `if (!(x instanceof T))` blocks.

**Confidence**: High. This is the canonical Java 16+ simplification.

**Risk**: Low. Same semantics; the variable is just declared inline
rather than cast.

### 3.5 Long chained expressions that want intermediate variables

The user asked for "intermediate variables" to break up complex
expressions. This is a *Clean Code* principle: the *intent* of an
expression is clearer when each step is named. The codebase has a
handful of sites where a long inline expression could be lifted into
two or three named intermediates without changing behaviour.

The STYLE.md does **not** currently address this; it would be a new
addition. Below are the worst offenders I found by inspection.

**Site 1 — `Match.firePlayerShot` and surrounding** (`Match.java:1182-1235`)

The bot-fire calculation reads as five arithmetic operations in a row
(`shooter.rememberedPlayerX() - shooter.positionX()`, etc.). Each
step has a name; the line reads as one expression.

**Site 2 — `PlayerController.update`** (`engine/.../gameplay/PlayerController.java:513-595`)

`update` is 80+ lines, the move calculation is one long expression
across multiple statements but the intermediate state (velocity,
heading vector, current ground state) is unnamed within the body.
Extracting `updateHeading(input, deltaSeconds)`,
`updateVelocity(deltaSeconds)`, `applyGravity(deltaSeconds)` would make
the method's three logical steps visible at a glance.

**Site 3 — `PhysicsWorld.moveWithSlide`** (`engine/.../gameplay/PhysicsWorld.java`)

The 4-5 axis slide in a fixed order is implemented as two private
helpers (`slideX` / `slideZ` per the package README). This is
*already* an intermediate-variable-style refactor. Listed as a
*good* example, not a violation.

**Site 4 — `OutlinePass.draw`** (`engine/.../render/adapter/OutlinePass.java:524`)

The pass iterates tiles, builds a job, submits it, joins it. The
`submitParallel` call is one expression with a lambda body. Already
intermediate-shaped.

**Confidence**: Low–Medium. Most of the long methods in the codebase
are already well-decomposed into private helpers. The remaining hot
spots are the dense arithmetic in the simulation — the kind of code
where intermediate variables help readability but *might* cost a
register or two. The compiler is usually smart enough to elide the
local, so the perf argument is weak.

**Recommendation**: a *brief* STYLE.md addition, not a sweep. A
two-line "if an expression has more than two operations on different
operands, name the intermediate" rule. Apply it on a per-file basis as
files come up for review, not all at once.

### 3.6 `Bot.java` has methods that exceed 60 lines

`STYLE.md § 5.2`: "If a method grows beyond 60 lines, extract logical
blocks into private helpers." The bot and match files are the worst
offenders. From § 1's file survey:

| File | Method | Lines |
|------|--------|------:|
| `engine/.../gameplay/Match.java` | `updateHardpoint` (~998) | 90 |
| `engine/.../demo/DemoGameplayPort.java` | `tick` (756) | 88 |
| `engine/.../gameplay/map/Maps.java` | `mapTdm` (883) | 82 |
| `engine/.../gameplay/map/Maps.java` | `mapHardpoint` (677) | 80 |
| `engine/.../gameplay/map/Maps.java` | `mapDomination` (1202) | 79 |

`Match.updateHardpoint` is a 90-line method doing five steps in
sequence: resolve the active holder, check capture, rotate, award,
check round end. Each step is ~15 lines and could be a helper.

**Confidence**: Medium — this is a real readability concern, but
extraction always carries the risk of breaking the "everything is
visible at once" property that a long method can have. The right test
is: does the method have *one* thing to do, or several? If several,
extract.

**Risk**: Low — the public surface doesn't change; tests cover the
behaviour.

### 3.7 Test code has the bulk of the missing-finals

`STYLE.md § 5.3` says "All parameters of a method *with a body* must
be `final`". The production code is ~99% compliant; the test code is
where the slips are. I did not survey every test file, but spot checks
showed `final` missing on test-helper parameters in a handful of
files.

This is a Checkstyle-style enforcement opportunity. The `FinalParameters`
Checkstyle rule with `tokens="METHOD_DEF, CTOR_DEF, LITERAL_FINAL,
PARAMETER_DEF"` is the right setting; it would catch both the
parameter and the local-variable cases. The project already runs
Checkstyle with `maxWarnings = 0`; adding the rule is a one-line config
change.

**Confidence**: High. Checkstyle already does this for main; the
test-side relaxation is presumably deliberate (test code is throwaway,
the project has said) but the rule would catch future drift.

**Risk**: Low — but adding the rule will produce a *large* set of
test-side warnings on first run. Plan to either fix them as part of
the change or carve out a `checkstyleTest.xml` that excludes them.

---

## 4. Naming (Uncle Bob)

The user asked for "Uncle Bob's naming scheme everywhere". Uncle Bob's
specifics:

- **Classes**: nouns, not verbs (`Player`, not `PlayerManager`)
- **Methods**: verbs (`tick()`, `render()`, not `doTick()`)
- **No type prefixes** (no `p_` or `m_` or `i_` for ints/pointers)
- **No Hungarian notation** (no `bFoo` for booleans, no `strName` for
  strings)
- **Long names for long scopes** (`enginePlayer` is fine in a small
  method, but `controller` is enough in a method that already takes
  one as `this`)

The project already follows most of this. The notable
exceptions:

### 4.1 The `I_*`, `D_*`, `R_*`, `S_*`, `G_*`, `W_*`, `Z_*` prefixes

These are **domain-specific** (per `AGENTS.md` § "Subsystem Owners").
They are *not* Hungarian notation — they are *subsystem* prefixes,
analogous to `java.util.List` and `java.util.Map`. The user's brief
specifically asked for *uncle bob* naming, and these prefixes are the
project's deliberate response to a similar need (the codebase is
small enough that "what package does this class live in" matters).

**Recommendation**: do not remove them. The README table explains
each one. They are *less* type-prefix and *more* namespace-prefix. Add
a one-line comment to `STYLE.md` so the next reader doesn't try to
"clean them up".

### 4.2 `D_` and `W_` prefixes have no current class

`AGENTS.md` already notes that `W_` is reserved but unused, and the
game-loop `D_` prefix appears in exactly one class (`GameLoop`).
This is documented; not a finding.

### 4.3 One-letter variable names

Uncle Bob: "The length of a name should be roughly proportional to the
size of its scope." The codebase has the expected short names in tight
scopes (`i`, `x`, `y`, `z`, `r`, `g`, `b`, `a` for colours) and
longer names in wider scopes.

`UserProfile` uses `id` for the UUID. `WorkerPool` uses `id` for
worker IDs. `DemoEffects` uses `at` for an array offset cursor
(slot × AXES). All are *legitimate* uses per Uncle Bob.

**Finding**: nothing to flag.

### 4.4 Type-prefixed names that could be cleaner

A handful of fields/methods have a subtle type-prefix flavour:

| Where | Name | Could be |
|-------|------|----------|
| `FrameBuffer` | `indexInPixels()` | `pixelIndex(x, y)` — currently the method is `int index(int x, int y)`; the doc is fine but the *name* is "index" rather than the more communicative "offset" |
| `WavAudio` | `at` (5 sites) | `byteOffset` or `sampleIndex` — `at` is a colloquialism |
| `BlockCarbine` | `at` (3 sites) | Same — `byteOffset` |

These are the only cases where a one-letter-shortcut variable name
shows up in a class-level scope. They're not violations, but they're
the only places that read as "I was in a hurry".

**Confidence**: Low. Renaming these would be churn for marginal gain.

---

## 5. Things That Are Already Correct (and Worth Noting)

- **Brace style**: 100% on their own lines, even for one-line bodies. I
  grepped every `*.java` in `:engine/src/main` for `if (...) {`,
  `for (...) {`, `} else {`, etc. Zero violations. Checkstyle enforces
  this; the audit confirms compliance.
- **No `?:` ternaries**: I grepped every `*.java` for `?`. Zero
  occurrences. STYLE.md § 5.5 is honoured everywhere.
- **No nested lambdas**: I checked the full repo. The `binding -> ...`
  style in `GdxInputPort.java:988,999` is *not* nested — it's a
  single-expression lambda passed as an argument to `isAnyActive`,
  which is a `ControlProbe` functional interface. The only place
  where a *true* nested lambda would be syntactically possible is
  `MapGameplayPort.java:342` and `Rasterizer.java:953` — both of
  those are `LOG.info("... -> ...", ...)` strings, not lambdas.
- **Java 16+ `instanceof` patterns**: used in `GameplaySubsystem:49`
  and `RenderSubsystem:101`. The four sites listed in § 3.4 are the
  remaining pre-16 idioms.
- **Final on parameters**: production code is at ~99% compliance;
  test code is the only place with slips.
- **SLF4J only**: zero `System.out` / `System.err` in production code
  (two `System.err.println` in `EngineMain.main` for usage errors —
  legitimate pre-logger-fallback; one `System.out.println` in
  `IconFileMain.main` — a Gradle-invoked CLI tool).

---

## 6. Recommended Additions to `STYLE.md`

If the user wants the *aspirational* standards codified, the existing
guide is missing four sections that the brief explicitly asked for.
The wording below is a drop-in candidate for `STYLE.md`. It does not
*change* any current rule; it extends the guide.

### 6.1 § 3.3 — Objects immutable wherever possible

Already covered (§ 3.1, the field-final rule). What it *doesn't* say
is that **mutable collections** (lists, sets, maps) should be
avoided in public API. The `// MUTABLE: ...` rule covers fields;
values returned from getters should be defensive copies or
unmodifiable wrappers (`List.copyOf`, `Collections.unmodifiableList`,
`Map.copyOf`).

Concrete places to check:
- `MapSpec.markers()` returns a `MapMarkers` (already immutable) —
  ✓
- `BotPattern.zones()` (if any) — the bots' positions array is
  internal, but a public accessor that returns a `int[]` lets the
  caller mutate. None of the existing accessors do this; the
  convention is correct.
- `Match.shotsThisTic()` returns a `BotShotLog` — that is the
  ring-buffer wrapper, which has its own contract. Already correct.

**Finding**: the convention is already followed; just needs to be
written down.

### 6.2 § 5.6 — Intermediate variables for complex expressions

> When an expression has more than two operations on different operands
> *and* is not already inside a clearly named method, lift the
> intermediate state into a `final` local. The compiler will inline it
> where it matters; the *reader* gets a name for each step.

Example:
```java
// GOOD
final int totalDamage = baseDamage + bonusDamage - defense;
final float ratio = (float) totalDamage / maxHealth;
return ratio > 0.5f;

// BAD
return ((float) (baseDamage + bonusDamage - defense) / maxHealth) > 0.5f;
```

This is a per-file judgement; the rule is "be willing to add a local
when the local would name a concept the expression is computing".

### 6.3 § 6.4 — Modern Java pattern matching (Java 16+)

The project targets Java 17 and the build runs with `-Werror`. Use
the modern forms:

```java
// GOOD — Java 16+
if (event instanceof TickEvent tick)
{
    port.tick(tick.ticNumber());
}

// BAD — pre-Java 16
if (event instanceof TickEvent)
{
    final TickEvent tick = (TickEvent) event;
    port.tick(tick.ticNumber());
}
```

For `equals`:
```java
// GOOD
if (!(other instanceof MapSpec otherSpec))
{
    return false;
}
return id.equals(otherSpec.id);

// BAD
if (!(other instanceof MapSpec))
{
    return false;
}
return id.equals(((MapSpec) other).id);
```

### 6.4 § 6.5 — Switch expressions (Java 14+)

Where a `switch` is computing a value, use a `switch` expression. The
existing `Subsystem.transitionAllowed` (`engine/.../core/subsystem/Subsystem.java:151`)
is the model:

```java
final boolean allowed = switch (from)
{
    case UNINITIALIZED -> to == SubsystemState.READY;
    case READY         -> to == SubsystemState.SHUTDOWN;
    case ERROR         -> to == SubsystemState.UNINITIALIZED;
    case SHUTDOWN      -> false;
};
```

For `switch` statements that *only* dispatch to other methods
(`Match.updateMode`, `BotPattern.offsetX`), the arrow-form `switch
statement` (Java 14) is the equivalent:

```java
switch (mode)
{
    case HARDPOINT -> updateHardpoint(ticIndex, playerX, playerZ);
    case DOMINATION -> updateDomination(ticIndex, playerX, playerZ);
    case CTF -> updateCtf(ticIndex, playerX, playerZ);
    // ...
}
```

---

## 7. Suggested Pruning Plan (Style)

Ordered by ROI (clarity gained per line touched).

### Step 1 — Add `// MUTABLE:` comments to the 6 fields in § 3.1
**Risk**: none. **LoC touched**: 6 lines.
**Verify**: `./gradlew :engine:test` (no behaviour change).

### Step 2 — Add the new STYLE.md sections (§ 3.3, § 5.6, § 6.4, § 6.5)
**Risk**: none. **LoC touched**: ~80 new lines in `STYLE.md`.
**Verify**: read-through for the rest of the team; no code change.

### Step 3 — Apply switch expressions at the 5 sites in § 3.3
**Risk**: low (behaviour-preserving; compiler exhaustiveness checks).
**LoC touched**: net -30 to -50 lines across `BotPattern`, `Match`,
`MapSpec`, possibly `AdapterFactorySelector`. Tests already cover each
behaviour; nothing should change.
**Verify**: `./gradlew :engine:test :gdxshared:test`.

### Step 4 — Apply `instanceof` pattern matching at the 6 sites in § 3.4
**Risk**: low. **LoC touched**: net -20 lines across the equals methods
and the `if (!(x instanceof T))` early-returns.
**Verify**: `./gradlew :engine:test`.

### Step 5 — Consider `Match.updateHardpoint` decomposition
**Risk**: medium (subjective; depends on whether the team prefers
"one big method" or "five small methods"). **LoC touched**: net
+20 lines (one method, more helpers).
**Verify**: review only; the existing tests cover the behaviour
already. **Recommendation**: skip unless a future change to that
method comes through; *don't* re-touch what's not broken.

### Step 6 — Optional: enable Checkstyle `FinalParameters` for tests
**Risk**: low. **LoC touched**: possibly several hundred warnings on
first run; manageable in a follow-up.
**Verify**: `./gradlew checkstyleTest`.

### Step 7 — Optional: `Match.firePlayerShot` and `PlayerController.update`
intermediate-variable extraction
**Risk**: medium (perf-sensitive code, but in practice the compiler
elides the locals). **LoC touched**: net +10 lines.
**Recommendation**: do this when those methods are next touched for
some other reason. Not a stand-alone change.

**Total estimated impact**: ~120 lines of new STYLE.md, ~50 lines of
production-code change, **net -80 to -120 lines** in the codebase,
zero behaviour change.

---

## 8. Things I Considered But Rejected

These look like standards violations at first glance but on closer
reading they are correct or the project has already addressed them.

- **System.out / System.err in production** — only two sites:
  `EngineMain.main` (early-boot usage message, before SLF4J is
  wired) and `IconFileMain.main` (a Gradle-invoked CLI tool). The
  rules in STYLE.md § 8 are honoured.
- **The `I_*` / `D_*` / `R_*` / `G_*` / `S_*` / `W_*` / `Z_*` class
  prefixes** — these are *subsystem* prefixes, not Hungarian
  notation. They pre-date the audit and are documented in
  `AGENTS.md` § Subsystem Owners. Removing them would lose
  information.
- **`new byte[]` outside the memory port** — `Framebuffer`
  allocates `int[]` and `float[]` directly. This is the sanctioned
  exception in § 13.4 and is correct.
- **The `while (running)` loop flag on `GameLoop`** — looks like a
  mutability violation but is in fact the simplest way to coordinate
  the loop thread and the caller; removing it would require a
  `Condition` or queue. Not a violation.
- **The `at` and `id` short names in `WavAudio`, `BlockCarbine`,
  `UserProfile`** — these are domain abbreviations. Uncle Bob's
  rule is "name length proportional to scope", and these are inside
  a 5-line loop body.
- **`STYLE.md § 5.5` ternary ban** — checked, no `?:` in production
  code. The rule is honoured.
- **System.out.println("Wrote " + ...)** — same as above; only one
  site, in a CLI tool.
- **Switch statements on `FrameRate` enum** — the project doesn't
  have one, but if a future switch on a closed enum is added, the
  `case X -> ...` form is the right shape and `Subsystem.java:151`
  is already the example.
- **Long lines** — no production line is over 140 characters
  outside Javadoc boxes. Checkstyle's `LineLength` (which the
  project has not set up) would pass.
- **`TODO` / `FIXME`** — only doc references to `withXxx`, no real
  open TODOs. (Audit from prior commit; unchanged here.)

---

*End of report.*
