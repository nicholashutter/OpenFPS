# Demo — the playable first-person scene

> A room to stand in and a blaster to hold. This package is what turns the
> engine's working parts into something you can walk around in.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING |
| **Phase** | not a phase item — this is the playable demo content |
| **Tests** | 79 |
| **Registered** | not registered — not a subsystem; it is a composition root that consumes the engine |
| **Verified** | 2026-07-29 |

**Built.** `DemoScene` builds the room layout, the kit scale, the weapon pose and
one blaster per bot once and hands back an immutable `Scene`; `DemoModels` loads
the model set and reports honestly which of its three asset outcomes it got;
`DemoGameplayPort` is the only live `I_GameplayPort` in the codebase, driving a
`PlayerController`, aiming the software renderer once per tic, respawning the
player and restoring the whole world for a rematch; `DemoAssetException` refuses
to start a demo with no floor. `:desktop:run` shows all of it in a window.

**Not built.** Nothing outstanding.

**Blocked on.** Nothing.

**Next step.** This package follows the renderer. There is nothing to do here
until the `render/README.md` polish items — the bilinear quality toggle and the
independent internal render resolution — land.

## The rematch, and why it is a reset rather than a rebuild

`DemoGameplayPort.restartMatch()` is what the end screen's PLAY AGAIN button
runs. It restores four things with four different owners, and **together is the
operative word** — a reset that missed one would produce a room that was only
mostly new:

| What | Who owns it | What would go wrong |
|---|---|---|
| bots alive, at spawn, cooldowns and memory cleared; every counter zeroed | `Match.reset()` | a summary that is the sum of two rounds |
| position, yaw, pitch, vertical velocity | `PlayerController.respawnAt` | the second round starts wherever the first ended |
| in-flight tracers and smoke | `DemoEffects.clear()` | bolts from the last round still crossing the room |
| **the bot placements, republished** | `publishBotPlacements()` | **seven live, shooting, INVISIBLE opponents** |

That last row is the one worth knowing about. A dead bot is hidden by a
**degenerate transform override held in the renderer** — not by anything in the
simulation — so reviving it in `Match` does not make it visible again. Only
publishing its placement does. Every simulation assertion in the suite passes
while the room looks empty, which is why `DemoGameplayPortTest.Rematch` asserts
the transform the *render port* is holding rather than the state of the match.

A rebuilt `Match` was the other candidate and is worse. `Scene` is immutable and
a bot's model occupies an instance index fixed when the scene was built, so a
rebuild cannot have new `Bot` objects — it would be handed the same seven, still
dead, still where they fell. It needs a per-bot reset anyway, and all it adds is
an object identity the render thread would have to be re-published to see.

## Dying is a score

A death respawns the player at the spawn point after
`Match.RESPAWN_DELAY_TICS` — counted in **tics**, because a wall-clock delay
would elapse on different tics on two peers. `MatchState.LOST` now needs a
`deathLimit`, which defaults to `UNLIMITED_DEATHS`; a round ends when the room is
empty. `ScoreOverlay` shows kills, deaths and health, and a counting-down
`ELIMINATED!` band while the player is down — without it, a view that snaps back
to the spawn point is indistinguishable from a teleport bug, and was reported as
one.

## Not a subsystem

`demo` has **no `SubsystemId`, no `Subsystem` wrapper, and is never registered
with the core.** It is a *consumer* of the engine, sitting where a game would
sit: `DesktopLauncher` builds a `DemoScene`, hands the renderer its `Scene`, and
registers `DemoGameplayPort` as `P_` through `I_GameplayPortFactory`. Delete this
package and the engine still starts — it just has nothing to show.

Because it is a composition root, `STYLE.md` § 1.1 lets it name concrete types:
`DemoGameplayPort` imports `SoftwareRenderPort` directly, and has to, since
`I_RenderPort` has no `setCamera` and `render/README.md` § 12 keeps it that way.

## The only live `I_GameplayPort`

`gameplay/adapter/NullGameplayPort` is a stub and `PhysicsWorld` does not exist
yet, so `DemoGameplayPort` is the only implementation that actually plays
anything. Per tic, under one lock: latch input, present it as `I_PlayerInput`,
integrate one tic of movement, aim the camera. Its `deltaSeconds` comes from
`GameConfig.nanosPerTic()` rather than a measured frame time, because `GameLoop`
is a fixed-timestep clock — see `gameplay/README.md`.

## Art — a fresh clone has none

`docs/ASSETS.md` § 6 keeps upstream art out of git, so `assets/models/` is
gitignored. Two CC0 Kenney packs feed the demo when they *are* staged — the
**Prototype Kit** (level geometry) and the **Blaster Kit** (the viewmodel) —
both documented with URLs, digests and measured budgets in `docs/DEMO_ASSETS.md`.

