# Common — Shared Types and Math

> Utilities used by every other subsystem. **Nothing platform-specific** lives here.

## Files

- `FixedMath.java` — 16.16 fixed-point arithmetic (convert, multiply, divide, abs, clamp)
- `Constants.java` — global engine constants
- `UserProfile.java` — immutable user profile (Phase 1.4), persisted through
  `I_UserProfilePort`; see `hal/README.md` for the SQLite adapter

## Why fixed-point?

Most game engines use `float` or `double`. The original DOOM used **fixed-point
integers** because (a) 386/486 CPUs had no FPU, (b) integer math is deterministic
across machines (no rounding differences), and (c) it's faster on hardware that
lacked SIMD.

For us the reasons are different but still apply:
1. **JVM boxing cost.** Every `Float` / `Double` allocates. `int` does not.
2. **JIT inlining.** Integer math inlines better than floating-point with strictfp.
3. **Determinism for netcode.** `int` arithmetic is exactly reproducible and its
   overflow is defined, so a fixed-point simulation is trivially safe to run in
   lockstep. This is a narrower edge than it sounds, and it is worth being
   precise about: since **JEP 306** (Java 17) every floating-point expression is
   FP-strict IEEE 754, so `+ - * /` and `sqrt` on `float` / `double` are
   bit-identical on every conforming JVM and CPU — ARM and x86 included. Plain
   float arithmetic does **not** desync peers. What can desync them is the
   transcendentals: `Math.sin` / `Math.cos` are permitted 1–2 ulp of
   implementation freedom, which is why float code in the engine calls
   `StrictMath` instead. See `gameplay/README.md`
   § "Determinism — a documented deviation from `PLAN.md` § 4" for the full
   argument and the test that enforces it.
4. **Cache locality.** Arrays of `int` are half the size of arrays of `float`.

> The "float differs between ARM and x86" claim also survives in `FixedMath`'s
> own class Javadoc (reason 3 there). It is wrong for the same reason; treat
> `gameplay/README.md` as the source of truth until that comment is corrected.

The tradeoff: limited range (`int` = ±2.1 billion ≈ ±32 768 map units in 16.16)
and reduced precision (1/65536 ≈ 0.000015 unit). Plenty for a 2D floorplan and
integer tile coordinates.

**Source code references for further reading:**
- DOOM source: https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/m_fixed.c
- The "Fixed Point Math" chapter of *Tricks of the Windows Game Programming Gurus* (LaMothe, 2002)
- "Using Fixed-Point Math" — Olivier Lebrun, GameDev.net: https://www.gamedev.net/tutorials/_/technical/math-and-physics/using-fixed-point-math-r2528/

## 16.16 format

```
1.0  = 0x00010000  = 65536
0.5  = 0x00008000  = 32768
0.0  = 0x00000000  = 0
-1.0 = 0xFFFF0000  = -65536
```

Conversion:
```java
int fixed = (int)(floatValue * 65536.0f);
float back = fixed / 65536.0f;
```

Multiplication (16.16 × 16.16 → 16.16):
```java
int mul(final int a, final int b)
{
    return (int) (((long) a * b) >> 16);
}
```
The cast to `long` is mandatory — the intermediate product is 32.32, won't fit in `int`.
The right-shift by 16 re-aligns back to 16.16.

Division (16.16 ÷ 16.16 → 16.16):
```java
int div(final int a, final int b)
{
    return (int) (((long) a << 16) / b);
}
```
Left-shift numerator by 16 to pre-align, then integer-divide.

## Trig — not implemented, and there is no angle convention yet

`FixedMath` has no sin/cos tables and no angle type. Its public surface is
exactly `fromFloat`, `toFloat`, `mul`, `div`, `abs`, `clamp`, plus the `UNIT`
and `HALF` constants.

An earlier draft did ship a 1024-entry table. It was indexed by degrees while
the surrounding documentation described a degrees × 65536 angle convention, so
the two silently disagreed: a caller that believed the documented convention
got a plausible wrong number back, with no exception and nothing to notice at
the call site. It was deleted rather than left in place as a trap — the
reasoning is preserved in the `TRIG — NOT YET IMPLEMENTED` block of
`FixedMath.java`.

This README then went on describing the deleted table, index expression and
angle convention as if they were live, which re-set precisely the trap the
deletion removed. That is why this section now says what is *not* there.

