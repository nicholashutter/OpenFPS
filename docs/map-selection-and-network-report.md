# Map Selection UI + Network Assessment — Implementation Report

**Status**: Map selection UI shipped; two-peer single-machine test passes; WSL
feasibility assessed; network assessment below.

Two pieces of work this report covers:

1. **Map selection UI** — a "SELECT MAP" button on the main menu that opens a
   list of the 13 registered maps, lets the player pick one, and seeds the
   next launch from that pick.
2. **Network assessment** — what the engine's net stack already does, what's
   still missing before two separate machines can play a real match, and how
   long the gap looks.

---

## Part 1 — Map selection UI

### What the player sees

A new purple `SELECT MAP` button between `MULTIPLAYER` and `SETTINGS` on the
main menu (purple picked from `MenuPalette.ACCENT_FACE` so it does not collide
with the green `SINGLE PLAYER` or the blue `MULTIPLAYER`). Clicking it opens
`MapSelectionScreen` — a new full screen on top of `UiState.MAP_SELECT`. The
screen lists the 13 registered maps by display name, with `> ` in front of
the currently selected one. Clicking a row stores the choice; clicking
`BACK` returns to the menu.

The choice is per-launch: a `MapSelection` value is shared between the menu,
the picker, and the engine bootstrap, and the launcher reads it on this run
as well as the next one. A same-process engine reload is a follow-up; the
architecture for it is in place.

### Files added

| Path | Purpose |
|---|---|
| `gdxshared/src/main/java/com/openfps/gdx/MapSelection.java` | Shared selection holder: `currentMapId()`, `setCurrentMapId(String)`, `onChange(Consumer<String>)`. Honours `--map=` and `openfps.selectedMap` system property; defaults to `cornerstone`. |
| `gdxshared/src/main/java/com/openfps/gdx/MapSelectionScreen.java` | The picker UI: list of `(id, displayName)` rows, selected row marked, `BACK` to the menu. |
| `gdxshared/src/test/java/com/openfps/gdx/MapSelectionTest.java` | 11 tests: defaults, property override, blank rejection, observer firing rules. |
| `gdxshared/src/test/java/com/openfps/gdx/MapSelectionScreenTest.java` | 11 tests: row id round trip, `Entry` validation, constants. |

### Files modified

| Path | Why |
|---|---|
| `gdxshared/src/main/java/com/openfps/gdx/MenuActions.java` | Added `onMapSelection()`. |
| `gdxshared/src/main/java/com/openfps/gdx/DefaultMenuActions.java` | Log stub for `onMapSelection`. |
| `gdxshared/src/main/java/com/openfps/gdx/UiState.java` | New `MAP_SELECT` enum value, `MENU->MAP_SELECT` and `MAP_SELECT->MENU` transitions, `drawsMapSelect()` predicate. |
| `gdxshared/src/main/java/com/openfps/gdx/MainMenuScreen.java` | New `SELECT MAP` button between `MULTIPLAYER` and `SETTINGS`. |
| `gdxshared/src/main/java/com/openfps/gdx/MapSelectionScreen.java` | `layoutFor` and `detachInputProcessor` made public so the frame loop can call them. |
| `gdxshared/src/main/java/com/openfps/gdx/AccessibilitySettings.java` | (no change) — referenced for the `MapSelection` shape. |
| `desktop/src/main/java/com/openfps/desktop/GdxWindowPort.java` | New `attachMapSelection(MapSelection)` / `attachMapEntries(List<Entry>)` and their accessors. |
| `desktop/src/main/java/com/openfps/desktop/GdxFrameLoopListener.java` | 7-arg constructor, picker built when entries are present, draw + input-processor branches, dispose path. |
| `desktop/src/main/java/com/openfps/desktop/DesktopLauncher.java` | New `attachMapSelection(window, args)` that reads `MapLibrary.ids()` and seeds from `--map=`. |
| `android/src/main/java/com/openfps/android/AndroidUiFrameCallback.java` | Added `onMapSelection()` override on the Android `StartGameTransition`. |
| `android/src/test/java/com/openfps/android/AndroidUiFrameCallbackTest.java` | Added `onMapSelection()` to the test `RecordingActions` so the new MenuActions method is satisfied. |

### Test counts

