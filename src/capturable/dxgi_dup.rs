/// DXGI Desktop Duplication capture backend for Windows.
///
/// Unlike the `captrs` backend (which uses GDI BitBlt), DXGI Desktop Duplication
/// works directly with the GPU and supports hardware-accelerated scenarios including:
/// - Dedicated GPU only mode (RTX / AMD)
/// - DirectX / Vulkan / Hardware-accelerated desktop composition
/// - HDR / high-refresh-rate displays
///
/// Requirements: Windows 8+ (available on all modern Windows 10/11 laptops).
use std::error::Error;
use std::ptr;
use std::mem;
use std::fmt;

use winapi::shared::dxgi::*;
use winapi::shared::dxgi1_2::*;
use winapi::shared::dxgiformat::DXGI_FORMAT_B8G8R8A8_UNORM;
use winapi::shared::winerror::{DXGI_ERROR_NOT_FOUND, DXGI_ERROR_WAIT_TIMEOUT, DXGI_ERROR_ACCESS_LOST};
use winapi::um::d3d11::*;
use winapi::um::d3dcommon::D3D_DRIVER_TYPE_UNKNOWN;
use winapi::shared::windef::RECT;
use wio::com::ComPtr;

use crate::capturable::{Capturable, Recorder};
use super::Geometry;

// ─── Error type ──────────────────────────────────────────────────────────────

#[derive(Debug)]
pub struct DxgiError(pub String);

impl fmt::Display for DxgiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DXGI: {}", self.0)
    }
}

impl Error for DxgiError {}

macro_rules! hr {
    ($expr:expr, $msg:literal) => {{
        let hr = $expr;
        if hr < 0 {
            return Err(Box::new(DxgiError(format!("{} (HRESULT={:#010x})", $msg, hr as u32))));
        }
        hr
    }};
}

// ─── Capturable ──────────────────────────────────────────────────────────────

#[derive(Clone)]
pub struct DxgiCapturable {
    pub adapter_idx: u32,
    pub output_idx: u32,
    pub name: String,
    pub rect: RECT,
    pub virtual_rect: RECT,
}

impl Capturable for DxgiCapturable {
    fn name(&self) -> String {
        format!("Display {} (DXGI: {})", self.output_idx, self.name)
    }

    fn before_input(&mut self) -> Result<(), Box<dyn Error>> {
        Ok(())
    }

    fn recorder(&self, _capture_cursor: bool) -> Result<Box<dyn Recorder>, Box<dyn Error>> {
        Ok(Box::new(DxgiRecorder::new(self.adapter_idx, self.output_idx, self.rect)?))
    }

    fn geometry(&self) -> Result<Geometry, Box<dyn Error>> {
        Ok(Geometry::VirtualScreen(
            self.rect.left - self.virtual_rect.left,
            self.rect.top - self.virtual_rect.top,
            (self.rect.right - self.rect.left) as u32,
            (self.rect.bottom - self.rect.top) as u32,
            self.rect.left,
            self.rect.top,
        ))
    }
}

// ─── Enumerate displays ───────────────────────────────────────────────────────

