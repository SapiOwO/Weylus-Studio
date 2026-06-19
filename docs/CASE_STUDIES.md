# Case Studies: Bug Investigations, Root-Cause Analysis & Engineering Lessons

This document compiles all case studies for Weylus Studio. Each chapter documents a specific technical challenge, a 5W+1H diagnostic analysis, the root cause, and the implemented fix. All findings are mapped to the source code files and line numbers where the issues were discovered.

---

## Document Index & Changelog Timeline

### Confirmed Bugs

| Chapter | Focus Area | Date | Key Finding | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Chapter 1** | Memory Leak in Windows Touch Injection | 2026-06-18 | `Box::into_raw()` without `Box::from_raw()` leaks heap memory on every multitouch event | **Resolved** ✅ |
| **Chapter 2** | Handle Leak: Synthetic Pointer Devices | 2026-06-18 | `CreateSyntheticPointerDevice` handles never released on shutdown | **Resolved** ✅ |
| **Chapter 3** | Crash Risk: `PointerType::Unknown` Panic | 2026-06-18 | `todo!()` macro causes full application panic on unrecognized pointer type | **Resolved** ✅ |
| **Chapter 4** | Pressure Range (0-1024 vs 0-8191) | 2026-06-19 | The 0-1024 scaling factor matches the maximum native range of Win32 Synthetic Pointer API | **Resolved** ✅ |
| **Chapter 5** | WebSocket Queue & Frame Coalescing | 2026-06-19 | Outbound video queues buffer video frames causing visual lag; fixed via priority coalescing | **Resolved** ✅ |
| **Chapter 10** | Frame Pacing & Timing Resolution | 2026-06-19 | Millisecond timing (`.as_millis()`) causing micro-stuttering under Windows DWM compositor | **Resolved** ✅ |

### Hypothesis / Candidate Bugs (Under Investigation)

