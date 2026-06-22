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
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for WebSockets
        .build()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var daemonHost = "10.0.2.2"
    private var daemonPort = 8080
    private var daemonUrl = "http://10.0.2.2:8080"
    private var wsUrl = "ws://10.0.2.2:8080/api/v1/events"

    private var activeWebSocket: WebSocket? = null
    private var isWsConnected = false

    private lateinit var clipboardManager: ClipboardManager
    private var lastSyncedClipboardText = ""

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
        connectWebSocket()

        Log.i(TAG, "ConnectionService initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val customIp = intent?.getStringExtra("DAEMON_IP")
        if (customIp != null) {
            updateDaemonUrl(customIp, 8080)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket payload: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isWsConnected = false
                Log.w(TAG, "WebSocket connection closed: $reason. Retrying in 5 seconds...")
                scope.launch {
                    delayReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWsConnected = false
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

    /* ---------------- Clipboard Operations ---------------- */
    private fun setupClipboardListener() {
        clipboardManager.addPrimaryClipChangedListener {
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val clipText = clipData.getItemAt(0).text?.toString() ?: ""
                if (clipText.isNotEmpty() && clipText != lastSyncedClipboardText) {
                    Log.i(TAG, "Local phone copy event detected. Syncing clipboard to desktop.")
                    lastSyncedClipboardText = clipText
                    relayClipboardState(clipText)
                }
            }
        }
    }

    private fun syncToAndroidClipboard(text: String) {
        lastSyncedClipboardText = text
        val clip = ClipData.newPlainText("platypusd-sync", text)
        clipboardManager.setPrimaryClip(clip)
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

    private fun relayClipboardState(text: String) {
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
