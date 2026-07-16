# Weylus Studio — Unified Roadmap, Phase Plan & Changelog Journal

This document outlines the engineering phase plan for evolving Weylus Studio from a stable Windows drawing tool to a modular multi-device platform. It serves as a living changelog and unified tracking plan for all completed and future milestones.

---

## Document Index & Timeline

| Section | Purpose | Date | Status |
| :--- | :--- | :--- | :--- |
| **Section 1** | Pillars of Engineering Integrity | 2026-06-18 | Active |
| **Section 2** | Historical Change Log Journal | 2026-06-18 | Ongoing |
| **Section 3** | Evolving Unified Phase Plan | 2026-07-08 | Active Plan |
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

### July 3, 2026 — OS Decoupling, Unified Wire Protocol & Client Capabilities
* **Universal JSON Wire Format**: Removed `#[cfg(target_os = "linux")]` from `uinput_support` in `ClientConfiguration` to ensure a platform-neutral WebSocket protocol.
* **ClientCapabilities Struct**: Added an extensible `ClientCapabilities` container supporting `virtual_keyboard`, `uinput`, `hover`, `clipboard`, and `pressure` flags for future Native Android client handshake.
* **Platform Gating Symmetry**: Gated `InputDeviceType::UInputDevice` with `#[cfg(target_os = "linux")]` to mirror the Windows variant.
* **Unified get_capturables Signature**: Standardized the parameters of `get_capturables` to take `wayland_support: bool` and `capture_cursor: bool` on all platforms, removing conditional inline parameters.

### July 8, 2026 — Android Native Client Architecture Freeze (Phase 3 Core)
* **Architecture Freeze**: Locked the decoupled component boundaries for the native Android Kotlin client. All layers (UI, Decoder, Scheduler, Input, Transport) are now defined as pure interfaces with no cross-layer dependencies.
* **CoordinateMapper**: Implemented as a pure math class with no Android or UI dependencies. Maps raw `MotionEvent` coordinates through aspect-ratio letterbox correction to normalized server space.
* **DeviceCapabilityProvider**: Dynamic runtime detection of stylus hardware, pen button support, and display refresh rate. Replaces compile-time static capabilities with runtime probing.
* **FrameScheduler (Choreographer-backed)**: Decouples `MediaCodec` buffer management from frame presentation. The decoder produces frames; the scheduler paces presentation via `Choreographer.FrameCallback`.
* **SessionState Machine**: Explicit lifecycle states (`Negotiating`, `Streaming`, `Recovering`, `Disconnected`, `Error`) with transition guards. Prevents illegal state transitions from causing crashes.
* **Transport Abstraction**: `Transport` interface + `WebSocketTransport` implementation via OkHttp. Designed for future swap with USB/ADB or QUIC transport.
### July 16, 2026 — Color Fixes, H.264 Streaming, Stylus Contact & Aspect Ratio Layout Fixes
* **PC Debug Screenshot Colors**: Swapped Red and Blue channels in `dxgi_dup.rs` debug frames before saving, restoring correct orange wallpaper hues.
* **H.264 Stream Decoding**: Exposed a `raw_h264` capability in capabilities negotiation, switching FFmpeg FFI output to raw Annex B H.264 streams to allow native `MediaCodecDecoder` rendering (fixing the blank/gray screen).
* **Stylus Contact & Pressure**: Forwarded pen contact drag states via `POINTER_FLAG_INCONTACT` during active pressure and mapped transitions to `event.button` instead of `event.buttons`.
* **Aspect Ratio Touch Accuracy**: Fixed coordinate mapping to use normalized `[0.0, 1.0]` coordinates, and scaled `SurfaceView` inside a centered `Box` with `Modifier.aspectRatio` to maintain correct proportions (fixing edge drift).
* **DXGI Duplication Pacing**: Reduced AcquireNextFrame timeout from 50ms to 2ms, unlocking high-framerate pacing (60Hz to 120Hz).
* **English Translation**: Translated help strings and launcher banner in `main.py` to English.

---

## Section 3: Evolving Unified Phase Plan

### Phase 1 — Windows Stability & Drawing Quality (Completed ✅)
* **Goal**: Stabilize memory allocations, ensure safe kernel handle drops, scale stylus pressure mappings accurately up to 1024, and resolve micro-stuttering using microsecond timestamping at capture boundaries. See [[CASE_STUDIES#Chapter 1 Memory Leak in Windows Touch Injection]], [[CASE_STUDIES#Chapter 2 Handle Leak: Synthetic Pointer Devices]], and [[CASE_STUDIES#Chapter 10 Frame Pacing & Timing Resolution]].

### Phase 2 — Plug-and-Play USB, mDNS & Community PRs (Completed ✅)
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
- [x] **OS Decoupling & Unified Wire Protocol**: Unified `get_capturables` signature, aligned platform gating on input devices, and established the platform-agnostic client configuration wire model. See [[CASE_STUDIES#Chapter 15 OS Decoupling & Unified Wire Protocol]].

### Phase 3 — Android Native Client (Kotlin + Jetpack Compose) (Current 🚀)
* **Goal**: Replace the web client completely with a native Kotlin Android application to bypass browser rendering bottlenecks and target **120 FPS** with ultra-low latency. See [[CASE_STUDIES#Chapter 12 Evolving to Modular Multi-Device Platform]].

#### Architecture Layer (Completed ✅)
- [x] **Transport Abstraction**: `Transport` interface and `WebSocketTransport` implementation via OkHttp. Future-proofed for USB/ADB and QUIC swap.
- [x] **SessionState Machine**: Explicit lifecycle with transition guards (`Negotiating` → `Streaming` → `Recovering` → `Disconnected`).
- [x] **CoordinateMapper**: Pure math class for aspect-ratio-aware, letterbox-corrected coordinate projection from `MotionEvent` to normalized server space.
- [x] **DeviceCapabilityProvider**: Runtime detection of stylus hardware, pen buttons, and display refresh rate.
- [x] **FrameScheduler (Choreographer-backed)**: Decouples `MediaCodec` buffer management from V-Sync paced frame presentation.
- [x] **Protocol Extensions**: `DisplayCapability` (handshake) and `DisplayChanged` (runtime orientation) added to `src/protocol.rs`.

#### Implementation Phase (Completed/Active 🚧)
- [x] **MediaCodec Hardware Decoding**: Decode H.264 streams directly into `SurfaceView` using Android `MediaCodec` hardware decoder with `KEY_LOW_LATENCY` enabled.
- [x] **MirrorCanvas Compose Surface**: Render decoded frames via Jetpack Compose `AndroidView` wrapping a `SurfaceView` with `CoordinateMapper` integration.
- [x] **Native MotionEvent Handler**: Forward `pressure`, `tiltX`, `tiltY` (via `AXIS_TILT`), and hover events from `MotionEvent` directly to the `Session` WebSocket pipe.
- [x] **ConnectScreen UI**: Compose-based UI supporting manual IP/port entry and connection state mapping.
- [x] **APK Build & Distribution**: Configured Gradle (Gradle 8.7 wrapper, local.properties) and produced a successful debug APK.
- [x] **Phase B Telemetry Verification & Tuning**: Perform end-to-end telemetry testing with a physical device and tune host-side NVIDIA NVENC (`h264_nvenc`) and AMD/Intel MediaFoundation (`h264_mf`) low-latency encoders.

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
