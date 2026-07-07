package com.platypus.platypusd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    private val CHANNEL_ID = "platypusd_sync"
    private val client = OkHttpClient.Builder()
        .pingInterval(4, TimeUnit.SECONDS) // Dynamic ping to detect cable disconnects instantly
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for WebSockets
        .build()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var daemonHost = "10.0.2.2"
    private var daemonPort = 8080
    private var daemonUrl = "http://10.0.2.2:8080"
    private var wsUrl = "ws://10.0.2.2:8080/api/v1/events"

    private var activeWebSocket: WebSocket? = null
    private var isWsConnected = false
    private var failedConnectionsCount = 0
    private var connectionType = "Local Network"
    
    private var fileServerJob: kotlinx.coroutines.Job? = null
    private var fileServerSocket: java.net.ServerSocket? = null

    private val connectedBluetoothDevices = HashSet<String>()
    
    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    connectedBluetoothDevices.add(it.address)
                    Log.d(TAG, "Bluetooth ACL Connected: ${it.name} (${it.address})")
                    checkBluetoothConnectionToHost()
                    
                    val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                    val selectedMac = sharedPrefs.getString("selected_bluetooth_mac", null)
                    if (selectedMac != null && it.address == selectedMac) {
                        Log.i(TAG, "Bluetooth connection to host PC detected. Bootstrapping connection...")
                        bootstrapConnectionViaBluetooth()
                    }
                }
            } else if (android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
                val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    connectedBluetoothDevices.remove(it.address)
                    Log.d(TAG, "Bluetooth ACL Disconnected: ${it.name} (${it.address})")
                    checkBluetoothConnectionToHost()
                }
            } else if (android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.ERROR)
                if (state == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                    connectedBluetoothDevices.clear()
                    checkBluetoothConnectionToHost()
                }
            }
        }
    }

    private lateinit var clipboardManager: ClipboardManager
    var lastSyncedClipboardText = ""

    private val nsdManager by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private lateinit var deviceId: String
    private lateinit var deviceName: String
    private val publicKey = "android-device-key-sig"

    val isConnected: Boolean
        get() = isWsConnected

    val connectedHost: String
        get() = daemonHost

    fun disconnect() {
        activeWebSocket?.close(1000, "User requested disconnect")
        isWsConnected = false
        // Update status notification
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, getNotification("Disconnected from daemon"))
    }

    companion object {
        private const val TAG = "ConnectionService"
        var instance: ConnectionService? = null

        fun updateCallState(callId: String, number: String, contactName: String, state: String) {
            instance?.relayCallState(callId, number, contactName, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        
        // Cache device identity
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        var cachedId = sharedPrefs.getString("device_id", null)
        if (cachedId == null) {
            cachedId = java.util.UUID.randomUUID().toString()
            sharedPrefs.edit().putString("device_id", cachedId).apply()
        }
        deviceId = cachedId
        deviceName = android.os.Build.MODEL ?: "Android Phone"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                getNotification("Searching for platypusd daemon..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, getNotification("Searching for platypusd daemon..."))
        }
        
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        setupClipboardListener()

        startMdnsDiscovery()
        bootstrapConnectionViaBluetooth()

        // Register dynamic bluetooth connection tracking
        val filter = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(bluetoothReceiver, filter)

        Log.i(TAG, "ConnectionService initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val customIp = intent?.getStringExtra("DAEMON_IP")
        if (customIp != null) {
            updateDaemonUrl(customIp, 8080)
        }
        val checkBt = intent?.getBooleanExtra("CHECK_BT", false) ?: false
        if (checkBt) {
            isBluetoothConnectedCached = null // force update
            checkBluetoothConnectionToHost()
        }
        val triggerBootstrap = intent?.getBooleanExtra("TRIGGER_BOOTSTRAP", false) ?: false
        if (triggerBootstrap) {
            bootstrapConnectionViaBluetooth()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering bluetooth receiver: ${e.message}")
        }
        stopLocalHttpServer()
        super.onDestroy()
        stopMdnsDiscovery()
        activeWebSocket?.close(1000, "Service destroyed")
        instance = null
        Log.i(TAG, "ConnectionService destroyed.")
    }

    private fun updateDaemonUrl(host: String, port: Int) {
        if (daemonHost == host && daemonPort == port) return
        daemonHost = host
        daemonPort = port
        daemonUrl = "http://$host:$port"
        wsUrl = "ws://$host:$port/api/v1/events"
        
        Log.i(TAG, "Updating daemon target server to: $daemonUrl")
        
        // Update notification content
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1, getNotification("Connected to daemon at $host"))

        // Trigger pairing registration in daemon database
        sendPairingRequest()

        // Reconnect WebSocket with new URL
        activeWebSocket?.close(1000, "Reconnecting to new host")
        connectWebSocket()
    }

    private fun sendPairingRequest() {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_name", deviceName)
                    put("public_key", publicKey)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$daemonUrl/api/v1/pairing/request")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Successfully registered pairing request to daemon.")
                    } else {
                        Log.e(TAG, "Daemon pairing request failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing error: ${e.message}")
            }
        }
    }

    /* ---------------- mDNS Discovery ---------------- */
    private fun startMdnsDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Discovery failed to stop: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "mDNS Discovery started for: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS Discovery stopped.")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("_platypusd")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(resolvedInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "mDNS Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            Log.i(TAG, "mDNS Resolved daemon endpoint: ${resolvedInfo.host.hostAddress}:${resolvedInfo.port}")
                            scope.launch(Dispatchers.Main) {
                                val resolvedHost = resolvedInfo.host.hostAddress
                                if (resolvedHost != null) {
                                    updateDaemonUrl(resolvedHost, resolvedInfo.port)
                                }
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.w(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
            }
        }
        
        try {
            nsdManager.discoverServices(
                "_platypusd._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register discoverServices: ${e.message}")
        }
    }

    private fun stopMdnsDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery: ${e.message}")
            }
        }
    }

    /* ---------------- WebSockets & Events ---------------- */
    private fun connectWebSocket() {
        if (isWsConnected) return

        val urlWithParams = if (wsUrl.contains("?")) {
            wsUrl
        } else {
            val encodedName = java.net.URLEncoder.encode(deviceName, "UTF-8")
            val encodedType = java.net.URLEncoder.encode(connectionType, "UTF-8")
            "$wsUrl?device_id=$deviceId&device_name=$encodedName&connection_type=$encodedType"
        }
        Log.i(TAG, "Connecting WebSocket to: $urlWithParams")
        val request = Request.Builder().url(urlWithParams).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
             override fun onOpen(webSocket: WebSocket, response: Response) {
                isWsConnected = true
                Log.i(TAG, "WebSocket event connection opened to: $wsUrl")
                isBluetoothConnectedCached = null // force update
                getClipboardConfigFromDaemon()
                scope.launch {
                    while (isWsConnected) {
                        checkBluetoothConnectionToHost()
                        kotlinx.coroutines.delay(3000)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")

                    if (event == "StartFileServer") {
                        Log.i(TAG, "Received command to START local HTTP file server.")
                        startLocalHttpServer()
                    } else if (event == "StopFileServer") {
                        Log.i(TAG, "Received command to STOP local HTTP file server.")
                        stopLocalHttpServer()
                    } else {
                        val data = json.optJSONObject("data") ?: return
                        if (event == "ClipboardSynced") {
                            val sharedText = data.optString("text")
                            if (sharedText.isNotEmpty() && sharedText != lastSyncedClipboardText) {
                                Log.i(TAG, "Received clipboard sync command. Updating Android clipboard.")
                                scope.launch(Dispatchers.Main) {
                                    syncToAndroidClipboard(sharedText)
                                }
                            }
                        } else if (event == "CallActionDispatched") {
                            val action = data.optString("action")
                            Log.i(TAG, "Received call action command: $action")
                            scope.launch(Dispatchers.Main) {
                                handleCallActionCommand(action)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket payload: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWsConnected = false
                Log.w(TAG, "WebSocket connection closed: $reason. Retrying...")
                failedConnectionsCount++
                scope.launch {
                    delayReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWsConnected = false
                Log.e(TAG, "WebSocket error: ${t.message}. Retrying...")
                failedConnectionsCount++
                scope.launch {
                    delayReconnect()
                }
            }
        })
    }

    private suspend fun delayReconnect() {
        if (failedConnectionsCount >= 2) {
            Log.i(TAG, "Reconnection failed multiple times. Attempting Bluetooth RFCOMM IP bootstrap...")
            bootstrapConnectionViaBluetooth()
            failedConnectionsCount = 0
        } else {
            kotlinx.coroutines.delay(4000)
            scope.launch(Dispatchers.Main) {
                connectWebSocket()
            }
        }
    }

    fun bootstrapConnectionViaBluetooth() {
        scope.launch(Dispatchers.IO) {
            var success = false
            try {
                Log.i(TAG, "RFCOMM: Starting Bluetooth bootstrapping...")
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null && adapter.isEnabled) {
                    val pairedDevices = adapter.bondedDevices
                    val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    
                    val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                    val selectedMac = sharedPrefs.getString("selected_bluetooth_mac", null)
                    
                    val targets = if (selectedMac != null) {
                        pairedDevices.filter { it.address == selectedMac }
                    } else {
                        pairedDevices ?: emptySet()
                    }

                    for (device in targets) {
                        Log.i(TAG, "RFCOMM: Connecting to: ${device.name} (${device.address})")
                        var socket: BluetoothSocket? = null
                        var connected = false
                        
                        // Attempt connection directly to channel 1, and fallback to channel 2
                        for (channel in arrayOf(1, 2)) {
                            try {
                                Log.i(TAG, "RFCOMM: Attempting raw channel $channel connection...")
                                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                                socket = method.invoke(device, channel) as BluetoothSocket
                                socket.connect()
                                Log.i(TAG, "RFCOMM: Connected successfully to channel $channel!")
                                connected = true
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "RFCOMM: Channel $channel connection failed: ${e.message}")
                                try { socket?.close() } catch (ex: Exception) {}
                                socket = null
                            }
                        }

                        if (connected && socket != null) {
                            try {
                                val outputStream = socket.outputStream
                                val inputStream = socket.inputStream
                                
                                outputStream.write("GET_DAEMON_IP".toByteArray())
                                outputStream.flush()
                                
                                val buffer = ByteArray(1024)
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead > 0) {
                                    val response = String(buffer, 0, bytesRead)
                                    Log.i(TAG, "RFCOMM: Received: $response")
                                    val json = JSONObject(response)
                                    
                                    val ipsArray = json.getJSONArray("ips")
                                    val candidateIps = mutableListOf<String>()
                                    val ipToIfaceName = mutableMapOf<String, String>()
                                    
                                    for (i in 0 until ipsArray.length()) {
                                        val obj = ipsArray.getJSONObject(i)
                                        val ip = obj.getString("ip")
                                        val ifaceName = obj.getString("name")
                                        candidateIps.add(ip)
                                        ipToIfaceName[ip] = ifaceName
                                    }
                                    val port = json.getInt("port")
                                    val id = json.getString("id")
                                    val name = json.getString("name")
                                    val pubkey = json.getString("pubkey")
                                    
                                    Log.i(TAG, "RFCOMM: Discovered IPs: $candidateIps. Probing in parallel...")
                                    
                                    // Parallel probe
                                    val winnerIp = withTimeoutOrNull(2500) {
                                        val deferredList = candidateIps.map { ip ->
                                            async {
                                                try {
                                                    val url = "http://$ip:$port/api/v1/status"
                                                    val request = Request.Builder().url(url).build()
                                                    client.newCall(request).execute().use { response ->
                                                        if (response.isSuccessful) {
                                                            Log.i(TAG, "Probing: IP $ip responded successfully.")
                                                            ip
                                                        } else null
                                                    }
                                                } catch (e: Exception) {
                                                    null
                                                }
                                            }
                                        }
                                        
                                        var resultIp: String? = null
                                        for (deferred in deferredList) {
                                            val ip = deferred.await()
                                            if (ip != null) {
                                                resultIp = ip
                                                deferredList.forEach { it.cancel() }
                                                break
                                            }
                                        }
                                        resultIp
                                    }
                                    
                                    if (winnerIp != null) {
                                        Log.i(TAG, "RFCOMM: Bootstrap Winner IP: $winnerIp")
                                        val winnerIface = ipToIfaceName[winnerIp] ?: "primary"
                                        connectionType = when {
                                            winnerIface.contains("usb") || winnerIface.contains("rndis") -> "USB Tethering"
                                            winnerIface.contains("wlan") || winnerIface.contains("wlp") || winnerIface.contains("wifi") -> "Wi-Fi"
                                            winnerIface.contains("ap") || winnerIface.contains("hotspot") -> "Mobile Hotspot"
                                            winnerIface.contains("eth") || winnerIface.contains("en") -> "Ethernet"
                                            else -> "Local Network"
                                        }
                                        Log.i(TAG, "Resolved connection type: $connectionType")
                                        
                                        sharedPrefs.edit()
                                            .putString("paired_host_ip", winnerIp)
                                            .putInt("paired_host_port", port)
                                            .putString("paired_device_id", id)
                                            .putString("paired_device_name", name)
                                            .putString("paired_device_pubkey", pubkey)
                                            .apply()
                                            
                                        scope.launch(Dispatchers.Main) {
                                            updateDaemonUrl(winnerIp, port)
                                        }
                                        success = true
                                    }
                                }
                                socket.close()
                                if (success) break
                            } catch (e: Exception) {
                                Log.w(TAG, "RFCOMM: Failed via ${device.name}: ${e.message}")
                            } finally {
                                try { socket?.close() } catch (ex: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RFCOMM: Error: ${e.message}")
            }
            
            if (!success) {
                Log.w(TAG, "RFCOMM: Bootstrapping did not find a reachable host. Attempting standard WebSocket connection using cached IP...")
                scope.launch(Dispatchers.Main) {
                    connectWebSocket()
                }
            }
        }
    }

    /* ---------------- Clipboard Operations ---------------- */
    private var lastIncomingSyncTime = 0L

    private fun setupClipboardListener() {
        clipboardManager.addPrimaryClipChangedListener {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val clipText = clipData.getItemAt(0).text?.toString() ?: ""
                val trimmedText = clipText.trim()
                val trimmedLast = lastSyncedClipboardText.trim()

                if (trimmedText.isNotEmpty() && trimmedText != trimmedLast) {
                    val timePassed = System.currentTimeMillis() - lastIncomingSyncTime
                    if (timePassed < 1500) {
                        Log.d(TAG, "Ignoring local clipboard change due to recent incoming sync ($timePassed ms ago)")
                        return@addPrimaryClipChangedListener
                    }

                    val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                    val direction = sharedPrefs.getString("clipboard_direction", "bidirectional")
                    val autoSync = sharedPrefs.getBoolean("clipboard_auto_sync", true)

                    if (autoSync && (direction == "bidirectional" || direction == "mobile_to_desktop")) {
                        Log.i(TAG, "Local phone copy event detected. Syncing clipboard to desktop.")
                        lastSyncedClipboardText = clipText
                        relayClipboardState(clipText)
                    } else {
                        Log.i(TAG, "Local phone copy event ignored (direction: $direction, autoSync: $autoSync)")
                    }
                }
            }
        }
    }

    private fun syncToAndroidClipboard(text: String) {
        lastSyncedClipboardText = text
        lastIncomingSyncTime = System.currentTimeMillis()
        val clip = ClipData.newPlainText("platypusd-sync", text)
        clipboardManager.setPrimaryClip(clip)
    }

    fun syncClipboardIfChanged() {
        val clipData = clipboardManager.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val clipText = clipData.getItemAt(0).text?.toString() ?: ""
            val trimmedText = clipText.trim()
            val trimmedLast = lastSyncedClipboardText.trim()

            if (trimmedText.isNotEmpty() && trimmedText != trimmedLast) {
                val timePassed = System.currentTimeMillis() - lastIncomingSyncTime
                if (timePassed < 1500) {
                    Log.d(TAG, "Ignoring resume clipboard check due to recent incoming sync ($timePassed ms ago)")
                    return
                }

                val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                val direction = sharedPrefs.getString("clipboard_direction", "bidirectional")
                val autoSync = sharedPrefs.getBoolean("clipboard_auto_sync", true)

                if (autoSync && (direction == "bidirectional" || direction == "mobile_to_desktop")) {
                    Log.i(TAG, "On resume: local copy event detected. Syncing clipboard to desktop.")
                    lastSyncedClipboardText = clipText
                    relayClipboardState(clipText)
                }
            }
        }
    }

    fun getClipboardConfigFromDaemon() {
        if (!isWsConnected) return
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$daemonUrl/api/v1/clipboard/config")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        Log.i(TAG, "Fetched clipboard config: $bodyStr")
                        val json = JSONObject(bodyStr)
                        val direction = json.optString("direction", "bidirectional")
                        val autoSync = json.optBoolean("auto_sync", true)

                        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit()
                            .putString("clipboard_direction", direction)
                            .putBoolean("clipboard_auto_sync", autoSync)
                            .apply()

                        // Trigger UI update if MainActivity is active
                        scope.launch(Dispatchers.Main) {
                            MainActivity.instance?.refreshClipboardUi()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching clipboard config: ${e.message}")
            }
        }
    }

    fun updateClipboardConfigOnDaemon(direction: String, autoSync: Boolean) {
        if (!isWsConnected) return
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("direction", direction)
                    put("auto_sync", autoSync)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$daemonUrl/api/v1/clipboard/config")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Successfully updated clipboard config on daemon.")
                    } else {
                        Log.e(TAG, "Failed to update clipboard config: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating clipboard config on daemon: ${e.message}")
            }
        }
    }

    private fun handleCallActionCommand(action: String) {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
        if (telecomManager == null) {
            Log.e(TAG, "TelecomManager not available")
            return
        }

        try {
            if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "ANSWER_PHONE_CALLS permission not granted, cannot execute action $action")
                return
            }

            if (action == "accept") {
                Log.i(TAG, "Answering incoming call programmatically...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    telecomManager.acceptRingingCall()
                }
            } else if (action == "reject") {
                Log.i(TAG, "Ending/Declining call programmatically...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                } else {
                    @Suppress("DEPRECATION")
                    telecomManager.endCall()
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException programmatically handling call action: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call action command: ${e.message}")
        }
    }

    private var isBluetoothConnectedCached: Boolean? = null

    private fun checkBluetoothConnectionToHost() {
        if (!isWsConnected || activeWebSocket == null) return

        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        var currentStatus = false
        var activeMac = ""
        var activeName = ""

        if (btAdapter != null && btAdapter.isEnabled) {
            try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                    val selectedMac = sharedPrefs.getString("selected_bluetooth_mac", null)
                    val selectedName = sharedPrefs.getString("selected_bluetooth_name", "")

                    if (selectedMac != null) {
                        activeMac = selectedMac
                        activeName = selectedName ?: ""
                        if (connectedBluetoothDevices.contains(selectedMac)) {
                            currentStatus = true
                        } else {
                            try {
                                val device = btAdapter.getRemoteDevice(selectedMac)
                                val method = device.javaClass.getMethod("isConnected")
                                currentStatus = method.invoke(device) as Boolean
                            } catch (e: Exception) {
                                currentStatus = false
                            }
                        }
                    } else {
                        val bonded = btAdapter.bondedDevices
                        for (device in bonded) {
                            val isConnected = try {
                                val method = device.javaClass.getMethod("isConnected")
                                method.invoke(device) as Boolean
                            } catch (e: Exception) {
                                false
                            }
                            if (isConnected) {
                                val devName = (device.name ?: "").lowercase()
                                if (devName.contains("edith") || devName.contains("linux") || devName.contains("pc") || devName.contains("desktop") || devName.contains("computer")) {
                                    currentStatus = true
                                    activeMac = device.address
                                    activeName = device.name ?: ""
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "No bluetooth permission to check connection status: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking bluetooth status: ${e.message}")
            }
        }

        if (isBluetoothConnectedCached != currentStatus) {
            isBluetoothConnectedCached = currentStatus
            Log.i(TAG, "Bluetooth connection status to host changed: $currentStatus. Sending to backend.")
            sendBluetoothStatus(currentStatus, activeMac, activeName)
        }
    }

    private fun sendBluetoothStatus(isConnected: Boolean, mac: String, name: String) {
        if (activeWebSocket == null || !isWsConnected) return
        scope.launch {
            try {
                val json = org.json.JSONObject().apply {
                    put("command", "UpdateBluetoothStatus")
                    put("data", org.json.JSONObject().apply {
                        put("device_id", deviceId)
                        put("is_connected", isConnected)
                        put("bluetooth_mac", mac)
                        put("bluetooth_name", name)
                    })
                }
                activeWebSocket?.send(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending bluetooth status: ${e.message}")
            }
        }
    }

    fun relayClipboardState(text: String) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("text", text)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$daemonUrl/api/v1/clipboard")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to push clipboard: ${response.code}")
                    } else {
                        Log.i(TAG, "Successfully pushed clipboard to host daemon.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pushing clipboard: ${e.message}")
            }
        }
    }

    /* ---------------- Telephony Integration ---------------- */
    private fun relayCallState(callId: String, number: String, contactName: String, state: String) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("call_id", callId)
                    put("number", number)
                    put("contact_name", contactName)
                    put("state", state)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                
                val request = Request.Builder()
                    .url("$daemonUrl/api/v1/calls/state")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to relay call state to daemon: ${response.code}")
                    } else {
                        Log.i(TAG, "Successfully sent call state update: $state")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to daemon: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "platypusd Sync Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun getNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("platypusd platform sync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }
    fun isBluetoothDeviceConnected(address: String): Boolean {
        if (connectedBluetoothDevices.contains(address)) return true
        
        val btAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && btAdapter.isEnabled) {
            try {
                val device = btAdapter.getRemoteDevice(address)
                val method = device.javaClass.getMethod("isConnected")
                return method.invoke(device) as Boolean
            } catch (e: Exception) {}
        }
        return false
    }

    private fun startLocalHttpServer() {
        if (fileServerJob != null && fileServerJob!!.isActive) {
            Log.i(TAG, "Local file sharing server is already running.")
            return
        }
        fileServerJob = scope.launch(Dispatchers.IO) {
            try {
                val serverSocket = java.net.ServerSocket(9090)
                fileServerSocket = serverSocket
                Log.i(TAG, "Local file sharing server started on port 9090")
                while (true) {
                    val socket = serverSocket.accept()
                    scope.launch(Dispatchers.IO) {
                        try {
                            val inputStream = socket.getInputStream()
                            val headerBytes = java.io.ByteArrayOutputStream()
                            while (true) {
                                val b = inputStream.read()
                                if (b == -1) break
                                headerBytes.write(b)
                                val bytes = headerBytes.toByteArray()
                                if (bytes.size >= 4 && 
                                    bytes[bytes.size - 4] == '\r'.toByte() && 
                                    bytes[bytes.size - 3] == '\n'.toByte() && 
                                    bytes[bytes.size - 2] == '\r'.toByte() && 
                                    bytes[bytes.size - 1] == '\n'.toByte()) {
                                    break
                                }
                            }
                            
                            val headerStr = String(headerBytes.toByteArray(), Charsets.UTF_8)
                            val headerLines = headerStr.split("\r\n")
                            if (headerLines.isEmpty()) return@launch
                            
                            val firstLine = headerLines[0]
                            val parts = firstLine.split(" ")
                            if (parts.size >= 2) {
                                val method = parts[0]
                                val decodedUrl = java.net.URLDecoder.decode(parts[1], "UTF-8")
                                val cleanUrl = decodedUrl.substringBefore("?")
                                val query = if (decodedUrl.contains("?")) decodedUrl.substringAfter("?") else ""
                                
                                // Parse headers map
                                val headers = mutableMapOf<String, String>()
                                for (i in 1 until headerLines.size) {
                                    val line = headerLines[i]
                                    if (line.contains(":")) {
                                        val key = line.substringBefore(":").trim().lowercase()
                                        val value = line.substringAfter(":").trim()
                                        headers[key] = value
                                    }
                                }

                                val out = socket.getOutputStream()
                                
                                if (method == "OPTIONS") {
                                    out.write("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS, DELETE\r\nAccess-Control-Allow-Headers: Content-Type, Content-Length\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                    out.flush()
                                } else if (method == "GET") {
                                    if (cleanUrl.startsWith("/list")) {
                                        var pathStr = android.os.Environment.getExternalStorageDirectory().absolutePath
                                        if (query.startsWith("path=")) {
                                            val p = query.substringAfter("path=")
                                            if (p.isNotEmpty()) {
                                                pathStr = p
                                            }
                                        }
                                        Log.i(TAG, "File Server: Listing directory path='$pathStr'")
                                        
                                        val dir = java.io.File(pathStr)
                                        val filesJson = org.json.JSONArray()
                                        if (dir.exists() && dir.isDirectory) {
                                            dir.listFiles()?.forEach { file ->
                                                val obj = org.json.JSONObject().apply {
                                                    put("name", file.name)
                                                    put("is_dir", file.isDirectory)
                                                    put("size", file.length())
                                                    put("path", file.absolutePath)
                                                    put("last_modified", file.lastModified() / 1000)
                                                }
                                                filesJson.put(obj)
                                            }
                                        }
                                        val response = filesJson.toString()
                                        val responseBytes = response.toByteArray(Charsets.UTF_8)
                                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${responseBytes.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                        out.write(responseBytes)
                                        out.flush()
                                    } else if (cleanUrl.startsWith("/download")) {
                                        var pathStr = ""
                                        if (query.startsWith("path=")) {
                                            pathStr = query.substringAfter("path=")
                                        }
                                        
                                        val file = java.io.File(pathStr)
                                        if (file.exists() && file.isFile) {
                                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nAccess-Control-Allow-Origin: *\r\nContent-Disposition: attachment; filename=\"${file.name}\"\r\n\r\n".toByteArray())
                                            file.inputStream().use { input ->
                                                input.copyTo(out)
                                            }
                                            out.flush()
                                        } else {
                                            val response = "File not found"
                                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${response.length}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                            out.write(response.toByteArray())
                                            out.flush()
                                        }
                                    } else {
                                        val response = "Not Found"
                                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${response.length}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                        out.write(response.toByteArray())
                                        out.flush()
                                    }
                                } else if (method == "POST" && cleanUrl.startsWith("/upload")) {
                                    var pathStr = ""
                                    if (query.startsWith("path=")) {
                                        pathStr = query.substringAfter("path=")
                                    }
                                    val file = java.io.File(pathStr)
                                    val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                                    
                                    file.parentFile?.mkdirs()
                                    val fileOut = file.outputStream()
                                    var bytesCopied = 0L
                                    val buffer = ByteArray(8192)
                                    
                                    while (bytesCopied < contentLength) {
                                        val toRead = minOf(buffer.size.toLong(), contentLength - bytesCopied).toInt()
                                        val read = inputStream.read(buffer, 0, toRead)
                                        if (read == -1) break
                                        fileOut.write(buffer, 0, read)
                                        bytesCopied += read
                                    }
                                    fileOut.flush()
                                    fileOut.close()
                                    
                                    val response = "{\"success\":true}"
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.length}\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS, DELETE\r\n\r\n".toByteArray())
                                    out.write(response.toByteArray())
                                    out.flush()
                                } else if (method == "DELETE" && cleanUrl.startsWith("/delete")) {
                                    var pathStr = ""
                                    if (query.startsWith("path=")) {
                                        pathStr = query.substringAfter("path=")
                                    }
                                    val file = java.io.File(pathStr)
                                    val deleted = if (file.exists()) {
                                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                                    } else false
                                    
                                    val response = "{\"success\":$deleted}"
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.length}\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS, DELETE\r\n\r\n".toByteArray())
                                    out.write(response.toByteArray())
                                    out.flush()
                                } else {
                                    val response = "Method not supported"
                                    out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: ${response.length}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                                    out.write(response.toByteArray())
                                    out.flush()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling HTTP client: ${e.message}")
                        } finally {
                            try { socket.close() } catch (e: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP Server socket closed or failed: ${e.message}")
            }
        }
    }

    private fun stopLocalHttpServer() {
        try { fileServerSocket?.close() } catch (e: Exception) {}
        fileServerSocket = null
        fileServerJob?.cancel()
        fileServerJob = null
        Log.i(TAG, "Local file sharing server stopped and socket released.")
    }
}
