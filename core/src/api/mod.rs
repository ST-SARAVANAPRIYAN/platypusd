use std::sync::Arc;
use axum::{
    routing::{get, post, delete},
    Router,
};
use tokio::sync::broadcast;
use serde::{Serialize, Deserialize};
use crate::db::{Database, LocalIdentity};
use crate::services::call::CallService;
use crate::services::clipboard::ClipboardService;
use crate::services::wifi_speaker::WifiSpeakerService;
use tower_http::cors::{Any, CorsLayer};

pub mod routes;
pub mod ws;

#[derive(Clone)]
pub struct AppState {
    pub db: Database,
    pub call_service: Arc<CallService>,
    pub clipboard_service: Arc<ClipboardService>,
    pub wifi_speaker_service: Arc<WifiSpeakerService>,
    pub identity: LocalIdentity,
    pub tx: broadcast::Sender<WsMessage>,
    pub active_connections: Arc<tokio::sync::Mutex<std::collections::HashSet<String>>>,
    pub connection_types: Arc<tokio::sync::Mutex<std::collections::HashMap<String, String>>>,
    pub device_ips: Arc<tokio::sync::Mutex<std::collections::HashMap<String, String>>>,
    pub active_bluetooth: Arc<tokio::sync::Mutex<std::collections::HashSet<String>>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WsMessage {
    pub event: String,
    pub data: serde_json::Value,
}

pub fn create_router(state: AppState) -> Router {
    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    Router::new()
        .route("/api/v1/status", get(routes::get_status))
        .route("/api/v1/pairing/request", post(routes::pairing_request))
        .route("/api/v1/pairing/confirm", post(routes::pairing_confirm))
        .route("/api/v1/pairing/unpair", post(routes::unpair_device_route))
        .route("/api/v1/calls/action", post(routes::calls_action))
        .route("/api/v1/calls/state", post(routes::update_call_state))
        .route("/api/v1/files/list", get(routes::list_files))
        .route("/api/v1/files/download", get(routes::download_file))
        .route("/api/v1/files/upload", post(routes::upload_file))
        .route("/api/v1/files/delete", delete(routes::delete_file))
        .route("/api/v1/clipboard", post(routes::update_clipboard))
        .route("/api/v1/clipboard/config", get(routes::get_clipboard_config).post(routes::set_clipboard_config))
        .route("/api/v1/bluetooth/open-settings", post(routes::open_bluetooth_settings_route))
        .route("/api/v1/bluetooth/disconnect", post(routes::disconnect_bluetooth_device_route))
        .route("/api/v1/speaker/start", post(routes::speaker_start))
        .route("/api/v1/speaker/stop", post(routes::speaker_stop))
        .route("/api/v1/audio/config", get(routes::get_audio_config).post(routes::set_audio_config))
        .route("/api/v1/calls/gateway/toggle", post(routes::toggle_call_gateway))
        .route("/api/v1/events", get(ws::ws_handler))

        .layer(cors)
        .with_state(state)
}
