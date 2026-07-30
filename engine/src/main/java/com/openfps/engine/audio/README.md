# Audio (S_) — Sound

> The engine says "make this noise". Everything else — device, format, file,
> mixer — is behind the port.

## Status

| Field | Value |
|---|---|
| **State** | SHIPPING (five sounds) |
| **Phase** | `PLAN.md` Phase 6 — started, deliberately narrow |
| **Registered** | S_ via `AudioSubsystem`, port supplied by `I_AdapterFactory.getAudioPort()` |
| **Verified** | 2026-07-30 |

**Built.** `I_AudioPort` and `SoundId`; `AudioVolume` (the clamp); `BlasterSound`
(the player's weapon), `CarbineSound` (a bot's), `SuperBlasterSound` (the player's
weapon while a kill streak is being spent), `PowerChimeSound` (that reward
arriving, and the same chime backwards when it goes), `SoundBank` (which id is
which) and `WavAudio` (the container); `NullAudioPort` (silent, recording);
`GdxAudioPort` in `:gdxshared` (real, lazy, degrades). The player's weapon fires
audibly from `DemoGameplayPort.fireIfRequested` and return fire from
`spawnIncomingFire`, on desktop and Android.

**The three reward sounds are the prediction above coming true.** The next step
used to read "a hit confirmation would now cost one enum value, one synth class
and one branch". Three sounds landed instead and cost exactly that each — two
synth classes, three enum values, three branches in `SoundBank`, **no adapter
change at all**. That is the seam working.

They also set two rules the next sound should read first:

- **A sound may be required to be the SAME as another one.** `CarbineSound` exists
  to be told apart from the player's weapon and argues at length that the two must
  therefore sit in different families. `SuperBlasterSound` has the opposite
  requirement — the player has to hear their own weapon upgraded rather than
  replaced — so it is built *out of* `BlasterSound`'s sweep, with its endpoints
  derived from that class's constants rather than written down again.
- **Two ids may share one synth class when they are one sound in two
  directions.** `PowerChimeSound` generates the award and the expiry from one
  generator with its two notes swapped, because rising against falling is the
  whole message and two classes would have let them drift into being merely
  different noises. `SoundBankTest` still holds: they are different bytes and
  different file names.

**Voice limiting** exists for return fire only, in `demo/BotFireVoices`: one
voice per tic and a six-tic minimum interval. It is not in this package and not
behind the port, deliberately — see § "Why the voice limit is not in here".

**Not built.** Positional audio, music, a mixer, `SoundEngine`, `SoundEmitter`,
`MusicPlayer`, `PcmLoader`. None of it exists and none of it is half-started.
See "What this is not".

