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
    bluetooth_mac: Arc<Mutex<Option<String>>>,
    original_bluetooth_profile: Arc<Mutex<Option<String>>>,
    speaker_mode: Arc<Mutex<String>>, // "desktop_as_speaker" or "mobile_as_speaker"
    call_sync_enabled: Arc<Mutex<bool>>,
}

impl CallService {
    pub fn new() -> Self {
        Self {
            active_call: Arc::new(Mutex::new(None)),
            loaded_modules: Arc::new(Mutex::new(Vec::new())),
            bluetooth_mac: Arc::new(Mutex::new(None)),
            original_bluetooth_profile: Arc::new(Mutex::new(None)),
            speaker_mode: Arc::new(Mutex::new("desktop_as_speaker".to_string())),
            call_sync_enabled: Arc::new(Mutex::new(true)),
        }
    }

    pub async fn set_bluetooth_config(&self, speaker_mode: String, call_sync_enabled: bool) {
        let mut mode = self.speaker_mode.lock().await;
        *mode = speaker_mode;
        let mut enabled = self.call_sync_enabled.lock().await;
        *enabled = call_sync_enabled;
    }

    pub async fn get_speaker_mode(&self) -> String {
        self.speaker_mode.lock().await.clone()
    }

    pub async fn is_call_sync_enabled(&self) -> bool {
        *self.call_sync_enabled.lock().await
    }

