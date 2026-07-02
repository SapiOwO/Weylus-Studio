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
        .join("dist_macos");
    let dist_dir_str = dist_dir_raw.to_str().unwrap().replace("\\\\?\\", "");
    let dist_dir = PathBuf::from(dist_dir_str);

    let enable_libnpp = env::var("I_AM_BUILDING_THIS_AT_HOME_AND_WANT_LIBNPP").map_or(false, |v| {
        ["y", "yes", "true", "1"].contains(&v.to_lowercase().as_str())
    });

    if env::var("CARGO_FEATURE_FFMPEG_SYSTEM").is_err() {
        build_ffmpeg(&dist_dir, enable_libnpp);
    }

    // macOS capability declaration — this module OWNS the capability decisions for macOS.
    // common.rs functions receive this struct and act on it without any OS detection.
    let caps = BuildCapabilities {
        has_nvenc: false,             // NVENC is not available on Apple Silicon / macOS
        has_vaapi: false,             // VA-API is Linux-only
        has_videotoolbox: true,       // VideoToolbox is macOS-native hardware encoding
        has_mediafoundation: false,   // MediaFoundation is Windows-only
        has_libnpp: enable_libnpp,    // Opt-in via env var (unusual on macOS but supported)
        shell: "",
        shell_flag: "",
    };

    // Compile shared resources using capability declaration
    build_common::build_web_client(&caps);
    build_common::compile_c_helpers(&caps, &dist_dir);

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

    if env::var("CARGO_FEATURE_FFMPEG_SYSTEM").is_err() {
        println!(
            "cargo:rustc-link-search={}",
            dist_dir.join("lib").to_string_lossy()
        );
    }

    // Link macOS frameworks
    println!("cargo:rustc-link-lib=framework=VideoToolbox");
    println!("cargo:rustc-link-lib=framework=CoreMedia");
}
