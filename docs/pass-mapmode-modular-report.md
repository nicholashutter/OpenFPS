# Pass: map mode modular — diagnostic + plan

> Diagnostic + plan for the user's asks:
> 1. collisions work in the map
> 2. gun fires visible projectiles with smoke in the map
> 3. 8 enemies visible (currently 7)
> 4. only enemies deal damage, not the map
> 5. enemies have gun models
> 6. bots do not fire (yet) — we'll re-enable later
> 7. game rules don't depend on the map

---

## 1. What the code looks like today

### 1.1 The two gameplay ports — same shape, different scene sources

`DemoGameplayPort` (the demo) and `MapGameplayPort` (every map) are
near-duplicates. The demo has the rich path (full DemoScene, audio,
network, remote players); the map port has the same per-tic loop but
with less wiring. Both call:

| Step | Demo | Map |
|---|---|---|
| `match.tick(...)` | yes | yes (just landed spawn shield) |
| `match.firePlayerShot(...)` | yes | yes |
| `match.shotsThisTic()` → tracers | yes | yes |
| `effects.publish(renderer)` | yes | yes |
| `setWorldTransform` on bot bodies | yes | yes (after map-driven demo pass) |
| `setWorldTransform` on bot weapons | yes | yes (after map-driven demo pass) |
| `setWorldTransform` on local body | yes | yes (after map-driven demo pass) |

What the map port is **missing** that the demo has:

- A `PhysicsWorld` — the `PlayerController` is built with the
  no-collision 5-arg constructor, so the player walks through walls.