pub fn enumerate_dxgi_displays() -> Result<Vec<DxgiCapturable>, Box<dyn Error>> {
    let mut capturables = Vec::new();
    let mut union_rect: RECT = unsafe { mem::zeroed() };

    unsafe {
        let mut factory_raw: *mut IDXGIFactory1 = ptr::null_mut();
        hr!(
            CreateDXGIFactory1(&IID_IDXGIFactory1, &mut factory_raw as *mut _ as *mut _),
            "CreateDXGIFactory1 failed"
        );
        let factory = ComPtr::from_raw(factory_raw);

        // First pass: collect all display rects for union_rect calculation
        let mut all_rects: Vec<(u32, u32, RECT)> = Vec::new();
        for adapter_idx in 0.. {
            let mut adapter_raw: *mut IDXGIAdapter1 = ptr::null_mut();
            let hr = factory.EnumAdapters1(adapter_idx, &mut adapter_raw);
            if hr == DXGI_ERROR_NOT_FOUND { break; }
            if hr < 0 { continue; }
            let adapter = ComPtr::from_raw(adapter_raw);

            for output_idx in 0.. {
                let mut output_raw: *mut IDXGIOutput = ptr::null_mut();
                let hr = adapter.EnumOutputs(output_idx, &mut output_raw);
                if hr == DXGI_ERROR_NOT_FOUND { break; }
                if hr < 0 { continue; }
                let output = ComPtr::from_raw(output_raw);

                let mut desc: DXGI_OUTPUT_DESC = mem::zeroed();
                output.GetDesc(&mut desc);
                if desc.AttachedToDesktop == 0 { continue; }

                all_rects.push((adapter_idx, output_idx, desc.DesktopCoordinates));

                // Expand union rect
                if union_rect.right == 0 {
                    union_rect = desc.DesktopCoordinates;
                } else {
                    if desc.DesktopCoordinates.left < union_rect.left {
                        union_rect.left = desc.DesktopCoordinates.left;
                    }
                    if desc.DesktopCoordinates.top < union_rect.top {
                        union_rect.top = desc.DesktopCoordinates.top;
                    }
                    if desc.DesktopCoordinates.right > union_rect.right {
                        union_rect.right = desc.DesktopCoordinates.right;
                    }
                    if desc.DesktopCoordinates.bottom > union_rect.bottom {
                        union_rect.bottom = desc.DesktopCoordinates.bottom;
                    }
                }
            }
        }

        // Second pass: create DxgiCapturable with correct union rect
        for (adapter_idx, output_idx, rect) in all_rects {
            let mut adapter_raw: *mut IDXGIAdapter1 = ptr::null_mut();
            if factory.EnumAdapters1(adapter_idx, &mut adapter_raw) < 0 { continue; }
            let adapter = ComPtr::from_raw(adapter_raw);

            let mut output_raw: *mut IDXGIOutput = ptr::null_mut();
            if adapter.EnumOutputs(output_idx, &mut output_raw) < 0 { continue; }
            let output = ComPtr::from_raw(output_raw);

            let mut desc: DXGI_OUTPUT_DESC = mem::zeroed();
            output.GetDesc(&mut desc);

            let name = String::from_utf16_lossy(
                desc.DeviceName.iter().take_while(|&&c| c != 0).cloned().collect::<Vec<_>>().as_slice()
            );

            capturables.push(DxgiCapturable {
                adapter_idx,
                output_idx,
                name,
                rect,
                virtual_rect: union_rect,
            });
        }
    }

    Ok(capturables)
}

// ─── Recorder ────────────────────────────────────────────────────────────────

pub struct DxgiRecorder {
    device: ComPtr<ID3D11Device>,
    context: ComPtr<ID3D11DeviceContext>,
    duplication: ComPtr<IDXGIOutputDuplication>,
    staging: Option<ComPtr<ID3D11Texture2D>>,
    width: u32,
    height: u32,
    /// Pixel buffer for the captured frame (BGRA).
    frame_buffer: Vec<u8>,
    debug_save_count: u32,
}

// Safety: We ensure single-threaded access per recorder instance.
unsafe impl Send for DxgiRecorder {}

