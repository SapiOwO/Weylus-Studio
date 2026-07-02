# Weylus Studio — Documentation & Architectural Specs

This directory houses the core system specifications, development journals, and anti-regression rules for `Weylus-Studio`.

---

## 📚 READ ORDER & DOCUMENT INDEX

To preserve critical memory-safety fixes, maintain cross-platform build boundaries, and execute the long-term migration to a native Android Kotlin client at 120 FPS, all developers and AI assistants must reference these documents in the following order:

### 1. [[CONSTRAINTS]]
* **Anti-Regression Constraint Layer**: Outlines structural boundaries, strict platform-neutral contracts for core files (`src/protocol.rs`, `src/video.rs`, `src/input/device.rs`), and limits build script compile-time abstractions from leaking into runtime surfaces.

### 2. [[ARCHITECTURE]]
* **Technical Design & Layout**: Breakdowns the project's directory structure, end-to-end data flow (stylus input serialization -> Win32 pointer injection), hardware-accelerated video capture pipelines, and FFI bindings.

### 3. [[ROADMAP]]
* **Unified Development Phases**: Integrates completed accomplishments with future milestones. Detail includes Phase 1 (Rust Stability), Phase 2 (mDNS & ADB USB Connectivity), Phase 3 (Native Android Client in Kotlin + Compose), and Phase 4 (Virtual Display/IddCx Drivers).

### 4. [[CASE_STUDIES]]
* **5W+1H Incident & Design Logs**: A detailed hybrid index compiling 12 chapters of architectural investigations (multitouch memory/handle leaks, frame pacing microsecond calibration, build script isolation, and PR #290 & #291 integration plans).

---

## 🚀 LONG-TERM ARCHITECTURAL DIRECTION

Weylus Studio is evolving from a browser-based drawing utility into a **modular, multi-device companion platform**. We target a low-latency 120 FPS drawing and mirroring experience using a native Android Kotlin application communicating directly over ADB USB cable tunnels. Refer to [[ROADMAP]] and Chapter 12 of [[CASE_STUDIES]] for structural principles.
