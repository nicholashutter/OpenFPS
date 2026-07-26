# Core (D_) — Main Game Loop

> The `D_` is for "Doom main loop" — we keep the prefix as homage.

## What lives here

- `EngineMain` — boot entry, wires HAL adapter, constructs `GameLoop`, runs to shutdown.
- `GameLoop` — the fixed-rate tic ticker. Single-threaded, called by `EngineMain` or a launcher.

## The 35 Hz tic

We run at **35 tics per second** (the original Doom rate). Each tic:
1. Sample input.
2. Process network (P2P tic cmds + snapshot deltas).
3. Advance gameplay (player physics, entity AI, map logic).
4. Advance audio.
5. Submit a frame to the renderer.

This is **fixed timestep** rendering — see Glenn Fiedler's "Fix Your Timestep":
https://gafferongames.com/post/fix_your_timestep/

> The DOOM source uses 35 tics/sec because that's the slowest rate where
> enemy movement still looks smooth on the original 386 hardware.
> Modern machines could go higher; we keep 35 for engine-compat with classic WAD content.

## Threading model

- **One** game thread, driven by `GameLoop.run()`.
- Audio output, network I/O, and rendering run on **separate threads** that read
  the latest `GameState` snapshot (lock-free or `volatile`-published).
- The game thread is the **only writer** to `GameState`.

## Timing primitive

We use `I_TimePort.nanos()` for everything. Never `System.currentTimeMillis()`
or `Thread.sleep` directly in the loop body — the port abstraction lets tests
inject a fake time source.

The tic deadline is `nextTicDeadlineNanos += NANOS_PER_TIC` (additive, not
absolute) so that one slow frame does not drift the whole loop.

## Files

- `EngineMain.java` — main()
- `GameLoop.java` — `Runnable` game loop

## TODOs (not yet implemented)

- Variable tic rate (configurable per-match)
- Lag compensation frame for netcode rollback
- Headless mode for dedicated server