impl DxgiRecorder {
    pub fn new(adapter_idx: u32, output_idx: u32, rect: RECT) -> Result<Self, Box<dyn Error>> {
        unsafe {
            // Create DXGI factory
            let mut factory_raw: *mut IDXGIFactory1 = ptr::null_mut();
            hr!(
                CreateDXGIFactory1(&IID_IDXGIFactory1, &mut factory_raw as *mut _ as *mut _),
                "CreateDXGIFactory1"
            );
            let factory = ComPtr::from_raw(factory_raw);

            // Get adapter
            let mut adapter_raw: *mut IDXGIAdapter1 = ptr::null_mut();
            hr!(factory.EnumAdapters1(adapter_idx, &mut adapter_raw), "EnumAdapters1");
            let adapter = ComPtr::from_raw(adapter_raw);

            // Create D3D11 device on this adapter
            let mut device_raw: *mut ID3D11Device = ptr::null_mut();
            let mut context_raw: *mut ID3D11DeviceContext = ptr::null_mut();
            let mut feature_level = 0u32;
            hr!(
                D3D11CreateDevice(
                    adapter.as_raw() as *mut _,
                    D3D_DRIVER_TYPE_UNKNOWN,
                    ptr::null_mut(),
                    0,
                    ptr::null(),
                    0,
                    D3D11_SDK_VERSION,
                    &mut device_raw,
                    &mut feature_level,
                    &mut context_raw,
                ),
                "D3D11CreateDevice"
            );
            let device = ComPtr::from_raw(device_raw);
            let context = ComPtr::from_raw(context_raw);

            // Get IDXGIDevice from D3D11 device
            let mut dxgi_device_raw: *mut IDXGIDevice = ptr::null_mut();
            hr!(
                device.QueryInterface(&IID_IDXGIDevice, &mut dxgi_device_raw as *mut _ as *mut _),
                "QueryInterface IDXGIDevice"
            );
            let _ = ComPtr::from_raw(dxgi_device_raw); // drop immediately

            // Get output
            let mut output_raw: *mut IDXGIOutput = ptr::null_mut();
            hr!(adapter.EnumOutputs(output_idx, &mut output_raw), "EnumOutputs");
            let output = ComPtr::from_raw(output_raw);

            // Query IDXGIOutput1 for desktop duplication
            let mut output1_raw: *mut IDXGIOutput1 = ptr::null_mut();
            hr!(
                output.QueryInterface(&IID_IDXGIOutput1, &mut output1_raw as *mut _ as *mut _),
                "QueryInterface IDXGIOutput1"
            );
            let output1 = ComPtr::from_raw(output1_raw);

            // Create desktop duplication
            let mut dup_raw: *mut IDXGIOutputDuplication = ptr::null_mut();
            hr!(
                output1.DuplicateOutput(device.as_raw() as *mut _, &mut dup_raw),
                "DuplicateOutput (ensure no other capture tool is running)"
            );
            let duplication = ComPtr::from_raw(dup_raw);

            let width = (rect.right - rect.left) as u32;
            let height = (rect.bottom - rect.top) as u32;

            Ok(DxgiRecorder {
                device,
                context,
                duplication,
                staging: None,
                width,
                height,
                frame_buffer: vec![0u8; (width * height * 4) as usize],
                debug_save_count: 0,
            })
        }
    }

    /// Ensure the staging texture is created with correct dimensions.
    fn ensure_staging(&mut self) -> Result<(), Box<dyn Error>> {
        if self.staging.is_some() {
            return Ok(());
        }
        let desc = D3D11_TEXTURE2D_DESC {
            Width: self.width,
            Height: self.height,
            MipLevels: 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: winapi::shared::dxgitype::DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_STAGING,
            BindFlags: 0,
            CPUAccessFlags: D3D11_CPU_ACCESS_READ,
            MiscFlags: 0,
        };
        let mut tex_raw: *mut ID3D11Texture2D = ptr::null_mut();
        unsafe {
            hr!(
                self.device.CreateTexture2D(&desc, ptr::null(), &mut tex_raw),
                "CreateTexture2D (staging)"
            );
        }
        self.staging = Some(unsafe { ComPtr::from_raw(tex_raw) });
        Ok(())
    }
}

impl Recorder for DxgiRecorder {
    fn capture(&mut self) -> Result<crate::video::PixelProvider<'_>, Box<dyn Error>> {
        self.ensure_staging()?;

