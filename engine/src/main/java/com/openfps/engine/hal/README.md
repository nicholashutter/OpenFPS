# HAL (I_) — Hardware Abstraction Layer

> The ONLY place platform-specific code lives.

## Layout

```
hal/
├── port/                interfaces only — core depends on these
│   ├── I_TimePort.java
│   ├── I_InputPort.java
│   ├── I_DatagramPort.java
│   ├── I_FilePort.java
│   ├── I_SystemInfoPort.java
│   └── I_UserProfilePort.java
└── adapter/             platform-specific implementations
    ├── desktop/         (Phase 1.5 — LWJGL3 window + input)
    ├── sqlite/          (current — Xerial SQLite user profile)
    ├── mobile/          (Phase 3+ — Android Canvas + AudioTrack + Room)
    └── nulladapter/     (current — headless / CI)
```

## What each port does

| Port | Role | Typical backends |
|---|---|---|
| `I_TimePort` | Monotonic millisecond / nanosecond counter | `System.nanoTime()` (any), `QueryPerformanceCounter` (Windows), `clock_gettime` (Linux) |
| `I_InputPort` | Sample keyboard / mouse / gamepad each tic | LWJGL3 Keyboard/Mouse (desktop), Android `MotionEvent` (mobile) |
| `I_DatagramPort` | Raw UDP send/receive + per-tic tick | `java.nio.channels.DatagramChannel` (any), native sockets |
| `I_FilePort` | Open + read + size + close files | `java.io.FileInputStream` (any), Android assets |

## How to write a new adapter

1. Pick the right `adapter/<platform>/` directory. Create it if needed.
2. `implements I_<Capability>Port` from the matching `port/` interface.
3. Use **only** JDK 17 stdlib APIs (no LWJGL / Android in the core module).
4. If you need a platform-specific dependency (e.g. LWJGL3 natives), put it in a
   sub-module with its own `build.gradle.kts` so the Android profile doesn't
   pull in desktop natives.
5. Update `NullAdapterFactory` in `nulladapter/` to expose your new adapter for
   test, and the desktop/mobile factory in their own files (Phase 1).
6. Add tests in `src/test/java/com/openfps/engine/hal/adapter/<yourplatform>/`.

## Why port-first?

Adapter/port (a.k.a. hexagonal) architecture lets the engine core run:
- On a server with no graphics (null adapter)
- On a desktop with LWJGL3 (desktop adapter)
- On a phone with Android (mobile adapter)
- In a test JVM with fakes (null adapter + injected time)

…all from the same compiled core JAR. Adapters are the only things that change.

**Reference:** Alistair Cockburn, "Hexagonal Architecture" (2005):
https://alistair.cockburn.us/hexagonal-architecture/
