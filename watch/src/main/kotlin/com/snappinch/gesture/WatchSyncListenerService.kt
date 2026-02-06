package com.snappinch.gesture

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WatchSyncListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WatchSyncListener"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path != WearSyncProtocol.PATH_SETTINGS_SYNC) {
            return
        }

        try {
            val settings = WearSyncProtocol.decodeMap(messageEvent.data)
            if (settings.isEmpty()) {
                Log.d(TAG, "Received empty settings payload")
                return
            }
            ActionPreferences.applyPhoneSyncedSettings(this, settings)
            sendBroadcast(
                android.content.Intent(OnePlusGestureService.ACTION_TOGGLE_PAUSE).apply {
                    setPackage(packageName)
                }
            )
            Log.i(TAG, "Applied synced settings from phone")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply synced settings", e)
        }
    }
}
