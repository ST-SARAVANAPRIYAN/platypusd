package com.platypus.platypusd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    private val CHANNEL_ID = "platypusd_sync"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for WebSockets
        .build()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var daemonHost = "10.0.2.2"
    private var daemonPort = 8080
    private var daemonUrl = "http://10.0.2.2:8080"
    private var wsUrl = "ws://10.0.2.2:8080/api/v1/events"

    private var activeWebSocket: WebSocket? = null
    private var isWsConnected = false

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
    private var audioTrack: AudioTrack? = null

    private val nsdManager by lazy { getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private lateinit var deviceId: String
    private lateinit var deviceName: String
    private val publicKey = "android-device-key-sig"

    val isConnected: Boolean
        get() = isWsConnected

    val connectedHost: String
        get() = daemonHost

    val isBluetoothConnectedToHost: Boolean
        get() = isBluetoothConnectedCached ?: false

    var connectedBluetoothDeviceName: String = ""

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
        connectWebSocket()

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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering bluetooth receiver: ${e.message}")
        }
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
            "$wsUrl?device_id=$deviceId&device_name=$encodedName"
        }
        Log.i(TAG, "Connecting WebSocket to: $urlWithParams")
        val request = Request.Builder().url(urlWithParams).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
             override fun onOpen(webSocket: WebSocket, response: Response) {
                isWsConnected = true
                Log.i(TAG, "WebSocket event connection opened to: $wsUrl")
                isBluetoothConnectedCached = null // force update
                getClipboardConfigFromDaemon()
                getBluetoothConfigFromDaemon()
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
                    } else if (event == "BluetoothConfigChanged") {
                        val speakerMode = data.optString("speaker_mode", "desktop_as_speaker")
                        val callSyncEnabled = data.optBoolean("call_sync_enabled", true)
                        Log.i(TAG, "Received bluetooth config update from daemon: speakerMode=$speakerMode, callSyncEnabled=$callSyncEnabled")
                        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit()
                            .putString("bluetooth_speaker_mode", speakerMode)
                            .putBoolean("bluetooth_call_sync_enabled", callSyncEnabled)
                            .apply()
                        
                        if (speakerMode == "mobile_as_speaker") {
                            startDesktopAudioStream()
                        } else {
                            stopDesktopAudioStream()
                        }
                        
                        scope.launch(Dispatchers.Main) {
                            MainActivity.instance?.initLayout()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket payload: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val buffer = bytes.toByteArray()
                audioTrack?.write(buffer, 0, buffer.size)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWsConnected = false
                releaseAudioTrack()
                Log.w(TAG, "WebSocket connection closed: $reason. Retrying in 5 seconds...")
                scope.launch {
                    delayReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWsConnected = false
                releaseAudioTrack()
                Log.e(TAG, "WebSocket error: ${t.message}. Retrying in 5 seconds...")
                scope.launch {
                    delayReconnect()
                }
            }
        })
    }

    private suspend fun delayReconnect() {
        kotlinx.coroutines.delay(5000)
        scope.launch(Dispatchers.Main) {
            connectWebSocket()
        }
    }

    /* ---------------- Desktop Audio Streaming ---------------- */
    private fun initAudioTrack() {
        if (audioTrack != null) return
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufSize.coerceAtLeast(8192),
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
            Log.i(TAG, "AudioTrack initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioTrack = null
        Log.i(TAG, "AudioTrack released.")
    }

    fun startDesktopAudioStream() {
        if (!isWsConnected) return
        scope.launch {
            val json = JSONObject().apply {
                put("command", "StartDesktopAudio")
            }
            initAudioTrack()
            activeWebSocket?.send(json.toString())
            Log.i(TAG, "Sent StartDesktopAudio command to daemon")
        }
    }

    fun stopDesktopAudioStream() {
        if (!isWsConnected) return
        scope.launch {
            val json = JSONObject().apply {
                put("command", "StopDesktopAudio")
            }
            activeWebSocket?.send(json.toString())
            releaseAudioTrack()
            Log.i(TAG, "Sent StopDesktopAudio command to daemon")
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

    fun getBluetoothConfigFromDaemon() {
        if (!isWsConnected) return
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$daemonUrl/api/v1/bluetooth/config")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        Log.i(TAG, "Fetched bluetooth config: $bodyStr")
                        val json = JSONObject(bodyStr)
                        val speakerMode = json.optString("speaker_mode", "desktop_as_speaker")
                        val callSyncEnabled = json.optBoolean("call_sync_enabled", true)

                        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit()
                            .putString("bluetooth_speaker_mode", speakerMode)
                            .putBoolean("bluetooth_call_sync_enabled", callSyncEnabled)
                            .apply()

                        // Trigger UI update if MainActivity is active
                        scope.launch(Dispatchers.Main) {
                            MainActivity.instance?.let { activity ->
                                activity.initLayout()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching bluetooth config: ${e.message}")
            }
        }
    }

    fun updateBluetoothConfigOnDaemon(speakerMode: String, callSyncEnabled: Boolean) {
        if (!isWsConnected) return
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("speaker_mode", speakerMode)
                    put("call_sync_enabled", callSyncEnabled)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("$daemonUrl/api/v1/bluetooth/config")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(TAG, "Successfully updated bluetooth config on daemon.")
                    } else {
                        Log.e(TAG, "Failed to update bluetooth config: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating bluetooth config on daemon: ${e.message}")
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

        connectedBluetoothDeviceName = activeName
        if (isBluetoothConnectedCached != currentStatus) {
            isBluetoothConnectedCached = currentStatus
            Log.i(TAG, "Bluetooth connection status to host changed: $currentStatus. Sending to backend.")
            sendBluetoothStatus(currentStatus, activeMac, activeName)
            MainActivity.instance?.let { activity ->
                activity.runOnUiThread {
                    activity.initLayout()
                }
            }
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
}
