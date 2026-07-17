use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::Mutex;
use tokio::process::Command;
use std::process::Stdio;
use tokio::io::AsyncReadExt;
use tokio::net::UdpSocket;
use tracing::{info, warn, error};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AudioConfig {
    pub audio_direction: String, // "desktop_to_mobile" or "mobile_to_desktop"
    pub playback_mode: String,  // "destination_only" or "both"
    pub wifi_speaker_active: bool,
}

pub struct WifiSpeakerService {
    is_active: Arc<AtomicBool>,
    parec_child_killer: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>>,
    module_id: Arc<Mutex<Option<String>>>,
    loopback_module_id: Arc<Mutex<Option<String>>>,
    original_default_sink: Arc<Mutex<Option<String>>>,
    pub config: Arc<Mutex<AudioConfig>>,
    routing_mutex: Mutex<()>,
}

impl WifiSpeakerService {
    pub fn new() -> Self {
        Self {
            is_active: Arc::new(AtomicBool::new(false)),
            parec_child_killer: Arc::new(Mutex::new(None)),
            module_id: Arc::new(Mutex::new(None)),
            loopback_module_id: Arc::new(Mutex::new(None)),
            original_default_sink: Arc::new(Mutex::new(None)),
            config: Arc::new(Mutex::new(AudioConfig {
                audio_direction: "desktop_to_mobile".to_string(),
                playback_mode: "destination_only".to_string(),
                wifi_speaker_active: false,
            })),
            routing_mutex: Mutex::new(()),
        }
    }

    pub fn is_active(&self) -> bool {
        self.is_active.load(Ordering::Relaxed)
    }

    pub async fn start(&self, target_ip: String, playback_mode: Option<String>) -> anyhow::Result<()> {
        let _guard = self.routing_mutex.lock().await;
        if self.is_active.load(Ordering::Relaxed) {
            info!("Wi-Fi Speaker Service is already active.");
            return Ok(());
        }
        self.is_active.store(true, Ordering::Relaxed);

        info!("Starting Wi-Fi Speaker Service to target IP: {} with playback mode: {:?}", target_ip, playback_mode);

        let mode = playback_mode.unwrap_or_else(|| "destination_only".to_string());

        // Try to fetch original default audio sink, filtering out wifi_speaker
        let mut original_sink = None;
        if let Ok(out) = Command::new("pactl").arg("get-default-sink").output().await {
            if out.status.success() {
                let name = String::from_utf8_lossy(&out.stdout).trim().to_string();
                if name != "wifi_speaker" {
                    original_sink = Some(name);
                }
            }
        }

        // If no default sink (or it was wifi_speaker), find first hardware sink
        if original_sink.is_none() {
            if let Ok(out) = Command::new("pactl").args(&["list", "short", "sinks"]).output().await {
                if out.status.success() {
                    let stdout_str = String::from_utf8_lossy(&out.stdout);
                    for line in stdout_str.lines() {
                        let mut parts = line.split_whitespace();
                        let _id = parts.next();
                        if let Some(name) = parts.next() {
                            if name != "wifi_speaker" {
                                original_sink = Some(name.to_string());
                                break;
                            }
                        }
                    }
                }
            }
        }

        let base_sink = original_sink.clone().unwrap_or_else(|| "alsa_output.pci-0000_00_1f.3.analog-stereo".to_string());
        info!("Determined default hardware audio sink: {}", base_sink);

        // 1. Load module-null-sink if not already loaded
        let mod_id = match Command::new("pactl")
            .args(&["load-module", "module-null-sink", "sink_name=wifi_speaker", "sink_properties=device.description=Wi-Fi_Speaker"])
            .output()
            .await
        {
            Ok(out) if out.status.success() => {
                let id = String::from_utf8_lossy(&out.stdout).trim().to_string();
                info!("Loaded module-null-sink with ID: {}", id);
                Some(id)
            }
            Ok(out) => {
                let err = String::from_utf8_lossy(&out.stderr);
                warn!("Failed to load module-null-sink, it might already be loaded: {}", err);
                None
            }
            Err(e) => {
                warn!("Failed to run pactl load-module: {}", e);
                None
            }
        };

        if let Some(ref id) = mod_id {
            *self.module_id.lock().await = Some(id.clone());
        }

        // Store original hardware sink to restore on stop
        *self.original_default_sink.lock().await = Some(base_sink.clone());

        // Always redirect default output to wifi_speaker
        info!("Setting default audio sink to wifi_speaker...");
        let _ = Command::new("pactl")
            .args(&["set-default-sink", "wifi_speaker"])
            .output()
            .await;

        // Move all active sink-inputs to wifi_speaker so currently playing sound is routed
        if let Ok(out) = Command::new("pactl").args(&["list", "short", "sink-inputs"]).output().await {
            if out.status.success() {
                let stdout_str = String::from_utf8_lossy(&out.stdout);
                for line in stdout_str.lines() {
                    let mut parts = line.split_whitespace();
                    if let Some(id_str) = parts.next() {
                        if let Ok(id) = id_str.parse::<u32>() {
                            info!("Moving active stream {} to wifi_speaker...", id);
                            let _ = Command::new("pactl")
                                .args(&["move-sink-input", &id.to_string(), "wifi_speaker"])
                                .output()
                                .await;
                        }
                    }
                }
            }
        }

        // If playing on both devices, loop back wifi_speaker monitor to the hardware speakers
        if mode == "both" {
            info!("Dual playback mode: looping back wifi_speaker.monitor to hardware sink {}...", base_sink);
            match Command::new("pactl")
                .args(&["load-module", "module-loopback", "source=wifi_speaker.monitor", &format!("sink={}", base_sink), "latency_msec=100"])
                .output()
                .await
            {
                Ok(out) if out.status.success() => {
                    let loop_id = String::from_utf8_lossy(&out.stdout).trim().to_string();
                    info!("Loaded module-loopback with ID: {}", loop_id);
                    *self.loopback_module_id.lock().await = Some(loop_id);
                }
                Ok(out) => {
                    let err = String::from_utf8_lossy(&out.stderr);
                    warn!("Failed to load module-loopback: {}", err);
                }
                Err(e) => {
                    warn!("Failed to run pactl load-module for loopback: {}", e);
                }
            }
        }

        // 2. Spawn parec and pipe raw PCM to UDP socket
        let is_active_clone = self.is_active.clone();
        let (tx, rx) = tokio::sync::oneshot::channel();
        *self.parec_child_killer.lock().await = Some(tx);

        tokio::spawn(async move {
            let mut parec = match Command::new("parec")
                .args(&[
                    "--device=wifi_speaker.monitor",
                    "--format=s16le",
                    "--rate=48000",
                    "--channels=2",
                    "--latency-msec=10",
                ])
                .stdout(Stdio::piped())
                .spawn()
            {
                Ok(child) => child,
                Err(e) => {
                    error!("Failed to spawn parec child process: {}", e);
                    is_active_clone.store(false, Ordering::Relaxed);
                    return;
                }
            };

            let mut stdout = parec.stdout.take().expect("Failed to open parec stdout");
            let socket = match UdpSocket::bind("0.0.0.0:0").await {
                Ok(s) => s,
                Err(e) => {
                    error!("Failed to bind UDP socket: {}", e);
                    let _ = parec.kill().await;
                    is_active_clone.store(false, Ordering::Relaxed);
                    return;
                }
            };

            let target_addr = format!("{}:9095", target_ip);
            info!("Streaming audio UDP packets to {}", target_addr);

            // Buffer size: 960 bytes (5ms of 48000Hz, 16bit, stereo)
            let mut buffer = vec![0u8; 960];
            
            tokio::select! {
                _ = async {
                    while is_active_clone.load(Ordering::Relaxed) {
                        match stdout.read_exact(&mut buffer).await {
                            Ok(_) => {
                                if let Err(e) = socket.send_to(&buffer, &target_addr).await {
                                    warn!("Failed to send UDP audio packet: {}", e);
                                }
                            }
                            Err(e) => {
                                error!("Error reading from parec stdout: {}", e);
                                break;
                            }
                        }
                    }
                } => {}
                _ = rx => {
                    info!("Received stop signal for parec stream.");
                }
            }

            // Cleanup parec process
            let _ = parec.kill().await;
            info!("Wi-Fi Speaker streaming thread terminated.");
        });

        Ok(())
    }

