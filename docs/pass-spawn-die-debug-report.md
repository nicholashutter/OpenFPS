# Pass: spawn-die debug + modular map-mode pass 2

> What logic exists, what the user is hitting, and what I'm doing about it.
> Diagnostic + plan + implementation, in one document.

---

## 1. What the user is seeing

> "I still die nearly immediately after spawning"

The most recent desktop run, foundry, `openfps-20260814-08.23`:

```
08:23:08.458 Match live — bots are moving and shooting (spec=foundry)
08:23:08.535 took 20 damage — 80 hp left (spec=foundry)        ← tic ~5
08:23:09.602 took 20 damage — 60 hp left
08:23:09.836 took 20 damage — 40 hp left
08:23:10.220 took 20 damage — 20 hp left
08:23:10.736 KILLED (tic 3558915) — death 1, respawning in 120 tics
08:23:12.736 RESPAWNED at (16.0, 0.0, 80.0) on NEUTRAL
08:23:12.902 took 20 damage — 80 hp left
08:23:13.552 took 20 damage — 60 hp left
08:23:13.619 took 20 damage — 40 hp left
08:23:14.702 took 20 damage — 20 hp left
08:23:15.819 KILLED (tic 3559220) — death 2
```

Five hits in 2.3 s → 1 hit every 0.46 s. Death at tic 137.

The smoke-test variant of the same map, `openfps-20260814-08.58`, says:

```
08:58:48.020 Tic 0: match state = IN_PROGRESS, player health = 100, bots alive = 6/6
```

Same source, **6 bots, not 8**.

### 1.1 Why the binary disagrees with the source

The prior pass bumped `Match.DEFAULT_BOT_COUNT` from 7 to 8 and changed `MapGameplayPort` to use `BotSkill.SILENT`. Both landed in the working tree:

- `Match.java:116` — `public static final int DEFAULT_BOT_COUNT = 8;`
- `MapGameplayPort.java:303` — `new Match(roster, new BotRng(), BotSkill.SILENT, ...)`
- `MapGameplayPort.java:489-493` — spawn shield announce

**But the binary the user is testing was built before the bumps.** Foundry's source has 8 waypoints (verified lines 737-746), the binary still says 6. Same story for the spawn shield: it's in `Match.tick()` and `Match.advanceRespawn()`, but the binary predates it.

Fix: a clean rebuild.

### 1.2 Two real bugs that survive the rebuild

The prior pass shipped one bug, and the rebuild will surface another.

**Bug A — spawn shield log never prints.** The map port announces the shield with:

```java
final int damage = match.tick(...);
...
if (match.spawnInvulnerabilityTicsRemaining() == Match.SPAWN_INVULNERABILITY_TICS
    && !match.isPlayerDown())
{
    LOG.info("Spawn shield up — ...");
}
```

`Match.tick()` decrements the shield from 150 to 149 *before* this check, so the equality test never matches and the line never prints. The shield is still working — the damage is suppressed — but the player never sees the announcement, and the developer never sees the proof in the log.

**Bug B — only 6 of 16 maps bumped to 8 waypoints.** The prior pass bumped 6 (overpass, tripoint, extraction, foundry, mesa, cornerstone). The 9-waypoint maps (arcticStation, crossroads, refinery) cap to 8 fine. **Seven maps still ship 6 waypoints** and produce 6 bots: pipeline, storage, stronghold, sandbar, coldfront, arcticDom, arcticHp.

Both bugs fixed in this pass.

---

## 2. What logic exists

### 2.1 Spawn shield — `Match.java`

- `SPAWN_INVULNERABILITY_TICS = 150` (2.5 s at 60 Hz)
- `spawnInvulnerabilityTics` field, initialised on construction, decremented inside `tick()` on every live tic
- `Match.tick()` reads the boolean, decrements, passes it to `resolveBotFire(..., invulnerable)`
- `resolveBotFire` suppresses `botShotsLanded`, `playerHealth`, and the kill when `invulnerable == true`
- **Shielded shots do NOT count as `botShotsLanded`** — "landed" implies "the player felt it", and a shield absorbing the hit is a hit that did not register
- Reset by `Match.advanceRespawn()` and `Match.reset()`

This is the right shape: cheap (one boolean), runs on the existing tick path, covers demo and map the same way.