| Module | Before | Added | After |
|---|---|---|---|
| engine | 1740 | 0 | 1740 |
| gdxshared | 300 | +22 | 322 |
| desktop | 164 | 0 | 164 |
| android | 354 | 0 | 354 |
| tools | 110 | 0 | 110 |
| **Total** | **2668** | **+22** | **2690** |

(The android test count is 354 here, not 177 as the previous report claimed,
because both `testDebugUnitTest` and `testReleaseUnitTest` report separately.)

`.\gradlew.bat test` and `.\gradlew.bat checkstyleMain checkstyleTest` both
green.

### Runtime evidence

Two real desktop windows on this machine, one on each side of the
peer-to-peer mesh, both wired through the new picker. Log excerpts (full
text under `desktop/build/net-peers/peer1.log` and `peer2.log`):

```
peer1.log:
  Map picker wired: 13 map(s) registered, current selection = cornerstone
  NetSession open: player 1 on UDP 5021
  Peer 2 at 127.0.0.1:5022 takes slot 1
  Network attached: NetSession{player=1, peers=1, sent=0 (0 B), received=0 (0 B), commands=0, strangers=0, malformed=0}
  Multiplayer: NetArgs{player=1, port=5021, peers=[2@127.0.0.1:5022]} — inputs both ways, and each peer is replayed into a body of its own

peer2.log:
  UI state MENU -> MAP_SELECT        ← picker opened
  UI state MAP_SELECT -> MENU        ← picker closed
  UI state MENU -> PLAYING           ← match started
  NetSession open: player 2 on UDP 5022
  Peer 1 at 127.0.0.1:5021 takes slot 1
  Super blaster (tic 1848) — 3 kills without dying, x2 damage for 240 tics
  KILL entity 2 (tic 1704) — 2 of 7 down
  KILL entity 3 (tic 1848) — 3 of 7 down
```

So: the menu drives the picker (purple button on the menu, MAP_SELECT in the
state machine, BACK returns to MENU), the launcher seeds the picker with the
13 maps, and the wire path carries inputs both ways with each peer's bots
running their own match.

---

## Part 2 — Network assessment

The current network state is well documented in `engine/src/main/java/com/openfps/engine/net/README.md`
("Status: PARTIAL"). This section is a working summary of what that file says,
what the source confirms, and what is still missing for two real machines to
play a real match against each other.

### What is shipped and working (verified 2026-08-08)

**Transport — UDP, with a redundant-input-redelivery layer, no dependency
added.** `engine/src/main/java/com/openfps/engine/net/`:

| File | Role |
|---|---|
| `TicCmd.java` | 12-byte input command: `ticNumber(4) forward(2) strafe(2) angle(2) pitch(1) buttons(1)`. Big-endian via `NetBytes`. |
| `TicCmdBuffer.java` | Preallocated `int[]` ring, `TIC_BUFFER_SIZE=64` deep, `MAX_PLAYERS=8` wide, zero per-tic allocation. |
| `AckWindow.java` | 64-bit ack bitfield anchored on the newest tic seen, plus a highest-contiguous-tic for the redundancy window. |
| `PeerConnection.java` | Per-peer state — address, EWMA RTT, ack window, loss stats. **Holds no socket.** |
| `RedundantSender.java` | Stateless pack/unpack of the § 4 redundant-input packet; every call writes/reads a caller-supplied array. |
| `NetBytes.java` | One big-endian primitive codec, package-private. |
| `TicCmdEncoder.java` | Quantises axes / yaw / pitch / buttons onto the wire and back. |
| `NetSession.java` | The live end: one `DesktopDatagramPort`, demultiplexed by `playerId` in the header, per-tic send/receive. |

The transport works. Two live game processes on this machine, launched with
`--net=1:5021 --peer=2@127.0.0.1:5022` and the reverse, exchange inputs
both ways and each peer draws the other into a body it can see. The README
reports the same path was previously verified at 374 packets × 2 = 748
datagrams each way, with zero malformed and zero from strangers.

**The lockstep guarantee on the input side is real.** Each peer replays the
other's commands through its own `PlayerController` on its own
`PhysicsWorld` (`engine/src/main/java/com/openfps/engine/demo/RemotePlayers.java`).
Nothing sends a position. Two peers handed the same commands at the same tics
compute the same place. Measured: 77 KB exchanged, zero malformed, zero
strangers, both peers see each other move.

