# Case Studies: Architecture, Performance, & Shared Engineering Lessons

This unified document compiles all case studies for Weylus Studio. Each chapter documents a major design decision, technical challenge, root-cause analysis, and the final implementation details, mapped to their specific commit lifecycle milestones.

---

## Document Index & Changelog Timeline

| Chapter | Focus Area | Date | Key Architectural Enhancement | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Chapter 1** | Memory Leak in Windows Touch Injection | 2026-06-18 | `Box::into_raw()` replaced with `Vec::as_mut_ptr()` borrow | **Resolved** ✅ |
| **Chapter 2** | Handle Leak: Synthetic Pointer Devices | 2026-06-18 | `impl Drop for WindowsInput` added to destroy pointer device handles | **Resolved** ✅ |
| **Chapter 3** | Crash Risk: `PointerType::Unknown` Panic | 2026-06-18 | Placeholder `todo!()` replaced with graceful `warn!` + `return` | **Resolved** ✅ |
| **Chapter 4** | Pressure Range Verification (0-1024) | 2026-06-19 | Calibrated stylus scaling factors to match Win32 native limits | **Resolved** ✅ |
| **Chapter 5** | WebSocket Queue & Frame Coalescing | 2026-06-19 | Outbound video queues buffer video frames; fixed via order-preserving frame coalescing | **Resolved** ✅ |
| **Chapter 6** | Windows Native Build Environment Issues | 2026-06-18 | Git Line conversions, NASM installation, and static compiler fixes | **Resolved** ✅ |
| **Chapter 7** | Dual-Backend Dispatcher Architecture | 2026-06-18 | Monolithic `build.rs` decoupled into per-OS modules (`windows.rs`, `linux.rs`, `macos.rs`) | **Resolved** ✅ |
| **Chapter 8** | Capability Layer Extraction (`common.rs`) | 2026-06-18 | Extracted OS-specific flags from build orchestration helper | **Resolved** ✅ |
| **Chapter 9** | Flat Typed Build Capabilities | 2026-06-19 | Frozen capability schema to prevent abstraction bloat | **Resolved** ✅ |
| **Chapter 10** | Frame Pacing & Timing Resolution | 2026-06-19 | Microsecond resolution presentation timestamping at capture boundary | **Resolved** ✅ |
| **Chapter 11** | Build Pipeline & Virtual Keyboard Serialization | 2026-06-21 | Streamlined node compile triggers, dropped string protocols, implemented virtual_keys.rs | **Resolved** ✅ |
| **Chapter 12** | Evolving to Modular Multi-Device Platform | 2026-06-21 | Shifting from web-based mirroring to native Kotlin client & USB 120 FPS target | **Vision Defined** 🚀 |
| **Chapter 13** | Build Decoupling & Configuration Fallback Safety | 2026-07-03 | Platform-neutral shell injection in `BuildCapabilities` and safe fallback struct generation | **Resolved** ✅ |
| **Chapter 14** | mDNS Discovery & USB Auto ADB Reverse | 2026-07-03 | Broadcast host via local mDNS and periodically establish adb reverse port mappings | **Resolved** ✅ |
| **Chapter 15** | OS Decoupling & Unified Wire Protocol | 2026-07-03 | Universal `ClientConfiguration` + `ClientCapabilities` handshake, symmetric enum gating | **Resolved** ✅ |
| **Chapter 16** | Phase 3 Android Native Client Architecture Freeze | 2026-07-08 | Decoupled Transport/Session/Decoder/Scheduler/Input layers; protocol extended with `DisplayCapability` | **Architecture Frozen** ✅ |

---

## Chapter 1: Memory Leak in Windows Touch Injection
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Any user running Weylus Studio on Windows using multi-touch gestures or stylus drawing.
* **Who executes the code**: The `send_pointer_event` function in `WindowsInput` (`src/input/autopilot_device_win.rs`) whenever `PointerType::Touch` is dispatched.

