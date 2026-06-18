#[path = "build/windows.rs"]
mod build_windows;

#[path = "build/linux.rs"]
mod build_linux;

#[path = "build/macos.rs"]
mod build_macos;

#[path = "build/common.rs"]
mod build_common;

use std::env;

fn main() {
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap();

    if target_os == "windows" {
        build_windows::build();
    } else if target_os == "linux" {
        build_linux::build();
    } else if target_os == "macos" {
        build_macos::build();
    } else {
        panic!("Unsupported target OS: {}", target_os);
    }
}
