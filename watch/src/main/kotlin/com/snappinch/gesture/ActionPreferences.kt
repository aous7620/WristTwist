package com.snappinch.gesture

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user preferences for gesture actions and media-control behavior.
 */
object ActionPreferences {
    private const val PREFS_NAME = "snappinch_prefs"
    private const val KEY_ACTION = "gesture_action"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_PREFER_EXPLICIT_PLAY_PAUSE = "prefer_explicit_play_pause"
    private const val KEY_ALLOW_PROXY_SCREEN_OFF = "allow_proxy_screen_off"
    private const val KEY_ENABLE_OFFSCREEN_RETRY = "enable_offscreen_retry"
    private const val KEY_OFFSCREEN_RETRY_DELAY_MS = "offscreen_retry_delay_ms"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_ROUTE_MEDIA_TO_PHONE = "route_media_to_phone"
    private const val KEY_LAST_PHONE_SYNC_TS = "last_phone_sync_ts"

    private const val DEFAULT_OFFSCREEN_RETRY_DELAY_MS = 220

    // Available actions
    const val ACTION_PLAY_PAUSE = "play_pause" // Default
    const val ACTION_BACK = "back"
    const val ACTION_HOME = "home"
    const val ACTION_RECENTS = "recents"
    const val ACTION_NEXT_TRACK = "next_track"
    const val ACTION_PREV_TRACK = "prev_track"
    const val ACTION_VOLUME_UP = "volume_up"
    const val ACTION_VOLUME_DOWN = "volume_down"
    const val ACTION_MUTE = "mute"
    const val ACTION_NOTIFICATIONS = "notifications"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedAction(context: Context): String {
        return getPrefs(context).getString(KEY_ACTION, ACTION_PLAY_PAUSE) ?: ACTION_PLAY_PAUSE
    }

    fun setSelectedAction(context: Context, action: String) {
        getPrefs(context).edit().putString(KEY_ACTION, action).apply()
    }

    fun isServiceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SERVICE_ENABLED, true)
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun preferExplicitPlayPauseKey(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PREFER_EXPLICIT_PLAY_PAUSE, true)
    }

    fun setPreferExplicitPlayPauseKey(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PREFER_EXPLICIT_PLAY_PAUSE, enabled).apply()
    }

    fun allowProxyControlWhenScreenOff(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALLOW_PROXY_SCREEN_OFF, false)
    }

    fun setAllowProxyControlWhenScreenOff(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALLOW_PROXY_SCREEN_OFF, enabled).apply()
    }

    fun isOffscreenRetryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLE_OFFSCREEN_RETRY, true)
    }

    fun setOffscreenRetryEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLE_OFFSCREEN_RETRY, enabled).apply()
    }

    fun getOffscreenRetryDelayMs(context: Context): Int {
        return getPrefs(context).getInt(KEY_OFFSCREEN_RETRY_DELAY_MS, DEFAULT_OFFSCREEN_RETRY_DELAY_MS)
    }

    fun setOffscreenRetryDelayMs(context: Context, delayMs: Int) {
        getPrefs(context).edit()
            .putInt(KEY_OFFSCREEN_RETRY_DELAY_MS, delayMs.coerceIn(120, 1000))
            .apply()
    }

    fun isHapticsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTICS_ENABLED, true)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
    }

    fun shouldRouteMediaToPhone(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ROUTE_MEDIA_TO_PHONE, true)
    }

    fun setRouteMediaToPhone(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ROUTE_MEDIA_TO_PHONE, enabled).apply()
    }

    fun getLastPhoneSyncTs(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_PHONE_SYNC_TS, 0L)
    }

    fun applyPhoneSyncedSettings(context: Context, payload: Map<String, String>) {
        val editor = getPrefs(context).edit()

        payload["primary_action"]?.let { primary ->
            val mappedAction = when (primary) {
                WearSyncProtocol.ACTION_PLAY_PAUSE -> ACTION_PLAY_PAUSE
                WearSyncProtocol.ACTION_NEXT -> ACTION_NEXT_TRACK
                WearSyncProtocol.ACTION_PREVIOUS -> ACTION_PREV_TRACK
                WearSyncProtocol.ACTION_VOLUME_UP -> ACTION_VOLUME_UP
                WearSyncProtocol.ACTION_VOLUME_DOWN -> ACTION_VOLUME_DOWN
                WearSyncProtocol.ACTION_MUTE -> ACTION_MUTE
                else -> ACTION_PLAY_PAUSE
            }
            editor.putString(KEY_ACTION, mappedAction)
        }

        payload["prefer_explicit_play_pause"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(KEY_PREFER_EXPLICIT_PLAY_PAUSE, it)
        }
        payload["allow_proxy_screen_off"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(KEY_ALLOW_PROXY_SCREEN_OFF, it)
        }
        payload["enable_offscreen_retry"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(KEY_ENABLE_OFFSCREEN_RETRY, it)
        }
        payload["offscreen_retry_delay_ms"]?.toIntOrNull()?.let {
            editor.putInt(KEY_OFFSCREEN_RETRY_DELAY_MS, it.coerceIn(120, 1000))
        }
        payload["haptics_enabled"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(KEY_HAPTICS_ENABLED, it)
        }
        payload["route_media_to_phone"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(KEY_ROUTE_MEDIA_TO_PHONE, it)
        }
        payload["control_enabled"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(KEY_SERVICE_ENABLED, it)
        }
        payload["sync_ts"]?.toLongOrNull()?.let {
            editor.putLong(KEY_LAST_PHONE_SYNC_TS, it)
        }

        editor.apply()
    }

    fun getActionDisplayName(action: String): String {
        return when (action) {
            ACTION_PLAY_PAUSE -> "Play/Pause Media"
            ACTION_BACK -> "Back"
            ACTION_HOME -> "Home"
            ACTION_RECENTS -> "Recent Apps"
            ACTION_NEXT_TRACK -> "Next Track"
            ACTION_PREV_TRACK -> "Previous Track"
            ACTION_VOLUME_UP -> "Volume Up"
            ACTION_VOLUME_DOWN -> "Volume Down"
            ACTION_MUTE -> "Mute"
            ACTION_NOTIFICATIONS -> "Notifications"
            else -> "Play/Pause Media"
        }
    }

    fun getAllActions(): List<String> = listOf(
        ACTION_PLAY_PAUSE,
        ACTION_BACK,
        ACTION_HOME,
        ACTION_RECENTS,
        ACTION_NEXT_TRACK,
        ACTION_PREV_TRACK,
        ACTION_VOLUME_UP,
        ACTION_VOLUME_DOWN,
        ACTION_MUTE,
        ACTION_NOTIFICATIONS
    )
}
