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
    
    // Theme state
    private var isDarkMode = true
    
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

    // Toggleable sections
    private lateinit var manualCard: LinearLayout
    private lateinit var wifiCard: LinearLayout
    private lateinit var wifiTitle: TextView
    private lateinit var manualTitle: TextView

    // Lists
    private lateinit var wifiDevicesLayout: LinearLayout
    private lateinit var bluetoothDevicesLayout: LinearLayout
    private lateinit var clipboardStatusText: TextView
    private lateinit var clipboardInput: EditText
    
    private var currentTab = "devices"

    // Neobrutalism Palette
    private val darkBg = Color.parseColor("#121212")
    private val darkCard = Color.parseColor("#1E1E1E")
    private val darkBorder = Color.parseColor("#FFFFFF")
    private val darkText = Color.parseColor("#FFFFFF")
    private val darkTextMuted = Color.parseColor("#9CA3AF")

    private val lightBg = Color.parseColor("#F3F4F6")
    private val lightCard = Color.parseColor("#FFFFFF")
    private val lightBorder = Color.parseColor("#000000")
    private val lightText = Color.parseColor("#000000")
    private val lightTextMuted = Color.parseColor("#4B5563")

    private val accentColor = Color.parseColor("#A855F7") // Neobrutalism Purple
    private val successColor = Color.parseColor("#10B981") // Neobrutalism Green

    private val updateRunnable = object : Runnable {
        override fun run() {
            refreshDiscoveryLists()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        initLayout()

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

    private fun getThemeColor(darkVal: Int, lightVal: Int): Int {
        return if (isDarkMode) darkVal else lightVal
    }

    private fun getNeobrutalismDrawable(backgroundColor: Int, borderColor: Int, borderWidthPx: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            setStroke(borderWidthPx, borderColor)
            cornerRadius = 0f // Sharp corners
        }
    }

    private fun initLayout() {
        // Setup root layout
        val rootLayout = RelativeLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(getThemeColor(darkBg, lightBg))
        }

        // Header Title Layout
        val headerLayout = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 50, 40, 30)
        }
        val titleText = TextView(this).apply {
            text = "platypusd platform"
            textSize = 20f
            setTextColor(getThemeColor(darkText, lightText))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(titleText)
        
        val themeToggleBtn = Button(this).apply {
            text = if (isDarkMode) "LIGHT" else "DARK"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getThemeColor(darkText, lightText))
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
            setPadding(30, 15, 30, 15)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                isDarkMode = !isDarkMode
                initLayout()
            }
        }
        headerLayout.addView(themeToggleBtn)
        
        val headerParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        rootLayout.addView(headerLayout, headerParams)

        // Tab Bar
        val tabContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
            setPadding(10, 10, 10, 10)
        }

        devicesTabBtn = createTabButton("Devices", currentTab == "devices") { switchTab("devices") }
        callsTabBtn = createTabButton("Call Sync", currentTab == "calls") { switchTab("calls") }
        clipboardTabBtn = createTabButton("Clipboard", currentTab == "clipboard") { switchTab("clipboard") }

        tabContainer.addView(devicesTabBtn)
        tabContainer.addView(callsTabBtn)
        tabContainer.addView(clipboardTabBtn)

        val tabParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.BELOW, headerLayout.id)
            setMargins(30, 10, 30, 20)
        }
        rootLayout.addView(tabContainer, tabParams)

        // Scrollable Content View
        val scrollView = ScrollView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            isFillViewport = true
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
            visibility = if (currentTab == "devices") View.VISIBLE else View.GONE
        }

        // Active Connection Status Card
        val statusCard = createCardLayout()
        statusBadge = TextView(this).apply {
            text = "DISCONNECTED"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getThemeColor(darkText, lightText))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 15)
        }
        statusCard.addView(statusBadge)

        statusDetails = TextView(this).apply {
            text = "No active connection to desktop daemon."
            textSize = 13f
            setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 25)
        }
        statusCard.addView(statusDetails)

        disconnectBtn = Button(this).apply {
            text = "Disconnect"
            visibility = View.GONE
            setTypeface(null, Typeface.BOLD)
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

        // Wi-Fi Discovery Section (Hide when connected)
        wifiTitle = createSectionTitle("Discovered Wi-Fi Hosts")
        devicesContainer.addView(wifiTitle)
        
        wifiCard = createCardLayout()
        wifiDevicesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wifiCard.addView(wifiDevicesLayout)
        devicesContainer.addView(wifiCard)

        // QR / Manual Pairing Section (Hide when connected)
        manualTitle = createSectionTitle("Manual Connect")
        devicesContainer.addView(manualTitle)
        
        manualCard = createCardLayout()
        val scanButton = Button(this).apply {
            text = "Scan Pairing QR Code"
            setTypeface(null, Typeface.BOLD)
            background = getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5)
            setTextColor(Color.WHITE)
            setOnClickListener {
                startQrCodeScanner()
            }
        }
        manualCard.addView(scanButton)

        val separator = TextView(this).apply {
            text = "— OR MANUALLY ENTER IP —"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
            gravity = Gravity.CENTER
            setPadding(0, 25, 0, 25)
        }
        manualCard.addView(separator)

        val pairingInput = EditText(this).apply {
            hint = "e.g., 192.168.1.112"
            setHintTextColor(getThemeColor(darkTextMuted, lightTextMuted))
            setTextColor(getThemeColor(darkText, lightText))
            textSize = 14f
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
            setPadding(25, 25, 25, 25)
        }
        manualCard.addView(pairingInput)

        val pairButton = Button(this).apply {
            text = "Connect Manually"
            setTypeface(null, Typeface.BOLD)
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
            setTextColor(getThemeColor(darkText, lightText))
            setOnClickListener {
                val input = pairingInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    handleManualPairing(input)
                }
            }
        }
        manualCard.addView(pairButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 25 })
        devicesContainer.addView(manualCard)

        // Bluetooth Section
        devicesContainer.addView(createSectionTitle("Bonded Bluetooth Devices"))
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
            visibility = if (currentTab == "calls") View.VISIBLE else View.GONE
        }

        val callControlCard = createCardLayout()
        val callTitleText = TextView(this).apply {
            text = "Phone Call Synchronization"
            textSize = 18f
            setTextColor(getThemeColor(darkText, lightText))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }
        callControlCard.addView(callTitleText)

        val callDescText = TextView(this).apply {
            text = "Relays live phone call alerts (Ringing, Connected, Muted) automatically to the desktop host when active."
            textSize = 14f
            setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
            setPadding(0, 0, 0, 30)
        }
        callControlCard.addView(callDescText)

        val callToggle = Switch(this).apply {
            text = "Enable Call State Mirroring"
            setTextColor(getThemeColor(darkText, lightText))
            isChecked = true
            textSize = 15f
        }
        callControlCard.addView(callToggle)
        callsContainer.addView(callControlCard)
        contentLayout.addView(callsContainer)

        // ================= TAB 3: CLIPBOARD =================
        clipboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentTab == "clipboard") View.VISIBLE else View.GONE
        }

        val clipControlCard = createCardLayout()
        clipboardStatusText = TextView(this).apply {
            text = "Shared Clipboard Text: None"
            textSize = 14f
            setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
            setTypeface(null, Typeface.ITALIC)
            setPadding(0, 0, 0, 30)
        }
        clipControlCard.addView(clipboardStatusText)

        clipboardInput = EditText(this).apply {
            hint = "Type text to send to host clipboard..."
            setHintTextColor(getThemeColor(darkTextMuted, lightTextMuted))
            setTextColor(getThemeColor(darkText, lightText))
            textSize = 14f
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
            setPadding(25, 25, 25, 25)
        }
        clipControlCard.addView(clipboardInput)

        val syncClipBtn = Button(this).apply {
            text = "Send to PC Clipboard"
            setTypeface(null, Typeface.BOLD)
            background = getNeobrutalismDrawable(successColor, getThemeColor(darkBorder, lightBorder), 5)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val text = clipboardInput.text.toString()
                if (text.isNotEmpty()) {
                    syncClipboard(text)
                    clipboardInput.setText("")
                }
            }
        }
        clipControlCard.addView(syncClipBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 25 })
        clipboardContainer.addView(clipControlCard)
        contentLayout.addView(clipboardContainer)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView, scrollParams)

        setContentView(rootLayout)
        refreshDiscoveryLists()
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        initLayout()
    }

    private fun createTabButton(title: String, active: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (active) Color.BLACK else getThemeColor(darkText, lightText))
            background = if (active) {
                getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5)
            } else {
                getNeobrutalismDrawable(Color.TRANSPARENT, Color.TRANSPARENT, 0)
            }
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
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 6)
            setPadding(35, 35, 35, 35)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 30
            }
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(getThemeColor(darkText, lightText))
            setTypeface(null, Typeface.BOLD)
            setPadding(10, 20, 10, 20)
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
            statusBadge.text = "WI-FI CONNECTED"
            statusBadge.setTextColor(successColor)
            statusDetails.text = "Connected host: ${service.connectedHost}"
            disconnectBtn.visibility = View.VISIBLE

            // Hide wifi setup & manual connection cards when connected
            manualCard.visibility = View.GONE
            manualTitle.visibility = View.GONE
            wifiCard.visibility = View.GONE
            wifiTitle.visibility = View.GONE
        } else {
            statusBadge.text = "WI-FI DISCONNECTED"
            statusBadge.setTextColor(getThemeColor(darkText, lightText))
            statusDetails.text = "Scan QR or select from discovered hosts to connect."
            disconnectBtn.visibility = View.GONE

            // Show wifi setup & manual connection cards when disconnected
            manualCard.visibility = View.VISIBLE
            manualTitle.visibility = View.VISIBLE
            wifiCard.visibility = View.VISIBLE
            wifiTitle.visibility = View.VISIBLE
        }

        // Update Wi-Fi discovery list only if disconnected
        wifiDevicesLayout.removeAllViews()
        if (!isConnected) {
            val devices = mutableListOf<String>()
            devices.add("platypusd-EDITH (192.168.1.112:8080)")

            for (device in devices) {
                val ip = device.substringAfter("(").substringBefore(":")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(25, 25, 25, 25)
                    background = getNeobrutalismDrawable(getThemeColor(darkBg, lightBg), getThemeColor(darkBorder, lightBorder), 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 15
                    }
                }
                val devText = TextView(this).apply {
                    text = device
                    setTextColor(getThemeColor(darkText, lightText))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(devText)

                val connectBtn = Button(this).apply {
                    text = "Connect"
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    background = getNeobrutalismDrawable(successColor, getThemeColor(darkBorder, lightBorder), 4)
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        triggerServiceConnect(ip)
                    }
                }
                row.addView(connectBtn)

                wifiDevicesLayout.addView(row)
            }
        }

        // Update Bluetooth bonded list safely checking permissions
        bluetoothDevicesLayout.removeAllViews()
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && btAdapter.isEnabled) {
            // Check Bluetooth Connect permission on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                
                val requestText = TextView(this).apply {
                    text = "Bluetooth permission required. Tap to grant."
                    setTextColor(Color.RED)
                    textSize = 13f
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
                    setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, 20, 0, 20)
                }
                bluetoothDevicesLayout.addView(emptyText)
            } else {
                val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                val selectedMac = sharedPrefs.getString("selected_bluetooth_mac", null)

                for (device in pairedDevices) {
                    val isDeviceConnected = try {
                        val method = device.javaClass.getMethod("isConnected")
                        method.invoke(device) as Boolean
                    } catch (e: Exception) {
                        false
                    }

                    val isSelected = device.address == selectedMac

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(25, 25, 25, 25)
                        val bgCol = if (isSelected) {
                            if (isDarkMode) Color.parseColor("#1B4D3E") else Color.parseColor("#E6F4EA")
                        } else {
                            getThemeColor(darkBg, lightBg)
                        }
                        background = getNeobrutalismDrawable(bgCol, getThemeColor(darkBorder, lightBorder), 4)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = 15
                        }
                    }
                    val devText = TextView(this).apply {
                        text = "${device.name ?: "Unknown Device"}\n${device.address}" + if (isSelected) " (Active Host)" else ""
                        setTextColor(getThemeColor(darkText, lightText))
                        textSize = 13f
                        setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(devText)

                    val statusText = TextView(this).apply {
                        text = if (isDeviceConnected) "CONNECTED" else "DISCONNECTED"
                        setTextColor(if (isDeviceConnected) successColor else getThemeColor(darkTextMuted, lightTextMuted))
                        setTypeface(null, Typeface.BOLD)
                        textSize = 11f
                        setPadding(15, 0, 15, 0)
                    }
                    row.addView(statusText)

                    val actionBtn = Button(this).apply {
                        text = if (isSelected) "DESELECT" else "SELECT"
                        textSize = 10f
                        setTypeface(null, Typeface.BOLD)
                        val btnBg = if (isSelected) {
                            getThemeColor(darkCard, lightCard)
                        } else {
                            accentColor
                        }
                        background = getNeobrutalismDrawable(btnBg, getThemeColor(darkBorder, lightBorder), 4)
                        setTextColor(if (isSelected) getThemeColor(darkText, lightText) else Color.WHITE)
                        setOnClickListener {
                            if (isSelected) {
                                sharedPrefs.edit()
                                    .remove("selected_bluetooth_mac")
                                    .remove("selected_bluetooth_name")
                                    .apply()
                            } else {
                                sharedPrefs.edit()
                                    .putString("selected_bluetooth_mac", device.address)
                                    .putString("selected_bluetooth_name", device.name ?: "Desktop PC")
                                    .apply()
                            }
                            refreshDiscoveryLists()
                            
                            val serviceIntent = Intent(this@MainActivity, ConnectionService::class.java).apply {
                                putExtra("CHECK_BT", true)
                            }
                            startService(serviceIntent)
                        }
                    }
                    row.addView(actionBtn)

                    bluetoothDevicesLayout.addView(row)
                }
            }
        } else {
            val disabledText = TextView(this).apply {
                text = "Bluetooth is disabled or unsupported."
                setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                textSize = 13f
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
                Toast.makeText(this, "Connected via QR config", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "Local clipboard synced and sent", Toast.LENGTH_SHORT).show()
    }
}
