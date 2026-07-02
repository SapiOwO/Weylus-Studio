use crate::config::{read_config, write_config};
use crate::protocol::VirtualKeyProfile;

/// Save the virtual key profiles to the persistent configuration file.
pub fn save_profiles(profiles: &[VirtualKeyProfile]) -> Result<(), Box<dyn std::error::Error>> {
    let json_string = serde_json::to_string(profiles)?;
    let mut config = read_config().unwrap_or_else(|| {
        crate::config::Config {
            access_code: None,
            bind_address: std::net::IpAddr::V4(std::net::Ipv4Addr::new(0, 0, 0, 0)),
            web_port: 1701,
            #[cfg(target_os = "linux")]
            try_vaapi: false,
            #[cfg(any(target_os = "linux", target_os = "windows"))]
            try_nvenc: false,
            #[cfg(target_os = "macos")]
            try_videotoolbox: false,
            #[cfg(target_os = "windows")]
            try_mediafoundation: false,
            auto_start: false,
            gui_theme: None,
            no_gui: false,
            #[cfg(target_os = "linux")]
            wayland_support: false,
            print_index_html: false,
            print_access_html: false,
            print_style_css: false,
            print_lib_js: false,
            custom_index_html: None,
            custom_access_html: None,
            custom_style_css: None,
            custom_lib_js: None,
            completions: None,
            virtual_keys_profiles: None,
        }
    });
    config.virtual_keys_profiles = Some(json_string);
    write_config(&config);
    Ok(())
}

/// Load the virtual key profiles from the persistent configuration file.
pub fn load_profiles() -> Vec<VirtualKeyProfile> {
    if let Some(config) = read_config() {
        if let Some(ref profiles_json) = config.virtual_keys_profiles {
            if let Ok(profiles) = serde_json::from_str::<Vec<VirtualKeyProfile>>(profiles_json) {
                return profiles;
            }
        }
    }
    Vec::new()
}