use axum::{
    extract::State,
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use crate::api::{AppState, WsMessage};
use crate::services::call::{ActiveCall, CallState};
use tracing::{info, error};

#[derive(Serialize)]
pub struct StatusResponse {
    pub status: String,
    pub version: String,
    pub device_id: String,
    pub device_name: String,
    pub public_key: String,
    pub paired_devices: Vec<PairedDeviceDto>,
    pub active_call: Option<ActiveCall>,
}

#[derive(Serialize)]
pub struct PairedDeviceDto {
    pub device_id: String,
    pub device_name: String,
    pub is_online: bool,
    pub is_bluetooth_connected: bool,
}

pub async fn get_status(State(state): State<AppState>) -> Json<StatusResponse> {
    let paired = state.db.get_paired_devices().await.unwrap_or_default();
    let active = state.active_connections.lock().await;
    let active_bt = state.active_bluetooth.lock().await;
    let paired_dtos = paired.into_iter()
        .map(|(id, name)| {
            let is_online = active.contains(&id);
            let is_bluetooth_connected = active_bt.contains(&id);
            PairedDeviceDto { device_id: id, device_name: name, is_online, is_bluetooth_connected }
        })
        .collect();

    let active_call = state.call_service.get_active_call().await;

    Json(StatusResponse {
        status: "online".to_string(),
        version: "0.1.0".to_string(),
        device_id: state.identity.device_id.clone(),
        device_name: state.identity.device_name.clone(),
        public_key: state.identity.public_key.clone(),
        paired_devices: paired_dtos,
        active_call,
    })
}

#[derive(Deserialize)]
pub struct PairingRequest {
    pub device_id: String,
    pub device_name: String,
    pub public_key: String,
}

pub async fn pairing_request(
    State(state): State<AppState>,
    Json(payload): Json<PairingRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Pairing request received from {} ({})", payload.device_name, payload.device_id);
    
    // In Phase 1, we auto-register the device to simplify testing
    match state.db.register_device(&payload.device_id, &payload.device_name, &payload.public_key).await {
        Ok(_) => {
            info!("Successfully paired with device: {}", payload.device_name);
            
            // Broadcast DevicePaired event so the desktop UI can update immediately
            let _ = state.tx.send(WsMessage {
                event: "DevicePaired".to_string(),
                data: serde_json::json!({
                    "device_id": payload.device_id.clone(),
                    "device_name": payload.device_name.clone()
                }),
            });

            (StatusCode::OK, Json(serde_json::json!({
                "status": "paired",
                "message": "Device paired successfully"
            })))
        }
        Err(e) => {
            error!("Failed to register device: {}", e);
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
                "status": "error",
                "message": format!("Failed to register device: {}", e)
            })))
        }
    }
}

#[derive(Deserialize)]
pub struct UnpairRequest {
    pub device_id: String,
}

pub async fn unpair_device_route(
    State(state): State<AppState>,
    Json(payload): Json<UnpairRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Unpair requested for device_id {}", payload.device_id);
    match state.db.unpair_device(&payload.device_id).await {
        Ok(_) => {
            // Also notify WebSockets about the unpairing
            let _ = state.tx.send(WsMessage {
                event: "DeviceUnpaired".to_string(),
                data: serde_json::json!({ "device_id": payload.device_id }),
            });
            (StatusCode::OK, Json(serde_json::json!({ "success": true })))
        }
        Err(e) => {
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
                "success": false,
                "error": e.to_string()
            })))
        }
    }
}

#[derive(Deserialize)]
pub struct PairingConfirm {
    pub device_id: String,
}

pub async fn pairing_confirm(
    State(state): State<AppState>,
    Json(payload): Json<PairingConfirm>,
) -> (StatusCode, Json<serde_json::Value>) {
    // Check if the device is registered
    match state.db.is_device_paired(&payload.device_id).await {
        Ok(true) => {
            (StatusCode::OK, Json(serde_json::json!({
                "status": "confirmed",
                "message": "Device pairing is active"
            })))
        }
        Ok(false) => {
            (StatusCode::NOT_FOUND, Json(serde_json::json!({
                "status": "not_found",
                "message": "Device ID is not paired"
            })))
        }
        Err(e) => {
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
                "status": "error",
                "message": e.to_string()
            })))
        }
    }
}

#[derive(Deserialize)]
pub struct CallActionRequest {
    pub action: String,
    pub call_id: String,
}

pub async fn calls_action(
    State(state): State<AppState>,
    Json(payload): Json<CallActionRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Call action requested: {} for call_id {}", payload.action, payload.call_id);
    
    // Broadcast the action to websockets (so the mobile phone receives it and answers/declines)
    let _ = state.tx.send(WsMessage {
        event: "CallActionDispatched".to_string(),
        data: serde_json::json!({
            "action": payload.action.clone(),
            "call_id": payload.call_id.clone()
        }),
    });

    match state.call_service.execute_action(&payload.action).await {
        Ok(_) => {
            // Broadcast call event to websockets
            let mut active_call = state.call_service.get_active_call().await;
            if let Some(ref mut call) = active_call {
                if payload.action == "mute" {
                    call.state = CallState::Muted;
                } else if payload.action == "unmute" {
                    call.state = CallState::Connected;
                } else if payload.action == "reject" {
                    call.state = CallState::Disconnected;
                }
                
                let _ = state.tx.send(WsMessage {
                    event: "CallStateChanged".to_string(),
                    data: serde_json::to_value(&call).unwrap_or_default(),
                });
            }

            (StatusCode::OK, Json(serde_json::json!({
                "success": true
            })))
        }
        Err(e) => {
            (StatusCode::BAD_REQUEST, Json(serde_json::json!({
                "success": false,
                "error": e.to_string()
            })))
        }
    }
}

