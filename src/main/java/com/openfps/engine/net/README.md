# Net (G_) — Peer-to-Peer Networking

> G_ is the network layer. The engine is P2P-first — no dedicated server required
> for small matches. Authority is distributed: every player is a peer.

**Read [Transport decision](#transport-decision) at the bottom of this file first.**
It records the protocol choice and supersedes several numbers in the sections
above it.

## What lives here (planned)

- `PeerConnection` — per-peer state (address, RTT, ack window, loss stats).
  Holds no socket; one shared `DatagramChannel` lives in the adapter.
- `TicCmdBuffer` — ring buffer of `TicCmd` per peer, indexed by tic number
- `SnapshotDelta` — diff-based state serialization between tics
- `Discovery` — LAN peer discovery via UDP broadcast

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

v1 is a **full mesh**: every peer sends directly to every other peer, with no
hub. That is O(n²) in packets — the same input is unicast 7 times in an 8-player
match, because there is no broadcast on the internet. Beyond `MAX_PLAYERS` this
has to move *toward* a star: a hub or relay peer that forwards on behalf of
others. v1 target: up to 8 players, direct UDP, LAN only (see
[NAT traversal](#9-nat-traversal-the-honest-part)).

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

Each tic, each peer sends a **12-byte** `TicCmd`:
```
offset  size  field
0       4     ticNumber
4       2     forward        (-32768..32767, percent)
6       2     strafe         (-32768..32767, percent)
8       2     angle          (quantized: 65536 steps / 360° ≈ 0.0055°)
10      1     pitch          (quantized: 256 steps / 180° ≈ 0.7°)
11      1     buttons        (bitmask: fire, use, jump, ...)
```

`playerId` is deliberately **not** in the cmd — it lives once in the packet
header, since every cmd in a packet comes from the same sender. At the
redundancy window this doc settles on, that alone saves 28 KB/s per peer.

Size is the binding constraint on the whole design, not a detail: every byte
here is multiplied by the redundancy window **and** by the mesh fanout. See
[Bandwidth and memory](#6-bandwidth-and-memory-recomputed) for the arithmetic.

There is no `checksum` field. Per-cmd checksums are covered under
[Security and trust model](#10-security-and-trust-model); desync detection is a
separate periodic packet.

DOOM is the precedent for keeping the cmd *small* — its `ticcmd_t` is 8 bytes
(`forwardmove`, `sidemove`, `angleturn`, `consistancy`, `chatchar`, `buttons`),
not the 64 an earlier draft of this file claimed.

**Source — DOOM source `d_ticcmd.h`**:
https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/d_ticcmd.h

### Network timing math

**RTT (round-trip time):** measured as `now - sentTime` on every received packet,
smoothed with an **EWMA** (exponentially weighted moving average):

```
smoothedRtt = 0.7 × smoothedRtt + 0.3 × rtt
```

One `long` of state, no ring buffer, and it is what Quake 3 actually does. (An
earlier draft called this a "7-tap moving average" — that is a different
algorithm, and it would cost a 7-slot buffer per peer for no benefit.)

**Packet loss rate:** count received vs. sent per peer over a 64-packet window.

**Bandwidth:** see [Bandwidth and memory](#6-bandwidth-and-memory-recomputed).
The figures live in exactly one place so the two copies cannot drift apart.

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

### Lag compensation — Phase 4+, not Phase 3

For hit detection (did my bullet hit that player?), you evaluate the world state
at the time the *shooter* saw the target, not at the time it is processed:

1. Records `GameState` snapshots every tic (ring buffer, depth = `MAX_LATENCY_TICS`)
2. On a shot from peer P, look up the snapshot from `now - peerP.rtt/2` ago
3. Test bullet path against the rewinded state
4. Apply damage in current state at the corresponding player

**Under pure lockstep there is nothing to rewind.** Every peer simulates
identical inputs at identical tic numbers, so every peer already computes the
same hit result — the world state at tic *N* is by construction the same
everywhere. Lag compensation only becomes meaningful once prediction or
snapshot interpolation exists, and it presumes an authority to do the rewinding.
Shipping `LagCompensator` alongside `TicCmdBuffer` would be contradictory, so it
moves to Phase 4+ in `PLAN.md` § 7.

**Source — Yahn Bernier, "Latency Compensating Methods in Client/Server In-game
Protocol Design and Optimization" (GDC 2001)** — the canonical reference:
https://developer.valvesoftware.com/wiki/Latency_Compensating_Methods_in_Client/Server_In-game_Protocol_Design_and_Optimization

**Source — Gabriel Gambetta, "Lag Compensation"**:
https://www.gabrielgambetta.com/lag-compensation.html

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

---

# Transport decision

> **Status**: Accepted · **Scope**: Phase 3 transport
> **Dependency outcome**: **none added** — this document is the discussion
> `AGENTS.md` requires before adding a library. `PLAN.md` § 6 and `STYLE.md` § 12
> are unchanged; the options were evaluated, not overlooked.
> **Supersedes**: the reliability model, cmd layout, RTT smoothing and bandwidth
> figures in the sections above.

## 1. Decision

**UDP datagrams over a single non-blocking `java.nio.channels.DatagramChannel`,
with an application-level redundant-input-redelivery layer.**

- **Transport** — UDP. Unordered, unreliable datagrams. JDK stdlib, zero deps.
- **Reliability** — redundancy, *not* retransmission. Every packet carries all
  inputs since the peer's last ack, so loss costs no extra round trip.
- **Sockets** — one bound socket, demultiplexed by source address. Not one per peer.
- **Discovery** — LAN broadcast for v1.
- **NAT traversal** — deferred, with an honest cost (§ 9). v1 is LAN-only.
- **Encryption** — out of scope for v1. Stated, not implied (§ 10).

## 2. Constraints this had to satisfy

Each row is a rule already in the repo, not a preference invented here.

| Constraint | Source | Consequence |
|---|---|---|
| No external libraries without discussion | `AGENTS.md` | A library must clear a bar the JDK already meets |
| JDK 17 stdlib only in adapters | `hal/README.md` step 3 | No Netty, no LWJGL sockets |
| Java 17 source/target | `PLAN.md` § 6 | FFM is final in 22 — unavailable without a language bump |
| Android is a target (Phase 3+) | `PLAN.md` § 6 | Per-ABI native builds are disqualifying |
| Pure Java, no native deps, permissive licence | `build.gradle.kts` SQLite comment | The de-facto dependency rubric |
| No `new byte[]` outside a memory port | `STYLE.md` § 13.4 | Receive path must reuse buffers |
| No boxed collections in hot paths | `STYLE.md` § 4.2 | Peer and tic state are primitive arrays |

## 3. Why not TCP, or "TCP with a deadline"

The idea: keep TCP's reliability, but let the receiver skip a packet that has
already arrived too late to matter.

TCP has no API surface at which that can happen. It presents an ordered **byte
stream**, not messages — there is no "packet" for an application to skip, and
the kernel will not deliver byte *n+1* until byte *n* arrives. A single lost
segment therefore stalls every byte behind it until the retransmit lands. This
is head-of-line blocking, and it is a consequence of the ordering guarantee
itself, not an implementation artifact.

The knobs usually reached for do not address it:

| Knob | What it actually does |
|---|---|
| `TCP_NODELAY` | Disables Nagle — send-side coalescing. Does not touch head-of-line blocking. |
| `SO_SNDBUF` / `SO_RCVBUF` | Bounds buffering. A full buffer blocks or drops the *connection's* progress, not one message. |
| `TCP_USER_TIMEOUT` | Abandons the **whole connection** after a deadline. All-or-nothing, not per-message. |

The cost is quantifiable. Linux `TCP_RTO_MIN` is 200 ms, so the minimum stall
from one dropped segment is 200 ms — **12+ frozen tics at 60 Hz**, for every
peer, from a single lost packet.

The *goal* is right, and it survives verbatim into § 5: never wait on data that
is already too old to be useful. It simply has to live above an unordered
datagram service, because a reliable ordered stream is definitionally the one
layer that cannot express it.

## 4. What lockstep actually requires

There is an apparent paradox here worth resolving explicitly.

Lockstep cannot advance tic *N* until **every** peer's input for *N* is in hand.
Input is therefore required — you cannot drop it. That looks like it demands
TCP-style reliability, which § 3 just ruled out.

It does not, because retransmission is not the only way to get reliability.
Retransmit-on-timeout reintroduces the exact failure TCP has: you wait ≥1 RTT to
*learn* about the loss, then ≥1 RTT for the resend.

**The resolution is redundancy, not retransmission.** Inputs are tiny, so send
them repeatedly instead of tracking which were lost:

```
packet := playerId | latestTic | ackBitfield | cmd[latestTic-W+1 .. latestTic]
```

Every packet carries the last `W` tics of input. Losing *k* consecutive packets
is fully covered by the next packet that arrives, as long as `k < W` — **with
zero added latency**, because the recovery data was already in flight before
anyone knew a loss had occurred.

Mechanics:

- **Ack** — highest contiguous tic received, plus a **64-bit bitfield** covering
  the previous 64 tics. That width is exactly `Constants.TIC_BUFFER_SIZE`, which
  this makes load-bearing rather than reserved.
- **Duplicates** — the receiver indexes by tic number, so a re-delivered cmd is
  an idempotent overwrite. No dedup bookkeeping.
- **Window** — `W = ceil(RTT / ticDuration) + 2`, clamped to a configured max.
- **Stall** — if a peer is more than `MAX_LATENCY_TICS` behind, the sim blocks.
  That is lockstep working as designed, not a bug to paper over.

Cost: roughly 200 lines of pure Java. No dependency.

**Source — Glenn Fiedler, "Deterministic Lockstep"**:
https://gafferongames.com/post/deterministic_lockstep/

**Source — Glenn Fiedler, "Reliability and Congestion Avoidance over UDP"**:
https://gafferongames.com/post/reliability_ordering_and_congestion_avoidance_over_udp/

## 5. Where "drop the stale packet" *is* correct

Snapshot and state-sync traffic — Phase 4+. Each snapshot supersedes the last,
so the receiver should discard anything older than what it has already applied:

```
if (seq <= lastAppliedSeq)
{
    return;
}
```

The distinction that governs which model applies:

> **Input is a sequence — every element is needed.**
> **State is a sample — only the newest is needed.**

Both eventually share one socket, on different channels. Phase 3 ships only the
input path.

**Source — Glenn Fiedler, "Snapshot Interpolation"**:
https://gafferongames.com/post/snapshot_interpolation/

## 6. Bandwidth and memory, recomputed

The figures previously in this file omitted the 28-byte IPv4+UDP header and
assumed zero redundancy, which § 4 makes mandatory. Actual cost per datagram:

```
bytes = 28 (IPv4 20 + UDP 8) + 16 (header) + cmdSize × W
KB/s  = bytes × 7 peers × rate ÷ 1000
```

At 60 Hz, 8 players, per peer, **each direction**:

| cmd | W | bytes/pkt | KB/s | verdict |
|---|---|---|---|---|
| 64 B | 1 | 108 | 45 | what the old "27 KB/s" figure should have said |
| 64 B | 8 | 556 | 233 | unaffordable — this is why the cmd shrank |
| 16 B | 8 | 172 | 72 | acceptable |
| **12 B** | **8** | **140** | **59** | **recommended** |
| 12 B | 4 | 92 | 39 | low-loss networks |
| 12 B | 8 | 140 | 29 | same, at 30 Hz |

Where latency and memory genuinely pull apart:

- **Larger `W`** tolerates longer loss bursts (latency win) but costs bandwidth
  and a deeper receive ring (memory cost). `W = 8` absorbs 7 consecutive losses
  — ~117 ms at 60 Hz — which exceeds any loss burst worth designing for.
- **Smaller cmd** makes redundancy affordable but costs input fidelity: 32-bit
  angle becomes 16-bit quantized (0.0055°/step — far below human perception).
- **Mesh fanout is O(n²)** and is not tunable. Growing past `MAX_PLAYERS = 8`
  changes topology, not parameters.

Memory, and this is the part that stays flat:

```
TIC_BUFFER_SIZE(64) × MAX_PLAYERS(8) × 12 B = 6 KB
```

Six kilobytes for the entire input ring, allocated **once at init** from
`I_MemoryPort`. Steady-state per-tic allocation is **zero**.

**MTU** — keep datagrams ≤ 1200 B to avoid fragmentation. Not binding at 12 B
cmds (`W ≤ 96`); binding at 64 B (`W ≤ 18`).

**One NIO detail that decides the receive path**: `DatagramChannel.receive()`
with a **heap** `ByteBuffer` makes the JDK copy through a temporary direct
buffer on every single call. Allocate one **direct** `ByteBuffer` per channel at
init and reuse it. This is how the receive path honors `STYLE.md` § 13.4 without
fighting it.

## 7. Options evaluated

| Option | Runtime requirement | Licence | Android | NAT traversal | Delivery model | Alloc/packet | Maintained | Cost to adopt | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **`DatagramChannel`** | **JDK 17 stdlib** | — | **Yes** | Manual | **Unordered datagram** | **0 (reused direct buffer)** | JDK | **~200 LOC** | **Chosen** |
| `DatagramSocket` | JDK 17 stdlib | — | Yes | Manual | Unordered datagram | 1 `byte[]` per receive | JDK | ~200 LOC | Older blocking API |
| KryoNet | JDK + jar | BSD-3 | Untested | No | Reliable ordered + unreliable | Object graph | **No** | Dependency approval | Unmaintained |
| Netty | JDK + jars | Apache-2.0 | Hostile | No | Both | Pooled off-heap | Yes | Dependency approval | Far too heavy |
| Netty QUIC | JDK + native | Apache-2.0 | No | No | Per-stream reliable | Pooled | Incubator | Dependency + native | Reliable-only |
| Aeron | JDK + media driver | Apache-2.0 | No | **No** | Reliable ordered | Off-heap | Yes | Dependency + driver | Datacenter, not P2P |
| KCP (Java ports) | JDK + jar (+Netty) | MIT / Apache-2.0 | Untested | No | Reliable ordered ARQ | Per message | Varies | Dependency approval | ARQ we don't want |
| ENet | JNI + native per ABI | MIT | Per-ABI build | No | Reliable + unreliable | Native | C lib yes | Binding + build matrix | Java bindings stale |
| **GameNetworkingSockets** | **JNI, or FFM (JDK 22+)** | BSD-3 | Per-ABI build | **Yes (ICE)** | Reliable + unreliable | Native | Yes | Binding + 6-platform matrix | See § 8 |
| QUIC (generally) | JDK 21+ / native | varies | Partial | No | Per-stream reliable | varies | Yes | — | Wrong tool |
| WebRTC data channels | Native or heavy JVM stack | BSD-3 | Yes | **Yes (ICE)** | Configurable partial | Native | Yes | Huge | Enormous for LAN P2P |

The one disqualifying reason, per option:

| Option | Out because |
|---|---|
| `DatagramSocket` | Blocking-era API; `DatagramChannel` is strictly better and equally stdlib |
| KryoNet | Unmaintained, and reflective object serialization is non-deterministic |
| Netty | An event-loop framework to send 140-byte datagrams |
| Netty QUIC | Fully reliable — reintroduces head-of-line blocking per stream |
| Aeron | Assumes a trusted network with a media driver; no NAT traversal at all |
| KCP | Solves reliable-ordered-fast; we need unreliable-with-redundancy |
| ENet | Native per ABI, and the Java bindings are abandoned |
| GameNetworkingSockets | § 8 |
| QUIC | Reliable by design — the property § 3 rules out |
| WebRTC | Correct feature set, disproportionate cost, and native anyway |

## 8. The native option, evaluated and rejected

Valve's **GameNetworkingSockets** is the strongest technical option on this list
and deserves a real rebuttal rather than a table row. It is BSD-3-Clause,
production-proven, and genuinely good: mature congestion control, encryption,
message fragmentation, and ICE-based NAT punching — the last of which is the
hardest problem in § 9, already solved.

It is still rejected, for reasons that compound:

1. **No Java binding exists.** Third-party bindings ship for C#, Go and Rust.
   We would be writing and maintaining the Java one.
2. **Java 17 gives us JNI only.** FFM is final in 22. Adopting FFM means moving
   source/target 17 → 22, contradicting `PLAN.md` § 6 and `build.gradle.kts`.
   Adopting JNI instead means hand-written C glue.
3. **Transitive native deps** — protobuf plus OpenSSL or libsodium.
4. **The build matrix is what actually kills it**: win/linux/mac × x64/arm64,
   **plus** Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`). Android is a
   stated goal in `PLAN.md` § 6, and this is irreconcilable with it.
5. **Shipping natives in a JAR** requires an extract-to-temp loader — file I/O
   and buffer allocation outside the HAL, violating two rules at once.

What we give up by saying no: mature congestion control, encryption, and working
NAT traversal. § 4 recovers the reliability property that matters for lockstep
at ~200 lines. § 9 and § 10 are honest that the other two are simply not solved
here.

**Source**: https://github.com/ValveSoftware/GameNetworkingSockets

## 9. NAT traversal: the honest part

This is the genuinely hard part of P2P, and no pure-Java library solves it.
Three tiers, with real costs:

| Tier | Approach | Cost | Works |
|---|---|---|---|
| **T0** | LAN UDP broadcast discovery | Zero deps | Same subnet only |
| **T1** | STUN + UDP hole punching | ~30 LOC, public STUN servers | Cone NATs; **fails on symmetric NAT and CGNAT** |
| **T2** | TURN relay / host authority | **Servers, ops, money** | Everywhere |

**T0 ships in Phase 3.** It is already specified under [Discovery](#discovery)
above, needs nothing new, and works.

T1 is cheap to implement (an RFC 5389 binding request is a small pure-Java
message) but carries no guarantee — symmetric NAT defeats hole punching outright.

T2 works universally and **contradicts the opening line of this file** ("no
dedicated server required"). That is a product decision with an operational
budget, not a config flag.

Android specifics: receiving broadcast/multicast requires a
`WifiManager.MulticastLock`, and mobile carriers sit behind CGNAT — making
phone-to-phone P2P effectively impossible without T2.

**Verdict: v1 is LAN-only, stated plainly.** Internet play is a Phase 4+ scope
item with a real cost attached.

## 10. Security and trust model

**The engine has no anti-cheat, and v1 has no encryption.** Stating this
explicitly so it is a decision rather than an oversight.

In symmetric P2P every peer runs the simulation and every peer can lie. A
checksum a sender computes over its own cmd is trivially recomputed by a sender
that is tampering — it detects **corruption**, not tampering, and UDP already
carries a checksum for corruption. An earlier draft of this file labelled the
cmd's `checksum` field "for tamper detection"; that field is removed.

What replaces it is **desync detection**, which is what DOOM's `consistancy`
field actually was: a hash of local world state, exchanged periodically and
compared across peers. A mismatch means the simulations have diverged — a
determinism bug, or someone cheating — and the match should halt. Because it is
periodic rather than per-tic, it costs almost nothing:

```
every 32 tics:  playerId | ticNumber | stateHash(4B)
```

Real anti-cheat requires either an authoritative server or cryptographic
attestation. Neither is in scope, and neither is compatible with the "no
dedicated server" premise.

## 11. What this commits Phase 3 to

| Component | Consequence of this decision |
|---|---|
| `PeerConnection` | Peer *state* only — address, RTT (EWMA), ack window, loss stats. **No socket.** |
| `TicCmdBuffer` | `int[]`/`byte[]` ring, `TIC_BUFFER_SIZE` deep × `MAX_PLAYERS` wide, allocated once |
| `RedundantSender` | Packs `[lastAcked+1 .. latest]` per packet; the § 4 layer |
| `Discovery` | Unchanged — LAN broadcast, `DEFAULT_NET_PORT` |
| `DesktopNetworkPort` | Phase 1.5, **datagram-only**. One direct `ByteBuffer`, reused. |

Reserved constants that become load-bearing:

| Constant | Becomes |
|---|---|
| `TIC_BUFFER_SIZE` = 64 | Ack bitfield width **and** ring depth |
| `MAX_LATENCY_TICS` = 5 | Stall threshold before the sim blocks |
| `MAX_PLAYERS` = 8 | Mesh fanout |
| `DEFAULT_NET_PORT` = 5021 | Bind port |

No new constants are needed (`STYLE.md` § 13.3).

Two API consequences, recorded now and executed later:

1. **`pollTicCmd(int ticIndex, int peerId)` returns `byte[]`** — one allocation
   per call, and a poor fit for redundant redelivery, which delivers *many* tics
   for one peer in a single packet. Phase 3 should replace it with a
   fill-caller's-buffer form.
2. **Two interfaces are both named `I_NetworkPort`** — `net.port` (high-level
   G_) and `hal.port` (low-level UDP). This becomes actively confusing the
   moment `DesktopNetworkPort` lands in Phase 1.5. Recommend renaming the HAL
   one to `I_DatagramPort`, which is what it is (`send`/`receive`/`bind`/`close`).
   Touchpoints: `hal/README.md` port table, `STYLE.md` § 13.1, `NullAdapterFactory`.

## 12. Corrections applied to this file

| Was | Now |
|---|---|
| "star-shaped ... every peer connects to every other" | Full **mesh**; a star has a hub. Terms were inverted |
| 64-byte `TicCmd`, 36 bytes reserved | 12 bytes — cmd size is the binding bandwidth constraint |
| "same layout DOOM uses" | DOOM's `ticcmd_t` is 8 bytes; cited as the precedent for *small* |
| `checksum` "for tamper detection" | Removed; replaced by periodic desync detection (§ 10) |
| "27 KB/s each way" | ~59 KB/s at the recommended cmd size and window (§ 6) |
| RTT "7-tap moving average" | EWMA — one `long`, and what Quake 3 does |
| `LagCompensator` in Phase 3 | Phase 4+ — nothing to rewind under pure lockstep |
| `PeerConnection` = "UDP socket per peer" | Peer state; one shared socket in the adapter |
| Citation `vinnieleer.com` | Dead domain (does not resolve). Replaced with Bernier, GDC 2001 |

## 13. Revisit triggers

- Player count needs to exceed 8 → topology change, not tuning
- Internet play becomes a P0 requirement → § 9 T2, with its operational cost
- Determinism proves unattainable across JVM/Android → snapshot + authority,
  which changes the transport requirements wholesale

## Files

- `port/I_NetworkPort.java`
- `adapter/NullNetworkPort.java`

## TODO (Phase 3)

- `PeerConnection` (peer state — address, RTT, ack window; no socket)
- `TicCmdBuffer` (lockstep ring, preallocated)
- `RedundantSender` (redundant input redelivery — § 4)
- `SnapshotDelta` (encode/decode)
- `Discovery` (LAN broadcast)
