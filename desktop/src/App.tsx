import { useState, useEffect, useRef } from 'react';
import QRCode from 'qrcode';

interface PairedDevice {
  device_id: string;
  device_name: string;
  is_online: boolean;
  is_bluetooth_connected: boolean;
  connection_type?: string;
  ip?: string;
}

interface ActiveCall {
  call_id: string;
  number: string;
  contact_name: string;
  state: 'Ringing' | 'Connected' | 'Muted' | 'Disconnected';
}

interface StatusData {
  status: string;
  version: string;
  device_id: string;
  device_name: string;
  public_key: string;
  paired_devices: PairedDevice[];
  active_call: ActiveCall | null;
}

export default function App() {
  const wsRef = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<StatusData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activeCall, setActiveCall] = useState<ActiveCall | null>(null);
  const [lastClipboard, setLastClipboard] = useState<string>('');
  const [clipboardInput, setClipboardInput] = useState<string>('');
  const [isDaemonOnline, setIsDaemonOnline] = useState<boolean>(false);
  const [qrCodeUrl, setQrCodeUrl] = useState<string>('');
  const [activeTab, setActiveTab] = useState<string>('dashboard');
  const [clipDirection, setClipDirection] = useState<string>('bidirectional');
  const [clipAutoSync, setClipAutoSync] = useState<boolean>(true);

  // Files explorer state
  const [mobilePath, setMobilePath] = useState<string>('');
  const [mobileFiles, setMobileFiles] = useState<any[]>([]);
  const [loadingMobile, setLoadingMobile] = useState<boolean>(false);

  // File explorer sorting, searching, and metadata details
  const [mobileSearch, setMobileSearch] = useState<string>('');
  const [mobileSort, setMobileSort] = useState<'name' | 'size' | 'date'>('name');
  const [mobileSortOrder, setMobileSortOrder] = useState<'asc' | 'desc'>('asc');
  const [selectedFileInfo, setSelectedFileInfo] = useState<any | null>(null);

  const showToast = (message: string) => {
    const toast = document.getElementById('toast');
    if (toast) {
      toast.innerText = message;
      toast.classList.add('show');
      setTimeout(() => toast.classList.remove('show'), 2500);
    }
  };

  const uploadFileToMobile = async (file: File) => {
    const connected = status?.paired_devices.filter(d => d.is_online) || [];
    if (!connected.length || !connected[0].ip) return;
    const phoneIp = connected[0].ip;
    
    const destPath = mobilePath ? `${mobilePath}/${file.name}` : `/sdcard/Download/${file.name}`;
    const url = `http://${phoneIp}:9090/upload?path=${encodeURIComponent(destPath)}`;
    try {
      showToast(`Uploading ${file.name} to mobile...`);
      const res = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/octet-stream',
          'Content-Length': file.size.toString()
        },
        body: file
      });
      if (res.ok) {
        fetchMobileFilesList(mobilePath || '/sdcard');
        showToast("Uploaded successfully!");
      } else {
        alert("Upload failed: " + res.statusText);
      }
    } catch (e: any) {
      alert("Upload failed: " + e.message);
    }
  };



  const deleteMobileFile = async (filePath: string) => {
    const connected = status?.paired_devices.filter(d => d.is_online) || [];
    if (!connected.length || !connected[0].ip) return;
    if (!confirm("Are you sure you want to delete this file/folder from your mobile device?")) return;
    
    const phoneIp = connected[0].ip;
    const url = `http://${phoneIp}:9090/delete?path=${encodeURIComponent(filePath)}`;
    try {
      const res = await fetch(url, { method: 'DELETE' });
      if (res.ok) {
        fetchMobileFilesList(mobilePath || '/sdcard');
        showToast("Deleted successfully!");
        if (selectedFileInfo && selectedFileInfo.path === filePath) {
          setSelectedFileInfo(null);
        }
      } else {
        alert("Delete failed: " + res.statusText);
      }
    } catch (e: any) {
      alert("Delete failed: " + e.message);
    }
  };



  const getProcessedFiles = (
    files: any[],
    search: string,
    sortBy: 'name' | 'size' | 'date',
    sortOrder: 'asc' | 'desc'
  ) => {
    let result = files.filter(f => f.name.toLowerCase().includes(search.toLowerCase()));
    result.sort((a, b) => {
      if (a.is_dir && !b.is_dir) return -1;
      if (!a.is_dir && b.is_dir) return 1;
      let comparison = 0;
      if (sortBy === 'name') {
        comparison = a.name.localeCompare(b.name);
      } else if (sortBy === 'size') {
        comparison = a.size - b.size;
      } else if (sortBy === 'date') {
        comparison = (a.last_modified || 0) - (b.last_modified || 0);
      }
      return sortOrder === 'asc' ? comparison : -comparison;
    });
    return result;
  };



  const fetchMobileFilesList = async (targetPath: string) => {
    const connected = status?.paired_devices.filter(d => d.is_online) || [];
    if (!connected.length || !connected[0].ip) {
      return;
    }
    const phoneIp = connected[0].ip;
    setLoadingMobile(true);
    try {
      const url = `http://${phoneIp}:9090/list?path=${encodeURIComponent(targetPath)}`;
      const res = await fetch(url);
      if (res.ok) {
        const data = await res.json();
        setMobileFiles(data);
        setMobilePath(targetPath);
      } else {
        console.error("Failed to fetch mobile files:", res.statusText);
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoadingMobile(false);
    }
  };

  const sendWebSocketCommand = (command: string, data: any = {}) => {
    console.log("Attempting to send WS command:", command, "readyState:", wsRef.current?.readyState);
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify({ command, data }));
      console.log("WS Command sent successfully:", command);
    } else {
      console.warn("WS Command dropped (socket not open):", command);
    }
  };

  useEffect(() => {
    if (activeTab === 'files' && isDaemonOnline) {
      sendWebSocketCommand('StartFileServer');
      const timer = setTimeout(() => {
        const connected = status?.paired_devices.filter(d => d.is_online) || [];
        if (connected.length && connected[0].ip) {
          fetchMobileFilesList(mobilePath || '');
        }
      }, 250);

      return () => {
        clearTimeout(timer);
        sendWebSocketCommand('StopFileServer');
      };
    }
  }, [activeTab, isDaemonOnline, status]);
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');

  const fetchClipboardConfig = async () => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/clipboard/config');
      if (res.ok) {
        const data = await res.json();
        setClipDirection(data.direction);
        setClipAutoSync(data.auto_sync);
      }
    } catch (err) {
      console.error("Failed to fetch clipboard config:", err);
    }
  };

  const saveClipboardConfig = async (direction: string, autoSync: boolean) => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/clipboard/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ direction, auto_sync: autoSync })
      });
      if (!res.ok) throw new Error("Failed to save clipboard config");
    } catch (err: any) {
      alert(`Error saving config: ${err.message}`);
    }
  };

  const fetchStatus = async () => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/status');
      if (!res.ok) throw new Error('Daemon status check failed');
      const data: StatusData = await res.json();
      setStatus(data);
      setIsDaemonOnline(true);
      if (data.active_call) {
        setActiveCall(data.active_call);
      }
      setError(null);
    } catch (err: any) {
      setError(err.message || 'Could not connect to platypusd daemon');
      setStatus(null);
      setIsDaemonOnline(false);
    }
  };

  const unpairDevice = async (deviceId: string) => {
    if (!confirm('Are you sure you want to unpair/disconnect this device?')) return;
    try {
      const res = await fetch('http://localhost:8080/api/v1/pairing/unpair', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device_id: deviceId })
      });
      if (!res.ok) throw new Error('Failed to unpair device');
      fetchStatus();
    } catch (err: any) {
      alert(`Unpair Error: ${err.message}`);
    }
  };

  const disconnectBluetooth = async (deviceId: string) => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/bluetooth/disconnect', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device_id: deviceId })
      });
      if (!res.ok) {
        const errData = await res.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error(errData.error || 'Failed to disconnect Bluetooth');
      }
      fetchStatus();
    } catch (err: any) {
      alert(`Bluetooth Disconnect Error: ${err.message}`);
    }
  };

  const openBluetoothSettings = async () => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/bluetooth/open-settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' }
      });
      if (!res.ok) {
        const errData = await res.json().catch(() => ({ error: 'Unknown error' }));
        throw new Error(errData.error || 'Failed to open settings');
      }
    } catch (err: any) {
      alert(`Bluetooth Settings Error: ${err.message}`);
    }
  };


  useEffect(() => {
    if (status) {
      const hostIp = window.location.hostname || '192.168.1.112';
      const connectionInfo = {
        ip: hostIp === 'localhost' || hostIp === '127.0.0.1' ? '192.168.1.112' : hostIp,
        port: 8080,
        id: status.device_id,
        name: status.device_name,
        pubkey: status.public_key
      };
      QRCode.toDataURL(JSON.stringify(connectionInfo))
        .then(url => setQrCodeUrl(url))
        .catch(err => console.error(err));
    }
  }, [status]);

  useEffect(() => {
    fetchStatus();
    fetchClipboardConfig();

    let ws: WebSocket | null = null;
    let reconnectTimeout: any = null;

    const connectWS = () => {
      console.log('Connecting to WebSocket event stream...');
      ws = new WebSocket('ws://localhost:8080/api/v1/events?device_id=desktop');

      ws.onopen = () => {
        console.log('Connected to platypusd event stream');
        wsRef.current = ws;
        setIsDaemonOnline(true);
        setError(null);
        fetchStatus();
      };

      ws.onmessage = (event) => {
        try {
          const payload = JSON.parse(event.data);
          console.log('WS Event:', payload);
          if (payload.event === 'CallStateChanged') {
            const call: ActiveCall = payload.data;
            setActiveCall(call.state === 'Disconnected' ? null : call);
          } else if (payload.event === 'ClipboardSynced') {
            setLastClipboard(payload.data.text);
          } else if (
            payload.event === 'DeviceConnected' ||
            payload.event === 'DeviceDisconnected' ||
            payload.event === 'DevicePaired' ||
            payload.event === 'DeviceUnpaired' ||
            payload.event === 'BluetoothStateChanged'
          ) {
            fetchStatus();
          }
        } catch (err) {
          console.error('Error parsing WS message', err);
        }
      };

      ws.onerror = (err) => {
        console.error('WebSocket error:', err);
        setIsDaemonOnline(false);
      };

      ws.onclose = () => {
        console.log('WebSocket closed. Attempting reconnect in 3s...');
        wsRef.current = null;
        setIsDaemonOnline(false);
        reconnectTimeout = setTimeout(connectWS, 3000);
      };
    };

    connectWS();

    return () => {
      wsRef.current = null;
      if (ws) ws.close();
      if (reconnectTimeout) clearTimeout(reconnectTimeout);
    };
  }, []);

  const sendCallAction = async (action: 'accept' | 'reject' | 'mute' | 'unmute', callId: string) => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/calls/action', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, call_id: callId })
      });
      if (!res.ok) throw new Error('Failed to dispatch call action');
      
      if (activeCall) {
        if (action === 'mute') {
          setActiveCall({ ...activeCall, state: 'Muted' });
        } else if (action === 'unmute') {
          setActiveCall({ ...activeCall, state: 'Connected' });
        } else if (action === 'reject') {
          setActiveCall(null);
        }
      }
    } catch (err: any) {
      alert(`Error dispatching action: ${err.message}`);
    }
  };

  const pushClipboard = async () => {
    if (!clipboardInput.trim()) return;
    try {
      const res = await fetch('http://localhost:8080/api/v1/clipboard', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: clipboardInput })
      });
      if (!res.ok) throw new Error('Failed to push clipboard');
      setLastClipboard(clipboardInput);
      setClipboardInput('');
    } catch (err: any) {
      alert(`Clipboard Sync Error: ${err.message}`);
    }
  };

  const connectedDevices = status?.paired_devices.filter(d => d.is_online) || [];
  const hasConnectedDevices = connectedDevices.length > 0;
  
  // Identify if active device is connected over Bluetooth
  const isBluetoothConnected = connectedDevices.some(d => d.is_bluetooth_connected);
  return (
    <div className={`theme-root ${theme}-mode`}>
      <div className="container">
        <header>
          <h1>platypusd platform</h1>
          <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
            <button 
              className="btn btn-secondary btn-sm" 
              onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
            >
              {theme === 'light' ? 'Dark Theme' : 'Light Theme'}
            </button>
            <div className={`status-badge ${isDaemonOnline ? 'online' : 'offline'}`}>
              <span style={{ 
                width: '8px', 
                height: '8px', 
                borderRadius: '50%', 
                background: isDaemonOnline ? 'var(--success)' : 'var(--danger)',
                display: 'inline-block',
                marginRight: '0.25rem'
              }}></span>
              {isDaemonOnline ? 'Daemon Online' : 'Daemon Offline'}
            </div>
          </div>
        </header>

        {/* Dashboard Tabs */}
        <div className="tab-bar">
          <button 
            className={`tab-btn ${activeTab === 'dashboard' ? 'active' : ''}`} 
            onClick={() => setActiveTab('dashboard')}
          >
            Dashboard
          </button>
          <button 
            className={`tab-btn ${activeTab === 'clipboard' ? 'active' : ''}`} 
            onClick={() => setActiveTab('clipboard')}
          >
            Clipboard Sync
          </button>
          <button 
            className={`tab-btn ${activeTab === 'files' ? 'active' : ''}`} 
            onClick={() => setActiveTab('files')}
          >
            File Explorer
          </button>
          <button 
            className={`tab-btn ${activeTab === 'settings' ? 'active' : ''}`} 
            onClick={() => setActiveTab('settings')}
          >
            Settings
          </button>
        </div>

        {error && !isDaemonOnline && (
          <div className="card" style={{ borderColor: 'var(--danger)', backgroundColor: 'var(--danger-container)' }}>
            <h2 style={{ color: 'var(--danger)' }}>Connection Error</h2>
            <p>{error}</p>
            <button className="btn btn-secondary" style={{ marginTop: '1rem' }} onClick={fetchStatus}>
              Retry Connection
            </button>
          </div>
        )}

        {status && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            
            {activeTab === 'dashboard' && (
              <>
                {/* Active Phone Call Overlay */}
                {activeCall && (
                  <div className="call-banner" style={{ width: '100%' }}>
                    <div className="call-info">
                      <span className="call-title">Active Phone Call Alert ({activeCall.state})</span>
                      <span className="caller-name">{activeCall.contact_name}</span>
                      <span className="caller-number">{activeCall.number}</span>
                    </div>
                    <div className="call-actions">
                      {activeCall.state === 'Ringing' && (
                        <>
                          <button className="btn btn-success" onClick={() => sendCallAction('accept', activeCall.call_id)}>Accept</button>
                          <button className="btn btn-danger" onClick={() => sendCallAction('reject', activeCall.call_id)}>Decline</button>
                        </>
                      )}
                      {(activeCall.state === 'Connected' || activeCall.state === 'Muted') && (
                        <>
                          {activeCall.state === 'Muted' ? (
                            <button className="btn btn-primary" onClick={() => sendCallAction('unmute', activeCall.call_id)}>Unmute</button>
                          ) : (
                            <button className="btn btn-secondary" onClick={() => sendCallAction('mute', activeCall.call_id)}>Mute</button>
                          )}
                          <button className="btn btn-danger" onClick={() => sendCallAction('reject', activeCall.call_id)}>Hang Up</button>
                        </>
                      )}
                    </div>
                  </div>
                )}

                {/* Connectivity Overview */}
                <div className="card" style={{ display: 'flex', gap: '1.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                  <div style={{ flexGrow: 1, minWidth: '250px' }}>
                    <h2>System Identity</h2>
                    <div style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: '0.75rem', fontSize: '0.95rem', marginBottom: '1rem' }}>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Device Name:</span>
                      <strong>{status.device_name}</strong>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Device ID:</span>
                      <code>{status.device_id}</code>
                    </div>

                    <h2>Connectivity Status</h2>
                    <div style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: '0.75rem', fontSize: '0.95rem' }}>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Link Connection:</span>
                      <strong style={{ color: hasConnectedDevices ? 'var(--success)' : 'var(--danger)' }}>
                        {hasConnectedDevices 
                          ? `${connectedDevices[0].connection_type || 'Wi-Fi'} (${connectedDevices[0].device_name})` 
                          : 'Disconnected (Waiting for connection...)'
                        }
                      </strong>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Bluetooth Status:</span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
                        {isBluetoothConnected ? (
                          <>
                            <strong style={{ color: 'var(--success)' }}>Connected (Audio gateway active)</strong>
                            <button 
                              className="btn btn-danger btn-sm" 
                              onClick={() => {
                                const devWithBt = status.paired_devices.find(d => d.is_bluetooth_connected);
                                if (devWithBt) {
                                  disconnectBluetooth(devWithBt.device_id);
                                }
                              }}
                            >
                              Disconnect Bluetooth
                            </button>
                          </>
                        ) : (
                          <>
                            <strong style={{ color: 'var(--text-muted)' }}>Disconnected</strong>
                            <button 
                              className="btn btn-secondary btn-sm" 
                              onClick={openBluetoothSettings}
                            >
                              Open Settings to Connect
                            </button>
                          </>
                        )}
                      </div>
                    </div>
                  </div>
                </div>

                {/* Recent Sync Status */}
                <div className="card">
                  <h2>Shared Clipboard</h2>
                  <div style={{ padding: '1.25rem', background: 'rgba(255, 255, 255, 0.04)', borderRadius: '16px', margin: '1rem 0' }}>
                    <p style={{ margin: 0, fontStyle: lastClipboard ? 'normal' : 'italic', wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
                      {lastClipboard || "No text synchronized yet..."}
                    </p>
                  </div>
                  {lastClipboard && (
                    <button className="btn btn-secondary" onClick={() => {
                      navigator.clipboard.writeText(lastClipboard);
                      const toast = document.getElementById('toast');
                      if (toast) {
                        toast.classList.add('show');
                        setTimeout(() => toast.classList.remove('show'), 2000);
                      }
                    }}>
                      Copy to PC Clipboard
                    </button>
                  )}
                </div>
              </>
            )}

            {activeTab === 'clipboard' && (
              <div className="card">
                <h2>Push to Mobile Clipboard</h2>
                {!hasConnectedDevices ? (
                  <div style={{
                    borderColor: 'var(--danger)',
                    backgroundColor: 'var(--danger-container)',
                    marginTop: '1rem',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '0.75rem',
                    padding: '1.5rem',
                    borderRadius: '16px'
                  }}>
                    <h3 style={{ margin: 0, color: 'var(--danger)', textTransform: 'uppercase' }}>Device Disconnected</h3>
                    <p style={{ margin: 0, fontSize: '0.9rem', color: 'var(--text-muted)' }}>
                      No mobile devices are currently connected. Connect a device under Settings to sync your clipboard.
                    </p>
                    <button className="btn btn-primary btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => setActiveTab('settings')}>
                      Go to Settings
                    </button>
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', marginTop: '1rem' }}>
                    <textarea 
                      placeholder="Type text to sync to your phone clipboard..." 
                      value={clipboardInput}
                      onChange={(e) => setClipboardInput(e.target.value)}
                      style={{ minHeight: '120px', resize: 'vertical' }}
                    />
                    <button className="btn btn-primary" style={{ alignSelf: 'flex-start' }} onClick={pushClipboard}>
                      Sync to Mobile
                    </button>
                  </div>
                )}
              </div>
            )}

            {activeTab === 'files' && (
              <section style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', marginTop: '1rem' }} aria-label="File Explorer">
                
                {/* Selected File Details Overlay */}
                {selectedFileInfo && (
                  <aside className="card" style={{ border: '2px solid var(--accent)', background: 'var(--bg-secondary)', position: 'relative' }}>
                    <button 
                      onClick={() => setSelectedFileInfo(null)}
                      style={{ position: 'absolute', right: '1rem', top: '1rem', background: 'none', border: 'none', color: 'var(--text-main)', fontSize: '1.2rem', cursor: 'pointer', fontWeight: 'bold' }}
                    >
                      Close
                    </button>
                    <h3 style={{ margin: '0 0 0.75rem 0' }}>
                      Detailed File Properties
                    </h3>
                    <dl style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: '0.5rem 1rem', fontSize: '0.9rem', margin: 0 }}>
                      <dt style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Name:</dt>
                      <dd style={{ margin: 0 }}>{selectedFileInfo.name}</dd>
                      <dt style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Location:</dt>
                      <dd style={{ margin: 0 }}><code style={{ wordBreak: 'break-all' }}>{selectedFileInfo.path}</code></dd>
                      <dt style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Type:</dt>
                      <dd style={{ margin: 0 }}>{selectedFileInfo.is_dir ? 'Directory / Folder' : 'Standard File'}</dd>
                      <dt style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Size:</dt>
                      <dd style={{ margin: 0 }}>{selectedFileInfo.is_dir ? '--' : `${(selectedFileInfo.size / 1024).toFixed(2)} KB (${selectedFileInfo.size} bytes)`}</dd>
                      <dt style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Last Modified:</dt>
                      <dd style={{ margin: 0 }}>{selectedFileInfo.last_modified ? new Date(selectedFileInfo.last_modified * 1000).toLocaleString() : 'Unknown'}</dd>
                    </dl>
                  </aside>
                )}

                {/* Mobile File Explorer (browsed from PC) */}
                <article className="card" style={{ display: 'flex', flexDirection: 'column', minHeight: '520px', width: '100%' }}>
                  <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <h2 style={{ margin: 0 }}>Browse Mobile Storage</h2>
                    {status?.paired_devices.some(d => d.is_online) ? (
                      <span className="status-badge online" style={{ fontSize: '0.8rem' }}>Server Active</span>
                    ) : (
                      <span className="status-badge offline" style={{ fontSize: '0.8rem' }}>Offline</span>
                    )}
                  </header>

                  {status?.paired_devices.some(d => d.is_online) ? (
                    <>
                      {/* Search & Sort Controls */}
                      <menu style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginBottom: '1rem', padding: 0, margin: '0 0 1rem 0' }}>
                        <input 
                          type="text" 
                          placeholder="Search files..." 
                          value={mobileSearch}
                          onChange={(e) => setMobileSearch(e.target.value)}
                          style={{ width: '100%', padding: '0.6rem 1rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-primary)', color: 'var(--text-main)' }}
                        />
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '0.5rem' }}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                            <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Sort by:</span>
                            <select 
                              value={mobileSort} 
                              onChange={(e) => setMobileSort(e.target.value as any)}
                              style={{ padding: '0.3rem 0.5rem', borderRadius: '4px', fontSize: '0.8rem' }}
                            >
                              <option value="name">Name</option>
                              <option value="size">Size</option>
                              <option value="date">Date Modified</option>
                            </select>
                            <button 
                              className="btn btn-secondary btn-sm"
                              style={{ padding: '0.2rem 0.6rem !important', fontSize: '0.75rem !important' }}
                              onClick={() => setMobileSortOrder(p => p === 'asc' ? 'desc' : 'asc')}
                            >
                              {mobileSortOrder === 'asc' ? 'Asc' : 'Desc'}
                            </button>
                          </div>

                          {/* File picker Upload */}
                          <label className="btn btn-primary btn-sm" style={{ cursor: 'pointer', margin: 0 }}>
                            Upload File
                            <input 
                              type="file" 
                              style={{ display: 'none' }}
                              onChange={(e) => {
                                const file = e.target.files?.[0];
                                if (file) uploadFileToMobile(file);
                              }}
                            />
                          </label>
                        </div>
                      </menu>

                      {/* Navigation Row */}
                      <nav style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.75rem', alignItems: 'center' }}>
                        <button 
                          className="btn btn-secondary btn-sm"
                          onClick={() => {
                            if (mobilePath.includes('/')) {
                              const parent = mobilePath.substring(0, mobilePath.lastIndexOf('/'));
                              fetchMobileFilesList(parent || '/sdcard');
                            }
                          }}
                        >
                          Up
                        </button>
                        <code style={{ flexGrow: 1, padding: '0.4rem 0.75rem', background: 'var(--bg-primary)', borderRadius: '6px', fontSize: '0.85rem', overflowX: 'auto', whiteSpace: 'nowrap', border: '1px solid var(--border-color)' }}>
                          {mobilePath || '/sdcard'}
                        </code>
                      </nav>

                      {/* File List Pane */}
                      {loadingMobile ? (
                        <div style={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)' }}>Loading storage...</div>
                      ) : (
                        <div style={{ flexGrow: 1, maxHeight: '380px', overflowY: 'auto', border: '1px solid var(--border-color)', borderRadius: '8px', background: 'var(--bg-primary)' }}>
                          {getProcessedFiles(mobileFiles, mobileSearch, mobileSort, mobileSortOrder).length === 0 ? (
                            <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--text-muted)' }}>No matching files found.</div>
                          ) : (
                            <ul style={{ listStyleType: 'none', padding: 0, margin: 0 }}>
                              {getProcessedFiles(mobileFiles, mobileSearch, mobileSort, mobileSortOrder).map((file: any) => (
                                <li 
                                  key={file.path} 
                                  style={{ 
                                    display: 'flex', 
                                    alignItems: 'center', 
                                    padding: '0.6rem 0.85rem', 
                                    borderBottom: '1px solid var(--border-color)',
                                    transition: 'background 0.2s'
                                  }}
                                  className="file-item-hover"
                                >
                                  <span 
                                    style={{ 
                                      padding: '0.15rem 0.4rem', 
                                      fontSize: '0.7rem', 
                                      textTransform: 'uppercase', 
                                      marginRight: '0.75rem', 
                                      background: file.is_dir ? 'var(--accent)' : 'var(--bg-secondary)', 
                                      color: file.is_dir ? 'white' : 'var(--text-main)', 
                                      border: '1px solid var(--border-color)',
                                      fontWeight: 'bold',
                                      borderRadius: '4px',
                                      cursor: 'pointer'
                                    }}
                                    onClick={() => {
                                      if (file.is_dir) fetchMobileFilesList(file.path);
                                    }}
                                  >
                                    {file.is_dir ? 'Folder' : 'File'}
                                  </span>
                                  <div 
                                    style={{ flexGrow: 1, minWidth: 0, cursor: file.is_dir ? 'pointer' : 'default' }}
                                    onClick={() => {
                                      if (file.is_dir) fetchMobileFilesList(file.path);
                                    }}
                                  >
                                    <div style={{ fontWeight: 500, fontSize: '0.85rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{file.name}</div>
                                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                                      {file.is_dir ? 'Folder' : `${(file.size / 1024).toFixed(1)} KB`}
                                    </div>
                                  </div>
                                  <div style={{ display: 'flex', gap: '0.35rem' }}>
                                    {!file.is_dir && (
                                      <button 
                                        className="btn btn-secondary btn-sm" 
                                        style={{ padding: '0.2rem 0.5rem', fontSize: '0.75rem' }}
                                        onClick={() => window.open(`http://${status?.paired_devices.filter(d => d.is_online)[0].ip}:9090/download?path=${encodeURIComponent(file.path)}`)}
                                      >
                                        Download
                                      </button>
                                    )}
                                    <button 
                                      className="btn btn-secondary btn-sm" 
                                      style={{ padding: '0.2rem 0.5rem', fontSize: '0.75rem' }}
                                      onClick={() => setSelectedFileInfo(file)}
                                    >
                                      Details
                                    </button>
                                    <button 
                                      className="btn btn-danger btn-sm" 
                                      style={{ padding: '0.2rem 0.5rem', fontSize: '0.75rem', background: 'var(--danger-container)', color: 'var(--danger)' }}
                                      onClick={() => deleteMobileFile(file.path)}
                                    >
                                      Delete
                                    </button>
                                  </div>
                                </li>
                              ))}
                            </ul>
                          )}
                        </div>
                      )}
                    </>
                  ) : (
                    <div style={{ flexGrow: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '2rem', textAlign: 'center', color: 'var(--text-muted)', gap: '1rem' }}>
                      <span>Mobile device is offline.</span>
                      <span style={{ fontSize: '0.9rem' }}>Please connect your phone to launch the lazy-loaded file server.</span>
                    </div>
                  )}
                </article>

              </section>
            )}

            {activeTab === 'settings' && (
              <>
                <div className="card">
                  <h2>Clipboard Configurations</h2>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem', marginTop: '1rem' }}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                      <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)', fontWeight: 'bold' }}>Sync Direction</span>
                      <select 
                        value={clipDirection} 
                        onChange={(e) => {
                          const val = e.target.value;
                          setClipDirection(val);
                          saveClipboardConfig(val, clipAutoSync);
                        }}
                      >
                        <option value="bidirectional">Bidirectional (Sync both ways)</option>
                        <option value="desktop_to_mobile">Desktop to Mobile Only</option>
                        <option value="mobile_to_desktop">Mobile to Desktop Only</option>
                      </select>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginTop: '0.25rem' }}>
                      <input 
                        type="checkbox" 
                        id="auto-sync-checkbox"
                        checked={clipAutoSync}
                        onChange={(e) => {
                          const checked = e.target.checked;
                          setClipAutoSync(checked);
                          saveClipboardConfig(clipDirection, checked);
                        }}
                        style={{
                          width: '18px',
                          height: '18px',
                          cursor: 'pointer',
                          accentColor: 'var(--accent)'
                        }}
                      />
                      <label htmlFor="auto-sync-checkbox" style={{ fontSize: '0.95rem', cursor: 'pointer', userSelect: 'none', fontWeight: 'bold' }}>
                        Automatically sync copies in real-time
                      </label>
                    </div>
                  </div>
                </div>

                {/* Paired Devices Manager */}
                <div className="card">
                  <h2>Paired Devices</h2>
                  {status.paired_devices.length === 0 ? (
                    <p style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
                      No paired devices found. Discoverable locally as {status.device_name} via mDNS.
                    </p>
                  ) : (
                    <div className="device-grid">
                      {status.paired_devices.map((dev) => (
                        <div key={dev.device_id} className="device-card">
                          <div>
                            <div className="device-name">{dev.device_name}</div>
                            <div className="device-id">{dev.device_id}</div>
                          </div>
                          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap' }}>
                            <span className={`status-badge badge-sm ${dev.is_online ? 'online' : 'offline'}`}>
                              {dev.is_online ? `Online (${dev.connection_type || 'Wi-Fi'})` : 'Offline'}
                            </span>
                            <span className={`status-badge badge-sm ${dev.is_bluetooth_connected ? 'online' : ''}`}>
                              Bluetooth: {dev.is_bluetooth_connected ? 'Connected' : 'Disconnected'}
                            </span>
                            {dev.is_bluetooth_connected ? (
                              <button 
                                className="btn btn-secondary btn-sm" 
                                onClick={() => disconnectBluetooth(dev.device_id)}
                              >
                                Disconnect BT
                              </button>
                            ) : (
                              <button 
                                className="btn btn-secondary btn-sm" 
                                onClick={openBluetoothSettings}
                              >
                                Connect BT
                              </button>
                            )}
                            <button 
                              className="btn btn-danger btn-sm" 
                              onClick={() => unpairDevice(dev.device_id)}
                            >
                              Unpair
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                {/* Wi-Fi Setup QR Code (Hidden when connected) */}
                {!hasConnectedDevices && (
                  <div className="card" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem' }}>
                    <h2>Wi-Fi Connection Wizard</h2>
                    <p style={{ textAlign: 'center', color: 'var(--text-muted)', maxWidth: '500px', fontSize: '0.95rem' }}>
                      To pair a new mobile device over Wi-Fi, open the platypusd app and scan this pairing configuration.
                    </p>
                    {qrCodeUrl ? (
                      <div className="qr-container">
                        <img src={qrCodeUrl} alt="Pairing QR Code" style={{ width: '180px', height: '180px', display: 'block' }} />
                      </div>
                    ) : (
                      <p>Generating pairing configurations...</p>
                    )}
                  </div>
                )}
              </>
            )}

          </div>
        )}

        {/* Floating Toast Notification */}
        <div id="toast" className="toast-container">
          Copied to clipboard!
        </div>

      </div>
    </div>
  );
}
