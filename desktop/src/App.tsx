import { useState, useEffect } from 'react';
import QRCode from 'qrcode';

interface PairedDevice {
  device_id: string;
  device_name: string;
  is_online: boolean;
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
            payload.event === 'DeviceUnpaired'
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

  return (
    <div className="container">
      <header>
        <h1>
          <span>🦦</span> platypusd dashboard
        </h1>
        <div className={`status-badge ${isDaemonOnline ? 'online' : ''} ${!isDaemonOnline ? 'offline' : ''}`}>
          <span className="indicator">●</span>
          {isDaemonOnline ? 'Daemon Online' : 'Daemon Offline'}
        </div>
      </header>

      {/* Dashboard Tabs */}
      <div className="tab-bar">
        <button 
          className={`tab-btn ${activeTab === 'devices' ? 'active' : ''}`} 
          onClick={() => setActiveTab('devices')}
        >
          📱 Devices {status && status.paired_devices.length > 0 ? `(${status.paired_devices.filter(d => d.is_online).length}/${status.paired_devices.length} Connected)` : ''}
        </button>
        <button 
          className={`tab-btn ${activeTab === 'clipboard' ? 'active' : ''}`} 
          onClick={() => setActiveTab('clipboard')}
        >
          📋 Clipboard Sync {status && (hasConnectedDevices ? '🟢' : '🔴')}
        </button>
        <button 
          className={`tab-btn ${activeTab === 'calls' ? 'active' : ''}`} 
          onClick={() => setActiveTab('calls')}
        >
          📞 Call Sync {status && (hasConnectedDevices ? '🟢' : '🔴')}
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
            <span className="call-title">📞 Active Phone Call Alert ({activeCall.state})</span>
            <span className="caller-name">{activeCall.contact_name}</span>
            <span className="caller-number">{activeCall.number}</span>
          </div>
          <div className="call-actions">
            {activeCall.state === 'Ringing' && (
              <>
                <button className="btn btn-success" onClick={() => sendCallAction('accept', activeCall.call_id)}>Accept & Route</button>
                <button className="btn btn-danger" onClick={() => sendCallAction('reject', activeCall.call_id)}>Decline</button>
              </>
            )}
            {(activeCall.state === 'Connected' || activeCall.state === 'Muted') && (
              <>
                {activeCall.state === 'Muted' ? (
                  <button className="btn btn-primary" onClick={() => sendCallAction('unmute', activeCall.call_id)}>Unmute Mic</button>
                ) : (
                  <button className="btn btn-secondary" onClick={() => sendCallAction('mute', activeCall.call_id)}>Mute Mic</button>
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
              <div className="card" style={{ display: 'flex', gap: '1.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                <div style={{ flexGrow: 1, minWidth: '250px' }}>
                  <h2>System Identity</h2>
                  <div style={{ display: 'grid', gridTemplateColumns: '150px 1fr', gap: '0.5rem', fontSize: '0.95rem' }}>
                    <span style={{ color: 'var(--text-muted)' }}>Device Name:</span>
                    <strong>{status.device_name}</strong>
                    <span style={{ color: 'var(--text-muted)' }}>Device ID:</span>
                    <code>{status.device_id}</code>
                    <span style={{ color: 'var(--text-muted)' }}>Public Key:</span>
                    <code style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {status.public_key}
                    </code>
                  </div>
                </div>
                {qrCodeUrl && (
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '0.5rem', padding: '0.5rem', background: '#fff', borderRadius: '12px' }}>
                    <img src={qrCodeUrl} alt="Pairing QR Code" style={{ width: '120px', height: '120px', display: 'block' }} />
                    <span style={{ fontSize: '0.75rem', color: '#0f172a', fontWeight: 'bold' }}>Scan QR to Pair</span>
                  </div>
                )}
              </div>

              <div className="card">
                <h2>Paired Mobile Devices</h2>
                {status.paired_devices.length === 0 ? (
                  <p style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
                    No paired devices. Discoverable locally as <strong>{status.device_name}</strong> via mDNS.
                  </p>
                ) : (
                  <div className="device-grid">
                    {status.paired_devices.map((dev) => (
                      <div key={dev.device_id} className="device-card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '1rem' }}>
                        <div>
                          <div className="device-name">📱 {dev.device_name}</div>
                          <div className="device-id">{dev.device_id}</div>
                        </div>
                        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                          <span className={`status-badge ${dev.is_online ? 'online' : 'offline'}`} style={{ padding: '0.2rem 0.5rem', fontSize: '0.75rem', display: 'flex', alignItems: 'center', gap: '0.25rem' }}>
                            <span style={{ fontSize: '0.65rem' }}>{dev.is_online ? '●' : '○'}</span>
                            {dev.is_online ? 'Connected' : 'Disconnected'}
                          </span>
                          <button 
                            className="btn btn-danger" 
                            style={{ padding: '0.35rem 0.75rem', fontSize: '0.75rem', border: 'none', borderRadius: '6px', cursor: 'pointer', height: 'auto', minHeight: 'unset' }}
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
                <h2>⚙️ Clipboard Synchronization Settings</h2>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem', marginTop: '1rem' }}>
                  
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)' }}>Sync Mode / Direction</span>
                    <select 
                      value={clipDirection} 
                      onChange={(e) => {
                        const val = e.target.value;
                        setClipDirection(val);
                        saveClipboardConfig(val, clipAutoSync);
                      }}
                      style={{
                        background: 'rgba(0, 0, 0, 0.4)',
                        border: '1px solid var(--border-card)',
                        borderRadius: '8px',
                        padding: '0.75rem',
                        color: 'var(--text-main)',
                        fontSize: '0.95rem',
                        outline: 'none',
                        cursor: 'pointer'
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
                    <label htmlFor="auto-sync-checkbox" style={{ fontSize: '0.95rem', cursor: 'pointer', userSelect: 'none' }}>
                      Automatically sync copies in real-time (Auto Sync)
                    </label>
                  </div>

                </div>
              </div>

              <div className="card">
                <h2>📋 Live Clipboard Synchronizer</h2>
                {!hasConnectedDevices ? (
                  <div style={{
                    background: 'rgba(244, 63, 94, 0.05)',
                    border: '1px solid var(--danger)',
                    padding: '1.25rem',
                    borderRadius: '8px',
                    marginTop: '1rem',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '0.75rem'
                  }}>
                    <h3 style={{ margin: 0, color: 'var(--danger)' }}>⚠️ Device Disconnected</h3>
                    <p style={{ margin: 0, fontSize: '0.9rem', color: 'var(--text-muted)' }}>
                      No mobile devices are currently connected. Please open the platypusd app on your mobile device and connect to this PC (<strong>{status.device_name}</strong>) to sync your clipboard.
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
                        borderRadius: '8px',
                        borderLeft: '4px solid var(--accent)',
                        fontSize: '0.95rem'
                      }}>
                        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '0.25rem', textTransform: 'uppercase' }}>
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
                          background: 'rgba(0, 0, 0, 0.3)',
                          border: '1px solid var(--border-card)',
                          borderRadius: '8px',
                          padding: '0.75rem',
                          color: 'var(--text-main)',
                          fontSize: '0.95rem',
                          outline: 'none'
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
              <h2>📞 Call Control Integration</h2>
              {!hasConnectedDevices ? (
                <div style={{
                  background: 'rgba(244, 63, 94, 0.05)',
                  border: '1px solid var(--danger)',
                  padding: '1.25rem',
                  borderRadius: '8px',
                  marginTop: '1rem',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '0.75rem'
                }}>
                  <h3 style={{ margin: 0, color: 'var(--danger)' }}>⚠️ Device Disconnected</h3>
                  <p style={{ margin: 0, fontSize: '0.9rem', color: 'var(--text-muted)' }}>
                    No mobile devices are currently connected. Live phone call events and remote controls require an active mobile integration.
                  </p>
                  <button className="btn btn-primary" style={{ alignSelf: 'flex-start' }} onClick={() => setActiveTab('devices')}>
                    Pair / Connect Device
                  </button>
                </div>
              ) : activeCall ? (
                <div className="call-banner" style={{ background: 'rgba(255, 255, 255, 0.02)', border: '1px solid var(--border-card)', width: '100%' }}>
                  <div className="call-info">
                    <span className="call-title">📞 Active Phone Call Alert ({activeCall.state})</span>
                    <span className="caller-name">{activeCall.contact_name}</span>
                    <span className="caller-number">{activeCall.number}</span>
                  </div>
                  <div className="call-actions">
                    {activeCall.state === 'Ringing' && (
                      <>
                        <button className="btn btn-success" onClick={() => sendCallAction('accept', activeCall.call_id)}>Accept & Route</button>
                        <button className="btn btn-danger" onClick={() => sendCallAction('reject', activeCall.call_id)}>Decline</button>
                      </>
                    )}
                    {(activeCall.state === 'Connected' || activeCall.state === 'Muted') && (
                      <>
                        {activeCall.state === 'Muted' ? (
                          <button className="btn btn-primary" onClick={() => sendCallAction('unmute', activeCall.call_id)}>Unmute Mic</button>
                        ) : (
                          <button className="btn btn-secondary" onClick={() => sendCallAction('mute', activeCall.call_id)}>Mute Mic</button>
                        )}
                        <button className="btn btn-danger" onClick={() => sendCallAction('reject', activeCall.call_id)}>Hang Up</button>
                      </>
                    )}
                  </div>
                </div>
              ) : (
                <p style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
                  No active calls at the moment. Desktop audio routing loops will be established automatically when a call starts.
                </p>
              )}
            </div>
          )}

        </div>
      )}
    </div>
  );
}