#### WHAT
* **What is the issue**: Each multitouch frame allocated a `Box<[POINTER_TYPE_INFO]>` on the Rust heap, converted it to a raw pointer via `Box::into_raw()`, passed the pointer to `InjectSyntheticPointerInput`, and then **never freed the allocation**. The `Box::from_raw()` call that would return ownership to Rust — and trigger the destructor — was missing.

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs` inside the `PointerType::Touch` branch of `send_pointer_event`.

#### WHEN
* **When is it triggered**: Every time a `MOVE`, `DOWN`, or `UP` touch event is received from the tablet. In drawing sessions, this leaks memory at up to 120-240 times per second.

#### WHY
* **Why does it happen (Root Cause)**: `Box::into_raw()` transfers heap ownership out of Rust's compiler control into a raw C pointer. Unless explicitly reclaimed using `Box::from_raw()`, that memory block is permanently leaked. Since the Win32 function `InjectSyntheticPointerInput` reads the data synchronously, holding heap ownership was unnecessary.

#### HOW
* **How it was resolved**: Replaced the heap allocation and raw box transfer with a stack borrow using `pointer_type_info_vec.as_mut_ptr()`. The vector is clean-dropped automatically at the end of the execution scope:
  ```rust
  InjectSyntheticPointerInput(self.touch_device_handle, pointer_type_info_vec.as_mut_ptr(), len as u32);
  ```

---

## Chapter 2: Handle Leak: Synthetic Pointer Devices
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: The Windows OS kernel handle table for the Weylus Studio process.

#### WHAT
* **What is the issue**: Raw handles to the synthetic pointer device (`pointer_device_handle`) and touch device (`touch_device_handle`) are allocated via `CreateSyntheticPointerDevice` at startup. However, they were never released upon application shutdown or device drop, leaving active kernel-level leaks.

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs` inside the `WindowsInput` struct wrapper.

#### WHEN
* **When is it triggered**: Whenever a client connects/disconnects, initiating a new `WindowsInput` instantiation. Multiple tablet reconnections quickly inflate leaked OS handle tables.

#### WHY
* **Why does it happen (Root Cause)**: The `WindowsInput` struct lacked a destructor implementation. Raw pointers representing C-style OS handles (`*mut HSYNTHETICPOINTERDEVICE__`) do not implement automatic garbage collection or destructor drop behavior in Rust.

#### HOW
* **How it was resolved**: Implemented the `Drop` trait for `WindowsInput` to guarantee that both device handles are explicitly destroyed using `DestroySyntheticPointerDevice` when the struct goes out of scope:
  ```rust
  impl Drop for WindowsInput {
      fn drop(&mut self) {
          unsafe {
              if !self.pointer_device_handle.is_null() {
                  DestroySyntheticPointerDevice(self.pointer_device_handle);
              }
              if !self.touch_device_handle.is_null() {
                  DestroySyntheticPointerDevice(self.touch_device_handle);
              }
          }
      }
  }
  ```

---

## Chapter 3: Crash Risk: `PointerType::Unknown` Panic
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Any client sending a pointer event with unrecognized or unpopulated device characteristics.

#### WHAT
* **What is the issue**: The match statement for handling inbound `PointerType` contained a placeholder `todo!()` macro for the `Unknown` variant, which triggered a complete application panic.

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs` in `send_pointer_event()`.

#### WHEN
* **When is it triggered**: Triggered when a browser client fails to identify the hardware stylus/mouse type and sends an empty string `""` as the pointer type.

#### WHY
* **Why does it happen (Root Cause)**: `todo!()` expands to `panic!()` in Rust. A network-exposed interface matching client parameters should never contain execution-halting panic macros.

#### HOW
* **How it was resolved**: Replaced `todo!()` with a warning log and an immediate return, gracefully skipping processing of unrecognized input:
  ```rust
  PointerType::Unknown => {
      warn!("Received pointer event with unknown pointer type, ignoring.");
      return;
  }
  ```

---

## Chapter 4: Pressure Range Verification (0-1024)
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Digital artists requiring precise stylus pressure increments inside design tools (Photoshop, Krita).

#### WHAT
* **What is the issue**: The codebase scales pressure using `(event.pressure * 1024f64) as u32`. We investigated whether this scale factor should be raised to a professional grade resolution of `8191`.

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs` in `send_pointer_event()`.

#### WHEN
* **When is it triggered**: Executed whenever pen pressure is active.

#### WHY
* **Why does it happen (Root Cause)**: Microsoft's Win32 API design for `POINTER_PEN_INFO` and `POINTER_TOUCH_INFO` defines pressure as a strictly bounded normalized range from `0` to `1024`. Injecting any value outside this bounds results in clamp validation errors or silent rejection by the Windows kernel.

