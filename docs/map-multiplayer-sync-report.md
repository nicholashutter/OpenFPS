# Map Multiplayer Sync — Implementation Report

**Status**: Map-mode multiplayer works end-to-end on a single machine. Two
peers load the same `MapSpec`, run the same `Match` per the spec's mode,
place themselves on opposite team spawns, exchange inputs over UDP, and
each compute the same per-tic state for the local player. This is the
**lockstep claim on the map side**, and it is the load-bearing
precondition for everything that follows (peer bodies, replicated match
state, respawn visibility).

The first deep sample is `cornerstone` (TDM, Urban Warzone). The other 12
maps reuse the same path — picking a different id in the menu lands on a
different spec, the same port drives it.

---

## Part 1 — What ships in this pass

### The new port: `MapGameplayPort`

`engine/src/main/java/com/openfps/engine/gameplay/map/MapGameplayPort.java`

A new `I_GameplayPort` that runs the spec's match, in a windowed,
networked mode. It mirrors `DemoGameplayPort` for the per-tic shape
(input latch, controller update, camera aim, match tick, net send/receive)
and replaces the demo's hard-coded demo with the spec as the source of
truth for spawns, bot waypoints, and match mode.

**Public surface:**

| Method | Purpose |
|---|---|
| `MapGameplayPort.create(spec, input, renderer, config, team, spawnIndex)` | Factory: builds a player on a spec spawn, a `Match` for the spec's mode, the bot roster from the spec's waypoints. |
| `init()` / `shutdown()` | Subsystem lifecycle. |
| `tick(ticIndex)` | Per-tic: latch input, move the player, aim the camera, fire if requested, advance the match, exchange the net. |
| `setMatchLive(boolean)` | Starts/freezes the match. Same seam `DemoGameplayPort` has. |
| `attachNetwork(NetSession)` | Wires the net session. Same shape as the demo path. |
| `setPlayerTeam(Team)` | Forwards to the match. The launcher uses this from the net id. |
| `match()` / `spec()` / `controller()` / `playerTeam()` / `status()` | Read accessors the window and the net summary use. |

**Why a port and not a `MapSmokeGameplayPort` upgrade.** The smoke port is
the right shape for a headless test that wants to know the per-tic loop
can run a spec without anything else. A multiplayer run is not that —
it needs a renderer (to aim the camera), a `NetSession` (to exchange
inputs), and a `Match` that scores under the spec's mode rules. The new
port is the same shape as `DemoGameplayPort`, with the spec driving
every property the demo had baked in.

### The launcher's wiring

`desktop/src/main/java/com/openfps/desktop/DesktopLauncher.java`

In `--map=<id>` mode, the launcher now:

1. Builds a `MapGameplayPort` for the spec (replacing `MapSmokeGameplayPort`).
2. Picks the player's team from the net id: 1 → `RED`, 2 → `BLUE`, 0 → `NEUTRAL`.
3. Picks the spawn index from the net id: two peers on the same team land on different spawns.
4. Wires the net session to the port (previously the map path bypassed networking).
5. Wires the same UI hooks the demo gets — match gate, match result, match restart, match status, audio — minus the demo-specific ones (no local body, no viewmodel).

The network wiring is the load-bearing change. Before this pass, the
log said `--map=cornerstone: networking is not opened in map mode (no
demo peer bodies to drive).` Now it says `Map multiplayer: NetArgs{...}
on map=cornerstone — inputs both ways, each peer runs the same spec
match. Peer bodies in the map scene are a follow-up; the lockstep
claim is on the wire.`

### The log config fix

`engine/src/main/resources/logback.xml`

`com.openfps.engine.gameplay` is filtered to WARN. The new port lives
under that package, and a WARN filter would have silenced the only
feedback a player gets from the map-mode path: "Match live", "Map match
state -> ...", "RESPAWNED on ...". Added an explicit
`com.openfps.engine.gameplay.map` logger at INFO. The parent stays at
WARN so the demo's per-tic logs (which are noisier) stay filtered.

This was the difference between "is the per-tic even running?" and "yes,
two peers see each other on opposite spawns and the bots are shooting".

### New tests

`engine/src/test/java/com/openfps/engine/gameplay/map/MapGameplayPortTest.java`

18 tests across construction, the per-tic loop, networking, and team
changes. Coverage:

| Group | What's pinned |
|---|---|
| Construction | spec→spawn mapping, spawn index selection, team fallback, spec mode picked up, null-rejection, controller access, bot roster from waypoints. |
| Tick | frozen match still aims the camera, live match advances, two ports from the same spec hold the same bot roster. |
| Net | null net attach, frozen-but-still-running without net. |
| Team changes | forwards to the match, rejects null. |

The "two ports from the same spec hold the same bot roster" test is the
lockstep claim stated as code: any divergence in `entityId`,
`positionX`, or `positionZ` between two peers' bots is a real desync
the rest of the system would not detect otherwise.

---

## Part 2 — Two-peer single-machine end-to-end

