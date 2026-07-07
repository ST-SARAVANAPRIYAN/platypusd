use libc::{socket, bind, listen, accept, sockaddr, read, write, close, sa_family_t};
use tracing::{info, error, warn};

#[repr(C)]
#[derive(Debug, Copy, Clone)]
struct sockaddr_rc {
    rc_family: sa_family_t,
    rc_bdaddr: [u8; 6], // bdaddr_t
    rc_channel: u8,
}

/// Retrieve the primary outbound IP address of the local system via UDP fallback
fn get_local_ip() -> Result<std::net::IpAddr, std::io::Error> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0")?;
    socket.connect("8.8.8.8:80")?;
    Ok(socket.local_addr()?.ip())
}

/// Retrieve all active IPv4 interface addresses of the local system via libc getifaddrs
fn get_all_ips() -> Vec<serde_json::Value> {
    let mut interfaces = Vec::new();
    let mut addrs: *mut libc::ifaddrs = std::ptr::null_mut();
    
    unsafe {
        if libc::getifaddrs(&mut addrs) == 0 {
            let mut curr = addrs;
            while !curr.is_null() {
                let addr = (*curr).ifa_addr;
                if !addr.is_null() && (*addr).sa_family == libc::AF_INET as sa_family_t {
                    let sin = addr as *const libc::sockaddr_in;
                    let ip_bytes = (*sin).sin_addr.s_addr;
                    // Format to IPv4 string
                    let a = (ip_bytes & 0xFF) as u8;
                    let b = ((ip_bytes >> 8) & 0xFF) as u8;
                    let c = ((ip_bytes >> 16) & 0xFF) as u8;
                    let d = ((ip_bytes >> 24) & 0xFF) as u8;
                    let ip_str = format!("{}.{}.{}.{}", a, b, c, d);
                    
                    let ifa_name = std::ffi::CStr::from_ptr((*curr).ifa_name)
                        .to_string_lossy()
                        .into_owned();
                    
                    // Skip loopback 127.0.0.1
                    if ip_str != "127.0.0.1" {
                        interfaces.push(serde_json::json!({
                            "name": ifa_name,
                            "ip": ip_str
                        }));
                    }
                }
                curr = (*curr).ifa_next;
            }
            libc::freeifaddrs(addrs);
        }
    }
    
    // Fallback if empty
    if interfaces.is_empty() {
        if let Ok(ip) = get_local_ip() {
            interfaces.push(serde_json::json!({
                "name": "primary",
                "ip": ip.to_string()
            }));
        } else {
            interfaces.push(serde_json::json!({
                "name": "loopback",
                "ip": "127.0.0.1"
            }));
        }
    }
    
    interfaces
}

pub fn start_rfcomm_server(db: crate::db::Database, port: u16) {
    tokio::spawn(async move {
        // Resolve database and identity state asynchronously outside the blocking loop
        let identity = match db.get_or_create_identity().await {
            Ok(ident) => ident,
            Err(e) => {
                error!("RFCOMM: Failed to load identity: {}", e);
                return;
            }
        };
        let device_id = identity.device_id;
        let device_name = identity.device_name;
        let pubkey_hex = identity.public_key;

        // Run the blocking RFCOMM listening loop in a spawn_blocking task
        tokio::task::spawn_blocking(move || {
            let rc_family = 31; // AF_BLUETOOTH
            let sock_type = 1;  // SOCK_STREAM
            let protocol = 3;   // BTPROTO_RFCOMM

            let server_fd = unsafe { socket(rc_family, sock_type, protocol) };
            if server_fd < 0 {
                error!("RFCOMM: Failed to create RFCOMM socket. Bluetooth RFCOMM bootstrapping will be unavailable.");
                return;
            }

            // Set socket reuse address
            unsafe {
                let optval: libc::c_int = 1;
                libc::setsockopt(
                    server_fd,
                    libc::SOL_SOCKET,
                    libc::SO_REUSEADDR,
                    &optval as *const _ as *const libc::c_void,
                    std::mem::size_of::<libc::c_int>() as libc::socklen_t,
                );
            }

            let mut addr = sockaddr_rc {
                rc_family: rc_family as sa_family_t,
                rc_bdaddr: [0; 6], // BDADDR_ANY
                rc_channel: 1,     // RFCOMM channel 1
            };

            let addr_ptr = &addr as *const _ as *const sockaddr;
            let addr_len = std::mem::size_of::<sockaddr_rc>() as libc::socklen_t;

            let bind_res = unsafe { bind(server_fd, addr_ptr, addr_len) };
            if bind_res < 0 {
                warn!("RFCOMM: Failed to bind RFCOMM socket to channel 1. Retrying on channel 2...");
                addr.rc_channel = 2;
                let bind_res_2 = unsafe { bind(server_fd, addr_ptr, addr_len) };
                if bind_res_2 < 0 {
                    error!("RFCOMM: Failed to bind RFCOMM socket on channel 2. RFCOMM bootstrapping is disabled.");
                    unsafe { close(server_fd) };
                    return;
                }
            }

            let listen_res = unsafe { listen(server_fd, 10) };
            if listen_res < 0 {
                error!("RFCOMM: Failed to listen on RFCOMM socket.");
                unsafe { close(server_fd) };
                return;
            }

            info!("RFCOMM: Bluetooth RFCOMM bootstrapping server listening on channel {}...", addr.rc_channel);

            loop {
                let mut client_addr = sockaddr_rc {
                    rc_family: 0,
                    rc_bdaddr: [0; 6],
                    rc_channel: 0,
                };
                let mut client_len = std::mem::size_of::<sockaddr_rc>() as libc::socklen_t;
                let client_addr_ptr = &mut client_addr as *mut _ as *mut sockaddr;

                let client_fd = unsafe { accept(server_fd, client_addr_ptr, &mut client_len) };
                if client_fd < 0 {
                    error!("RFCOMM: Failed to accept client RFCOMM connection.");
                    continue;
                }

                info!("RFCOMM: Accepted connection from Bluetooth device.");

                let dev_id_clone = device_id.clone();
                let dev_name_clone = device_name.clone();
                let pubkey_clone = pubkey_hex.clone();

                let mut buffer = [0u8; 1024];
                let bytes_read = unsafe { read(client_fd, buffer.as_mut_ptr() as *mut libc::c_void, buffer.len()) };
                if bytes_read > 0 {
                    let request_str = String::from_utf8_lossy(&buffer[..bytes_read as usize]);
                    if request_str.contains("GET_DAEMON_IP") {
                        // Resolve all local IPv4 interface addresses
                        let ips = get_all_ips();
                        
                        // Construct connection payload matching dynamic probing schema
                        let connection_info = serde_json::json!({
                            "ips": ips,
                            "port": port,
                            "id": dev_id_clone,
                            "name": dev_name_clone,
                            "pubkey": pubkey_clone
                        });
                        
                        let response_str = connection_info.to_string();
                        let response_bytes = response_str.as_bytes();
                        unsafe {
                            write(client_fd, response_bytes.as_ptr() as *const libc::c_void, response_bytes.len());
                        }
                        info!("RFCOMM: Sent interface list over RFCOMM: IPs={:?}", ips);
                    }
                }
                unsafe { close(client_fd) };
            }
        });
    });
}