    pub async fn stop(&self) -> anyhow::Result<()> {
        let _guard = self.routing_mutex.lock().await;
        if !self.is_active.load(Ordering::Relaxed) {
            info!("Wi-Fi Speaker Service is already inactive.");
            return Ok(());
        }
        self.is_active.store(false, Ordering::Relaxed);

        info!("Stopping Wi-Fi Speaker Service...");

        // 1. Stop parec process
        if let Some(killer) = self.parec_child_killer.lock().await.take() {
            let _ = killer.send(());
        }

        // Restore original default sink
        let mut orig_sink = self.original_default_sink.lock().await;
        if let Some(ref sink) = *orig_sink {
            info!("Restoring original default audio sink: {}", sink);
            let _ = Command::new("pactl")
                .args(&["set-default-sink", sink])
                .output()
                .await;

            // Move all active sink-inputs back to original sink
            if let Ok(out) = Command::new("pactl").args(&["list", "short", "sink-inputs"]).output().await {
                if out.status.success() {
                    let stdout_str = String::from_utf8_lossy(&out.stdout);
                    for line in stdout_str.lines() {
                        let mut parts = line.split_whitespace();
                        if let Some(id_str) = parts.next() {
                            if let Ok(id) = id_str.parse::<u32>() {
                                info!("Moving active stream {} back to original default sink {}...", id, sink);
                                let _ = Command::new("pactl")
                                    .args(&["move-sink-input", &id.to_string(), sink])
                                    .output()
                                    .await;
                            }
                        }
                    }
                }
            }

            *orig_sink = None;
        }

        // 2. Unload loopback module if loaded
        let mut loop_id = self.loopback_module_id.lock().await;
        if let Some(id) = loop_id.take() {
            info!("Unloading module-loopback ID: {}", id);
            let _ = Command::new("pactl")
                .args(&["unload-module", &id])
                .output()
                .await;
        }

        // 3. Unload PulseAudio null sink module
        let mut mod_id = self.module_id.lock().await;
        if let Some(id) = mod_id.take() {
            info!("Unloading module-null-sink ID: {}", id);
            let _ = Command::new("pactl")
                .args(&["unload-module", &id])
                .output()
                .await;
        } else {
            // Fallback safety: unload by name
            let _ = Command::new("pactl")
                .args(&["unload-module", "module-null-sink"])
                .output()
                .await;
        }

        Ok(())
    }
}