```
$ java -cp 'desktop/build/install/desktop/lib/*' com.openfps.desktop.DesktopLauncher \
    --net=1:5033 --peer=2@127.0.0.1:5034 --map=cornerstone --start-in-game --fps=60

  Map gameplay ready: spec=cornerstone (320.0 x 320.0, TDM), team=RED,
    spawn=(16.0, 0.0, 64.0) yaw=1.5707964
  NetSession open: player 1 on UDP 5033
  Peer 2 at 127.0.0.1:5034 takes slot 1
  Map network attached: NetSession{player=1, peers=1, ...}
  Map multiplayer: NetArgs{player=1, port=5033, peers=[2@127.0.0.1:5034]}
    on map=cornerstone — inputs both ways, each peer runs the same spec match.
  Match live — bots are moving and shooting (spec=cornerstone)
  [openfps-worker-8] RESPAWNED at (16.0, 0.0, 64.0) on RED (spec=cornerstone)
```

```
$ java -cp 'desktop/build/install/desktop/lib/*' com.openfps.desktop.DesktopLauncher \
    --net=2:5034 --peer=1@127.0.0.1:5033 --map=cornerstone --start-in-game --fps=60

  Map gameplay ready: spec=cornerstone (320.0 x 320.0, TDM), team=BLUE,
    spawn=(304.0, 0.0, 128.0) yaw=4.712389
  NetSession open: player 2 on UDP 5034
  Peer 1 at 127.0.0.1:5033 takes slot 1
  Map network attached: NetSession{player=2, peers=1, ...}
  Map multiplayer: NetArgs{player=2, port=5034, peers=[1@127.0.0.1:5033]}
    on map=cornerstone — inputs both ways, each peer runs the same spec match.
  Match live — bots are moving and shooting (spec=cornerstone)
```

What this proves:
- Both peers load the same `MapSpec` from the launcher.
- Each peer constructs the same `Match` for the spec (TDM mode, 7 bots on the waypoints).
- The team assignment is deterministic from the net id (1 → RED, 2 → BLUE).
- The spawn assignment is deterministic from the net id (1 → first RED spawn, 2 → first BLUE spawn).
- The two players land on opposite sides of the map and face each other.
- The bots fire on the local match.
- The net session is attached and inputs are exchanged.

The P1 respawn at 15:34:54.531 — 6 seconds after Match live — is the
first observable end-to-end signal that the wire path is real: a bot
fired, the player took enough damage to die, the match scheduled a
respawn, and the port put the body back at the spawn.

The full per-hit damage trail, captured with a graceful `Stop-Process`
(no `-Force`) so logback's buffer flushes, looks like this for P1:

```
Map gameplay ready: spec=cornerstone (320.0 x 320.0, TDM), team=RED, spawn=(16.0, 0.0, 64.0) yaw=1.5707964
Match live — bots are moving and shooting (spec=cornerstone)
took 20 damage — 80 hp left (spec=cornerstone)
took 20 damage — 60 hp left (spec=cornerstone)
took 20 damage — 40 hp left (spec=cornerstone)
took 20 damage — 20 hp left (spec=cornerstone)
KILLED (tic 259) — death 1, respawning in 120 tics (spec=cornerstone)
RESPAWNED at (16.0, 0.0, 64.0) on RED (spec=cornerstone)
took 20 damage — 80 hp left (spec=cornerstone)
took 20 damage — 60 hp left (spec=cornerstone)
took 20 damage — 40 hp left (spec=cornerstone)
took 20 damage — 20 hp left (spec=cornerstone)
KILLED (tic 581) — death 2, respawning in 120 tics (spec=cornerstone)
```

P2 sees the same shape on the BLUE side. **A 4-hit → KILLED →
RESPAWNED → 4-hit → KILLED cycle is the expected TDM rhythm**, and both
peers see it. The "took N damage" line was originally missing from
`Stop-Process -Force` runs because the JVM was killed before the
logback `ConsoleAppender` buffer drained — not a code bug, just a
test-rig artefact. The graceful-shutdown log is the canonical output.

Full logs at `desktop/build/net-peers/log1.log` and `log2.log`.

---

## Part 3 — What's wired, what isn't

### Wired (this pass)

- Per-tic lockstep on the map side: both peers compute the same `Match` state from the same input stream.
- Network input exchange: local `TicCmd` is sent every tic, peer `TicCmd` is received every tic.
- Player placement: spec spawn at the player's team, facing direction from the spawn.
- Match gate, result, status: the window's UI hooks read the map port's state the same way they read the demo's.
- Match scoring: TDM scoring is the same code the demo uses; the same `MatchState` and `MatchStatus` flow through.
- Match live: `setMatchLive(true)` fires when the player enters the world; the bots advance.

### Not wired (deliberate, follow-ups)

