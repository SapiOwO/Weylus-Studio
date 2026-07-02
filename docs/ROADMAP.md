# Weylus Studio — Unified Roadmap, Phase Plan & Changelog Journal

This document outlines the engineering phase plan for evolving Weylus Studio from a stable Windows drawing tool to a modular multi-device platform. It serves as a living changelog and unified tracking plan for all completed and future milestones.

---

## Document Index & Timeline

| Section | Purpose | Date | Status |
| :--- | :--- | :--- | :--- |
| **Section 1** | Pillars of Engineering Integrity | 2026-06-18 | Active |
| **Section 2** | Historical Change Log Journal | 2026-06-18 | Ongoing |
| **Section 3** | Evolving Unified Phase Plan | 2026-07-03 | Active Plan |
| **Section 4** | Rust Learning Milestones | 2026-06-18 | In progress |

---

## Section 1: Pillars of Engineering Integrity

1. **Latency-First for Drawing**: Every architectural decision must minimize the round-trip time from stylus contact on the tablet to pixel registration in the drawing app on the PC.
2. **Memory Safety Without Sacrifice**: Rust's ownership system is the primary defense against memory corruption and leaks. Paired cleanup drops must be implemented for all synthetic OS pointer handles.
3. **Consumer Hardware Realism**: The baseline compiler supports consumer-grade setups with static libraries. Software H.264 remains the robust fallback, with optional NVENC/MediaFoundation acceleration.
4. **Progressive Enhancement Architecture**: All network protocol formats must be platform-neutral (`src/protocol.rs`), allowing the browser client to be progressively replaced by a native Kotlin application without breaking core server logic.

---

## Section 2: Historical Change Log Journal

### June 18, 2026 — Initial Documentation & Bug Investigation Pass
* **Project Forked**: Forked as `Weylus-Studio` to evolve the drawing platform.
* **Initial Audit**: Identified memory leaks in multitouch pointer allocation, kernel handle leaks on device drop, and crash risk panics on unknown events.

### June 19, 2026 — Input Stability, Decoupled Dispatcher, and Flat Typed Capability Freeze
* **Fixed Confirmed Bugs**: Completed touch injection borrows, implemented `Drop` traits for Win32 synthetic handles, and replaced panic-bound `todo!()` macros with warnings.
* **Decoupled Build Script Modules**: Refactored monolithic `build.rs` into platform dispatcher submodules (`build/windows.rs`, `build/linux.rs`, `build/macos.rs`, and `build/common.rs`).
* **Established Capabilities Contract**: Extracted compiler checks to a flat `BuildCapabilities` struct with typed enums.

### June 21, 2026 — Documentation Standardization & Visi Platform Multi-Device
* **5W+1H Standardization**: Unified all case studies into a comprehensive document using the 5W+1H template. See [[CASE_STUDIES]].
* **Defined Native 120 FPS Vision**: Detailed the transition from web server rendering to a native Kotlin Android application utilizing USB tethering to break performance boundaries.

### July 3, 2026 — Build Decoupling & Configuration Fallback
* **Refactored common.rs**: Removed all platform detection `cfg!(target_os)` checks from the TS compiler by introducing `shell` and `shell_flag` in `BuildCapabilities`.
* **Recursive Cargo Tracking**: Configured Cargo to track all source files inside `www/src` recursively, preventing stale client assets.
* **Safety Configuration Fallback**: Fixed a silent failure where virtual key profiles could not be saved if the initial `weylus.toml` config file was missing.

---

## Section 3: Evolving Unified Phase Plan

