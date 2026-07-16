#!/usr/bin/env python3
"""
Weylus Studio Debug Launcher (main.py)
---------------------------------------
The only script you need for debug sessions:
  - Automatically sets up ADB reverse port forwarding via USB
  - Runs Weylus host server (cargo run)
  - Streams server log + tablet logcat side-by-side with colors
  - Press Ctrl+C to stop all processes at once

Usage:
  python main.py            # normal mode
  python main.py --no-adb   # skip ADB (if tablet is not connected)
  python main.py --release  # use release build (faster)

No Android Studio needed. No WiFi needed.
"""

import subprocess
import threading
import sys
import os
import time
import argparse
import signal
from datetime import datetime

# Paths - sesuaikan jika perlu
WORKSPACE = os.path.dirname(os.path.abspath(__file__))
ADB       = r"C:\Users\pasca\AppData\Local\Android\Sdk\platform-tools\adb.exe"
RTK       = r"C:\Users\pasca\.local\bin\rtk.exe"
PORT      = 1701

# ANSI Colors
class C:
    RESET   = "\033[0m"
    BOLD    = "\033[1m"
    RED     = "\033[31m"
    GREEN   = "\033[32m"
    YELLOW  = "\033[33m"
    CYAN    = "\033[36m"
    MAGENTA = "\033[35m"
    BLUE    = "\033[34m"
    GRAY    = "\033[90m"
    WHITE   = "\033[97m"

def enable_ansi():
    if sys.platform == "win32":
        os.system("color")
        try:
            import ctypes
            kernel32 = ctypes.windll.kernel32
            kernel32.SetConsoleMode(kernel32.GetStdHandle(-11), 7)
        except Exception:
            pass

def ts():
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]

def print_server(line: str):
    line = line.rstrip()
    if not line:
        return
    color = C.GRAY
    if "ERROR" in line or "error" in line:
        color = C.RED
    elif "WARN" in line or "warn" in line:
        color = C.YELLOW
    elif "INFO" in line:
        color = C.GREEN
    elif "DEBUG" in line or "debug" in line:
        color = C.CYAN
    elif "Frame rendered" in line:
        color = C.MAGENTA
    elif "Video:" in line or "profile" in line:
        color = C.BLUE
    print(f"{C.GRAY}[PC  {ts()}]{C.RESET} {color}{line}{C.RESET}", flush=True)
    if "Registered mDNS service" in line:
        print_sys("🚀 WEYLUS STUDIO IS READY! Connect your tablet now.")

def print_tablet(line: str):
    line = line.rstrip()
    if not line or line.startswith("*"):
        return
    color = C.GRAY
    if " E " in line or "Error" in line or "ERROR" in line:
        color = C.RED
    elif " W " in line or "WARN" in line:
        color = C.YELLOW
    elif "[WEYLUS]" in line or "[WEYLUS_DBG]" in line:
        color = C.MAGENTA
    elif "Frame rendered" in line:
        color = C.GREEN
    elif " I " in line:
        color = C.WHITE
    print(f"{C.GRAY}[TAB {ts()}]{C.RESET} {color}{line}{C.RESET}", flush=True)

def print_sys(msg: str):
    print(f"\n{C.BOLD}{C.CYAN}[SYS {ts()}] {msg}{C.RESET}\n", flush=True)

def print_err(msg: str):
    print(f"\n{C.BOLD}{C.RED}[ERR {ts()}] {msg}{C.RESET}\n", flush=True)

def adb(*args):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True)

def check_adb_device() -> bool:
    result = adb("devices")
    lines = [l for l in result.stdout.splitlines() if "\tdevice" in l]
    if not lines:
        print_err("No ADB device found! Check USB cable and USB Debugging.")
        return False
    print_sys(f"ADB device detected: {lines[0].split()[0]}")
    return True

def setup_adb_reverse() -> bool:
    print_sys(f"Setting up ADB reverse tunnel tcp:{PORT} to localhost:{PORT}")
    result = adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    if result.returncode != 0:
        print_err(f"ADB reverse failed: {result.stderr}")
        return False
    print_sys("ADB reverse tunnel active OK")
    return True

def stream_logcat(stop_event: threading.Event):
    tags = [
        "WeylusWS:V",
        "WeylusInput:V",
        "MediaCodecDecoder:D",
        "MediaCodec:W",
        "AndroidRuntime:E",
        "*:S",
    ]
    cmd = [ADB, "logcat"] + tags
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        while not stop_event.is_set():
            line = proc.stdout.readline()
            if not line:
                break
            print_tablet(line)
        proc.terminate()
    except Exception as e:
        print_err(f"Logcat error: {e}")

def stream_server(proc: subprocess.Popen, stop_event: threading.Event):
    try:
        for line in proc.stdout:
            if stop_event.is_set():
                break
            print_server(line)
    except Exception as e:
        print_err(f"Server stream error: {e}")

def run_server(release: bool) -> subprocess.Popen:
    cargo_args = ["cargo", "run"]
    if release:
        cargo_args.append("--release")
    cargo_args += ["--", "--no-gui"]
    cmd = [RTK] + cargo_args
    print_sys(f"Starting server: {' '.join(cargo_args)}")
    proc = subprocess.Popen(
        cmd,
        cwd=WORKSPACE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    return proc

def main():
    enable_ansi()
    parser = argparse.ArgumentParser(description="Weylus Studio Debug Launcher")
    parser.add_argument("--no-adb", action="store_true", help="Skip ADB (no tablet)")
    parser.add_argument("--release", action="store_true", help="Use release build")
    args = parser.parse_args()

    print(f"""
{C.BOLD}{C.CYAN}+------------------------------------------+
|   Weylus Studio - Debug Launcher        |
|   Ctrl+C to stop all processes          |
+------------------------------------------+{C.RESET}

  {C.GREEN}PC log{C.RESET}  = [{C.GRAY}PC  HH:MM:SS{C.RESET}]   (server Rust)
  {C.MAGENTA}Tab log{C.RESET} = [{C.GRAY}TAB HH:MM:SS{C.RESET}]  (Android logcat)
  Port   = {PORT} via USB ADB reverse tunnel
""")

    stop_event = threading.Event()
    threads = []
    server_proc = None

    def shutdown(sig=None, frame=None):
        print_sys("Shutting down all processes...")
        stop_event.set()
        if server_proc:
            server_proc.terminate()
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    if not args.no_adb:
        if check_adb_device():
            setup_adb_reverse()
            adb("logcat", "-c")
            print_sys("Logcat buffer cleared OK")
            t = threading.Thread(target=stream_logcat, args=(stop_event,), daemon=True)
            t.start()
            threads.append(t)
            print_sys("Tablet logcat streaming started OK")
        else:
            print_sys("Tip: run with --no-adb to skip tablet. Continuing with server only...")
    else:
        print_sys("--no-adb: skipping tablet setup")

    server_proc = run_server(args.release)
    t = threading.Thread(target=stream_server, args=(server_proc, stop_event), daemon=True)
    t.start()
    threads.append(t)
    print_sys(f"Server started. Tablet should connect to 127.0.0.1:{PORT}")
    print_sys("-" * 50)

    try:
        server_proc.wait()
        if not stop_event.is_set():
            print_err(f"Server exited unexpectedly! (code={server_proc.returncode})")
    except KeyboardInterrupt:
        shutdown()

if __name__ == "__main__":
    main()
