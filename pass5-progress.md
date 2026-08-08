# Pass 5 progress log

## Setup findings
- 7 maps currently shipped (4 Urban Warzone + 3 TDM in other settings)
- Mode logics all implemented (HP/Dom/CTF 13+13+16 tests)
- Kenney atlas staged at `assets/gltf/level/Textures/colormap.png` (8706 bytes, 512×512 swatch atlas)
- `MapSpec` and `MapMarkers` already exist with all four marker subtypes
- `KenneyTexture` class lives in `tools/.../model/` and exposes `floor()`, `wall()`, `crate()`, `column()`, `accent()`, `accentRed()`, `accentOrange()`, `forceOpaque()` helpers
- Asset path inconsistency found: refinery/crossroads/arctic-station specs point to `assets/maps/<id>/level.ofm` but the actual .ofm is at `engine/src/main/resources/maps/<id>/level.ofm`. Will fix as a Pass 5 drift correction.
- Pre-existing corner-and-trim pieces in `OverpassMapBuilder` use the `kenneyAccent` swatch (kenney row 1 col 0) - but I noticed in this build it does NOT actually use KenneyTexture for accent. It uses procedural `accentTexels()`. So the "accent" submesh in Overpass is procedural red. Will mirror that for new builders.

## Map id convention
- Spec docs use `hp-foundry`, `hp-mesa`, `hp-arctic`
- User's instructions say use `foundry`, `mesa`, `arctic-hp` (the un-prefixed form)
- Subzero is the display name for arctic-hp

## Order of work
1. Add `--atlas` to Refinery/Crossroads/ArcticStation builders
2. Migrate Maps.java asset paths for the 3 TDM maps to the new form
3. Add 3 new HP factories to Maps.java (`foundry`, `mesa`, `arcticHp`)
4. Register the 3 new maps in MapLibrary
5. Build the 3 new HP .ofms with the existing build task pattern
6. Add MapLibraryTest entries for the 3 new maps
7. Update docs (specs, README, AGENTS, PLAN, etc.)
8. Final verification (engine:test, checkstyle, 6 smoke tests)
