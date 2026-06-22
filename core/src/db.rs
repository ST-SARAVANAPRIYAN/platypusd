use sqlx::{sqlite::SqlitePoolOptions, SqlitePool};
use std::fs;
use std::path::PathBuf;
use anyhow::{Context, Result};
use tracing::info;
use ed25519_dalek::SigningKey;
use rand::rngs::OsRng;
use uuid::Uuid;

#[derive(Clone)]
pub struct Database {
    pub pool: SqlitePool,
}

#[derive(Debug, Clone)]
pub struct LocalIdentity {
    pub device_id: String,
    pub device_name: String,
    pub public_key: String,
    #[allow(dead_code)]
    pub private_key: String,
}

impl Database {
    pub async fn init() -> Result<Self> {
        // Resolve database path: ~/.config/platypusd/platypusd.db
        let mut db_dir = dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
        db_dir.push("platypusd");
        fs::create_dir_all(&db_dir).context("Failed to create configuration directory")?;
        
        let db_path = db_dir.join("platypusd.db");
        let db_url = format!("sqlite://{}", db_path.to_string_lossy());
        
        info!("Initializing SQLite database at: {:?}", db_path);
        
        // Ensure database file exists
        if !db_path.exists() {
            fs::File::create(&db_path).context("Failed to create database file")?;
        }

        let pool = SqlitePoolOptions::new()
            .max_connections(5)
            .connect(&db_url)
            .await
            .context("Failed to connect to SQLite database")?;

        let db = Self { pool };
        db.run_migrations().await?;
        Ok(db)
    }

    async fn run_migrations(&self) -> Result<()> {
        info!("Running database migrations...");
        sqlx::query(
            "CREATE TABLE IF NOT EXISTS paired_devices (
                device_id TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                public_key TEXT NOT NULL,
                paired_at TEXT DEFAULT (datetime('now'))
            );"
        )
        .execute(&self.pool)
        .await
        .context("Failed to create paired_devices table")?;

        sqlx::query(
            "CREATE TABLE IF NOT EXISTS local_identity (
                device_id TEXT PRIMARY KEY,
                device_name TEXT NOT NULL,
                public_key TEXT NOT NULL,
                private_key TEXT NOT NULL
            );"
        )
        .execute(&self.pool)
        .await
        .context("Failed to create local_identity table")?;

        Ok(())
    }

    pub async fn get_or_create_identity(&self) -> Result<LocalIdentity> {
        let row = sqlx::query("SELECT device_id, device_name, public_key, private_key FROM local_identity LIMIT 1")
            .fetch_optional(&self.pool)
            .await?;

        if let Some(r) = row {
            use sqlx::Row;
            Ok(LocalIdentity {
                device_id: r.get(0),
                device_name: r.get(1),
                public_key: r.get(2),
                private_key: r.get(3),
            })
        } else {
            info!("No local identity found. Generating new Ed25519 keypair...");
            let mut csprng = OsRng;
            let signing_key = SigningKey::generate(&mut csprng);
            let verifying_key = signing_key.verifying_key();
            
            let device_id = Uuid::new_v4().to_string();
            let hostname = hostname::get().unwrap_or_else(|_| "Linux Desktop".into());
            let device_name = format!("platypusd-{}", hostname);
            let private_key = to_hex(&signing_key.to_bytes());
            let public_key = to_hex(&verifying_key.to_bytes());

            sqlx::query(
                "INSERT INTO local_identity (device_id, device_name, public_key, private_key) VALUES (?, ?, ?, ?)"
            )
            .bind(&device_id)
            .bind(&device_name)
            .bind(&public_key)
            .bind(&private_key)
            .execute(&self.pool)
            .await?;

            info!("Local identity created: {} ({})", device_name, device_id);

            Ok(LocalIdentity {
                device_id,
                device_name,
                public_key,
                private_key,
            })
        }
    }

    pub async fn register_device(&self, device_id: &str, device_name: &str, public_key: &str) -> Result<()> {
        sqlx::query(
            "INSERT OR REPLACE INTO paired_devices (device_id, device_name, public_key) VALUES (?, ?, ?)"
        )
        .bind(device_id)
        .bind(device_name)
        .bind(public_key)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn unpair_device(&self, device_id: &str) -> Result<()> {
        sqlx::query("DELETE FROM paired_devices WHERE device_id = ?")
            .bind(device_id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn is_device_paired(&self, device_id: &str) -> Result<bool> {
        let row = sqlx::query("SELECT 1 FROM paired_devices WHERE device_id = ?")
            .bind(device_id)
            .fetch_optional(&self.pool)
            .await?;
        Ok(row.is_some())
    }

    pub async fn get_paired_devices(&self) -> Result<Vec<(String, String)>> {
        let rows = sqlx::query("SELECT device_id, device_name FROM paired_devices")
            .fetch_all(&self.pool)
            .await?;
        
        let devices = rows.into_iter()
            .map(|r| {
                use sqlx::Row;
                (r.get(0), r.get(1))
            })
            .collect();
        Ok(devices)
    }
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

// Minimal mock module of dirs/hostname crates to avoid adding extra dependencies
mod dirs {
    use std::path::PathBuf;
    pub fn config_dir() -> Option<PathBuf> {
        std::env::var("HOME").ok().map(|h| PathBuf::from(h).join(".config"))
    }
}

mod hostname {
    use std::io;
    pub fn get() -> io::Result<String> {
        // read /proc/sys/kernel/hostname or /etc/hostname, or return mock
        std::fs::read_to_string("/etc/hostname")
            .map(|s| s.trim().to_string())
            .or_else(|_| Ok("Linux-PC".to_string()))
    }
}

