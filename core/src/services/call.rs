use std::sync::Arc;
use tokio::sync::Mutex;
use tokio::process::Command;
use serde::{Serialize, Deserialize};
use tracing::{info, warn};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum CallState {
    Ringing,
    Connected,
    Muted,
    Disconnected,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActiveCall {
    pub call_id: String,
    pub number: String,
    pub contact_name: String,
    pub state: CallState,
}

pub struct CallService {
    active_call: Arc<Mutex<Option<ActiveCall>>>,
}

impl CallService {
    pub fn new() -> Self {
        Self {
            active_call: Arc::new(Mutex::new(None)),
        }
    }

    pub async fn update_call_state(&self, call: ActiveCall) -> Option<ActiveCall> {
        let mut active = self.active_call.lock().await;
        info!("Updating call state to: {:?}", call);
        
        if call.state == CallState::Disconnected {
            let old = active.take();
            // Clean up any audio routing setup
            if let Err(e) = self.cleanup_audio_routing().await {
                warn!("Failed to clean up audio routing: {}", e);
            }
            old
        } else {
            *active = Some(call.clone());
            Some(call)
        }
    }

    pub async fn get_active_call(&self) -> Option<ActiveCall> {
        self.active_call.lock().await.clone()
    }

    pub async fn execute_action(&self, action: &str) -> anyhow::Result<()> {
        info!("Executing call action: {}", action);
        match action {
            "accept" => {
                // If accepting, set up audio routing loops
                self.setup_audio_routing().await?;
            }
            "reject" => {
                self.cleanup_audio_routing().await?;
            }
            "mute" => {
                self.set_mic_mute(true).await?;
            }
            "unmute" => {
                self.set_mic_mute(false).await?;
            }
            _ => {
                anyhow::bail!("Unknown call action: {}", action);
            }
        }
        Ok(())
    }

    async fn set_mic_mute(&self, mute: bool) -> anyhow::Result<()> {
        info!("Setting microphone mute to: {}", mute);
        let mute_str = if mute { "1" } else { "0" };
        
        // Try pactl first (PulseAudio/PipeWire)
        let output = Command::new("pactl")
            .args(["set-source-mute", "@DEFAULT_SOURCE@", mute_str])
            .output()
            .await;

        match output {
            Ok(out) if out.status.success() => {
                info!("Microphone mute successfully set via pactl.");
                return Ok(());
            }
            _ => {
                warn!("pactl command failed, trying wpctl (Pipewire)...");
            }
        }

        // Try wpctl fallback
        let wp_mute_str = if mute { "1" } else { "0" };
        let output = Command::new("wpctl")
            .args(["set-mute", "@DEFAULT_AUDIO_SOURCE@", wp_mute_str])
            .output()
            .await;

        match output {
            Ok(out) if out.status.success() => {
                info!("Microphone mute successfully set via wpctl.");
                Ok(())
            }
            Err(e) => {
                warn!("No audio server controls (pactl/wpctl) found: {}", e);
                Ok(()) // Mock success for compatibility
            }
            Ok(out) => {
                warn!("wpctl command exited with status: {:?}", out.status);
                Ok(())
            }
        }
    }

    async fn setup_audio_routing(&self) -> anyhow::Result<()> {
        info!("Setting up desktop audio routing loops for call...");
        // In a real setup, we might load loopback modules in PulseAudio:
        // pactl load-module module-loopback latency_msec=60
        // We run a dry run or log it
        let output = Command::new("pactl")
            .args(["load-module", "module-loopback", "latency_msec=60"])
            .output()
            .await;
            
        match output {
            Ok(out) if out.status.success() => {
                let module_id = String::from_utf8_lossy(&out.stdout).trim().to_string();
                info!("Loaded PulseAudio loopback module: {}", module_id);
            }
            _ => {
                info!("PulseAudio load-module not supported or failed. Skipping loopback routing.");
            }
        }
        Ok(())
    }

    async fn cleanup_audio_routing(&self) -> anyhow::Result<()> {
        info!("Cleaning up desktop audio routing loops...");
        // Unload loopback modules if they were loaded
        // For safety, we can unload all module-loopback instances:
        // pactl unload-module module-loopback
        let _ = Command::new("pactl")
            .args(["unload-module", "module-loopback"])
            .output()
            .await;
        Ok(())
    }
}
