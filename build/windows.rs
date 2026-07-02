use std::env;
use std::path::Path;
use crate::build_common::{self, BuildCapabilities};

pub fn build() {
    let dist_dir = Path::new("deps").join("prebuilt_windows");

    let enable_libnpp = env::var("I_AM_BUILDING_THIS_AT_HOME_AND_WANT_LIBNPP").map_or(false, |v| {
        ["y", "yes", "true", "1"].contains(&v.to_lowercase().as_str())
    });

    // Windows capability declaration — this module OWNS the capability decisions for Windows.
    // common.rs functions receive this struct and act on it without any OS detection.
    let caps = BuildCapabilities {
        has_nvenc: true,             // Windows supports NVENC via nv-codec-headers
        has_vaapi: false,            // VA-API is Linux-only
        has_videotoolbox: false,     // VideoToolbox is macOS-only
        has_mediafoundation: true,   // MediaFoundation is Windows-only
        has_libnpp: enable_libnpp,   // Opt-in via env var
        shell: "cmd",
        shell_flag: "/c",
    };

    // Compile shared resources using capability declaration
    build_common::build_web_client(&caps);
    build_common::compile_c_helpers(&caps, &dist_dir);

    // Link FFmpeg & x264 libs from prebuilt_windows
    // dylib (not static) because prebuilt .lib files are MSVC import libraries
    // that require the matching .dll at runtime. Static flag causes LNK4098.
    let ffmpeg_link_kind = "dylib";
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

    println!(
        "cargo:rustc-link-search={}",
        dist_dir.join("lib").to_string_lossy()
    );

    // Link Windows system libraries
    println!("cargo:rustc-link-lib=dylib=mfplat");
    println!("cargo:rustc-link-lib=dylib=mfuuid");
    println!("cargo:rustc-link-lib=dylib=ole32");
    println!("cargo:rustc-link-lib=dylib=strmiids");
    println!("cargo:rustc-link-lib=dylib=vfw32");
    println!("cargo:rustc-link-lib=dylib=shlwapi");
    println!("cargo:rustc-link-lib=dylib=bcrypt");
}
