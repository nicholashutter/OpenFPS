# Pass: richer map + bots that move freely

> Three user asks:
> 1. **Improve staying in bounds** — physics boundaries so the player and bots cannot escape the playable area.
> 2. **The map is a brown blob** — give it additional textures / visual variety.
> 3. **Enemies move freely** like in the demo — bots should look like they're roaming the level, not walking a closed-form SENTRY loop forever.

---

## 1. What the user is seeing

The shipped map levels (16 of them, `engine/src/main/resources/maps/<id>/level.ofm`) are tiny single-mesh files:

| Map | Vertices | Triangles | Submeshes | Textures |
|---|---|---|---|---|
| foundry | 1,104 | 552 | 3 | 3 |
| mesa | 1,344 | 672 | 3 | 3 |
| cornerstone | 816 | 408 | 3 | 3 |
| overpass | 816 | 408 | 3 | 3 |
| extraction | 432 | 216 | 3 | 3 |
| tripoint | 504 | 252 | 3 | 3 |
| arctic-station | 600 | 300 | 2 | 2 |
| crossroads | 888 | 444 | 2 | 2 |
| refinery | 1,128 | 564 | 2 | 2 |
| arctic-dom | 792 | 396 | 3 | 3 |
| arctic-hp | 624 | 312 | 3 | 3 |
| coldfront | 264 | 132 | 2 | 2 |
| pipeline | 888 | 444 | 3 | 3 |
| sandbar | 1,104 | 552 | 3 | 3 |
| storage | 384 | 192 | 2 | 2 |
| stronghold | 336 | 168 | 2 | 2 |

The demo's level uses 18 Kenney Prototype Kit pieces (walls, floors, columns, crates, stairs, slope) composed at runtime — about 150 textured instances, each with its own real Kenney material. That is the visual the maps are missing.

The bots are on `BotPattern.SENTRY` (closed-form loop around their waypoint). With 8 waypoints in a 320x320 map, the room reads as 8 circles, not as 8 free agents.

---

## 2. Plan

### 2.1 `addLevelKit` — a Kenney-kit composer for maps

Mirror `DemoScene.addKitRoom`, but parameterised by the spec's `MapDimensions` and composed with the Kenney models `DemoModels` already loads. Every map gets:

- A **floor grid** sized to the spec: `width/64 × depth/64` tiles at y=0, each a real Kenney floor piece with its own texture.
- A **ceiling grid** at y=`spec.dimensions().height()`, the same tiles upside down.
- A **perimeter wall** ring (4 walls, 2 courses high) around the playable area. Inner face at `±(spec.w/2 - 6.4)`.
- **Columns** at the four quarter positions (visual rhythm, not gameplay-critical).
- A handful of **crates / stairs / slopes** scattered per the existing `CRATE_PLACEMENTS` style.

For a 320×320×128 spec: 25 floor tiles + 25 ceiling + 40 wall instances + 4 columns + ~6 crates + 1 stairs + 1 slope ≈ 102 textured Kenney instances. The level.ofm stays as the "main play area" backdrop; the kit adds the room around it.

The collision world is built by `PhysicsWorld.fromModel` over the union of the level.ofm's submeshes AND each kit instance's submeshes. The perimeter walls each add one Aabb box to the world's solid table — a player walking toward ±160 stops at the inner face.

### 2.2 Stay in bounds — fallback perimeter ring

If a map's level.ofm has no submeshes that lie along the boundary, `MapScene` derives four perimeter boxes from `spec.dimensions()` and unions them with the level-derived solids. The fallback is in `addPerimeterRing(spec, builder)` and is skipped automatically when the level already covers the boundary (most shipped levels do).

### 2.3 `BotPattern.WANDER` — a real "free movement" pattern

`BotPattern.WANDER` is a new pattern that, on every tic, picks a **random** waypoint from the spec's `botWaypoints()` list and computes a heading toward it. The bot moves at a constant walking speed (the existing `MOVE_SPEED_UNITS_PER_SECOND`). When the bot is within `2 × PLAYER_HALF_WIDTH_UNITS` of the target, it picks a new target — the pause is implicit in the move-until-close loop.

Implementation lives in `Bot` itself, not the pattern: `Bot` already has a `moveTo(tic)` that calls `pattern.offsetX/Z(phase, amplitude)`. Adding a `nextTargetX/Z` pair and steering toward it on every `moveTo` is a 30-line patch. `BotPattern.WANDER` is the marker that the bot's `moveTo` should drive toward the random target rather than a closed-form path.

The collision world handles "what stops the bot" — it doesn't pathfind, but it also doesn't phase through walls, so the visible result is bots that walk toward a waypoint, stop at the first wall in their way, pick a new one, and try again. That is "moving freely around like in the demo" without a pathfinder.