#### HOW
* **How it was resolved**: Confirmed that the current scale factor of `1024` is the absolute hardware-injection limit of the Windows platform. The architecture is validated and finalized as correct.

---

## Chapter 5: WebSocket Queue & Frame Coalescing
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Stylus users experiencing brush lagging or frame stuttering under high drawing frequencies or transient network congestion.

#### WHAT
* **What is the issue**:
  1. The inbound control event buffer was restricted to `32`, leading to backpressure and socket blocking on high sampling rate stylus strokes.
  2. The outbound queue buffered H.264 video frames alongside control packets. Under network drop conditions, stale video frames filled the buffer queue, causing up to ~500ms of lag.

#### WHERE
* **Where does it occur**: `src/websocket.rs` inside `weylus_websocket_channel`.

#### WHEN
* **When is it triggered**: Active when high frequency stylus movements generate dense streams of pointer packages.

#### WHY
* **Why does it happen (Root Cause)**: The video pipeline treated real-time video frames as reliable sequenced blocks rather than drop-tolerant packets. This lack of differentiation allowed stale visual states to delay newer frames.

#### HOW
* **How it was resolved**:
  - Expanded inbound/outbound queue boundaries to `128` channels.
  - Implemented an **Order-Preserving Frame Coalescing** strategy: if subsequent messages in the queue are video frames, we discard intermediate stale states and deliver only the latest frame. Crucially, the loop stops immediately if any control packet (such as configuration switches) is found, keeping critical setup bounds correct.

---

## Chapter 6: Windows Native Build Environment Issues
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Windows developers compiling the project natively without relying on Linux virtualization layers (WSL).

#### WHAT
* **What is the issue**: `cargo check`/`cargo build` fails due to Git line-ending conversions (CRLF), Git Bash path hijacking, missing NASM assemblers, and LLVM-CUDA compilation failures.

#### WHERE
* **Where does it occur**: Root compilation script `build.rs` and dependencies folder `deps/`.

#### WHEN
* **When is it triggered**: Initiated upon cargo build sequences on fresh Windows checkouts.

#### WHY
* **Why does it happen (Root Cause)**: Upstream build configurations assumed a standard POSIX system with bash launchers, executing scripts that crash under MSVC toolchains and CRLF character rules.

#### HOW
* **How it was resolved**:
  - Enforced LF line endings via Git configurations.
  - Provided a precompiled static library archive under `deps/prebuilt_windows` for MSVC linking.
  - Adapted TypeScript packaging triggers to invoke `npx` dynamically rather than requiring globally mapped compilation environments.

---

## Chapter 7: Dual-Backend Dispatcher Architecture
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: All developers and CI maintainers across Windows, Linux, and macOS platforms.

#### WHAT
* **What is the issue**: The root `build.rs` compile wrapper was a monolithic block of 300+ lines. Edits to target path configurations on Windows frequently introduced compilation regressions on POSIX platforms, creating a high maintenance blast radius.

#### WHERE
* **Where does it occur**: Root `build.rs` compile orchestrator.

#### WHEN
* **When is it triggered**: Triggered at every compilation launch.

#### WHY
* **Why does it happen (Root Cause)**: The project grew by appending OS conditional compilation switches (`cfg!(target_os)`) into a single file, failing to separate platform build pipelines.

#### HOW
* **How it was resolved**: Modularized the compilation system into discrete platform-specific submodules:
  - `build.rs` (Thin dispatcher wrapper)
  - `build/windows.rs` (Direct prebuilt library linker)
  - `build/linux.rs` & `build/macos.rs` (FFmpeg compiler systems)
  - `build/common.rs` (Universal TypeScript compiler & C bindings creator)

---

## Chapter 8: Capability Layer Extraction (`common.rs`)
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Core engine developers adding platform features.

#### WHAT
* **What is the issue**: The shared helper script `build/common.rs` was heavily coupled to OS identity checks (`if target_os ==`), undermining the purpose of modular split-outs.

#### WHERE
* **Where does it occur**: `build/common.rs` core compilation functions.

#### WHEN
* **When is it triggered**: Executed on compilation routines.

#### WHY
* **Why does it happen (Root Cause)**: Platform-neutral logic was making concrete environmental assumptions (e.g., executing `/c npx` on Windows vs `tsc` on Linux) instead of checking abstract capabilities.

