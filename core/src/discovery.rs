use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use std::collections::HashMap;
use std::net::UdpSocket;
use anyhow::{Context, Result};
use tracing::{info, warn, error};

pub struct DiscoveryManager {
    daemon: ServiceDaemon,
}

impl DiscoveryManager {
    pub fn new() -> Result<Self> {
        let daemon = ServiceDaemon::new().context("Failed to create mDNS ServiceDaemon")?;
        Ok(Self { daemon })
    }

    pub fn start(&self, device_id: &str, device_name: &str, public_key: &str, port: u16) -> Result<()> {
        let service_type = "_platypusd._tcp.local.";
        let instance_name = format!("platypusd-{}", device_id);
        
        // Find local IP by connecting a temporary UDP socket
        let local_ip = match get_local_ip() {
            Ok(ip) => ip.to_string(),
            Err(e) => {
                warn!("Could not determine local IP: {}. Falling back to 0.0.0.0", e);
                "0.0.0.0".to_string()
            }
        };
        
        info!("Registering mDNS service with IP: {}", local_ip);
        
        let host_name = format!("{}.local.", device_id);
        let mut properties = HashMap::new();
        properties.insert("id".to_string(), device_id.to_string());
        properties.insert("name".to_string(), device_name.to_string());
        properties.insert("pubkey".to_string(), public_key.to_string());

        let service_info = ServiceInfo::new(
            service_type,
            &instance_name,
            &host_name,
            local_ip,
            port,
            properties,
        ).context("Failed to construct ServiceInfo")?;

        self.daemon.register(service_info).context("Failed to register mDNS service")?;
        info!("mDNS service registered successfully: {}", instance_name);

        // Start browsing for other platypusd services
        let receiver = self.daemon.browse(service_type).context("Failed to browse mDNS services")?;
        
        tokio::spawn(async move {
            info!("Browsing for other platypusd devices on the local network...");
            loop {
                match receiver.recv_async().await {
                    Ok(event) => match event {
                        ServiceEvent::ServiceResolved(info) => {
                            let peer_id = info.get_property_val_str("id").unwrap_or("unknown");
                            let peer_name = info.get_property_val_str("name").unwrap_or("unknown");
                            info!(
                                "Discovered platypusd peer: {} (ID: {}) at {:?}",
                                peer_name,
                                peer_id,
                                info.get_addresses()
                            );
                        }
                        ServiceEvent::ServiceRemoved(service_type, fullname) => {
                            info!("Peer removed: {} (type: {})", fullname, service_type);
                        }
                        _ => {}
                    },
                    Err(e) => {
                        error!("Error during mDNS browsing: {:?}", e);
                        break;
                    }
                }
            }
        });

        Ok(())
    }
}

/// Retrieve the primary outbound IP address of the local system
fn get_local_ip() -> Result<std::net::IpAddr> {
    let socket = UdpSocket::bind("0.0.0.0:0")?;
    socket.connect("8.8.8.8:80")?;
    Ok(socket.local_addr()?.ip())
}