Trig is **Phase 4/5** work. The first real consumers are player movement in
gameplay and then the renderer, and the table width, angle unit, and rounding
behaviour should be chosen against those callers and a test that pins them
down — not decided in advance in a README. Nothing is blocked on it today:
`gameplay/PlayerController` does its trig in `StrictMath` on floats, which is
a deliberate choice documented in `gameplay/README.md`.

**References for when that lands:**
- https://en.wikipedia.org/wiki/Trigonometric_tables
- DOOM source `tables.c` (`finesine[]`, `angle_t`) and `r_main.c` (angle setup)
- "How DOOM's tables work" — Fabien Sanglard: http://fabiensanglard.net/doomIphone/doomIphone.php (search for "TRIG")
- Michael Abrash's *Graphics Programming Black Book*, Chapter 50 ("Fast Trig Functions")

## Constants reference

> **Not here: frame rate.** `TIC_RATE` / `MS_PER_TIC` / `NANOS_PER_TIC` were
> removed in Phase 1.3. The rate is configuration, not a constant — see
> `core/FrameRate.java` (closed enum: 30 / 60 / 120 Hz) and `core/GameConfig.java`.

All fifteen, in declaration order:

| Constant | Value | Meaning |
|---|---|---|
| `MAX_PLAYERS` | 8 | Per-match player cap |
| `DEFAULT_NET_PORT` | 5021 | Default P2P bind port |
| `TIC_BUFFER_SIZE` | 64 | Tic command ring depth — also the ack bitfield width |
| `MAX_LATENCY_TICS` | 5 | Peer latency cap; the stall threshold |
| `ZONE_HEAP_SIZE` | 16 MB | Default zone allocator heap |
| `ZONE_ALIGN` | 8 | Smallest allocation alignment, bytes |
| `MAP_SCALE` | 65536 | Fixed-point units per map unit (= `FixedMath.UNIT`) |
| `PLAYER_RADIUS` | 16 × `MAP_SCALE` | Collision radius, fixed-point |
| `MAX_OPEN_HEIGHT` | 128 × `MAP_SCALE` | Sector ceiling cap, fixed-point |
| `GRAVITY` | 8 × `MAP_SCALE` / 120 / 120 = 36 | Downward acceleration, fixed-point **per tic²** |
| `PLAYER_SPEED` | 256 × `MAP_SCALE` / 120 = 139 810 | Movement velocity, fixed-point **per tic** |
| `MAX_VELOCITY` | 50 × `MAP_SCALE` | Velocity magnitude clamp |
| `MAX_ENTITIES` | 4096 | Active entities per map |
| `NULL_ENTITY` | -1 | "No entity" sentinel |
| `ENTITY_EMPTY` | 0 | "Empty slot" entity type |

**Read the units on `GRAVITY` and `PLAYER_SPEED` carefully.** They are authored
as 8 map-units/sec² and 256 map-units/sec, but what is stored is already scaled
to fixed-point **per tic at 120 Hz** — the `/ 120` factors are baked into the
constant. They do not rescale when `FrameRate` is 30 or 60; a consumer at
another rate has to convert. Both are also floored by integer division, so
neither is exactly the ideal value. `PlayerController.MOVE_SPEED_UNITS_PER_SECOND`
is the worked example: it divides `PLAYER_SPEED` by `MAP_SCALE` and multiplies
by 120 to recover units/sec, lands a shade under 256, and derives rather than
rounds precisely so it reproduces that shortfall.

The networking constants are load-bearing rather than decorative.
`TIC_BUFFER_SIZE` is both the tic ring depth and the ack bitfield width
(`net/AckWindow.WIDTH` is defined as it), `MAX_LATENCY_TICS` is the stall
threshold in `PeerConnection`, and `DEFAULT_NET_PORT` is the desktop datagram
bind port. `net/README.md` sizes the whole design off them.

Several constants still have **no caller** — `MAX_OPEN_HEIGHT`, `GRAVITY`,
`MAX_VELOCITY`, `MAX_ENTITIES`, `NULL_ENTITY`, `ENTITY_EMPTY`, and
`PLAYER_RADIUS` (referenced in prose, not read). That is intentional: the value
is fixed here once so that the phase which lands the feature reads it instead of
inventing a magic number. `STYLE.md` § 13.2 says what to use each one for —
note its "only `ZONE_HEAP_SIZE` and `ZONE_ALIGN` have callers today" line has
since gone stale; net, gameplay and resource all read from here now.

The physics values are tuned for "feels like Doom". Change them and everything
else (player height, jump arc, projectile speed) needs to be re-tuned together.