### Phase 1 — Windows Stability & Drawing Quality (Completed ✅)
* **Goal**: Stabilize memory allocations, ensure safe kernel handle drops, scale stylus pressure mappings accurately up to 1024, and resolve micro-stuttering using microsecond timestamping at capture boundaries. See [[CASE_STUDIES#Chapter 1 Memory Leak in Windows Touch Injection]], [[CASE_STUDIES#Chapter 2 Handle Leak: Synthetic Pointer Devices]], and [[CASE_STUDIES#Chapter 10 Frame Pacing & Timing Resolution]].

### Phase 2 — Plug-and-Play USB, mDNS & Community PRs (Current 🚀)
* **Goal**: Implement click-to-reconnect and HiDPI coordinate scaling alignments (PR #290), integrate virtual keyboard bindings (PR #291), and configure network discovery protocols.
- [x] **Clean Executable Reference Client Build**: Created `build_web_client` in `build/common.rs` to invoke npm/pnpm directly.
- [x] **Removed Auto-Install**: Enforced explicit panic errors if dependency modules are missing, protecting compilation determinism.
- [x] **Pointer Enter/Leave Events**: Added logical `Enter` and `Leave` states to `PointerEventType` in `src/protocol.rs`.
- [x] **Type-Safe Virtual Keyboard Protocol**: Created struct `VirtualKey` and `VirtualKeyProfile` in `src/protocol.rs`, bypassing loose string serialization.
- [x] **Modular Domain Extraction**: Extracted configuration read/write for virtual keys profiles out of the websocket net transport layer into `src/virtual_keys.rs`.
- [x] **Decoupled Shell Execution in Build Script**: Integrated `shell` and `shell_flag` in `BuildCapabilities` for platform-neutral compiler invocation. See [[CONSTRAINTS]].
- [x] **Recursive Asset Rebuild Tracking**: Configured Cargo to watch all `www/src` files recursively, preventing stale assets.
- [x] **Safety Config Fallback**: Added default configuration fallback when writing virtual key profiles dynamically.
- [x] **mDNS Discovery & USB Auto ADB reverse**: Automatically reverse tcp ports (`adb reverse tcp:1701 tcp:1701`) when Android is connected via USB, and broadcast the host via mDNS. See [[CASE_STUDIES#Chapter 14 mDNS Discovery & USB Auto ADB Reverse]].

### Phase 3 — Android Native Client (Kotlin + Jetpack Compose) (Future 🚀)
* **Goal**: Replace the web client completely with a native Kotlin Android application to bypass browser rendering bottlenecks and target **120 FPS** with ultra-low latency. See [[CASE_STUDIES#Chapter 12 Evolving to Modular Multi-Device Platform]].
- [ ] **Kotlin WebSocket Engine**: Connect native client directly to `protocol.rs` serializations.
- [ ] **MediaCodec Hardware Decoding**: Decode H.264 streams directly into native Android `SurfaceView` or Jetpack Compose Canvas.
- [ ] **Native MotionEvent Handler**: Access stylus API parameters (`pressure`, `orientation`, `tilt`, and hover events) with zero latency overhead.
- [ ] **Samsung S Pen SDK Integration**: Calibrate S Pen-specific hover and Air Action signals.

### Phase 4 — Virtual Display & Second Screen (Future 🚀)
* **Goal**: Leverage Windows Indirect Display Driver (IddCx) to create a virtual monitor, streaming the workspace exclusively to the tablet for a complete dual-display drawing experience.

---

## Section 4: Rust Learning Milestones

| Milestone | Concept | Applied in Weylus Studio | Status |
| :--- | :--- | :--- | :--- |
| **M1** | Variables, functions, structs, enums | `protocol.rs` — pure data structures | [x] |
| **M2** | Ownership, borrowing, `&`, `&mut` | `capturable/mod.rs`, `websocket.rs` | [x] |
| **M3** | Traits, `impl Trait`, `Box<dyn Trait>` | `input/device.rs`, `WeylusSender` trait | [x] |
| **M4** | Error handling: `Result<T, E>`, `Option<T>`, `?` | `cerror.rs`, all `match` blocks | [x] |
| **M5** | Pattern matching: `match`, `if let`, `while let` | `autopilot_device_win.rs` pointer dispatch | [x] |
| **M6** | Async/Await with Tokio | `web.rs`, `websocket.rs` async loops | [/] |
| **M7** | Unsafe Rust & raw pointers | `autopilot_device_win.rs` Win32 calls | [/] |
| **M8** | FFI: calling C from Rust | `video.rs` → `lib/encode_video.c` | [/] |
| **M9** | Conditional compilation: `#[cfg(...)]` | Throughout, OS-specific module gating | [/] |
| **M10** | Build scripts: `build.rs` | `build.rs` — tsc, FFmpeg, link flags | [/] |