#### HOW
* **How it was resolved**: Implemented a flat `BuildCapabilities` struct that shifts OS-aware declarations to the OS-specific modules. `common.rs` now queries features strictly via flat properties without knowing which OS is active:
  ```rust
  pub struct BuildCapabilities {
      pub has_nvenc: bool,
      pub has_vaapi: bool,
      pub has_videotoolbox: bool,
      pub has_mediafoundation: bool,
      pub has_libnpp: bool,
  }
  ```

---

## Chapter 9: Flat Typed Build Capabilities
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Build system maintainers.

#### WHAT
* **What is the issue**: Risk of nested capability struct creep converting compile script code into an over-engineered framework ontology.

#### WHERE
* **Where does it occur**: `build/common.rs`.

#### WHEN
* **When is it triggered**: Active during development iterations of capability properties.

#### WHY
* **Why does it happen (Root Cause)**: Flat booleans were simple but lacked type-safety. However, structuring nested objects creates structural overhead that is unnecessary for build-time compilation.

#### HOW
* **How it was resolved**: Locked the capability architecture to a flat typed structure, replacing boolean flags with simple typed enums (such as `TypeScriptCompilerSource`) and added structural safety rules in [[CONSTRAINTS]].

---

## Chapter 10: Frame Pacing & Timing Resolution
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Windows drawing tablet clients experiencing minor micro-stutters or presentation timeline jitter.

#### WHAT
* **What is the issue**: Mirrored screens stuttered because presentation timelines used milliseconds (`.as_millis()`), which is too coarse under DWM scheduling models. Additionally, frames were timestamped after capture, baking CPU delays into video playback.

#### WHERE
* **Where does it occur**: `src/video.rs` and `src/websocket.rs`.

#### WHEN
* **When is it triggered**: Active during real-time screen streaming.

#### WHY
* **Why does it happen (Root Cause)**: Coarse timing intervals coupled with encoding scheduling jitter disrupted video stream pacing.

#### HOW
* **How it was resolved**:
  - Upgraded video presentation timing base to **microseconds** (`1,000,000` base).
  - Modified FFI signatures to use `int64_t pts`.
  - Moved timestamp generation directly to the **capture boundary** (immediately before calling `recorder.capture()`), isolating visual playback pacing from CPU/GPU encoding overhead.

$$\text{PTS}_{\text{frame}} = \text{Time}_{\text{capture\_boundary}} - \text{Time}_{\text{stream\_start}}$$

---

## Chapter 11: Build Pipeline & Virtual Keyboard Serialization
* **Investigated**: 2026-06-21
* **Resolved**: 2026-06-21
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Developers building the system and tablet users who require type-safe virtual key shortcut custom mappings.

#### WHAT
* **What is the issue**: 
  - **Build compiler side-effects**: Cargo compilation loops initiated redundant `npm install` tasks, causing high compile latencies. Command invocations on Windows also failed due to shell wrapper differences.
  - **Type safety loss**: Virtual keyboard settings were transmitted over WebSocket as loose, typeless raw String variables (`profiles: String`). This increased coordinate mapping failures and was highly error-prone.

#### WHERE
* **Where does it occur**: Build script configuration files (`build/common.rs` and platform modules) and networking/protocol models (`src/protocol.rs`, `src/websocket.rs`, `src/config.rs`).

#### WHEN
* **When is it triggered**: During build execution (`cargo check` or `cargo build`) and during runtime keyboard layout profile loading/saving processes.

#### WHY
* **Why does it happen (Root Cause)**:
  - The build script mixed package management side-effects with compilation, failing to halt explicitly when dependencies were missing.
  - The communication layer relied on raw string payloads to handle key profiles, creating technical debt and forcing serialization logic inside net transport loops.

#### HOW
* **How it was resolved (Code Comparison)**:
  - **Deterministic Build script**: Renamed compilation to `build_web_client` and removed auto-install. Added assertion panics if `node_modules` is missing:
    ```rust
    // BEFORE (unstable, side-effect prone):
    // Spinned cmd /c npm install dynamically during cargo check.
    
    // AFTER (clean, explicit):
    if !node_modules.exists() {
        panic!("www/node_modules missing. Run npm install first.");
    }
    ```
  - **Direct Command fallback**: Configured direct call logic for Windows MSVC to invoke `npm.cmd` / `pnpm.cmd` dynamically without shell wrappers.
  - **Type-Safe Serialization structures**: Created clean `VirtualKey` and `VirtualKeyProfile` structs:
    ```rust
    // BEFORE:
    // SetVirtualKeysProfiles { profiles: String }
    
    // AFTER:
    pub struct VirtualKey {
        pub label: String,
        pub key_code: u16,
    }
    pub struct VirtualKeyProfile {
        pub name: String,
        pub keys: Vec<VirtualKey>,
    }
    ```
  - **Domain Extraction**: Built a dedicated module `src/virtual_keys.rs` to persist configurations cleanly, decoupling `src/websocket.rs` from serialization and IO storage details.

