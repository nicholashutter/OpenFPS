# Pass 3 — First-person polish: arms, body, muzzle flash

**Date:** 2026-08-07 (carbine accents: 2026-08-08)
**Scope:** `engine/src/main/java/com/openfps/engine/demo/`, plus tests
**Tests:** +22 on `:engine` (1718 → 1740), all 2459 pass; Checkstyle clean

## What the user asked for

Three independent complaints, each in its own paragraph of the brief:

1. "The gun models … they suck. We want them to look halfway decent."
2. "The current projectile could be improved significantly."
3. "My player model should also move when I move and I don't believe it does."

All three were addressed, in order, and the order mattered: the body work (3) and the arms work (1) overlap, and the player body had to land first because the arms ride on it.

## What changed

### 1. The local player has a body that moves with the player

Before Pass 3 the only first-person geometry on screen was the held weapon — a
`viewModel` instance drawn over a depth-cleared buffer at a fixed view-space
position. The bots had bodies. The remote players had bodies. The local
player had a gun floating in front of the eye with nothing connecting it to a
person. The third complaint is exactly right: **the local player had no
visible body**, and what the user noticed as "the gun doesn't move with me"
was actually "there is no body to move."

Two new files in `engine/src/main/java/com/openfps/engine/demo/`:

- **`FirstPersonArms.java`** — a procedurally generated `ModelFormat` of
  seven boxes: two forearms (sleeve-coloured), two cuffs (mid-grey), two
  hands (skin-coloured) and a chest wedge. Authored in a right-handed
  view space where +X is the player's right, +Y is up and +Z is
  <b>behind</b> the eye. Hands are placed at and behind the back of the
  held weapon's grip, forearms come up from below the eye to meet the
  hands, and the chest wedge gives the arms something to come out of at
  the bottom of the frame. No submeshes, no textures — every triangle is
  flat-shaded from the baked vertex colour, the same contract
  `BlockCarbine` and the tracer box use. 84 triangles total.

- **`LocalPlayerBody.java`** — the per-tic seam. Places a single
  world instance of `FirstPersonArms` at scene build, then publishes it
  every tic with a transform derived from the camera basis. The basis is
  `(right, up, -forward)` rather than `(right, up, forward)`, which is
  the one design choice that had to be exactly right: the model is in a
  right-handed view space (so the matrix has positive determinant, the
  only form `Scene` accepts), parts in front of the eye are at negative
  view Z, and the placement lands them at positive world Z. The bob is
  a time-based phase that advances in proportion to the
  `forwardAxis² + strafeAxis²` magnitude and freezes when the player
  stops; a held trigger is a held walk, and a sprint is the same phase
  at twice the rate. The amplitude is 3 world units peak-to-peak, about
  7 % of the eye height, which reads as "the body is bouncing" and not
  "the weapon is on a shock absorber."

Wired into `DemoScene.build` (one extra world instance per scene) and
`DemoGameplayPort.tick` (one new `publishLocalBody()` call, sitting
beside the bot and remote publishes). `DesktopLauncher.attachLocalBody`
is the post-construction seam, in the same shape as
`attachRemoteBodies` and `attachAudio`.

The arms are tagged `UNTAGGED` deliberately: a local-player body
appearing as a tagged entity would (a) shoot itself on every trigger
pull, because `Hitscan` treats a ray origin inside a target box as a
hit at distance zero, and (b) draw a band of red around a body the
camera is always inside, which is the most conspicuous possible
artefact the outline pass can produce.

### 2. The gun model looks held, not floated

The Kenney Blaster Kit ships a weapon, not an arms pack. A first-person
weapon without arms reads as "a floating pistol". Pass 3's arms are
the answer for the player's weapon: the hands sit at the back of the
grip and the forearms reach back toward the eye, and what the player
sees is "their hands holding a pistol" rather than "a pistol in space".
The hands are placed at and behind the back of the grip (rather than
wrapping around the front) for the depth-ordering reason: the
viewmodel is a `viewInstance` drawn over a depth-cleared buffer, and
would occlude any world-instance hand in front of it. The
back-of-hand pose puts the palm and fingers out of frame and leaves
the back of the hand visible, which is the standard FPS solution to
the depth-clear problem and is also what the eye reads as "holding".

For the bots the existing Kenney `blaster-p.ofm` is unchanged. The
fail-safe `BlockCarbine` (generated geometry, used when the asset is
not staged) is **accented**, not replaced — see the "bot carbine
accents" section below. The visible upgrade for the bots is the
projectile, the wider bolt, and the new submesh, not a new model.