### 2.2 No-enemy-fire — `BotSkill.SILENT`

- New `BotSkill.SILENT = new BotSkill(0, 45, 90, 0.244f, 0, 24)`
- `fireChancePermille = 0` → `wantsToFire` rolls the dice and always gets zero
- `wildShotChancePermille = 0` → even the wild-shot path is silent
- `MapGameplayPort.create` uses SILENT by default
- Re-enable is one line: `BotSkill.SILENT` → `BotSkill.DUMB` in the constructor

A `DUMB` bot is re-armed the moment its per-tic `meanShotIntervalTics()` elapses; a `SILENT` bot is never re-armed. This is the cleanest "no fire" path because it never re-arms the trigger.

### 2.3 Physics from the level — `PhysicsWorld.fromModel`

- New static helper, `PhysicsWorld.fromModel(ModelFormat, bodyHalfWidth)`
- Walks every submesh of the level model, computes the (x, z) Aabb from the indexed vertices, adds one box per non-degenerate submesh
- Skips submeshes with zero indices and Aabbs that collapse to a point or line
- `MapScene.build(spec, models)` and `MapScene.build(spec)` both use it
- `MapGameplayPort.create(...)` accepts the `PhysicsWorld` and hands it to the `PlayerController`
- `MapRuntime.loadMap` plumbs `newScene.physics()` through to the port

One map, one collision: no hand-authored `solidGeometry` table, no per-map port. The collision world is the same Aabbs the renderer is drawing, so a box built here is bit-identical to a box read off the mesh in a debug overlay.

### 2.4 8 bots visible — `MapSpec.botWaypoints()`

- `MapScene.build(spec, models)` does `Math.min(waypointCount, Match.DEFAULT_BOT_COUNT) = 8`
- The prior pass bumped 6 maps to 8 waypoints. This pass bumps the remaining 7.
- `Match.FIRST_BOT_ENTITY_ID` through `FIRST_BOT_ENTITY_ID + 7` is the 8-id block.

### 2.5 Gun models — `MapScene.addBotWeapon`

- One weapon (`blaster-p.ofm`) per bot, added as a world instance, index captured
- `MapGameplayPort.publishBotWeapon` moves the instance to the bot's hand every tic
- `DemoScene.botWeaponPlacement(bot)` returns the degenerate transform for a dead bot — the gun follows the corpse, then the corpse, then nothing

### 2.6 Visible projectiles + smoke — `MapGameplayPort.spawnIncomingFire`

- Mirror of `DemoGameplayPort.spawnIncomingFire`
- Reads `match.shotsThisTic()` (cleared at the top of the NEXT tick — the only window there is)
- Calls `DemoScene.botMuzzle(shooter, muzzleScratch)` for the muzzle, `effects.spawnIncoming(...)` for the tracer
- Bot muzzle, not bot eye, because a tracer that came out of a chest is worse than no tracer

### 2.7 Physics holds the player — `PlayerController` + `PhysicsWorld`

- `PlayerController.update` calls `applyMove` which calls `world.slideX` then `world.slideZ`
- Same shape as the demo, same solver
- `MapScene` reads the level, builds the world, hands it to the port

---

## 3. What is still coupled

After this pass, the demo and map ports are still near-duplicates of each other:

| Step | `DemoGameplayPort` | `MapGameplayPort` |
|---|---|---|
| Per-tic lock, view wrapping, controller update | yes | yes |
| `match.tick(...)` + damage logging | yes | yes |
| `fireIfRequested` (player trigger) | yes | yes |
| `spawnIncomingFire` (bot muzzle + ray → tracer) | yes | yes |
| `exchangeNetwork` (TicCmd out, peer bodies in) | yes | yes |
| `advanceEffects` / `publishEffects` | yes | yes |
| `publishBotPlacements` + `publishBotWeapon` | yes | yes |
| `publishLocalBody` (arms) | yes | yes |
| `aimCamera` (only on positive surface) | yes | yes |

**Modular refactor: extract `AbstractGameplayPort` holding the shared per-tic loop and the shared publish calls.** Demo + map extend it and provide the scene-specific bits (which model root, which audio port, which `setMatchLive` semantics, which `restartMatch` semantics). The shared loop is the bulk of the per-tic code in both classes.

