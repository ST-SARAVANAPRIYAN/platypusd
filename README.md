# platypusd 🦦

> An open-source, local-first device integration platform that connects mobile devices directly to desktop environments, window managers, and shell automation scripts.

Unlike traditional desktop applications (like KDE Connect), **platypusd** is built as a daemon-first, API-first integration platform. The CLI and APIs are the primary product, enabling developers to build custom notifications, panel widgets, and automatic call management directly into any Linux system (GNOME, Plasma, Hyprland, i3, etc.).

---

## 🚀 Key Features

*   **API-First Core**: Fully scriptable REST and WebSockets interfaces.
*   **Local-First & Secure**: Mutual authentication using persistent Ed25519 keypairs. No cloud services or user accounts.
*   **Local Discovery**: Discovers devices on the local area network using mDNS.
*   **Phone Call Integration (Phase 1)**: Receive real-time call states, toggle microphone mute/unmute, and control desktop audio routing loopbacks automatically.

---

## 🛠 Tech Stack

*   **Daemon (Core)**: Rust (Tokio, Axum, SQLite, sqlx)
*   **CLI Tool**: Rust (Clap, Reqwest)
*   **Security & Crypto**: Ed25519 key signatures and mDNS network advertisement
*   **Audio Pipeline**: PulseAudio / PipeWire (`pactl` and `wpctl` integrations)

---

## 📂 Project Structure

```
platypusd/
├── core/                      # Daemon running background integration hooks
│   └── src/
│       ├── main.rs            # Application entrypoint
│       ├── db.rs              # SQLite DB & identity key manager
│       ├── discovery.rs       # mDNS networking registration & browsing
│       ├── services/          # Modular services (call routing, audio hooks)
│       └── api/               # Axum REST and WebSocket endpoints
├── cli/                       # Command-line interface to interact with platypusd
│   └── src/
│       └── main.rs            # Clap CLI parser & API request broker
├── protocol/
│   └── protocol_spec.md       # API endpoint and event specifications
├── desktop/                   # Tauri Desktop UI (React + TypeScript)
└── android/                   # Android App (Kotlin foreground services)
```

---

## ⚡ Quickstart

### 1. Build the Workspace

```bash
cargo build --release
```

### 2. Run the Daemon

Start the daemon. On its first run, it will automatically generate an SQLite database at `~/.config/platypusd/platypusd.db` and generate a unique cryptographic identity (Ed25519 keys):

```bash
./target/release/platypusd-core
```

### 3. Use the CLI to Control the Daemon

Open another terminal and interact with the running daemon:

```bash
# Get status, device information, and active call data
./target/release/platypus-cli status

# Simulate an incoming phone call
./target/release/platypus-cli simulate-call "+1234567890" "Alice Smith" "Ringing"

# Mute/Unmute active phone call
./target/release/platypus-cli call mute

# Accept/Route phone call audio
./target/release/platypus-cli call accept
```

---

## 🛡 Security & Pairing

On the first exchange:
1. Devices broadcast their `public_key` and `device_id` via local network discovery.
2. A pairing request is sent via `/api/v1/pairing/request`.
3. Pairing must be confirmed, storing the device public key inside the SQLite datastore.
4. Subsequent calls and socket packets are validated securely using the public keys.
