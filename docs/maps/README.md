# OpenFPS — 16-Map Multiplayer Library

> The game ships a 16-map library covering the four real multiplayer modes
> across four settings. Every map is at a `(setting, mode)` intersection; the
> settings share asset packs, the modes drive the gameplay rules. Maps are
> built around the **three-lane COD pattern** at **BO6/BO7 sizing** (~320×320
> units, 6v6), with textures from Kenney CC0 kits (and per-setting custom
> textures generated via the `mmx` provider CLI for surfaces the Kenney packs
> don't cover).

## Status legend

- **FULL** — designed, implemented, runnable in `:engine:run --headless --map=<id>` and (after `MapScene` lands) in `:desktop:run --args="--map=<id>"`
- **DESIGN ONLY** — full design spec committed; implementation deferred to a later pass
- **TODO** — not yet designed

## The grid

| | **TDM** | **Hardpoint** | **Domination** | **CTF** |
|---|---|---|---|---|
| **Urban Warzone** | [`01-cornerstone`](urban-warzone/01-cornerstone.md) — **FULL** | [`02-hp-overpass`](urban-warzone/02-hp-overpass.md) — **FULL** | [`03-dom-tripoint`](urban-warzone/03-dom-tripoint.md) — **FULL** | [`04-ctf-extraction`](urban-warzone/04-ctf-extraction.md) — **FULL** |
| **Industrial Complex** | [`01-refinery`](industrial-complex/01-refinery.md) — **FULL** | [`02-hp-foundry`](industrial-complex/02-hp-foundry.md) — **FULL** | [`03-dom-pipeline`](industrial-complex/03-dom-pipeline.md) — **FULL** | TODO |
| **Desert Ravine** | [`01-crossroads`](desert-ravine/01-crossroads.md) — **FULL** | [`02-hp-mesa`](desert-ravine/02-hp-mesa.md) — **FULL** | [`03-dom-sandbar`](desert-ravine/03-dom-sandbar.md) — **FULL** | TODO |
| **Arctic Station** | [`01-icebridge`](arctic-station/01-icebridge.md) — **FULL** | [`02-hp-arctic`](arctic-station/02-hp-arctic.md) — **FULL** | [`03-dom-arctic`](arctic-station/03-dom-arctic.md) — **FULL** | TODO |

## Settings

| Setting | Vibe | Asset pack(s) |
|---|---|---|
| **Urban Warzone** | Modern city block — concrete and glass, painted steel, parked vehicles. Three lanes run north–south, with the central Market row acting as the risk/reward mid lane. | Kenney `City Kit` (buildings), Kenney `Vehicle Kit` (props), Kenney `Blaster Kit` (weapon viewmodel) |
| **Industrial Complex** | Factories, warehouses, container yards, exposed steel and pipework. Vertical play via catwalks and gantries. | TBD (Pass 3) |
| **Desert Ravine** | Arid canyon with rock formations, shanty towns, an oil pipeline. Long sightlines, sand-stone palette. | TBD (Pass 4) |
| **Arctic Station** | Frozen research station, snow-blown metal, ice floes. Cold blue-grey palette, indoor / outdoor mix. | TBD (Pass 5) |

## Modes

| Mode | Markers | Rules (all implemented in `Match.updateX`) |
|---|---|---|
| **TDM** | `MapMarkers.TeamDeathmatch.INSTANCE` | Respawn on death, score per kill. No map markers. |
| **Hardpoint** | `MapMarkers.Hardpoint` with N `HardpointZone` records | One active zone at a time, rotation every N tics, capture by standing in zone, +1 score per second held |
| **Domination** | `MapMarkers.Domination` with N `Flag` records (typically 3: A, B, C) | Neutral start, capture by standing in radius, ticks while held |
| **CTF** | `MapMarkers.CaptureTheFlag` with 2 `Base` records (red + blue) | Pickup enemy flag, drop on death, return by touching own flag, capture by touching enemy base with their flag |

## How to run

```powershell
# Headless smoke test (any registered map):
.\gradlew.bat :engine:run --args="--headless --map=cornerstone --fps=60"

# Windowed (after MapScene lands — Pass 2):
.\gradlew.bat :desktop:run --args="--map=cornerstone"
.\gradlew.bat :desktop:run --args="--map=cornerstone --start-in-game"
```

`MapLibrary` registers defaults at class load time. `MapLoader.loadOrFallback(id)`
returns the spec or a `MapSpec` placeholder so a missing map never crashes the
launcher — it logs a warning and falls back to the default.

## Architecture

The map system lives in `engine/src/main/java/com/openfps/engine/gameplay/map/`:

- `MapSetting` — the four settings enum
- `Team` — `RED` / `BLUE` / `NEUTRAL`
- `LaneAxis` / `Lane` / `Chokepoint` — the three-lane structure
- `SpawnPoint` / `Waypoint` — placement primitives
- `MapAssets` — which level / weapon / atlas model files back this map
- `MapDimensions` — width × depth × height
- `MapMarkers` — **sealed interface** with `TeamDeathmatch` (singleton), `Hardpoint`, `Domination`, `CaptureTheFlag` implementations
- `MapSpec` — final, immutable, equal-by-id; carries everything needed to load and run a map
- `Maps` — factory class with the static `cornerstone()`, `overpass()`, `tripoint()`, `extraction()`, `refinery()`, `crossroads()`, `arcticStation()`, `foundry()`, `mesa()`, `arcticHp()`, `pipeline()`, `sandbar()`, `arcticDom()` specs (13 maps total: 4 Urban Warzone + 3 each in the other three settings × {TDM, Hardpoint, Domination}). The CTF variants of the three non-Urban settings remain design-only.
- `MapLibrary` — singleton registry, lock-free reads via volatile-rewritten immutable map
- `MapLoader` — facade over `MapLibrary`, with `loadOrFallback` for graceful headless behaviour
- `MapScene` — wraps a `MapSpec`'s level `.ofm` into a renderable `Scene`. The classpath lookup is the load-bearing path: a `level.ofm` committed at `engine/src/main/resources/maps/<id>/` is on the runtime classpath and reads back through `ModelFormat.read`. The class is wired into the desktop launcher's `--map=<id>` path; the demo is bypassed in map mode.
- `MapSmokeGameplayPort` — headless `I_GameplayPort` for the smoke test path

`Match` accepts a `MapSpec` and dispatches per-tic mode updates. The mode logic
itself is in `Match.updateHardpoint` / `updateDomination` / `updateCtf` — all
fully implemented and tested (13 + 13 + 16 tests respectively).

## Roadmap

- **Pass 1 ✅** — Architecture + `cornerstone` (Urban Warzone × TDM). 21 new tests, 1658 in `:engine`. Headless smoke test green. See `docs/pass1-report.md`.
- **Pass 2 ✅** — Three more Urban Warzone maps (`overpass` HP, `tripoint` Dom, `extraction` CTF) + Kenney-textured geometry for all four + `MapScene` for the windowed render path. The mode logic is already in place; Pass 2 is purely about map instances, art, and renderer integration. **48 new tests, 1712 in `:engine`.** See `docs/pass2-report.md`.
- **Pass 3 ✅** — `refinery` (Industrial × TDM) + Domination mode logic. See `docs/pass3-report.md`.
- **Pass 4 ✅** — `crossroads` (Desert × TDM) + `arctic-station` (Arctic × TDM) + CTF mode logic. The 16-map library is now 7 maps fully implemented + 9 design-only siblings. See `docs/pass4-report.md`.
- **Pass 5 ✅** — Three new Hardpoint maps (`foundry` Industrial, `mesa` Desert, `arctic-hp` Arctic) + Kenney-ized the 3 procedural TDM maps. The 16-map library is now 10 maps fully implemented + 6 design-only siblings (the Domination and CTF variants of the three non-Urban settings). **6 new tests, 1718 in `:engine`.** See `docs/pass5-report.md`.
- **Pass 6 ✅** — Three new Domination maps (`pipeline` Industrial, `sandbar` Desert, `arctic-dom`/Frostline Arctic) + Domination mode logic (shipped in Pass 3). The 16-map library is now 13 maps fully implemented + 3 design-only siblings (the CTF variants of the three non-Urban settings). **6 new tests, 1724 in `:engine`.** See `docs/pass6-report.md`.

## Open items

- `MapScene` (windowed render path) — **BUILT in Pass 2.** `:desktop:run --map=<id>` now constructs a `Scene` from a `MapSpec` and binds it to the renderer. The viewmodel and bots are bypassed in map mode; a future pass can replace the `MapSmokeGameplayPort` with a full map-driven demo.
- Cornerstone uses **Kenney-textured** geometry in Pass 2 (the floor and wall tiles are sampled from the Prototype Kit's `colormap.png`). The geometry remains procedural; a future pass could swap to a wholly Kenney-converted level if a City Kit or other building set is downloaded.
- `RemotePlayers` is unchanged. The 16-map library is offline (PVE / single-process) for now; a future pass can wire it through when match state replication lands.