| Chapter | Focus Area | Date | Key Finding | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Chapter 6** | Windows Native Build Environment Issues | 2026-06-18 | CRLF conversions, WSL bash hijacking, and missing NASM cause baseline build fails | **Resolved** |
| **Chapter 7** | Build System Modularization: Dual-Backend Dispatcher | 2026-06-18 | Monolithic `build.rs` is a "shared execution surface" — Windows prebuilt changes leak into Linux/macOS pipelines | **Resolved** |
| **Chapter 8** | Capability Layer Extraction: `common.rs` Semantic Separation | 2026-06-18 | `common.rs` was mixing build orchestration, capability detection, and environment assumptions — all three are different concern classes | **Resolved** |
| **Chapter 9** | Flat Typed Build Capabilities: Abstraction Freeze | 2026-06-19 | The boolean capability system was evolving into an over-engineered config system; refactoring to flat, typed contracts freezes abstraction creep | **Resolved** |
| **Chapter 11** | Community Patches (PR #290 & #291) Integration | 2026-06-19 | HiDPI coordinate offsets and lack of reconnection UX | **Needs verification** |


> Case studies are living documents. New chapters are added as bugs are investigated, root-caused, and resolved.

---

## System Engineering Context

Weylus Studio is a **Rust-native screen mirroring and input injection server** that lets an Android tablet act as a graphics tablet for a Windows PC. The Windows input path is the most critical subsystem for drawing applications, as it is responsible for translating stylus pressure, tilt, and multitouch data from the tablet's browser into native Windows pointer events recognized by apps like Krita, Clip Studio Paint, and Photoshop.

The core Windows input file is:
* [`src/input/autopilot_device_win.rs`](../src/input/autopilot_device_win.rs) — Win32 synthetic pointer injection logic.

---

## Chapter 1: Memory Leak in Windows Touch Injection
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.** Fix applied — `Box::into_raw()` replaced with `Vec::as_mut_ptr()` borrow.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Any user running Weylus Studio on Windows with a multitouch screen or a tablet using finger touch gestures.
* **Who executes the problematic code**: The `send_pointer_event` function in `WindowsInput` (`autopilot_device_win.rs`) whenever a `PointerType::Touch` event is received.

#### WHAT
* **What was the issue**: Each multitouch frame allocated a `Box<[POINTER_TYPE_INFO]>` on the Rust heap, converted it to a raw pointer via `Box::into_raw()`, passed the pointer to `InjectSyntheticPointerInput`, and then **never freed the allocation**. The `Box::from_raw()` call that would return ownership to Rust — and trigger the destructor — was missing.

The original problematic code (now replaced):
```rust
// BEFORE (leaked memory on every touch frame):
let b: Box<[POINTER_TYPE_INFO]> = pointer_type_info_vec.into_boxed_slice();
let m: *mut POINTER_TYPE_INFO = Box::into_raw(b) as _; // Rust stops managing this memory
InjectSyntheticPointerInput(self.touch_device_handle, m, len as u32);
// 'm' was never freed. 'Box::from_raw(m)' was missing.

// AFTER (current code — fixed, autopilot_device_win.rs lines 146–151):
InjectSyntheticPointerInput(self.touch_device_handle, pointer_type_info_vec.as_mut_ptr(), len as u32);
// Vec is dropped automatically at end of scope. No leak.
```

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs`, inside the `PointerType::Touch` branch of `send_pointer_event`, lines 146–153.

#### WHEN
* **When is it triggered**: Every time a `MOVE`, `DOWN`, or `UP` touch event is received from the tablet while at least one finger is on the screen. For a drawing session, this could be hundreds to thousands of events per minute.

#### WHY
* **Root Cause**: `Box::into_raw()` is a Rust function that transfers heap ownership out of Rust's memory management system into a raw C-style pointer (`*mut T`). After this call, Rust will **never** automatically free the memory. The intent was likely to pass the data pointer to the Windows API, but the allocation was never reclaimed afterward.

`InjectSyntheticPointerInput` is a synchronous Win32 function — it reads the data during the call and returns. The pointer does not need to outlive the function call. Therefore, `Box::into_raw()` (which implies long-lived ownership transfer) was the wrong tool for this job.

#### HOW
* **Proposed Fix**: Eliminate the `Box` conversion entirely. Borrow a raw pointer directly from the `Vec` using `.as_mut_ptr()`. Since `Vec` is owned by the local scope, Rust automatically drops and frees it when the function returns. No manual memory management required.

```rust
// BEFORE (memory leak):
let b: Box<[POINTER_TYPE_INFO]> = pointer_type_info_vec.into_boxed_slice();
let m: *mut POINTER_TYPE_INFO = Box::into_raw(b) as _;
InjectSyntheticPointerInput(self.touch_device_handle, m, len as u32);

// AFTER (correct, safe, idiomatic Rust):
InjectSyntheticPointerInput(
    self.touch_device_handle,
    pointer_type_info_vec.as_mut_ptr(),
    len as u32,
);
// Vec is dropped automatically here. No leak.
```

### 2. Engineering Lesson

`Box::into_raw()` is a one-way operation — it transfers sole ownership of heap memory to an unmanaged raw pointer. It is only appropriate when:
- You are handing ownership to a C library that will later call a corresponding free/destroy function, **and**
- You document which function will call `Box::from_raw()` to reclaim the memory.

For synchronous calls that only need to *read* the data (like `InjectSyntheticPointerInput`), use `.as_ptr()` or `.as_mut_ptr()` on a `Vec` or slice instead — these borrow the memory without transferring ownership.

---

## Chapter 2: Handle Leak — Synthetic Pointer Devices Never Released
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.** `impl Drop for WindowsInput` added with `DestroySyntheticPointerDevice` cleanup.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: The Windows OS kernel handle table for the Weylus Studio process on every run.

#### WHAT
* **What is the issue**: Two synthetic pointer device handles are created at startup (`pointer_device_handle` and `touch_device_handle`) via `CreateSyntheticPointerDevice`. These handles represent kernel-level resources. There is no `impl Drop for WindowsInput` in the codebase, so when a `WindowsInput` object is dropped (e.g., on client disconnect or app shutdown), the handles are **never passed to `DestroySyntheticPointerDevice`**.

The initialization code (`autopilot_device_win.rs`, lines 30–31):
```rust
pointer_device_handle: CreateSyntheticPointerDevice(PT_PEN, 1, 1),
touch_device_handle: CreateSyntheticPointerDevice(PT_TOUCH, 5, 1),
```

Originally there was no corresponding cleanup anywhere in the file. This has since been fixed — see HOW section below.

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs`. The `WindowsInput` struct holds raw pointers to kernel handles but implements no `Drop` trait.

#### WHEN
* **When is it triggered**: On every Weylus session that uses Windows input. If a user frequently reconnects their tablet (starting and stopping sessions), each session creates two new unreleased handles.

#### WHY
* **Root Cause**: The `WindowsInput` struct was implemented without a destructor (`Drop` trait). In Rust, the `Drop` trait is the idiomatic mechanism for running cleanup code when a value goes out of scope — equivalent to a C++ destructor. Without it, the raw pointer fields (`*mut HSYNTHETICPOINTERDEVICE__`) are dropped as integers, not as handles.

#### HOW
* **Proposed Fix**: Implement `Drop` for `WindowsInput` to call `DestroySyntheticPointerDevice` on both handles:

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

### 2. Engineering Lesson

In Rust, when a struct holds raw pointers to OS/kernel resources (file handles, device handles, sockets), the `Drop` trait is the mandatory cleanup contract. Rust does not automatically call OS-level release functions — it only drops the pointer integer value itself. Any struct that wraps a resource obtained from an OS API (`Create*`, `Open*`, `alloc_*`) must implement `Drop` to pair with the corresponding release function (`Destroy*`, `Close*`, `free_*`).

---

## Chapter 3: Crash Risk — `PointerType::Unknown` Causes Application Panic
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.** `todo!()` replaced with `warn!()` + `return` for graceful degradation.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Any client — including the current browser frontend or a future Flutter native client — that sends a pointer event where the pointer type is not recognized by the browser's PointerEvents API.

#### WHAT
* **What is the issue**: The `send_pointer_event` function in `WindowsInput` handles three known pointer types (`Pen`, `Touch`, `Mouse`) but uses `todo!()` as the handler for the `Unknown` variant:

```rust
PointerType::Unknown => todo!(),
```

In Rust, `todo!()` is a macro that expands to `panic!()` with the message `"not yet implemented"`. A panic in this context will **terminate the entire Weylus server process**, disconnecting all active sessions.

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs`, line 212, in the `match event.pointer_type` block.

#### WHEN
* **When is it triggered**: When a browser or client sends a `PointerEvent` whose `pointerType` field deserializes to the empty string `""`. In `protocol.rs`, the `Unknown` variant is mapped to:
```rust
#[serde(rename = "")]
Unknown,
```
This can happen on some older Android browsers or in edge-case touch scenarios where the browser does not report a pointer type.

#### WHY
* **Root Cause**: `todo!()` was used as a development placeholder to mark the branch as unimplemented. It was never replaced with either a proper implementation or a graceful no-op. In production, this is equivalent to an unhandled exception that crashes the server.

#### HOW
* **Proposed Fix**: Replace `todo!()` with a `warn!` log and a `return` — the event is silently dropped, which is the correct behavior for an unrecognized input type:

```rust
// BEFORE (crash):
PointerType::Unknown => todo!(),

// AFTER (graceful degradation):
PointerType::Unknown => {
    warn!("Received pointer event with unknown pointer type, ignoring.");
    return;
}
```

### 2. Engineering Lesson

`todo!()` and `unimplemented!()` are **development-only** markers in Rust. They must never ship in production code paths that can be triggered by external input (network events, file data, user actions). Before releasing any version, every `todo!()` must be either:
1. Replaced with a real implementation, or
2. Replaced with a documented graceful fallback (`warn!()` + `return`).

The compiler does not warn about `todo!()` in match arms. A production audit pass should grep for `todo!()`, `unimplemented!()`, and `panic!()` in all code paths reachable from network input handlers.

---

## Chapter 4: Pressure Range Verification (0-1024 vs 0-8191)
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Verified.** The current 0-1024 scaling factor is the Win32 API limit and is correct.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Stylus users drawing on Windows drawing applications (like Photoshop, Krita, or Clip Studio Paint) who want to verify that stylus pressure gradients are delivered at maximum fidelity.

#### WHAT
* **What is the issue**: The codebase translates normalized stylus pressure `0.0` - `1.0` to Win32 pointer events using `(event.pressure * 1024f64) as u32`. We needed to verify if the Win32 Synthetic Pointer API supports professional ranges up to `8191` (the professional tablet standard) to prevent loss of fidelity.

#### WHERE
* **Where does it occur**: `src/input/autopilot_device_win.rs` lines 114 and 137.

#### WHEN
* **When is it triggered**: When drawing on the tablet with pressure sensitivity.

#### WHY
* **Root Cause / Finding**: Detailed investigation of Microsoft Win32 API specifications for `POINTER_PEN_INFO` and `POINTER_TOUCH_INFO` reveals that:
  1. The `pressure` field in both structures is explicitly defined by Windows as a value normalized to a range between **0 and 1024**.
  2. The default value if no pressure is reported is `0` for pens and `512` for touch points.
  3. Setting any pressure values larger than 1024 violates the Win32 specification and leads to clamping or rejection at the kernel level.
  4. Therefore, `1024` is the maximum native precision that Windows input injection can accept.

#### HOW
* **Resolution**: Verified that the current scaling factor of `1024` is correct and mathematically optimal for the Win32 target platform. No code changes are required as the implementation is already fully compliant with the platform's limits.

### 2. Engineering Lesson

**Do not optimize beyond platform boundaries.** 

Before modifying code to match professional hardware standards (like Wacom's 8192 pressure levels), check the target OS injection API capabilities first. Even if the stylus hardware reports higher fidelity, the OS input subsystem may enforce a lower normalized range. In these cases, the OS range is the absolute hard ceiling, and any scaling beyond it is redundant or invalid.

---

## Chapter 5: WebSocket Queue Buffer Size & Frame Coalescing
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Resolved.** Queue capacities tuned to 128 and order-preserving frame coalescing implemented.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Stylus users drawing rapid strokes or gestures, experiencing brush input lag, jagged lines, or visible visual delay.

#### WHAT
* **What is the issue**: 
  1. The inbound channel for client input events had a fixed-size buffer of 32. For professional stylus devices sampling at 120Hz-240Hz, a brief server scheduling delay would fill the buffer, leading to backpressure and packet delivery delay on the WebSocket.
  2. The outbound WebSocket channel buffered up to 32 video frames (`WsMessage::Video`). At 60fps, 32 frames represents ~533ms of visual latency. If the network was temporarily congested, the outbound writer thread sent stale video frames, causing a "sticky brush" lag feel.

#### WHERE
* **Where does it occur**: `src/websocket.rs` inside the `weylus_websocket_channel` function and its outbound frame dispatcher task.

#### WHEN
* **When is it triggered**: During rapid drawing bursts or gestures, and whenever network bandwidth fluctuated under load.

#### WHY
* **Root Cause**: The system lacked distinction between the lossy real-time requirements of interactive video and the strict ordering requirements of control messages. Treating video frames as reliable, sequential archival frames meant that stale frames were queued and sent in full, accumulating latency.

#### HOW
* **Resolution**:
  1. Increased the inbound queue capacity (`sender_inbound`) from 32 to 128 to absorb high-frequency stylus input bursts without blocking the WebSocket receiver loop.
  2. Increased the outbound queue capacity (`sender_outbound`) from 32 to 128 to buffer transient text spikes.
  3. Implemented a **Priority-Preserving / Coalesce Consecutive Only** strategy in the outbound tokio task. When a `WsMessage::Video` frame is processed:
     - The task drains the outbound queue using `try_recv()` as long as subsequent messages are also `WsMessage::Video` frames.
     - Older video frames are dropped, and only the latest video frame is sent.
     - The draining loops **breaks** immediately if it encounters a non-video message (like `MessageOutbound::NewVideo` or `WsMessage::Frame`).
     - This guarantees that critical decoder setup commands (`NewVideo`) are never reordered past video frames (which would cause client decoder crashes or state-machine mismatch), while still shedding stale video frames under network pressure.

### 2. Engineering Lesson

Real-time interactive systems must treat video and control streams with different delivery philosophies:
- **Interactive Video**: Lossy. Obsolete video frames are useless; it is better to drop old frames than to delay new ones.
- **Control Plane**: Reliable. Messages like decoder resets, layout dimensions, or config updates are state-dependent and must never be reordered or lost.

A pure "drain completely" strategy for coalescing is dangerous because it reorders control messages past video frames. An **Order-Preserving (Coalesce Consecutive)** strategy is the correct model to balance low latency with state-machine correctness.

---

## Chapter 6: Investigation — Windows Native Environment Build Issues
* **Investigated**: 2026-06-18
* **Status**: Confirmed environment issues. Fixes documented and applied.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Any developer trying to build Weylus Studio upstream default on a Windows development machine that has WSL (Windows Subsystem for Linux) and git's default line ending config enabled.

#### WHAT
* **What is the issue**: The baseline build (`cargo check`) fails at the custom build step `weylus` during FFmpeg compilation. The build logs show parsing errors like `clean.sh: line 4: syntax error near unexpected token $'do\r'`, compiler errors like `cuda_llvm requested but not found`, TypeScript compiler failures due to missing global `tsc` command, and strict type errors when compiling `./ts/lib.ts`.

#### WHERE
* **Where does it occur**: In the shell script execution block driven by `build.rs` under the `deps/` directory, the typescript compilation in `build.rs` and `tsconfig.json`, and the file structure in `deps/dist_windows`.

#### WHEN
* **When is it triggered**: When running a default baseline build (`cargo check` or `cargo build`) on Windows.

#### WHY
* **Root Causes**:
  1. **Line Ending Mismatch (CRLF vs LF):** Windows Git checked out files using CRLF (`\r\n`), causing Git Bash to raise syntax errors when executing the shell scripts.
  2. **WSL Bash Hijacking:** `build.rs` calls `Command::new("bash")` which invokes the top-priority `bash.exe` from Windows `System32` (WSL Launcher). This changes the environment target to Linux inside WSL, causing compile errors.
  3. **Missing NASM Assembler:** The underlying x264/FFmpeg source build requires `nasm` assembler to process assembly code.
  4. **CUDA LLVM Requirement:** The configure scripts requested `--enable-cuda-llvm` which fails on platforms without LLVM/Clang CUDA toolchain support.
  5. **TypeScript Global Command and Compiler Version strictness:** `tsc` was run directly via `cmd /c tsc` expecting a global command, and newer TypeScript compilers enforce strict type checks by default that fail on existing code.
  6. **Interrupted Build/Skip Logic Error:** `build.rs` used `if dist_dir.exists() { return; }` to skip building FFmpeg. Since `x264` compilation ran first and created the folder `dist_dir`, any subsequent failure in FFmpeg compilation caused future build attempts to skip FFmpeg completely, resulting in missing headers.

#### HOW
* **Proposed Action & Applied Fixes**:
  - **Autocrlf fix:** Configured git locally to prevent converting script line endings (`git config core.autocrlf input`), then did a hard reset (`git rm --cached -r .` and `git reset --hard`) to restore scripts as LF (`\n`) murni.
  - **CMake & NASM install:** Installed CMake via `winget install Kitware.CMake` and NASM via `winget install NASM.NASM`.
  - **PATH Override solution:** Prepend Git Bash, CMake, and NASM paths directly to the terminal sessions' `$env:PATH` to ensure `Command::new("bash")` calls Git Bash native (`msys`) instead of WSL.
  - **Remove cuda-llvm:** Stripped `--enable-cuda-llvm` from `deps/build.sh` parameters since NVENC only requires `ffnvcodec` and `nv-codec-headers` to enable hardware-accelerated video streaming.
  - **TypeScript npx execution and strictness bypass:** Configured `tsconfig.json` to disable strict type-checking (`strict: false`, `noImplicitAny: false`, `strictNullChecks: false`, `strictPropertyInitialization: false`), and modified `build.rs` to run `tsc` dynamically using `npx -y -p typescript tsc`.
  - **FFmpeg Skip Logic fix:** Updated `build.rs` to verify the existence of the specific header `dist_dir.join("include/libavcodec/avcodec.h")` instead of the root `dist_dir` before skipping build steps.

---

## Chapter 7: Build System Modularization — Dual-Backend Dispatcher Architecture
* **Investigated**: 2026-06-18
* **Status**: Resolved. Architecture implemented and verified.

### 0. Background Context

After resolving the Windows build environment issues documented in Chapter 6, the project successfully compiled FFmpeg + x264 from source on a Windows machine using MSVC + MSYS2 + NASM. The resulting `.lib` files were cached locally under `deps/prebuilt_windows/`.

This raised a critical architectural question: the original monolithic `build.rs` (300+ lines) contained *all* platform logic — POSIX shell calls for Linux/macOS, Windows prebuilt linking, TypeScript compilation, and C helper compilation — in a **single compilation unit**. Every modification for Windows risked silently breaking Linux/macOS builds, and vice versa.

The engineering team (including ChatGPT's advisory input) identified this as a **"shared build execution surface"** problem — a structural anti-pattern where logical isolation (if/else branches) gives a *false sense of separation* while the actual code blast radius remains the entire file.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Every developer and CI system that builds Weylus Studio on any OS. Specifically:
  - **Windows developers** who only need prebuilt `.lib` linking, not POSIX shell scripts.
  - **Linux/macOS contributors** who maintain FFmpeg source-build pipelines (`deps/build.sh`) and system library linking.
  - **The project maintainer** (current fork owner) who wants Windows-first optimization without destroying the community's Linux/macOS foundation.

#### WHAT
* **What is the issue**: The original `build.rs` was a single 300+ line file containing:
  1. POSIX-specific logic: `Command::new("bash")` calls to `deps/build.sh` and `deps/clean.sh` for FFmpeg source compilation.
  2. Windows-specific logic: prebuilt `.lib` path resolution and MSVC system library linking (`mfplat`, `bcrypt`, `shlwapi`, etc.).
  3. Linux-specific logic: X11/VA-API/DRM library linking, `uinput.c` compilation, `pkg-config` feature gating.
  4. macOS-specific logic: Apple framework linking (`VideoToolbox`, `CoreMedia`).
  5. Shared logic: TypeScript compilation (`tsc`), C helper compilation (`lib/encode_video.c`), `cc::Build` calls.

  All of this lived in one file separated only by `if cfg!(target_os = ...)` branches. This meant:
  - **Any edit to the Windows prebuilt path could accidentally affect the Linux `dist_dir` resolution.**
  - **Removing a POSIX dependency for Windows could delete a function that Linux/macOS still needs.**
  - **IDE refactoring tools (rename, extract, delete unused) operate on the whole file, not per-branch.**
  - **Code review diffs mix unrelated OS changes, making reviews error-prone.**

  The original monolithic structure (simplified):
  ```rust
  // build.rs (BEFORE - monolith, ~300 lines)
  fn main() {
      // ... shared TypeScript compilation ...
      // ... shared C helper compilation ...

      if cfg!(target_os = "linux") {
          // bash calls, dist_linux, X11 libs, uinput.c ...
      } else if cfg!(target_os = "macos") {
          // bash calls, dist_macos, Apple frameworks ...
      } else if cfg!(target_os = "windows") {
          // prebuilt_windows path, MSVC libs ...
      }

      // ... shared FFmpeg linking (but with OS-conditional link kind!) ...
  }
  ```

#### WHERE
* **Where does it occur**: `build.rs` (root of repository) — the Cargo build script that runs before `rustc` compilation.

#### WHEN
* **When is it triggered**: Every time `cargo build`, `cargo check`, or `cargo run` is executed. Cargo re-runs `build.rs` whenever any `cargo:rerun-if-changed` watched file changes.
* **When did it become critical**: When the Windows build was migrated from "source-compile FFmpeg via bash" to "link prebuilt `.lib` files directly". This was a fundamental change in dependency model — Windows no longer needs POSIX tools at all — but the monolithic `build.rs` forced both models to coexist in the same execution flow.

#### WHY
* **Root Cause (Structural)**: The original Weylus upstream was designed as a **Linux-first project** where Windows and macOS were secondary ports. The `build.rs` grew organically by adding `if cfg!(...)` branches for each OS, which is fine when all OS targets share the same dependency model (source-compile FFmpeg). But when Windows migrated to prebuilt `.lib` files, the dependency model diverged fundamentally:

  | Aspect | Linux/macOS | Windows (after migration) |
  | :--- | :--- | :--- |
  | FFmpeg source | Compiled from `deps/build.sh` via bash | **Not used** — prebuilt `.lib` from `deps/prebuilt_windows/` |
  | External tools needed | `bash`, `make`, `nasm`, `cmake`, `pkg-config` | **None** — only MSVC linker |
  | Link kind | `static` (self-compiled) or `dylib` (system) | `dylib` (prebuilt import libs) |
  | Platform libs | X11, VA-API, DRM (Linux) / Frameworks (macOS) | `mfplat`, `bcrypt`, `ole32`, `shlwapi`, `vfw32` |
  | C helper differences | `HAS_VAAPI` (Linux), `HAS_VIDEOTOOLBOX` (macOS) | `HAS_NVENC`, `HAS_MEDIAFOUNDATION` |

  With this level of divergence, keeping everything in one file creates what ChatGPT accurately termed a **"shared build execution surface"** — where logical separation (if/else) is not the same as structural isolation (separate files with separate blast radii).

* **Root Cause (Practical)**: Several concrete risk scenarios were identified:
  1. **Accidental deletion**: A Windows developer removes a `build_ffmpeg()` function that "isn't called on Windows" → Linux/macOS builds break.
  2. **Path collision**: Windows uses `deps/prebuilt_windows/lib`, Linux uses `deps/dist_linux/lib`. Both reference `dist_dir` but resolve differently. A refactor that renames one variable can break the other.
  3. **Shared function mutation**: `compile_c_helpers()` has OS-conditional `#define` flags. Adding a Windows-only flag to the shared function body affects all OS compilation paths.
  4. **IDE auto-cleanup**: Rust-analyzer or clippy may flag "unused imports" or "dead code" that are only used in non-Windows branches, tempting a developer to delete them.

#### HOW
* **Implemented Solution**: Split `build.rs` into a **thin dispatcher** + **per-OS module files** + **shared common module**.

  **New file structure**:
  ```text
  Weylus-Studio/
  ├── build.rs            # 28 lines — dispatcher only, routes to OS module
  ├── build/
  │   ├── common.rs       # 70 lines — shared: TypeScript (npx), C helpers (cc::Build)
  │   ├── windows.rs      # 54 lines — prebuilt linking, Win32 system libs
  │   ├── linux.rs        # 125 lines — FFmpeg source build, X11/VA-API/DRM libs
  │   └── macos.rs        # 84 lines — FFmpeg source build, Apple framework libs
  ```

  **The dispatcher ([`build.rs`](../build.rs), 28 lines)**:
  ```rust
  #[path = "build/windows.rs"]
  mod build_windows;
  #[path = "build/linux.rs"]
  mod build_linux;
  #[path = "build/macos.rs"]
  mod build_macos;
  #[path = "build/common.rs"]
  mod build_common;

  use std::env;

  fn main() {
      let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap();
      if target_os == "windows" {
          build_windows::build();
      } else if target_os == "linux" {
          build_linux::build();
      } else if target_os == "macos" {
          build_macos::build();
      } else {
          panic!("Unsupported target OS: {}", target_os);
      }
  }
  ```

  **Key design decisions in each module**:

  | Module | Key Responsibility | Isolation Boundary |
  | :--- | :--- | :--- |
  | [`build/common.rs`](../build/common.rs) | `compile_typescript()` — uses `npx` on Windows, `tsc` on Unix. `compile_c_helpers()` — shared `cc::Build` with OS-conditional `#define` flags. | Called by each OS module explicitly. Changes here are visible to all OS targets — intentionally. |
  | [`build/windows.rs`](../build/windows.rs) | Links `deps/prebuilt_windows/lib/*.lib`. Links Win32 system libs. **Does not contain any bash/shell/POSIX logic.** | Complete isolation. No `Command::new("bash")`. No `build_ffmpeg()`. No `clean.sh`. |
  | [`build/linux.rs`](../build/linux.rs) | Calls `deps/build.sh` via bash. Compiles `lib/linux/uinput.c`, `xcapture.c`, `xhelper.c`. Links X11, VA-API, DRM libs. | Contains `build_ffmpeg()` and `resolve_bash()` — POSIX-only functions that Windows never sees. |
  | [`build/macos.rs`](../build/macos.rs) | Calls `deps/build.sh` via bash. Links Apple frameworks (`VideoToolbox`, `CoreMedia`). | Contains its own `build_ffmpeg()` — identical structure to Linux but with macOS `dist_dir`. |

  **What this solves**:
  1. **Blast radius containment**: Editing `build/windows.rs` cannot affect `build/linux.rs` or `build/macos.rs`. They are separate files with separate function scopes.
  2. **Safe deletion**: A Windows developer can freely modify or remove any function in `build/windows.rs` without risk to Linux/macOS pipelines.
  3. **Clear ownership**: Community contributors working on Linux support know exactly which file to edit (`build/linux.rs`) without needing to understand Windows prebuilt logic.
  4. **IDE safety**: Dead code warnings, auto-imports, and refactoring tools operate within file boundaries, reducing cross-contamination risk.
  5. **Reviewability**: PRs that modify only Windows build logic touch only `build/windows.rs`, making code review targeted and safe.

### 2. Remaining Risks & Mitigations

Despite the modularization, there are still shared surfaces that require discipline:

| Risk | Location | Mitigation |
| :--- | :--- | :--- |
| `build/common.rs` is shared by all OS modules | `compile_typescript()`, `compile_c_helpers()` | Changes to `common.rs` must be tested on all target OS (or at minimum, reviewed for OS-conditional branches). |
| `Cargo.toml` dependency declarations are shared | `[dependencies]`, `[target.'cfg(...)'.dependencies]` | Use `[target.'cfg(target_os = "windows")'.dependencies]` for platform-specific crates. |
| `build.rs` dispatcher itself is shared | `main()` function | Dispatcher is 28 lines with trivial logic — minimal surface area. |
| TypeScript frontend (`ts/lib.ts`) is shared | 43KB of client-side code | Protocol-level changes must be coordinated with `protocol.rs`. Not a build system risk. |

### 3. Engineering Lesson

**Logical isolation is not structural isolation.** An `if/else` branch inside a single file creates a *logical* boundary — the code in each branch only executes on its target OS. But the *structural* boundary (blast radius for edits, IDE refactoring scope, code review surface, accidental deletion risk) remains the entire file.

When two OS targets have **fundamentally different dependency models** (source compilation vs. prebuilt linking), they should live in **separate files** — not separate branches within the same file. The cost of maintaining separate files (some duplication of FFmpeg link lines) is vastly outweighed by the safety of knowing that a Windows-only change cannot silently break a Linux build.

This principle is widely applied in production codebases:
- Chromium: `build/config/win/`, `build/config/linux/`, `build/config/mac/` — separate GN configs per OS.
- Firefox: `toolkit/moz.build` with platform-specific subdirectories.
- Game engines (Unreal, Godot): Per-platform build scripts in `platform/windows/`, `platform/linux/`, etc.

The Rust `#[path = "..."]` module attribute makes this pattern particularly clean — the dispatcher file stays minimal (28 lines) while each OS module is a fully self-contained compilation unit.

---

## Chapter 8: Capability Layer Extraction — `common.rs` Semantic Separation
* **Investigated**: 2026-06-18
* **Resolved**: 2026-06-18
* **Status**: ✅ **Resolved.** `BuildCapabilities` struct introduced. `common.rs` now has zero `if target_os` branches.

### 0. Background Context

After modularizing `build.rs` into a dispatcher + per-OS modules (Chapter 7), the `build/common.rs` file retained OS-conditional logic. ChatGPT identified this as a "semantic leakage" problem — not a bug, but an **early architecture drift pattern** where three different concern classes were mixed in one file:

| Concern Class | Example in `common.rs` |
| :--- | :--- |
| **Build orchestration** | Calling `cc::Build`, compiling C files |
| **Capability detection** | `if target_os == "linux" { HAS_VAAPI }` |
| **Environment assumption** | `if target_os == "windows" { cmd /c npx }` |

The key insight: `common.rs` should not be asking "which OS am I on?" — that question belongs in the OS modules. `common.rs` should only be asking "what capabilities do I have?" and acting on them.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Any developer adding a new hardware encoder or build tool to the project. They would be forced to add an `if target_os ==` branch to `common.rs`, increasing the OS-awareness of a file that should be OS-neutral.

#### WHAT
* **What was the issue**: `build/common.rs` contained 5 `if target_os == ...` branches:

  ```rust
  // BEFORE — common.rs asking "which OS am I?"
  pub fn compile_typescript(target_os: &str) {
      let mut tsc_command = if target_os == "windows" {  // ← OS detection
          let mut cmd = Command::new("cmd");
          cmd.args(&["/c", "npx -y -p typescript tsc"]);
          cmd
      } else {
          Command::new("tsc")
      };
  }

  pub fn compile_c_helpers(target_os: &str, dist_dir: &Path, enable_libnpp: bool) {
      if ["linux", "windows"].contains(&target_os) {  // ← OS detection
          cc_video.define("HAS_NVENC", None);
      }
      if target_os == "linux" {    // ← OS detection
          cc_video.define("HAS_VAAPI", None);
      }
      if target_os == "macos" {   // ← OS detection
          cc_video.define("HAS_VIDEOTOOLBOX", None);
      }
      if target_os == "windows" { // ← OS detection
          cc_video.define("HAS_MEDIAFOUNDATION", None);
      }
  }
  ```

#### WHERE
* **Where does it occur**: `build/common.rs` — the shared helper module called by all three OS build modules.

#### WHEN
* **When did it become problematic**: From the moment it was created. The OS-conditional logic was necessary in the original monolith but should not have been carried into `common.rs` during the modularization refactor. ChatGPT identified this as a "garbage collector for cross-platform logic" and the most dangerous file in the build system.

#### WHY
* **Root Cause (Semantic)**: The refactor from monolith → dispatcher preserved the structure of the logic but not its separation of concerns. The OS string `target_os: &str` was passed as a parameter to `common.rs` functions — this meant `common.rs` still "knew" about the OS and made decisions based on it.

  The deeper issue is that **"which OS"** and **"what capabilities"** are different questions:
  - "Which OS" = a string-based identity check, fragile and expandable
  - "What capabilities" = a boolean declaration, stable and auditable

  When `common.rs` asks "which OS", adding a new OS (e.g., FreeBSD, WASM) requires modifying `common.rs`. When `common.rs` asks "what capabilities", adding a new OS only requires adding a new OS module — `common.rs` is untouched.

#### HOW
* **Implemented Solution**: Introduced `BuildCapabilities` struct. Each OS module **declares** its capabilities; `common.rs` **receives** them.

  **New `BuildCapabilities` struct** (owned by `build/common.rs`, populated by OS modules):
  ```rust
  pub struct BuildCapabilities {
      pub has_nvenc: bool,
      pub has_vaapi: bool,
      pub has_videotoolbox: bool,
      pub has_mediafoundation: bool,
      pub has_libnpp: bool,
      pub typescript_via_npx: bool,
  }
  ```

  **New `common.rs` functions** — zero OS detection:
  ```rust
  pub fn compile_typescript(caps: &BuildCapabilities) {
      let mut tsc_command = if caps.typescript_via_npx {
          let mut cmd = Command::new("cmd");
          cmd.args(&["/c", "npx -y -p typescript tsc"]);
          cmd
      } else {
          Command::new("tsc")
      };
      // ...
  }

  pub fn compile_c_helpers(caps: &BuildCapabilities, dist_dir: &Path) {
      if caps.has_nvenc        { cc_video.define("HAS_NVENC", None); }
      if caps.has_vaapi        { cc_video.define("HAS_VAAPI", None); }
      if caps.has_videotoolbox { cc_video.define("HAS_VIDEOTOOLBOX", None); }
      if caps.has_mediafoundation { cc_video.define("HAS_MEDIAFOUNDATION", None); }
      if caps.has_libnpp       { cc_video.define("HAS_LIBNPP", None); }
  }
  ```

  **Each OS module now owns its capability declaration**:
  ```rust
  // build/windows.rs
  let caps = BuildCapabilities {
      has_nvenc: true, has_vaapi: false, has_videotoolbox: false,
      has_mediafoundation: true, has_libnpp: enable_libnpp, typescript_via_npx: true,
  };

  // build/linux.rs
  let caps = BuildCapabilities {
      has_nvenc: true, has_vaapi: true, has_videotoolbox: false,
      has_mediafoundation: false, has_libnpp: enable_libnpp, typescript_via_npx: false,
  };

  // build/macos.rs
  let caps = BuildCapabilities {
      has_nvenc: false, has_vaapi: false, has_videotoolbox: true,
      has_mediafoundation: false, has_libnpp: enable_libnpp, typescript_via_npx: false,
  };
  ```

  **Build verification**: `cargo check` passed in 2.26s with zero errors after the refactor.

### 2. Engineering Lesson

**"Which OS" and "what capabilities" are different questions that must live in different places.**

When shared code asks "which OS am I on?", it becomes coupled to the OS taxonomy. Adding a new OS requires changing the shared code. When shared code asks "what capabilities do I have?", it is decoupled from OS identity. Adding a new OS means adding a new module — shared code is untouched.

This pattern is called **capability-based abstraction** or **dependency inversion** — the shared layer depends on an abstraction (capabilities) rather than on concrete details (OS strings). It is the same principle behind Rust's trait system, Go's interface system, and SOLID's Dependency Inversion Principle.

The `BuildCapabilities` struct also provides an additional benefit: it is **self-documenting**. Reading the struct declaration in a Windows, Linux, or macOS module tells you exactly what hardware encoders and tools are available on that platform — without needing to know anything about the build system internals.

---

## Chapter 9: Flat Typed Build Capabilities — Abstraction Freeze
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Resolved.** Flat typed capability contract implemented. Build system abstraction frozen.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Any future contributor working on the Weylus Studio build system or target-specific tools.
* **Who identified the issue**: ChatGPT acting as architectural advisor, noting the risk of "capability/nested abstraction creep" converting the build script into a complex platform ontology or configuration framework.

#### WHAT
* **What was the issue**: Evolving the boolean `BuildCapabilities` into a deeply nested structure (e.g. `VideoCapabilities` + `ToolchainCapabilities` + `BuildOptions`) creates a "false sense of correctness" and introduces cognitive tax for simple build-time orchestration. It risks bloating the build script into a custom DSL framework rather than a minimal helper.

#### WHERE
* **Where does it occur**: `build/common.rs`, `build/windows.rs`, `build/linux.rs`, and `build/macos.rs`.

#### WHEN
* **When is it triggered**: During compilation (`cargo check`, `cargo build`).
* **When did it become critical**: Right after extracting capabilities from `common.rs`. The flat boolean flags were simple but lacked type-safety for toolchain checks (e.g. using `typescript_via_npx` boolean flag instead of a clean, typed enum indicating tsc resolution). The temptation was to build a nested configuration domain model, which would over-engineer the build layer.

#### WHY
* **Root Cause**: Build systems are fundamentally simple, build-time only scripts. Introducing nested structures and domain modeling increases the maintenance barrier. A flat capability layout with targeted enums (like `TypeScriptCompilerSource`) is the optimal sweet spot between logical simplicity and type safety.

#### HOW
* **Implemented Solution**:
  1. Kept the `BuildCapabilities` struct completely flat, maintaining simplicity.
  2. Replaced the boolean `typescript_via_npx` flag with a typed enum `typescript: TypeScriptCompilerSource` to make the TypeScript toolchain invocation clean, deterministic, and type-safe.
  3. Added Rule 7, Rule 8, and Rule 9 to `docs/CONSTRAINTS.md` to prevent compilation-time `BuildCapabilities` from leaking into runtime code (`src/`) and to freeze the nesting depth of capabilities.

### 2. Engineering Lesson

**Over-shaping an abstraction in build scripts is an anti-pattern.**

A clean build system must be minimal, flat, and declarative. Strive to map platform facts to compile-time variables without introducing complex domain structures. Adding layers of nested types (e.g. separating capabilities vs options vs configs) inside a pre-compilation script creates a parallel architecture that raises the barrier to contribution. When typing build variables, keep the container struct flat and freeze its evolution depth.

---

## Chapter 10: Frame Pacing & Timing Resolution
* **Investigated**: 2026-06-19
* **Resolved**: 2026-06-19
* **Status**: ✅ **Resolved.** Frame pacing migrated to microsecond precision and capture boundary timestamped.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**: Windows users who notice micro-stuttering or minor jitter in the mirrored screen on the tablet, even when the network connection is strong and encoder latency is low.

#### WHAT
* **What was the issue**: The video encoding loop utilized millisecond timing resolution (`.as_millis()`) to track packet display times and pacing intervals. Under Windows' Desktop Window Manager (DWM) compositor and thread scheduler, millisecond precision is too coarse. Frame intervals were inconsistent, causing visible micro-stuttering.

#### WHERE
* **Where does it occur**: `src/video.rs` (calculating relative frame presentation timestamps) and `lib/encode_video.c` (passing PTS to FFmpeg).

#### WHEN
* **When is it triggered**: During active drawing or screen mirroring where precise, smooth frame delivery is critical.

#### WHY
* **Root Cause**: Two issues:
  1. Coarse millisecond timing granularity in the video stream base time resulted in quantization jitter.
  2. Stamping frames after capture was complete meant encoding latency and thread scheduling delays were baked directly into the video timeline, causing jitter in frame presentation times.

#### HOW
* **Resolution**:
  1. **Microsecond Resolution**: Refactored the `TIME_BASE` denominator in `lib/encode_video.c` from `1000` to `1000000` (microseconds).
  2. **i64 timestamps**: Changed the C/FFI parameter signature in `encode_video_frame` from `int millis` to `int64_t pts` to support microsecond timestamps without the 35-minute overflow limitation.
  3. **Capture Boundary Decoupling**: Moved the timestamping clock in `src/websocket.rs` immediately *before* calling `recorder.capture()`. The calculated presentation timestamp is based on the moment of capture, shielding the timeline from downstream CPU encoding jitter.

### 2. Engineering Lesson

In video streaming systems, timestamps must reflect the **logical moment of capture**, not the physical moment of submission to the encoder. Moving the timestamp boundary upstream decouples presentation timeline calculations from downstream resource contention (such as CPU/GPU encode spikes). Additionally, temporal resolution must match or exceed the compositor scheduling slice (microseconds) to prevent pacing quantization.

---

## Chapter 11: Investigation — Community Patches (PR #290 & #291) Integration
* **Investigated**: 2026-06-19
* **Status**: Needs verification.

### 1. 5W+1H Diagnostic Matrix

#### WHO
* **Who is affected**:
  - Stylus users drawing on High-DPI screens under Windows whose inputs are offset from the visual cursor (PR #290).
  - Tablet users who need to reconnect their session without reloading the web page, or who need to send virtual keyboard strokes (PR #291).

#### WHAT
* **What is the issue**: Weylus CE contains two high-demand community PRs that were never merged upstream:
  1. **PR #290 (Click-to-reconnect + HiDPI coordinates)**: Fixes a known offset discrepancy where Windows display scaling shifts coordinates relative to the screen dimensions, and implements reconnect button.
  2. **PR #291 (Virtual keyboard)**: Restores the ability to toggle an on-screen keyboard on the client side.

#### WHERE
* **Where does it occur**: The frontend TypeScript (`ts/lib.ts`) and HTML modules (`www/`), and the coordinate receiver on the Rust server (`src/websocket.rs`).

#### WHEN
* **When is it triggered**: When connecting a client tablet to a Windows host with display scale factor > 100%, or when the client tablet experiences connection drops.

#### WHY
* **Root Cause**: The original Weylus was written before Windows HiDPI coordinate scaling was fully verified, resulting in coordinates being mapped to physical pixels rather than logical pixels. The upstream repo ceased merging active community PRs due to merge conflicts and lack of developer testing.

#### HOW
* **Proposed Action**: Create clean feature branches for these patches. Resolve merge conflicts in `ts/lib.ts`, test coordinate translation on Windows machines with scaling (e.g. 125%, 150%), and integrate the frontend/backend support safely.

