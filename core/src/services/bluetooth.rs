use std::process::Command;
use tracing::{info, warn};

pub fn check_bluetooth_connected_devices() -> Vec<(String, String)> {
    let output = Command::new("bluetoothctl")
        .args(&["devices", "Connected"])
        .output();
    
    let mut connected = Vec::new();
    if let Ok(out) = output {
        let text = String::from_utf8_lossy(&out.stdout);
        for line in text.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 3 && parts[0] == "Device" {
                let mac = parts[1].to_string();
                let name = parts[2..].join(" ");
                connected.push((mac, name));
            }
        }
    }
    connected
}

pub fn open_bluetooth_settings() -> bool {
    let commands = [
        ("blueman-manager", vec![]),
        ("gnome-control-center", vec!["bluetooth"]),
        ("kde-open5", vec!["settings://bluetooth"]),
        ("xdg-open", vec!["x-apple.systempreferences:com.apple.preferences.sharing"]),
    ];
    for (cmd, args) in commands.iter() {
        if Command::new(cmd)
            .args(args)
            .spawn()
            .is_ok()
        {
            info!("Launched Bluetooth settings GUI using: {}", cmd);
            return true;
        }
    }
    warn!("Failed to launch any Bluetooth settings GUI");
    false
}

pub fn disconnect_bluetooth_device(mac: &str) -> bool {
    info!("Running bluetoothctl disconnect for {}", mac);
    let output = Command::new("bluetoothctl")
        .args(&["disconnect", mac])
        .output();
        
    match output {
        Ok(out) => {
            let out_str = String::from_utf8_lossy(&out.stdout);
            info!("bluetoothctl disconnect output: {}", out_str.trim());
            out.status.success()
        }
        Err(e) => {
            warn!("Failed to run bluetoothctl disconnect command: {}", e);
            false
        }
    }
}
