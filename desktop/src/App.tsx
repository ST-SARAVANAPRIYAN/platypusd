import { useState, useEffect } from 'react';
import QRCode from 'qrcode';

interface PairedDevice {
  device_id: string;
  device_name: string;
  is_online: boolean;
  is_bluetooth_connected: boolean;
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
  const [status, setStatus] = useState<StatusData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activeCall, setActiveCall] = useState<ActiveCall | null>(null);
  const [lastClipboard, setLastClipboard] = useState<string>('');
  const [clipboardInput, setClipboardInput] = useState<string>('');
  const [isDaemonOnline, setIsDaemonOnline] = useState<boolean>(false);
  const [qrCodeUrl, setQrCodeUrl] = useState<string>('');
  const [activeTab, setActiveTab] = useState<string>('devices');
  const [clipDirection, setClipDirection] = useState<string>('bidirectional');
  const [clipAutoSync, setClipAutoSync] = useState<boolean>(true);
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [speakerMode, setSpeakerMode] = useState<string>('desktop_as_speaker');
  const [callSyncEnabled, setCallSyncEnabled] = useState<boolean>(true);

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

  const fetchBluetoothConfig = async () => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/bluetooth/config');
      if (res.ok) {
        const data = await res.json();
        setSpeakerMode(data.speaker_mode);
        setCallSyncEnabled(data.call_sync_enabled);
      }
    } catch (err) {
      console.error("Failed to fetch bluetooth config:", err);
    }
  };

  const saveBluetoothConfig = async (mode: string, enabled: boolean) => {
    try {
      const res = await fetch('http://localhost:8080/api/v1/bluetooth/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ speaker_mode: mode, call_sync_enabled: enabled })
      });
      if (!res.ok) throw new Error("Failed to save bluetooth config");
    } catch (err: any) {
      alert(`Error saving bluetooth config: ${err.message}`);
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
    fetchBluetoothConfig();

    let ws: WebSocket | null = null;
    let reconnectTimeout: any = null;

    const connectWS = () => {
      console.log('Connecting to WebSocket event stream...');
      ws = new WebSocket('ws://localhost:8080/api/v1/events?device_id=desktop');

      ws.onopen = () => {
        console.log('Connected to platypusd event stream');
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
        setIsDaemonOnline(false);
        reconnectTimeout = setTimeout(connectWS, 3000);
      };
    };

    connectWS();

    return () => {
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
          <h1>platypusd dashboard</h1>
          <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
            <button 
              className="btn btn-secondary" 
              onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
              style={{ fontSize: '0.8rem', padding: '0.5rem 1rem' }}
            >
              {theme === 'light' ? 'Dark Mode' : 'Light Mode'}
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
            className={`tab-btn ${activeTab === 'devices' ? 'active' : ''}`} 
            onClick={() => setActiveTab('devices')}
          >
            Devices {status && status.paired_devices.length > 0 ? `(${status.paired_devices.filter(d => d.is_online).length}/${status.paired_devices.length} Connected)` : ''}
          </button>
          <button 
            className={`tab-btn ${activeTab === 'clipboard' ? 'active' : ''}`} 
            onClick={() => setActiveTab('clipboard')}
          >
            Clipboard Sync {status && (hasConnectedDevices ? 'Active' : 'Offline')}
          </button>
          <button 
            className={`tab-btn ${activeTab === 'calls' ? 'active' : ''}`} 
            onClick={() => setActiveTab('calls')}
          >
            Bluetooth {status && (hasConnectedDevices ? 'Active' : 'Offline')}
          </button>
        </div>

        {error && !isDaemonOnline && (
          <div className="card" style={{ borderColor: 'var(--danger)', background: 'rgba(244, 63, 94, 0.05)' }}>
            <h2 style={{ color: 'var(--danger)' }}>Connection Error</h2>
            <p>{error}</p>
            <button className="btn btn-secondary" style={{ marginTop: '1rem' }} onClick={fetchStatus}>
              Retry Connection
            </button>
          </div>
        )}

        {/* Conditionally rendering call banners if call active and not in Calls tab to preserve alerts */}
        {activeCall && activeTab !== 'calls' && (
          <div className="call-banner" style={{ marginBottom: '1.5rem' }}>
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

        {status && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            
            {activeTab === 'devices' && (
              <>
                {/* System Identity & Connectivity Status */}
                <div className="card" style={{ display: 'flex', gap: '1.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                  <div style={{ flexGrow: 1, minWidth: '250px' }}>
                    <h2>System Identity</h2>
                    <div style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: '0.75rem', fontSize: '0.95rem', marginBottom: '1rem' }}>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Device Name:</span>
                      <strong>{status.device_name}</strong>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Device ID:</span>
                      <code>{status.device_id}</code>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Public Key:</span>
                      <code style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {status.public_key}
                      </code>
                    </div>

                    <h2>Connectivity Status</h2>
                    <div style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: '0.75rem', fontSize: '0.95rem' }}>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Wi-Fi Status:</span>
                      <strong style={{ color: hasConnectedDevices ? 'var(--success)' : 'var(--danger)' }}>
                        {hasConnectedDevices 
                          ? `Connected (${connectedDevices[0].device_name})` 
                          : 'Disconnected (Waiting for connection...)'
                        }
                      </strong>
                      <span style={{ color: 'var(--text-muted)', fontWeight: 'bold' }}>Bluetooth Status:</span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
                        {isBluetoothConnected ? (
                          <>
                            <strong style={{ color: 'var(--success)' }}>Connected (Audio gateway active)</strong>
                            <button 
                              className="btn btn-danger" 
                              style={{ padding: '0.25rem 0.75rem', fontSize: '0.75rem', border: 'none', height: 'auto', minHeight: 'unset', boxShadow: 'none' }}
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
                              className="btn btn-secondary" 
                              style={{ padding: '0.25rem 0.75rem', fontSize: '0.75rem', border: '3px solid var(--border-color)', height: 'auto', minHeight: 'unset', boxShadow: 'none' }}
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

                {/* Wi-Fi Setup QR Code (Hidden when connected) */}
                {!hasConnectedDevices && (
                  <div className="card" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '1rem' }}>
                    <h2>Wi-Fi Pairing Configuration</h2>
                    <p style={{ textAlign: 'center', color: 'var(--text-muted)', maxWidth: '500px', fontSize: '0.95rem' }}>
                      To sync clipboards and calls over Wi-Fi, open the platypusd app on your mobile device and scan this QR code.
                    </p>
                    {qrCodeUrl ? (
                      <div style={{ background: '#fff', padding: '1rem', border: '3px solid var(--border-color)' }}>
                        <img src={qrCodeUrl} alt="Pairing QR Code" style={{ width: '180px', height: '180px', display: 'block' }} />
                      </div>
                    ) : (
                      <p>Generating pairing configurations...</p>
                    )}
                  </div>
                )}

                {/* Paired Devices List */}
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
                          <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
                            <span className={`status-badge ${dev.is_online ? 'online' : 'offline'}`} style={{ boxShadow: 'none', border: '2px solid var(--border-color)', padding: '0.2rem 0.5rem', fontSize: '0.75rem' }}>
                              {dev.is_online ? 'Online' : 'Offline'}
                            </span>
                            <span className={`status-badge ${dev.is_bluetooth_connected ? 'online' : ''}`} style={{ 
                              boxShadow: 'none', 
                              border: '2px solid var(--border-color)', 
                              padding: '0.2rem 0.5rem', 
                              fontSize: '0.75rem',
                              color: dev.is_bluetooth_connected ? 'var(--success)' : 'var(--text-muted)',
                              backgroundColor: dev.is_bluetooth_connected ? 'rgba(16, 185, 129, 0.1)' : 'transparent'
                            }}>
                              Bluetooth: {dev.is_bluetooth_connected ? 'Connected' : 'Disconnected'}
                            </span>
                            {dev.is_bluetooth_connected ? (
                              <button 
                                className="btn btn-secondary" 
                                style={{ padding: '0.35rem 0.75rem', fontSize: '0.75rem', border: '3px solid var(--border-color)', cursor: 'pointer', height: 'auto', minHeight: 'unset', boxShadow: 'none' }}
                                onClick={() => disconnectBluetooth(dev.device_id)}
                              >
                                Disconnect BT
                              </button>
                            ) : (
                              <button 
                                className="btn btn-secondary" 
                                style={{ padding: '0.35rem 0.75rem', fontSize: '0.75rem', border: '3px solid var(--border-color)', cursor: 'pointer', height: 'auto', minHeight: 'unset', boxShadow: 'none' }}
                                onClick={openBluetoothSettings}
                              >
                                Connect BT
                              </button>
                            )}
                            <button 
                              className="btn btn-danger" 
                              style={{ padding: '0.35rem 0.75rem', fontSize: '0.75rem', border: 'none', cursor: 'pointer', height: 'auto', minHeight: 'unset', boxShadow: 'none' }}
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
              </>
            )}

            {activeTab === 'clipboard' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
                <div className="card">
                  <h2>Clipboard Sync Settings</h2>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem', marginTop: '1rem' }}>
                    
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                      <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)', fontWeight: 'bold' }}>Sync Mode / Direction</span>
                      <select 
                        value={clipDirection} 
                        onChange={(e) => {
                          const val = e.target.value;
                          setClipDirection(val);
                          saveClipboardConfig(val, clipAutoSync);
                        }}
                        style={{
                          background: 'var(--bg-secondary)',
                          border: '3px solid var(--border-color)',
                          padding: '0.75rem',
                          color: 'var(--text-main)',
                          fontSize: '0.95rem',
                          outline: 'none',
                          cursor: 'pointer',
                          fontWeight: 'bold'
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

                <div className="card">
                  <h2>Live Clipboard Synchronizer</h2>
                  {!hasConnectedDevices ? (
                    <div style={{
                      background: 'rgba(244, 63, 94, 0.05)',
                      border: '3px solid var(--danger)',
                      padding: '1.25rem',
                      marginTop: '1rem',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: '0.75rem'
                    }}>
                      <h3 style={{ margin: 0, color: 'var(--danger)', textTransform: 'uppercase' }}>Device Disconnected</h3>
                      <p style={{ margin: 0, fontSize: '0.9rem', color: 'var(--text-muted)' }}>
                        No mobile devices are currently connected. Please open the platypusd app on your mobile device and connect to this PC to sync your clipboard.
                      </p>
                      <button className="btn btn-primary" style={{ alignSelf: 'flex-start' }} onClick={() => setActiveTab('devices')}>
                        Pair / Connect Device
                      </button>
                    </div>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem', marginTop: '0.5rem' }}>
                      {lastClipboard && (
                        <div style={{
                          background: 'rgba(255, 255, 255, 0.04)',
                          padding: '1rem',
                          borderLeft: '4px solid var(--accent)',
                          fontSize: '0.95rem'
                        }}>
                          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '0.25rem', textTransform: 'uppercase', fontWeight: 'bold' }}>
                            Active Shared Clipboard:
                          </div>
                          <code style={{ wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>{lastClipboard}</code>
                        </div>
                      )}
                      
                      <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
                        <input 
                          type="text" 
                          placeholder="Type text to sync to all device clipboards..." 
                          value={clipboardInput}
                          onChange={(e) => setClipboardInput(e.target.value)}
                          onKeyDown={(e) => e.key === 'Enter' && pushClipboard()}
                          style={{
                            flexGrow: 1,
                            background: 'var(--bg-secondary)',
                            border: '3px solid var(--border-color)',
                            padding: '0.75rem',
                            color: 'var(--text-main)',
                            fontSize: '0.95rem',
                            outline: 'none',
                            fontWeight: 'bold'
                          }}
                        />
                        <button className="btn btn-primary" onClick={pushClipboard}>
                          Sync
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )}

            {activeTab === 'calls' && (
              <div className="card">
                <h2>Bluetooth Connectivity & Audio Role Configuration</h2>
                {!isBluetoothConnected ? (
                  <div style={{
                    background: 'rgba(244, 63, 94, 0.05)',
                    border: '3px solid var(--danger)',
                    padding: '1.25rem',
                    marginTop: '1rem',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '0.75rem'
                  }}>
                    <h3 style={{ margin: 0, color: 'var(--danger)', textTransform: 'uppercase' }}>⚠️ Bluetooth Device Disconnected</h3>
                    <p style={{ margin: 0, fontSize: '0.9rem', color: 'var(--text-muted)' }}>
                      No mobile device is currently connected to this host via Bluetooth. Audio role modes and call routing require an active Bluetooth link.
                    </p>
                    <button className="btn btn-primary" style={{ alignSelf: 'flex-start' }} onClick={openBluetoothSettings}>
                      Open System Bluetooth Settings
                    </button>
                  </div>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', marginTop: '1rem' }}>
                    <div className="status-banner" style={{ background: 'rgba(16, 185, 129, 0.05)', border: '3px solid var(--success)', padding: '1rem' }}>
                      <h3 style={{ margin: 0, color: 'var(--success)' }}>✅ Bluetooth Connection Active</h3>
                      <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.9rem', color: 'var(--text-muted)' }}>
                        Connected to paired mobile integration device. Audio routing and call states are synchronized.
                      </p>
                    </div>

                    <div className="config-section" style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                      <h3>Audio Role Mode Settings</h3>
                      <div className="form-group" style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                        <label style={{ fontWeight: 'bold' }}>Audio Role Mode:</label>
                        <select 
                          value={speakerMode} 
                          onChange={(e) => {
                            const val = e.target.value;
                            setSpeakerMode(val);
                            saveBluetoothConfig(val, callSyncEnabled);
                          }}
                          style={{
                            background: 'var(--bg-secondary)',
                            border: '3px solid var(--border-color)',
                            padding: '0.75rem',
                            color: 'var(--text)',
                            fontWeight: 'bold',
                            outline: 'none'
                          }}
                        >
                          <option value="desktop_as_speaker">Desktop as Speaker (PC plays phone audio)</option>
                          <option value="mobile_as_speaker">Mobile as Speaker (Phone plays PC audio)</option>
                        </select>
                      </div>

                      <div className="form-group" style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginTop: '0.5rem' }}>
                        <input 
                          type="checkbox" 
                          id="callSyncToggle"
                          checked={callSyncEnabled} 
                          onChange={(e) => {
                            const val = e.target.checked;
                            setCallSyncEnabled(val);
                            saveBluetoothConfig(speakerMode, val);
                          }}
                          style={{ width: '20px', height: '20px', accentColor: 'var(--accent)' }}
                        />
                        <label htmlFor="callSyncToggle" style={{ fontWeight: 'bold', cursor: 'pointer' }}>Enable Automatic Call Audio Routing</label>
                      </div>
                    </div>

                    {activeCall ? (
                      <div className="call-banner" style={{ background: 'var(--bg-secondary)', border: '3px solid var(--border-color)', width: '100%', marginTop: '1rem' }}>
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
                    ) : (
                      <p style={{ color: 'var(--text-muted)', fontStyle: 'italic', marginTop: '1rem' }}>
                        No active call at the moment. Audio routing loops will be established automatically based on the selected Role Mode when a call starts.
                      </p>
                    )}
                  </div>
                )}
              </div>
            )}

          </div>
        )}
      </div>
    </div>
  );
}
