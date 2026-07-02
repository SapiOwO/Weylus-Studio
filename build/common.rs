use std::path::Path;

pub struct BuildCapabilities {
    /// Whether the build target supports NVIDIA NVENC hardware encoding.
    /// True on Windows and Linux (if NVENC headers are available), false on macOS.
    pub has_nvenc: bool,
    /// Whether the build target supports VA-API hardware encoding.
    /// True on Linux only.
    pub has_vaapi: bool,
    /// Whether the build target supports Apple VideoToolbox encoding.
    /// True on macOS only.
    pub has_videotoolbox: bool,
    /// Whether the build target supports Microsoft MediaFoundation encoding.
    /// True on Windows only.
    pub has_mediafoundation: bool,
    /// Whether the user has opted in to libnpp CUDA-accelerated processing.
    /// Controlled by the `I_AM_BUILDING_THIS_AT_HOME_AND_WANT_LIBNPP` env var.
    pub has_libnpp: bool,
    /// The shell executable (e.g. "cmd" on Windows, or "" on POSIX)
    pub shell: &'static str,
    /// The shell evaluation flag (e.g. "/c" on Windows, or "" on POSIX)
    pub shell_flag: &'static str,
}

pub fn build_web_client(caps: &BuildCapabilities) {
    use std::process::Command;

    let www_dir = std::path::Path::new("www");
    let node_modules = www_dir.join("node_modules");

    // Track config files
    println!("cargo:rerun-if-changed=www/package.json");
    println!("cargo:rerun-if-changed=www/tsconfig.json");
    if www_dir.join("pnpm-lock.yaml").exists() {
        println!("cargo:rerun-if-changed=www/pnpm-lock.yaml");
    } else if www_dir.join("package-lock.json").exists() {
        println!("cargo:rerun-if-changed=www/package-lock.json");
    }

    // Recursively track www/src directory contents to rebuild static files when code changes
    if let Ok(entries) = std::fs::read_dir("www/src") {
        for entry in entries.flatten() {
            if entry.file_type().map_or(false, |ft| ft.is_file()) {
                println!("cargo:rerun-if-changed={}", entry.path().display());
            }
        }
    }

    if !node_modules.exists() {
        panic!(
            "\n\n\
             www/node_modules missing.\n\
             Run:\n\
             cd www\n\
             npm install\n\n"
        );
    }

    // Determine binary extension based on shell configuration
    let is_windows = !caps.shell.is_empty() && caps.shell.contains("cmd");
    let pnpm_bin = if is_windows { "pnpm.cmd" } else { "pnpm" };
    let npm_bin = if is_windows { "npm.cmd" } else { "npm" };

    let is_pnpm = www_dir.join("pnpm-lock.yaml").exists() 
        && Command::new(pnpm_bin).arg("--version").status().map_or(false, |s| s.success());
    let base_cmd = if is_pnpm { pnpm_bin } else { npm_bin };
    
    let status_build = if !caps.shell.is_empty() {
        Command::new(caps.shell)
            .args(&[caps.shell_flag, &format!("{} run build", base_cmd)])
            .current_dir(www_dir)
            .status()
    } else {
        Command::new(base_cmd)
            .args(&["run", "build"])
            .current_dir(www_dir)
            .status()
    };

    match status_build {
        Ok(status) if status.success() => {},
        _ => panic!("Failed to run build pipeline for Reference Client inside www/"),
    }
}

pub fn compile_c_helpers(caps: &BuildCapabilities, dist_dir: &Path) {
    println!("cargo:rerun-if-changed=lib/encode_video.c");
    let mut cc_video = cc::Build::new();
    cc_video.file("lib/encode_video.c");
    cc_video.include(dist_dir.join("include"));

    // Apply capability flags — each flag maps to a #define in encode_video.c
    // that gates hardware encoder compilation. No OS detection here.
    if caps.has_nvenc        { cc_video.define("HAS_NVENC", None); }
    if caps.has_vaapi        { cc_video.define("HAS_VAAPI", None); }
    if caps.has_videotoolbox { cc_video.define("HAS_VIDEOTOOLBOX", None); }
    if caps.has_mediafoundation { cc_video.define("HAS_MEDIAFOUNDATION", None); }
    if caps.has_libnpp       { cc_video.define("HAS_LIBNPP", None); }

    cc_video.compile("video");

    println!("cargo:rerun-if-changed=lib/error.h");
    println!("cargo:rerun-if-changed=lib/error.c");
    println!("cargo:rerun-if-changed=lib/log.h");
    println!("cargo:rerun-if-changed=lib/log.c");
    cc::Build::new().file("lib/error.c").compile("error");
    cc::Build::new().file("lib/log.c").compile("log");
}