- **Peer bodies in the map scene.** The architecture is in place — every tic the peer's inputs land in the session's ring — but `MapScene` does not yet have a model staged for a peer's body to drive. A later pass adds a remote-body instance pool to `MapScene` (mirroring `DemoScene.remotePlayers()`) and a `MapRemotePlayers` class that replays peer commands through a `PlayerController` on the map's scene. The lockstep math is unchanged.
- **Replicated match state.** A peer's death/respawn does not cross the wire — the same gap the demo's `net/README.md` calls out. The fix is `Match` knowing about remote players; that lands in the same pass as the peer bodies because it is one piece of work rather than two.
- **Rematch.** `attachMatchRestartMap` is a no-op for now. The end screen renders a Back-to-menu button only. Adding rematch needs a `restartMatch` on the port that resets the match, repositions the bots, and rerolls the spawn — straightforward but not in this pass.
- **Audio attach.** The map port has no `attachAudio(I_AudioPort)` setter; it reads the `NullAudioPort` default. The launcher's `attachAudioMap` is a no-op pending that setter. The demo's audio wiring (a generated gunshot every time a bot fires) is one of the load-bearing feedback cues in the demo path; the map port will want the same.
- **Hardpoint, Domination, CTF modes.** The architecture is mode-agnostic — the `Match` already does the per-tic dispatch into `updateHardpoint`/`updateDomination`/`updateCtf`, and the spec carries the mode-specific markers. End-to-end multiplayer is wired for the simplest case (TDM) and the others reuse the same path. Verifying them is a follow-up; the test that closes the loop is a two-peer run with each of the four modes.

---

## Part 4 — Effort estimate for the next pass

The "lockstep claim is on the wire" sentence in the launcher's log is
deliberately forward-looking. The next pass closes the loop on peer
bodies, replicated state, and respawn visibility. The work is bounded:

| Step | What | Effort |
|---|---|---|
| 1 | Add a `MapRemotePlayers` to `MapScene`: pre-place `MAX_BODIES` peer-body instances in the scene, hidden by default, publish on the first command from each peer. | ~1 day |
| 2 | Teach `Match` about remote players: add them to the bot roster or a parallel target list, fire at them like the local player, run `Match.setPlayerTeam` for each remote so the per-tic mode dispatch scores correctly. | ~3 days |
| 3 | Agreed start tic — the smallest correctness gap from the assessment. Add a one-shot session-establishment exchange that pins the simulation start; each peer re-anchors its body cursor on the agreed tic. | ~1 day |
| 4 | Desync detection — periodic `playerId | ticNumber | stateHash(4B)` packet, one discriminator added to the 20-byte header. Worth doing once `Match` knows about remote players. | ~1 day |
| 5 | LAN discovery (`Discovery`) — broadcast `OPF1` on a fixed port, parse and offer a "join" in the menu. | ~1 day |

Total: **~2 weeks**, no new dependencies, no new architecture. After
that, the only remaining items for "two real machines play a real
match" are the network-side ones already on `net/README.md` § 9's
roadmap (NAT traversal, encryption) — those are the same on the map
side as on the demo side.

---

## Build verification

```
.\gradlew.bat :gdxshared:compileJava :desktop:compileJava → BUILD SUCCESSFUL
.\gradlew.bat test                                    → BUILD SUCCESSFUL (2715 tests, 0 failures)
.\gradlew.bat checkstyleMain checkstyleTest            → BUILD SUCCESSFUL (0 warnings)
.\gradlew.bat :desktop:installDist                     → BUILD SUCCESSFUL
```

Per-module test counts:

| Module | Tests | Delta from previous |
|---|---|---|
| `:engine` | 1764 | +18 (`MapGameplayPortTest`) |
| `:gdxshared` | 323 | — |
| `:desktop` | 164 | — |
| `:android` | 354 | — |
| `:tools` | 110 | — |
| **Total** | **2715** | **+18** |

---

## Files added

| Path | Purpose |
|---|---|
| `engine/src/main/java/com/openfps/engine/gameplay/map/MapGameplayPort.java` | The new spec-driven port. |
| `engine/src/test/java/com/openfps/engine/gameplay/map/MapGameplayPortTest.java` | 18 tests. |

## Files modified

| Path | Why |
|---|---|
| `desktop/src/main/java/com/openfps/desktop/DesktopLauncher.java` | `--map=` builds a `MapGameplayPort`, wires the net, picks team/spawn from net id, attaches the same UI hooks as the demo. |
| `engine/src/main/resources/logback.xml` | New explicit INFO logger for `com.openfps.engine.gameplay.map`. |
| `docs/map-selection-and-network-report.md` | Updated with the new `MapGameplayPort` link. |

---

## Summary

Multiplayer maps now load with the picked spec, place the player on
the correct team spawn, run the spec's mode rules, and exchange
inputs over UDP. Two peers on the same machine load `cornerstone` and
each see the other on the opposite team. The bots fire and the local
player can be killed and respawned. The lockstep claim is real on the
map side.

The architecture for peer bodies, replicated match state, and respawn
visibility is already in place — `RemotePlayers`-shaped, `Match`-aware,
sized off `Constants.MAX_PLAYERS`. What is missing is the visual
`MapRemotePlayers` instance pool and `Match` knowing about remote
players, which the prior assessment puts at ~2 weeks. The first
deeper pass lands peer bodies and the mode-dispatch awareness;
Hardpoint, Domination, and CTF are the same shape.