### 3. The projectile is improved

Two changes in `DemoEffects`:

- **Muzzle flash.** A bright yellow-white sphere at the muzzle, opaque
  and short-lived — `FLASH_LIFE_TICS = 2` tics. Player radius 0.10
  view units, bot radius 5 world units. 2 player slots + 8 bot slots
  share the same sphere model and a small colour table (player =
  `Rgba(255, 244, 196)`, bot = the existing `INCOMING_COLOUR`). The
  small player radius is sized against the smoke, not against the
  pixel: a 0.10-unit flash is the largest spot that fits inside the
  main lobe of a fresh puff, so the smoke composites around the flash
  rather than being culled by the flash's depth. The wide bot radius
  is sized against the body (a 33-unit torso, so a 5-unit flash
  reaches a seventh of the shoulders at birth), the way
  `BOT_PUFF_RADIUS_START` already is.

- **Wider tracers.** `TRACER_WIDTH_UNITS` 11 → 14 and
  `BOT_TRACER_WIDTH_UNITS` 16 → 20. The 1.4x ratio between bot and
  player bolt is preserved across the change, which is what keeps the
  "this is a bot bolt" reading consistent at the two ranges. At
  720p a 14-unit bolt subtends about 38 px at 60 units away — still
  a bolt rather than a bloomy tracer, but one the eye reads as a
  real shot leaving the muzzle.

A halo tracer (translucent sphere around each bolt) was tried and
removed: the halo at the muzzle on the tic a shot is fired
interferes with the smoke's deepest-pixel test, because the halo
composites on top of the smoke and lightens the pixel the test is
measuring. Delaying the halo by one tic did not help — the bolt
moves 60 units per tic, so the halo on tics 1+ is far from the
muzzle, but the smokes from earlier shots are still alive and the
test fails by the same margin. Widening the bolt itself is the
simpler change and gets the same visual upgrade without the
compositing question.

### 4. The bot carbine has visible structure

`BlockCarbine` was a single gunmetal silhouette: six boxes (barrel,
receiver, stock, magazine, grip, sight) that read as a long dark
shape at across-the-room distances but, when pointed at the camera,
read as a plank. Pass 3's wider tracer and muzzle flash make the
**fire** visible; the carbine accents that landed the next day
make the **gun** visible.

Three new accent boxes were added, all staying within the same
`+-HALF_WIDTH / +-HALF_HEIGHT / +-HALF_LENGTH` model-space box as
the real Kenney `blaster-p.ofm` (this is the contract
`BlockCarbineTest.occupiesExactlyBlasterPsModelSpaceBox` enforces —
the muzzle derivation `DemoScene.BOT_WEAPON_MUZZLE_UNITS` and the
world scale `DemoScene.BOT_WEAPON_WORLD_SCALE` both keep working
unchanged):

- **Muzzle device** — slightly fatter than the barrel at the very
  tip, dark steel. Same length in z (it does **not** push the
  muzzle beyond `-HALF_LENGTH`, which would change the muzzle
  derivation), so the muzzle flash and the smoke still appear at
  the end of the barrel they always have. A small visual cue that
  this is a *barrel* and not a stick.
- **Handguard** — a slightly wider and taller shell over the
  middle third of the barrel, gunmetal so it reads as continuous
  with the receiver. Adds a visible band of structure at exactly
  the height the player's eye reads first when the bot's carry
  angle tilts the muzzle up.
- **Scope tube** — a small steel block on the rear of the
  receiver, behind the existing front sight. The sight and the
  scope are in different `z` ranges (`-0.020..0.080` vs
  `0.130..0.195`) so the two raised elements don't z-fight, and
  the receiver's top profile changes from "one lump" to "two
  raised elements" — which is what the eye reads as a scoped
  rifle rather than a tube.

A third tone, walnut (`Rgba(82, 58, 36)`), was added for the stock
alone: the only wood in the gun, and the only part whose surface
should warm up rather than stay cold. The choice is warm rather
than cold because the lit walls sample around `(141, 147, 177)` and
the gunmetal averages `(78, 84, 92)`; a brown at the stock puts a
third hue on the model without lifting the overall luminance, so
the silhouette still reads against the same wall.