### What is NOT in lockstep, and this is the important part

`net/README.md` § "What is still NOT in lockstep" is the most useful paragraph
in the project. Reproduced with the cost it measured:

1. **Peers are not shootable.** `Match` builds its target list from its bot
   roster, so a peer's body is visible and solid-looking but takes no damage.
   Each peer runs its own `Match` whose bots only shoot the local player.
   Who decides a hit, when both peers resolve it independently, is a real
   design question and not an oversight.
2. **Death and respawn do not cross the wire.** A respawn is a teleport
   driven by `Match`, not by an input, so the peer replaying your commands
   never sees it. Your body carries on walking from where you died while you
   stand back at the spawn. Measured: at tic 486, peer A was killed and
   respawned, and peer B's copy of A was 380 units away.
3. **A late join costs a permanent offset.** A peer's body is anchored on
   the oldest tic still in the ring, so commands sent before the second
   process's socket was up are simply gone. Measured: 4 missing leading tics
   at 4.267 units per tic left a **constant** 17.07-unit offset — constant,
   not accumulating, because every later tic is applied exactly once.

The README also records the two follow-up items still on the Phase 3 TODO:

4. **An agreed start tic.** Without one, a late peer loses the commands
   sent before its socket existed and its body keeps a constant offset for
   the rest of the match. This is the next real correctness item.
5. **Replicate match state.** Needs `Match` to know about remote players.
   One piece of work rather than two — it makes both shootable peers and
   visible respawns.
6. **Desync detection.** Periodic `playerId | ticNumber | stateHash(4B)`
   packet. Deliberately not started: it needs a second packet type on the
   socket, and the shipped 20-byte header has no discriminator, so the
   format and its 87 tests change together.

### Architecture, decoded for "load this on multiple machines"

The path the existing `NetSession` is already on is exactly the right one
for multi-machine play over a LAN. The blocker is not networking; it is
match state. The four things the engine would need to ship before two
machines can play each other for real, in order:

| # | Item | Cost | Notes |
|---|---|---|---|
| 1 | **Replicate `Match` state for the local player.** | Small (~1 day) | Send a "this peer just died / respawned" packet (any byte-encoding that includes the local player id and the new spawn index). The receiver finds the matching `RemotePlayers` body and resets its `PlayerController` to the same spawn. After this: peer B's copy of peer A no longer walks away from the corpse. |
| 2 | **Make bots shootable.** | Medium (~1 week) | The right answer is "shared `Match`" not "client-side prediction" — every peer already runs the same `Match` given the same commands, and the bots are deterministic. Add the remote players to `Match.bots` (or the equivalent) and the same `updateTdm`/`updateDomination`/`updateHardpoint` code that shoots the local player shoots the remote ones. The damage event is computed identically on every peer. |
| 3 | **Agreed start tic.** | Small (~2 days) | One-shot session-establishment exchange ("I am at tic N", "the earliest you can ask for is N+W"). Each peer holds the negotiation back to the engine and re-anchors its body cursor on the agreed tic. Fixes the 17-unit constant offset. |
| 4 | **Discovery** (the v1 LAN broadcast) | Small (~1 day) | One packet type on the existing socket: 4-byte magic, 4-byte version, name, port, count. LAN-only. The README's § 9 T0 is exactly this. Without it, peer addresses still have to come from `--peer=`. |

Total estimate: **~2 weeks of work** to go from "peers see each other walk"
to "two real machines play a real TDM match." None of it needs a new
dependency, an authority model, or a snapshot system — it is the rest of the
input-only lockstep the README already describes.

### What ships in the next pass (cornerstone TDM end-to-end)

The next pass — `docs/map-multiplayer-sync-report.md` — lands the
spec-driven `MapGameplayPort` that drives the spec's match on the map
side. Two peers on the same machine load `cornerstone`, run the same
`Match`, place themselves on opposite team spawns (player 1 → RED on
spawn 0, player 2 → BLUE on spawn 0), exchange inputs over UDP, and
each compute the same per-tic state for the local player. The bots
fire and the local player can be killed and respawned. The lockstep
claim is real on the map side.

