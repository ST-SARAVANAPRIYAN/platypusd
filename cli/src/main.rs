use clap::{Parser, Subcommand};
use anyhow::Result;
use reqwest::Client;
use serde_json::json;

#[derive(Parser)]
#[command(name = "platypus-cli")]
#[command(about = "CLI tool to control and query local platypusd daemon", long_about = None)]
struct Cli {
    /// URL of the platypusd daemon api
    #[arg(short, long, default_value = "http://localhost:8080")]
    url: String,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Query and print daemon status, identity, paired devices, and active calls in JSON format
    Status,
    
    /// Trigger a call control action (accept, reject, mute, unmute)
    Call {
        /// The call control command (accept, reject, mute, unmute)
        action: String,
        /// The unique ID of the target call
        #[arg(default_value = "mock-call")]
        call_id: String,
    },
    
    /// Trigger device pairing request
    Pair {
        /// Unique remote device ID (UUID)
        device_id: String,
        /// User-friendly name of the remote device
        device_name: String,
        /// Hex-encoded Ed25519 public key of the remote device
        public_key: String,
    },

    /// Simulate an incoming or active call (for integration and testing)
    SimulateCall {
        /// Caller phone number
        number: String,
        /// Contact display name
        contact_name: String,
        /// Current call state (Ringing, Connected, Muted, Disconnected)
        state: String,
        /// Optional unique call ID
        #[arg(default_value = "mock-call")]
        call_id: String,
    },
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let client = Client::new();

    match cli.command {
        Commands::Status => {
            let res = client.get(&format!("{}/api/v1/status", cli.url))
                .send()
                .await?
                .json::<serde_json::Value>()
                .await?;
            println!("{}", serde_json::to_string_pretty(&res)?);
        }
        Commands::Call { action, call_id } => {
            let res = client.post(&format!("{}/api/v1/calls/action", cli.url))
                .json(&json!({
                    "action": action,
                    "call_id": call_id,
                }))
                .send()
                .await?
                .json::<serde_json::Value>()
                .await?;
            println!("{}", serde_json::to_string_pretty(&res)?);
        }
        Commands::Pair { device_id, device_name, public_key } => {
            let res = client.post(&format!("{}/api/v1/pairing/request", cli.url))
                .json(&json!({
                    "device_id": device_id,
                    "device_name": device_name,
                    "public_key": public_key,
                }))
                .send()
                .await?
                .json::<serde_json::Value>()
                .await?;
            println!("{}", serde_json::to_string_pretty(&res)?);
        }
        Commands::SimulateCall { number, contact_name, state, call_id } => {
            // Verify call state format matches the enum
            let formatted_state = match state.to_lowercase().as_str() {
                "ringing" => "Ringing",
                "connected" => "Connected",
                "muted" => "Muted",
                "disconnected" => "Disconnected",
                _ => {
                    println!("Invalid state. Must be one of: Ringing, Connected, Muted, Disconnected");
                    return Ok(());
                }
            };

            let res = client.post(&format!("{}/api/v1/calls/state", cli.url))
                .json(&json!({
                    "call_id": call_id,
                    "number": number,
                    "contact_name": contact_name,
                    "state": formatted_state,
                }))
                .send()
                .await?
                .json::<serde_json::Value>()
                .await?;
            println!("{}", serde_json::to_string_pretty(&res)?);
        }
    }
    Ok(())
}