---

## Chapter 12: Evolving to Modular Multi-Device Platform
* **Investigated**: 2026-06-21
* **Status**: 🚀 **Vision Defined.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Professional digital artists, creators, and developers seeking zero-latency multi-display drawing workflows.

#### WHAT
* **What is the issue**: Weylus originally operated as a generic web server accessed via a browser client. Browser engines impose performance bottlenecks (lack of direct Vulkan access, coarse pointer event loop scheduling, and VSync limits), preventing stable 120 FPS performance.

#### WHERE
* **Where does it occur**: Client-side application layer.

#### WHEN
* **When is it triggered**: Active when mirroring and drawing on high refresh-rate screens (90Hz, 120Hz, 144Hz).

#### WHY
* **Why does it happen (Root Cause)**: Browser execution engines add scheduling layers that introduce latency. Additionally, WiFi connections introduce jitter and packet dropouts.

#### HOW
* **Proposed Action**:
  - Migrate client target to a native Android application written in **Kotlin + Jetpack Compose**.
  - Direct hardware decoding via Android `MediaCodec` into a native `SurfaceView`.
  - Establish **USB Cable Connectivity** via automatic ADB reverse port forwarding, bypassing WiFi completely.
  - Achieve a target of **120 FPS** with ultra-low latency:

$$\text{Latency}_{\text{round\_trip}} = T_{\text{capture}} + T_{\text{encode}} + T_{\text{USB\_transfer}} + T_{\text{decode}} + T_{\text{render}} < 8\,\text{ms}$$

---

## Chapter 13: Build Decoupling & Configuration Fallback Safety
* **Investigated**: 2026-07-03
* **Resolved**: 2026-07-03
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Developers compiling Weylus Studio across multiple OS environments, and users saving keyboard profiles without prior config file creation.

#### WHAT
* **What is the issue**:
  1. The shared compilation module `build/common.rs` used hardcoded `cfg!(target_os = "windows")` switches for compiler invocations, violating the decoupled capabilities architecture model.
  2. Cargo did not recursively watch TypeScript/SASS source files inside `www/src/`, which resulted in stale embedded web assets unless `cargo clean` was manually run.
  3. Saving keyboard shortcuts via `save_profiles` in `src/virtual_keys.rs` failed silently if the global `weylus.toml` config file was not yet present.

#### WHERE
* **Where does it occur**: Compile-time build scripts (`build/common.rs`, `build/windows.rs`, `build/linux.rs`, `build/macos.rs`) and configuration persistence (`src/virtual_keys.rs`).

#### WHEN
* **When is it triggered**: Run at every cargo build iteration, and during runtime keyboard profile operations.

#### WHY
* **Why does it happen (Root Cause)**:
  - Hardcoded OS gating inside common helpers breaks the clean separation of concerns.
  - Cargo's default directory monitoring only checks directory metadata updates instead of contents.
  - Fallback logic to build default configurations was missing.

#### HOW
* **How it was resolved**:
  - Injected `shell` and `shell_flag` into the `BuildCapabilities` struct so that OS-specific build dispatchers own their tool invocation wrappers.
  - Configured `std::fs::read_dir` inside `build_web_client` to output recursive `cargo:rerun-if-changed` triggers for all files in `www/src/`.
  - Upgraded `save_profiles` to fallback to a default `Config` struct instance via `unwrap_or_else` if no config file is found:
    ```rust
    let mut config = read_config().unwrap_or_else(|| {
        crate::config::Config {
            access_code: None,
            bind_address: std::net::IpAddr::V4(std::net::Ipv4Addr::new(0, 0, 0, 0)),
            web_port: 1701,
            // ... all fields populated with safe defaults ...
        }
    });
    ```

---