Without them, `:tools:regenerateDemoAssets` emits **only** a 60-triangle greybox
room and **no weapon at all**. `DemoModels` reports which of the three outcomes
it got, and reports the fallback at `WARN`, precisely so nobody mistakes
generated greybox for Kenney art. If neither is present it throws
`DemoAssetException` naming the command that fixes it, rather than starting a
demo with no floor.

## The hand-tuned constants in `DemoScene`

These are empirical — arrived at by rendering the scene and looking at it — and
their derivations live in the Javadoc, which is worth reading before changing
any of them. The short version:

| Constant | Value | Why that number |
|---|---|---|
| `KIT_WORLD_SCALE` | 64 | Kenney authors on a **1-unit grid**; this engine inherited DOOM's geometry — a 41-unit eye, a 16-unit radius. Unscaled, a wall would be 1/41 of the player's eye height. 64 is DOOM's map grid, so one Kenney cell maps onto one DOOM cell |
| `FALLBACK_WORLD_SCALE` | 32 | Different kit, same job: `ProceduralRoom` authors 4-unit-tall walls, so 32 gives the fallback room the *same* ceiling and keeps the two demos comparable |
| `WEAPON_VIEW_SCALE` | 1.9 | Sized to look right on a screen, not in the world — see below |
| `WEAPON_VIEW_YAW_DEGREES` | 174 | 180° because the blaster's muzzle points down model **−z** while view space is **+z forward**, minus 6° of toe-in so a weapon sitting right of centre reads as aimed rather than carried |
| `WEAPON_VIEW_FORWARD` / `_RIGHT` / `_DOWN` | 1.85 / 0.92 / −0.38 | Far enough forward to clear the near plane (nearest vertex ≈ 1.43 against a 1.0 near plane), close enough that perspective does not flatten it into a decal |
| `BOT_WEAPON_WORLD_SCALE` | = `CHARACTER_WORLD_SCALE` | **A finding, not a shortcut.** The two packs turn out to be authored at very nearly one common metre — a 2.70-unit character stands for 1.7 m and a 0.42-unit pistol is a pistol, so one unit is about 0.6 m in both. The bots' 0.86-unit carbine therefore lands at 17.8 world units against a 56-unit body: a third of the holder's height |
| `BOT_WEAPON_YAW_DEGREES` | 240 | 180° for the same muzzle flip the viewmodel needs, plus **60° of carry angle — the number that made the weapon visible at all.** Pointed down the bot's facing it was aimed at the camera, so what the player saw was its *cross-section*: 3 units by 8, on a 56-unit body, which reads as a smudge on a shirt. Identical mistake to the one `DemoEffects.TRACER_WIDTH_UNITS` records, made again in the same package |
| `BOT_WEAPON_RIGHT` / `_FORWARD` / `_HEIGHT` | 9 / 7 / 30 | A hand's position on a 33-unit-wide, 17-unit-deep, 56-unit-tall body. Below eye height on purpose: a weapon level with the eyes reads as aimed down a sight, and the whole of `BotSkill` is about how badly these opponents aim |

**Angled rather than scaled up**, which was the other candidate fix for the
invisible weapon. The scale is *derived* from the two packs agreeing about a
metre, and inflating it to solve a foreshortening problem would have thrown that
away and given the bots comically large guns to look at from the side. The weapon
was the right size all along and pointing the wrong way. `DemoSceneTest` asserts
the *projected* length across the viewer's screen, in world units — an assertion
that the weapon's yaw merely differed from the bot's would have passed at one
degree.

**The corroboration is the interesting part.** `KIT_WORLD_SCALE` was chosen from
the eye height — 41 of a 128-unit ceiling is 32% up, the same fraction as a 1.7 m
person in a 5.3 m room. Only afterwards did it turn out that two Kenney courses
at 64 land exactly on `Constants.MAX_OPEN_HEIGHT`, this engine's own inherited
constant for the tallest open space, written long before the demo existed.
`DemoSceneTest` asserts that equality so the two cannot drift apart silently.

**The weapon scale is deliberately unrelated to the world scale.** A viewmodel
lives in view space; it is sized against the screen and the field of view, not
against the room. Deriving one from the other would be a coincidence dressed as
a rule.

> Two Javadoc *summary* lines in `DemoScene` are stale — they open with 2.2 and
> 1.8 where the fields hold 1.9 and 1.85. The prose below each one, and the
> table above, follow the real values.

## Files

- `DemoScene.java` — builds the immutable `Scene` once and the spawn that goes with it
- `DemoModels.java` — loads the model set, with the honest three-outcome fallback
- `DemoGameplayPort.java` — the per-tic loop, the respawn, and the rematch
- `DemoEffects.java` — the pre-placed tracer and smoke pool
- `DemoAssetException.java` — thrown when there is nothing to stand on

**79 tests.** Run with `.\gradlew.bat test`.