pub async fn update_call_state(
    State(state): State<AppState>,
    Json(payload): Json<ActiveCall>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Call state update pushed: {:?}", payload);
    
    let updated = state.call_service.update_call_state(payload.clone()).await;
    
    // Broadcast the update via WS
    let _ = state.tx.send(WsMessage {
        event: "CallStateChanged".to_string(),
        data: serde_json::to_value(&payload).unwrap_or_default(),
    });

    (StatusCode::OK, Json(serde_json::json!({
        "success": true,
        "active_call": updated
    })))
}

#[derive(Deserialize)]
pub struct ClipboardPayload {
    pub text: String,
}

pub async fn update_clipboard(
    State(state): State<AppState>,
    Json(payload): Json<ClipboardPayload>,
) -> (StatusCode, Json<serde_json::Value>) {
    let (auto_sync, direction) = {
        let cfg = state.clipboard_service.config.lock().await;
        (cfg.auto_sync, cfg.direction.clone())
    };

    if !auto_sync || (direction != "bidirectional" && direction != "mobile_to_desktop") {
        info!("Clipboard update from mobile ignored (settings: direction={}, auto_sync={})", direction, auto_sync);
        return (StatusCode::OK, Json(serde_json::json!({ "success": true, "ignored": true })));
    }

    info!("Clipboard update pushed from device (length: {})", payload.text.len());
    match state.clipboard_service.set_clipboard_text(payload.text).await {
        Ok(_) => {
            (StatusCode::OK, Json(serde_json::json!({ "success": true })))
        }
        Err(e) => {
            (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
                "success": false,
                "error": e.to_string()
            })))
        }
    }
}

#[derive(Deserialize, Serialize, Clone)]
pub struct ClipboardConfigDto {
    pub direction: String,
    pub auto_sync: bool,
}

pub async fn get_clipboard_config(
    State(state): State<AppState>,
) -> Json<ClipboardConfigDto> {
    let cfg = state.clipboard_service.config.lock().await;
    Json(ClipboardConfigDto {
        direction: cfg.direction.clone(),
        auto_sync: cfg.auto_sync,
    })
}

pub async fn set_clipboard_config(
    State(state): State<AppState>,
    Json(payload): Json<ClipboardConfigDto>,
) -> (StatusCode, Json<serde_json::Value>) {
    let mut cfg = state.clipboard_service.config.lock().await;
    cfg.direction = payload.direction;
    cfg.auto_sync = payload.auto_sync;
    info!("Updated clipboard configuration: direction={}, auto_sync={}", cfg.direction, cfg.auto_sync);
    (StatusCode::OK, Json(serde_json::json!({ "success": true })))
}

#[derive(Deserialize)]
pub struct BluetoothDisconnectRequest {
    pub device_id: String,
}

pub async fn open_bluetooth_settings_route() -> (StatusCode, Json<serde_json::Value>) {
    if crate::services::bluetooth::open_bluetooth_settings() {
        (StatusCode::OK, Json(serde_json::json!({ "success": true })))
    } else {
        (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
            "success": false,
            "error": "Failed to launch Bluetooth settings GUI. Please open it manually."
        })))
    }
}

pub async fn disconnect_bluetooth_device_route(
    State(state): State<AppState>,
    Json(payload): Json<BluetoothDisconnectRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Request to disconnect Bluetooth device_id: {}", payload.device_id);
    
    // Find device name by ID
    let paired = state.db.get_paired_devices().await.unwrap_or_default();
    let dev_name = paired.into_iter()
        .find(|(id, _)| id == &payload.device_id)
        .map(|(_, name)| name);
        
    if let Some(name) = dev_name {
        // Query bluetoothctl devices Connected to find MAC
        let connected_bt = crate::services::bluetooth::check_bluetooth_connected_devices();
        let target_mac = connected_bt.into_iter()
            .find(|(_, bt_name)| bt_name.to_lowercase().contains(&name.to_lowercase()) || name.to_lowercase().contains(&bt_name.to_lowercase()))
            .map(|(mac, _)| mac);
            
        if let Some(mac) = target_mac {
            info!("Found matching Bluetooth MAC address: {} for device name: {}", mac, name);
            if crate::services::bluetooth::disconnect_bluetooth_device(&mac) {
                // Update active_bluetooth state
                let mut active_bt = state.active_bluetooth.lock().await;
                active_bt.remove(&payload.device_id);
                
                // Broadcast event
                let _ = state.tx.send(WsMessage {
                    event: "BluetoothStateChanged".to_string(),
                    data: serde_json::json!({
                        "device_id": payload.device_id.clone(),
                        "is_connected": false
                    }),
                });
                
                (StatusCode::OK, Json(serde_json::json!({ "success": true })))
            } else {
                (StatusCode::INTERNAL_SERVER_ERROR, Json(serde_json::json!({
                    "success": false,
                    "error": "Failed to run disconnect command."
                })))
            }
        } else {
            (StatusCode::NOT_FOUND, Json(serde_json::json!({
                "success": false,
                "error": format!("No active Bluetooth connection found matching device: {}", name)
            })))
        }
    } else {
        (StatusCode::NOT_FOUND, Json(serde_json::json!({
            "success": false,
            "error": "Device not paired"
        })))
    }
}