    pub async fn set_bluetooth_mac(&self, mac: Option<String>) {
        let mut b_mac = self.bluetooth_mac.lock().await;
        *b_mac = mac;
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
            if call.state == CallState::Ringing {
                if let Err(e) = self.setup_audio_routing().await {
                    warn!("Failed to set up pre-emptive audio routing: {}", e);
                }
            }
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
        // Prevent duplicate loopbacks
        {
            let modules = self.loaded_modules.lock().await;
            if !modules.is_empty() {
                info!("Audio routing loops already established. Skipping setup.");
                return Ok(());
            }
        }

        info!("Setting up desktop audio routing loops for call...");
        
        let loaded_modules = self.loaded_modules.clone();
        let bluetooth_mac = self.bluetooth_mac.clone();
        let original_bluetooth_profile = self.original_bluetooth_profile.clone();
        let speaker_mode = self.speaker_mode.clone();
        
        // Spawn background task to wait for SCO channels (Bluetooth source/sink) to be created by Pipewire
        tokio::spawn(async move {
            let mac_opt = {
                let m = bluetooth_mac.lock().await;
                m.clone()
            };

            if let Some(ref mac) = mac_opt {
                match set_bluetooth_profile(mac, true).await {
                    Ok(Some(old_profile)) => {
                        let mut orig = original_bluetooth_profile.lock().await;
                        *orig = Some(old_profile);
                    }
                    Ok(None) => {}
                    Err(e) => {
                        warn!("Failed to set Bluetooth card profile to headset: {}", e);
                    }
                }
            }

            let mut attempts = 0;
            let max_attempts = 20; // 10 seconds
            
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
                
                let mac_opt = {
                    let m = bluetooth_mac.lock().await;
                    m.clone()
                };

                let bluez_source = if let Some(ref mac) = mac_opt {
                    let formatted_mac = mac.replace(":", "_");
                    if let Some(src) = sources.iter().find(|s| s.contains("bluez_input") && s.to_lowercase().contains(&formatted_mac.to_lowercase())).cloned() {
                        Some(src)
                    } else if let Some(src) = sources.iter().find(|s| s.contains("bluez_source") && s.to_lowercase().contains(&formatted_mac.to_lowercase())).cloned() {
                        Some(src)
                    } else {
                        sources.iter().find(|s| s.contains("bluez_input") || s.contains("bluez_source")).cloned()
                    }
                } else {
                    sources.iter().find(|s| s.contains("bluez_input") || s.contains("bluez_source")).cloned()
                };

                let bluez_sink = if let Some(ref mac) = mac_opt {
                    let formatted_mac = mac.replace(":", "_");
                    if let Some(s) = sinks.iter().find(|s| s.contains("bluez_output") && s.to_lowercase().contains(&formatted_mac.to_lowercase())).cloned() {
                        Some(s)
                    } else {
                        sinks.iter().find(|s| s.contains("bluez_output")).cloned()
                    }
                } else {
                    sinks.iter().find(|s| s.contains("bluez_output")).cloned()
                };
                
                if let (Some(ref b_source), Some(ref b_sink)) = (bluez_source, bluez_sink) {
                    info!("Bluetooth call audio nodes discovered. Source: {}, Sink: {}", b_source, b_sink);
                    
                    // Unmute and set Bluetooth Source volume to 100%
                    let _ = Command::new("pactl").args(&["set-source-mute", b_source, "0"]).output().await;
                    let _ = Command::new("pactl").args(&["set-source-volume", b_source, "100%"]).output().await;

                    // Unmute and set Bluetooth Sink volume to 100%
                    let _ = Command::new("pactl").args(&["set-sink-mute", b_sink, "0"]).output().await;
                    let _ = Command::new("pactl").args(&["set-sink-volume", b_sink, "100%"]).output().await;

                    // Resolve physical microphone source and speaker sink
                    let phys_source = get_physical_mic_source().await;
                    let phys_sink = get_physical_speaker_sink().await;
                    info!("Using physical microphone source: {}, speaker sink: {}", phys_source, phys_sink);

                    // Ensure physical source & sink are unmuted and set volume
                    let _ = Command::new("pactl").args(&["set-source-mute", &phys_source, "0"]).output().await;
                    let _ = Command::new("pactl").args(&["set-source-volume", &phys_source, "80%"]).output().await;
                    let _ = Command::new("pactl").args(&["set-sink-mute", &phys_sink, "0"]).output().await;
                    let _ = Command::new("pactl").args(&["set-sink-volume", &phys_sink, "100%"]).output().await;

                    // Resolve speaker mode
                    let mode = {
                        let m = speaker_mode.lock().await;
                        m.clone()
                    };
                    info!("Active bluetooth speaker mode: {}", mode);

                    let (mod1, mod2) = if mode == "desktop_as_speaker" {
                        // Desktop as speaker to mobile
                        // Loop 1: Phone MIC (bluez source) -> PC Speakers (resolved physical sink)
                        let m1 = Command::new("pactl")
                            .args(&["load-module", "module-loopback", &format!("source={}", b_source), &format!("sink={}", phys_sink), "latency_msec=120"])
                            .output()
                            .await;
                        
                        // Loop 2: PC MIC (resolved physical source) -> Phone Speaker (bluez sink)
                        let m2 = Command::new("pactl")
                            .args(&["load-module", "module-loopback", &format!("source={}", phys_source), &format!("sink={}", b_sink), "latency_msec=120"])
                            .output()
                            .await;
                            
                        (m1, m2)
                    } else {
                        // Mobile as speaker to desktop
                        // Loop 1: PC Speakers Output (phys_sink.monitor) -> Phone Speaker (bluez sink)
                        let m1 = Command::new("pactl")
                            .args(&["load-module", "module-loopback", &format!("source={}.monitor", phys_sink), &format!("sink={}", b_sink), "latency_msec=120"])
                            .output()
                            .await;
                            
                        // Loop 2: No-op
                        let m2 = Command::new("true").output().await;
                        
                        (m1, m2)
                    };
                        
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

        // Restore original Bluetooth profile
        let mac_opt = {
            let m = self.bluetooth_mac.lock().await;
            m.clone()
        };
        
        if let Some(ref mac) = mac_opt {
            let orig_profile = {
                let mut orig = self.original_bluetooth_profile.lock().await;
                orig.take()
            };
            
            let card_name = match find_bluetooth_card(mac).await {
                Some(name) => name,
                None => {
                    warn!("Bluetooth card for MAC {} not found during cleanup.", mac);
                    return Ok(());
                }
            };
            
            let profile_to_set = orig_profile.unwrap_or_else(|| "a2dp-sink".to_string());
            info!("Restoring card {} profile to {}", card_name, profile_to_set);
            let _ = Command::new("pactl")
                .args(&["set-card-profile", &card_name, &profile_to_set])
                .output()
                .await;
        }
            
        Ok(())
    }

    pub async fn apply_speaker_mode_routing(&self) -> anyhow::Result<()> {
        // Clean up first
        let _ = self.cleanup_audio_routing().await;

        let mode = self.get_speaker_mode().await;
        if mode == "mobile_as_speaker" {
            info!("Establishing virtual routing for Mobile as Desktop Speaker...");
            
            // 1. Ensure the null sink is loaded
            let sinks = get_pactl_list("sinks").await.unwrap_or_default();
            let null_sink_exists = sinks.iter().any(|s| s.contains("platypus_null_sink"));
            if !null_sink_exists {
                info!("Loading platypus_null_sink module...");
                let _ = Command::new("pactl")
                    .args(&["load-module", "module-null-sink", "sink_name=platypus_null_sink", "sink_properties=device.description=PlatypusNullSink"])
                    .output()
                    .await;
            }

            // 2. Set the default sink to the null sink so PC plays exclusively to it
            let _ = Command::new("pactl").args(&["set-default-sink", "platypus_null_sink"]).output().await;
        } else {
            // desktop_as_speaker
            info!("Restoring physical speaker routing...");
            
            // 1. Get the actual physical sink
            let phys_sink = get_actual_physical_sink().await;
            
            // 2. Set default sink back to physical
            if phys_sink != "@DEFAULT_SINK@" {
                let _ = Command::new("pactl").args(&["set-default-sink", &phys_sink]).output().await;
            }

            // 3. Unload the null sink module if it exists
            let modules = get_pactl_modules().await;
            for (id, name, args) in modules {
                if name == "module-null-sink" && args.contains("platypus_null_sink") {
                    info!("Unloading platypus_null_sink module index: {}", id);
                    let _ = Command::new("pactl").args(&["unload-module", &id]).output().await;
                }
            }
        }
        Ok(())
    }
}

async fn find_bluetooth_card(mac: &str) -> Option<String> {
    let formatted_mac = mac.replace(":", "_").to_lowercase();
    let cards = get_pactl_list("cards").await.unwrap_or_default();
    if let Some(card) = cards.iter().find(|c| c.to_lowercase().contains(&formatted_mac)) {
        return Some(card.clone());
    }
    let raw_mac = mac.to_lowercase();
    if let Some(card) = cards.iter().find(|c| c.to_lowercase().contains(&raw_mac)) {
        return Some(card.clone());
    }
    // Fallback: any bluez card
    cards.into_iter().find(|c| c.to_lowercase().contains("bluez_card"))
}

async fn set_bluetooth_profile(mac: &str, headset: bool) -> anyhow::Result<Option<String>> {
    let card_name = match find_bluetooth_card(mac).await {
        Some(name) => name,
        None => {
            warn!("Bluetooth card for MAC {} not found.", mac);
            return Ok(None);
        }
    };

    info!("Found card name: {}", card_name);

    // Get card details to find profiles and active profile
    let output = Command::new("pactl")
        .args(&["list", "cards"])
        .output()
        .await?;

    if !output.status.success() {
        anyhow::bail!("Failed to run pactl list cards");
    }

    let text = String::from_utf8_lossy(&output.stdout);
    let mut current_card_match = false;
    let mut profiles = Vec::new();
    let mut active_profile = None;

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("Name: ") {
            let name = trimmed.strip_prefix("Name: ").unwrap().trim();
            if name == card_name {
                current_card_match = true;
            } else {
                current_card_match = false;
            }
        }

        if current_card_match {
            if trimmed.starts_with("Active Profile: ") {
                active_profile = trimmed.strip_prefix("Active Profile: ").map(|s| s.trim().to_string());
            } else if trimmed.starts_with("Profiles:") {
                // We are in the profiles section
            } else if trimmed.contains(":") && trimmed.contains("sinks:") {
                // A profile line: profile_name: Description (sinks: X, sources: Y, ...)
                if let Some(p_name) = trimmed.split(':').next() {
                    profiles.push(p_name.trim().to_string());
                }
            }
        }
    }

    info!("Profiles found for {}: {:?}", card_name, profiles);
    info!("Active profile for {}: {:?}", card_name, active_profile);

    if headset {
        // Find a suitable headset profile
        let target_profile = profiles.iter()
            .find(|p| p.contains("headset-head-unit") || p.contains("headset-audio-gateway") || p.contains("handsfree-audio-gateway") || p.contains("handsfree") || p.contains("hfp") || p.contains("hsp") || p.contains("headset"))
            .cloned();

        if let Some(profile) = target_profile {
            info!("Switching card {} to profile {}", card_name, profile);
            let _ = Command::new("pactl")
                .args(&["set-card-profile", &card_name, &profile])
                .output()
                .await;
        } else {
            warn!("No suitable headset profile found for card {}", card_name);
        }
    } else {
        // Switching back: try to find a2dp-sink, a2dp, or media profile
        let target_profile = profiles.iter()
            .find(|p| p.contains("a2dp-sink") || p.contains("a2dp") || p.contains("high-quality") || p.contains("media"))
            .cloned();

        if let Some(profile) = target_profile {
            info!("Switching card {} back to profile {}", card_name, profile);
            let _ = Command::new("pactl")
                .args(&["set-card-profile", &card_name, &profile])
                .output()
                .await;
        } else {
            warn!("No suitable A2DP profile found to restore card {}", card_name);
        }
    }

    Ok(active_profile)
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

async fn get_default_source_or_sink(default_type: &str) -> Option<String> {
    let output = Command::new("pactl")
        .arg("info")
        .output()
        .await
        .ok()?;
        
    if output.status.success() {
        let text = String::from_utf8_lossy(&output.stdout);
        for line in text.lines() {
            if line.starts_with(default_type) {
                return line.split(':').nth(1).map(|s| s.trim().to_string());
            }
        }
    }
    None
}

async fn get_physical_mic_source() -> String {
    let default_source = match get_default_source_or_sink("Default Source").await {
        Some(s) => s,
        None => "@DEFAULT_SOURCE@".to_string(),
    };

    if default_source.contains(".monitor") || default_source.is_empty() || default_source == "@DEFAULT_SOURCE@" {
        if let Ok(sources) = get_pactl_list("sources").await {
            let physical = sources.into_iter()
                .find(|s| s.contains("input") && !s.contains(".monitor"));
            if let Some(src) = physical {
                info!("Default source is a monitor or invalid. Selected physical input source: {}", src);
                return src;
            }
        }
    }
    
    default_source
}

async fn get_physical_speaker_sink() -> String {
    match get_default_source_or_sink("Default Sink").await {
        Some(s) if !s.is_empty() => s,
        _ => "@DEFAULT_SINK@".to_string(),
    }
}

async fn get_actual_physical_sink() -> String {
    let sinks = get_pactl_list("sinks").await.unwrap_or_default();
    if let Some(sink) = sinks.iter().find(|s| s.contains("alsa_output")).cloned() {
        sink
    } else {
        "@DEFAULT_SINK@".to_string()
    }
}

async fn get_pactl_modules() -> Vec<(String, String, String)> {
    let output = Command::new("pactl").args(&["list", "modules", "short"]).output().await;
    let mut result = Vec::new();
    if let Ok(out) = output {
        let text = String::from_utf8_lossy(&out.stdout);
        for line in text.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                let id = parts[0].to_string();
                let name = parts[1].to_string();
                let args = parts[2..].join(" ");
                result.push((id, name, args));
            }
        }
    }
    result
}
