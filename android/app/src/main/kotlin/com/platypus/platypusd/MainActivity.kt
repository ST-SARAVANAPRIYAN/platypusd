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
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101
    private val STORAGE_PERMISSION_CODE = 102
    private var currentPcPath = ""
    private var pcFilesList = org.json.JSONArray()
    private val handler = Handler(Looper.getMainLooper())
    
    // Theme state
    private var isDarkMode = true
    
    // Tab buttons
    private lateinit var connectionTabBtn: Button
    private lateinit var settingsTabBtn: Button
    private lateinit var clipboardTabBtn: Button
    private lateinit var filesTabBtn: Button
    
    // Tab containers
    private lateinit var connectionContainer: LinearLayout
    private lateinit var settingsContainer: LinearLayout
    private lateinit var clipboardContainer: LinearLayout
    private lateinit var filesContainer: LinearLayout

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
    
    private var currentTab = "connection"

    // Material You / Android 16 Palette
    private val darkBg = Color.parseColor("#0B0D11")
    private val darkCard = Color.parseColor("#181B21")
    private val darkBorder = Color.parseColor("#2F333A")
    private val darkText = Color.parseColor("#E6E1E5")
    private val darkTextMuted = Color.parseColor("#CAC4D0")

    private val lightBg = Color.parseColor("#F0F4F9")
    private val lightCard = Color.parseColor("#FFFFFF")
    private val lightBorder = Color.parseColor("#E1E3E8")
    private val lightText = Color.parseColor("#1D1B20")
    private val lightTextMuted = Color.parseColor("#49454F")

    private val accentColor: Int
        get() = if (isDarkMode) Color.parseColor("#A8C7FA") else Color.parseColor("#0B57D0")
    private val successColor: Int
        get() = if (isDarkMode) Color.parseColor("#6DDB9C") else Color.parseColor("#146C43")

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
        CustomToast.show(this, "Welcome to platypus", isDarkMode)
        
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

    private fun getNeobrutalismDrawable(backgroundColor: Int, borderColor: Int, borderWidthPx: Int, radius: Float = 56f): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            if (borderWidthPx > 0) {
                setStroke(2, borderColor) // Force thin stroke (2px)
            }
            cornerRadius = radius
        }
    }

    private fun initLayout() {
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
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5, 100f)
            setPadding(10, 10, 10, 10)
        }

        connectionTabBtn = createTabButton("Connection", currentTab == "connection") { switchTab("connection") }
        clipboardTabBtn = createTabButton("Clipboard", currentTab == "clipboard") { switchTab("clipboard") }
        filesTabBtn = createTabButton("Files", currentTab == "files") { switchTab("files") }
        settingsTabBtn = createTabButton("Settings", currentTab == "settings") { switchTab("settings") }

        tabContainer.addView(connectionTabBtn)
        tabContainer.addView(clipboardTabBtn)
        tabContainer.addView(filesTabBtn)
        tabContainer.addView(settingsTabBtn)

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

        // ================= TAB 1: CONNECTION =================
        connectionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentTab == "connection") View.VISIBLE else View.GONE
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
            background = getNeobrutalismDrawable(Color.parseColor("#B3261E"), Color.TRANSPARENT, 0, 100f)
            setTextColor(Color.WHITE)
            setOnClickListener {
                ConnectionService.instance?.disconnect()
                CustomToast.show(this@MainActivity, "Disconnected from daemon", isDarkMode)
                refreshDiscoveryLists()
            }
        }
        statusCard.addView(disconnectBtn)
        connectionContainer.addView(statusCard)

        // Bluetooth Section
        connectionContainer.addView(createSectionTitle("Bonded Bluetooth Devices"))
        val bluetoothCard = createCardLayout()
        bluetoothDevicesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        bluetoothCard.addView(bluetoothDevicesLayout)
        connectionContainer.addView(bluetoothCard)

        contentLayout.addView(connectionContainer)

        // ================= TAB 2: CLIPBOARD =================
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
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5, 36f)
            setPadding(25, 25, 25, 25)
        }
        clipControlCard.addView(clipboardInput)

        val syncClipBtn = Button(this).apply {
            text = "Send to PC Clipboard"
            setTypeface(null, Typeface.BOLD)
            background = getNeobrutalismDrawable(successColor, getThemeColor(darkBorder, lightBorder), 5, 100f)
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

        // ================= TAB: FILES =================
        filesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentTab == "files") View.VISIBLE else View.GONE
        }

        if (!checkStoragePermission()) {
            val permCard = createCardLayout()
            val promptText = TextView(this).apply {
                text = "Storage access is required to share files with the PC. Please grant permission."
                setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                textSize = 14f
                setPadding(20, 20, 20, 20)
            }
            val grantBtn = Button(this).apply {
                text = "Grant Storage Permission"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5)
                setOnClickListener {
                    requestStoragePermission()
                }
            }
            permCard.addView(promptText)
            permCard.addView(grantBtn)
            filesContainer.addView(permCard)
        } else {
            val filesCard = createCardLayout()
            
            // Files Header & Navigation
            val filesHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 10, 10, 15)
            }
            
            val upBtn = Button(this).apply {
                text = "UP"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5)
                setOnClickListener {
                    if (currentPcPath.isNotEmpty() && currentPcPath != "/" && currentPcPath.contains("/")) {
                        val parent = currentPcPath.substringBeforeLast("/")
                        val target = if (parent.isEmpty()) "/" else parent
                        fetchPcFiles(target)
                    }
                }
            }
            
            val pathText = TextView(this).apply {
                text = if (currentPcPath.isEmpty()) "PC Home Directory" else currentPcPath
                setTextColor(getThemeColor(darkText, lightText))
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
                setPadding(25, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                ellipsize = android.text.TextUtils.TruncateAt.START
                isSingleLine = true
            }
            
            filesHeader.addView(upBtn)
            filesHeader.addView(pathText)
            filesCard.addView(filesHeader)

            // Search input field
            val searchInput = EditText(this).apply {
                hint = "Search files..."
                setText(pcSearchQuery)
                setTextColor(getThemeColor(darkText, lightText))
                setHintTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 8)
                setPadding(30, 20, 30, 20)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(10, 5, 10, 15)
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        pcSearchQuery = s?.toString() ?: ""
                        runOnUiThread { initLayout() }
                    }
                })
            }
            filesCard.addView(searchInput)

            // Sort Controls & Upload Button Bar
            val controlsBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 0, 10, 20)
            }

            val sortTitle = TextView(this).apply {
                text = "Sort:"
                setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                textSize = 12f
                setPadding(0, 0, 10, 0)
            }
            controlsBar.addView(sortTitle)

            // Sort Toggle Button
            val sortToggleBtn = Button(this).apply {
                text = when (pcSortBy) {
                    "size" -> "Size ${if (pcSortAscending) "▲" else "▼"}"
                    "date" -> "Date ${if (pcSortAscending) "▲" else "▼"}"
                    else -> "Name ${if (pcSortAscending) "▲" else "▼"}"
                }
                textSize = 10f
                setTextColor(getThemeColor(darkText, lightText))
                background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5)
                setPadding(20, 10, 20, 10)
                setOnClickListener {
                    if (pcSortBy == "name") {
                        if (pcSortAscending) {
                            pcSortAscending = false
                        } else {
                            pcSortBy = "size"
                            pcSortAscending = true
                        }
                    } else if (pcSortBy == "size") {
                        if (pcSortAscending) {
                            pcSortAscending = false
                        } else {
                            pcSortBy = "date"
                            pcSortAscending = true
                        }
                    } else {
                        if (pcSortAscending) {
                            pcSortAscending = false
                        } else {
                            pcSortBy = "name"
                            pcSortAscending = true
                        }
                    }
                    runOnUiThread { initLayout() }
                }
            }
            controlsBar.addView(sortToggleBtn)

            // Spacer
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            }
            controlsBar.addView(spacer)

            // Upload Button
            val uploadBtn = Button(this).apply {
                text = "UPLOAD FILE"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5)
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                    }
                    startActivityForResult(Intent.createChooser(intent, "Select File to Upload"), PICK_FILE_REQUEST_CODE)
                }
            }
            controlsBar.addView(uploadBtn)
            filesCard.addView(controlsBar)
            
            val filesListLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            
            val processedFiles = getProcessedPcFiles()
            if (processedFiles.length() == 0) {
                val emptyText = TextView(this).apply {
                    text = if (pcFilesList.length() == 0) "Tap to load PC files or connect to PC." else "No matching files found."
                    setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(0, 40, 0, 40)
                    setOnClickListener {
                        fetchPcFiles(currentPcPath)
                    }
                }
                filesListLayout.addView(emptyText)
            } else {
                for (i in 0 until processedFiles.length()) {
                    val item = processedFiles.getJSONObject(i)
                    val name = item.getString("name")
                    val isDir = item.getBoolean("is_dir")
                    val path = item.getString("path")
                    val size = item.getLong("size")
                    
                    val itemRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(20, 25, 20, 25)
                        background = getNeobrutalismDrawable(Color.TRANSPARENT, getThemeColor(darkBorder, lightBorder), 1)
                        setOnClickListener {
                            if (isDir) {
                                fetchPcFiles(path)
                            } else {
                                val options = arrayOf("Download to Mobile", "Show Details", "Delete from PC")
                                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle(name)
                                    .setItems(options) { _, which ->
                                        when (options[which]) {
                                            "Download to Mobile" -> downloadPcFile(path, name)
                                            "Show Details" -> showFileDetailsDialog(name, path, size, isDir, item.optLong("last_modified", 0))
                                            "Delete from PC" -> deletePcFile(path)
                                        }
                                    }
                                    .show()
                            }
                        }
                        setOnLongClickListener {
                            val options = arrayOf("Show Details", "Delete from PC")
                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle(name)
                                .setItems(options) { _, which ->
                                    when (options[which]) {
                                        "Show Details" -> showFileDetailsDialog(name, path, size, isDir, item.optLong("last_modified", 0))
                                        "Delete from PC" -> deletePcFile(path)
                                    }
                                }
                                .show()
                            true
                        }
                    }
                    
                    val iconView = TextView(this).apply {
                        text = if (isDir) "DIR" else "FILE"
                        textSize = 9f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (isDir) Color.WHITE else getThemeColor(darkText, lightText))
                        background = getNeobrutalismDrawable(if (isDir) accentColor else getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 4)
                        setPadding(12, 6, 12, 6)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 0, 20, 0)
                        }
                    }
                    
                    val infoLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    
                    val nameView = TextView(this).apply {
                        text = name
                        setTextColor(getThemeColor(darkText, lightText))
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                    }
                    
                    val sizeView = TextView(this).apply {
                        text = if (isDir) "Folder" else "${size / 1024} KB"
                        setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
                        textSize = 11f
                    }
                    
                    infoLayout.addView(nameView)
                    infoLayout.addView(sizeView)
                    
                    itemRow.addView(iconView)
                    itemRow.addView(infoLayout)
                    filesListLayout.addView(itemRow)
                }
            }
            
            filesCard.addView(filesListLayout)
            filesContainer.addView(filesCard)
        }
        contentLayout.addView(filesContainer)

        // ================= TAB 3: SETTINGS =================
        settingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentTab == "settings") View.VISIBLE else View.GONE
        }

        // Clipboard Configuration Card
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
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5, 36f)
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
            
            val initialPos = when (currentDirection) {
                "bidirectional" -> 0
                "desktop_to_mobile" -> 1
                "mobile_to_desktop" -> 2
                else -> 0
            }
            setSelection(initialPos)

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val dir = when (pos) {
                        0 -> "bidirectional"
                        1 -> "desktop_to_mobile"
                        2 -> "mobile_to_desktop"
                        else -> "bidirectional"
                    }
                    saveClipboardConfig(dir, autoSyncSwitch.isChecked)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        spinnerContainer.addView(directionSpinner)
        clipConfigCard.addView(spinnerContainer)

        autoSyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            val dir = when (directionSpinner.selectedItemPosition) {
                0 -> "bidirectional"
                1 -> "desktop_to_mobile"
                2 -> "mobile_to_desktop"
                else -> "bidirectional"
            }
            saveClipboardConfig(dir, isChecked)
        }
        settingsContainer.addView(clipConfigCard)

        // Call Mirroring Configuration Card
        val callControlCard = createCardLayout()
        val callTitleText = TextView(this).apply {
            text = "Phone Call Synchronization"
            textSize = 16f
            setTextColor(getThemeColor(darkText, lightText))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }
        callControlCard.addView(callTitleText)

        val callDescText = TextView(this).apply {
            text = "Relays live phone call alerts (Ringing, Connected, Muted) automatically to the desktop host when active."
            textSize = 13f
            setTextColor(getThemeColor(darkTextMuted, lightTextMuted))
            setPadding(0, 0, 0, 20)
        }
        callControlCard.addView(callDescText)

        val callToggle = Switch(this).apply {
            text = "Enable Call State Mirroring"
            setTextColor(getThemeColor(darkText, lightText))
            isChecked = true
            textSize = 14f
        }
        callControlCard.addView(callToggle)
        settingsContainer.addView(callControlCard)

        // Wi-Fi Setup Cards
        wifiTitle = createSectionTitle("Discovered Wi-Fi Hosts")
        settingsContainer.addView(wifiTitle)
        
        wifiCard = createCardLayout()
        wifiDevicesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wifiCard.addView(wifiDevicesLayout)
        settingsContainer.addView(wifiCard)

        manualTitle = createSectionTitle("Manual Configuration")
        settingsContainer.addView(manualTitle)
        
        manualCard = createCardLayout()
        val scanButton = Button(this).apply {
            text = "Scan Pairing QR Code"
            setTypeface(null, Typeface.BOLD)
            background = getNeobrutalismDrawable(accentColor, getThemeColor(darkBorder, lightBorder), 5, 100f)
            setTextColor(if (isDarkMode) Color.parseColor("#062E6F") else Color.WHITE)
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
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5, 36f)
            setPadding(25, 25, 25, 25)
        }
        manualCard.addView(pairingInput)

        val pairButton = Button(this).apply {
            text = "Connect Manually"
            setTypeface(null, Typeface.BOLD)
            background = getNeobrutalismDrawable(getThemeColor(darkCard, lightCard), getThemeColor(darkBorder, lightBorder), 5, 100f)
            setTextColor(getThemeColor(darkText, lightText))
            setOnClickListener {
                val input = pairingInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    handleManualPairing(input)
                }
            }
        }
        manualCard.addView(pairButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 25 })
        settingsContainer.addView(manualCard)

        contentLayout.addView(settingsContainer)

        scrollView.addView(contentLayout)
        rootLayout.addView(scrollView, scrollParams)

        setContentView(rootLayout)
        refreshDiscoveryLists()
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        if (tab == "files") {
            fetchPcFiles(currentPcPath)
        }
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
                    background = getNeobrutalismDrawable(getThemeColor(darkBg, lightBg), getThemeColor(darkBorder, lightBorder), 4, 36f)
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
                    background = getNeobrutalismDrawable(successColor, getThemeColor(darkBorder, lightBorder), 4, 100f)
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
                    val isDeviceConnected = ConnectionService.instance?.isBluetoothDeviceConnected(device.address) ?: false

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
                        background = getNeobrutalismDrawable(bgCol, getThemeColor(darkBorder, lightBorder), 4, 36f)
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
                        background = getNeobrutalismDrawable(btnBg, getThemeColor(darkBorder, lightBorder), 4, 100f)
                        setTextColor(if (isSelected) getThemeColor(darkText, lightText) else if (isDarkMode) Color.parseColor("#062E6F") else Color.WHITE)
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

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            val readPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            val writePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            readPerm == PackageManager.PERMISSION_GRANTED && writePerm == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
        }
    }

    private fun fetchPcFiles(path: String) {
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("paired_host_ip", null)
        val port = sharedPrefs.getInt("paired_host_port", 8080)
        
        if (ip == null) {
            Toast.makeText(this, "Not connected to PC daemon", Toast.LENGTH_SHORT).show()
            return
        }
        
        val url = "http://$ip:$port/api/v1/files/list?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        val request = okhttp3.Request.Builder().url(url).build()
        val client = okhttp3.OkHttpClient()
        
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to list files: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: "[]"
                        runOnUiThread {
                            pcFilesList = org.json.JSONArray(bodyStr)
                            currentPcPath = path
                            initLayout()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun downloadPcFile(path: String, fileName: String) {
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("paired_host_ip", null)
        val port = sharedPrefs.getInt("paired_host_port", 8080)
        
        if (ip == null) return
        
        val url = "http://$ip:$port/api/v1/files/download?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        val request = okhttp3.Request.Builder().url(url).build()
        val client = okhttp3.OkHttpClient()
        
        Toast.makeText(this, "Downloading $fileName...", Toast.LENGTH_SHORT).show()
        
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (response.isSuccessful) {
                        try {
                            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            val destFile = java.io.File(downloadsDir, fileName)
                            response.body?.byteStream()?.use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Downloaded to Downloads/$fileName", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Server error: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }
    private val PICK_FILE_REQUEST_CODE = 201
    private var pcSearchQuery = ""
    private var pcSortBy = "name" // name, size, date
    private var pcSortAscending = true

    private fun getProcessedPcFiles(): org.json.JSONArray {
        val filtered = mutableListOf<org.json.JSONObject>()
        for (i in 0 until pcFilesList.length()) {
            val item = pcFilesList.getJSONObject(i)
            val name = item.getString("name")
            if (pcSearchQuery.isEmpty() || name.lowercase().contains(pcSearchQuery.lowercase())) {
                filtered.add(item)
            }
        }
        
        filtered.sortWith(Comparator { a, b ->
            val aDir = a.getBoolean("is_dir")
            val bDir = b.getBoolean("is_dir")
            if (aDir && !bDir) return@Comparator -1
            if (!aDir && bDir) return@Comparator 1
            
            val comparison = when (pcSortBy) {
                "size" -> {
                    val aSize = a.optLong("size", 0)
                    val bSize = b.optLong("size", 0)
                    aSize.compareTo(bSize)
                }
                "date" -> {
                    val aDate = a.optLong("last_modified", 0)
                    val bDate = b.optLong("last_modified", 0)
                    aDate.compareTo(bDate)
                }
                else -> {
                    val aName = a.optString("name", "")
                    val bName = b.optString("name", "")
                    aName.lowercase().compareTo(bName.lowercase())
                }
            }
            if (pcSortAscending) comparison else -comparison
        })
        
        val result = org.json.JSONArray()
        filtered.forEach { result.put(it) }
        return result
    }

    private fun deletePcFile(path: String) {
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("paired_host_ip", null) ?: return
        val port = sharedPrefs.getInt("paired_host_port", 8080)
        
        val url = "http://$ip:$port/api/v1/files/delete?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        val request = okhttp3.Request.Builder().url(url).delete().build()
        val client = okhttp3.OkHttpClient()
        
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Deleted successfully from PC", Toast.LENGTH_SHORT).show()
                    fetchPcFiles(currentPcPath)
                }
            }
        })
    }

    private fun showFileDetailsDialog(name: String, path: String, size: Long, isDir: Boolean, lastModified: Long) {
        val dateStr = if (lastModified > 0) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            sdf.format(java.util.Date(lastModified * 1000))
        } else {
            "Unknown"
        }
        val sizeStr = if (isDir) "Directory" else "${size / 1024} KB ($size bytes)"
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Detailed Properties")
            .setMessage("Name: $name\n\nLocation: $path\n\nSize: $sizeStr\n\nLast Modified: $dateStr")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            
            try {
                val fileName = getFileName(uri) ?: "upload.bin"
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
                    return
                }
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
                val ip = sharedPrefs.getString("paired_host_ip", null)
                val port = sharedPrefs.getInt("paired_host_port", 8080)
                
                if (ip == null) {
                    Toast.makeText(this, "Not connected to PC", Toast.LENGTH_SHORT).show()
                    return
                }
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        fileName,
                        RequestBody.create("application/octet-stream".toMediaTypeOrNull(), bytes)
                    )
                    .build()
                
                val targetPath = if (currentPcPath.isEmpty()) "/home/saravana" else currentPcPath
                val url = "http://$ip:$port/api/v1/files/upload?path=${java.net.URLEncoder.encode(targetPath, "UTF-8")}"
                val request = okhttp3.Request.Builder().url(url).post(requestBody).build()
                val client = okhttp3.OkHttpClient()
                
                Toast.makeText(this, "Uploading to PC...", Toast.LENGTH_SHORT).show()
                
                client.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Uploaded successfully to PC!", Toast.LENGTH_SHORT).show()
                            fetchPcFiles(currentPcPath)
                        }
                    }
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        var instance: MainActivity? = null
    }
}

object CustomToast {
    fun show(context: android.content.Context, message: String, isDarkMode: Boolean) {
        val toast = android.widget.Toast(context)
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(40, 20, 40, 20)
            
            val bgCol = if (isDarkMode) Color.parseColor("#E6E1E5") else Color.parseColor("#1D1B20")
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(bgCol)
                cornerRadius = 100f
            }
            background = drawable
        }
        
        val text = android.widget.TextView(context).apply {
            setText(message)
            val textCol = if (isDarkMode) Color.parseColor("#1D1B20") else Color.parseColor("#E6E1E5")
            setTextColor(textCol)
            textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        layout.addView(text)
        
        toast.view = layout
        toast.duration = android.widget.Toast.LENGTH_SHORT
        toast.show()
    }
}
