# Pass: spawn shield — the first few tics are not the death sentence

## What was wrong

`Match.tick()` applied bot fire damage on the very first tic, so a player who
read the menu for ten seconds arrived at a map-port with seven bots already
in line of sight and died in under two seconds — the "spawn-die-spawn-die"
loop the user reported on the August 2026 foundry log:

```
15:48:00.170 Match live — bots are moving and shooting
15:48:00.221 took 20 damage — 80 hp left
15:48:00.438 took 20 damage — 60 hp left
15:48:00.554 took 20 damage — 40 hp left
15:48:00.571 took 20 damage — 20 hp left
15:48:01.721 KILLED (tic 6431) — death 1, respawning in 120 tics
```

Five hits from five different bots in the first second, death at 1.5 s. The
player respawned into the same swarm and died again. The "fall through the
floor" reading of the log was a misdiagnosis — the player was at `y = 0` (the
floor top) and `isOnGround()` returned true; the deaths were from bot
damage, not gravity.

## What the fix is

A 150-tic (2.5 s at 60 Hz) spawn shield in `Match` itself, not in the
per-port code, so the protection covers the demo and every map port. The
shield:

- Suppresses the **damage** but not the **shot** — bots still fire, the
  player still sees the tracers, the log still records the rays. The HUD
  signal is "you are safe", not "the room is empty".
- Resets on every respawn (a returner needs the same grace a beginner got
  on the first spawn).
- Resets on every `Match.reset()` (a rematch opens as a fresh round, not a
  continuation of the previous one).
- Does **not** count absorbed shots as `botShotsLanded` — a HUD that said
  "X shots landed on you" would be confusing if it counted the absorbed
  ones. "Landed" implies the player felt it.

The constant `SPAWN_INVULNERABILITY_TICS = 150` is in tics, never
milliseconds, for the same lockstep-correctness reason the respawn delay
gives: every peer runs the same `GameConfig`, so a duration counted in tics
elapses on the same tic on every machine.

## What changed

- `engine/src/main/java/com/openfps/engine/gameplay/Match.java`
  - `SPAWN_INVULNERABILITY_TICS` constant + Javadoc.
  - `spawnInvulnerabilityTics` field, initialised at construction.
  - `Match.tick()` checks and decrements the counter on every live tic;
    passes the invulnerable flag to `resolveBotFire`.
  - `resolveBotFire(..., boolean invulnerable)` skips the health
    decrement, the kill, and the "landed" count when the player is
    shielded.
  - `Match.advanceRespawn()` and `Match.reset()` reset the counter to the
    full window.
  - `isPlayerInvulnerable()` and `spawnInvulnerabilityTicsRemaining()`
    accessors for the HUD.
  - Package-private `drainSpawnShield()` for tests that want to skip the
    shield.
- `engine/src/main/java/com/openfps/engine/gameplay/map/MapGameplayPort.java`
  - One HUD log line on the first live tic after every spawn and respawn:
    `Spawn shield up — 150 tics (spec=<id>)`. The countdown is the HUD's
    job; the log is the "the game just saved you, here is why" line that
    a player only needs to see once per life.

## Tests

- New `MatchTest$SpawnShield` nested class (6 tests):
  - shield is up on the very first tic
  - no damage lands during the shield
  - damage resumes the tic after the shield expires
  - shield is re-granted on respawn
  - bots still fire during the shield (only the damage is suppressed)
  - `reset()` restores the full shield
- Updated `MatchTest` (4 tests) + `MatchMapSpecTest` (4 tests) +
  `MatchCtfTest` (1 test) + `MatchStatusTest` (1 test) +
  `BotShotLogTest` (1 test, via the `roomOfOne` helper) to use the new
  `drainSpawnShield()` or a local `burnShield(match)` helper so the
  pre-shield tests don't see the new window.
- Test count: **2,525 → 2,531** (+6 net; the SpawnShield tests are 6 new,
  the burn-helper is per-test boilerplate that doesn't show up as a test).

## Build

`gradlew.bat check` is green: all 2,531 tests pass, Checkstyle
`maxWarnings = 0` holds.

## What the user will see

The next time they pick a map from the menu, the log will show:

```
Map gameplay ready: spec=foundry ...
Match live — bots are moving and shooting (spec=foundry)
Spawn shield up — 150 tics (spec=foundry)
```

Tracers fly past the player for the first 2.5 seconds; the player takes
no damage and can move. After 2.5 s, damage resumes normally. Same window
on respawn.
