use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::Mutex;
use tokio::process::Command;
use std::process::Stdio;
use tokio::io::AsyncReadExt;
use tokio::net::UdpSocket;
use tracing::{info, warn, error};

pub struct WifiSpeakerService {
    is_active: Arc<AtomicBool>,
    parec_child_killer: Arc<Mutex<Option<tokio::sync::oneshot::Sender<()>>>>,
    module_id: Arc<Mutex<Option<String>>>,
    loopback_module_id: Arc<Mutex<Option<String>>>,
}

impl WifiSpeakerService {
    pub fn new() -> Self {
        Self {
            is_active: Arc::new(AtomicBool::new(false)),
            parec_child_killer: Arc::new(Mutex::new(None)),
            module_id: Arc::new(Mutex::new(None)),
            loopback_module_id: Arc::new(Mutex::new(None)),
        }
    }

    pub fn is_active(&self) -> bool {
        self.is_active.load(Ordering::Relaxed)
    }

    pub async fn start(&self, target_ip: String, playback_mode: Option<String>) -> anyhow::Result<()> {
        if self.is_active.swap(true, Ordering::Relaxed) {
            info!("Wi-Fi Speaker Service is already active.");
            return Ok(());
        }

        info!("Starting Wi-Fi Speaker Service to target IP: {} with playback mode: {:?}", target_ip, playback_mode);

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

        // If playback_mode is "both", load module-loopback to duplicate sound
        let mode = playback_mode.unwrap_or_else(|| "destination_only".to_string());
        if mode == "both" {
            info!("Dual playback mode: loading module-loopback from @DEFAULT_MONITOR@ to wifi_speaker...");
            match Command::new("pactl")
                .args(&["load-module", "module-loopback", "source=@DEFAULT_MONITOR@", "sink=wifi_speaker"])
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
        if !self.is_active.swap(false, Ordering::Relaxed) {
            info!("Wi-Fi Speaker Service is already inactive.");
            return Ok(());
        }

        info!("Stopping Wi-Fi Speaker Service...");

        // 1. Stop parec process
        if let Some(killer) = self.parec_child_killer.lock().await.take() {
            let _ = killer.send(());
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
