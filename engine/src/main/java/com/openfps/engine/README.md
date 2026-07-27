# OpenFPS — Engine Subsystem Map

> **For new contributors**: read this first. Every Java package below has a `README.md`
> with the full subsystem spec. This page is the 2-line elevator pitch for each.

---

## Subsystem Layout (Doom-style, adapted for JVM)

```
+--------------------------------------------------------------------+
|  ENGINE ENTRY          com.openfps.engine.core.EngineMain          |
|  Boots engine, wires HAL adapter, drives game loop.                |
+--------------------------------------------------------------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|  CORE GAME LOOP       com.openfps.engine.core.GameLoop (D_)        |
|  30/60/120 Hz tic producer; publishes events to the shared bus.    |
+--------------------------------------------------------------------+
       |            |            |            |            |
       v            v            v            v            v
+--------+  +-----------+  +--------+  +--------+  +------------+
|   P_   |  |    R_     |  |   S_   |  |   G_   |  |     W_     |
| gameplay|  |  render   |  | audio  |  |  net   |  |  resource  |
+--------+  +-----------+  +--------+  +--------+  +------------+
       |            |            |            |            |
       +------------+------------+------------+------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|  Z_ MEMORY  com.openfps.engine.memory                                |
|  Zone allocator, tag-based bulk free, bypasses JVM GC for hot path. |
+--------------------------------------------------------------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|  I_ HAL     com.openfps.engine.hal                                   |
|  Hardware abstraction: port interfaces + platform adapters.        |
|  desktop/  mobile/  null/  -- only adapters live here.             |
+--------------------------------------------------------------------+
```

---

## Subsystems (2-line pitch each)

| Prefix | Package | What it does |
|---|---|---|
| `D_` | `core` | Runs the game at a configurable 30/60/120 Hz tic rate and publishes events to the bus; a worker pool dispatches them to subsystems. |
| `P_` | `gameplay` | Holds player state, entities, map logic, and physics (collision, sliding, BSP-assisted movement). |
| `R_` | `render` | (Stub) Multi-threaded software triangle rasterizer — z-buffer, perspective-correct interpolation, mipmapped bilinear sampling. Produces a framebuffer; the platform adapter presents it. |
| `S_` | `audio` | (Stub) Plays 3D-positioned SFX and background music. Adapter-agnostic. |
| `G_` | `net` | P2P tic distribution, peer discovery (LAN), snapshot delta, and latency compensation. |
| `W_` | `resource` | Reads `.wad` files (maps, textures, flats, sprites) and caches lumps by name and index. |
| `Z_` | `memory` | Custom zone heap with tag-based bulk free — bypasses JVM GC for game objects. |
| `I_` | `hal` | The only place platform code lives. Port interfaces + desktop / mobile / null adapters. |

---

## Folder layout

```
engine/
├── core/           D_ main game loop, EngineMain entry
├── gameplay/       P_ player, entities, physics
│   ├── port/         (interfaces)
│   └── adapter/      (null/stub impls)
├── render/         R_ rendering pipeline
│   ├── port/
│   └── adapter/
├── audio/          S_ sound engine
│   ├── port/
│   └── adapter/
├── net/            G_ P2P networking
│   ├── port/
│   └── adapter/
├── resource/       W_ WAD file loader
│   ├── port/
│   └── adapter/
├── memory/         Z_ zone allocator
│   ├── port/
│   └── adapter/
├── hal/            I_ hardware abstraction
│   ├── port/         (port interfaces — core depends only on these)
│   └── adapter/
│       ├── desktop/    (LWJGL3 + OpenAL — Phase 1)
│       ├── mobile/     (Android Canvas / AudioTrack — Phase 1)
│       └── nulladapter/ (headless / CI — current)
└── common/         shared types: FixedMath, Constants
```

Every package has its own `README.md` with the full design. **Read top-to-bottom, D_ → P_ → R_ → S_ → G_ → W_ → Z_ → I_.**

---

## Architectural rules (enforced by `STYLE.md` and review)

1. **Core never imports an adapter.** Core depends only on `port/` interfaces and `common/`.
2. **Adapters never import each other.** Each adapter is self-contained.
3. **No global state.** All state is in subsystem instances held by the core.
4. **No boxing in hot paths.** `int[]` not `List<Integer>`. `long` not `Long`. See `common/README.md`.
5. **All ports first, adapters second.** A new subsystem ships its interface before any implementation.

---

## Where to start reading

1. `core/README.md` — understand the tic loop
2. `common/README.md` — fixed-point math (used everywhere)
3. `hal/README.md` — how adapters plug in
4. Any subsystem `README.md` — details on that subsystem
