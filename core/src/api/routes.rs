use axum::{
    extract::{State, Query},
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use crate::api::{AppState, WsMessage};
use crate::services::call::{ActiveCall, CallState};
use tracing::{info, warn, error};

#[derive(Serialize)]
pub struct StatusResponse {
    pub status: String,
    pub version: String,
    pub device_id: String,
    pub device_name: String,
    pub public_key: String,
    pub paired_devices: Vec<PairedDeviceDto>,
    pub active_call: Option<ActiveCall>,
    pub wifi_speaker_active: bool,
    pub call_gateway_enabled: bool,
    pub audio_config: crate::services::wifi_speaker::AudioConfig,
}

#[derive(Serialize)]
pub struct PairedDeviceDto {
    pub device_id: String,
    pub device_name: String,
    pub is_online: bool,
    pub is_bluetooth_connected: bool,
    pub connection_type: Option<String>,
    pub ip: Option<String>,
}

pub async fn get_status(State(state): State<AppState>) -> Json<StatusResponse> {
    let paired = state.db.get_paired_devices().await.unwrap_or_default();
    let active = state.active_connections.lock().await;
    let active_bt = state.active_bluetooth.lock().await;
    let types = state.connection_types.lock().await;
    let ips = state.device_ips.lock().await;
    let paired_dtos = paired.into_iter()
        .map(|(id, name)| {
            let is_online = active.contains(&id);
            let is_bluetooth_connected = active_bt.contains(&id);
            let connection_type = types.get(&id).cloned();
            let ip = ips.get(&id).cloned();
            PairedDeviceDto { 
                device_id: id, 
                device_name: name, 
                is_online, 
                is_bluetooth_connected,
                connection_type,
                ip
            }
        })
        .collect();

    let active_call = state.call_service.get_active_call().await;
    let audio_config = state.wifi_speaker_service.config.lock().await.clone();

    Json(StatusResponse {
        status: "online".to_string(),
        version: "0.1.0".to_string(),
        device_id: state.identity.device_id.clone(),
        device_name: state.identity.device_name.clone(),
        public_key: state.identity.public_key.clone(),
        paired_devices: paired_dtos,
        active_call,
        wifi_speaker_active: state.wifi_speaker_service.is_active(),
        call_gateway_enabled: state.call_service.is_gateway_enabled().await,
        audio_config,
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

    if payload.action == "accept" || payload.action == "mute" || payload.action == "unmute" {
        if state.wifi_speaker_service.is_active() {
            info!("Muting/Stopping Wi-Fi Speaker due to active call action");
            let _ = state.wifi_speaker_service.stop().await;
            let _ = state.tx.send(WsMessage {
                event: "WifiSpeakerStopped".to_string(),
                data: serde_json::json!({ "reason": "phone_call" }),
            });
        }
    }

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
    
    if payload.state != CallState::Disconnected {
        if state.wifi_speaker_service.is_active() {
            info!("Muting/Stopping Wi-Fi Speaker due to active call state");
            let _ = state.wifi_speaker_service.stop().await;
            let _ = state.tx.send(WsMessage {
                event: "WifiSpeakerStopped".to_string(),
                data: serde_json::json!({ "reason": "phone_call" }),
            });
        }
    }
    
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
    
    let _ = state.tx.send(crate::api::WsMessage {
        event: "ClipboardConfigChanged".to_string(),
        data: serde_json::json!({
            "direction": cfg.direction.clone(),
            "auto_sync": cfg.auto_sync,
        }),
    });

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

#[derive(Deserialize)]
pub struct FileListParams {
    pub path: Option<String>,
}

pub async fn list_files(
    Query(params): Query<FileListParams>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let path_str = match params.path {
        Some(ref p) if !p.trim().is_empty() => p.clone(),
        _ => "/home/saravana".to_string(),
    };
    let path = std::path::Path::new(&path_str);
    
    if !path.exists() {
        return Err((StatusCode::NOT_FOUND, "Directory not found".to_string()));
    }
    
    let entries = std::fs::read_dir(path)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
        
    let mut list = Vec::new();
    for entry in entries {
        if let Ok(entry) = entry {
            let file_name = entry.file_name().to_string_lossy().into_owned();
            let metadata = entry.metadata().ok();
            let is_dir = metadata.as_ref().map(|m| m.is_dir()).unwrap_or(false);
            let size = metadata.as_ref().map(|m| m.len()).unwrap_or(0);
            let abs_path = entry.path().to_string_lossy().into_owned();
            
            let last_modified = metadata.as_ref()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            
            list.push(serde_json::json!({
                "name": file_name,
                "is_dir": is_dir,
                "size": size,
                "path": abs_path,
                "last_modified": last_modified
            }));
        }
    }
    
    Ok(Json(serde_json::json!(list)))
}

pub async fn download_file(
    Query(params): Query<FileListParams>,
) -> Result<axum::response::Response, (StatusCode, String)> {
    let path_str = params.path.ok_or_else(|| (StatusCode::BAD_REQUEST, "Missing path".to_string()))?;
    let path = std::path::Path::new(&path_str);
    
    if !path.exists() || !path.is_file() {
        return Err((StatusCode::NOT_FOUND, "File not found".to_string()));
    }
    
    let file = tokio::fs::File::open(path)
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
        
    let stream = tokio_util::io::ReaderStream::new(file);
    let body = axum::body::Body::from_stream(stream);
    
    let file_name = path.file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file");
        
    let response = axum::response::Response::builder()
        .header("Content-Type", "application/octet-stream")
        .header("Content-Disposition", format!("attachment; filename=\"{}\"", file_name))
        .body(body)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
        
    Ok(response)
}

pub async fn upload_file(
    Query(params): Query<FileListParams>,
    mut multipart: axum::extract::Multipart,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let dir_path_str = params.path.ok_or_else(|| (StatusCode::BAD_REQUEST, "Missing path".to_string()))?;
    let dir_path = std::path::Path::new(&dir_path_str);
    
    if !dir_path.exists() || !dir_path.is_dir() {
        return Err((StatusCode::BAD_REQUEST, "Invalid upload directory".to_string()));
    }
    
    while let Ok(Some(field)) = multipart.next_field().await {
        let file_name = field.file_name().unwrap_or("file.bin").to_string();
        let data = field.bytes().await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("Failed to read field: {}", e)))?;
            
        let dest_path = dir_path.join(file_name);
        tokio::fs::write(&dest_path, data).await
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("Failed to write file: {}", e)))?;
        info!("Successfully uploaded file to: {:?}", dest_path);
    }
    
    Ok(Json(serde_json::json!({ "success": true })))
}

