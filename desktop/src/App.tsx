import { useState, useEffect, useRef } from 'react';
import QRCode from 'qrcode';
import { LayoutDashboard, Clipboard, Folder, Settings, Volume2, VolumeX } from 'lucide-react';

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
  wifi_speaker_active: boolean;
  call_gateway_enabled: boolean;
  audio_config: {
    audio_direction: 'desktop_to_mobile' | 'mobile_to_desktop';
    playback_mode: 'destination_only' | 'both';
    wifi_speaker_active: boolean;
  };
}

export default function App() {
  const wsRef = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<StatusData | null>(null);
  const statusRef = useRef(status);
  useEffect(() => {
    statusRef.current = status;
  }, [status]);
  const [error, setError] = useState<string | null>(null);
  const [activeCall, setActiveCall] = useState<ActiveCall | null>(null);
  const [wifiSpeakerActive, setWifiSpeakerActive] = useState<boolean>(false);
  const [startingSpeaker, setStartingSpeaker] = useState<boolean>(false);
  const [audioSyncEnabled, setAudioSyncEnabled] = useState<boolean>(true);
  const [audioDirection, setAudioDirection] = useState<'desktop_to_mobile' | 'mobile_to_desktop'>('desktop_to_mobile');
  const [desktopToMobilePlaybackMode, setDesktopToMobilePlaybackMode] = useState<'destination_only' | 'both'>('destination_only');
  const [mobileToDesktopPlaybackMode, setMobileToDesktopPlaybackMode] = useState<'destination_only' | 'both'>('destination_only');
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
  const [viewMode, setViewMode] = useState<'list' | 'grid' | 'compact'>('list');
  const [hideHiddenFiles, setHideHiddenFiles] = useState<boolean>(true);

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







  const getProcessedFiles = (
    files: any[],
    search: string,
    sortBy: 'name' | 'size' | 'date',
    sortOrder: 'asc' | 'desc'
  ) => {
    let result = files.filter(f => {
      if (hideHiddenFiles && f.name.startsWith('.')) return false;
      return f.name.toLowerCase().includes(search.toLowerCase());
    });
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



  const fetchMobileFilesList = async (targetPath: string, retryCount = 0) => {
    const connected = status?.paired_devices.filter(d => d.is_online) || [];
    if (!connected.length || !connected[0].ip) {
      return;
    }
    const phoneIp = connected[0].ip;
    const path = targetPath || '/sdcard';
    if (retryCount === 0) {
      setLoadingMobile(true);
    }
    try {
      const url = `http://${phoneIp}:9090/list?path=${encodeURIComponent(path)}`;
      const res = await fetch(url);
      if (res.ok) {
        const data = await res.json();
        setMobileFiles(data);
        setMobilePath(path);
        setLoadingMobile(false);
      } else {
        console.error("Failed to fetch mobile files:", res.statusText);
        if (retryCount < 3) {
          setTimeout(() => fetchMobileFilesList(path, retryCount + 1), 600);
        } else {
          setLoadingMobile(false);
        }
      }
    } catch (e) {
      console.error("Network error fetching mobile files, retrying...", e);
      if (retryCount < 3) {
        setTimeout(() => fetchMobileFilesList(path, retryCount + 1), 600);
      } else {
        setLoadingMobile(false);
      }
    }
  };

  const handleFileClick = async (file: any) => {
    if (file.is_dir) {
      fetchMobileFilesList(file.path);
      return;
    }
    
    const connected = status?.paired_devices.filter(d => d.is_online) || [];
    if (!connected.length || !connected[0].ip) return;
    const phoneIp = connected[0].ip;
    
    const fileUrl = `http://${phoneIp}:9090/view?path=${encodeURIComponent(file.path)}`;
    window.open(fileUrl, '_blank');
  };

  const downloadMobileFile = (file: any) => {
    const connected = status?.paired_devices.filter(d => d.is_online) || [];
    if (!connected.length || !connected[0].ip) return;
    const phoneIp = connected[0].ip;
    
    const fileUrl = `http://${phoneIp}:9090/download?path=${encodeURIComponent(file.path)}`;
    const link = document.createElement('a');
    link.href = fileUrl;
    link.download = file.name;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
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
        const connected = statusRef.current?.paired_devices.filter(d => d.is_online) || [];
        if (connected.length && connected[0].ip) {
          fetchMobileFilesList(mobilePath || '');
        }
      }, 250);

      return () => {
        clearTimeout(timer);
        sendWebSocketCommand('StopFileServer');
      };
    }
  }, [activeTab, isDaemonOnline]);

  const isPhoneOnline = status?.paired_devices.some(d => d.is_online) || false;
  useEffect(() => {
    if (activeTab === 'files' && isPhoneOnline) {
      const connected = status?.paired_devices.filter(d => d.is_online) || [];
      if (connected.length && connected[0].ip) {
        fetchMobileFilesList(mobilePath || '');
      }
    }
  }, [activeTab, isPhoneOnline]);
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [palette, setPalette] = useState<string>(() => {
    return localStorage.getItem('theme-palette') || 'purple';
  });

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
      if (data.audio_config) {
        setAudioDirection(data.audio_config.audio_direction);
        setDesktopToMobilePlaybackMode(data.audio_config.playback_mode);
        setWifiSpeakerActive(data.audio_config.wifi_speaker_active);
        setAudioSyncEnabled(data.audio_config.wifi_speaker_active || data.call_gateway_enabled);
      } else {
        setWifiSpeakerActive(data.wifi_speaker_active);
        if (data.call_gateway_enabled) {
          setAudioSyncEnabled(true);
          setAudioDirection('mobile_to_desktop');
        } else if (data.wifi_speaker_active) {
          setAudioSyncEnabled(true);
          setAudioDirection('desktop_to_mobile');
        }
      }
      if (data.active_call) {
        setActiveCall(data.active_call);
      }
      setError(null);
    } catch (err: any) {
      setError(err.message || 'Could not connect to platypusd daemon');
      setStatus(null);
    }
  };

  const updateAudioConfig = async (newConfig: {
    audio_direction?: 'desktop_to_mobile' | 'mobile_to_desktop';
    playback_mode?: 'destination_only' | 'both';
    wifi_speaker_active?: boolean;
  }) => {
    try {
      const currentDir = newConfig.audio_direction !== undefined ? newConfig.audio_direction : audioDirection;
      const currentMode = newConfig.playback_mode !== undefined ? newConfig.playback_mode : desktopToMobilePlaybackMode;
      const currentActive = newConfig.wifi_speaker_active !== undefined ? newConfig.wifi_speaker_active : wifiSpeakerActive;

      const res = await fetch('http://localhost:8080/api/v1/audio/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          audio_direction: currentDir,
          playback_mode: currentMode,
          wifi_speaker_active: currentActive
        })
      });
      if (!res.ok) throw new Error('Failed to update audio configuration');
      
      if (newConfig.audio_direction !== undefined) setAudioDirection(newConfig.audio_direction);
      if (newConfig.playback_mode !== undefined) setDesktopToMobilePlaybackMode(newConfig.playback_mode);
      if (newConfig.wifi_speaker_active !== undefined) setWifiSpeakerActive(newConfig.wifi_speaker_active);
    } catch (err: any) {
      console.error(err);
      showToast(`Error updating config: ${err.message}`);
    }
  };

  const toggleWifiSpeaker = async () => {
    const connected = status?.paired_devices.filter(d => d.is_online) || [];
    if (connected.length === 0) {
      alert("No paired mobile device is online over Wi-Fi.");
      return;
    }
    
    setStartingSpeaker(true);
    try {
      if (wifiSpeakerActive) {
        const res = await fetch('http://localhost:8080/api/v1/speaker/stop', { method: 'POST' });
        if (!res.ok) throw new Error('Failed to stop Wi-Fi Speaker');
        setWifiSpeakerActive(false);
        showToast('Wi-Fi Speaker Stopped');
      } else {
        const res = await fetch('http://localhost:8080/api/v1/speaker/start', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ 
            device_id: connected[0].device_id,
            playback_mode: desktopToMobilePlaybackMode
          })
        });
        if (!res.ok) {
          const errData = await res.json();
          throw new Error(errData.error || 'Failed to start Wi-Fi Speaker');
        }
        setWifiSpeakerActive(true);
        showToast('Wi-Fi Speaker Started!');
      }
    } catch (err: any) {
      alert(`Wi-Fi Speaker Error: ${err.message}`);
    } finally {
      setStartingSpeaker(false);
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
    if (!isDaemonOnline) return;
    
    const syncBackend = async () => {
      try {
        const gatewayShouldBeActive = audioSyncEnabled && audioDirection === 'mobile_to_desktop';
        await fetch('http://localhost:8080/api/v1/calls/gateway/toggle', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: gatewayShouldBeActive })
        });

        const speakerShouldBeActive = audioSyncEnabled && audioDirection === 'desktop_to_mobile';
        if (!speakerShouldBeActive && wifiSpeakerActive) {
          await fetch('http://localhost:8080/api/v1/speaker/stop', { method: 'POST' });
          setWifiSpeakerActive(false);
          showToast('Wi-Fi Speaker Stopped');
        }
      } catch (err) {
        console.error("Failed to sync audio state to backend:", err);
      }
    };
    
    syncBackend();
  }, [audioSyncEnabled, audioDirection, isDaemonOnline]);

  useEffect(() => {
    fetchStatus();
    fetchClipboardConfig();

    let ws: WebSocket | null = null;
    let reconnectTimeout: any = null;

    const connectWS = () => {
      if (ws) {
        ws.onopen = null;
        ws.onmessage = null;
        ws.onerror = null;
        ws.onclose = null;
        try { ws.close(); } catch (e) {}
      }
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
          } else if (payload.event === 'ClipboardConfigChanged') {
            setClipDirection(payload.data.direction);
            setClipAutoSync(payload.data.auto_sync);
          } else if (payload.event === 'AudioConfigChanged') {
            setAudioDirection(payload.data.audio_direction);
            setDesktopToMobilePlaybackMode(payload.data.playback_mode);
            setWifiSpeakerActive(payload.data.wifi_speaker_active);
            if (payload.data.wifi_speaker_active) {
              setAudioSyncEnabled(true);
            }
          } else if (payload.event === 'ClipboardSynced') {
            setLastClipboard(payload.data.text);
          } else if (payload.event === 'WifiSpeakerStopped') {
            setWifiSpeakerActive(false);
            showToast('Wi-Fi Speaker Stopped');
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
  const isBluetoothConnected = status?.paired_devices.some(d => d.is_bluetooth_connected) || false;
  return (
    <div className={`theme-root ${theme}-mode palette-${palette}`}>
      <div className="container">
        
        {/* Sidebar Navigation */}
        <aside className="app-sidebar">
          <div className="sidebar-title-container">
            <span className="sidebar-logo">P</span>
            <h1 className="sidebar-title-text">platypusd</h1>
          </div>
          
          <nav className="sidebar-nav">
            <button 
              className={`sidebar-btn ${activeTab === 'dashboard' ? 'active' : ''}`} 
              onClick={() => setActiveTab('dashboard')}
            >
              <span className="btn-icon"><LayoutDashboard size={18} /></span>
              <span className="btn-text">Dashboard</span>
            </button>
            <button 
              className={`sidebar-btn ${activeTab === 'clipboard' ? 'active' : ''}`} 
              onClick={() => setActiveTab('clipboard')}
            >
              <span className="btn-icon"><Clipboard size={18} /></span>
              <span className="btn-text">Clipboard Sync</span>
            </button>
            <button 
              className={`sidebar-btn ${activeTab === 'files' ? 'active' : ''}`} 
              onClick={() => setActiveTab('files')}
            >
              <span className="btn-icon"><Folder size={18} /></span>
              <span className="btn-text">File Explorer</span>
            </button>
            <button 
              className={`sidebar-btn ${activeTab === 'speaker' ? 'active' : ''}`} 
              onClick={() => setActiveTab('speaker')}
            >
              <span className="btn-icon"><Volume2 size={18} /></span>
              <span className="btn-text">Audio Sync</span>
            </button>
            <button 
              className={`sidebar-btn ${activeTab === 'settings' ? 'active' : ''}`} 
              onClick={() => setActiveTab('settings')}
            >
              <span className="btn-icon"><Settings size={18} /></span>
              <span className="btn-text">Settings</span>
            </button>
          </nav>
        </aside>

        {/* Main Content Area */}
        <main className="app-main">
          
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
              <section style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', marginTop: '1rem', width: '100%' }} aria-label="File Explorer">
                


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

                {/* Main Side-by-Side Real OS Explorer Interface */}
                <div style={{ display: 'flex', gap: '1.5rem', width: '100%', alignItems: 'stretch' }}>
                  
                  {/* Left Sidebar: Quick Access Places */}
                  <aside className="card" style={{ width: '240px', flexShrink: 0, padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
                    <h3 style={{ margin: 0, fontSize: '0.85rem', textTransform: 'uppercase', color: 'var(--text-muted)', fontWeight: 'bold', letterSpacing: '0.05em' }}>Places</h3>
                    <nav style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
                      <button 
                        className={`btn btn-sm ${mobilePath === '/sdcard' || mobilePath === '' ? 'btn-primary' : 'btn-secondary'}`} 
                        style={{ width: '100%', textAlign: 'left', borderRadius: '8px', padding: '0.6rem 1rem' }} 
                        onClick={() => fetchMobileFilesList('/sdcard')}
                      >
                        Internal Storage
                      </button>
                      <button 
                        className={`btn btn-sm ${mobilePath === '/sdcard/Download' ? 'btn-primary' : 'btn-secondary'}`} 
                        style={{ width: '100%', textAlign: 'left', borderRadius: '8px', padding: '0.6rem 1rem' }} 
                        onClick={() => fetchMobileFilesList('/sdcard/Download')}
                      >
                        Downloads
                      </button>
                      <button 
                        className={`btn btn-sm ${mobilePath === '/sdcard/DCIM' ? 'btn-primary' : 'btn-secondary'}`} 
                        style={{ width: '100%', textAlign: 'left', borderRadius: '8px', padding: '0.6rem 1rem' }} 
                        onClick={() => fetchMobileFilesList('/sdcard/DCIM')}
                      >
                        Camera / DCIM
                      </button>
                      <button 
                        className={`btn btn-sm ${mobilePath === '/sdcard/Pictures' ? 'btn-primary' : 'btn-secondary'}`} 
                        style={{ width: '100%', textAlign: 'left', borderRadius: '8px', padding: '0.6rem 1rem' }} 
                        onClick={() => fetchMobileFilesList('/sdcard/Pictures')}
                      >
                        Pictures
                      </button>
                      <button 
                        className={`btn btn-sm ${mobilePath === '/sdcard/Documents' ? 'btn-primary' : 'btn-secondary'}`} 
                        style={{ width: '100%', textAlign: 'left', borderRadius: '8px', padding: '0.6rem 1rem' }} 
                        onClick={() => fetchMobileFilesList('/sdcard/Documents')}
                      >
                        Documents
                      </button>
                      <button 
                        className={`btn btn-sm ${mobilePath === '/sdcard/Music' ? 'btn-primary' : 'btn-secondary'}`} 
                        style={{ width: '100%', textAlign: 'left', borderRadius: '8px', padding: '0.6rem 1rem' }} 
                        onClick={() => fetchMobileFilesList('/sdcard/Music')}
                      >
                        Music
                      </button>
                    </nav>
                  </aside>

                  {/* Right Explorer Content Panel */}
                  <article className="card" style={{ flexGrow: 1, display: 'flex', flexDirection: 'column', minHeight: '620px', padding: '2rem' }}>
                    
                    {/* Header: Title and Service Indicator */}
                    <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.25rem' }}>
                      <h2 style={{ margin: 0, fontSize: '1.3rem', fontWeight: 'bold' }}>Mobile Device Files</h2>
                      {status?.paired_devices.some(d => d.is_online) ? (
                        <span className="status-badge online" style={{ fontSize: '0.8rem' }}>Server Active</span>
                      ) : (
                        <span className="status-badge offline" style={{ fontSize: '0.8rem' }}>Offline</span>
                      )}
                    </header>

                    {status?.paired_devices.some(d => d.is_online) ? (
                      <>
                        {/* Toolbar: Navigation Address and Action Buttons */}
                        <menu style={{ display: 'flex', flexDirection: 'column', gap: '1rem', marginBottom: '1.25rem', padding: 0, margin: '0 0 1.5rem 0' }}>
                          
                          {/* Search, Layout toggles and Upload */}
                          <div style={{ display: 'flex', gap: '0.75rem', width: '100%', flexWrap: 'wrap', alignItems: 'center' }}>
                            
                            <input 
                              type="text" 
                              placeholder="Search current directory..." 
                              value={mobileSearch}
                              onChange={(e) => setMobileSearch(e.target.value)}
                              style={{ flexGrow: 1, minWidth: '220px', padding: '0.55rem 1rem', borderRadius: '8px', border: '1px solid var(--border-color)', background: 'var(--bg-primary)', color: 'var(--text-main)', fontSize: '0.85rem' }}
                            />

                            {/* Layout View Toggles */}
                            <div style={{ display: 'flex', gap: '0.25rem', background: 'var(--bg-secondary)', padding: '4px', borderRadius: '8px', border: '1px solid var(--border-color)' }}>
                              <button 
                                className={`btn btn-sm ${viewMode === 'list' ? 'btn-primary' : 'btn-secondary'}`} 
                                style={{ padding: '0.35rem 0.75rem', borderRadius: '6px', fontSize: '0.8rem', border: 'none' }}
                                onClick={() => setViewMode('list')}
                              >
                                List
                              </button>
                              <button 
                                className={`btn btn-sm ${viewMode === 'grid' ? 'btn-primary' : 'btn-secondary'}`} 
                                style={{ padding: '0.35rem 0.75rem', borderRadius: '6px', fontSize: '0.8rem', border: 'none' }}
                                onClick={() => setViewMode('grid')}
                              >
                                Grid
                              </button>
                              <button 
                                className={`btn btn-sm ${viewMode === 'compact' ? 'btn-primary' : 'btn-secondary'}`} 
                                style={{ padding: '0.35rem 0.75rem', borderRadius: '6px', fontSize: '0.8rem', border: 'none' }}
                                onClick={() => setViewMode('compact')}
                              >
                                Compact
                              </button>
                            </div>

                            {/* File picker Upload */}
                            <label className="btn btn-primary btn-sm" style={{ cursor: 'pointer', margin: 0, display: 'inline-flex', alignItems: 'center', height: '34px', padding: '0 1rem', borderRadius: '8px', fontSize: '0.8rem' }}>
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

                          {/* Path address and Sort params */}
                          <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', width: '100%', flexWrap: 'wrap', justifyContent: 'space-between' }}>
                            <nav style={{ display: 'flex', gap: '0.4rem', flexGrow: 1, alignItems: 'center', overflow: 'hidden' }}>
                              <button 
                                className="btn btn-secondary btn-sm"
                                style={{ height: '34px', padding: '0 0.85rem', borderRadius: '6px', fontSize: '0.8rem' }}
                                onClick={() => {
                                  if (mobilePath.includes('/')) {
                                    const parent = mobilePath.substring(0, mobilePath.lastIndexOf('/'));
                                    fetchMobileFilesList(parent || '/sdcard');
                                  }
                                }}
                              >
                                Up
                              </button>
                              <code style={{ flexGrow: 1, padding: '0.45rem 0.75rem', background: 'var(--bg-primary)', borderRadius: '6px', fontSize: '0.85rem', overflowX: 'auto', whiteSpace: 'nowrap', border: '1px solid var(--border-color)', display: 'block', fontFamily: 'monospace' }}>
                                {mobilePath || '/sdcard'}
                              </code>
                            </nav>

                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexShrink: 0 }}>
                              <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Sort by:</span>
                              <select 
                                value={mobileSort} 
                                onChange={(e) => setMobileSort(e.target.value as any)}
                                style={{ padding: '0.35rem 0.50rem', borderRadius: '6px', fontSize: '0.8rem', border: '1px solid var(--border-color)', background: 'var(--bg-primary)', color: 'var(--text-main)', height: '34px' }}
                              >
                                <option value="name">Name</option>
                                <option value="size">Size</option>
                                <option value="date">Date Modified</option>
                              </select>
                              <button 
                                className="btn btn-secondary btn-sm"
                                style={{ padding: '0.35rem 0.75rem', borderRadius: '6px', fontSize: '0.8rem', height: '34px' }}
                                onClick={() => setMobileSortOrder(p => p === 'asc' ? 'desc' : 'asc')}
                              >
                                {mobileSortOrder === 'asc' ? 'Asc' : 'Desc'}
                              </button>
                              <label style={{ display: 'flex', alignItems: 'center', gap: '0.35rem', fontSize: '0.8rem', cursor: 'pointer', userSelect: 'none', marginLeft: '0.5rem' }}>
                                <input 
                                  type="checkbox" 
                                  checked={hideHiddenFiles} 
                                  onChange={(e) => setHideHiddenFiles(e.target.checked)} 
                                  style={{ cursor: 'pointer', margin: 0 }}
                                />
                                Hide Hidden
                              </label>
                            </div>
                          </div>

                        </menu>

                        {/* File Pane Content wrapper */}
                        {loadingMobile ? (
                          <div style={{ flexGrow: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)', fontSize: '0.95rem' }}>Loading phone storage...</div>
                        ) : (
                          <div style={{ flexGrow: 1, minHeight: '380px', border: '1px solid var(--border-color)', borderRadius: '12px', background: 'var(--bg-primary)', overflow: 'hidden' }}>
                            {getProcessedFiles(mobileFiles, mobileSearch, mobileSort, mobileSortOrder).length === 0 ? (
                              <div style={{ padding: '4rem 2rem', textAlign: 'center', color: 'var(--text-muted)' }}>No matching files found.</div>
                            ) : (
                              
                              /* RENDERING VIEW: LIST MODE */
                              viewMode === 'list' && (
                                <ul style={{ listStyleType: 'none', padding: 0, margin: 0, display: 'flex', flexDirection: 'column', maxHeight: '420px', overflowY: 'auto' }}>
                                  {getProcessedFiles(mobileFiles, mobileSearch, mobileSort, mobileSortOrder).map((file: any) => (
                                    <li 
                                      key={file.path} 
                                      style={{ 
                                        display: 'flex', 
                                        alignItems: 'center', 
                                        padding: '0.7rem 1rem', 
                                        borderBottom: '1px solid var(--border-color)',
                                        transition: 'background 0.2s',
                                        cursor: 'pointer'
                                      }}
                                      className="file-item-hover"
                                      onClick={() => handleFileClick(file)}
                                    >
                                      <span 
                                        style={{ 
                                          padding: '0.2rem 0.5rem', 
                                          fontSize: '0.7rem', 
                                          textTransform: 'uppercase', 
                                          marginRight: '0.85rem', 
                                          background: file.is_dir ? 'var(--accent)' : 'var(--bg-secondary)', 
                                          color: file.is_dir ? 'white' : 'var(--text-main)', 
                                          border: '1px solid var(--border-color)',
                                          fontWeight: 'bold',
                                          borderRadius: '4px'
                                        }}
                                      >
                                        {file.is_dir ? 'Folder' : 'File'}
                                      </span>
                                      <div style={{ flexGrow: 1, minWidth: 0 }}>
                                        <div style={{ fontWeight: 500, fontSize: '0.85rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{file.name}</div>
                                        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                                          {file.is_dir ? 'Folder' : `${(file.size / 1024).toFixed(1)} KB`}
                                        </div>
                                      </div>
                                      <div style={{ display: 'flex', gap: '0.35rem' }} onClick={(e) => e.stopPropagation()}>
                                        {!file.is_dir && (
                                          <button 
                                            className="btn btn-primary btn-sm" 
                                            style={{ padding: '0.25rem 0.5rem', fontSize: '0.75rem' }}
                                            onClick={() => downloadMobileFile(file)}
                                          >
                                            Download
                                          </button>
                                        )}
                                      </div>
                                    </li>
                                  ))}
                                </ul>
                              )

                              /* RENDERING VIEW: GRID MODE */
                              || viewMode === 'grid' && (
                                <ul style={{ 
                                  listStyleType: 'none', 
                                  padding: '1.25rem', 
                                  margin: 0, 
                                  display: 'grid', 
                                  gridTemplateColumns: 'repeat(auto-fill, minmax(130px, 1fr))', 
                                  gap: '1.25rem',
                                  maxHeight: '420px',
                                  overflowY: 'auto'
                                }}>
                                  {getProcessedFiles(mobileFiles, mobileSearch, mobileSort, mobileSortOrder).map((file: any) => {
                                    const ext = file.name.split('.').pop()?.toLowerCase() || '';
                                    const phoneIp = status?.paired_devices.filter(d => d.is_online)[0]?.ip;
                                    const fileUrl = `http://${phoneIp}:9090/download?path=${encodeURIComponent(file.path)}`;
                                    const isImage = ['png', 'jpg', 'jpeg', 'webp', 'gif', 'svg'].includes(ext);

                                    return (
                                      <li 
                                        key={file.path} 
                                        style={{ 
                                          display: 'flex', 
                                          flexDirection: 'column', 
                                          padding: '0.75rem', 
                                          borderRadius: '8px',
                                          border: '1px solid var(--border-color)',
                                          background: 'var(--bg-secondary)',
                                          cursor: 'pointer',
                                          transition: 'transform 0.2s, border-color 0.2s',
                                          textAlign: 'center',
                                          alignItems: 'center'
                                        }}
                                        className="file-item-hover"
                                        onClick={() => handleFileClick(file)}
                                      >
                                        {file.is_dir ? (
                                          <div style={{ height: '70px', width: '100%', background: 'var(--accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 'bold', fontSize: '0.8rem', borderRadius: '4px', border: '1px solid var(--border-color)', color: 'white' }}>
                                            FOLDER
                                          </div>
                                        ) : isImage && phoneIp ? (
                                          <img 
                                            src={fileUrl} 
                                            alt={file.name} 
                                            loading="lazy"
                                            style={{ width: '100%', height: '70px', objectFit: 'cover', borderRadius: '4px', border: '1px solid var(--border-color)', marginBottom: '0.4rem' }} 
                                          />
                                        ) : (
                                          <div style={{ height: '70px', width: '100%', background: 'var(--bg-primary)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 'bold', fontSize: '0.8rem', borderRadius: '4px', border: '1px solid var(--border-color)', marginBottom: '0.4rem', color: 'var(--text-muted)' }}>
                                            {ext.toUpperCase()}
                                          </div>
                                        )}
                                        <div style={{ fontSize: '0.8rem', fontWeight: '500', width: '100%', textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap', marginTop: '0.25rem' }}>
                                          {file.name}
                                        </div>
                                        <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>
                                          {file.is_dir ? 'Folder' : `${(file.size / 1024).toFixed(0)} KB`}
                                        </div>
                                        {!file.is_dir && (
                                          <button 
                                            className="btn btn-primary btn-sm" 
                                            style={{ padding: '0.15rem 0.35rem', fontSize: '0.65rem', marginTop: '0.35rem', width: '100%' }}
                                            onClick={(e) => { e.stopPropagation(); downloadMobileFile(file); }}
                                          >
                                            Download
                                          </button>
                                        )}
                                      </li>
                                    );
                                  })}
                                </ul>
                              )

                              /* RENDERING VIEW: COMPACT MODE */
                              || viewMode === 'compact' && (
                                <ul style={{ 
                                  listStyleType: 'none', 
                                  padding: '0.85rem', 
                                  margin: 0, 
                                  display: 'grid', 
                                  gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', 
                                  gap: '0.5rem',
                                  maxHeight: '420px',
                                  overflowY: 'auto'
                                }}>
                                  {getProcessedFiles(mobileFiles, mobileSearch, mobileSort, mobileSortOrder).map((file: any) => (
                                    <li 
                                      key={file.path} 
                                      style={{ 
                                        display: 'flex', 
                                        alignItems: 'center', 
                                        padding: '0.45rem 0.6rem', 
                                        borderRadius: '6px',
                                        border: '1px solid var(--border-color)',
                                        background: 'var(--bg-secondary)',
                                        cursor: 'pointer',
                                        transition: 'background 0.2s',
                                        gap: '0.4rem'
                                      }}
                                      className="file-item-hover"
                                      onClick={() => handleFileClick(file)}
                                    >
                                      <span style={{ 
                                        padding: '0.15rem 0.35rem', 
                                        fontSize: '0.65rem', 
                                        background: file.is_dir ? 'var(--accent)' : 'var(--bg-secondary)', 
                                        color: file.is_dir ? 'white' : 'var(--text-main)', 
                                        borderRadius: '3px',
                                        border: '1px solid var(--border-color)',
                                        fontWeight: 'bold',
                                        textTransform: 'uppercase'
                                      }}>
                                        {file.is_dir ? 'Dir' : 'File'}
                                      </span>
                                      <span style={{ fontSize: '0.75rem', fontWeight: '500', flexGrow: 1, textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap' }}>
                                        {file.name}
                                      </span>
                                      {!file.is_dir && (
                                        <button 
                                          className="btn btn-primary btn-sm" 
                                          style={{ padding: '0.15rem 0.35rem', fontSize: '0.65rem', flexShrink: 0 }}
                                          onClick={(e) => { e.stopPropagation(); downloadMobileFile(file); }}
                                        >
                                          Download
                                        </button>
                                      )}
                                    </li>
                                  ))}
                                </ul>
                              )

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

                </div>
              </section>
            )}

            {activeTab === 'speaker' && (
              <section style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', marginTop: '1rem', width: '100%' }} aria-label="Audio & Sound Sync">
                <header>
                  <h2 style={{ margin: 0 }}>Audio & Sound Sync</h2>
                  <p style={{ color: 'var(--text-muted)', margin: '0.25rem 0 0 0', fontSize: '0.9rem' }}>
                    Configure and control dynamic audio routing, playback redirection, and phone call synchronization.
                  </p>
                </header>

                {/* 1. Overall Master ON/OFF Card */}
                <div className="card" style={{ padding: '1.5rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <h3 style={{ margin: 0, fontSize: '1.1rem' }}>Overall Audio Sync Master Control</h3>
                    <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                      Enable or disable all system audio streaming and call gateway routing features completely.
                    </p>
                  </div>
                  <button
                    role="switch"
                    aria-checked={audioSyncEnabled}
                    onClick={() => {
                      const newEnabled = !audioSyncEnabled;
                      setAudioSyncEnabled(newEnabled);
                      if (!newEnabled && wifiSpeakerActive) {
                        updateAudioConfig({ wifi_speaker_active: false });
                      }
                    }}
                    className={`m3-switch ${audioSyncEnabled ? 'checked' : ''}`}
                  >
                    <span className="m3-switch-thumb"></span>
                  </button>
                </div>

                {audioSyncEnabled ? (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
                    
                    {/* 2. Direction Selection Card */}
                    <div className="card" style={{ padding: '1.5rem' }}>
                      <h3 style={{ marginTop: 0, marginBottom: '1rem', fontSize: '1.1rem' }}>Select Audio Flow Direction</h3>
                      
                      <div style={{ display: 'flex', gap: '2rem' }}>
                        <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontWeight: '500' }}>
                          <input 
                            type="radio" 
                            name="audio_direction" 
                            checked={audioDirection === 'desktop_to_mobile'} 
                            onChange={() => updateAudioConfig({ audio_direction: 'desktop_to_mobile' })}
                            style={{ accentColor: 'var(--accent)' }}
                          />
                          Desktop to Mobile (Use Mobile Phone as Laptop Speaker)
                        </label>
                        
                        <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontWeight: '500' }}>
                          <input 
                            type="radio" 
                            name="audio_direction" 
                            checked={audioDirection === 'mobile_to_desktop'} 
                            onChange={() => updateAudioConfig({ audio_direction: 'mobile_to_desktop', wifi_speaker_active: false })}
                            style={{ accentColor: 'var(--accent)' }}
                          />
                          Mobile to Desktop (Use Laptop as Mobile Phone Speaker)
                        </label>
                      </div>
                    </div>

                    {/* 3. Direction-specific options and controls */}
                    {audioDirection === 'desktop_to_mobile' ? (
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '1.5rem' }}>
                        
                        {/* Desktop to Mobile Options Card */}
                        <div className="card" style={{ padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                          <h3 style={{ margin: 0, fontSize: '1.1rem' }}>Playback Options</h3>
                          
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginTop: '0.5rem' }}>
                            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.9rem' }}>
                              <input 
                                type="radio" 
                                name="desktop_playback_mode" 
                                checked={desktopToMobilePlaybackMode === 'destination_only'} 
                                onChange={() => updateAudioConfig({ playback_mode: 'destination_only' })}
                                style={{ accentColor: 'var(--accent)' }}
                              />
                              Play on Destination Device Only (Mute laptop speakers)
                            </label>
                            
                            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.9rem' }}>
                              <input 
                                type="radio" 
                                name="desktop_playback_mode" 
                                checked={desktopToMobilePlaybackMode === 'both'} 
                                onChange={() => updateAudioConfig({ playback_mode: 'both' })}
                                style={{ accentColor: 'var(--accent)' }}
                              />
                              Play on Both Devices (Laptop & mobile speakers simultaneously)
                            </label>
                          </div>

                          <div style={{ marginTop: '1.5rem', display: 'flex', justifyContent: 'center' }}>
                            {status?.paired_devices.some(d => d.is_online) ? (
                              <button 
                                className={`m3-btn ${wifiSpeakerActive ? 'm3-btn-outlined' : 'm3-btn-filled'}`}
                                onClick={toggleWifiSpeaker}
                                disabled={startingSpeaker}
                                style={{ padding: '0.75rem 2.5rem', fontSize: '1rem', minWidth: '180px', borderRadius: '50px' }}
                              >
                                {startingSpeaker ? 'Connecting...' : wifiSpeakerActive ? 'Stop Sync' : 'Start Sync'}
                              </button>
                            ) : (
                              <div style={{ fontSize: '0.9rem', color: 'var(--danger)', fontStyle: 'italic', textAlign: 'center' }}>
                                Connect your mobile device over Wi-Fi to start streaming.
                              </div>
                            )}
                          </div>
                        </div>

                        {/* Stream telemetry card */}
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                          <div className="card" style={{ padding: '1.5rem' }}>
                            <h3 style={{ marginTop: 0, marginBottom: '1rem', fontSize: '1.1rem' }}>Stream Details & Status</h3>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                              <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.4rem' }}>
                                <span style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Status</span>
                                <span style={{ fontWeight: '600', fontSize: '0.9rem', color: wifiSpeakerActive ? 'var(--success)' : 'var(--text-muted)' }}>
                                  {wifiSpeakerActive ? 'Streaming Active' : 'Ready'}
                                </span>
                              </div>
                              <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.4rem' }}>
                                <span style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Latency Profile</span>
                                <span style={{ fontWeight: '500', fontSize: '0.9rem', color: 'var(--success)' }}>&lt; 5 ms (Low Latency)</span>
                              </div>
                              <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.4rem' }}>
                                <span style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Target Device</span>
                                <span style={{ fontWeight: '500', fontSize: '0.9rem' }}>{status?.paired_devices.filter(d => d.is_online)[0]?.device_name || 'None'}</span>
                              </div>
                              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                                <span style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Format</span>
                                <span style={{ fontWeight: '500', fontSize: '0.9rem' }}>Stereo 48.0 kHz 16-bit PCM</span>
                              </div>
                            </div>
                          </div>
                        </div>

                      </div>
                    ) : (
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: '1.5rem' }}>
                        
                        {/* Mobile to Desktop Options Card */}
                        <div className="card" style={{ padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                          <h3 style={{ margin: 0, fontSize: '1.1rem' }}>Playback Options</h3>
                          
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginTop: '0.5rem' }}>
                            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.9rem' }}>
                              <input 
                                type="radio" 
                                name="mobile_playback_mode" 
                                checked={mobileToDesktopPlaybackMode === 'destination_only'} 
                                onChange={() => setMobileToDesktopPlaybackMode('destination_only')}
                                style={{ accentColor: 'var(--accent)' }}
                              />
                              Play on Destination Device Only (Laptop speakers only)
                            </label>
                            
                            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer', fontSize: '0.9rem', opacity: 0.7 }}>
                              <input 
                                type="radio" 
                                name="mobile_playback_mode" 
                                checked={mobileToDesktopPlaybackMode === 'both'} 
                                onChange={() => {
                                  alert("Note: Routing calls to both phone and laptop simultaneously is not supported by Android OS to prevent feedback/echo loops. Media will play on laptop speakers.");
                                  setMobileToDesktopPlaybackMode('both');
                                }}
                                style={{ accentColor: 'var(--accent)' }}
                              />
                              Play on Both Devices (Mobile phone & laptop speakers)
                            </label>
                          </div>
                        </div>

                        {/* Bluetooth Gateway Status Card */}
                        <div className="card" style={{ padding: '1.5rem' }}>
                          <h3 style={{ marginTop: 0, marginBottom: '1rem', fontSize: '1.1rem' }}>Bluetooth Call Routing Gateway</h3>
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.4rem' }}>
                              <span style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Gateway Connection</span>
                              <span style={{ 
                                fontWeight: '600', 
                                fontSize: '0.9rem', 
                                color: status?.paired_devices[0]?.is_bluetooth_connected ? 'var(--success)' : 'var(--danger)' 
                              }}>
                                {status?.paired_devices[0]?.is_bluetooth_connected ? 'Connected' : 'Disconnected'}
                              </span>
                            </div>

                            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid var(--border-color)', paddingBottom: '0.4rem' }}>
                              <span style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Mobile Device</span>
                              <span style={{ fontWeight: '500', fontSize: '0.9rem' }}>{status?.paired_devices[0]?.device_name || 'None'}</span>
                            </div>
                            
                            <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: '0.5rem', lineHeight: '1.4' }}>
                              {status?.paired_devices[0]?.is_bluetooth_connected ? (
                                <span>Phone call audio is actively synchronized. It will route to your laptop speakers and microphone when active.</span>
                              ) : (
                                <span>Gateway is offline. Ensure Bluetooth is enabled on your phone and both devices are paired.</span>
                              )}
                            </div>
                          </div>
                        </div>

                      </div>
                    )}

                  </div>
                ) : (
                  <div className="card" style={{ padding: '3rem 2rem', textAlign: 'center', backgroundColor: 'var(--bg-secondary)', borderColor: 'var(--border-color)' }}>
                    <VolumeX size={48} style={{ color: 'var(--text-muted)', marginBottom: '1rem' }} />
                    <h3 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--text-muted)' }}>Audio Synchronization Disabled</h3>
                    <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                      All audio sharing features are disabled. Switch on the Master Toggle above to configure stream flows.
                    </p>
                  </div>
                )}
              </section>
            )}

            {activeTab === 'settings' && (
              <>
                {/* System Preferences & Status Card */}
                <div className="card" style={{ marginBottom: '1.5rem' }}>
                  <h2>System Preferences & Status</h2>
                  <div style={{ display: 'flex', gap: '2.5rem', alignItems: 'center', flexWrap: 'wrap', marginTop: '1rem' }}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                      <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)', fontWeight: 'bold' }}>Application Theme</span>
                      <button 
                        className="m3-btn m3-btn-outlined"
                        onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
                        style={{ alignSelf: 'flex-start' }}
                      >
                        {theme === 'light' ? 'Dark Theme' : 'Light Theme'}
                      </button>
                    </div>

                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                      <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)', fontWeight: 'bold' }}>Theme Color Palette</span>
                      <div style={{ display: 'flex', gap: '0.75rem', marginTop: '0.25rem' }}>
                        {[
                          { id: 'purple', name: 'Violent Violet', color: '#6750a4' },
                          { id: 'blue', name: 'Depressed Denim', color: '#0f52ba' },
                          { id: 'green', name: 'Grumpy Guacamole', color: '#386a20' },
                          { id: 'red', name: 'Spicy Salsa', color: '#ba1a1a' }
                        ].map((pal) => (
                          <button
                            key={pal.id}
                            onClick={() => {
                              setPalette(pal.id);
                              localStorage.setItem('theme-palette', pal.id);
                            }}
                            className="m3-btn"
                            style={{
                              borderColor: palette === pal.id ? pal.color : 'var(--border-color)',
                              borderStyle: 'solid',
                              borderWidth: palette === pal.id ? '2px' : '1px',
                              color: pal.color,
                              backgroundColor: palette === pal.id ? `${pal.color}18` : 'transparent',
                              padding: '0 16px',
                              height: '32px',
                              fontSize: '0.85rem'
                            }}
                          >
                            {pal.name}
                          </button>
                        ))}
                      </div>
                    </div>
                    
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                      <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)', fontWeight: 'bold' }}>Service Status</span>
                      <div className={`status-badge ${isDaemonOnline ? 'online' : 'offline'}`} style={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center' }}>
                        <span style={{ 
                          width: '8px', 
                          height: '8px', 
                          borderRadius: '50%', 
                          background: isDaemonOnline ? 'var(--success)' : 'var(--danger)',
                          display: 'inline-block',
                          marginRight: '0.4rem'
                        }}></span>
                        {isDaemonOnline ? 'Daemon Online' : 'Daemon Offline'}
                      </div>
                    </div>
                  </div>
                </div>

                <div className="card">
                  <h2>Clipboard Synchronization</h2>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem', marginTop: '1rem' }}>
                    
                    {/* Master Auto Sync Toggle */}
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', width: '100%', gap: '1rem' }}>
                      <span style={{ fontSize: '1rem', fontWeight: 'bold' }}>
                        Enable Real-Time Clipboard Synchronization
                      </span>
                      <button
                        role="switch"
                        aria-checked={clipAutoSync}
                        onClick={() => {
                          const checked = !clipAutoSync;
                          setClipAutoSync(checked);
                          saveClipboardConfig(clipDirection, checked);
                        }}
                        className={`m3-switch ${clipAutoSync ? 'checked' : ''}`}
                      >
                        <span className="m3-switch-thumb"></span>
                      </button>
                    </div>

                    {/* Sync Direction Configuration (Disabled if master is off) */}
                    <div style={{ 
                      display: 'flex', 
                      flexDirection: 'column', 
                      gap: '0.75rem', 
                      marginTop: '0.5rem',
                      opacity: clipAutoSync ? 1 : 0.4,
                      pointerEvents: clipAutoSync ? 'auto' : 'none',
                      transition: 'opacity 0.2s'
                    }}>
                      <span style={{ fontSize: '0.9rem', color: 'var(--text-muted)', fontWeight: 'bold' }}>Sync Direction Flow</span>
                      
                      {/* Segmented Button Selection */}
                      <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', marginTop: '0.5rem' }}>
                        {clipDirection === 'bidirectional' ? (
                          <button className="m3-btn m3-btn-filled" onClick={() => { if (clipAutoSync) { setClipDirection('bidirectional'); saveClipboardConfig('bidirectional', clipAutoSync); } }}>
                            Bidirectional
                          </button>
                        ) : (
                          <button className="m3-btn m3-btn-outlined" disabled={!clipAutoSync} onClick={() => { if (clipAutoSync) { setClipDirection('bidirectional'); saveClipboardConfig('bidirectional', clipAutoSync); } }}>
                            Bidirectional
                          </button>
                        )}
                        {clipDirection === 'desktop_to_mobile' ? (
                          <button className="m3-btn m3-btn-filled" onClick={() => { if (clipAutoSync) { setClipDirection('desktop_to_mobile'); saveClipboardConfig('desktop_to_mobile', clipAutoSync); } }}>
                            Desktop to Mobile
                          </button>
                        ) : (
                          <button className="m3-btn m3-btn-outlined" disabled={!clipAutoSync} onClick={() => { if (clipAutoSync) { setClipDirection('desktop_to_mobile'); saveClipboardConfig('desktop_to_mobile', clipAutoSync); } }}>
                            Desktop to Mobile
                          </button>
                        )}
                        {clipDirection === 'mobile_to_desktop' ? (
                          <button className="m3-btn m3-btn-filled" onClick={() => { if (clipAutoSync) { setClipDirection('mobile_to_desktop'); saveClipboardConfig('mobile_to_desktop', clipAutoSync); } }}>
                            Mobile to Desktop
                          </button>
                        ) : (
                          <button className="m3-btn m3-btn-outlined" disabled={!clipAutoSync} onClick={() => { if (clipAutoSync) { setClipDirection('mobile_to_desktop'); saveClipboardConfig('mobile_to_desktop', clipAutoSync); } }}>
                            Mobile to Desktop
                          </button>
                        )}
                      </div>
                      
                      <p style={{ margin: 0, fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                        {clipDirection === 'bidirectional' && "Copies on either device will automatically sync to the other."}
                        {clipDirection === 'desktop_to_mobile' && "Copies on this PC are sent to your mobile device, but mobile copies are ignored."}
                        {clipDirection === 'mobile_to_desktop' && "Copies on your mobile device are pulled to this PC, but PC copies are ignored."}
                      </p>
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

        </main>

        {/* Floating Toast Notification */}
        <div id="toast" className="toast-container">
          Copied to clipboard!
        </div>

      </div>
    </div>
  );
}
