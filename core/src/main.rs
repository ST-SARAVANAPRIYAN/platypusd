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

    // Initialize & Start Local Network Discovery (mDNS)
    let port: u16 = 8080;
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
        identity,
        tx,
        active_connections: Arc::new(tokio::sync::Mutex::new(std::collections::HashSet::new())),
    };

    // Instantiate Axum Router
    let app = api::create_router(app_state);

    // Run Axum HTTP/WebSocket server
    let bind_addr = format!("0.0.0.0:{}", port);
    info!("Exposing public endpoints on http://{}", bind_addr);

    let listener = tokio::net::TcpListener::bind(&bind_addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