pub async fn delete_file(
    Query(params): Query<FileListParams>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let path_str = params.path.ok_or_else(|| (StatusCode::BAD_REQUEST, "Missing path".to_string()))?;
    let path = std::path::Path::new(&path_str);
    
    if !path.exists() {
        return Err((StatusCode::NOT_FOUND, "File not found".to_string()));
    }
    
    if path.is_dir() {
        std::fs::remove_dir_all(path)
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("Failed to delete directory: {}", e)))?;
    } else {
        std::fs::remove_file(path)
            .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("Failed to delete file: {}", e)))?;
    }
    
    Ok(Json(serde_json::json!({ "success": true })))
}

#[derive(Deserialize)]
pub struct SpeakerStartRequest {
    pub device_id: String,
    pub playback_mode: Option<String>,
}

pub async fn speaker_start(
    State(state): State<AppState>,
    Json(payload): Json<SpeakerStartRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Speaker start requested for device ID: {} with playback mode: {:?}", payload.device_id, payload.playback_mode);
    
    let ips = state.device_ips.lock().await;
    let target_ip = match ips.get(&payload.device_id) {
        Some(ip) => ip.clone(),
        None => {
            warn!("Device ID {} not found in active connection IPs", payload.device_id);
            return (StatusCode::BAD_REQUEST, Json(serde_json::json!({
                "success": false,
                "error": format!("Device IP not found for ID: {}", payload.device_id)
            })));
        }
    };
    
    match state.wifi_speaker_service.start(target_ip, payload.playback_mode.clone()).await {
        Ok(_) => {
            {
                let mut cfg = state.wifi_speaker_service.config.lock().await;
                cfg.wifi_speaker_active = true;
                if let Some(ref pm) = payload.playback_mode {
                    cfg.playback_mode = pm.clone();
                }
            }
            let final_cfg = state.wifi_speaker_service.config.lock().await.clone();
            let _ = state.tx.send(WsMessage {
                event: "AudioConfigChanged".to_string(),
                data: serde_json::to_value(&final_cfg).unwrap_or_default(),
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

pub async fn speaker_stop(
    State(state): State<AppState>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Speaker stop requested");
    
    match state.wifi_speaker_service.stop().await {
        Ok(_) => {
            {
                let mut cfg = state.wifi_speaker_service.config.lock().await;
                cfg.wifi_speaker_active = false;
            }
            let final_cfg = state.wifi_speaker_service.config.lock().await.clone();
            let _ = state.tx.send(WsMessage {
                event: "AudioConfigChanged".to_string(),
                data: serde_json::to_value(&final_cfg).unwrap_or_default(),
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

pub async fn get_audio_config(
    State(state): State<AppState>,
) -> Json<crate::services::wifi_speaker::AudioConfig> {
    let cfg = state.wifi_speaker_service.config.lock().await.clone();
    Json(cfg)
}

pub async fn set_audio_config(
    State(state): State<AppState>,
    Json(payload): Json<crate::services::wifi_speaker::AudioConfig>,
) -> (StatusCode, Json<serde_json::Value>) {
    let mut cfg = state.wifi_speaker_service.config.lock().await;
    let old_active = cfg.wifi_speaker_active;
    let old_mode = cfg.playback_mode.clone();
    let old_dir = cfg.audio_direction.clone();
    
    cfg.audio_direction = payload.audio_direction.clone();
    cfg.playback_mode = payload.playback_mode.clone();
    cfg.wifi_speaker_active = payload.wifi_speaker_active;
    
    info!("Updated audio configuration: direction={}, mode={}, active={}", cfg.audio_direction, cfg.playback_mode, cfg.wifi_speaker_active);

    let mut start_speaker = false;
    let mut stop_speaker = false;

    if cfg.wifi_speaker_active {
        if cfg.audio_direction == "mobile_to_desktop" {
            stop_speaker = true;
            cfg.wifi_speaker_active = false;
        } else if !old_active || old_mode != cfg.playback_mode || old_dir != cfg.audio_direction {
            if old_active {
                stop_speaker = true;
            }
            start_speaker = true;
        }
    } else if old_active {
        stop_speaker = true;
    }

    let cfg_clone = cfg.clone();
    drop(cfg);

    if stop_speaker {
        let _ = state.wifi_speaker_service.stop().await;
    }

    if start_speaker {
        let ips = state.device_ips.lock().await;
        if let Some(target_ip) = ips.values().next() {
            let _ = state.wifi_speaker_service.start(target_ip.clone(), Some(cfg_clone.playback_mode.clone())).await;
        } else {
            warn!("No connected devices found to stream audio to.");
            let mut cfg = state.wifi_speaker_service.config.lock().await;
            cfg.wifi_speaker_active = false;
        }
    }

    let final_cfg = state.wifi_speaker_service.config.lock().await.clone();
    let _ = state.tx.send(WsMessage {
        event: "AudioConfigChanged".to_string(),
        data: serde_json::to_value(&final_cfg).unwrap_or_default(),
    });

    (StatusCode::OK, Json(serde_json::json!({ "success": true })))
}

#[derive(Deserialize)]
pub struct GatewayToggleRequest {
    pub enabled: bool,
}

pub async fn toggle_call_gateway(
    State(state): State<AppState>,
    Json(payload): Json<GatewayToggleRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    info!("Toggling Bluetooth call gateway to: {}", payload.enabled);
    state.call_service.set_gateway_enabled(payload.enabled).await;
    (StatusCode::OK, Json(serde_json::json!({ "success": true })))
}

