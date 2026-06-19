# Weylus Studio — Roadmap, Phase Plan & Changelog Journal

This document outlines the engineering phase plan for evolving Weylus Studio from a stable Windows drawing tool to a full-featured Android native drawing tablet experience (Kotlin + Jetpack Compose). It also serves as a living changelog journal for tracking decisions over time.

---

## Document Index & Timeline

| Section | Purpose | Date | Status |
| :--- | :--- | :--- | :--- |
| **Section 1** | Pillars of Engineering Integrity | 2026-06-18 | Active |
| **Section 2** | Historical Change Log Journal | 2026-06-18 | Ongoing |
| **Section 3** | Phase Plan: Windows → Flutter Native | 2026-06-18 | Phased planning |
| **Section 4** | Rust Learning Milestones | 2026-06-18 | In progress |

---

## Section 1: Pillars of Engineering Integrity

The Weylus Studio engineering approach is governed by four core pillars, reflecting the project owner's background in consumer-grade hardware optimization:

1. **Latency-First for Drawing**: Every architectural decision must minimize the round-trip time from stylus contact on the tablet to pixel registration in the drawing app on the PC. This is non-negotiable — a drawing tool that lags feels broken.

2. **Memory Safety Without Sacrifice**: Rust's ownership system is the primary defense against memory corruption and leaks. `unsafe` blocks must be minimal, documented, and reviewed. Every `Box::into_raw()` must have a paired `Box::from_raw()` or be replaced with a safe borrow.

3. **Consumer Hardware Realism**: The project runs on a personal Windows PC and an Android tablet. No cloud dependencies, no hardware encoders assumed. Software x264 must always be the reliable fallback. Optional hardware acceleration (NVENC, MediaFoundation) is a performance bonus, not a baseline requirement.

4. **Progressive Enhancement Architecture**: The browser-based frontend is the current baseline. The Flutter native client is the target. Every protocol decision (`protocol.rs`) must be designed so that a native client can replace the browser without breaking the Rust server.

---

## Section 2: Historical Change Log Journal

### June 18, 2026 — Initial Documentation & Bug Investigation Pass
* **Project forked and renamed**: Weylus Community Edition forked as `Weylus-Studio` under the Synthover Framework. License remains AGPL-3.0-or-later. Original copyright notices preserved.
* **Initial codebase audit**: Full read of all Rust source files (`src/`), build scripts (`build.rs`, `deps/`), TypeScript frontend (`ts/lib.ts`), and Windows-specific modules (`capturable/win_ctx.rs`, `input/autopilot_device_win.rs`).
* **Three candidate bugs identified and documented**:
    1. Memory leak: `Box::into_raw()` in touch injection without deallocation (Chapter 1, CASE_STUDIES.md).
    2. Handle leak: `CreateSyntheticPointerDevice` handles never released (Chapter 2, CASE_STUDIES.md).
    3. Crash risk: `PointerType::Unknown => todo!()` panic in production code path (Chapter 3, CASE_STUDIES.md).
* **Docs folder initialized**: `QUICKSTART.md`, `ARCHITECTURE.md`, `CASE_STUDIES.md`, `ROADMAP.md` created.
* **License clarified**: AGPL-3.0 allows republication under a new project name without using GitHub's Fork feature, provided the license is preserved, copyright notices are retained, and source code of modifications is made available upon distribution.

### June 19, 2026 — Windows Input Stability Fixes, Decoupled Dispatcher Build System, and Flat Typed Capability Freeze
* **Fixed Confirmed Bugs (Priority S)**: Completed safe pointer touches without box leak, implemented `Drop` destructor for windows pointer/touch synthetic handles, and handled unknown pointer events gracefully without crash panic.
* **Established Dual-Backend Architecture**: Switched Windows target to utilize a precompiled cache of locally-built static libraries under `deps/prebuilt_windows`, bypassing compilation dependencies entirely. Linux/macOS targets continue using their POSIX pipelines.
* **Decoupled Build Script Modules**: Refactored `build.rs` into a platform module dispatcher that delegates compiling and linking to isolated submodules: `build/windows.rs`, `build/linux.rs`, `build/macos.rs`, and `build/common.rs`.
* **TypeScript NPX Compilation & Type Safety**: Updated compilation script to execute TypeScript natively using `npx` dynamic execution, resolving strict type checks via loose settings in `tsconfig.json`.
* **Evolved Capabilities to Flat Typed Contracts**: Replaced boolean `typescript_via_npx` flag with a typed enum `TypeScriptCompilerSource` in `common.rs` to make toolchain execution deterministic and type-safe. Kept the `BuildCapabilities` struct flat to avoid over-engineering.
* **Enforced Boundaries**: Added Rules 7, 8, and 9 to `CONSTRAINTS.md` to freeze build-system abstraction levels and prevent compile-time structs from leaking into runtime `src/` modules.

