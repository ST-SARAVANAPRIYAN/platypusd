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

        // Query contact name logic could go here; for now, we pass the phone number as name or stub it.
        val contactName = if (incomingNumber.startsWith("+")) "Incoming: $incomingNumber" else "Unknown Caller"

        // Forward to running service to perform HTTP upload
        ConnectionService.updateCallState(callId, incomingNumber, contactName, state)
    }
}
