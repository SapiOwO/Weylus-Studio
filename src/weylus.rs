use std::net::SocketAddr;
use std::sync::Arc;
use tracing::{error, info, warn, debug};
use mdns_sd::{ServiceDaemon, ServiceInfo};

use crate::config::Config;
use crate::video::EncoderOptions;
use crate::web::{Web2UiMessage, WebServerConfig, WebStartUpMessage};
use crate::websocket::WeylusClientConfig;

pub struct Weylus {
    notify_shutdown: Arc<tokio::sync::Notify>,
    web_thread: Option<std::thread::JoinHandle<()>>,
    mdns_daemon: Option<ServiceDaemon>,
    running_adb: Option<Arc<std::sync::atomic::AtomicBool>>,
}

impl Weylus {
    pub fn new() -> Self {
        Self {
            notify_shutdown: Arc::new(tokio::sync::Notify::new()),
            web_thread: None,
            mdns_daemon: None,
            running_adb: None,
        }
    }

    pub fn start(
        &mut self,
        config: &Config,
        mut on_web_message: impl FnMut(Web2UiMessage) + Send + 'static,
    ) -> bool {
        let encoder_options = EncoderOptions {
            #[cfg(target_os = "linux")]
            try_vaapi: config.try_vaapi,
            #[cfg(not(target_os = "linux"))]
            try_vaapi: false,

            #[cfg(any(target_os = "linux", target_os = "windows"))]
            try_nvenc: config.try_nvenc,
            #[cfg(not(any(target_os = "linux", target_os = "windows")))]
            try_nvenc: false,

            #[cfg(target_os = "macos")]
            try_videotoolbox: config.try_videotoolbox,
            #[cfg(not(target_os = "macos"))]
            try_videotoolbox: false,

            #[cfg(target_os = "windows")]
            try_mediafoundation: config.try_mediafoundation,
            #[cfg(not(target_os = "windows"))]
            try_mediafoundation: false,
        };

        let (sender_ui, mut receiver_ui) = tokio::sync::mpsc::channel(100);
        let (sender_startup, receiver_startup) = tokio::sync::oneshot::channel();

        let web_thread = crate::web::run(
            sender_ui,
            sender_startup,
            self.notify_shutdown.clone(),
            WebServerConfig {
                bind_addr: SocketAddr::new(config.bind_address, config.web_port),
                access_code: config.access_code.clone(),
                custom_index_html: config.custom_index_html.clone(),
                custom_access_html: config.custom_access_html.clone(),
                custom_style_css: config.custom_style_css.clone(),
                custom_lib_js: config.custom_lib_js.clone(),
                #[cfg(target_os = "linux")]
                enable_custom_input_areas: config.wayland_support,
                #[cfg(not(target_os = "linux"))]
                enable_custom_input_areas: false,
            },
            WeylusClientConfig {
                encoder_options,
                #[cfg(target_os = "linux")]
                wayland_support: config.wayland_support,
                no_gui: config.no_gui,
            },
        );

        match receiver_startup.blocking_recv() {
            Ok(WebStartUpMessage::Start) => (),
            Ok(WebStartUpMessage::Error) => {
                if web_thread.join().is_err() {
                    error!("Webserver thread panicked.");
                }
                return false;
            }
            Err(err) => {
                error!("Error communicating with webserver thread: {}", err);
                if web_thread.join().is_err() {
                    error!("Webserver thread panicked.");
                }
                return false;
            }
        }
        self.web_thread = Some(web_thread);

        // Initialize mDNS Advertising
        self.mdns_daemon = match ServiceDaemon::new() {
            Ok(daemon) => {
                let service_type = "_weylus._tcp.local.";
                let instance_name = "Weylus Server";
                let host_name = "weylus-host.local.";
                let properties = [("path", "/")];

                let service_info = ServiceInfo::new(
                    service_type,
                    instance_name,
                    host_name,
                    "",
                    config.web_port,
                    &properties[..],
                );

                match service_info {
                    Ok(info) => {
                        let info = info.enable_addr_auto();
                        if let Err(err) = daemon.register(info) {
                            warn!("Failed to register mDNS service: {}", err);
                        } else {
                            info!("Registered mDNS service '_weylus._tcp.local.' on port {}", config.web_port);
                        }
                    }
                    Err(err) => {
                        warn!("Failed to create mDNS ServiceInfo: {}", err);
                    }
                }
                Some(daemon)
            }
            Err(err) => {
                warn!("Failed to initialize mDNS ServiceDaemon: {}", err);
                None
            }
        };

        // Initialize USB Auto ADB Reverse Loop
        let running_adb = Arc::new(std::sync::atomic::AtomicBool::new(true));
        self.running_adb = Some(running_adb.clone());
        let port = config.web_port;
        std::thread::spawn(move || {
            while running_adb.load(std::sync::atomic::Ordering::Relaxed) {
                run_adb_reverse(port);
                std::thread::sleep(std::time::Duration::from_secs(5));
            }
        });

        std::thread::spawn(move || {
            while let Some(msg) = receiver_ui.blocking_recv() {
                on_web_message(msg);
            }
        });
        true
    }

    pub fn stop(&mut self) {
        self.notify_shutdown.notify_one();
        if let Some(ref running) = self.running_adb {
            running.store(false, std::sync::atomic::Ordering::Relaxed);
        }
        self.wait();
        self.mdns_daemon = None;
    }

    fn wait(&mut self) {
        if let Some(t) = self.web_thread.take() {
            if t.join().is_err() {
                error!("Web thread panicked.");
            }
        }
    }
}

impl Drop for Weylus {
    fn drop(&mut self) {
        self.stop();
    }
}

fn run_adb_reverse(port: u16) {
    let output = std::process::Command::new("adb")
        .arg("devices")
        .output();

    if let Ok(out) = output {
        let stdout = String::from_utf8_lossy(&out.stdout);
        let mut lines = stdout.lines();
        lines.next(); // Skip header
        let mut has_device = false;
        for line in lines {
            if line.contains("device") && !line.trim().is_empty() {
                has_device = true;
                break;
            }
        }

        if has_device {
            let reverse_status = std::process::Command::new("adb")
                .args(&["reverse", &format!("tcp:{}", port), &format!("tcp:{}", port)])
                .status();
            match reverse_status {
                Ok(status) if status.success() => {
                    debug!("Successfully executed adb reverse for port {}", port);
                }
                Ok(status) => {
                    debug!("adb reverse command exited with status: {:?}", status);
                }
                Err(err) => {
                    debug!("Failed to run adb reverse: {}", err);
                }
            }
        }
    }
}
