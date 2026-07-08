# Quickstart, Build Guide & Known Limitations

This document is the primary entry point for building, running, and maintaining Weylus Studio. For architecture deep-dives and case studies, see the linked documents below.

*   **Architecture Reference**: How the input injection, screen capture, and video pipeline work → [[ARCHITECTURE]]
*   **Case Studies**: Root-cause analysis of bugs found and resolved → [[CASE_STUDIES]]
*   **Roadmap**: Phase plan from Windows stability to native app → [[ROADMAP]]

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
   Now navigate to `http://localhost:1701` on your tablet's browser.

---

## ⚠️ 4. Known Limitations & Notes

### Pressure Sensitivity
*   Must be supported by the tablet hardware (e.g., Samsung Galaxy Tab with S-Pen, iPad with Apple Pencil).
*   Chrome on Android fully supports pen pressure and tilt. Firefox may require custom settings depending on version.

### Windows Pointer Type Issues
*   Win32 input injection maps pen events with synthetic device handles. Software like Photoshop/Krita must support Windows Ink (Pointer API) to register pen pressure correctly. If you experience issues, toggle Windows Ink in your program's settings.

---

## 📱 5. Android Native Client (Phase 3)

The Android Native Client is a pure Kotlin + Jetpack Compose app. It bypasses the browser to access native stylus APIs (`MotionEvent`) for true pressure, tilt, and hover at 120 FPS.

### 5.1 Client Platform Overview

| Platform | Client | Status |
| :--- | :--- | :--- |
| **Android** | Kotlin Native (`android/`) | 🚀 In Development |
| **macOS** | Web browser (built-in) | ✅ Stable |
| **Linux** | Web browser (built-in) | ✅ Stable |
| **iOS** | Swift/SwiftUI | 🔮 Planned (Phase 5+) |

### 5.2 Prerequisites (Android Client)

| Component | Requirement |
| :--- | :--- |
| **JDK** | JDK 17+ (via `winget install Eclipse.Temurin.17.JDK` or Android Studio bundled JDK) |
| **Android SDK** | Android SDK API Level 33+ (install via Android Studio or `sdkmanager`) |
| **ADB** | Android Debug Bridge (bundled with Android Studio or `winget install Google.PlatformTools`) |
| **Device / Emulator** | Physical Android device API 33+ recommended for stylus testing |

> [!NOTE]
> You do **not** need Android Studio to build the APK. A command-line-only build using `gradlew` works if the Android SDK is installed and `ANDROID_HOME` is set.

### 5.3 Build Steps (Android APK)

#### Option A: Command Line (Gradle)

```powershell
# Set ANDROID_HOME if not already in environment
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Build debug APK
push-location android
.\gradlew assembleDebug
pop-location
```

The output APK will be at:
```
android\app\build\outputs\apk\debug\app-debug.apk
```

#### Option B: Android Studio
1. Open **Android Studio**.
2. Select **File → Open** and navigate to the `android/` subdirectory of this project.
3. Let Gradle sync complete.
4. Select **Build → Make Project** or press **Ctrl+F9**.
5. Use **Run → Run 'app'** to deploy directly to a connected device or emulator.

### 5.4 Sideloading to a Device (ADB)

With the APK built, install it onto a connected Android device:

```powershell
adb install android\app\build\outputs\apk\debug\app-debug.apk
```

For USB tethering (lowest latency), reverse the WebSocket and uinput ports:

```powershell
adb reverse tcp:1701 tcp:1701
adb reverse tcp:9001 tcp:9001
```

Then connect the native app to `ws://localhost:1701` instead of the WiFi IP address.

### 5.5 Development Notes

*   **Coordinate Mapping**: The `CoordinateMapper` class in `android/.../input/` handles all aspect-ratio and letterbox corrections. Do **not** pass raw `MotionEvent` coordinates directly to the WebSocket — always normalize through `CoordinateMapper.map()`.
*   **Protocol Sync**: If you rename any field in `src/protocol.rs`, you must also update the corresponding `@SerialName` annotation in the Kotlin data classes. A mismatch causes silent deserialization failures on the server.
*   **FrameScheduler**: Never call `Choreographer.postFrameCallback()` directly from `MediaCodecDecoder`. All frame presentation timing must go through `FrameScheduler` to maintain V-Sync alignment.
