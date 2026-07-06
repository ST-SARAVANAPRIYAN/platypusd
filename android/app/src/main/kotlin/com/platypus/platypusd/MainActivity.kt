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
        instance = this
        
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
        instance = this
        handler.post(updateRunnable)
        ConnectionService.instance?.syncClipboardIfChanged()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
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

    fun initLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = getThemeColor(darkBg, lightBg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = window.decorView.systemUiVisibility
                flags = if (isDarkMode) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                window.decorView.systemUiVisibility = flags
            }
        }

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
        callsTabBtn = createTabButton("Bluetooth", currentTab == "calls") { switchTab("calls") }
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

        // ================= TAB 2: BLUETOOTH CONNECTIVITY =================
        callsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentTab == "calls") View.VISIBLE else View.GONE
        }

        val service = ConnectionService.instance
        val isBluetoothConnected = service?.isBluetoothConnectedToHost ?: false
        val connectedDeviceName = service?.connectedBluetoothDeviceName ?: ""

        if (!isBluetoothConnected) {
            val disconnectedCard = createCardLayout()
            val warnTitle = TextView(this).apply {
                text = "Bluetooth Connectivity"
                textSize = 18f
                setTextColor(getThemeColor(darkText, lightText))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 15)
            }
            disconnectedCard.addView(warnTitle)

            val warnDesc = TextView(this).apply {
                text = "⚠️ Bluetooth Devices Not Connected.\n\nPlease pair and connect your mobile phone and PC in system Bluetooth settings to access Call Audio Routing and PC Speaker configuration."
                textSize = 14f
                setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                setPadding(0, 0, 0, 30)
            }
            disconnectedCard.addView(warnDesc)

            val openSettingsBtn = Button(this).apply {
                text = "Open Bluetooth Settings"
                setTypeface(null, Typeface.BOLD)
                setTextColor(getThemeColor(darkText, lightText))
                background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
                setOnClickListener {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Could not open Bluetooth settings", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            disconnectedCard.addView(openSettingsBtn)
            callsContainer.addView(disconnectedCard)
        } else {
            // Bluetooth Connected UI
            val statusCard = createCardLayout()
            val statusTitle = TextView(this).apply {
                text = "Bluetooth Status"
                textSize = 16f
                setTextColor(getThemeColor(darkText, lightText))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 10)
            }
            statusCard.addView(statusTitle)

            val statusText = TextView(this).apply {
                text = "✅ Connected to Host via Bluetooth\nDevice: $connectedDeviceName"
                textSize = 14f
                setTextColor(successColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 10)
            }
            statusCard.addView(statusText)
            callsContainer.addView(statusCard)

            // Configurations Card
            val configCard = createCardLayout()
            val configTitle = TextView(this).apply {
                text = "Bluetooth Audio & Call Options"
                textSize = 18f
                setTextColor(getThemeColor(darkText, lightText))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 15)
            }
            configCard.addView(configTitle)

            val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
            val currentMode = sharedPrefs.getString("bluetooth_speaker_mode", "desktop_as_speaker") ?: "desktop_as_speaker"
            val currentCallSync = sharedPrefs.getBoolean("bluetooth_call_sync_enabled", true)

            // 1. Speaker Mode Config
            val modeLabel = TextView(this).apply {
                text = "Audio Role Mode:"
                textSize = 13f
                setTextColor(getThemeColor(darkText, lightText))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 10, 0, 10)
            }
            configCard.addView(modeLabel)

            val toggleContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
                setPadding(5, 5, 5, 5)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 25
                }
            }

            var selectedMode = currentMode

            val btnDesktop = Button(this).apply {
                text = "Desktop Speaker"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnMobile = Button(this).apply {
                text = "Mobile Speaker"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            fun updateToggleVisuals() {
                if (selectedMode == "desktop_as_speaker") {
                    btnDesktop.background = getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5)
                    btnDesktop.setTextColor(Color.WHITE)
                    btnMobile.background = getNeobrutalismDrawable(Color.TRANSPARENT, Color.TRANSPARENT, 0)
                    btnMobile.setTextColor(getThemeColor(darkText, lightText))
                } else {
                    btnMobile.background = getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5)
                    btnMobile.setTextColor(Color.WHITE)
                    btnDesktop.background = getNeobrutalismDrawable(Color.TRANSPARENT, Color.TRANSPARENT, 0)
                    btnDesktop.setTextColor(getThemeColor(darkText, lightText))
                }
            }

            updateToggleVisuals()

            // 2. Call Sync Toggle
            val callToggle = Switch(this).apply {
                text = "Enable Call Audio Routing"
                setTextColor(getThemeColor(darkText, lightText))
                isChecked = currentCallSync
                textSize = 15f
                setPadding(0, 0, 0, 20)
            }

            val saveConfig = { modeVal: String, callSyncVal: Boolean, showToast: Boolean ->
                sharedPrefs.edit()
                    .putString("bluetooth_speaker_mode", modeVal)
                    .putBoolean("bluetooth_call_sync_enabled", callSyncVal)
                    .apply()
                
                service?.updateBluetoothConfigOnDaemon(modeVal, callSyncVal)
                if (showToast) {
                    val toastText = if (modeVal == "desktop_as_speaker") "Role Mode: Desktop as Speaker" else "Role Mode: Mobile as Speaker (Wi-Fi Stream)"
                    Toast.makeText(this@MainActivity, "✅ $toastText", Toast.LENGTH_SHORT).show()
                }
            }

            val audioStreamToggle = Switch(this).apply {
                text = "Receive PC Audio Stream (Wi-Fi)"
                setTextColor(getThemeColor(darkText, lightText))
                isChecked = (currentMode == "mobile_as_speaker")
                textSize = 15f
            }

            btnDesktop.setOnClickListener {
                if (selectedMode != "desktop_as_speaker") {
                    selectedMode = "desktop_as_speaker"
                    updateToggleVisuals()
                    audioStreamToggle.isChecked = false
                    saveConfig(selectedMode, callToggle.isChecked, true)
                }
            }

            btnMobile.setOnClickListener {
                if (selectedMode != "mobile_as_speaker") {
                    selectedMode = "mobile_as_speaker"
                    updateToggleVisuals()
                    audioStreamToggle.isChecked = true
                    saveConfig(selectedMode, callToggle.isChecked, true)
                }
            }

            toggleContainer.addView(btnDesktop)
            toggleContainer.addView(btnMobile)
            configCard.addView(toggleContainer)

            configCard.addView(callToggle)

            callToggle.setOnCheckedChangeListener { _, isChecked ->
                saveConfig(selectedMode, isChecked, false)
                Toast.makeText(this@MainActivity, if (isChecked) "✅ Call Routing Enabled" else "✅ Call Routing Disabled", Toast.LENGTH_SHORT).show()
            }

            configCard.addView(audioStreamToggle)

            audioStreamToggle.setOnCheckedChangeListener { _, isChecked ->
                val newMode = if (isChecked) "mobile_as_speaker" else "desktop_as_speaker"
                if (selectedMode != newMode) {
                    selectedMode = newMode
                    updateToggleVisuals()
                    saveConfig(selectedMode, callToggle.isChecked, false)
                }
                if (service != null) {
                    if (isChecked) {
                        service.startDesktopAudioStream()
                        Toast.makeText(this@MainActivity, "Desktop audio stream started", Toast.LENGTH_SHORT).show()
                    } else {
                        service.stopDesktopAudioStream()
                        Toast.makeText(this@MainActivity, "Desktop audio stream stopped", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            callsContainer.addView(configCard)
        }

        contentLayout.addView(callsContainer)

        // ================= TAB 3: CLIPBOARD =================
        clipboardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentTab == "clipboard") View.VISIBLE else View.GONE
        }

        // 1. Config Card
        val clipConfigCard = createCardLayout()
        val configTitle = TextView(this).apply {
            text = "Clipboard Sync Options"
            textSize = 16f
            setTextColor(getThemeColor(darkText, lightText))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }
        clipConfigCard.addView(configTitle)

        val autoSyncPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val currentAutoSync = autoSyncPrefs.getBoolean("clipboard_auto_sync", true)
        val currentDirection = autoSyncPrefs.getString("clipboard_direction", "bidirectional") ?: "bidirectional"

        val autoSyncSwitch = Switch(this).apply {
            text = "Enable Automatic Sync"
            setTextColor(getThemeColor(darkText, lightText))
            isChecked = currentAutoSync
            textSize = 14f
            setPadding(0, 0, 0, 20)
        }
        clipConfigCard.addView(autoSyncSwitch)

        val directionLabel = TextView(this).apply {
            text = "Sync Direction:"
            textSize = 13f
            setTextColor(getThemeColor(darkText, lightText))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 10)
        }
        clipConfigCard.addView(directionLabel)

        val spinnerContainer = FrameLayout(this).apply {
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
            setPadding(10, 5, 10, 5)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 20
            }
        }

        val directionSpinner = Spinner(this).apply {
            val options = arrayOf("Bidirectional", "Desktop to Mobile", "Mobile to Desktop")
            val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, options) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    (v as? TextView)?.apply {
                        setTextColor(getThemeColor(darkText, lightText))
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                    }
                    return v
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent)
                    v.setBackgroundColor(getThemeColor(darkCard, lightCard))
                    (v as? TextView)?.apply {
                        setTextColor(getThemeColor(darkText, lightText))
                        textSize = 14f
                    }
                    return v
                }
            }
            this.adapter = adapter
            
            val selectionIndex = when(currentDirection) {
                "bidirectional" -> 0
                "desktop_to_mobile" -> 1
                "mobile_to_desktop" -> 2
                else -> 0
            }
            setSelection(selectionIndex)
        }
        spinnerContainer.addView(directionSpinner)
        clipConfigCard.addView(spinnerContainer)

        autoSyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            val dirIndex = directionSpinner.selectedItemPosition
            val dirValue = when(dirIndex) {
                0 -> "bidirectional"
                1 -> "desktop_to_mobile"
                2 -> "mobile_to_desktop"
                else -> "bidirectional"
            }
            saveClipboardConfig(dirValue, isChecked)
        }

        directionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isChecked = autoSyncSwitch.isChecked
                val dirValue = when(position) {
                    0 -> "bidirectional"
                    1 -> "desktop_to_mobile"
                    2 -> "mobile_to_desktop"
                    else -> "bidirectional"
                }
                saveClipboardConfig(dirValue, isChecked)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        clipboardContainer.addView(clipConfigCard)

        // 2. Control Card (Send/Status)
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
            setTextColor(if (active) Color.WHITE else getThemeColor(darkText, lightText))
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
        val readContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
        
        val bluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return phoneState == PackageManager.PERMISSION_GRANTED && 
               callLog == PackageManager.PERMISSION_GRANTED && 
               answerCalls == PackageManager.PERMISSION_GRANTED && 
               readContacts == PackageManager.PERMISSION_GRANTED && 
               bluetoothConnect
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE, 
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS
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

        val service = ConnectionService.instance
        if (service != null) {
            service.lastSyncedClipboardText = text
            service.relayClipboardState(text)
            Toast.makeText(this, "Local clipboard synced and sent to PC", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Local clipboard synced (Daemon not connected)", Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshClipboardUi() {
        runOnUiThread {
            if (currentTab == "clipboard") {
                initLayout()
            }
        }
    }

    private fun saveClipboardConfig(direction: String, autoSync: Boolean) {
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val oldDirection = sharedPrefs.getString("clipboard_direction", "bidirectional")
        val oldAutoSync = sharedPrefs.getBoolean("clipboard_auto_sync", true)

        if (oldDirection != direction || oldAutoSync != autoSync) {
            sharedPrefs.edit()
                .putString("clipboard_direction", direction)
                .putBoolean("clipboard_auto_sync", autoSync)
                .apply()
            
            ConnectionService.instance?.updateClipboardConfigOnDaemon(direction, autoSync)
        }
    }

    companion object {
        var instance: MainActivity? = null
    }
}