**Blocked on.** Nothing. The source-format question that used to block this
package is settled *for the demo* by generating the sounds (§ "Where the sound
comes from"); it remains genuinely open for a real sound bank, and that is
recorded rather than pretended away.

**What the second sound found.** The next step used to read: "one sound cannot
tell you whether the port's shape is right." It was worth doing, and the answer
is that `I_AudioPort` and `SoundId` were both fine and **the seam between the
synthesis and the adapter did not exist**. `GdxAudioPort.stage` named
`BlasterSound` outright, so a second id staged, loaded, warmed and played — as
the player's own weapon. Green build, working audio, and the only symptom
audible rather than assertable. `SoundBank` is that missing seam: the engine
answers *what a sound is*, an adapter answers *how this platform plays bytes*,
and adding a third sound now touches no adapter at all. `SoundBankTest` asserts
over `SoundId.values()` that no two ids share a buffer or a file name, which is
the property whose absence made the bug invisible.

**Next step.** Nothing is queued. A hit confirmation is the obvious next sound,
and the reward sounds have just demonstrated the price: one enum value, one synth
class, one branch in `SoundBank`, and nothing outside `:engine`.

## What lives here

```
audio/
├── port/
│   ├── I_AudioPort.java    the contract: init, shutdown, play, stopAll,
│   │                       setMasterVolume, masterVolume, isAudible
│   └── SoundId.java        the closed set of sounds — five, today
├── synth/
│   ├── BlasterSound.java   the player's weapon: a pitch sweep
│   ├── CarbineSound.java   a bot's weapon: a noise crack, and NOT a sweep
│   ├── SuperBlasterSound.java  the player's weapon on a kill streak: the SAME
│   │                       sweep with an octave of weight under it
│   ├── PowerChimeSound.java    two notes: rising when the reward lands,
│   │                       falling when it goes. One class, one message
│   ├── SoundBank.java      which SoundId is which sound, as playable bytes
│   └── WavAudio.java       wraps PCM in a RIFF/WAVE container
├── AudioVolume.java        the one definition of a legal volume
└── adapter/
    └── NullAudioPort.java  silent, and counts what it was asked to play
```

`SoundBank` is the seam an adapter goes through, and the only one. An adapter
must name no synthesis class: see § "What the second sound found" above for the
bug that rule was written from.

## Why the voice limit is not in here

Because it is a rule about **this game's opponents**, not about sound. The port's
contract is explicit that overlapping plays overlap in the mix — "firing again
before the last shot has decayed must not cut it off. That is what a weapon
sounds like" — and both shipped backends do exactly that. A port that silently
dropped plays would be one nobody could reason about.

But seven bots roll for the trigger independently every tic their weapon is
ready, and two things follow that a single shooter cannot do. Several can fire on
one tic, and **copies of one generated buffer started on the same tic are the
same waveform added to itself** — identical samples, perfectly in phase — so
seven of them is one shot at seven times the amplitude, which clips. And the
long-run rate is bounded by nothing audible, so a burst becomes texture rather
than a warning.

`demo/BotFireVoices` owns both numbers, and both are derived:

- **One voice per tic.** The second copy is not a smaller contribution, it is a
  doubling; there is no second shot in there to hear.
- **Six tics minimum**, so ten a second. `CarbineSound.DURATION_MS` is 120 ms,
  which at 60 Hz is 7.2 tics, so six admits at most `ceil(7.2 / 6) = 2`
  overlapping voices — enough that "more than one of them is shooting" is
  audible, and two at `PEAK` 0.45 reach 0.90, inside full scale. The measured
  room fires once every 18 tics between the seven of them, so the gate only ever
  closes on a burst; a limiter that shaped the *average* would be changing the
  demo's balance from the audio layer.

The gate is a pure function of the tic index. No wall clock, for the reason
`BotRng` gives — a gate that opened on different tics on two peers would be a
divergence in the one layer nobody would look in.

The real backend is **not here**, and cannot be: it needs `Gdx.audio`, and
`:engine` imports no libGDX. `GdxAudioPort` lives in `:gdxshared` and is wired in
by `GdxAdapterFactory` (`:desktop`) and `AndroidAdapterFactory` (`:android`).
Same rule, and the same reason, as the window and input ports —
`hal/README.md` § "Where the window and input actually live".

## The port is six methods, and that is the design

```java
void init();
void shutdown();
void play(SoundId sound);
void stopAll();
void setMasterVolume(float volume);
float masterVolume();
boolean isAudible();
```

**What it replaced.** Until 2026-07-29 this interface was
`playSfx(int soundId, int x, int y, int z)` and `playMusic(String lumpName)`,
under eighty lines of inverse-square attenuation, panning and Doppler formulae —
none of it implemented. Both signatures were wrong in ways that would have had to
be undone before anything could use them:

- **The position arguments presumed a mixer that does not exist.** Neither
  libGDX `Sound` nor Android `SoundPool` takes a world position; they take a
  volume and a pan. Every adapter would therefore have carried its own copy of a
  listener transform — duplicated, untested arithmetic in exactly the place a
  port exists to prevent it.
- **`playMusic(String lumpName)` named a WAD lump.** `docs/ASSETS.md` § 8 is
  "No IWADs, ever". A parameter named after a container the project has ruled
  out is worse than no method at all.

The formulae are not deleted, they are relocated: see "The 3D audio that is not
here" below, which is now honestly labelled as a future extension rather than as
a specification the code is failing to meet.

**The contract every implementation owes** is on `I_AudioPort` itself and is
worth repeating in one line: *nothing throws, `play` is callable from any thread,
`play` never blocks, volume is clamped rather than rejected, and `init` /
`shutdown` are idempotent.* CI has no sound card, and a game that dies because
the speakers are missing is worse than a silent one.

## Where the sound comes from — the licence question, answered

**Nothing is downloaded, nothing is committed, nothing is staged.** The blaster
is arithmetic: a 900 Hz → 120 Hz exponential pitch sweep with a fast exponential
decay, 180 ms of 22.05 kHz mono, generated by `BlasterSound` into a `short[]` and
wrapped by `WavAudio` into a 8 KB WAVE file in a cache directory the first time a
shot is fired.

That is a deliberate answer to the question this README used to be blocked on.
The three things that had to be settled together —

1. **which CC0 sources satisfy `docs/ASSETS.md` § 3**, whose accepted-sources
   table was written for art and has no audio row;
2. **what the shipped format is**, given the project's preprocess-to-flat-binary
   pattern (§ 4) and audio's lack of a `ModelFormat` equivalent;
3. **what container carries it**, which is partly blocked on
   `render/README.md` § 11(b)

— are the right questions for a *sound bank*, and a blaster noise is not one.
Generating it removes all three: no third-party content, so nothing to add to
`NOTICE` beyond a statement that there is nothing to add; no binary in git, so
the "ships no game data in source form" rule is untouched; no staging step, so a
fresh clone with no `assets/` directory still makes a noise.

**The three questions stay open** for the day a real sound bank arrives. Nothing
above pre-empts them — it declines to answer them, which is different.

**Why a file at all, having generated the samples.** Because the backends will
not take samples: libGDX's `Audio.newSound` accepts a `FileHandle` and nothing
else. The one API that does take raw PCM, `newAudioDevice`, blocks the calling
thread for the duration of the sound — and the caller is the game loop thread, so
a 180 ms block would cost eleven tics per shot. A 44-byte header and a temp file
is the cheap way out. Uncompressed PCM rather than OGG because a decoder is a
third-party dependency (`AGENTS.md`) and the payload is 8 KB.

## Two pieces of arithmetic worth knowing about

Both are in `BlasterSound`, both are tested, and both are the kind of thing that
sounds like a broken synthesiser rather than like a mistake.

**The sweep integrates frequency; it does not substitute it.** The intuitive line
is `sin(2π · f(t) · t)`, and it is wrong: instantaneous frequency is the
*derivative* of phase, so a time-varying `f` inside that expression makes the
actual frequency `f(t) + t·f'(t)`. For a falling sweep the second term is
negative and large, and the pitch dives past zero and runs backwards. The fix is
to accumulate — `phase += 2π·f(i)/rate` per sample.

**Both ends of the envelope are forced to zero.** A waveform that starts or stops
mid-cycle is a step discontinuity; a step contains every frequency and is heard
as a click on top of the sound. The 2 ms attack and 8 ms release ramps exist for
that and nothing else — the decay alone leaves the sound at ~8% of peak at
180 ms, which is a very audible cut.

And one in `AudioVolume`: **NaN is checked before the range, not folded into
it.** `Math.max(0, Math.min(1, x))` propagates NaN, because NaN fails every
comparison. A NaN gain silences the source on OpenAL and has been observed to
play at full scale on `SoundPool` — the same "clamped" value inaudible on one
platform and deafening on the other.

## Lifecycle — who calls what, and when

`AudioSubsystem` owns `init()` and `shutdown()`, and **no adapter factory calls
either**. That is stated on `I_AdapterFactory.getAudioPort()` and matters because
the input port already taught the lesson: initialising in both places runs both
twice, which is harmless (the contract makes them idempotent) and shows up as a
duplicated line in a log, which is how you find out something is happening twice.

**Everything in `GdxAudioPort` is lazy, and that is not an optimisation.**
`Gdx.audio` is null when `init()` runs. On desktop the libGDX application does
not exist until `GdxWindowPort.runFrameLoop` constructs `Lwjgl3Application`, and
that is long after `EngineMain.start` has brought the subsystems up; on Android
the equivalent is `initialize()`, called after the engine starts. An
implementation that loaded its sounds in `init()` would fail on every run,
permanently and silently, and would look like a broken sound file rather than a
broken ordering. So the sound is baked on **first play** — by which time a shot
has been fired, which means a window exists, which means a device does.

A failed bake is **not latched**: "no device yet" is a temporary condition, and
disabling audio forever over it would be the same permanent-silent-failure bug in
a different place. The warning is logged once so a genuinely broken setup does
not scroll the console.

## The path a shot takes

```
InputState.fire()                       the trigger, latched once per tic
  DemoGameplayPort.fireIfRequested      guards, then the cooldown
    audio.play(SoundId.WEAPON_FIRE)     ← the port, never a libGDX type
      GdxAudioPort.play                 volume applied, non-blocking
        bake()  (first time only)       generate PCM → WAV → temp file → Sound
        Sound.play(volume)              OpenAL / SoundPool
```

Three properties of where that `play` call sits, all of them tested:

- **After the cooldown**, so a held trigger makes five sounds a second and not
  sixty. Before it, the blaster would be a buzz whose pitch depended on `--fps`.
- **Before the hitscan resolves, and unconditionally**, so a miss sounds exactly
  like a hit. Same rule as the tracer: a sound that only played when you
  connected would tell the player the outcome before the game does.
- **Through the port**, so on a headless run it is a counter increment — which is
  why CI can assert the cadence without a sound card.

## What this is not

**There is no mixer, no positional audio and no music.** Not "not yet, see the
formulae below" — not at all. The port cannot express a position, `SoundId`
carries no volume or pitch, and nothing here allocates a mixing buffer.

**There is no voice limit *in this package*** either, and that is a different
statement from there being none: return fire is capped by `demo/BotFireVoices`
above the port, for the reason § "Why the voice limit is not in here" gives. What
does not exist is any notion of voices *inside* the audio layer — no count of what
is playing, no priority, no culling.

That is a narrowing, and it was chosen. A 32-voice mixer with distance
attenuation is a real piece of work, and building the *interface* for one before
building the thing is how this package spent several phases as eighty lines of
formulae and a stub that did nothing. One sound that actually plays is worth more
than a specification for a hundred that do not.

### The 3D audio that is not here

Kept as a reading list for whoever builds it, explicitly **not** as a description
of this code:

- **Inverse-square distance falloff.** `loudness = 1 / max(MIN_DIST², distance²)`,
  usually softened to linear inside a near radius. OpenAL 1.1 specification § 5.2
  "Distance Attenuation":
  https://www.openal.org/documentation/openal-1.1-specification.pdf
- **Stereo panning** from the angle between the source-to-listener vector and the
  listener's forward: `pan = sin(acos(sourceDir · forward))`, signed by the cross
  product. "3D Audio for Games", DSP Dimension: https://www.dspdimension.com/
- **Doppler** for moving sources: `f' = f · (c + vListener) / (c + vSource)`,
  `c ≈ 343 m/s`, clamped to `[0.5f, 2f]`.
  https://en.wikipedia.org/wiki/Doppler_effect
- **Voice priority** for culling above a voice limit, roughly
  `distance × volume × recency`.

When these arrive they arrive as a **listener pose plus a source position** — not
as three ints whose frame of reference nobody wrote down, which is what the old
`playSfx(int, int, int, int)` was.

Whichever way it goes, one property is worth preserving: **audio is not
lockstep**. Nothing here has to be identical between two peers, because a sound
is a consequence of a tic and no tic reads one back. That is what permits float
volumes, platform mixers and a frame of latency — none of which the gameplay
state machine could tolerate. `BlasterSound` therefore uses `Math`, not
`StrictMath`, unlike `Hitscan`.

**Music** is still undecided and still unstarted. MIDI is what 1993 did, OGG
would mean a decoder, streaming raw PCM costs disk. No preference is recorded and
no interface pretends otherwise.

## Files and tests

| File | Tests |
|---|---|
| `port/I_AudioPort.java` | contract exercised via `NullAudioPortTest` |
| `port/SoundId.java` | covered by `SoundBankTest`, which iterates `values()` |
| `AudioVolume.java` | `AudioVolumeTest` — 8, including the NaN one-liner trap |
| `synth/BlasterSound.java` | `BlasterSoundTest` — 12 |
| `synth/CarbineSound.java` | `CarbineSoundTest` — well formed, reproducible, and measurably not a pitch sweep |
| `synth/SoundBank.java` | `SoundBankTest` — no two ids share a buffer or a file name |
| `synth/WavAudio.java` | `WavAudioTest` — 12, header read back independently |
| `demo/BotFireVoices.java` | `BotFireVoicesTest` — the cap, the headroom, and the first shot |
| `adapter/NullAudioPort.java` | `NullAudioPortTest` — 10, incl. concurrent play |
| `:gdxshared` `GdxAudioPort.java` | `GdxAudioPortTest` — 8, all of them the no-device path |
| `demo/DemoGameplayPort` | `DemoGameplayPortAudioTest` — 9, cadence and silence |

No test needs an audio device, and none may ever be added that does.
