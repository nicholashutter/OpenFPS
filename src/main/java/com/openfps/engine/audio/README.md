# Audio (S_) — Sound and Music

> S_ is the sound engine. Plays 3D-positioned SFX and background music.

## What lives here (planned)

- `SoundEngine` — main entry, processes a queue of sound events per tic
- `SoundEmitter` — 3D-positioned source (openAL/OpenSL source equivalent)
- `SoundChannel` — one playing sound (loudness curve, position, looping)
- `MusicPlayer` — background music player (MIDI or streamed audio)

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

For DOOM-style mixers, the practical limit is **8 simultaneous voices** on
the original SoundBlaster. Modern hardware handles hundreds. We target 32
active voices; above that, lowest-priority voices get culled.

Priority is computed as:
```
priority = (distance_penalty) * (volume_boost) * (recency_penalty)
```

Distance penalty: louder = higher priority. Recency: recently-triggered sounds
stay slightly prioritized so they don't get culled mid-playback.

## Audio file formats

- **SFX**: raw 8-bit signed PCM, 11025 Hz, mono. Stored as WAD lumps
  named `DS*` (e.g. `DSPISTOL`, `DSPLDIE`).
- **Music**: original DOOM used MIDI. Modern engines often use OGG or
  raw WAV. We default to OGG for music, raw PCM for SFX.

**Source — DOOM sound format details**:
https://doom.fandom.com/wiki/Sound

## Performance constraints

- **8 voices** (lowest priority culling), expandable to 32.
- **No locks in the audio thread.** All parameter updates are atomic.
- **Pre-allocated buffers** for every sound, no runtime allocation.

## Files

- `port/I_AudioPort.java`
- `adapter/NullAudioPort.java`

## TODO (Phase 6)

- `SoundEngine` — voice allocation, mix loop
- `SoundEmitter` — 3D position + velocity
- `MusicPlayer` — OGG/MIDI streaming
- `PcmLoader` — read SFX from WAD lumps
