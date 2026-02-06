package com.snappinch.gesture.phone

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log

class PhoneNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PhoneNotifListener"

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, PhoneNotificationListener::class.java)
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledListeners)
            while (splitter.hasNext()) {
                val enabled = ComponentName.unflattenFromString(splitter.next())
                if (enabled != null && enabled == expectedComponent) {
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
}