- An `audio` port — the demo has `attachAudio`, the map does not.
  The bots fire but make no sound. (The user didn't ask for this
  explicitly, but it's part of the modularity story.)
- The `matchLive` gate — actually, the map has it; both ports have it
  via the same `I_GameplayPort` API. ✓
- The map port inherits the spawn shield from the engine, ✓

### 1.2 Where physics should come from

`PhysicsWorld` already exists and is fully wired into `PlayerController`.
The 6-arg constructor takes a `PhysicsWorld` and uses it for slideX/slideZ.
What the demo does (in `DemoGameplayPort.create`):
- It builds a `PhysicsWorld` from `DemoScene.solidGeometry()` — a flat
  `float[]` of 16 walls/columns/boxes that the demo scene author wrote
  by hand.

**This is the coupling the user is asking us to break.** A new map
should not have to know about `PhysicsWorld` or hand-author box
tables. The map's level `.ofm` should turn into a `PhysicsWorld`
automatically.

The cleanest source for that is `ModelFormat` — a level is a `ModelFormat`
with submeshes, and every submesh has an `Aabb` (or we add one). Each
submesh becomes one box in the `PhysicsWorld`. The user's "fix
collision death boundaries" complaint will go away once every map's
level is a real physics world.

### 1.3 Where the bot count comes from

`Match.DEFAULT_BOT_COUNT = 7`. The user wants 8.

- The map's `MapSpec.botWaypoints()` is the canonical source. Currently
  every map has **6** waypoints, capped to `min(waypoints, 7) = 6 bots`.
- The map-driven demo pass already does `Math.min(waypointCount, DEFAULT_BOT_COUNT)`.
- The cleanest fix is per-spec `botCount`, with `DEFAULT_BOT_COUNT = 8`
  as a backstop. Most maps will have 8 waypoints; we can add 2 more to
  each spec.

### 1.4 Where the bot gun model comes from

`MapScene.addBotWeapon` is called for every bot and adds
`models.botWeapon()` as a world instance. **The guns are already
there** — `DemoModels.botWeapon()` returns `blaster-p.ofm`. The
problem the user is seeing is probably that the bots aren't positioned
where the player can see them, or the gun model is hidden by the body
because the per-tic publish isn't reaching the bot weapon indices.

Looking at `MapGameplayPort.publishBotPlacements` (which I haven't
read in full yet) — that publishes the body transforms. The
sub-agent's pass added the bot weapon index publish too. So the guns
*are* staged. Whether they are visible at runtime depends on whether
the per-tic publish runs.

The user said "give them their gun models as well" — that suggests
the guns aren't visible. Likely cause: either the bot-weapon instance
index is `NO_INSTANCE` (no gun model was staged), or the publish
isn't reaching them. We'll verify when we implement.

### 1.5 Where the no-fire flag goes

`Bot.wantsToFire(ticIndex, rng, skill)` is what makes a bot pull the
trigger. The user wants bots to NOT fire "for now". Two options:

1. **Don't call `match.tick()` in the map port for the bot-fire path**
   — would break TDM/Dom/CTF scoring.
2. **Use a `BotSkill` that never fires** — e.g., a new `BotSkill.SILENT`
   with `fireChancePermille = 0`. Cleanest, most modular.

The map port already constructs the match with a `BotSkill`. The
choice of skill is currently `BotSkill.DUMB`. We add a new
`BotSkill.SILENT` (or pass `fireChancePermille = 0` directly) and
the bot never fires — but still walks, observes, and turns.

### 1.6 The "no damage from the map" rule

The user said: "The only thing should deal damage for now is just the
enemy players." Two parts:

- **No fall damage from the map.** Currently the player doesn't take
  fall damage (only bots do via the hitscan). So this is already
  correct.
- **No environmental damage** (touching lava, etc.). The game has
  no such thing today. ✓
- **Only enemy hitscan deals damage.** This is already true. Bots
  fire hitscans at the player, the player takes damage. The map has
  no damage sources. ✓

So this requirement is already met by the current rules — but the
user's intuition is "make sure". The fix is to verify and to not
introduce any new damage source.

### 1.7 The visible projectile + smoke

`DemoEffects` already has tracer + smoke. The map port has it
attached (via the rich `MapScene.build(spec, models)` overload).
The map port calls `fx.spawn(eyeX, eyeY, eyeZ, ...)` on player
fire — but the user is reporting it doesn't work. **The reason:**
on the menu-picked map path, the player spawns and IMMEDIATELY gets
shot to death (the bot swarm) before the player can ever pull the
trigger. With the spawn shield, the player now has 2.5s to look
around — but the user might not have tried firing yet, or the
per-tic publish might be broken.

Once we add `BotSkill.SILENT`, the bots won't shoot the player, and
the player can test the gun.

---

## 2. What's coupled, and how to break it

### 2.1 The coupling today

- **PhysicsWorld is hand-authored per scene.** `DemoScene.solidGeometry()`
  is a hand-typed `float[]` of 16 boxes. The map path has no
  physics. Fix: derive `PhysicsWorld` from `ModelFormat` submeshes
  (or `Aabb` per submesh). One helper: `PhysicsWorld.fromModel(level)`.
- **`Match.DEFAULT_BOT_COUNT` is a global cap.** Every map is
  hard-capped to 7. Fix: per-spec `botCount` with `DEFAULT_BOT_COUNT`
  as a backstop default of 8.
- **Bot fire is on by default in the map path.** Fix: new
  `BotSkill.SILENT` (fireChancePermille = 0). The map port picks it.
  The demo stays on `BotSkill.DUMB` so the existing demo behavior
  doesn't change.
- **The per-tic loop is duplicated.** The map port is a fork of the
  demo port. The fork is now big enough that the right fix is a
  shared base class — `AbstractGameplayPort` that holds the per-tic
  loop, the match invocation, the effect publish, the bot placement
  publish, the respawn, the network exchange. Subclasses provide
  the scene-specific bits (which audio port, which model source).
  This is a refactor for the next pass — it's not strictly required
  for the user's asks today, but it's the right end state. The
  current task: extract the two duplicated fire-paths and the two
  duplicated effect-publishes into a single `AbstractGameplayPort`
  base.

### 2.2 The "rules don't depend on the map" rule

The user wants: "game rules don't depend on the map at all". The
game rules I read in the code are:

- Bots fire hitscans at the player.
- Bots follow a patrol route.
- The player has health, respawns after 120 tics.
- Bots have health and die in 3 hits.
- The mode is TDM/HP/Dom/CTF.

None of these rules look at the map. The map provides:
- Spawn placements
- Patrol waypoints
- Mode markers
- Level geometry (for visuals, and now for physics)
- Asset paths

**The rules already don't depend on the map.** The coupling the
user is feeling is that the demo's behavior is welded to the demo's
level (a hand-authored box table, a hand-authored bot roster), and
the map path is a thin clone that forgot some of those pieces
(physics, audio, 8 enemies, no-enemy-fire, gun models).

The fix is to make the map path a **first-class port**, not a
half-clone. The way to do that is to extract the shared per-tic
loop, give the map port its own physics world derived from the
level, give it a SILENT bot skill by default, and bump the bot
count to 8 per spec.

### 2.3 The plan

1. **Add `PhysicsWorld.fromModel(ModelFormat, float bodyHalfWidth)`**
   - Walks every submesh, takes its Aabb, emits one box.
   - One call per map. No more hand-authored box tables.
   - The demo keeps its hand-authored geometry (it's already
     there and works), but a `fromModel` helper makes the map
     path identical.

2. **Add `BotSkill.SILENT`** (fireChancePermille = 0).
   - The map port uses SILENT by default.
   - The demo stays on DUMB.
   - Bots still walk, observe, and turn — they just don't fire.

3. **Per-spec `botCount` in `MapSpec`**
   - Default: `DEFAULT_BOT_COUNT` (raised from 7 to 8).
   - Each map sets it to 8 (or whatever the design calls for).
   - The `MapScene` and `MapGameplayPort` both honor it.
   - Bump `DEFAULT_BOT_COUNT = 7 → 8`.

4. **Wire physics into `MapGameplayPort`**
   - `MapScene` builds a `PhysicsWorld` from the level model
     (`fromModel`), stores it, hands it to the port.
   - `MapGameplayPort.create` uses the 6-arg `PlayerController`
     constructor with that physics world.

5. **Verify the bot-weapon publish**
   - Confirm the per-tic publish reaches the bot weapon indices.
   - Confirm the weapon model is staged (not `NO_INSTANCE`).

6. **(Optional, for next pass) Extract `AbstractGameplayPort`**
   - Holds the per-tic loop, the match call, the effect publish,
     the bot placement publish, the respawn, the network exchange.
   - The demo and map ports extend it and provide the
     scene-specific bits.

7. **Fix collision death boundaries**
   - The user said "fix the collision death boundaries". This is
     presumably the player walking off a ledge and dying. Currently
     the player has no fall damage — `applyJumpAndGravity` just
     clamps `positionY` to `GROUND_LEVEL_UNITS = 0`. So there's
     no fall damage today. **The user might be misreading the
     log** (we already established the deaths are from bot fire,
     not falling). But: if the level has surfaces the player
     should be standing on (e.g., the refinery catwalks at y=64),
     the player currently walks through them because physics is
     missing. **The fix: physics from the model.** Once the player
     collides with the geometry, they'll stand on the catwalks
     instead of falling through them, and the perceived
     "fall-through-floor" disappears.

---

## 3. Implementation order

1. `BotSkill.SILENT` + map port uses it. (5 min)
2. Bump `DEFAULT_BOT_COUNT` to 8. (1 min)
3. Add per-spec `botCount` field on `MapSpec`. (30 min, including
   constructor change + each spec gets an explicit `8`)
4. `PhysicsWorld.fromModel` helper. (30 min)
5. Wire `MapScene` to build a `PhysicsWorld` from the level and
   `MapGameplayPort` to pass it to `PlayerController`. (30 min)
6. Verify bot-weapon publish by reading the per-tic flow. (15 min)
7. Tests for each of the above. (1 hour)
8. Full test run + checkstyle. (15 min)
9. Smoke test on foundry, then the user runs the desktop launcher
   and we iterate on visual issues.

This is a 4-5 hour block of work, split across the same shape as
the spawn-shield pass: do the changes, run the build, smoke test,
report.

---

## 4. What we'll know we got it right

The user's success criteria, restated:

- **Collisions work**: player can stand on a catwalk, can walk
  into a wall and stop, slides along walls.
- **Gun fires**: pulling the trigger throws a tracer and a puff
  of smoke, every time.
- **8 enemies visible**: the player sees 8 character models in
  the level.
- **Only enemies deal damage**: with `BotSkill.SILENT`, the bots
  don't fire. The player takes 0 damage. (We'll re-enable enemy
  fire in a later pass — for now, the user is right that the
  fire system needs to be re-thought.)
- **Enemies have gun models**: the bot bodies have weapons in
  their hands.
- **Don't die immediately**: the spawn shield + the lack of
  enemy fire + the physics holding the player on the ground
  means the player has time to look around.

Once the user is happy with this state, we re-enable the bot
fire (and the bot gun VFX, which is `DemoEffects.spawnIncoming`).
