# Audio (S_) — Sound and Music

> S_ is the sound engine. Plays 3D-positioned SFX and background music.

## What lives here (planned)

- `SoundEngine` — main entry, processes a queue of sound events per tic
- `SoundEmitter` — 3D-positioned source (openAL/OpenSL source equivalent)
- `SoundChannel` — one playing sound (loudness curve, position, looping)
- `MusicPlayer` — background music player (MIDI or streamed audio)

## What exists today

Two files and no implementation — but the subsystem is **wired, not absent**.
`core/subsystem/impl/AudioSubsystem.java` registers under `SubsystemId.S_` and
forwards `onInit` / `onShutdown` to whichever `I_AudioPort` it was given, which
in practice is `NullAudioPort`. It handles **no event types at all**: there is
no `PlaySfxEvent`, and `onEvent` is not overridden. So S_ starts and stops with
the engine and does nothing in between, by design, until Phase 6.

That matters when reading the rest of this file: every formula below is
specification written ahead of the code. **This package has 0 tests**, and that
is honest rather than an oversight — there is nothing here yet to assert.

## Subsystem layout

```
audio/
├── port/
│   └── I_AudioPort.java   interface — called by core per tic
└── adapter/
    └── NullAudioPort.java stub
```

## 3D audio math — what's coming

### Inverse-square distance falloff

Sound loudness falls off as `1 / r²` (r = distance from source to listener).
To prevent loudness from blowing up at r=0, we clamp the minimum distance:

```
loudness = 1 / max(MIN_DISTANCE, distance²) * MAX_LOUDNESS
```

For practical purposes, game audio often uses a **linear** falloff between
MIN_DISTANCE and MAX_DISTANCE, and 1/r² outside that range. The transition
point is a tuning knob.

**Source — OpenAL 1.1 specification, section 5.2 ("Distance Attenuation")**:
https://www.openal.org/documentation/openal-1.1-specification.pdf

**Source — "Game Audio Programming" — Guy Somberg**:
https://gameaudioprogramming.com/

### Stereo panning

Panning (left/right balance) is computed from the angle between the source-to-listener
vector and the listener's forward direction:

```
dot = sourceDir · listenerForward
angle = acos(dot)            // 0 = directly in front, π = directly behind
pan = sin(angle)             // [-1, 1], 0 = centered
```

The sign of the cross product determines left vs. right.

**Source — "3D Audio for Games" — Dyon Dutil, DSP Dimension**:
https://www.dspdimension.com/

### Pitch and Doppler

The Doppler effect for moving sources:

```
f_observed = f_source * (c + v_listener) / (c + v_source)
```

Where `c` is the speed of sound (~343 m/s). For game purposes we typically
clamp the observed frequency to `[0.5, 2.0] × f_source` to avoid extreme
pitches.

**Source — "Doppler Effect" — Wikipedia**:
https://en.wikipedia.org/wiki/Doppler_effect

**Source — "Real-time 3D Audio for Interactive Games" — IRCAM, 2019**:
https://forum.ircam.fr/

### Mix bus limit

**The target is 32 simultaneous voices**, with lowest-priority voices culled
above that.

The 8 that appears in DOOM-derived writing is the original SoundBlaster's
practical limit — a 1993 hardware constraint, not one we inherit. Modern
hardware handles hundreds, and a software mixer's cost here is a per-sample
add per voice, so 32 is chosen as the number a mix loop can carry without
thinking about it rather than as a measured ceiling. **Nothing is implemented,
so treat 32 as an intent; there is no constant to read and no benchmark behind
it.** If it ever turns out to be wrong it will be wrong in the cheap direction.

Priority is computed as:
```
priority = (distance_penalty) * (volume_boost) * (recency_penalty)
```

Distance penalty: louder = higher priority. Recency: recently-triggered sounds
stay slightly prioritized so they don't get culled mid-playback.

## Audio file formats — OPEN, deliberately

**This is not decided, and the previous version of this document decided it by
accident.** It described SFX as raw 8-bit PCM in WAD lumps named `DS*`
(`DSPISTOL`, `DSPLDIE`) and a `PcmLoader` that reads them. That cannot be right
as written:

- `docs/ASSETS.md` § 8 trap 7 is **"No IWADs, ever."** The project ships no id
  Software content, so there are no `DS*` lumps to read.
- The WAD subsystem itself has no art left to read at all — see
  `render/README.md` § 11(b), where its remaining role is explicitly left to the
  user. Asserting that audio arrives through it would be settling that question
  from the wrong document.
- `docs/ASSETS.md` § 3's accepted-sources table covers models and textures only.
  It has no row for audio, and its per-file-licensing note about Freesound is
  the only place sound is mentioned at all.

So this is the **same family of question as the resource-package one**, and it
gets the same treatment: recorded, not answered. Three things have to be settled
together, and none of them is:

1. **Where sound comes from** — which CC0 sources satisfy `docs/ASSETS.md` § 3,
   given that its accepted list was written for art.
2. **What the shipped format is** — the project's pattern (`docs/ASSETS.md` § 4)
   is to preprocess at build time into a flat binary the engine reads with
   near-zero parsing. Audio has no equivalent of `ModelFormat` yet, and whether
   it needs one or can just ship decoded PCM is unanswered.
3. **What container carries it** — the resource package, a zip payload, or loose
   files. Blocked on § 11(b).

Decide these before writing `PcmLoader`, and record the decision here and in
`docs/ASSETS.md` rather than in code.

**Music** is not decided either. Original DOOM used MIDI; OGG is the obvious
modern choice and would mean a decoder the project does not have and cannot add
casually (`AGENTS.md` rules out new runtime dependencies). Streaming raw PCM
costs disk instead. No preference is recorded.

**Source — DOOM sound format details**, retained as background on the 1993
format only, not as a statement of what this engine reads:
https://doom.fandom.com/wiki/Sound

## Performance constraints

- **32 voices** target, with lowest-priority culling above it (see the mix bus
  section — this is an intent, not a measurement).
- **No locks in the audio thread.** All parameter updates are atomic.
- **Pre-allocated buffers** for every sound, no runtime allocation. The mixing
  buffers are `float[]`, allocated once at init, which is the shape
  `STYLE.md` § 13.4 sanctions for direct allocation — they will not go through
  `I_MemoryPort`. They are not a named site yet because they do not exist yet;
  add them to that table when they do.

## Files

- `port/I_AudioPort.java`
- `adapter/NullAudioPort.java`

**0 tests.** Nothing here is implemented; see "What exists today".

## TODO (Phase 6)

- **Settle the source-format question above** — it blocks the loader, and
  partly blocks itself on `render/README.md` § 11(b)
- `SoundEngine` — voice allocation, mix loop
- `SoundEmitter` — 3D position + velocity
- `MusicPlayer` — streaming, format undecided
- `PcmLoader` — decode SFX, once there is a decided format for it to read
- Event types for S_ — `AudioSubsystem` handles none, so nothing can reach the
  port between init and shutdown
