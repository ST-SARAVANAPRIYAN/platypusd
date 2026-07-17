use std::sync::Arc;
use tokio::sync::broadcast;
use tracing::{info, error};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

mod db;
mod discovery;
mod api;
mod services;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Initialize tracing subscriber for logging
    tracing_subscriber::registry()
        .with(tracing_subscriber::EnvFilter::new(
            std::env::var("RUST_LOG").unwrap_or_else(|_| "platypusd_core=info,info".into()),
        ))
        .with(tracing_subscriber::fmt::layer())
        .init();

    info!("Starting platypusd core daemon...");

    // Initialize Database
    let db = db::Database::init().await?;

    // Retrieve or create local cryptographic identity
    let identity = db.get_or_create_identity().await?;
    info!("Local Identity Loaded: {}", identity.device_name);
    info!("Device ID: {}", identity.device_id);
    info!("Public Key fingerprint: {}", &identity.public_key[..16]);

    // Initialize Call Service
    let call_service = Arc::new(services::call::CallService::new());

    // Create broadcast channel for WebSocket real-time events
    let (tx, _rx) = broadcast::channel(100);

    // Initialize Clipboard Service
    let clipboard_service = Arc::new(services::clipboard::ClipboardService::new(tx.clone()));
    clipboard_service.clone().start_monitoring();

    // Initialize Wi-Fi Speaker Service
    let wifi_speaker_service = Arc::new(services::wifi_speaker::WifiSpeakerService::new());

    // Initialize & Start Local Network Discovery (mDNS)
    let port: u16 = 8080;

    // Initialize RFCOMM Bootstrapping Server (Bluetooth)
    services::rfcomm::start_rfcomm_server(db.clone(), port);
    match discovery::DiscoveryManager::new() {
        Ok(discovery_manager) => {
            if let Err(e) = discovery_manager.start(&identity.device_id, &identity.device_name, &identity.public_key, port) {
                error!("Failed to start local discovery: {}", e);
            }
        }
        Err(e) => {
            error!("Could not initialize discovery manager: {}", e);
        }
    }

    // Build Axum application state
    let app_state = api::AppState {
        db,
        call_service,
        clipboard_service,
        wifi_speaker_service,
        identity,
        tx,
        active_connections: Arc::new(tokio::sync::Mutex::new(std::collections::HashSet::new())),
        connection_types: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
        device_ips: Arc::new(tokio::sync::Mutex::new(std::collections::HashMap::new())),
        active_bluetooth: Arc::new(tokio::sync::Mutex::new(std::collections::HashSet::new())),
    };

    // Periodically poll connected Bluetooth devices and update active_bluetooth state
    let db_clone = app_state.db.clone();
    let active_bt_clone = app_state.active_bluetooth.clone();
    let active_connections_clone = app_state.active_connections.clone();
    let tx_clone = app_state.tx.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
            
            let connected_bt = services::bluetooth::check_bluetooth_connected_devices();
            let paired = db_clone.get_paired_devices().await.unwrap_or_default();
            
            let active_connections = active_connections_clone.lock().await;
            let mut active_bt = active_bt_clone.lock().await;
            for (id, name) in paired {
                if active_connections.contains(&id) {
                    continue;
                }
                let is_connected_now = connected_bt.iter().any(|(_, bt_name)| {
                    bt_name.to_lowercase().contains(&name.to_lowercase()) || name.to_lowercase().contains(&bt_name.to_lowercase())
                });
                
                let is_connected_currently = active_bt.contains(&id);
                if is_connected_now && !is_connected_currently {
                    info!("Bluetooth connection detected dynamically for device: {} ({})", name, id);
                    active_bt.insert(id.clone());
                    let _ = tx_clone.send(api::WsMessage {
                        event: "BluetoothStateChanged".to_string(),
                        data: serde_json::json!({
                            "device_id": id,
                            "is_connected": true
                        }),
                    });
                } else if !is_connected_now && is_connected_currently {
                    info!("Bluetooth disconnection detected dynamically for device: {} ({})", name, id);
                    active_bt.remove(&id);
                    let _ = tx_clone.send(api::WsMessage {
                        event: "BluetoothStateChanged".to_string(),
                        data: serde_json::json!({
                            "device_id": id,
                            "is_connected": false
                        }),
                    });
                }
            }
        }
    });

    // Instantiate Axum Router
    let app = api::create_router(app_state.clone());


    // Run Axum HTTP/WebSocket server
    let bind_addr = format!("0.0.0.0:{}", port);
    info!("Exposing public endpoints on http://{}", bind_addr);

    let listener = tokio::net::TcpListener::bind(&bind_addr).await?;
    axum::serve(listener, app.into_make_service_with_connect_info::<std::net::SocketAddr>()).await?;

    Ok(())
}
