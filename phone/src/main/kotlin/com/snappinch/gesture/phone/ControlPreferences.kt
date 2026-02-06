package com.snappinch.gesture.phone

import android.content.Context
import android.content.SharedPreferences

object ControlPreferences {
    private const val PREFS_NAME = "snappinch_phone_prefs"
    private const val KEY_PRIMARY_ACTION = "primary_action"
    private const val KEY_REVERSE_ACTION = "reverse_action"
    private const val KEY_CONTROL_ENABLED = "control_enabled"
    private const val KEY_PREFER_EXPLICIT_PLAY_PAUSE = "prefer_explicit_play_pause"
    private const val KEY_ALLOW_PROXY_SCREEN_OFF = "allow_proxy_screen_off"
    private const val KEY_ENABLE_OFFSCREEN_RETRY = "enable_offscreen_retry"
    private const val KEY_OFFSCREEN_RETRY_DELAY_MS = "offscreen_retry_delay_ms"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"

    private const val DEFAULT_OFFSCREEN_RETRY_DELAY_MS = 220

    const val ACTION_PLAY_PAUSE = "play_pause"
    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_STOP = "stop"
    const val ACTION_NEXT_TRACK = "next_track"
    const val ACTION_PREV_TRACK = "prev_track"
    const val ACTION_FAST_FORWARD = "fast_forward"
    const val ACTION_REWIND = "rewind"
    const val ACTION_VOLUME_UP = "volume_up"
    const val ACTION_VOLUME_DOWN = "volume_down"
    const val ACTION_MUTE = "mute"
    const val ACTION_OPEN_CAMERA = "open_camera"
    const val ACTION_LAUNCH_ASSISTANT = "launch_assistant"
    const val ACTION_FIND_PHONE = "find_phone"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getPrimaryAction(context: Context): String {
        return prefs(context).getString(KEY_PRIMARY_ACTION, ACTION_PLAY_PAUSE) ?: ACTION_PLAY_PAUSE
    }

    fun setPrimaryAction(context: Context, action: String) {
        prefs(context).edit().putString(KEY_PRIMARY_ACTION, action).apply()
    }

    fun getReverseAction(context: Context): String {
        return prefs(context).getString(KEY_REVERSE_ACTION, ACTION_PLAY_PAUSE) ?: ACTION_PLAY_PAUSE
    }

    fun setReverseAction(context: Context, action: String) {
        prefs(context).edit().putString(KEY_REVERSE_ACTION, action).apply()
    }

    fun isControlEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CONTROL_ENABLED, true)
    }

    fun setControlEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTROL_ENABLED, enabled).apply()
    }

    fun preferExplicitPlayPauseKey(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PREFER_EXPLICIT_PLAY_PAUSE, true)
    }

    fun setPreferExplicitPlayPauseKey(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFER_EXPLICIT_PLAY_PAUSE, enabled).apply()
    }

    fun allowProxyControlWhenScreenOff(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ALLOW_PROXY_SCREEN_OFF, false)
    }

    fun setAllowProxyControlWhenScreenOff(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_PROXY_SCREEN_OFF, enabled).apply()
    }

    fun isOffscreenRetryEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLE_OFFSCREEN_RETRY, true)
    }

    fun setOffscreenRetryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLE_OFFSCREEN_RETRY, enabled).apply()
    }

    fun getOffscreenRetryDelayMs(context: Context): Int {
        return prefs(context).getInt(KEY_OFFSCREEN_RETRY_DELAY_MS, DEFAULT_OFFSCREEN_RETRY_DELAY_MS)
    }

    fun setOffscreenRetryDelayMs(context: Context, delayMs: Int) {
        prefs(context).edit().putInt(KEY_OFFSCREEN_RETRY_DELAY_MS, delayMs.coerceIn(120, 1000)).apply()
    }

    fun isHapticsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HAPTICS_ENABLED, true)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTICS_ENABLED, enabled).apply()
    }

    fun getActionDisplayName(action: String): String {
        return when (action) {
            ACTION_PLAY_PAUSE -> "Play/Pause"
            ACTION_PLAY -> "Play"
            ACTION_PAUSE -> "Pause"
            ACTION_STOP -> "Stop"
            ACTION_NEXT_TRACK -> "Next Track"
            ACTION_PREV_TRACK -> "Previous Track"
            ACTION_FAST_FORWARD -> "Fast Forward"
            ACTION_REWIND -> "Rewind"
            ACTION_VOLUME_UP -> "Volume Up"
            ACTION_VOLUME_DOWN -> "Volume Down"
            ACTION_MUTE -> "Mute"
            ACTION_OPEN_CAMERA -> "Open Camera"
            ACTION_LAUNCH_ASSISTANT -> "Voice Assistant"
            ACTION_FIND_PHONE -> "Find My Phone"
            else -> "Play/Pause"
        }
    }

    fun getAllActions(): List<String> = listOf(
        ACTION_PLAY_PAUSE,
        ACTION_PLAY,
        ACTION_PAUSE,
        ACTION_STOP,
        ACTION_NEXT_TRACK,
        ACTION_PREV_TRACK,
        ACTION_FAST_FORWARD,
        ACTION_REWIND,
        ACTION_VOLUME_UP,
        ACTION_VOLUME_DOWN,
        ACTION_MUTE,
        ACTION_OPEN_CAMERA,
        ACTION_LAUNCH_ASSISTANT,
        ACTION_FIND_PHONE
    )
}
