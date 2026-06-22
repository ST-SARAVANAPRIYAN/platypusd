package com.platypus.platypusd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Private Number"
        
        Log.i("CallReceiver", "Phone Call Event Received: State = $stateStr, Number = $incomingNumber")

        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> "Ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "Connected"
            TelephonyManager.EXTRA_STATE_IDLE -> "Disconnected"
            else -> return
        }

        // Generate a stable call ID for this session (simulating Telecom APIs)
        val callId = "call-${incomingNumber.hashCode()}"

        val contactName = getContactName(context, incomingNumber)

        // Forward to running service to perform HTTP upload
        ConnectionService.updateCallState(callId, incomingNumber, contactName, state)
    }

    private fun getContactName(context: Context, phoneNumber: String): String {
        if (phoneNumber == "Private Number" || phoneNumber.isEmpty()) return "Unknown Caller"
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Incoming: $phoneNumber"
        }
        
        try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, 
                android.net.Uri.encode(phoneNumber)
            )
            val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CallReceiver", "Error querying contacts: ${e.message}")
        }
        
        return "Incoming: $phoneNumber"
    }
}