This is the "AbstractGameplayPort" refactor the prior summary flagged as the right end state. It is not blocking the spawn-die fix, so it is **not in this pass** — this pass is unblock-the-user work. The refactor is a clean follow-up.

---

## 4. Game rules vs. map

The user asked: "game rules don't depend on the map at all". Check.

- `Match` reads `mapSpec` for the mode (TDM / HP / Dom / CTF), nothing more.
- `MapSpec` is the only map-shaped input. Bots are derived from `botWaypoints()`, spawns from `spawnPoints()`, mode from `markers()`. The collision is built from the level. The lane structure is for the HUD.
- `MapGameplayPort` does not know what mode it is running. `match.mode()` is the only place the four modes diverge, and `match.mode()` is read from the spec.
- `Bot` does not know it is a map bot. The same `Bot` runs in the demo and in every map.
- `PhysicsWorld` does not know it is a map. The `OPEN` constant is the "no geometry" case, the `fromModel` helper is the "give me a level" case, the `Builder` is the "boxes by hand" case for tests.

A new map is a new `MapSpec` and a new `level.ofm`. No code change.

---

## 5. Implementation this pass

### 5.1 Bumps

- 7 maps bumped from 6 waypoints to 8 waypoints, with the new waypoints picked to extend each map's existing closed loop:
  - **pipeline** (Industrial, Domination) — 2 interior waypoints inside the existing east-west loop
  - **arcticDom** (Arctic, Domination) — 2 floor waypoints between the three platforms
  - **stronghold** (Desert, CTF) — 2 perimeter waypoints to close the existing route
  - **coldfront** (Arctic, CTF) — 2 bank waypoints on the far side of the river
  - **arcticHp** (Arctic, Hardpoint) — 2 waypoints in the east building and north passage
  - **sandbar** (Desert, Domination) — 2 bank waypoints flanking the mesa column
  - **storage** (Industrial, CTF) — 2 perimeter waypoints on the north and south edges

### 5.2 Spawn shield log fix

Move the check from "after the tick" to "after a flag, only on the first live tic the shield is up":

```java
// before
if (match.spawnInvulnerabilityTicsRemaining() == Match.SPAWN_INVULNERABILITY_TICS
    && !match.isPlayerDown()) { LOG.info(...); }

// after
if (!firstShieldLogged
    && match.spawnInvulnerabilityTicsRemaining() == Match.SPAWN_INVULNERABILITY_TICS - 1
    && !match.isPlayerDown())
{
    LOG.info("Spawn shield up — {} tics (spec={})",
        Match.SPAWN_INVULNERABILITY_TICS, spec.id());
    this.firstShieldLogged = true;
}
```

`firstShieldLogged` is a `boolean` reset on every respawn, the same shape as `firstTracerLogged` already uses for the effects-live line.

### 5.3 Tests

- `BotSkillTest.SILENT` — never fires across many tics (the whole point of the skill)
- `PhysicsWorldTest.fromModel` — per-submesh Aabb, degenerate-submesh skip, null throw
- `MatchTest` already covers `Match.SPAWN_INVULNERABILITY_TICS` end-to-end via the new `SpawnShield` nested class from the prior pass

---

## 6. Verification plan

1. `gradlew.bat check` — full test suite, checkstyle clean
2. Headless smoke test on foundry, 120 tics — expect 8 bots alive, no damage, no `Map path effects live` line (no enemy fire ⇒ no enemy tracer ⇒ no spawn of incoming fire)
3. Headless smoke test on storage (CTF) and arcticDom (Domination) — confirm 8 bots across modes

---

## 7. Hand back

Once `gradlew.bat check` is green and the smoke tests print what they should, the user runs:

```
.\gradlew.bat :desktop:run --args="--map=foundry --start-in-game"
```

Expected first second of play:
- 8 bots visible, on the foundry waypoints, each with a blaster-p in hand
- Player takes **zero damage** in the first 2.5 s
- One log line: `Spawn shield up — 150 tics (spec=foundry)`
- After 2.5 s, the player can move into the open and the bots will start missing, not hitting

Re-enable bot fire when satisfied: change `BotSkill.SILENT` → `BotSkill.DUMB` in `MapGameplayPort.create` (one line).
