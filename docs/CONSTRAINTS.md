# Architecture Constraint Layer — Anti-Regression Design Rules

This document defines **enforceable structural rules** that prevent the Weylus Studio codebase from drifting into a "cross-platform in theory, Windows in practice" state. These constraints protect the long-term health of both the Windows-optimized primary path and the community's Linux/macOS foundation.

> **Why this document exists**: After modularizing the build system into a dual-backend dispatcher (see [[CASE_STUDIES#Chapter 7 Dual-Backend Dispatcher Architecture]]), the project reached a structurally sound state. But **structure alone does not prevent drift** — only enforced constraints do. This document defines those constraints.

---

## Document Index

| Section | Purpose | Audience |
| :--- | :--- | :--- |
| **Section 1** | Core Contract Layer — what MUST stay OS-neutral | All contributors |
| **Section 2** | Known Coupling Points — current audit results | Maintainers & AI assistants |
| **Section 3** | Anti-Regression Rules — enforceable constraints | All contributors |
| **Section 4** | Evolution Risk Model — what will break if rules are ignored | Project owner |
| **Section 5** | CI/Verification Requirements | Maintainers |

---

## Section 1: Core Contract Layer

The following files and abstractions form the **"neutral core"** of Weylus Studio. They define the interfaces between the server, the client, and the OS backends. These MUST remain OS-neutral — no `#[cfg(target_os)]` allowed except where explicitly documented as an approved exception.

### 1.1 Protocol Layer (Wire Format)

| File | Abstraction | Neutrality Rule |
| :--- | :--- | :--- |
| `src/protocol.rs` | `PointerEvent`, `WheelEvent`, `KeyboardEvent`, `MessageInbound`, `MessageOutbound` | **STRICT** — zero `cfg(target_os)` allowed. The wire protocol must serialize identically on all platforms. |
| `src/protocol.rs` | `WeylusSender`, `WeylusReceiver` traits | **STRICT** — network transport traits must be platform-agnostic. |

> [!CAUTION]
> **Current violation**: `ClientConfiguration` (protocol.rs line 5–6) contains `#[cfg(target_os = "linux")] pub uinput_support: bool`. This makes the **JSON wire format OS-dependent** — a client talking to a Linux server sees a different JSON shape than one talking to a Windows server. This must be resolved by making the field unconditional with a default value, or by moving it to a separate Linux-specific configuration extension.

### 1.2 Video Pipeline

| File | Abstraction | Neutrality Rule |
| :--- | :--- | :--- |
| `src/video.rs` | `PixelProvider` enum, `VideoEncoder`, `EncoderOptions` | **STRICT** — zero `cfg(target_os)` allowed. Currently fully clean ✅. |

`video.rs` is the **model of correct abstraction** in this codebase. Hardware encoder selection (`try_vaapi`, `try_nvenc`, `try_videotoolbox`, `try_mediafoundation`) is expressed as boolean flags, not OS-conditional compilation. The caller (in `config.rs` / `websocket.rs`) gates which flags are available per OS — the video pipeline itself remains unaware of the OS.

**This pattern should be followed by all new abstractions.**

### 1.3 Input Device Trait

| File | Abstraction | Neutrality Rule |
| :--- | :--- | :--- |
| `src/input/device.rs` | `InputDevice` trait | **STRICT** — the trait methods (`send_wheel_event`, `send_pointer_event`, `send_keyboard_event`, `set_capturable`, `device_type`) must remain OS-neutral. |
| `src/input/device.rs` | `InputDeviceType` enum | **RELAXED** — OS-conditional variants are acceptable (e.g., `WindowsInput`) as long as they use `#[cfg]` gating. |

> [!WARNING]
> **Current inconsistency**: `InputDeviceType::WindowsInput` is `#[cfg(target_os = "windows")]` gated, but `InputDeviceType::UInputDevice` is **not** gated despite being Linux-only. This asymmetry should be resolved — either gate all OS-specific variants, or un-gate all of them.

### 1.4 Capturable / Recorder Traits

| File | Abstraction | Neutrality Rule |
| :--- | :--- | :--- |
| `src/capturable/mod.rs` | `Capturable` trait, `Recorder` trait, `BoxCloneCapturable` | **STRICT** — trait definitions must remain OS-neutral. |
| `src/capturable/mod.rs` | `Geometry` enum | **VIOLATED** — see below. |
| `src/capturable/mod.rs` | `get_capturables()` function | **VIOLATED** — see below. |

> [!CAUTION]
> **Current violations**:
> 1. `Geometry::VirtualScreen(i32, i32, u32, u32, i32, i32)` is a Windows-only variant in a shared enum. This forces every `match Geometry` site across the entire codebase to use `#[cfg(target_os = "windows")]` guards — a classic example of OS-specific logic "leaking" through a shared type.
> 2. `get_capturables()` has **different function signatures per OS** — on Linux it takes `(wayland_support: bool, capture_cursor: bool)`, on other platforms it takes zero parameters. This makes the function uncallable from genuinely cross-platform code.

### 1.5 Build Common Module

| File | Abstraction | Neutrality Rule |
| :--- | :--- | :--- |
| `build/common.rs` | `build_web_client()`, `compile_c_helpers()` | **STRICT** — zero `if target_os ==` branches. The module receives a `BuildCapabilities` struct from each OS module and acts on it. |
| `build/common.rs` | `BuildCapabilities` struct | **CONTRACT** — the struct is the only interface between OS modules and shared build helpers. All capability decisions are owned by the OS modules. |

**Current state**: ✅ `common.rs` has zero `if target_os` branches. The `BuildCapabilities` struct is declared here, but populated entirely by each OS module:

```rust
// build/windows.rs — Windows OWNS its capability decisions:
let caps = BuildCapabilities {
    has_nvenc: true,
    has_vaapi: false,
    has_videotoolbox: false,
    has_mediafoundation: true,
    has_libnpp: enable_libnpp,
    shell: "cmd",
    shell_flag: "/c",
};
build_common::build_web_client(&caps);
build_common::compile_c_helpers(&caps, &dist_dir);
```

`common.rs` then acts on these capabilities directly without asking which OS it's running on.

> [!IMPORTANT]
> **Invariant**: If you are adding `if target_os ==` to `build/common.rs`, you are doing it wrong. Move the decision into the calling OS module and express it as a new `BuildCapabilities` capability/flag instead.

---

## Section 2: Known Coupling Points (Audit Results 2026-06-18)

This section documents every location where OS-specific logic currently exists in shared/neutral files. Each entry is classified by risk level and whether the coupling is justified.

### 🔴 HIGH Risk — OS Logic Leaking Into Shared Abstractions

| # | File | Line(s) | Issue | Action Required |
| :--- | :--- | :--- | :--- | :--- |
| H1 | `src/protocol.rs` | 5–6 | `#[cfg(target_os = "linux")] pub uinput_support: bool` in `ClientConfiguration` | Make field unconditional: `pub uinput_support: bool` with `#[serde(default)]`. Linux sets it to `true`/`false`; other OSes always send `false`. |
| H2 | `src/capturable/mod.rs` | 41–42 | `Geometry::VirtualScreen` is Windows-only variant in shared enum | Long-term: refactor `Geometry` to use a single variant with optional fields, or move VirtualScreen data into the Windows capturable implementation. |

### 🟡 MEDIUM Risk — Justified But Fragile

| # | File | Line(s) | Issue | Monitoring Required |
| :--- | :--- | :--- | :--- | :--- |
| M1 | `src/capturable/mod.rs` | 66–69 | `get_capturables()` has OS-conditional parameters | Watch for new parameters added only for one OS. Consider moving to a builder/config struct pattern. |
| M2 | `src/input/device.rs` | 8–9 | `WindowsInput` gated but `UInputDevice` not gated | Resolve inconsistency — either gate all OS-specific variants or none. |
| M3 | `build/common.rs` | 7–13 | Windows uses `cmd /c npx` while Unix uses `tsc` directly | Handled via `caps.shell` capability injection. |
| M4 | `src/gui.rs` | ~350 | Windows has no QR code feature (`pnet_datalink` excluded) | Known feature gap. Document in [[ROADMAP]] if planning to fix. |
| M5 | `Cargo.toml` | 52–53 | `pnet_datalink` excluded from Windows via `cfg(not(windows))` | Negative gate — unusual. If Windows needs IP discovery, add a Windows-native alternative. |

### 🟢 LOW Risk — Properly Gated

These are correctly implemented and serve as **examples of good practice**:

- `src/input/mod.rs` — All OS-specific modules properly gated with `#[cfg(target_os)]`
- `src/capturable/mod.rs` — Module declarations properly gated
- `src/video.rs` — Zero OS-specific code. **Model file.**
- `src/config.rs` — Encoder option fields gated at config level
- `src/websocket.rs` — Windows input device initialization properly gated
- `build/common.rs` — C `#define` flags for hardware encoders (justified, symmetric)
- `Cargo.toml` — Platform-conditional dependencies properly sectioned

---

## Section 3: Anti-Regression Rules

These rules are the **hard constraints** that must be enforced on every commit. They prevent the codebase from drifting toward "Windows-only in disguise."

### Rule 1: Protocol Neutrality 🔒

> **`src/protocol.rs` must produce identical JSON on all platforms.**

- No `#[cfg(target_os)]` on any field of any struct that is serialized/deserialized via serde.
- If a feature is OS-specific (e.g., uinput support), make the field always present with a platform-appropriate default value.
- Rationale: The protocol is the **contract between the server and ALL clients** (browser, Android app, future desktop client). A client must be able to talk to any server regardless of OS.

### Rule 2: Trait Neutrality 🔒

> **Trait definitions in `device.rs`, `capturable/mod.rs`, and `video.rs` must contain zero `#[cfg(target_os)]`.**

- Trait **methods** must be callable from any OS without conditional compilation.
- Trait **implementations** are OS-specific by nature (that's the whole point of the trait pattern).
- Enum **variants** returned by traits may be gated, but must be consistent (all gated or none gated).

### Rule 3: Shared Return Types Must Not Leak OS Variants 🔒

> **If a type is returned by a shared trait method, it must not contain OS-specific variants that force `#[cfg]` at every match site.**

- `Geometry::VirtualScreen` violates this rule. Every piece of code that matches on `Geometry` must handle VirtualScreen with a `#[cfg]` guard.
- Fix pattern: use an opaque data carrier (e.g., `Geometry::Extended(Box<dyn Any>)`) or flatten the data into the existing variant.

### Rule 4: No OS Logic in `common.rs` Beyond Tool Invocation 🔒

> **`build/common.rs` may contain OS-conditional logic ONLY for tool invocation differences (e.g., `npx` vs `tsc`). It must NOT contain OS-conditional library linking, path resolution, or feature flag logic that belongs in the per-OS modules.**

- The `compile_c_helpers()` function's OS-conditional `#define` flags (`HAS_NVENC`, `HAS_VAAPI`, etc.) are **currently acceptable** because they map to hardware capabilities, not OS preference. But if this section grows beyond simple `#define` flags, it must be split into per-OS helper functions.
- **Smell test**: if you're writing `if target_os == "windows" { ... }` in `common.rs` and it's more than 3 lines, it belongs in `build/windows.rs`.

### Rule 5: Feature Parity Awareness 🔒

> **If a feature is removed or degraded on one OS, it must be documented in [[ROADMAP]] with a justification and a "restore plan" (or explicit "will not fix" decision).**

- Current known gap: QR code / IP display on Windows (missing `pnet_datalink`).
- Future gaps must be tracked, not silently accumulated.

### Rule 6: Documentation Must Reflect Runtime Truth 🔒

> **Every status field in [[CASE_STUDIES]] and [[ROADMAP]] must match the actual code state. If a fix is applied, the doc must be updated in the same commit.**

- **Rationale**: This project already experienced documentation drift where [[CASE_STUDIES]] Chapters 1–3 claimed fixes were "pending" when they were already applied. This was caught by a cross-document audit.
- **Enforcement**: When marking a [[ROADMAP]] item as `[x]`, also update the corresponding [[CASE_STUDIES]] chapter status.

### Rule 7: BuildCapabilities Isolation 🔒

> **`BuildCapabilities` MUST exist only in build pipeline modules.**

- It MUST NOT be imported, referenced, or duplicated in runtime (`src/`) code.
- Any runtime feature gating must use OS-specific conditional compilation (`#[cfg(target_os)]`) or explicit runtime detection APIs.
- Rationale: Prevents compile-time build configuration from leaking into the runtime state of the application.

### Rule 8: No Capability Propagation 🔒

> **Build outputs MUST NOT serialize or expose `BuildCapabilities` to runtime layers.**

- Rationale: Enforces clear compilation boundaries, preventing the build script from acting as a runtime feature-flag bus.

### Rule 9: Flat and Minimal Build Abstraction 🔒

> **`BuildCapabilities` MUST remain a flat struct without nested domains.**

- To prevent nested abstraction creep, do not group fields under sub-structs (e.g. no `VideoCapabilities` or `BuildConfig` nested structures).
- Rationale: The build script is not a generic configuration framework; it should remain a minimal helper mapping compilation-time environment facts to compile parameters.

---

## Section 4: Evolution Risk Model

ChatGPT identified a predictable **4-phase degradation pattern** for cross-platform projects with a dominant primary target. This section documents the pattern and the current project position.

```
Phase 1 (CURRENT) ────────────────────────────────────
│ ✅ Clean modular dispatcher                         │
│ ✅ Windows optimized (prebuilt path)                │
│ ✅ Linux/macOS retain original pipelines            │
│ ✅ common.rs decoupled via BuildCapabilities        │
│ ⚠️ 2 HIGH-risk coupling points in shared types      │
───────────────────────────────────────────────────────

Phase 2 (RISK: 6-12 months) ──────────────────────────
│ common.rs starts growing Windows-friendly defaults   │
│ New features tested only on Windows                  │
│ Abstractions start assuming Windows data model       │
│ "It works on Linux" becomes "It compiles on Linux"   │
───────────────────────────────────────────────────────

Phase 3 (RISK: 12-24 months) ─────────────────────────
│ Linux/macOS build but are "not primary tested"       │
│ Community PRs for Linux start breaking Windows       │
│ Nobody catches it because CI doesn't test both       │
───────────────────────────────────────────────────────

Phase 4 (MUST AVOID) ─────────────────────────────────
│ ❌ "cross-platform in theory, Windows in practice"   │
│ ❌ Community loses trust and forks again             │
│ ❌ 2+ years of structural debt to undo              │
───────────────────────────────────────────────────────
```

### Prevention Mechanisms

| Phase Risk | Prevention | How to Detect Drift |
| :--- | :--- | :--- |
| Phase 2 | Rules 1–4 in this document | Count `cfg(target_os)` instances in core contract files. If count increases, investigate. |
| Phase 3 | CI matrix (Section 5) | If Linux CI is red for >1 week, it's Phase 3. |
| Phase 4 | Community feedback + periodic audit | Re-run the bias audit from Section 2 every 3 months. |

---

## Section 5: CI / Verification Requirements

### Minimum CI Matrix

For this project to maintain its "dual-backend" integrity, the following build checks should be automated:

| Target | Environment | What It Verifies |
| :--- | :--- | :--- |
| **Windows** | `windows-latest` + MSVC | Primary development path. Prebuilt FFmpeg linking. |
| **Linux** | `ubuntu-latest` + GCC | POSIX pipeline still works. FFmpeg source build from `deps/build.sh`. X11/VA-API linking. |
| **macOS** (optional) | `macos-latest` + Clang | Apple framework linking. FFmpeg source build. |

### Manual Audit Checklist (Quarterly)

Run these checks every 3 months or before any major release:

- [ ] `grep -rn 'cfg(target_os' src/protocol.rs` — must return 0 results (after H1 is fixed)
- [ ] `grep -rn 'cfg(target_os' src/video.rs` — must return 0 results
- [ ] `grep -rn 'cfg(target_os' src/input/device.rs` — count should not increase
- [ ] `grep -rn 'cfg(target_os' src/capturable/mod.rs` — count should not increase
- [ ] `grep -rn 'cfg(target_os' build/common.rs` — count should not increase
- [ ] Verify all [[CASE_STUDIES]] status fields match actual code state
- [ ] Verify all [[ROADMAP]] `[x]` items are actually completed in code

---

## Appendix: Approved Exceptions

The following `#[cfg(target_os)]` usages in shared files are **explicitly approved** and should not be flagged during audits:

| File | Location | Reason for Exception |
| :--- | :--- | :--- |
| `src/input/device.rs` | `InputDeviceType` enum variants | Enum gating for OS-specific device types is standard Rust pattern |
| `src/input/mod.rs` | Module declarations | Conditional module compilation is the intended Rust mechanism |
| `src/capturable/mod.rs` | Module declarations (lines 5–19) | Same as above |
| `src/capturable/mod.rs` | `get_capturables()` body blocks (lines 71–140) | Implementation blocks are properly gated |
| `src/config.rs` | Encoder option field gating | Config mirrors encoder availability |
| `src/gui.rs` | UI label and checkbox gating | GUI reflects available features per OS |

> [!NOTE]
> `build/common.rs` is **no longer on this list**. It was refactored to contain zero `if target_os ==` branches via the `BuildCapabilities` capability layer extraction. See Section 1.5.

---

## Section 6: AI Assistant Execution Contract

This section defines the mandatory contract for all AI coding assistants (Gemini, Claude, etc.) modifying the Weylus Studio codebase.

> [!IMPORTANT]
> **Read Before Modifying**: As an AI coding assistant, you MUST read and satisfy all rules in this contract before implementing changes.

### 6.1 Hard Coding Rules (Non-Negotiable)

*   **No OS Gating in Shared Modules**: You must NOT introduce `#[cfg(target_os)]` inside:
    - `src/protocol.rs`
    - `src/video.rs`
    - `src/input/device.rs` (traits/interfaces only)
    - `src/capturable/mod.rs` (traits/interfaces only)
    - `build/common.rs`
*   **No OS-Specific Serialization**: Do not add OS-specific fields to JSON-serialized types or wire protocol structs.
*   **No God Configurations**: `BuildCapabilities` is compile-time only and must NEVER be used to branch runtime logic in `src/`.

### 6.2 Pre-Commit Verification Loop

Before proposing or finalizing any changes, you must mentally run or simulate these checks:
1.  `grep -rn "cfg(target_os" src/` — ensure zero occurrences in `src/protocol.rs`, `src/video.rs` or shared traits.
2.  `grep -rn "windows" src/protocol.rs` — ensure no Windows-specific logic leaks into shared wire types.
3.  `grep -rn "linux" src/protocol.rs` — same as above.

### 6.3 Change Classification Requirement

You must explicitly classify every proposed change in your implementation plan into:
1.  **Shared-core change** (must be strictly OS-neutral)
2.  **OS module change** (allowed to contain platform-specific target code)
3.  **Build-system change** (must stay in the `build/` module tree only)

---

## Section 7: Modular Platform & Extensibility Constraints

This section outlines rules ensuring the long-term evolution of Weylus Studio into a multi-device streaming and input platform.

### 7.1 Separation of Concerns (Loose Coupling)
- **Streaming Pipeline**: Gated by capability flags and timing parameters. Keep encoder-specific parameters out of the connection or coordinate handling layers.
- **Input Injection**: Decoupled from the websocket handler using clean traits (`src/input/device.rs`). New injection modes (e.g. game controller, virtual keyboard) must implement isolated interfaces.
- **Frontend Architecture**: The Preact + SASS + esbuild workspace inside `www/` is the single source of truth for UI. Do not attempt to merge backend changes into frontend directories or vice versa.

### 7.2 Invariant Preservation Checklist
Every modification must verify that we do not regress on our key stability invariants:
1. **Memory Invariant**: Direct borrows on `Vec` structures (`.as_mut_ptr()`) instead of raw `Box::into_raw` allocations for Win32 input.
2. **Handle Invariant**: Windows synthetic pointer handles are released via the `Drop` implementation on `WindowsInput`.
3. **Pacing Invariant**: Video timestamps are computed in microsecond resolution, calculated at the capture boundary.
4. **Coalescing Invariant**: Network queue video frames are coalesced consecutively, stopping immediately at any control message to prevent out-of-order execution.