## Chapter 14: mDNS Discovery & USB Auto ADB Reverse
* **Investigated**: 2026-07-03
* **Resolved**: 2026-07-03
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Tablet clients connecting to the host PC via local WiFi (requiring auto-discovery) or direct USB cable connection.

#### WHAT
* **What is the issue**:
  - **No Auto-Discovery**: Clients had to manually discover and type the host PC's dynamic local IP address and port into their browser or client app.
  - **No Automated USB Tunneling**: To achieve the low-latency target (120 FPS), users had to manually run terminal scripts (`adb reverse tcp:1701 tcp:1701`) every time the device was re-connected.

#### WHERE
* **Where does it occur**: Server startup and daemon loops in `src/weylus.rs`, with dependencies declared in `Cargo.toml`.

#### WHEN
* **When is it triggered**: Activated at server startup (`Weylus::start()`), running continuously in a 5-second background loop for ADB detection, and cleaned up at shutdown (`Weylus::stop()`).

#### WHY
* **Why does it happen (Root Cause)**:
  - Local network discovery protocols (mDNS/DNS-SD) were missing from the host PC server.
  - Connection lifecycle events for USB devices were not monitored or automated on the host.

#### HOW
* **How it was resolved**:
  - **mDNS Auto-Discovery**: Integrated `mdns-sd` (version `^0.11`) to broadcast the `_weylus._tcp.local.` service automatically on `config.web_port`.
  - **USB Auto ADB Reverse**: Spawned a background thread in `weylus.rs` that periodically checks for connected Android devices via `adb devices`. If detected, it automatically executes:
    ```bash
    adb reverse tcp:<port> tcp:<port>
    ```
    This bridges the connection over the USB cable instantly.

---

## Chapter 15: OS Decoupling & Unified Wire Protocol
* **Investigated**: 2026-07-03
* **Resolved**: 2026-07-03
* **Status**: ✅ **Resolved.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Future multi-platform clients (such as the Native Android Kotlin Client) connecting to servers on different OS targets, and developers maintaining the cross-platform code compilation.

#### WHAT
* **What is the issue**:
  1. **OS-Dependent Wire Protocol**: The WebSocket `ClientConfiguration` structure had an OS-conditional field `uinput_support` gated with `#[cfg(target_os = "linux")]`, causing different JSON shapes depending on the server host OS.
  2. **Asymmetric Enum Gating**: `InputDeviceType::WindowsInput` was platform-gated on Windows, but the Linux-only `UInputDevice` variant was left ungated, causing compilation warnings and asymmetry.
  3. **Platform-Conditional get_capturables**: The `get_capturables` function accepted arguments only on Linux, necessitating inline conditional compilation directives at all call sites.

#### WHERE
* **Where does it occur**: Wire protocol format (`src/protocol.rs`), input device enumeration (`src/input/device.rs`), capture helpers (`src/capturable/mod.rs`), and server/websocket logic (`src/websocket.rs`, `src/weylus.rs`).

#### WHEN
* **When is it triggered**: Active at client WebSocket handshake initialization, capturable list querying, and device initialization.

#### WHY
* **Why does it happen (Root Cause)**:
  - Gating fields in wire format structs based on compile-time target OS attributes creates runtime API drift across platforms.
  - Divergent helper signatures force conditional complexity onto caller code.

#### HOW
* **How it was resolved**:
  - **Universal Wire Protocol**: Removed `#[cfg]` gating from `uinput_support` in `ClientConfiguration` so that it parses unconditionally. Introduced `ClientCapabilities` with `#[serde(default)]` support to provide a scalable way for future native clients to advertise capabilities like pressure, hover, and virtual keyboards.
  - **Symmetric Enum Gating**: Applied `#[cfg(target_os = "linux")]` to `InputDeviceType::UInputDevice` to align with the Windows variant.
  - **Universal Helper Signature**: Unified `get_capturables` to take `wayland_support: bool` and `capture_cursor: bool` on all platforms, discarding them on non-Linux hosts to keep call sites clean and free of macro switches.

---

## Chapter 16: Phase 3 Android Native Client — Architecture Freeze
* **Investigated**: 2026-07-08
* **Resolved**: 2026-07-08
* **Status**: ✅ **Architecture Frozen.**

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Developers building the Android native client, and end-users on Android tablets who need native stylus pressure and ultra-low-latency frame delivery beyond what a browser WebView can provide.