### 2.4 Per-bot parameter variety

Beyond `WANDER`, the existing `SENTRY` pattern is varied per bot:

- `routePeriodTics`: 60, 80, 100, 120 (a stride of the per-bot id)
- `routeAmplitudeUnits`: 16, 24, 32, 40
- `routePhaseTics`: `entityId * 13`

Eight bots with eight different (period, amplitude, phase) tuples do not look like a single closed-form route — they look like eight agents with eight different rhythms. `MapGameplayPort.botsFromSpec` is the place that picks the tuples.

### 2.5 12 waypoints per map

The spec's `botWaypoints()` grows from 8 to 12. `Math.min(12, 8) = 8` is still the bot cap (`Match.DEFAULT_BOT_COUNT`), but the 12 waypoints are what `WANDER` picks from. More waypoints = more "free" looking choices per bot, even when the player is looking at two bots that are clearly going to different rooms.

---

## 3. Files

| File | Change |
|---|---|
| `engine/src/main/java/com/openfps/engine/gameplay/map/MapScene.java` | `addLevelKit(...)`, `addPerimeterRing(...)`, kit-piece placement helpers, union of level + kit into a single `PhysicsWorld`. |
| `engine/src/main/java/com/openfps/engine/gameplay/BotPattern.java` | `WANDER` value; `Bot` gets a `wanderTargetX/Z` pair, the existing `moveTo(tic)` steers toward it. |
| `engine/src/main/java/com/openfps/engine/gameplay/Bot.java` | `nextTarget()` + `setNextTarget(...)`; per-bot `routePeriodTics` / `routeAmplitudeUnits` from the entityId. |
| `engine/src/main/java/com/openfps/engine/gameplay/map/MapGameplayPort.java` | `botsFromSpec(spec)` uses the varied per-bot tuples; `SENTRY` mix with some `WANDER` bots. |
| `engine/src/main/java/com/openfps/engine/gameplay/map/Maps.java` | Bump 12 waypoints per map (added 4 per map, keeping the existing 8 plus 4 new). |
| `engine/src/test/java/com/openfps/engine/gameplay/MapGameplayPortTest.java` | Update for 12-waypoint map, `WANDER` instantiation. |
| `engine/src/test/java/com/openfps/engine/gameplay/map/MapSceneTest.java` | New test: `addLevelKit` adds the expected number of instances. |
| `engine/src/test/java/com/openfps/engine/gameplay/BotPatternTest.java` (or similar) | `WANDER` test: bot moves toward the chosen target and switches on close. |
| `docs/pass-map-richer-and-bots-free.md` | This file. |

---

## 4. Risks

- **WANDER against walls.** The bot will run into a wall and stop without knowing it's hit one. The visible result is "walks toward X, hits wall, picks a new X". That is not a pathfinder; the user may want a follow-up pass. The current ask is "move freely", not "navigate the level" — a pathfinder is a separate project.
- **Kit tiles overlap the level.ofm.** The kit's floor sits at y=0 and the level.ofm also sits at y=0. Visually the level.ofm's geometry is now occluded by the kit's floor tiles. The kit's perimeter walls rise above the level's geometry. We accept the occlusion — the kit is the new visual, the level.ofm is the player's collision.
- **12 waypoints per map.** Each map's spec grows. `Maps.java` already has the waypoint lists; bumping them is a sed-like edit.
- **Performance.** The kit adds ~100 instances per map. A 1280x720 frame at 60 Hz on Steam Deck should be fine, but I will re-run the smoke test and check `ticsApplied` advances to the maxTics.

---

## 5. Verification plan

1. `gradlew.bat check` — full suite green, checkstyle clean
2. Headless smoke test on foundry, 120 tics — expect 8 bots, 100 hp, perimeter walls stop the player, no out-of-bounds death
3. Headless smoke test on mesa and crossroads — confirm kit composition works on different spec dimensions
4. Desktop visual check: `.\gradlew.bat :desktop:run --args="--map=foundry --start-in-game"` — visually confirm 100+ textured instances, perimeter walls, no brown blob

---

## 6. Hand back

After this pass:

- 100+ Kenney kit instances per map (floor, ceiling, perimeter walls, columns, crates, stairs, slope) — each with its own real Kenney texture
- Perimeter walls from the kit keep the player in bounds automatically
- Bots move via a random-target WANDER pattern, with per-bot parameter variety on SENTRY
- 12 waypoints per map give WANDER more destinations to choose from
- 2,531+ tests still pass (will grow as new WANDER tests land)
- Bot fire is still off (`BotSkill.SILENT`) — flip `SILENT` → `DUMB` in `MapGameplayPort.create` when you're ready
