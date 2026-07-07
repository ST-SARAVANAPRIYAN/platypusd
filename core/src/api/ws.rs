use axum::{
    extract::{ws::{Message, WebSocket, WebSocketUpgrade}, State, Query, connect_info::ConnectInfo},
    response::IntoResponse,
};
use std::net::SocketAddr;
use futures_util::{sink::SinkExt, stream::StreamExt};
use crate::api::{AppState, WsMessage};
use tracing::{info, warn};
use serde::Deserialize;

#[derive(Deserialize)]
pub struct WsParams {
    pub device_id: Option<String>,
    pub connection_type: Option<String>,
}

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Query(params): Query<WsParams>,
    State(state): State<AppState>,
) -> impl IntoResponse {
    let peer_ip = addr.ip().to_string();
    ws.on_upgrade(move |socket| handle_socket(socket, params.device_id, params.connection_type, Some(peer_ip), state))
}

async fn handle_socket(
    socket: WebSocket,
    device_id: Option<String>,
    connection_type: Option<String>,
    peer_ip: Option<String>,
    state: AppState,
) {
    info!("New WebSocket connection established. Device ID: {:?}, Connection Type: {:?}, IP: {:?}", device_id, connection_type, peer_ip);
    let (mut sender, mut receiver) = socket.split();
    let mut rx = state.tx.subscribe();

    let mut is_mobile = false;
    let mut dev_id = String::new();

    if let Some(ref id) = device_id {
        if id != "desktop" {
            is_mobile = true;
            dev_id = id.clone();
            info!("Mobile device connected via WS: {} ({:?}) at IP {:?}", dev_id, connection_type, peer_ip);
            
            {
                let mut active = state.active_connections.lock().await;
                active.insert(dev_id.clone());
            }
            {
                let mut types = state.connection_types.lock().await;
                types.insert(dev_id.clone(), connection_type.unwrap_or_else(|| "Local Network".to_string()));
            }
            {
                let mut ips = state.device_ips.lock().await;
                if let Some(ref ip) = peer_ip {
                    ips.insert(dev_id.clone(), ip.clone());
                }
            }

            // Broadcast connection update
            let _ = state.tx.send(WsMessage {
                event: "DeviceConnected".to_string(),
                data: serde_json::json!({ "device_id": dev_id.clone() }),
            });
        }
    }

    // Spawn a task to forward events from the broadcast channel to the WebSocket sender
    let dev_id_clone = dev_id.clone();
    let mut send_task = tokio::spawn(async move {
        loop {
            match rx.recv().await {
                Ok(msg) => {
                    // If the device was unpaired, terminate its WebSocket connection immediately!
                    if msg.event == "DeviceUnpaired" {
                        if let Some(target_id) = msg.data.get("device_id").and_then(|id| id.as_str()) {
                            if target_id == dev_id_clone {
                                warn!("Device {} was unpaired. Terminating WebSocket connection.", dev_id_clone);
                                break;
                            }
                        }
                    }

                    let serialized = serde_json::to_string(&msg).unwrap_or_default();
                    if sender.send(Message::Text(serialized)).await.is_err() {
                        break; // Client disconnected
                    }
                }
                Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                    warn!("WebSocket send task lagged behind, skipped {} messages", skipped);
                    continue;
                }
                Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                    break;
                }
            }
        }
    });

    // Spawn a task to read from the WebSocket receiver and handle incoming commands
    let state_clone = state.clone();
    let mut recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = receiver.next().await {
            if let Message::Text(text) = msg {
                // Parse commands sent by client if any (e.g. TriggerCallAction)
                if let Ok(ws_cmd) = serde_json::from_str::<serde_json::Value>(&text) {
                    if let Some(cmd) = ws_cmd.get("command").and_then(|c| c.as_str()) {
                        info!("Received websocket command: {}", cmd);
                        if cmd == "TriggerCallAction" {
                            if let Some(data) = ws_cmd.get("data") {
                                if let (Some(action), Some(_call_id)) = (
                                    data.get("action").and_then(|a| a.as_str()),
                                    data.get("call_id").and_then(|c| c.as_str()),
                                ) {
                                    info!("Executing websocket triggered call action: {}", action);
                                    if let Err(e) = state_clone.call_service.execute_action(action).await {
                                        warn!("Failed to execute call action from websocket: {}", e);
                                    }
                                }
                            }
                        } else if cmd == "UpdateBluetoothStatus" {
                            if let Some(data) = ws_cmd.get("data") {
                                if let (Some(is_connected), Some(dev_id)) = (
                                    data.get("is_connected").and_then(|c| c.as_bool()),
                                    data.get("device_id").and_then(|d| d.as_str()),
                                ) {
                                    let mac = data.get("bluetooth_mac").and_then(|m| m.as_str()).map(|s| s.to_string());
                                    info!("Bluetooth connection status update received from {}: connected={}, mac={:?}", dev_id, is_connected, mac);
                                    let mut active_bt = state_clone.active_bluetooth.lock().await;
                                    if is_connected {
                                        active_bt.insert(dev_id.to_string());
                                        if let Some(ref m) = mac {
                                            state_clone.call_service.set_bluetooth_mac(Some(m.clone())).await;
                                        }
                                    } else {
                                        active_bt.remove(dev_id);
                                        state_clone.call_service.set_bluetooth_mac(None).await;
                                    }
                                    // Broadcast Bluetooth state change so the desktop UI updates instantly
                                    let _ = state_clone.tx.send(WsMessage {
                                        event: "BluetoothStateChanged".to_string(),
                                        data: serde_json::json!({
                                            "device_id": dev_id.to_string(),
                                            "is_connected": is_connected
                                        }),
                                    });
                                }
                            }
                        } else if cmd == "StartFileServer" || cmd == "StopFileServer" {
                            let _ = state_clone.tx.send(WsMessage {
                                event: cmd.to_string(),
                                data: serde_json::json!({}),
                            });
                        }
                    }
                }
            }
        }
    });

    // Wait for either send or receive task to complete, and abort the other
    tokio::select! {
        _ = (&mut send_task) => recv_task.abort(),
        _ = (&mut recv_task) => send_task.abort(),
    };

    if is_mobile && !dev_id.is_empty() {
        info!("Mobile device disconnected via WS: {}", dev_id);
        {
            let mut active = state.active_connections.lock().await;
            active.remove(&dev_id);
        }
        {
            let mut types = state.connection_types.lock().await;
            types.remove(&dev_id);
        }
        {
            let mut ips = state.device_ips.lock().await;
            ips.remove(&dev_id);
        }
        {
            let mut active_bt = state.active_bluetooth.lock().await;
            active_bt.remove(&dev_id);
        }
        // Broadcast disconnection update
        let _ = state.tx.send(WsMessage {
            event: "DeviceDisconnected".to_string(),
            data: serde_json::json!({ "device_id": dev_id.clone() }),
        });
    }

    info!("WebSocket connection closed.");
}
