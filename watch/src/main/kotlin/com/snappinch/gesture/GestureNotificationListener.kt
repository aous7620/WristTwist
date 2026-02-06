package com.snappinch.gesture

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log

class GestureNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "GestureNotifListener"

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, GestureNotificationListener::class.java)
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )

            if (enabledListeners.isNullOrBlank()) {
                return false
            }

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledListeners)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponent) {
                    return true
                }
            }
            return false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener disconnected")
    }
}
