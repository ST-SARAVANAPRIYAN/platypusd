package com.platypus.platypusd

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val PERMISSION_REQUEST_CODE = 101
    private val STORAGE_PERMISSION_CODE = 102
    private val PICK_FILE_REQUEST_CODE = 103

    private val handler = Handler(Looper.getMainLooper())

    // Compose State wrappers for clean unidirectional data updates
    private val isConnectedState = mutableStateOf(false)
    private val connectedHostState = mutableStateOf("")
    private val bondedDevicesState = mutableStateListOf<BluetoothDevice>()
    private val selectedBtMacState = mutableStateOf<String?>(null)
    private val activeBtMacState = mutableStateOf<String?>(null)
    private val currentTabState = mutableStateOf("connection")
    private val isDarkModeState = mutableStateOf(true)
    private val themePaletteState = mutableStateOf("auto")

    // Clipboard State
    private val sharedClipboardTextState = mutableStateOf("")
    private val clipboardInputTextState = mutableStateOf("")

    // Settings State
    private val clipboardAutoSyncState = mutableStateOf(true)
    private val clipboardDirectionState = mutableStateOf("bidirectional")
    private val isStoragePermissionGrantedState = mutableStateOf(true)
    var isActivityInForeground = false

    // Audio Settings State
    val audioSyncEnabledState = mutableStateOf(true)
    val audioDirectionState = mutableStateOf("desktop_to_mobile")
    val audioPlaybackModeState = mutableStateOf("destination_only")
    val wifiSpeakerActiveState = mutableStateOf(false)

    // Files State
    private val currentPcPathState = mutableStateOf("")
    private val pcFilesListState = mutableStateListOf<JSONObject>()
    private val filesSearchQueryState = mutableStateOf("")
    private val filesSortByState = mutableStateOf("name") // "name", "size", "date"
    private val filesSortAscendingState = mutableStateOf(true)
    private val filesHideHiddenState = mutableStateOf(true)
    private val filesLayoutState = mutableStateOf("list") // "list", "grid", "compact"
    private val isFileDownloadingState = mutableStateOf(false)
    private val downloadProgressState = mutableStateOf("")

    // Dialog state
    private val activeDialogState = mutableStateOf<DialogType?>(null)

    sealed class DialogType {
        data class FileDetails(
            val name: String,
            val path: String,
            val size: Long,
            val isDir: Boolean,
            val lastModified: Long
        ) : DialogType()
        data class FileActions(val json: JSONObject) : DialogType()
        object ManualPairing : DialogType()
    }

    companion object {
        var instance: MainActivity? = null
            private set
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            // Fetch live connection details
            val service = ConnectionService.instance
            isConnectedState.value = service != null && service.isConnected
            connectedHostState.value = service?.connectedHost ?: ""

            // Fetch Bluetooth info safely
            val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
            selectedBtMacState.value = sharedPrefs.getString("selected_bluetooth_mac", null)

            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            if (btAdapter != null && btAdapter.isEnabled) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    val bonded = btAdapter.bondedDevices.toList()
                    if (bonded.size != bondedDevicesState.size || !bondedDevicesState.containsAll(bonded)) {
                        bondedDevicesState.clear()
                        bondedDevicesState.addAll(bonded)
                    }
                }
            }

            // Sync shared clipboard preview
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                sharedClipboardTextState.value = clipData.getItemAt(0).text?.toString() ?: ""
            }

            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Load initial preferences
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        isDarkModeState.value = sharedPrefs.getBoolean("is_dark_mode", true)
        clipboardAutoSyncState.value = sharedPrefs.getBoolean("clipboard_auto_sync", true)
        clipboardDirectionState.value = sharedPrefs.getString("clipboard_direction", "bidirectional") ?: "bidirectional"
        selectedBtMacState.value = sharedPrefs.getString("selected_bluetooth_mac", null)
        themePaletteState.value = sharedPrefs.getString("theme_palette", "auto") ?: "auto"

        audioSyncEnabledState.value = sharedPrefs.getBoolean("audio_sync_enabled", true)
        audioDirectionState.value = sharedPrefs.getString("audio_direction", "desktop_to_mobile") ?: "desktop_to_mobile"
        audioPlaybackModeState.value = sharedPrefs.getString("audio_playback_mode", "destination_only") ?: "destination_only"
        wifiSpeakerActiveState.value = sharedPrefs.getBoolean("wifi_speaker_active", false)

        startIntegrationService()
        checkPermissions()

        handler.post(updateRunnable)

        setContent {
            PlatypusTheme(darkTheme = isDarkModeState.value, palette = themePaletteState.value) {
                MainAppScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityInForeground = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isStoragePermissionGrantedState.value = android.os.Environment.isExternalStorageManager()
        } else {
            isStoragePermissionGrantedState.value = androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (currentTabState.value == "files") {
            fetchPcFiles(currentPcPathState.value)
        }
        ConnectionService.instance?.syncClipboardIfChanged()
    }

    override fun onPause() {
        super.onPause()
        isActivityInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        if (instance == this) {
            instance = null
        }
    }

    fun refreshClipboardUi() {
        runOnUiThread {
            val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
            clipboardAutoSyncState.value = sharedPrefs.getBoolean("clipboard_auto_sync", true)
            clipboardDirectionState.value = sharedPrefs.getString("clipboard_direction", "bidirectional") ?: "bidirectional"
        }
    }

    fun refreshAudioUi() {
        runOnUiThread {
            val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
            audioSyncEnabledState.value = sharedPrefs.getBoolean("audio_sync_enabled", true)
            audioDirectionState.value = sharedPrefs.getString("audio_direction", "desktop_to_mobile") ?: "desktop_to_mobile"
            audioPlaybackModeState.value = sharedPrefs.getString("audio_playback_mode", "destination_only") ?: "destination_only"
            wifiSpeakerActiveState.value = sharedPrefs.getBoolean("wifi_speaker_active", false)
        }
    }

    fun updateAudioConfig(direction: String, mode: String, active: Boolean) {
        runOnUiThread {
            audioDirectionState.value = direction
            audioPlaybackModeState.value = mode
            wifiSpeakerActiveState.value = active
            
            val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
            sharedPrefs.edit()
                .putString("audio_direction", direction)
                .putString("audio_playback_mode", mode)
                .putBoolean("wifi_speaker_active", active)
                .apply()
            
            ConnectionService.instance?.updateAudioConfigOnDaemon(direction, mode, active)
        }
    }

    private fun startIntegrationService() {
        val serviceIntent = Intent(this, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        // Add storage permissions for older Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
            return false
        }

        // Request MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${packageName}")
                    }
                    startActivity(intent)
                    return false
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                    return false
                }
            }
        }
        
        return true
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${packageName}")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
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

    private fun handleManualPairing(input: String) {
        try {
            val hostIp: String
            val hostPort: Int
            val hostName: String
            val pubKey: String

            if (input.trim().startsWith("{")) {
                val json = JSONObject(input)
                hostIp = json.getString("ip")
                hostPort = json.optInt("port", 8080)
                hostName = json.optString("name", "Desktop Daemon")
                pubKey = json.optString("pubkey", "")
            } else {
                val cleaned = input.trim()
                hostIp = cleaned.substringBefore(":")
                val remaining = cleaned.substringAfter(":", "8080")
                hostPort = remaining.substringBefore(" ").toIntOrNull() ?: 8080
                hostName = "Desktop Daemon"
                pubKey = ""
            }

            // Save details
            val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
            sharedPrefs.edit()
                .putString("paired_host_ip", hostIp)
                .putInt("paired_host_port", hostPort)
                .putString("paired_host_name", hostName)
                .apply()

            triggerServiceConnect(hostIp)
            Toast.makeText(this, "Connecting to $hostName ($hostIp)...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to parse connection parameters: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerServiceConnect(ip: String) {
        val serviceIntent = Intent(this, ConnectionService::class.java).apply {
            putExtra("CONNECT_IP", ip)
        }
        startService(serviceIntent)
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

    private fun syncClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("platypusd-sync", text)
        clipboard.setPrimaryClip(clip)

        val service = ConnectionService.instance
        if (service != null) {
            service.lastSyncedClipboardText = text
            service.relayClipboardState(text)
            Toast.makeText(this, "Clipboard sent to PC", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Clipboard saved locally (Offline)", Toast.LENGTH_SHORT).show()
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
        
        val url = "http://$ip:$port/api/v1/files/list?path=${URLEncoder.encode(path, "UTF-8")}"
        val request = okhttp3.Request.Builder().url(url).build()
        val client = okhttp3.OkHttpClient()
        
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to list files: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: "[]"
                        runOnUiThread {
                            val jsonArray = JSONArray(bodyStr)
                            pcFilesListState.clear()
                            for (i in 0 until jsonArray.length()) {
                                pcFilesListState.add(jsonArray.getJSONObject(i))
                            }
                            currentPcPathState.value = path
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

    private fun getMimeType(path: String): String? {
        val extension = path.substringAfterLast('.', "").lowercase()
        return if (extension.isNotEmpty()) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        } else {
            null
        }
    }

    private fun openLocalFile(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "com.platypus.platypusd.fileprovider",
                file
            )
            val mime = getMimeType(file.absolutePath) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "No application found to open this file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadPcFile(path: String, fileName: String, shouldOpen: Boolean = false, useCache: Boolean = false) {
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("paired_host_ip", null)
        val port = sharedPrefs.getInt("paired_host_port", 8080)
        
        if (ip == null) return
        
        isFileDownloadingState.value = true
        downloadProgressState.value = fileName
        
        val url = "http://$ip:$port/api/v1/files/download?path=${URLEncoder.encode(path, "UTF-8")}"
        val request = okhttp3.Request.Builder().url(url).build()
        val client = okhttp3.OkHttpClient()
        
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    isFileDownloadingState.value = false
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        runOnUiThread {
                            isFileDownloadingState.value = false
                        }
                        if (bytes != null) {
                            if (useCache) {
                                saveCacheFile(fileName, bytes)
                            } else {
                                saveDownloadedFile(fileName, bytes, shouldOpen)
                            }
                        }
                    } else {
                        runOnUiThread {
                            isFileDownloadingState.value = false
                            Toast.makeText(this@MainActivity, "Download server error: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun saveCacheFile(fileName: String, bytes: ByteArray) {
        try {
            val cacheFile = java.io.File(cacheDir, fileName)
            cacheFile.writeBytes(bytes)
            runOnUiThread {
                openLocalFile(cacheFile)
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Failed to preview file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveDownloadedFile(fileName: String, bytes: ByteArray, shouldOpen: Boolean) {
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val file = java.io.File(downloadsDir, fileName)
            file.writeBytes(bytes)
            runOnUiThread {
                Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                if (shouldOpen) {
                    openLocalFile(file)
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deletePcFile(path: String) {
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("paired_host_ip", null) ?: return
        val port = sharedPrefs.getInt("paired_host_port", 8080)
        
        val url = "http://$ip:$port/api/v1/files/delete?path=${URLEncoder.encode(path, "UTF-8")}"
        val request = okhttp3.Request.Builder().url(url).delete().build()
        val client = okhttp3.OkHttpClient()
        
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Delete failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Deleted successfully from PC", Toast.LENGTH_SHORT).show()
                    fetchPcFiles(currentPcPathState.value)
                }
            }
        })
    }

    private fun getProcessedPcFiles(): List<JSONObject> {
        val query = filesSearchQueryState.value.lowercase().trim()
        val hideHidden = filesHideHiddenState.value
        val filtered = pcFilesListState.filter {
            val name = it.optString("name", "")
            if (hideHidden && name.startsWith(".")) {
                false
            } else {
                query.isEmpty() || name.lowercase().contains(query)
            }
        }
        
        val sorted = when (filesSortByState.value) {
            "size" -> filtered.sortedBy { it.optLong("size", 0) }
            "date" -> filtered.sortedBy { it.optLong("last_modified", 0) }
            else -> filtered.sortedBy { it.optString("name", "").lowercase() }
        }
        
        return if (filesSortAscendingState.value) sorted else sorted.reversed()
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
            uploadLocalFile(uri)
        }
    }

    private fun uploadLocalFile(uri: Uri) {
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
            
            val targetPath = if (currentPcPathState.value.isEmpty()) "/home/saravana" else currentPcPathState.value
            val url = "http://$ip:$port/api/v1/files/upload?path=${URLEncoder.encode(targetPath, "UTF-8")}"
            val request = okhttp3.Request.Builder().url(url).post(requestBody).build()
            val client = okhttp3.OkHttpClient()
            
            Toast.makeText(this, "Uploading $fileName to PC...", Toast.LENGTH_SHORT).show()
            
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Uploaded successfully to PC!", Toast.LENGTH_SHORT).show()
                        fetchPcFiles(currentPcPathState.value)
                    }
                }
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // JETPACK COMPOSE UI IMPLEMENTATION
    // ==========================================

    @Composable
    fun MainAppScreen() {
        val currentTab by currentTabState
        
        Scaffold(
            topBar = { AppHeader() },
            bottomBar = { AppBottomNavigation() }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    }
                ) { tab ->
                    when (tab) {
                        "connection" -> ConnectionTabScreen()
                        "audio" -> AudioTabScreen()
                        "clipboard" -> ClipboardTabScreen()
                        "files" -> FilesTabScreen()
                        "settings" -> SettingsTabScreen()
                    }
                }
            }
        }

        // Global custom Dialog handler
        DialogManager()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppHeader() {
        val isDark by isDarkModeState
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)

        TopAppBar(
            title = {
                Text(
                    text = "Platypus Link",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            actions = {
                IconButton(onClick = {
                    val newMode = !isDark
                    isDarkModeState.value = newMode
                    sharedPrefs.edit().putBoolean("is_dark_mode", newMode).apply()
                }) {
                    Icon(
                        imageVector = if (isDark) Icons.Default.BrightnessHigh else Icons.Default.BrightnessLow,
                        contentDescription = "Toggle Light/Dark Theme",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }

    @Composable
    fun AppBottomNavigation() {
        val currentTab by currentTabState
        val items = listOf(
            Triple("connection", "Link", Icons.Default.Info),
            Triple("audio", "Audio", Icons.Default.VolumeUp),
            Triple("clipboard", "Clipboard", Icons.Default.Send),
            Triple("files", "Explorer", Icons.Default.Folder),
            Triple("settings", "Settings", Icons.Default.Settings)
        )

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            items.forEach { (tab, label, icon) ->
                NavigationBarItem(
                    selected = currentTab == tab,
                    onClick = {
                        currentTabState.value = tab
                        if (tab == "files") {
                            fetchPcFiles(currentPcPathState.value)
                        }
                    },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }

    // ==========================================
    // SCREEN 1: CONNECTION (LINK STATUS)
    // ==========================================
    @Composable
    fun ConnectionTabScreen() {
        val isConnected by isConnectedState
        val connectedHost by connectedHostState
        val bondedDevices = bondedDevicesState
        val selectedMac by selectedBtMacState
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Status Banner Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isConnected) "WI-FI LINK ONLINE" else "WI-FI LINK OFFLINE",
                        fontWeight = FontWeight.Bold,
                        color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isConnected) "Host: $connectedHost" else "Scanning for desktop hosts or scan pairing QR code.",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isConnected) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val serviceIntent = Intent(this@MainActivity, ConnectionService::class.java).apply {
                                    putExtra("DISCONNECT", true)
                                }
                                startService(serviceIntent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Disconnect Host")
                        }
                    }
                }
            }

            // QR Pairing Actions Card
            if (!isConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Desktop Pairing", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "Scan the connection QR code displayed inside the Platypus desktop application settings screen.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { startQrCodeScanner() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan QR")
                            }
                            OutlinedButton(
                                onClick = { activeDialogState.value = DialogType.ManualPairing },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pair Manually")
                            }
                        }
                    }
                }
            }

            // Bluetooth Card Section
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            val isBluetoothEnabled = btAdapter != null && btAdapter.isEnabled

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBluetoothEnabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Bluetooth Audio Gateway", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    
                    if (!isBluetoothEnabled) {
                        Text(
                            "Bluetooth is currently turned OFF. Please turn on Bluetooth to discover and pair your desktop host for call routing.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            "Route phone audio calls automatically through your PC gateway connection. Pair with your desktop host below.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (bondedDevices.isEmpty()) {
                            Text(
                                "No bonded bluetooth devices found. Check if your phone's Bluetooth is on and paired with the PC.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                bondedDevices.forEach { device ->
                                    val address = device.address
                                    val isSelected = address == selectedMac
                                    val isDeviceConnected = ConnectionService.instance?.isBluetoothDeviceConnected(address) ?: false
                                    
                                    val cardBg = if (isSelected) {
                                        if (isDarkModeState.value) Color(0xFF1B4D3E) else Color(0xFFE6F4EA)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(cardBg)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = device.name ?: "Unknown Device",
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp
                                            )
                                            Text(address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (isDeviceConnected) "LINK STATUS: CONNECTED" else "LINK STATUS: DISCONNECTED",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isDeviceConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        
                                        Button(
                                            onClick = {
                                                if (isSelected) {
                                                    sharedPrefs.edit()
                                                        .remove("selected_bluetooth_mac")
                                                        .remove("selected_bluetooth_name")
                                                        .apply()
                                                } else {
                                                    sharedPrefs.edit()
                                                        .putString("selected_bluetooth_mac", address)
                                                        .putString("selected_bluetooth_name", device.name ?: "Desktop PC")
                                                        .apply()
                                                }
                                                selectedBtMacState.value = sharedPrefs.getString("selected_bluetooth_mac", null)
                                                val serviceIntent = Intent(this@MainActivity, ConnectionService::class.java).apply {
                                                    putExtra("CHECK_BT", true)
                                                }
                                                startService(serviceIntent)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(if (isSelected) "DESELECT" else "SELECT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // SCREEN 1.5: AUDIO & SOUND SYNC
    // ==========================================
    @Composable
    fun AudioTabScreen() {
        val context = LocalContext.current
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        val isBtEnabled = btAdapter != null && btAdapter.isEnabled
        
        // Audio states
        val audioSyncEnabled = audioSyncEnabledState.value
        val audioDirection = audioDirectionState.value
        val isWifiActive = wifiSpeakerActiveState.value
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Audio & Sound Sync", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            // 1. Overall Master ON/OFF Switch
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Overall Audio Sync", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "Enable all audio sync & call routing features",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = audioSyncEnabled,
                        onCheckedChange = { checked ->
                            audioSyncEnabledState.value = checked
                            sharedPrefs.edit().putBoolean("audio_sync_enabled", checked).apply()
                            
                            val callsActive = checked && audioDirection == "mobile_to_desktop"
                            sharedPrefs.edit().putBoolean("calls_sync_enabled", callsActive).apply()
                            val serviceIntent = Intent(context, ConnectionService::class.java).apply {
                                putExtra("UPDATE_CALLS_SYNC", true)
                                putExtra("CALLS_SYNC_ENABLED", callsActive)
                            }
                            context.startService(serviceIntent)

                            if (!checked && isWifiActive) {
                                updateAudioConfig(audioDirection, audioPlaybackModeState.value, false)
                            } else {
                                ConnectionService.instance?.updateAudioConfigOnDaemon(
                                    audioDirection,
                                    audioPlaybackModeState.value,
                                    isWifiActive
                                )
                            }
                        }
                    )
                }
            }

            if (audioSyncEnabled) {
                // 2. Audio Flow Direction Radio Options Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Audio Flow Direction", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                updateAudioConfig("desktop_to_mobile", audioPlaybackModeState.value, isWifiActive)
                                sharedPrefs.edit().putBoolean("calls_sync_enabled", false).apply()
                                val serviceIntent = Intent(context, ConnectionService::class.java).apply {
                                    putExtra("UPDATE_CALLS_SYNC", true)
                                    putExtra("CALLS_SYNC_ENABLED", false)
                                }
                                context.startService(serviceIntent)
                                ConnectionService.instance?.updateAudioConfigOnDaemon(
                                    "desktop_to_mobile",
                                    audioPlaybackModeState.value,
                                    isWifiActive
                                )
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = audioDirection == "desktop_to_mobile",
                                onClick = {
                                    updateAudioConfig("desktop_to_mobile", audioPlaybackModeState.value, isWifiActive)
                                    sharedPrefs.edit().putBoolean("calls_sync_enabled", false).apply()
                                    val serviceIntent = Intent(context, ConnectionService::class.java).apply {
                                        putExtra("UPDATE_CALLS_SYNC", true)
                                        putExtra("CALLS_SYNC_ENABLED", false)
                                    }
                                    context.startService(serviceIntent)
                                    ConnectionService.instance?.updateAudioConfigOnDaemon(
                                        "desktop_to_mobile",
                                        audioPlaybackModeState.value,
                                        isWifiActive
                                    )
                                }
                            )
                            Text("Desktop to Mobile (Phone as Speaker)", fontSize = 13.sp)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                updateAudioConfig("mobile_to_desktop", audioPlaybackModeState.value, false)
                                sharedPrefs.edit().putBoolean("calls_sync_enabled", true).apply()
                                val serviceIntent = Intent(context, ConnectionService::class.java).apply {
                                    putExtra("UPDATE_CALLS_SYNC", true)
                                    putExtra("CALLS_SYNC_ENABLED", true)
                                }
                                context.startService(serviceIntent)
                                ConnectionService.instance?.updateAudioConfigOnDaemon(
                                    "mobile_to_desktop",
                                    audioPlaybackModeState.value,
                                    false
                                )
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = audioDirection == "mobile_to_desktop",
                                onClick = {
                                    updateAudioConfig("mobile_to_desktop", audioPlaybackModeState.value, false)
                                    sharedPrefs.edit().putBoolean("calls_sync_enabled", true).apply()
                                    val serviceIntent = Intent(context, ConnectionService::class.java).apply {
                                        putExtra("UPDATE_CALLS_SYNC", true)
                                        putExtra("CALLS_SYNC_ENABLED", true)
                                    }
                                    context.startService(serviceIntent)
                                    ConnectionService.instance?.updateAudioConfigOnDaemon(
                                        "mobile_to_desktop",
                                        audioPlaybackModeState.value,
                                        false
                                    )
                                }
                            )
                            Text("Mobile to Desktop (PC as Speaker/Mic)", fontSize = 13.sp)
                        }
                    }
                }

                // 3. Details Card based on Direction
                if (audioDirection == "desktop_to_mobile") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isWifiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Wi-Fi Speaker Sync", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("Play laptop sound lossless over Wi-Fi", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    imageVector = if (isWifiActive) Icons.Default.MusicNote else Icons.Default.MusicOff,
                                    contentDescription = null,
                                    tint = if (isWifiActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Status:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = if (isWifiActive) "Active (Streaming)" else "Idle (Standby)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isWifiActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Quality:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("48.0 kHz Stereo (PCM 16-bit)", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Port:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("UDP Port 9095", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Playback Mode selection
                            Text("Playback Options", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        updateAudioConfig(audioDirection, "destination_only", isWifiActive)
                                        ConnectionService.instance?.updateAudioConfigOnDaemon(
                                            audioDirection,
                                            "destination_only",
                                            isWifiActive
                                        )
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = audioPlaybackModeState.value == "destination_only",
                                        onClick = {
                                            updateAudioConfig(audioDirection, "destination_only", isWifiActive)
                                            ConnectionService.instance?.updateAudioConfigOnDaemon(
                                                audioDirection,
                                                "destination_only",
                                                isWifiActive
                                            )
                                        }
                                    )
                                    Text("Play on Destination Device Only (Mute laptop)", fontSize = 12.sp)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        updateAudioConfig(audioDirection, "both", isWifiActive)
                                        ConnectionService.instance?.updateAudioConfigOnDaemon(
                                            audioDirection,
                                            "both",
                                            isWifiActive
                                        )
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = audioPlaybackModeState.value == "both",
                                        onClick = {
                                            updateAudioConfig(audioDirection, "both", isWifiActive)
                                            ConnectionService.instance?.updateAudioConfigOnDaemon(
                                                audioDirection,
                                                "both",
                                                isWifiActive
                                            )
                                        }
                                    )
                                    Text("Play on Both Devices simultaneously", fontSize = 12.sp)
                                }
                            }

                            // Start / Stop button
                            Button(
                                onClick = {
                                    val nextActive = !isWifiActive
                                    updateAudioConfig(audioDirection, audioPlaybackModeState.value, nextActive)
                                    ConnectionService.instance?.updateAudioConfigOnDaemon(
                                        audioDirection,
                                        audioPlaybackModeState.value,
                                        nextActive
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isWifiActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(if (isWifiActive) "Stop Sync" else "Start Sync")
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isBtEnabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Call Audio Routing Gateway", fontWeight = FontWeight.Bold, fontSize = 15.sp)

                            if (!isBtEnabled) {
                                Text(
                                    "Bluetooth is turned OFF. Call audio routing requires Bluetooth connection.",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Could not open Bluetooth settings", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Turn on Bluetooth")
                                }
                            } else {
                                val selectedName = sharedPrefs.getString("selected_bluetooth_name", "None")
                                val selectedMac = sharedPrefs.getString("selected_bluetooth_mac", null)
                                val isDeviceConnected = if (selectedMac != null) {
                                    ConnectionService.instance?.isBluetoothDeviceConnected(selectedMac) ?: false
                                } else false

                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Paired PC Gateway:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(selectedName ?: "None", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Gateway Connection:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = if (isDeviceConnected) "Connected" else "Disconnected",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDeviceConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeMute,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Audio Sync Disabled",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Enable Overall Audio Sync switch above to configure sound sharing flows.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // ==========================================
    // SCREEN 2: CLIPBOARD SYNC
    // ==========================================
    @Composable
    fun ClipboardTabScreen() {
        val clipboardText by sharedClipboardTextState
        var inputText by clipboardInputTextState

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Clipboard Synchronization", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ACTIVE PHONE CLIPBOARD", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = clipboardText.ifEmpty { "Empty clipboard history." },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text("Push Text to Desktop", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            OutlinedTextField(
                value = inputText,
                onValueChange = { clipboardInputTextState.value = it },
                label = { Text("Message details to send...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                minLines = 4
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (inputText.isNotEmpty()) {
                            syncClipboard(inputText)
                            clipboardInputTextState.value = ""
                        }
                    },
                    modifier = Modifier.weight(1.5f),
                    enabled = inputText.isNotEmpty()
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send to PC")
                }
                
                OutlinedButton(
                    onClick = { clipboardInputTextState.value = "" },
                    modifier = Modifier.weight(1f),
                    enabled = inputText.isNotEmpty()
                ) {
                    Text("Clear")
                }
            }
        }
    }

    // ==========================================
    // SCREEN 3: FILE EXPLORER
    // ==========================================
    @Composable
    fun FilesTabScreen() {
        val currentPath by currentPcPathState
        val isConnected by isConnectedState
        var query by filesSearchQueryState
        val sortBy by filesSortByState
        val sortAscending by filesSortAscendingState

        val processedFiles = remember(pcFilesListState.size, query, sortBy, sortAscending, filesHideHiddenState.value) {
            getProcessedPcFiles()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            val isStorageGranted by isStoragePermissionGrantedState
            if (!isStorageGranted) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "⚠️ Storage Access Required",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Platypus needs All Files Access permission to show and share files with your PC.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { requestStoragePermission() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text("Grant Permission", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Path Navigation Header Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentPath.isNotEmpty() && currentPath != "/") {
                        IconButton(onClick = {
                            val parent = currentPath.substringBeforeLast("/")
                            fetchPcFiles(parent.ifEmpty { "/" })
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Go Back")
                        }
                    }
                    Text(
                        text = currentPath.ifEmpty { "PC Home Directory" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { fetchPcFiles(currentPath) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Files")
                    }
                }
            }

            // Explorer Actions bar - Row 1 (Search + Upload)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { filesSearchQueryState.value = it },
                    placeholder = { Text("Filter files...", fontSize = 13.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                        }
                        startActivityForResult(Intent.createChooser(intent, "Select File to Upload"), PICK_FILE_REQUEST_CODE)
                    },
                    modifier = Modifier.height(48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Upload", fontSize = 13.sp)
                }
            }

            // Explorer Actions bar - Row 2 (Layout + Options + Sort)
            if (isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Layout Mode Selector (List, Grid, Compact)
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val currentMode = filesLayoutState.value
                        IconButton(
                            onClick = { filesLayoutState.value = "list" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = "List View",
                                tint = if (currentMode == "list") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { filesLayoutState.value = "grid" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridOn,
                                contentDescription = "Grid View",
                                tint = if (currentMode == "grid") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { filesLayoutState.value = "compact" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Compact View",
                                tint = if (currentMode == "compact") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Sort and Hidden Options Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Hide Hidden Switch as a compact filter chip style
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { filesHideHiddenState.value = !filesHideHiddenState.value }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Hide Hidden", 
                                fontSize = 11.sp, 
                                fontWeight = FontWeight.Medium,
                                color = if (filesHideHiddenState.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Checkbox(
                                checked = filesHideHiddenState.value,
                                onCheckedChange = { filesHideHiddenState.value = it },
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Sort dropdown box
                        var sortMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { sortMenuExpanded = true },
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .size(36.dp)
                            ) {
                                Icon(
                                    imageVector = when (sortBy) {
                                        "size" -> Icons.Default.SortByAlpha
                                        "date" -> Icons.Default.Schedule
                                        else -> Icons.Default.Sort
                                    },
                                    contentDescription = "Sort Options",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sort by Name") },
                                    onClick = {
                                        filesSortByState.value = "name"
                                        sortMenuExpanded = false
                                    },
                                    leadingIcon = { if (sortBy == "name") Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by Size") },
                                    onClick = {
                                        filesSortByState.value = "size"
                                        sortMenuExpanded = false
                                    },
                                    leadingIcon = { if (sortBy == "size") Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort by Date") },
                                    onClick = {
                                        filesSortByState.value = "date"
                                        sortMenuExpanded = false
                                    },
                                    leadingIcon = { if (sortBy == "date") Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Ascending") },
                                    onClick = {
                                        filesSortAscendingState.value = true
                                        sortMenuExpanded = false
                                    },
                                    leadingIcon = { if (sortAscending) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Descending") },
                                    onClick = {
                                        filesSortAscendingState.value = false
                                        sortMenuExpanded = false
                                    },
                                    leadingIcon = { if (!sortAscending) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) }
                                )
                            }
                        }
                    }
                }
            }

            // Files Listing
            if (!isConnected) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Connect to PC host to view files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (processedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { fetchPcFiles(currentPath) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (pcFilesListState.isEmpty()) "Tap to load PC file directory list." else "No files matched query.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val layoutMode = filesLayoutState.value
                if (layoutMode == "grid") {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    ) {
                        items(processedFiles.size) { index ->
                            val item = processedFiles[index]
                            val name = item.getString("name")
                            val isDir = item.getBoolean("is_dir")
                            val path = item.getString("path")
                            
                            Card(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            fetchPcFiles(path)
                                        } else {
                                            downloadPcFile(path, name, shouldOpen = true, useCache = true)
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = name,
                                            fontWeight = if (isDir) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.height(32.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            activeDialogState.value = DialogType.FileActions(item)
                                        },
                                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp).padding(4.dp)
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                } else if (layoutMode == "compact") {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(processedFiles) { item ->
                            val name = item.getString("name")
                            val isDir = item.getBoolean("is_dir")
                            val path = item.getString("path")
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            fetchPcFiles(path)
                                        } else {
                                            downloadPcFile(path, name, shouldOpen = true, useCache = true)
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = name,
                                    fontWeight = if (isDir) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = {
                                        activeDialogState.value = DialogType.FileActions(item)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(16.dp))
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                } else { // "list"
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(processedFiles) { item ->
                            val name = item.getString("name")
                            val isDir = item.getBoolean("is_dir")
                            val path = item.getString("path")
                            val size = item.getLong("size")
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            fetchPcFiles(path)
                                        } else {
                                            downloadPcFile(path, name, shouldOpen = true, useCache = true)
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = name,
                                        fontWeight = if (isDir) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!isDir) {
                                        val sizeKb = size / 1024
                                        Text("$sizeKb KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = {
                                    activeDialogState.value = DialogType.FileActions(item)
                                }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // SCREEN 4: SETTINGS (MATERIAL 3 TOGGLES)
    // ==========================================
    @Composable
    fun SettingsTabScreen() {
        var autoSync by clipboardAutoSyncState
        var direction by clipboardDirectionState
        val sharedPrefs = getSharedPreferences("platypusd_prefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("paired_host_ip", "127.0.0.1")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Synchronization Configurations", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            // Clipboard Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Clipboard Options", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Automatic Clipboard Sync", fontSize = 14.sp)
                        Switch(
                            checked = autoSync,
                            onCheckedChange = { checked ->
                                autoSync = checked
                                saveClipboardConfig(direction, checked)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sync Direction Flow:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alpha(if (autoSync) 1.0f else 0.4f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Custom Segmented Button Flow in Compose (Horizontal button group)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (autoSync) 1.0f else 0.4f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val options = listOf(
                            "bidirectional" to "Bidirectional",
                            "desktop_to_mobile" to "Desktop to Mobile",
                            "mobile_to_desktop" to "Mobile to Desktop"
                        )

                        options.forEach { (key, label) ->
                            val isSelected = direction == key
                            Button(
                                onClick = {
                                    if (autoSync) {
                                        direction = key
                                        saveClipboardConfig(key, autoSync)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = autoSync,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    contentColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                ),
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                                border = if (isSelected) null else borderStrokeStyle()
                            ) {
                                Text(
                                    text = label, 
                                    fontSize = 9.sp, 
                                    fontWeight = FontWeight.Bold, 
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // Phone Call Settings Card
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            val isBtEnabled = btAdapter != null && btAdapter.isEnabled

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBtEnabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Call Options", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!isBtEnabled) {
                        Text(
                            text = "Bluetooth is turned off. Please turn on Bluetooth to configure call settings.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Phone Calls", fontSize = 14.sp)
                            Text("Requires Bluetooth pairing & permissions", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        var callsEnabled by remember {
                            mutableStateOf(sharedPrefs.getBoolean("calls_sync_enabled", true))
                        }
                        
                        Switch(
                            checked = callsEnabled && isBtEnabled,
                            enabled = isBtEnabled,
                            onCheckedChange = { checked ->
                                callsEnabled = checked
                                sharedPrefs.edit().putBoolean("calls_sync_enabled", checked).apply()
                                
                                val serviceIntent = Intent(this@MainActivity, ConnectionService::class.java).apply {
                                    putExtra("UPDATE_CALLS_SYNC", true)
                                    putExtra("CALLS_SYNC_ENABLED", checked)
                                }
                                startService(serviceIntent)
                            }
                        )
                    }
                }
            }

            // Theme Preferences Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("System Preferences", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Theme Color Palette:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val palettes = listOf(
                        "auto" to "Auto",
                        "purple" to "Violent Violet",
                        "blue" to "Depressed Denim",
                        "green" to "Grumpy Guacamole",
                        "red" to "Spicy Salsa"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            palettes.subList(0, 3).forEach { (id, name) ->
                                val isSelected = themePaletteState.value == id
                                val palColor = when (id) {
                                    "purple" -> Color(0xFF6750A4)
                                    "blue" -> Color(0xFF0F52BA)
                                    "green" -> Color(0xFF386A20)
                                    "red" -> Color(0xFFBA1A1A)
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Button(
                                    onClick = {
                                        themePaletteState.value = id
                                        sharedPrefs.edit().putString("theme_palette", id).apply()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) palColor else MaterialTheme.colorScheme.surface,
                                        contentColor = if (isSelected) Color.White else palColor
                                    ),
                                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, palColor)
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            palettes.subList(3, 5).forEach { (id, name) ->
                                val isSelected = themePaletteState.value == id
                                val palColor = when (id) {
                                    "purple" -> Color(0xFF6750A4)
                                    "blue" -> Color(0xFF0F52BA)
                                    "green" -> Color(0xFF386A20)
                                    "red" -> Color(0xFFBA1A1A)
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Button(
                                    onClick = {
                                        themePaletteState.value = id
                                        sharedPrefs.edit().putString("theme_palette", id).apply()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) palColor else MaterialTheme.colorScheme.surface,
                                        contentColor = if (isSelected) Color.White else palColor
                                    ),
                                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, palColor)
                                ) {
                                    Text(
                                        text = name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Call sync Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Phone Status Alerts", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Mirror live call state events (Ringing, Connected, Muted) immediately to the desktop notification layer when active.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Enable Call State Mirroring", fontSize = 14.sp)
                        Switch(
                            checked = true,
                            onCheckedChange = {
                                Toast.makeText(this@MainActivity, "Phone Call Synchronization is required for core link functionality.", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // Wi-Fi details Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Network Information", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Paired Desktop Host IP: $ip", fontSize = 13.sp)
                    Text("Daemon Service Port: 8080", fontSize = 13.sp)
                    Text("Ensure both local devices reside on the same Wi-Fi subnet domain for discoverability.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    fun borderStrokeStyle() = androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )

    // ==========================================
    // DIALOGS & SHEET MODALS MANAGER
    // ==========================================
    @Composable
    fun DialogManager() {
        var activeDialog by activeDialogState

        if (isFileDownloadingState.value) {
            androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Retrieving file from PC...",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = downloadProgressState.value,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        when (val dialog = activeDialog) {
            is DialogType.ManualPairing -> {
                var ipInput by remember { mutableStateOf("") }
                
                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    title = { Text("Manual Pairing Host") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Provide the IP address of your desktop client:")
                            OutlinedTextField(
                                value = ipInput,
                                onValueChange = { ipInput = it },
                                placeholder = { Text("192.168.1.112") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (ipInput.isNotEmpty()) {
                                    handleManualPairing(ipInput)
                                    activeDialog = null
                                }
                            }
                        ) {
                            Text("Pair Host")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeDialog = null }) { Text("Cancel") }
                    }
                )
            }
            is DialogType.FileDetails -> {
                val dateStr = remember(dialog.lastModified) {
                    if (dialog.lastModified > 0) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        sdf.format(Date(dialog.lastModified * 1000))
                    } else "Unknown"
                }
                val sizeStr = if (dialog.isDir) "Directory Folder" else "${dialog.size / 1024} KB (${dialog.size} bytes)"

                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    title = { Text("File Attributes") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Name: ${dialog.name}", fontWeight = FontWeight.Bold)
                            Text("Location: ${dialog.path}")
                            Text("Size: $sizeStr")
                            Text("Last Modified: $dateStr")
                        }
                    },
                    confirmButton = {
                        Button(onClick = { activeDialog = null }) { Text("Close") }
                    }
                )
            }
            is DialogType.FileActions -> {
                val item = dialog.json
                val name = item.getString("name")
                val path = item.getString("path")
                val size = item.optLong("size", 0)
                val isDir = item.optBoolean("is_dir", false)
                val lastModified = item.optLong("last_modified", 0)

                AlertDialog(
                    onDismissRequest = { activeDialog = null },
                    title = { Text("File Options") },
                    text = { Text("Perform actions on the selected file '$name':") },
                    confirmButton = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (!isDir) {
                                Button(
                                    onClick = {
                                        downloadPcFile(path, name)
                                        activeDialog = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download to Mobile")
                                }
                            }
                            
                            Button(
                                onClick = {
                                    activeDialog = DialogType.FileDetails(
                                        name = name,
                                        path = path,
                                        size = size,
                                        isDir = isDir,
                                        lastModified = lastModified
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View Properties")
                            }

                            Button(
                                onClick = {
                                    deletePcFile(path)
                                    activeDialog = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Delete from PC")
                            }
                            
                            OutlinedButton(
                                onClick = { activeDialog = null },
                                modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Cancel")
                                }
                            }
                        },
                        dismissButton = {} // Actions stacked vertically in confirmButton
                    )
                }
            null -> {}
        }
    }
}

// ==========================================
// THEME & COLOR SCHEMES
// ==========================================
@Composable
fun PlatypusTheme(
    darkTheme: Boolean = true,
    palette: String = "auto",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        palette == "auto" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        palette == "purple" || (palette == "auto" && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) -> {
            if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFFD0BCFF), // M3 Pastel Violet Accent
                    onPrimary = Color(0xFF381E72),
                    background = Color(0xFF0F0E13), // Deep Purple Tint
                    surface = Color(0xFF25232A),
                    surfaceVariant = Color(0xFF1D1B20),
                    onBackground = Color(0xFFE6E1E5),
                    onSurface = Color(0xFFE6E1E5),
                    surfaceContainer = Color(0xFF1D1B20),
                    surfaceContainerHigh = Color(0xFF322F37),
                    outlineVariant = Color(0xFF49454F)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF6750A4), // M3 Deep Purple Accent
                    onPrimary = Color.White,
                    background = Color(0xFFFBF8FD), // Light Lavender Tint
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFF3EDF7),
                    onBackground = Color(0xFF1D1B20),
                    onSurface = Color(0xFF1D1B20),
                    surfaceContainer = Color(0xFFF3EDF7),
                    surfaceContainerHigh = Color(0xFFEADBFF),
                    outlineVariant = Color(0xFFE6E0E9)
                )
            }
        }
        palette == "blue" -> {
            if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFFA8C7FA),
                    onPrimary = Color(0xFF062E6F),
                    background = Color(0xFF0B0D11),
                    surface = Color(0xFF181B21),
                    surfaceVariant = Color(0xFF13161C),
                    onBackground = Color(0xFFE6E1E5),
                    onSurface = Color(0xFFE6E1E5),
                    surfaceContainer = Color(0xFF13161C),
                    surfaceContainerHigh = Color(0xFF1D212A),
                    outlineVariant = Color(0xFF2F333A)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF0F52BA),
                    onPrimary = Color.White,
                    background = Color(0xFFF8F9FF),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFEDF0F9),
                    onBackground = Color(0xFF1B1B1F),
                    onSurface = Color(0xFF1B1B1F),
                    surfaceContainer = Color(0xFFEDF0F9),
                    surfaceContainerHigh = Color(0xFFDBE3F8),
                    outlineVariant = Color(0xFFDBE3F8)
                )
            }
        }
        palette == "green" -> {
            if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF9CD67D),
                    onPrimary = Color(0xFF0B3900),
                    background = Color(0xFF0F100E),
                    surface = Color(0xFF252822),
                    surfaceVariant = Color(0xFF1D1F1B),
                    onBackground = Color(0xFFE2E3DD),
                    onSurface = Color(0xFFE2E3DD),
                    surfaceContainer = Color(0xFF1D1F1B),
                    surfaceContainerHigh = Color(0xFF2D3228),
                    outlineVariant = Color(0xFF43483F)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF386A20),
                    onPrimary = Color.White,
                    background = Color(0xFFF6FBF2),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFEEF4E8),
                    onBackground = Color(0xFF191C18),
                    onSurface = Color(0xFF191C18),
                    surfaceContainer = Color(0xFFEEF4E8),
                    surfaceContainerHigh = Color(0xFFE0E4DB),
                    outlineVariant = Color(0xFFE0E4DB)
                )
            }
        }
        else -> { // red
            if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFFFFB4AB),
                    onPrimary = Color(0xFF690005),
                    background = Color(0xFF140F0E),
                    surface = Color(0xFF2F2120),
                    surfaceVariant = Color(0xFF261B1A),
                    onBackground = Color(0xFFEDE0DE),
                    onSurface = Color(0xFFEDE0DE),
                    surfaceContainer = Color(0xFF261B1A),
                    surfaceContainerHigh = Color(0xFF3B2A28),
                    outlineVariant = Color(0xFF534341)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFFBA1A1A),
                    onPrimary = Color.White,
                    background = Color(0xFFFFF5F5),
                    surface = Color(0xFFFFFFFF),
                    surfaceVariant = Color(0xFFFCEAE9),
                    onBackground = Color(0xFF201A19),
                    onSurface = Color(0xFF201A19),
                    surfaceContainer = Color(0xFFFCEAE9),
                    surfaceContainerHigh = Color(0xFFF5DAD7),
                    outlineVariant = Color(0xFFF5DAD7)
                )
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
