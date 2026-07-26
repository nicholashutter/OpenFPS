# Net (G_) — Peer-to-Peer Networking

> G_ is the network layer. The engine is P2P-first — no dedicated server required
> for small matches. Authority is distributed: every player is a peer.

## What lives here (planned)

- `PeerConnection` — one connected peer (UDP socket + send/recv state)
- `TicCmdBuffer` — ring buffer of `TicCmd` per peer, indexed by tic number
- `SnapshotDelta` — diff-based state serialization between tics
- `Discovery` — LAN peer discovery via UDP broadcast
- `LagCompensator` — rewind world state for client-side hit detection

## Subsystem layout

```
net/
├── port/
│   └── I_NetworkPort.java   interface — called by core per tic
└── adapter/
    └── NullNetworkPort.java stub
```

## P2P model — what's coming

### Topology

We use a **star-shaped** P2P for simplicity in v1 (every peer connects directly
to every other peer). For more than 8 players this becomes a mesh with relay
peers. v1 target: up to 8 players, direct UDP.

Each peer:
- Owns a 32-bit `playerId` (assigned at game creation)
- Sends its `TicCmd` (input) to all peers every tic
- Receives every peer's `TicCmd` for tics `[current - MAX_LATENCY_TICS, current]`
- Runs the same `GameLoop` deterministically on every peer (lockstep)

**Source — Gabriel Gambetta, "Fast-Paced Multiplayer (Parts I–IV)"**:
https://www.gabrielgambetta.com/client-server-game-architecture.html
https://www.gabrielgambetta.com/client-side-prediction.html
https://www.gabrielgambetta.com/entity-interpolation.html
https://www.gabrielgambetta.com/lag-compensation.html

### TicCmd structure

For each tic, each peer sends a 64-byte `TicCmd`:
```
offset  size  field
0       4     playerId
4       4     ticNumber
8       2     forward        (-32768..32767, percent)
10      2     strafe         (-32768..32767, percent)
12      1     buttons        (bitmask: fire, use, jump, ...)
13      3     padding (alignment)
16      4     angle          (degrees × 65536, but compressed)
20      4     pitch          (degrees × 65536, clamped)
24      4     checksum       (for tamper detection)
28..64        reserved
```

The fields are bit-packed; this is the same layout DOOM uses for cmds.

**Source — DOOM source `d_ticcmd.h`**:
https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/d_ticcmd.h

### Network timing math

**RTT (round-trip time):** measured as `now - sentTime` on every received packet,
smoothed with a 7-tap moving average.

**Packet loss rate:** count received vs. sent per peer over a 64-packet window.

**Bandwidth target:** 8 players × 64 bytes/tic × 35 tics/s = **~18 KB/s per peer**.
For an 8-player game, each peer sends/receives ~144 KB/s. Fits comfortably
in any modern broadband.

### Lockstep determinism

For lockstep to work, every peer must compute **the exact same** state given
the same inputs. The original DOOM was lockstep; modern games (Quake 3, Overwatch)
use snapshot + interpolation for better feel. We start with lockstep for
simplicity and add snapshot delta later.

The key rules:
1. **No `HashMap`** — use a fixed-order `int[]` indexed array
2. **No `Math.random`** — use a custom `int`-based PRNG with seed
3. **No `float` accumulation** — fixed-point only (see `common/README.md`)
4. **No `System.currentTimeMillis`** — use `I_TimePort`
5. **No `Thread.sleep`** — game loop times itself

**Source — "1500 Archers on a 28.8: Network Programming in Age of Empires and Beyond"**:
https://www.gamedeveloper.com/programming/1500-archers-on-a-288-network-programming-in-age-of-empires-and-beyond

### Lag compensation

For hit detection (did my bullet hit that player?), we need to evaluate
the world state at the time the *shooter* saw the target, not at the time
the server processes the shot. The lag compensator:

1. Records `GameState` snapshots every tic (ring buffer, depth = `MAX_LATENCY_TICS`)
2. On a shot from peer P, look up the snapshot from `now - peerP.rtt/2` ago
3. Test bullet path against the rewinded state
4. Apply damage in current state at the corresponding player

**Source — "Lag Compensation for Real-Time Games" — Vinnie Lee, 2014**:
http://www.vinnieleer.com/articles/lag-compensation-in-real-time-games/

### Snapshot delta

After lockstep, we'll add snapshot deltas for large objects (positions,
health). Format:

```
delta header:
  4 bytes  base tic number
  4 bytes  base state checksum
  4 bytes  delta entity count

per delta entity:
  2 bytes  entityId
  1 byte   field mask (bit 0=pos, bit 1=vel, bit 2=health, ...)
  2 bytes  field-id (only the fields in the mask, in order)
```

Bit-packing is critical here. The original Quake 3 netcode is a masterclass:

**Source — Quake 3 source `qcommon/net_chan.c`**:
https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/net_chan.c

### Discovery

LAN discovery: every peer broadcasts a small announcement packet every second
on a fixed port (default 5021). The packet includes:
- 4 bytes magic (`OPF1`)
- 4 bytes version
- 32 bytes peer name (UTF-8, null-padded)
- 2 bytes game port
- 4 bytes current player count
- 4 bytes max player count

Receivers filter by magic + version, parse the rest, and decide whether to
display a "join" option in the lobby.

**Source — Bonjour/Zeroconf (for inspiration on a more robust version)**:
https://developer.apple.com/library/archive/documentation/Networking/Conceptual/dns_sd_networking_discovery_service/

## Files

- `port/I_NetworkPort.java`
- `adapter/NullNetworkPort.java`

## TODO (Phase 3)

- `PeerConnection` (UDP, non-blocking, ring buffer)
- `TicCmdBuffer` (lockstep)
- `SnapshotDelta` (encode/decode)
- `Discovery` (LAN broadcast)
- `LagCompensator` (rewind for hits)
