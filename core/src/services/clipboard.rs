use std::sync::Arc;
use tokio::sync::Mutex;
use tokio::process::Command;
use tracing::{info, warn};
use tokio::sync::broadcast;
use crate::api::WsMessage;

#[derive(Clone)]
pub struct ClipboardConfig {
    pub direction: String, // "bidirectional", "desktop_to_mobile", "mobile_to_desktop"
    pub auto_sync: bool,
}

pub struct ClipboardService {
    last_clipboard: Arc<Mutex<String>>,
    tx: broadcast::Sender<WsMessage>,
    pub config: Arc<Mutex<ClipboardConfig>>,
    suppress_broadcast_until: Arc<Mutex<Option<std::time::Instant>>>,
}

impl ClipboardService {
    pub fn new(tx: broadcast::Sender<WsMessage>) -> Self {
        Self {
            last_clipboard: Arc::new(Mutex::new(String::new())),
            tx,
            config: Arc::new(Mutex::new(ClipboardConfig {
                direction: "bidirectional".to_string(),
                auto_sync: true,
            })),
            suppress_broadcast_until: Arc::new(Mutex::new(None)),
        }
    }

    /// Update the system clipboard (invoked when text is sent from the phone)
    pub async fn set_clipboard_text(&self, text: String) -> anyhow::Result<()> {
        info!("Updating host system clipboard with: {}", text);
        
        {
            let mut suppress = self.suppress_broadcast_until.lock().await;
            *suppress = Some(std::time::Instant::now() + std::time::Duration::from_secs(2));
        }

        let mut last = self.last_clipboard.lock().await;
        *last = text.clone();

        // Attempt Wayland wl-copy first
        let child = Command::new("wl-copy")
            .stdin(std::process::Stdio::piped())
            .spawn();

        if let Ok(mut c) = child {
            if let Some(mut stdin) = c.stdin.take() {
                use tokio::io::AsyncWriteExt;
                let _ = stdin.write_all(text.as_bytes()).await;
            }
            let _ = c.wait().await;
            return Ok(());
        }

        // Fallback to X11 xclip
        let child = Command::new("xclip")
            .args(["-selection", "clipboard"])
            .stdin(std::process::Stdio::piped())
            .spawn();

        if let Ok(mut c) = child {
            if let Some(mut stdin) = c.stdin.take() {
                use tokio::io::AsyncWriteExt;
                let _ = stdin.write_all(text.as_bytes()).await;
            }
            let _ = c.wait().await;
            return Ok(());
        }

        warn!("Neither wl-copy nor xclip are available on this host. Clipboard sync ignored.");
        Ok(())
    }

    /// Read the current host clipboard content
    pub async fn get_clipboard_text(&self) -> String {
        // Try wl-paste (Wayland)
        let output = Command::new("wl-paste")
            .arg("-n") // No trailing newline
            .output()
            .await;

        if let Ok(out) = output {
            if out.status.success() {
                return String::from_utf8_lossy(&out.stdout).to_string();
            }
        }

        // Try xclip (X11)
        let output = Command::new("xclip")
            .args(["-selection", "clipboard", "-o"])
            .output()
            .await;

        if let Ok(out) = output {
            if out.status.success() {
                return String::from_utf8_lossy(&out.stdout).to_string();
            }
        }

        String::new()
    }

    /// Spawns a background worker that polls the system clipboard for updates
    pub fn start_monitoring(self: Arc<Self>) {
        tokio::spawn(async move {
            info!("Clipboard monitoring background worker started.");
            
            // Initialize last clipboard state
            let initial = self.get_clipboard_text().await;
            {
                let mut last = self.last_clipboard.lock().await;
                *last = initial;
            }

            let mut interval = tokio::time::interval(tokio::time::Duration::from_millis(1500));
            let suppress_broadcast_until = self.suppress_broadcast_until.clone();
            loop {
                interval.tick().await;

                // Read config settings dynamically
                let (auto_sync, direction) = {
                    let cfg = self.config.lock().await;
                    (cfg.auto_sync, cfg.direction.clone())
                };

                if !auto_sync || (direction != "bidirectional" && direction != "desktop_to_mobile") {
                    continue;
                }

                let current = self.get_clipboard_text().await;
                if current.is_empty() {
                    continue;
                }

                let mut last = self.last_clipboard.lock().await;
                if current.trim() != last.trim() {
                    info!("Host clipboard update detected (length: {}). Syncing to devices.", current.len());
                    *last = current.clone();

                    // Check if broadcast is currently suppressed
                    let suppressed = {
                        let suppress = suppress_broadcast_until.lock().await;
                        if let Some(until) = *suppress {
                            std::time::Instant::now() < until
                        } else {
                            false
                        }
                    };

                    if suppressed {
                        info!("Broadcast suppressed because clipboard update was recently pushed from mobile.");
                    } else {
                        // Broadcast to websocket clients
                        let _ = self.tx.send(WsMessage {
                            event: "ClipboardSynced".to_string(),
                            data: serde_json::json!({ "text": current }),
                        });
                    }
                }
            }
        });
    }
}
