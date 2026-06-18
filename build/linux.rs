use std::env;
use std::path::{Path, PathBuf};
use std::process::Command;
use crate::build_common::{self, BuildCapabilities};

fn resolve_bash() -> String {
    "bash".to_string()
}

fn build_ffmpeg(dist_dir: &Path, enable_libnpp: bool) {
    if dist_dir.join("include/libavcodec/avcodec.h").exists() {
        return;
    }

    let bash_cmd = resolve_bash();

    Command::new(&bash_cmd)
        .arg(Path::new("clean.sh"))
        .current_dir("deps")
        .status()
        .expect("Failed to clean ffmpeg build!");

    if !Command::new(&bash_cmd)
        .arg(Path::new("build.sh"))
        .current_dir("deps")
        .env("DIST", dist_dir)
        .env("ENABLE_LIBNPP", if enable_libnpp { "y" } else { "n" })
        .status()
        .expect("Failed to run bash!")
        .success()
    {
        println!("cargo:warning=Failed to build ffmpeg!");
        std::process::exit(1);
    }
}

pub fn build() {
    let dist_dir_raw = Path::new("deps")
        .canonicalize()
        .unwrap()
        .join("dist_linux");
    let dist_dir_str = dist_dir_raw.to_str().unwrap().replace("\\\\?\\", "");
    let dist_dir = PathBuf::from(dist_dir_str);

    let enable_libnpp = env::var("I_AM_BUILDING_THIS_AT_HOME_AND_WANT_LIBNPP").map_or(false, |v| {
        ["y", "yes", "true", "1"].contains(&v.to_lowercase().as_str())
    });

    if env::var("CARGO_FEATURE_FFMPEG_SYSTEM").is_err() {
        build_ffmpeg(&dist_dir, enable_libnpp);
    }

    // Linux capability declaration — this module OWNS the capability decisions for Linux.
    // common.rs functions receive this struct and act on it without any OS detection.
    let caps = BuildCapabilities {
        has_nvenc: true,              // Linux supports NVENC via nv-codec-headers
        has_vaapi: true,              // VA-API is Linux-native hardware encoding
        has_videotoolbox: false,      // VideoToolbox is macOS-only
        has_mediafoundation: false,   // MediaFoundation is Windows-only
        has_libnpp: enable_libnpp,    // Opt-in via env var
        typescript: build_common::TypeScriptCompilerSource::GlobalTsc,
    };

    // Compile shared resources using capability declaration
    build_common::compile_typescript(&caps);
    build_common::compile_c_helpers(&caps, &dist_dir);

    // Compile Linux-specific C helpers (uinput, X11 capture)
    println!("cargo:rerun-if-changed=lib/linux/uniput.c");
    println!("cargo:rerun-if-changed=lib/linux/xcapture.c");
    println!("cargo:rerun-if-changed=lib/linux/xhelper.c");
    println!("cargo:rerun-if-changed=lib/linux/xhelper.h");

    cc::Build::new()
        .file("lib/linux/uinput.c")
        .file("lib/linux/xcapture.c")
        .file("lib/linux/xhelper.c")
        .compile("linux");

    // Link FFmpeg & x264 libs
    let ffmpeg_link_kind = if env::var("CARGO_FEATURE_FFMPEG_SYSTEM").is_ok() {
        "dylib"
    } else {
        "static"
    };

    println!("cargo:rustc-link-lib={}=avdevice", ffmpeg_link_kind);
    println!("cargo:rustc-link-lib={}=avformat", ffmpeg_link_kind);
    println!("cargo:rustc-link-lib={}=avfilter", ffmpeg_link_kind);
    println!("cargo:rustc-link-lib={}=avcodec", ffmpeg_link_kind);
    println!("cargo:rustc-link-lib={}=swresample", ffmpeg_link_kind);
    println!("cargo:rustc-link-lib={}=swscale", ffmpeg_link_kind);
    println!("cargo:rustc-link-lib={}=avutil", ffmpeg_link_kind);
    println!("cargo:rustc-link-lib={}=x264", ffmpeg_link_kind);

    if enable_libnpp {
        if let Ok(lib_paths) = env::var("LIBRARY_PATH") {
            for lib_path in lib_paths.split(':') {
                println!("cargo:rustc-link-search={}", lib_path);
            }
        }
        println!("cargo:rustc-link-lib=dylib=nppig");
        println!("cargo:rustc-link-lib=dylib=nppicc");
        println!("cargo:rustc-link-lib=dylib=nppc");
        println!("cargo:rustc-link-lib=dylib=nppidei");
        println!("cargo:rustc-link-lib=dylib=nppif");
    }

    if env::var("CARGO_FEATURE_FFMPEG_SYSTEM").is_err() {
        println!(
            "cargo:rustc-link-search={}",
            dist_dir.join("lib").to_string_lossy()
        );
    }

    // Link Linux system libraries
    println!("cargo:rustc-link-lib=X11");
    println!("cargo:rustc-link-lib=Xext");
    println!("cargo:rustc-link-lib=Xrandr");
    println!("cargo:rustc-link-lib=Xfixes");
    println!("cargo:rustc-link-lib=Xcomposite");
    println!("cargo:rustc-link-lib=Xi");
    let va_link_kind = if env::var("CARGO_FEATURE_VA_STATIC").is_ok() {
        "static"
    } else {
        "dylib"
    };
    println!("cargo:rustc-link-lib={}=va", va_link_kind);
    println!("cargo:rustc-link-lib={}=va-drm", va_link_kind);
    println!("cargo:rustc-link-lib={}=va-x11", va_link_kind);
    println!("cargo:rustc-link-lib=drm");
    println!("cargo:rustc-link-lib=xcb-dri3");
    println!("cargo:rustc-link-lib=X11-xcb");
    println!("cargo:rustc-link-lib=xcb");
}