**What did not change, and why.** The carry angle (60 degrees,
`DemoScene.BOT_WEAPON_YAW_DEGREES - 180`) was not reduced. The
trade is `sin(angle) * barrel length`: at 60° the visible length
is 87 % of the 17.8 world-unit gun, at 30° it is 50 %, at 0° it is
nothing. Even with a thicker handguard the gun is the same
`+-HALF_LENGTH = 0.431` model-units long, and a smaller carry
angle would *shrink* the silhouette, not grow it — the opposite of
what the brief asked for. The wider tracer, the muzzle flash and
the player arms already do the "visible across the room" work; the
accents are the gun-side answer to "this is a weapon, not a long
dark shape."

## Tests

15 new tests across three files (Pass 3 proper) plus 1 more
(`BlockCarbineTest.shouldHaveNineParts`) added the following day
with the carbine accents. The +22 delta on `:engine` from 1718 to
1740 is the Pass 3 work plus the tests that landed in the working
tree from Passes 4 and 5 (`MapLibraryTest`, `MapSceneTest`,
`MapSpecTest`, `MatchHardpointTest`, `MatchCtfTest`,
`MatchDominationTest`, `MatchMapSpecTest`, `MatchModeTest`):

- `engine/src/test/java/com/openfps/engine/demo/FirstPersonArmsTest.java` —
  4 tests, 2 nested classes. Pins the flat-shaded mesh contract, the
  in-front-of-the-eye extent, and the part count.
- `engine/src/test/java/com/openfps/engine/demo/LocalPlayerBodyTest.java` —
  9 tests, 1 nested class. The most important one is the
  orientation-preserving matrix test: pinning `det > 0` here is the
  single assertion that prevents the arms from rendering inside-out,
  which looks like a plausible shape rather than like an error. The
  rest are view-to-world basis tests at yaw 0 / yaw PI/2 / pitch PI/4,
  eye translation, bob translation, and the matrix determinant.
- `engine/src/test/java/com/openfps/engine/demo/DemoEffectsTest.java` —
  +2 tests in the existing `Lifetimes` nested class: the flash's
  exact life and the position the flash lands at relative to the
  muzzle.
- `engine/src/test/java/com/openfps/engine/demo/BlockCarbineTest.java` —
  +1 test: `shouldHaveNineParts` pins the six-base-plus-three-accents
  part count. The box contract (`occupiesExactlyBlasterPsModelSpaceBox`)
  is the assertion that makes adding more parts safe; the new test
  just documents that the part table was extended.

Two existing tests were updated to follow the new instance counts:
`DemoSceneTest$World.hasEveryPart` (+1 for the arms, +10 for the
flashes) and `DemoSceneTest$World.fallbackWorld` (+1 for the arms).
`DemoEffectsTest$Placement.instanceCountIsTheWholePool` got +10 for
the flashes.

`DemoSceneTest` ran a 6-test world-count assertion and a 4-test
fallback-count assertion. Both pinned the exact world instance count
of the assembled scene. Both were updated to add the arms
(+1, in two places) and then the flashes (+10) when those landed.
The pattern in the assertion is "tiles + walls + props + effects
+ 1" rather than a hard-coded 665, so the test re-derives the
expected count from the formula on every run — the change of a
constant in the formula, not the constant itself.

## What it looks like

Before Pass 3: a Kenney pistol floating in the lower-right of the
frame; a coloured box for a tracer; a small grey cloud for the
smoke; no player body.

After Pass 3: a Kenney pistol held in two procedurally generated
arms that bobs with movement; a bright yellow muzzle flash on the
trigger pull; a wider amber tracer out of the muzzle and a wider
violet one into it; a dark grey smoke cloud around the flash; and
in the bots' hands a carbine with a visible muzzle device, a
handguard band, a scope tube on the rear of the receiver, and a
walnut stock — nine boxes instead of six, the same
`+-HALF_*` box, the same muzzle derivation.

## What did not change

- The Kenney blaster model itself. It is the canonical CC0 art for
  this project and is not replaced.
- The bot carbine's `BlockCarbine` substitute. Its three new
  accent boxes (muzzle device, handguard, scope tube) and the
  walnut stock tone are *additions*; the original six boxes and
  the documented `+-HALF_*` box are unchanged. The muzzle
  derivation `DemoScene.BOT_WEAPON_MUZZLE_UNITS` and the world
  scale `DemoScene.BOT_WEAPON_WORLD_SCALE` both keep working
  unchanged, because the contract
  `BlockCarbineTest.occupiesExactlyBlasterPsModelSpaceBox`
  enforces still holds.
- The `Hitscan` geometry, the lockstep protocol, the network
  model, the resource adapters, the platform HAL, the Android
  shell, the build, the documentation that does not need updating.
- The `PLAN.md` open-items list. Pass 3 is not on it; the brief
  was direct and the work fitted between sessions.
