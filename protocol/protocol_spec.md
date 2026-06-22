# platypusd: Protocol & API Specification

Version: 0.1

This document specifies the APIs and communication protocol for the **platypusd** platform.

---

## 1. Local Discovery & Pairing

To discover other instances of platypusd on the local network (e.g., an Android device discovering a desktop), platypusd uses **mDNS** (Multicast DNS).

### mDNS Service
- **Service Type**: `_platypusd._tcp`
- **Domain**: `local.`
- **TXT Records**:
  - `id`: Unique Device UUID
  - `name`: User-friendly device name (e.g., "Saravana's Pixel 7", "Linux Desktop")
  - `pubkey`: Hex-encoded SHA-256 fingerprint of the device's public key (used for verification)

### Pairing Workflow (Layer 1 Security)
Every platypusd instance generates a persistent Ed25519 public/private key pair upon first startup, along with a self-signed TLS certificate for QUIC/HTTPS.

1. **Discovery**: Device A discovers Device B via mDNS.
2. **Pairing Request**: Device A sends a pairing request payload (via HTTP POST or WS) to Device B.
   - Endpoint: `POST /api/v1/pairing/request`
   - Payload:
     ```json
     {
       "device_id": "uuid-v4-of-device-a",
       "device_name": "Device A Name",
       "public_key": "hex-encoded-ed25519-public-key"
     }
     ```
3. **User Confirmation**: Device B prompts the user (desktop notification or app dialog) with a pairing code derived from the hashes of both public keys.
4. **Accept/Reject**: Once accepted on both sides, the public keys are saved to each device's SQLite registry (`paired_devices` table). All future connections must authenticate via mutual TLS (mTLS) or signature verification.

---

## 2. Public REST API

Exposed by `platypusd-core` daemon on port `8080` (by default, bound to localhost for desktop clients, and on the local network interface for authenticated paired devices).

### GET `/api/v1/status`
Returns daemon status, version, and connected devices.
- **Response**:
  ```json
  {
    "status": "online",
    "version": "0.1.0",
    "paired_devices": [
      {
        "device_id": "uuid-v4-device-b",
        "device_name": "My Phone",
        "is_connected": true
      }
    ]
  }
  ```

### POST `/api/v1/calls/action`
Manage active call routing (Phase 1).
- **Request**:
  ```json
  {
    "action": "accept | reject | mute | unmute",
    "call_id": "unique-call-identifier-from-android"
  }
  ```
- **Response**:
  ```json
  {
    "success": true
  }
  ```

---

## 3. WebSockets Events API

Websocket endpoint: `/api/v1/events`. Clients connect to receive real-time updates and push commands.

### Messages Sent by Daemon (Server -> Client)

#### `CallStateChanged`
Triggered when an incoming, outgoing, or ended call occurs on the mobile device.
```json
{
  "event": "CallStateChanged",
  "data": {
    "call_id": "call-12345",
    "number": "+1234567890",
    "contact_name": "John Doe",
    "state": "Ringing | Connected | Disconnected | Muted"
  }
}
```

#### `ClipboardSynced`
Triggered when the clipboard on a paired device is updated (Phase 2).
```json
{
  "event": "ClipboardSynced",
  "data": {
    "text": "Copied content from phone"
  }
}
```

### Messages Sent by Client (Client -> Server)

#### `TriggerCallAction`
Equivalent to the REST call action.
```json
{
  "command": "TriggerCallAction",
  "data": {
    "action": "accept | reject | mute",
    "call_id": "call-12345"
  }
}
```
