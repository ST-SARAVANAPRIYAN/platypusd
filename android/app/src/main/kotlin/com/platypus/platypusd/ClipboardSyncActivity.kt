package com.platypus.platypusd

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import android.util.Log

class ClipboardSyncActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val clipText = clipData.getItemAt(0).text?.toString() ?: ""
                val trimmedText = clipText.trim()
                if (trimmedText.isNotEmpty()) {
                    ConnectionService.instance?.relayClipboardState(clipText)
                    Toast.makeText(this, "Clipboard synced to PC!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ClipboardSyncActivity", "Failed to sync clipboard: ${e.message}")
            Toast.makeText(this, "Failed to sync clipboard: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
