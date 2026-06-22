package com.platypus.platypusd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101
    private val handler = Handler(Looper.getMainLooper())
    
    // Tab buttons
    private lateinit var devicesTabBtn: Button
    private lateinit var callsTabBtn: Button
    private lateinit var clipboardTabBtn: Button
    
    // Tab containers
    private lateinit var devicesContainer: LinearLayout
    private lateinit var callsContainer: LinearLayout
    private lateinit var clipboardContainer: LinearLayout

    // Connection status card views
    private lateinit var statusBadge: TextView
    private lateinit var statusDetails: TextView
    private lateinit var disconnectBtn: Button

    // Lists
    private lateinit var wifiDevicesLayout: LinearLayout
    private lateinit var bluetoothDevicesLayout: LinearLayout
    private lateinit var clipboardStatusText: TextView
    private lateinit var clipboardInput: EditText
    
    private val updateRunnable = object : Runnable {
        override fun run() {
            refreshDiscoveryLists()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup base layout
        val rootLayout = RelativeLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#08090D"))
        }

        // Header Title (Always Visible)
        val header = TextView(this).apply {
            id = View.generateViewId()
            text = "🦦 platypusd platform"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 50, 0, 30)
        }
        val headerParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        rootLayout.addView(header, headerParams)

        // Tab Bar (Always Visible)
        val tabContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0E111A"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        devicesTabBtn = createTabButton("Devices", true) { switchTab("devices") }
        callsTabBtn = createTabButton("Call Sync", false) { switchTab("calls") }
        clipboardTabBtn = createTabButton("Clipboard", false) { switchTab("clipboard") }

        tabContainer.addView(devicesTabBtn)
        tabContainer.addView(callsTabBtn)
        tabContainer.addView(clipboardTabBtn)

        val tabParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.BELOW, header.id)
            setMargins(30, 10, 30, 20)
        }
        rootLayout.addView(tabContainer, tabParams)

        // Scrollable Content View
        val scrollView = ScrollView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        }
        val scrollParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT).apply {
            addRule(RelativeLayout.BELOW, tabContainer.id)
            setMargins(30, 0, 30, 30)
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // ================= TAB 1: DEVICES =================
        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }

        // Active Connection Status Card
        val statusCard = createCardLayout()
        statusBadge = TextView(this).apply {
            text = "DISCONNECTED"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F43F5E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }
        statusCard.addView(statusBadge)

        statusDetails = TextView(this).apply {
            text = "No active connection to desktop daemon."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        statusCard.addView(statusDetails)

        disconnectBtn = Button(this).apply {
            text = "Disconnect"
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E11D48"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                ConnectionService.instance?.disconnect()
                Toast.makeText(this@MainActivity, "Disconnected from daemon", Toast.LENGTH_SHORT).show()
                refreshDiscoveryLists()
            }
        }
        statusCard.addView(disconnectBtn)
        devicesContainer.addView(statusCard)

        // Wi-Fi Discovery Section
        devicesContainer.addView(createSectionTitle("📶 Discovered Wi-Fi Hosts (mDNS)"))
        val wifiCard = createCardLayout()
        wifiDevicesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wifiCard.addView(wifiDevicesLayout)
        devicesContainer.addView(wifiCard)

        // QR / Manual Pairing Section
        devicesContainer.addView(createSectionTitle("🔗 QR Code / Manual Connect"))
        val manualCard = createCardLayout()
        
        val scanButton = Button(this).apply {
            text = "📷 Scan Pairing QR Code"
            setBackgroundColor(Color.parseColor("#5E60CE"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                startQrCodeScanner()
            }
        }
        manualCard.addView(scanButton)

        val separator = TextView(this).apply {
            text = "— OR MANUALLY ENTER IP —"
            textSize = 10f
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, 15, 0, 15)
        }
        manualCard.addView(separator)

        val pairingInput = EditText(this).apply {
            hint = "e.g., 192.168.1.112"
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.edit_text)
            setPadding(20, 20, 20, 20)
        }
        manualCard.addView(pairingInput)

        val pairButton = Button(this).apply {
            text = "Connect Manually"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val input = pairingInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    handleManualPairing(input)
                }
            }
        }
        manualCard.addView(pairButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
        devicesContainer.addView(manualCard)

        // Bluetooth Section
        devicesContainer.addView(createSectionTitle("🛜 Bonded Bluetooth Devices"))
        val bluetoothCard = createCardLayout()
        bluetoothDevicesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bluetoothCard.addView(bluetoothDevicesLayout)
        devicesContainer.addView(bluetoothCard)

        contentLayout.addView(devicesContainer)

        // ================= TAB 2: CALL SYNC =================
        callsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val callControlCard = createCardLayout()
        val callTitleText = TextView(this).apply {
            text = "📞 Phone Call Attending Integration"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 10)
        }
        callControlCard.addView(callTitleText)

        val callDescText = TextView(this).apply {
            text = "Relays live phone call alerts (Ringing, Connected, Muted) automatically to the desktop host when active."
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 30)
        }
        callControlCard.addView(callDescText)

        val callToggle = Switch(this).apply {
            text = "Enable Call State Mirroring"
            setTextColor(Color.WHITE)
            isChecked = true
            textSize = 15f
        }
        callControlCard.addView(callToggle)
        callsContainer.addView(callControlCard)
        contentLayout.addView(callsContainer)

        // ================= TAB 3: CLIPBOARD =================
        clipboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val clipControlCard = createCardLayout()
        clipboardStatusText = TextView(this).apply {
            text = "Shared Clipboard Text: None"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setTypeface(null, Typeface.ITALIC)
            setPadding(0, 0, 0, 30)
        }
        clipControlCard.addView(clipboardStatusText)

        clipboardInput = EditText(this).apply {
            hint = "Type text to send to host clipboard..."
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.edit_text)
            setPadding(20, 20, 20, 20)
        }
        clipControlCard.addView(clipboardInput)

        val syncClipBtn = Button(this).apply {
            text = "Send to PC Clipboard"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val text = clipboardInput.text.toString()
                if (text.isNotEmpty()) {
                    syncClipboard(text)
                    clipboardInput.setText("")
                }
            }
        }
        clipControlCard.addView(syncClipBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
        clipboardContainer.addView(clipControlCard)
        contentLayout.addView(clipboardContainer)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView, scrollParams)

        setContentView(rootLayout)

        // Request Permissions
        if (checkPermissions()) {
            startIntegrationService()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun switchTab(tab: String) {
        // Reset backgrounds
        devicesTabBtn.setBackgroundColor(Color.parseColor("#0E111A"))
        callsTabBtn.setBackgroundColor(Color.parseColor("#0E111A"))
        clipboardTabBtn.setBackgroundColor(Color.parseColor("#0E111A"))

        // Hide containers
        devicesContainer.visibility = View.GONE
        callsContainer.visibility = View.GONE
        clipboardContainer.visibility = View.GONE

        when (tab) {
            "devices" -> {
                devicesTabBtn.setBackgroundColor(Color.parseColor("#5E60CE"))
                devicesContainer.visibility = View.VISIBLE
            }
            "calls" -> {
                callsTabBtn.setBackgroundColor(Color.parseColor("#5E60CE"))
                callsContainer.visibility = View.VISIBLE
            }
            "clipboard" -> {
                clipboardTabBtn.setBackgroundColor(Color.parseColor("#5E60CE"))
                clipboardContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun createTabButton(title: String, active: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(if (active) "#5E60CE" else "#0E111A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun checkPermissions(): Boolean {
        val phoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
        val callLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
        val answerCalls = ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
        
        val bluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return phoneState == PackageManager.PERMISSION_GRANTED && 
               callLog == PackageManager.PERMISSION_GRANTED && 
               answerCalls == PackageManager.PERMISSION_GRANTED && 
               bluetoothConnect
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE, 
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startIntegrationService()
            }
        }
    }

    private fun startIntegrationService() {
        val intent = Intent(this, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E111A"))
            setPadding(30, 30, 30, 30)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 30
            }
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(10, 20, 10, 15)
        }
    }

    private fun startQrCodeScanner() {
        val scanner = GmsBarcodeScanning.getClient(this)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue ?: ""
                if (rawValue.isNotEmpty()) {
                    handleManualPairing(rawValue)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun refreshDiscoveryLists() {
        val service = ConnectionService.instance
        val isConnected = service != null && service.isConnected

        // Update central Status Card
        if (isConnected && service != null) {
            statusBadge.text = "🟢 CONNECTED"
            statusBadge.setTextColor(Color.parseColor("#10B981"))
            statusDetails.text = "Synced with desktop at ${service.connectedHost}"
            disconnectBtn.visibility = View.VISIBLE
        } else {
            statusBadge.text = "🔴 DISCONNECTED"
            statusBadge.setTextColor(Color.parseColor("#F43F5E"))
            statusDetails.text = "No active connection to desktop daemon."
            disconnectBtn.visibility = View.GONE
        }

        // Update Wi-Fi discovery list
        wifiDevicesLayout.removeAllViews()
        val devices = mutableListOf<String>()
        devices.add("platypusd-EDITH (192.168.1.112:8080)")

        for (device in devices) {
            val ip = device.substringAfter("(").substringBefore(":")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(25, 25, 25, 25)
                setBackgroundColor(Color.parseColor("#0E111A"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 15
                }
            }
            val devText = TextView(this).apply {
                text = device
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(devText)

            if (isConnected && service != null && service.connectedHost == ip) {
                // Already connected
                val activeIndicator = TextView(this).apply {
                    text = "🟢 Connected"
                    setTextColor(Color.parseColor("#10B981"))
                    setTypeface(null, Typeface.BOLD)
                    textSize = 14f
                    setPadding(15, 0, 15, 0)
                }
                row.addView(activeIndicator)
            } else {
                // Show connect option only if not connected to ANY host
                if (!isConnected) {
                    val connectBtn = Button(this).apply {
                        text = "Connect"
                        textSize = 11f
                        setBackgroundColor(Color.parseColor("#10B981"))
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            triggerServiceConnect(ip)
                        }
                    }
                    row.addView(connectBtn)
                } else {
                    val idleText = TextView(this).apply {
                        text = "Idle"
                        setTextColor(Color.parseColor("#475569"))
                        textSize = 13f
                        setPadding(15, 0, 15, 0)
                    }
                    row.addView(idleText)
                }
            }

            wifiDevicesLayout.addView(row)
        }

        // Update Bluetooth bonded list safely checking permissions
        bluetoothDevicesLayout.removeAllViews()
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && btAdapter.isEnabled) {
            // Check Bluetooth Connect permission on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                
                val requestText = TextView(this).apply {
                    text = "⚠️ Bluetooth permission required. Tap to grant."
                    setTextColor(Color.parseColor("#F43F5E"))
                    textSize = 14f
                    setOnClickListener {
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                            PERMISSION_REQUEST_CODE
                        )
                    }
                }
                bluetoothDevicesLayout.addView(requestText)
                return
            }
            
            val pairedDevices: Set<BluetoothDevice> = btAdapter.bondedDevices
            if (pairedDevices.isEmpty()) {
                val emptyText = TextView(this).apply {
                    text = "No bonded Bluetooth hosts found."
                    setTextColor(Color.parseColor("#475569"))
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, 20, 0, 20)
                }
                bluetoothDevicesLayout.addView(emptyText)
            } else {
                for (device in pairedDevices) {
                    val isDeviceConnected = try {
                        val method = device.javaClass.getMethod("isConnected")
                        method.invoke(device) as Boolean
                    } catch (e: Exception) {
                        false
                    }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(25, 25, 25, 25)
                        setBackgroundColor(Color.parseColor("#0E111A"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = 15
                        }
                    }
                    val devText = TextView(this).apply {
                        text = "💻 ${device.name ?: "Unknown Device"}\n${device.address}"
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(devText)

                    if (isDeviceConnected) {
                        val activeIndicator = TextView(this).apply {
                            text = "🟢 Connected"
                            setTextColor(Color.parseColor("#10B981"))
                            setTypeface(null, Typeface.BOLD)
                            textSize = 13f
                            setPadding(15, 0, 15, 0)
                        }
                        row.addView(activeIndicator)

                        val infoBtn = Button(this).apply {
                            text = "Info"
                            textSize = 11f
                            setBackgroundColor(Color.parseColor("#1E293B"))
                            setTextColor(Color.WHITE)
                            setOnClickListener {
                                val builder = android.app.AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                builder.setTitle("Bluetooth Connected")
                                builder.setMessage("This device is currently connected over system Bluetooth.\n\nTo disconnect or unpair, please use your phone's system Bluetooth Settings.")
                                builder.setPositiveButton("OK", null)
                                builder.show()
                            }
                        }
                        row.addView(infoBtn)
                    } else {
                        val connectBtn = Button(this).apply {
                            text = "Connect"
                            textSize = 11f
                            setBackgroundColor(Color.parseColor("#5E60CE"))
                            setTextColor(Color.WHITE)
                            setOnClickListener {
                                val builder = android.app.AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                builder.setTitle("Route Audio over Bluetooth")
                                builder.setMessage("To stream voice call audio to/from '${device.name}', please ensure this phone is paired and connected under Headset/Hands-Free profile in your system's Bluetooth settings.\n\nCall controls are automatically synchronized over Wi-Fi/WebSockets.")
                                builder.setPositiveButton("OK", null)
                                builder.show()
                            }
                        }
                        row.addView(connectBtn)
                    }
                    bluetoothDevicesLayout.addView(row)
                }
            }
        } else {
            val disabledText = TextView(this).apply {
                text = "Bluetooth is disabled or unsupported."
                setTextColor(Color.parseColor("#475569"))
                textSize = 14f
            }
            bluetoothDevicesLayout.addView(disabledText)
        }

        // Update active clipboard indicator
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString() ?: ""
            clipboardStatusText.text = "Shared Clipboard Text: $text"
        }
    }

    private fun handleManualPairing(input: String) {
        try {
            if (input.startsWith("{")) {
                val json = JSONObject(input)
                val ip = json.getString("ip")
                triggerServiceConnect(ip)
                Toast.makeText(this, "Connected via QR config!", Toast.LENGTH_SHORT).show()
            } else {
                triggerServiceConnect(input)
                Toast.makeText(this, "Connecting to: $input", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Invalid pairing code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerServiceConnect(ip: String) {
        val intent = Intent(this, ConnectionService::class.java).apply {
            putExtra("DAEMON_IP", ip)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Initiating connection to $ip", Toast.LENGTH_SHORT).show()
    }

    private fun syncClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("platypusd-sync", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Local clipboard synced & sent to PC!", Toast.LENGTH_SHORT).show()
    }
}
