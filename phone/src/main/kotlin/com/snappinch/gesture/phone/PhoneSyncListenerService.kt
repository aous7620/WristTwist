package com.snappinch.gesture.phone

import android.util.Log
import android.os.SystemClock
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class PhoneSyncListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneSyncListener"
        private const val DUPLICATE_WINDOW_MS = 900L
        private const val SETTINGS_REQUEST_COOLDOWN_MS = 1200L
        const val ACTION_PHONE_SETTINGS_CHANGED = "com.snappinch.gesture.phone.PHONE_SETTINGS_CHANGED"
        @Volatile
        private var lastHandledTs: Long = 0L
        @Volatile
        private var lastHandledAction: String = ""
        @Volatile
        private var lastHandledElapsedMs: Long = 0L
        @Volatile
        private var lastSettingsRequestElapsedMs: Long = 0L
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            WearSyncProtocol.PATH_MEDIA_COMMAND -> {
                val decoded = runCatching { WearSyncProtocol.decodeMap(messageEvent.data) }.getOrDefault(emptyMap())
                val action = decoded["action"] ?: messageEvent.data.toString(Charsets.UTF_8)
                val ts = decoded["ts"]?.toLongOrNull() ?: 0L
                if (!shouldHandle(action, ts)) {
                    Log.d(TAG, "Skipping duplicate media command ts=$ts")
                    return
                }
                try {
                    PhoneMediaExecutor.executeAction(this, action)
                    Log.d(TAG, "Executed watch command action=$action ts=$ts")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to execute watch command action=$action", e)
                }
            }

            WearSyncProtocol.PATH_SETTINGS_REQUEST -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSettingsRequestElapsedMs < SETTINGS_REQUEST_COOLDOWN_MS) {
                    Log.d(TAG, "Skipping duplicate settings request")
                    return
                }
                lastSettingsRequestElapsedMs = now
                try {
                    PhoneMediaExecutor.syncSettingsToWatchAsync(this)
                    Log.d(TAG, "Processed watch settings request")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed responding to settings request", e)
                }
            }

            WearSyncProtocol.PATH_WATCH_SETTINGS_UPDATE -> {
                val payload = runCatching { WearSyncProtocol.decodeMap(messageEvent.data) }.getOrDefault(emptyMap())
                if (payload.isEmpty()) {
                    return
                }
                var changed = false
                payload["primary_action"]?.let { action ->
                    if (ControlPreferences.getAllActions().contains(action) &&
                        ControlPreferences.getPrimaryAction(this) != action
                    ) {
                        ControlPreferences.setPrimaryAction(this, action)
                        changed = true
                    }
                }
                payload["control_enabled"]?.toBooleanStrictOrNull()?.let { enabled ->
                    if (ControlPreferences.isControlEnabled(this) != enabled) {
                        ControlPreferences.setControlEnabled(this, enabled)
                        changed = true
                    }
                }
                if (changed) {
                    sendBroadcast(
                        android.content.Intent(ACTION_PHONE_SETTINGS_CHANGED).apply {
                            setPackage(packageName)
                        }
                    )
                    PhoneMediaExecutor.syncSettingsToWatchAsync(this)
                    Log.d(TAG, "Applied watch settings update payload=$payload")
                }
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearSyncProtocol.PATH_MEDIA_COMMAND) continue
            try {
                val dataMap = DataMapItem.fromDataItem(item).dataMap
                val action = dataMap.getString("action") ?: continue
                val ts = dataMap.getLong("ts", 0L)
                if (!shouldHandle(action, ts)) {
                    Log.d(TAG, "Skipping duplicate media data item ts=$ts")
                    continue
                }
                PhoneMediaExecutor.executeAction(this, action)
                Log.d(TAG, "Executed data item watch command action=$action ts=$ts")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process media data item", e)
            }
        }
    }

    private fun shouldHandle(action: String, ts: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(PhoneSyncListenerService::class.java) {
            val sameAction = action == lastHandledAction
            if (sameAction && now - lastHandledElapsedMs < DUPLICATE_WINDOW_MS) {
                return false
            }
            if (ts > 0L && sameAction && ts <= lastHandledTs) {
                return false
            }
            lastHandledTs = ts
            lastHandledAction = action
            lastHandledElapsedMs = now
            return true
        }
    }
}
