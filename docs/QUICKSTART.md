# Quickstart, Build Guide & Known Limitations

This document is the primary entry point for building, running, and maintaining Weylus Studio on Windows. For architecture deep-dives and case studies, see the linked documents below.

*   **Architecture Reference**: How the input injection, screen capture, and video pipeline work → [ARCHITECTURE.md](./ARCHITECTURE.md)
*   **Case Studies**: Root-cause analysis of bugs found and resolved → [CASE_STUDIES.md](./CASE_STUDIES.md)
*   **Roadmap**: Phase plan from Windows stability to Flutter native app → [ROADMAP.md](./ROADMAP.md)

---

## 🚀 1. System Requirements

| Component | Requirement |
| :--- | :--- |
| **OS** | Windows 10 (Build 1903+) or Windows 11 |
| **Rust** | `rustup` stable toolchain (≥ 1.75) |
| **C Compiler** | MSVC via Visual Studio Build Tools 2019+ |
| **Node.js** | Node.js (≥ 18.0) and `npm`/`npx` |
| **Tools** | `nasm` (for x264/FFmpeg), `git`, `make` (via MSYS2 or Git Bash), `cmake` |
| **Tablet** | Android tablet with a modern browser (Chrome 80+, Firefox 80+) |

> [!IMPORTANT]
> On Windows, building FFmpeg from source requires a POSIX environment. Ensure you have **MSYS2** installed and that WSL `bash.exe` does not override it. We use a deterministic bash resolver inside `build.rs` to prioritize MSYS2/Git Bash over WSL.

---

## ⚙️ 2. Build Steps (Windows)

### Step 1: Install Rust, CMake, and NASM
Run the following in PowerShell:
```powershell
winget install Rustlang.Rustup
winget install Kitware.CMake
winget install NASM.NASM
rustup default stable
```

### Step 2: Configure Git Line Endings
To prevent Git Bash/MSYS2 syntax errors (`\r` unexpected token) in shell scripts:
```powershell
git config core.autocrlf input
```
If you already cloned the repository with CRLF line endings, run this to reset:
```powershell
git rm --cached -r .
git reset --hard
```

### Step 3: Set up PATH for the Compiler Session
To build, Cargo must run with MSVC compiler variables (`cl.exe`, `link.exe`) loaded, and MSYS2, CMake, and NASM in your process PATH. You can load this environment in PowerShell using VS Developer Command Prompt:
```powershell
# Load VS Native x64 tools variables
$vsEnv = cmd.exe /c "call `"C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvarsall.bat`" x64 && set";
foreach ($line in $vsEnv) {
    if ($line -match "^([^=]+)=(.*)$") {
        $name = $Matches[1]
        $value = $Matches[2]
        [System.Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
};

# Prepend compilers and Unix tools to PATH, while excluding WSL and Laragon to avoid conflicts
$pathElements = $env:PATH -split ";";
$cleanElements = @();
foreach ($el in $pathElements) {
    if ($el -match "laragon" -or $el -eq "C:\Windows\system32" -or $el -eq "C:\WINDOWS\system32") {
        continue
    }
    $cleanElements += $el
};
$vsPath = "C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Tools\MSVC\14.51.36231\bin\Hostx64\x64";
$env:PATH = "$vsPath;C:\msys64\usr\bin;C:\Program Files\CMake\bin;$env:USERPROFILE\AppData\Local\bin\NASM;" + ($cleanElements -join ";");
```

*(Note: Adjust the Visual Studio paths above according to your system installation directory.)*

### Step 4: Build
Build the project using Cargo. The TypeScript compiler is automatically managed and executed via `npx` during compile.
```powershell
cargo build --release
```
The first build compiles x264 and FFmpeg from source. Subsequent builds are instantaneous.

### Step 5: Run
```powershell
.\target\release\weylus.exe
```

The GUI will launch. Set an access code, click **Start**, then open the URL shown on your Android tablet browser.

---

## 📡 3. Connecting Your Android Tablet

1. Ensure your PC and tablet are on the **same WiFi network**.
2. Open the URL displayed by Weylus (e.g., `http://192.168.x.x:1701`) in Chrome or Firefox.
3. For lowest latency, use **USB tethering via ADB** instead of WiFi:
   ```bash
   adb reverse tcp:1701 tcp:1701
   adb reverse tcp:9001 tcp:9001
   ```
   Then connect from the tablet to `http://127.0.0.1:1701`.

---

## 🖊️ 4. Enabling Stylus & Pen Pressure (Windows)

Weylus Studio uses `CreateSyntheticPointerDevice` and `InjectSyntheticPointerInput` — the modern Windows 10 pointer injection API — which means:

- **Pen pressure**, **tilt X/Y**, and **rotation/twist** are transmitted from the tablet browser and injected as a synthetic pen device in Windows.
- Apps supporting Windows Ink (Krita, Clip Studio Paint, Photoshop) should detect pressure automatically.
- If an app does not respond to pressure, verify that **Windows Ink** support is enabled in that app's settings.

---

## ⚠️ 5. Known Limitations & Non-Goals

| Limitation | Status | Notes |
| :--- | :--- | :--- |
| **Build requires bash on Windows** | Open / Priority B | FFmpeg build script uses `bash`. Workaround: Git Bash or MSYS2. |
| **No per-window capture on Windows** | Open / Priority C | Only full-monitor capture via DXGI. Per-window is Linux-only. |
| **Web browser required (no native app)** | Open / Phase 3 | Flutter native client planned. Browser limits stylus sampling to ~60 Hz. |
| **No encryption by default** | By design | Use on trusted local networks only. TLS proxy via `hitch` documented in main README. |
| **Virtual keyboard not supported** | By design | Physical Bluetooth keyboards connected to the tablet are supported. |
| **FFmpeg shipped as DLL on Windows** | Open / Priority B | Unlike Linux (static), Windows requires FFmpeg DLLs alongside the binary. |