#### WHAT
* **What is the problem**:
  1. **Browser WebSocket Latency**: The existing TypeScript web client runs in a browser sandbox with additional JS garbage collection pauses, adding 15–40 ms of jitter on top of network latency. This manifests as visible brush lag on fast strokes.
  2. **Stylus API Limitations**: The browser's `PointerEvent.pressure` API does not expose raw stylus tilt azimuth, hover distance, or pen-button states reliably across all Android OEMs. `MotionEvent` on native Android provides all of these.
  3. **No V-Sync Alignment**: The browser MSE player buffers video frames independently of the display's V-Sync signal, causing occasional frame duplication and tearing artifacts.
  4. **Architectural Coupling Risk**: Without defined boundaries, a naive port risks putting WebSocket logic inside Compose composables or calling `Choreographer` from the decoder thread — both of which cause threading bugs and hard-to-test code.

#### WHERE
* **Where does it occur**: The gap exists between the Rust server's streaming WebSocket endpoint (`src/websocket.rs`) and the Android client layer. The existing TypeScript client (`ts/lib.ts`) cannot be used on Android without a browser sandbox.

#### WHEN
* **When is it triggered**: Latency problems appear at stylus speeds above 80 mm/s (typical fast strokes). The V-Sync mismatch appears as dropped or duplicated frames at 60 Hz. The stylus API gap is permanent on Chrome for Android.

#### WHY
* **Why did we architect this way (Root Cause)**:
  - The original Weylus server was designed for a browser client (TypeScript + MSE). A direct port without boundaries would push WebSocket and MediaCodec logic into the same object, making testing impossible and refactoring fragile.
  - Community feedback (ChatGPT architecture review, July 2026) reinforced that `Choreographer` should be an interface boundary, not a concrete call inside the decoder, and that `DisplayCapability` should be a one-time server-push event — not a client-poll.

#### HOW
* **How it was resolved (Architecture Freeze)**:

  **Transport Layer**:
  - Defined `Transport` interface (`net/Transport.kt`) with `connect()`, `sendText()`, `sendBinary()`, and `disconnect()` methods plus `TransportListener` callback interface.
  - Implemented `WebSocketTransport` using OkHttp. Future USB/ADB or QUIC transports can swap in without touching `Session`.

  **Session State Machine**:
  - `SessionState` enum: `Negotiating`, `Streaming`, `Recovering`, `Disconnected`, `Error`. No direct transitions outside `updateState()` guard.
  - `Session` holds `Transport`, `VideoDecoder`, `CoordinateMapper`, and `DeviceCapabilityProvider` as constructor-injected dependencies.
  - `SessionListener` callbacks (`onVideoConfigReceived`, `onStateChanged`, `onVideoFrameReceived`) decouple UI from network events.

  **CoordinateMapper**:
  - Pure Kotlin class with zero Android SDK imports. Accepts `serverWidth`, `serverHeight`, `viewWidth`, `viewHeight` in constructor.
  - `updateViewport()` recomputes the letterbox `Rect` on orientation change.
  - `map(rawX, rawY)` projects raw `MotionEvent` coordinates through the active letterbox into normalized [0.0, 1.0] server space.

  **DeviceCapabilityProvider**:
  - Probes `InputDevice.getDevice()` at runtime to detect `SOURCE_STYLUS`, supported pressure ranges, and `FEATURE_STYLUS_BASED_STYLUS_POINTER`.
  - Exposes `getCapabilities(): ClientCapabilities` to `Session` for handshake negotiation with the server.

  **FrameScheduler**:
  - `FrameScheduler` interface with `scheduleFrame(callback)` and `cancelPending()` decouples decoder from presentation timing.
  - `ChoreographerFrameScheduler` implementation registers a `Choreographer.FrameCallback` on the display V-Sync signal.
  - `MediaCodecDecoder` calls `frameScheduler.scheduleFrame { renderOutputBuffer() }` — never `Choreographer` directly.

  **Protocol Extensions (`src/protocol.rs` + `src/websocket.rs`)**:
  - `DisplayCapability`: Server sends once after handshake, advertising server resolution, color space, and supported input modes.
  - `DisplayChanged`: Server sends on runtime orientation change to trigger `CoordinateMapper.updateViewport()` on the client.
  - Both are added to `MessageOutbound` enum and deserialized in `Session.onTextMessageReceived()` on the Kotlin side.