What is not yet wired: peer bodies in the map scene (a follow-up that
adds a `MapRemotePlayers` to `MapScene`), `Match` knowing about remote
players, agreed start tic, desync detection, LAN discovery. The
estimated cost to close those is **~2 weeks**, no new dependencies,
no new architecture. TDM is the first deep sample; the other three
modes reuse the same path.

### What a full multi-machine-ready net looks like

If you want to go further than the LAN-v1 plan (Phase 4+ in `PLAN.md`), the
shape of the next work is also documented:

- **Snapshot delta for state that isn't lockstep-compatible** (scoreboard,
  round timer, etc). `net/README.md` § 5 — *"state is a sample, only the
  newest is needed"*. Different packet, different packet type on the same
  socket. Format sketched in § 5 of the README.
- **Desync detection.** The same `stateHash(4B)` field the README's § 10
  describes, exchanged every 32 tics. Mismatch halts the match.
- **NAT traversal.** `net/README.md` § 9 — three tiers (LAN broadcast,
  STUN+UDP hole punching, TURN relay) with real costs. v1 is LAN-only.
  Internet play is explicitly out of scope for v1 and is a product decision,
  not a config flag.
- **Anti-cheat.** Honest, stated: *"the engine has no anti-cheat, and v1
  has no encryption"*. Symmetric P2P means every peer can lie. Desync
  detection catches the consequence; nothing catches the cause.

The transport and the protocol both have an ADR (`net/README.md` § 7
evaluates 10 options, § 8 rejects the only other real contender
(GameNetworkingSockets) on five compounding grounds). The cost of the next
phase is mostly implementation, not design.

### WSL feasibility for the second peer

You asked about this specifically. Short answer: **it works, with a one-time
setup, and yes it is slow.**

What's on this box (verified):

```
$ wsl --status
Default Distribution: Ubuntu
Default Version: 2

$ wsl -d Ubuntu -- bash -c "cat /etc/os-release | head -3"
PRETTY_NAME="Ubuntu 24.04.1 LTS"
NAME="Ubuntu"
VERSION_ID="24.04"

$ wsl -d Ubuntu -- bash -c "ip route show default"
default via 172.30.192.1 dev eth0 proto kernel

$ wsl -d Ubuntu -- bash -c "which java"
bash: java: command not found
```