---

## Section 3: Phase Plan

### Phase 1 — Windows Stability & Drawing Quality (Current)

Goal: Make the existing web-based experience reliable and accurate enough for real drawing sessions on Windows.

#### Priority S — Crash & Leak Prevention (Confirmed Bugs)
- [x] **Fix memory leak** in touch injection (`autopilot_device_win.rs`): Replace `Box::into_raw()` with `Vec::as_mut_ptr()`. See [CASE_STUDIES.md Chapter 1](./CASE_STUDIES.md#chapter-1-memory-leak-in-windows-touch-injection).
- [x] **Fix handle leak**: Implement `Drop` for `WindowsInput` to call `DestroySyntheticPointerDevice`. See [CASE_STUDIES.md Chapter 2](./CASE_STUDIES.md#chapter-2-handle-leak--synthetic-pointer-devices-never-released).
- [x] **Fix crash risk**: Replace `PointerType::Unknown => todo!()` with a graceful `warn!` + `return`. See [CASE_STUDIES.md Chapter 3](./CASE_STUDIES.md#chapter-3-crash-risk--pointertype-unknown-causes-application-panic).

#### Priority A — Drawing Accuracy & Latency (Investigations & Tuning)
- [x] **Verify pen pressure range**: Confirm whether Win32 Synthetic Pointer API accepts 0–1024 or a wider range (e.g., 0–8191). Verified that 1024 is the native Win32 API limit. See [CASE_STUDIES.md Chapter 4](./CASE_STUDIES.md#chapter-4-pressure-range-verification-0-1024-vs-0-8191).
- [x] **Improve video timestamp precision**: Upgraded timing resolution to microseconds and shifted the timestamp boundary to the capture moment. See [CASE_STUDIES.md Chapter 10](./CASE_STUDIES.md#chapter-10-frame-pacing--timing-resolution).
- [x] **Evaluate WebSocket input buffer**: Tuned inbound/outbound queues to 128 and implemented priority-aware frame coalescing. See [CASE_STUDIES.md Chapter 5](./CASE_STUDIES.md#chapter-5-websocket-queue-buffer-size--frame-coalescing).

#### Priority B — Build & Distribution Improvements (Known Limitation)
- [x] **Remove `bash` dependency on Windows build**: Replace `build.rs`'s `Command::new("bash")` FFmpeg build step with a PowerShell script or a pre-built FFmpeg DLL strategy, so `cargo build` works natively on Windows without Git Bash or MSYS2.
- [x] **Investigate static FFmpeg linking on Windows**: Currently `dylib`. Static linking would make the `.exe` self-contained.

### Phase 2 — Plug-and-Play USB & Connectivity (Next Priority)

Goal: Make connecting the tablet to the PC seamless and feel like a finished product, especially for USB-connected setups.

- [ ] **Auto ADB reverse**: Detect if an Android device is connected via USB and automatically run `adb reverse tcp:1701 tcp:1701` and `adb reverse tcp:9001 tcp:9001`.
- [ ] **mDNS / auto-discovery**: Broadcast the Weylus Studio server via mDNS so the tablet browser can discover the PC without manually entering an IP address.
- [ ] **Reconnection UX**: Implement the deferred [new frontend patch](https://github.com/H-M-H/Weylus/pull/290) for click-to-reconnect and HiDPI coordinate accuracy.


### Phase 3 — Android Native Client (Kotlin + Compose)

Goal: Replace the browser frontend with a low-latency Android native client capable of delivering a SuperDisplay-class experience.

**Why Kotlin Native + Jetpack Compose over Flutter**:

| Capability                   | Flutter              | Kotlin Native |
| ---------------------------- | -------------------- | ------------- |
| Modern UI                    | Excellent            | Excellent     |
| Android API access           | Plugin layer         | Direct access |
| MotionEvent                  | Indirect             | Native        |
| Stylus pressure              | Good                 | Excellent     |
| Tilt support                 | Good                 | Excellent     |
| Hover support                | Limited              | Excellent     |
| Samsung S Pen SDK            | Plugin/manual bridge | Native        |
| USB detection                | Good                 | Excellent     |
| BroadcastReceiver            | Plugin/manual bridge | Native        |
| MediaCodec hardware decoding | Plugin               | Native        |
| Lowest possible latency      | Good                 | Excellent     |
| Android ecosystem support    | Excellent            | Official      |
| Jetpack Compose              | No                   | Yes           |

**Planned Android app architecture**:
```text
weylus-android/ (Kotlin Native + Jetpack Compose)
│
├── ui/
│     ConnectScreen.kt      # IP/port entry, QR scan, mDNS discovery
│     CanvasScreen.kt       # Full-screen drawing canvas + low-latency video overlay
│
├── network/
│     WebSocketClient.kt    # Sends PointerEvent JSON matching protocol.rs
│     VideoStream.kt        # Receives H.264 video streams
│
├── stylus/
│     StylusManager.kt      # MotionEvent handler (pressure, tilt, tool types)
│
├── usb/
│     UsbManager.kt         # Detects USB connection, triggers BroadcastReceiver
│     AdbReverseManager.kt  # Integrates ADB port forwarding triggers
│
├── discovery/
│     MdnsManager.kt        # Auto-discovers Weylus Studio server
│
└── decoder/
      MediaCodecPlayer.kt   # Direct low-latency hardware decoding via MediaCodec
```

- [ ] **Phase 3.1**: WebSocket client in Kotlin sending `PointerEvent` JSON matching `protocol.rs` exactly.
- [ ] **Phase 3.2**: Low-latency video pipeline using Android `MediaCodec` direct decoding into a `SurfaceView` / `TextureView`.
- [ ] **Phase 3.3**: Android MotionEvent Stylus API integration (`event.pressure`, `event.orientation`, `event.axisValue(AXIS_TILT)`, `event.toolType`).
- [ ] **Phase 3.4**: Samsung S Pen SDK integration (pressure calibration, hover events, Air Actions).


### Phase 4 — Virtual Display & Second Screen

Goal: Use the tablet as a true second screen, not just a mirror.

- [ ] **Virtual display driver on Windows**: Investigate IddCx (Indirect Display Driver) or similar to create a virtual monitor that Weylus Studio can capture and stream exclusively to the tablet.
- [ ] **Pen-only mode**: When using a virtual display, route all tablet pen input directly to the virtual screen coordinate space, eliminating the need for coordinate scaling.
- [ ] **SuperDisplay-class experience**: Full drawing canvas on the tablet with hardware-accurate pressure and tilt, matching the experience of a dedicated drawing tablet.

---

## Section 4: Rust Learning Milestones

A personal learning track aligned with the Weylus Studio codebase, for someone coming from a Python/AI engineering background.

| Milestone | Concept | Applied in Weylus Studio | Status |
| :--- | :--- | :--- | :--- |
| **M1** | Variables, functions, structs, enums | `protocol.rs` — pure data structures | [ ] |
| **M2** | Ownership, borrowing, `&`, `&mut` | `capturable/mod.rs`, `websocket.rs` | [ ] |
| **M3** | Traits, `impl Trait`, `Box<dyn Trait>` | `input/device.rs`, `WeylusSender` trait | [ ] |
| **M4** | Error handling: `Result<T, E>`, `Option<T>`, `?` | `cerror.rs`, all `match` blocks | [ ] |
| **M5** | Pattern matching: `match`, `if let`, `while let` | `autopilot_device_win.rs` pointer dispatch | [ ] |
| **M6** | Async/Await with Tokio | `web.rs`, `websocket.rs` async loops | [ ] |
| **M7** | Unsafe Rust & raw pointers | `autopilot_device_win.rs` Win32 calls | [ ] |
| **M8** | FFI: calling C from Rust | `video.rs` → `lib/encode_video.c` | [ ] |
| **M9** | Conditional compilation: `#[cfg(...)]` | Throughout, OS-specific module gating | [ ] |
| **M10** | Build scripts: `build.rs` | `build.rs` — tsc, FFmpeg, link flags | [ ] |
