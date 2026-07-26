# Common — Shared Types and Math

> Utilities used by every other subsystem. **Nothing platform-specific** lives here.

## Files

- `FixedMath.java` — 16.16 fixed-point arithmetic, lookup trig tables
- `Constants.java` — global engine constants

## Why fixed-point?

Most game engines use `float` or `double`. The original DOOM used **fixed-point
integers** because (a) 386/486 CPUs had no FPU, (b) integer math is deterministic
across machines (no rounding differences), and (c) it's faster on hardware that
lacked SIMD.

For us the reasons are different but still apply:
1. **JVM boxing cost.** Every `Float` / `Double` allocates. `int` does not.
2. **JIT inlining.** Integer math inlines better than floating-point with strictfp.
3. **Determinism for netcode.** `int * int` is the same on every CPU. `float * float`
   can produce last-bit differences between ARM and x86, breaking lockstep P2P.
4. **Cache locality.** Arrays of `int` are half the size of arrays of `float`.

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
int mul(int a, int b) {
    return (int)(((long)a * b) >> 16);
}
```
The cast to `long` is mandatory — the intermediate product is 32.32, won't fit in `int`.
The right-shift by 16 re-aligns back to 16.16.

Division (16.16 ÷ 16.16 → 16.16):
```java
int div(int a, int b) {
    return (int)(((long)a << 16) / b);
}
```
Left-shift numerator by 16 to pre-align, then integer-divide.

## Trig tables

`FixedMath` builds 1024-entry sin/cos tables at class-load time. The index is
`(angle >> 16) & 0x3FF` — the upper 10 bits of the angle word, which gives
~0.35° resolution (360 / 1024). Doom used the same scheme with SLOPE_SHIFT=14
and 8192-entry tables (finer resolution but more memory); we use 1024 because
modern hardware makes the table lookup-vs-computation tradeoff less critical.

**Sources:**
- https://en.wikipedia.org/wiki/Trigonometric_tables
- "How DOOM's tables work" — Fabien Sanglard: http://fabiensanglard.net/doomIphone/doomIphone.php (search for "TRIG")
- Michael Abrash's *Graphics Programming Black Book*, Chapter 50 ("Fast Trig Functions")

## Angle convention

DOOM stores angles as unsigned 32-bit integers, where 0 = East, and the value
**increases counter-clockwise**. The full circle is `ANG90 = 0x40000000` etc.

We use a similar convention: angle is in degrees × 65536 (one full rotation =
360 × 65536 = 23 592 960). For lookups we shift right by 16 to get degrees and
modulo 1024 to get the table index.

**Source:** DOOM source `tables.c`, `r_main.c` (angle setup).

## Constants reference

| Constant | Value | Meaning |
|---|---|---|
| `TIC_RATE` | 35 | Hz — fixed game tic rate |
| `NANOS_PER_TIC` | ~28.5M | 1 sec / 35 |
| `MAX_PLAYERS` | 8 | Per-match player cap |
| `ZONE_HEAP_SIZE` | 16 MB | Default zone allocator heap |
| `MAP_SCALE` | 65536 | Fixed-point units per map unit |
| `PLAYER_SPEED` | 256 / tic | Movement velocity |
| `GRAVITY` | 8 / sec² | Downward acceleration |

These are tuned for "feels like Doom". Change them and everything else
(player height, jump arc, projectile speed) needs to be re-tuned together.
