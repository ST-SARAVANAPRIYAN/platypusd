# platypusd

An open-source, local-first device integration platform that connects mobile devices directly to desktop environments, window managers, and shell automation scripts.

Unlike traditional desktop applications (like KDE Connect), platypusd is built as a daemon-first, API-first integration platform. The CLI and APIs are the primary product, enabling developers to build custom notifications, panel widgets, and automatic call management directly into any Linux system (GNOME, Plasma, Hyprland, i3, etc.).

---

## Key Features

*   **API-First Core**: Fully scriptable REST and WebSockets interfaces.
*   **Local-First & Secure**: Mutual authentication using persistent Ed25519 keypairs. No cloud services or user accounts.
*   **Local Discovery**: Discovers devices on the local area network using mDNS.
*   **Bluetooth Audio & Call Integration**: Flexibly route call and system audio depending on the device's role. Supports two modes: Desktop acting as a speaker/mic for Mobile, or Mobile acting as a real-time wireless speaker/mic for Desktop.

---

## Tech Stack

*   **Daemon (Core)**: Rust (Tokio, Axum, SQLite, sqlx)
*   **CLI Tool**: Rust (Clap, Reqwest)
*   **Desktop Dashboard**: React, TypeScript, Vite
*   **Android App**: Kotlin (Foreground services, WebSockets, AudioTrack API)
*   **Audio Pipeline**: PulseAudio / PipeWire (command-line integrations via pactl and wpctl)

---

## Project Structure

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
├── desktop/                   # Desktop UI dashboard (React + TypeScript + Vite)
├── android/                   # Android App (Kotlin foreground services)
└── run.sh                     # Helper script to launch daemon and frontend
```

---

## Prerequisites

Ensure your development environment meets the following requirements:

*   **Operating System**: Linux (required for PulseAudio/PipeWire integrations).
*   **Rust Toolchain**: Rust 1.70+ installed.
*   **Node.js**: Node 18+ and npm installed (for building the desktop dashboard).
*   **Android Development**: Android SDK and Gradle (for building the APK).
*   **Audio Packages**: `pulseaudio-utils` (specifically `pactl` and `parec`) must be installed and available in your PATH.

---

## Building the Components

### 1. Build the Rust Daemon and CLI
From the root directory, compile the Rust workspace:
```bash
cargo build --release
```
This builds:
*   The core daemon: `target/release/platypusd-core`
*   The command-line tool: `target/release/platypus-cli`

### 2. Build the Desktop Dashboard
Navigate to the `desktop` directory, install dependencies, and build the production assets:
```bash
cd desktop
npm install
npm run build
```

### 3. Build the Android Application
Navigate to the `android` directory and compile the debug APK:
```bash
cd android
./gradlew assembleDebug
```
The compiled APK will be generated at:
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## Running the Application

### The Unified Script (Recommended)
You can run the core daemon and the React desktop dashboard simultaneously in development mode using the helper script in the root directory:
```bash
chmod +x run.sh
./run.sh
```
This script:
1. Starts the Rust core daemon (`platypusd-core`) in the background.
2. Starts the Vite development server for the desktop dashboard.
3. Keeps both processes running in your terminal. Pressing `Ctrl+C` cleanly shuts down both services.

### Running Components Individually

#### Start the Core Daemon
To run the daemon independently (which automatically initializes the SQLite database at `~/.config/platypusd/platypusd.db` and generates cryptographic Ed25519 keys on the first run):
```bash
./target/release/platypusd-core
```

#### Start the Desktop Dashboard
To start the Vite developer server for the dashboard UI separately:
```bash
cd desktop
npm run dev
```
Open your browser and navigate to `http://localhost:5173` to access the interface.

#### Install and Run the Android App
To install the compiled debug APK directly to a USB-connected Android device (with USB debugging enabled):
```bash
cd android
./gradlew installDebug
```
Alternatively, copy the generated `app-debug.apk` manually to your phone and install it.

---

## Pairing and Connection Setup

### 1. Connect via Local Network
1. Launch the `platypusd` application on your phone.
2. Open the desktop dashboard in your browser.
3. Scan the generated QR code shown on the dashboard using the mobile app scanner. This transmits the local IP address and pairs the devices securely.
4. Once paired, the devices will automatically establish WebSocket and REST connections over your local Wi-Fi or mobile hotspot.

### 2. Pair via Bluetooth
Ensure your phone is paired and connected to your Linux PC via your system's Bluetooth settings (e.g., using `blueman-manager` or `bluetoothctl`). Bluetooth is required to negotiate HFP call routing.

---

## Testing and CLI Automation

Open a terminal and use the compiled CLI tool (`platypus-cli`) to interact with the active daemon and test features:

### Get Daemon Status
Retrieve identity details, Wi-Fi connectivity status, and a list of paired/online devices:
```bash
./target/release/platypus-cli status
```

### Simulate an Incoming Call
Test the call interface and automatic PulseAudio loopbacks without placing a real phone call:
```bash
./target/release/platypus-cli simulate-call "+1234567890" "John Doe" "Ringing"
```
During a simulated call:
*   The desktop dashboard will display an active call control panel.
*   The daemon dynamically loads PipeWire/PulseAudio loopbacks to bridge mobile audio nodes to your system.

### Manage Calls
Control the call state programmatically:
```bash
# Mute microphone input during the call
./target/release/platypus-cli call mute

# Unmute microphone input
./target/release/platypus-cli call unmute

# Reject or end the active call
./target/release/platypus-cli call reject
```

---

## Security and Verification

All communication between the Android application and the desktop daemon is authenticated locally:
1. Devices exchange their public Ed25519 keys upon pairing.
2. Every request is verified by checking the cryptographic signature of the payload against the stored public key in the database.
3. No remote servers or cloud databases are used.
