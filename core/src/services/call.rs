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
    loaded_modules: Arc<Mutex<Vec<String>>>,
}

impl CallService {
    pub fn new() -> Self {
        Self {
            active_call: Arc::new(Mutex::new(None)),
            loaded_modules: Arc::new(Mutex::new(Vec::new())),
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
        
        let loaded_modules = self.loaded_modules.clone();
        
        // Spawn background task to wait for SCO channels (Bluetooth source/sink) to be created by Pipewire
        tokio::spawn(async move {
            let mut attempts = 0;
            let max_attempts = 15; // 7.5 seconds
            
            while attempts < max_attempts {
                tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
                attempts += 1;
                
                let sources = match get_pactl_list("sources").await {
                    Ok(s) => s,
                    Err(_) => continue,
                };
                let sinks = match get_pactl_list("sinks").await {
                    Ok(s) => s,
                    Err(_) => continue,
                };
                
                let bluez_source = sources.iter().find(|s| s.contains("bluez_input"));
                let bluez_sink = sinks.iter().find(|s| s.contains("bluez_output"));
                
                if let (Some(b_source), Some(b_sink)) = (bluez_source, bluez_sink) {
                    info!("Bluetooth call audio nodes discovered. Source: {}, Sink: {}", b_source, b_sink);
                    
                    // Loop 1: Phone MIC (bluez source) -> PC Speakers (default sink)
                    let mod1 = Command::new("pactl")
                        .args(&["load-module", "module-loopback", &format!("source={}", b_source), "sink=@DEFAULT_SINK@", "latency_msec=60"])
                        .output()
                        .await;
                        
                    // Loop 2: PC MIC (default source) -> Phone Speaker (bluez sink)
                    let mod2 = Command::new("pactl")
                        .args(&["load-module", "module-loopback", "source=@DEFAULT_SOURCE@", &format!("sink={}", b_sink), "latency_msec=60"])
                        .output()
                        .await;
                        
                    let mut modules = loaded_modules.lock().await;
                    if let Ok(m1_out) = mod1 {
                        if m1_out.status.success() {
                            let id = String::from_utf8_lossy(&m1_out.stdout).trim().to_string();
                            info!("Loaded Loopback Module 1 (Phone -> Speakers): {}", id);
                            modules.push(id);
                        }
                    }
                    if let Ok(m2_out) = mod2 {
                        if m2_out.status.success() {
                            let id = String::from_utf8_lossy(&m2_out.stdout).trim().to_string();
                            info!("Loaded Loopback Module 2 (Mic -> Phone): {}", id);
                            modules.push(id);
                        }
                    }
                    break;
                }
                info!("Waiting for Bluetooth call audio nodes (attempt {}/{})...", attempts, max_attempts);
            }
        });
        
        Ok(())
    }

    async fn cleanup_audio_routing(&self) -> anyhow::Result<()> {
        info!("Cleaning up desktop audio routing loops...");
        
        let mut modules = self.loaded_modules.lock().await;
        for mod_id in modules.drain(..) {
            info!("Unloading loopback module ID: {}", mod_id);
            let _ = Command::new("pactl")
                .args(&["unload-module", &mod_id])
                .output()
                .await;
        }
        
        // Safety fallback: unload any remaining loopback modules
        let _ = Command::new("pactl")
            .args(&["unload-module", "module-loopback"])
            .output()
            .await;
            
        Ok(())
    }
}

async fn get_pactl_list(type_str: &str) -> anyhow::Result<Vec<String>> {
    let output = Command::new("pactl")
        .args(&["list", type_str, "short"])
        .output()
        .await?;
        
    let mut names = Vec::new();
    if output.status.success() {
        let text = String::from_utf8_lossy(&output.stdout);
        for line in text.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                names.push(parts[1].to_string());
            }
        }
    }
    Ok(names)
}