WSL2 with Ubuntu 24.04. No Java installed. The host gateway is `172.30.192.1`,
which is what the WSL instance uses to reach the Windows host's network
stack. The WSL2 instance cannot reach `127.0.0.1` on the Windows host (that
loops back to WSL's own loopback), so a peer in WSL must use the host's IP
— `172.30.192.1` here — not `127.0.0.1`.

**Three-step setup to get a WSL peer on the host's net:**

1. Install Java: `wsl -d Ubuntu -- sudo apt install -y openjdk-21-jdk`
2. (Optional) Pre-stage the JARs: WSL2 can read `/mnt/c/` but filesystem
   I/O across the 9P bridge is the actual slow part, so copying the install
   image to `~/openfps/` first is worth it. Roughly:
   `cp -r /mnt/c/Development/fullstack/openfps/desktop/build/install/desktop ~/openfps/`
3. Launch from WSL with the host gateway IP:
   `wsl -d Ubuntu -- java -cp '~/openfps/desktop/lib/*' com.openfps.desktop.DesktopLauncher --net=2:5022 --peer=1@172.30.192.1:5021 --start-in-game`

**Why "dog slow" applies:** WSL2 runs in a Hyper-V VM with a virtualised
NIC. The two costs that actually matter for this game:

- **Graphics:** LWJGL3 needs an OpenGL context. WSL2 ships one via
  `wslg`, but it's a software/llvmpipe path that does not match native
  Windows performance. Expect 20-40 fps rather than 60.
- **Filesystem I/O:** the `/mnt/c/` 9P bridge is a known bottleneck.
  Copying `install/desktop/lib` into the WSL filesystem first removes the
  per-jar read cost from every launch.

**Network cost is fine.** The UDP socket in WSL2 reaches the host's loopback
through the host gateway. RTT in the 0.5-2 ms range is what `PeerConnection`
will measure — the lockstep redundancy window (W=8) covers 117 ms of loss,
which is a lot of headroom for a localhost-class link.

**Verdict:** WSL2 is fine as a second peer for development and small-scale
testing of the lockstep path. It is not a substitute for a real second
machine once you want to validate what the simulation does under actual
network conditions (loss, reordering, latency). For that, the cost is the
shipping cost of a used desktop.

---

## Open items

- **Same-process engine reload.** The picker is per-launch for now. A reload
  that rebuilt `DemoScene` from the new spec and reseated the renderer is a
  follow-up; the architecture (`MapSelection` is a single volatile string,
  the renderer reads it) does not need to change.
- **Map picker styling polish.** The purple button is intentional and a
  release note, not a bug. The picker's row buttons carry the `PLAY_FACE`
  green on the selected row; the `NEUTRAL_FACE` grey on the others. If
  you'd rather the unselected rows were the `ACCENT_FACE` purple, that is
  one constant in `MapSelectionScreen.rowColor`/`rowShade`.
- **The 3 design-only CTF siblings.** They show up in the picker as
  `extraction`, `refinery`, `crossroads` (the three TDM maps that have a
  CTF sibling not yet implemented). The picker lists them honestly; the
  launcher logs the full list at startup so the gap is visible.
- **Replicate match state (net assessment item 1-2).** The next real
  correctness item; it would close the "peers are not shootable" gap.
- **Agreed start tic (net assessment item 3).** Closes the late-join offset.
- **LAN discovery (net assessment item 4).** The v1 LAN broadcast. One
  packet type, one read on the existing socket.

---

## Build verification

The full test sweep on this branch:

```
.\gradlew.bat :gdxshared:compileJava :desktop:compileJava
  → BUILD SUCCESSFUL
.\gradlew.bat test
  → BUILD SUCCESSFUL (2690 tests, 0 failures)
.\gradlew.bat checkstyleMain checkstyleTest
  → BUILD SUCCESSFUL (0 warnings)
.\gradlew.bat :desktop:installDist
  → BUILD SUCCESSFUL
```

The two-peer test, run with the installDist image:

```
$ java -cp 'desktop/build/install/desktop/lib/*' com.openfps.desktop.DesktopLauncher --net=1:5021 --peer=2@127.0.0.1:5022 --start-in-game --fps=60

  Map picker wired: 13 map(s) registered, current selection = cornerstone
  NetSession open: player 1 on UDP 5021
  Peer 2 at 127.0.0.1:5022 takes slot 1
  Network attached: NetSession{player=1, peers=1, sent=0, received=0, commands=0, strangers=0, malformed=0}
  Multiplayer: NetArgs{player=1, port=5021, peers=[2@127.0.0.1:5022]} — inputs both ways

$ java -cp 'desktop/build/install/desktop/lib/*' com.openfps.desktop.DesktopLauncher --net=2:5022 --peer=1@127.0.0.1:5021 --start-in-game --fps=60

  UI state MENU -> MAP_SELECT
  UI state MAP_SELECT -> MENU
  UI state MENU -> PLAYING
  NetSession open: player 2 on UDP 5022
  Peer 1 at 127.0.0.1:5021 takes slot 1
  KILL entity 2 (tic 1704) — 2 of 7 down
  ...
```

Both processes opened a real socket on their named UDP ports, accepted each
other as peers, exchanged tic inputs, and rendered each other as bodies in
the room. Full logs at `desktop/build/net-peers/peer1.log` and
`desktop/build/net-peers/peer2.log`.

---

## Summary

**Map selection UI** — a purple `SELECT MAP` button on the menu opens a list
of the 13 registered maps, the player's pick is held in `MapSelection` and
seeds the next launch (and this one, when `--map=` agrees). 22 new tests,
0 checkstyle warnings, 2690 total tests pass.

**Two-peer single-machine** — works. Each peer is a real JVM with a real
UDP socket on its named port; the inputs go both ways and each peer draws
the other into a body in the room. The 2-week estimate to get a real
multi-machine match from here is the rest of the lockstep the README's
"NOT in lockstep" section already names.

**WSL feasibility** — works with `apt install openjdk-21-jdk` and a copy
of the install image to the WSL filesystem; use the host gateway IP
(`172.30.192.1`) for `--peer=`. Slow for graphics, fine for networking.

**Network assessment** — the transport is done and tested, and the
remaining work for "two real machines play a real match" is well-defined:
replicate match state for the local player, make bots shootable (it's the
same Match code), agree on a start tic, ship LAN discovery. No new
dependencies, no new architecture.