        unsafe {
            let mut frame_info: DXGI_OUTDUPL_FRAME_INFO = mem::zeroed();
            let mut resource_raw: *mut IDXGIResource = ptr::null_mut();

            // AcquireNextFrame — timeout 2ms so we don't block the capture loop
            let hr = self.duplication.AcquireNextFrame(2, &mut frame_info, &mut resource_raw);

            if hr == DXGI_ERROR_WAIT_TIMEOUT {
                // No new frame — re-use existing buffer
                let buf = &self.frame_buffer;
                let w = self.width as usize;
                let h = self.height as usize;
                // SAFETY: lifetime tied to self which outlives this borrow
                let slice = std::slice::from_raw_parts(buf.as_ptr(), buf.len());
                return Ok(crate::video::PixelProvider::BGR0(w, h, slice));
            }

            if hr == DXGI_ERROR_ACCESS_LOST {
                // Desktop mode changed (e.g. lock screen) — recreate duplication
                return Err(Box::new(DxgiError(
                    "DXGI_ERROR_ACCESS_LOST: desktop mode changed, reconnect required".into(),
                )));
            }

            if hr < 0 {
                return Err(Box::new(DxgiError(format!("AcquireNextFrame failed: {:#010x}", hr as u32))));
            }

            // resource_raw is the desktop frame texture
            let resource = ComPtr::from_raw(resource_raw);
            let mut tex_raw: *mut ID3D11Texture2D = ptr::null_mut();
            hr!(
                resource.QueryInterface(
                    &IID_ID3D11Texture2D,
                    &mut tex_raw as *mut _ as *mut _,
                ),
                "QueryInterface ID3D11Texture2D"
            );
            let tex = ComPtr::from_raw(tex_raw);

            // Copy GPU texture → staging (CPU-readable)
            let staging = self.staging.as_ref().unwrap();
            self.context.CopyResource(
                staging.as_raw() as *mut _,
                tex.as_raw() as *mut _,
            );

            // Release the frame ASAP to avoid stalling compositor
            self.duplication.ReleaseFrame();

            // Map staging texture for CPU read
            let mut mapped: D3D11_MAPPED_SUBRESOURCE = mem::zeroed();
            hr!(
                self.context.Map(
                    staging.as_raw() as *mut _,
                    0,
                    D3D11_MAP_READ,
                    0,
                    &mut mapped,
                ),
                "Map staging texture"
            );

            // Copy to our frame buffer row-by-row (handles row pitch padding)
            let w = self.width as usize;
            let h = self.height as usize;
            let row_size = w * 4;
            let pitch = mapped.RowPitch as usize;
            let src = mapped.pData as *const u8;
            for row in 0..h {
                let src_row = std::slice::from_raw_parts(src.add(row * pitch), row_size);
                self.frame_buffer[row * row_size..(row + 1) * row_size].copy_from_slice(src_row);
            }

            self.context.Unmap(staging.as_raw() as *mut _, 0);

            // Debug save first few frames to disk as PNG to verify content
            if self.debug_save_count < 3 {
                self.debug_save_count += 1;
                let filename = format!("dxgi_debug_frame_{}.png", self.debug_save_count);
                let mut rgba_buf = self.frame_buffer.clone();
                for pixel in rgba_buf.chunks_exact_mut(4) {
                    pixel.swap(0, 2);
                }
                match image::save_buffer(
                    &filename,
                    &rgba_buf,
                    self.width,
                    self.height,
                    image::ColorType::Rgba8
                ) {
                    Ok(_) => {
                        tracing::info!("Successfully saved debug frame capture: {}", filename);
                    }
                    Err(err) => {
                        tracing::warn!("Failed to save debug frame capture: {}", err);
                    }
                }
            }

            let buf = &self.frame_buffer;
            let slice = std::slice::from_raw_parts(buf.as_ptr(), buf.len());
            Ok(crate::video::PixelProvider::BGR0(w, h, slice))
        }
    }
}
