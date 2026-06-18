use std::path::Path;
use std::process::Command;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TypeScriptCompilerSource {
    GlobalTsc,
    NpxShell,
}

/// Capability declaration struct — owned by each OS module, consumed by common helpers.
///
/// This is the "capability layer" separating OS identity from feature detection.
/// `common.rs` functions MUST NOT contain any `if target_os == ...` branches.
/// Instead, each OS module constructs a `BuildCapabilities` struct and passes it here.
///
/// Rules:
/// - If you find yourself adding `if target_os ==` to a function in this file, STOP.
///   Move the decision into the calling OS module and express it as a capability here.
/// - This struct is the single contract between OS modules and shared build logic.
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
    /// How the TypeScript compiler is invoked on the build platform.
    pub typescript: TypeScriptCompilerSource,
}

pub fn compile_typescript(caps: &BuildCapabilities) {
    println!("cargo:rerun-if-changed=ts/lib.ts");

    let mut tsc_command = match caps.typescript {
        TypeScriptCompilerSource::NpxShell => {
            // Windows: tsc is not directly executable — must go through cmd and npx
            let mut cmd = Command::new("cmd");
            cmd.args(&["/c", "npx -y -p typescript tsc"]);
            cmd
        }
        TypeScriptCompilerSource::GlobalTsc => {
            // Linux / macOS: tsc expected to be available via PATH
            Command::new("tsc")
        }
    };

    let js_needs_update = || -> Result<bool, Box<dyn std::error::Error>> {
        Ok(Path::new("ts/lib.ts").metadata()?.modified()?
            > Path::new("www/static/lib.js").metadata()?.modified()?)
    }()
    .unwrap_or(true);

    if js_needs_update {
        match tsc_command.status() {
            Err(err) => {
                println!("cargo:warning=Failed to call tsc: {}", err);
                std::process::exit(1);
            }
            Ok(status) => {
                if !status.success() {
                    match status.code() {
                        Some(code) => println!("cargo:warning=tsc failed with exitcode: {}", code),
                        None => println!("cargo:warning=tsc terminated by signal."),
                    };
                    std::process::exit(2);
                }
            }
        }
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
