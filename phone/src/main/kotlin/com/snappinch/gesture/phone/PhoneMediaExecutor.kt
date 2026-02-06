package com.snappinch.gesture.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object PhoneMediaExecutor {
    private const val TAG = "PhoneMediaExecutor"
    private val syncExecutor = Executors.newSingleThreadExecutor()

    fun executeAction(context: Context, action: String) {
        if (!ControlPreferences.isControlEnabled(context)) {
            Log.d(TAG, "Control paused on phone; ignoring watch action=$action")
            return
        }

        when (action) {
            WearSyncProtocol.ACTION_PLAY_PAUSE -> {
                if (!dispatchToActiveSession(context, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                    handlePlayPause(context)
                }
            }
            WearSyncProtocol.ACTION_NEXT -> {
                if (!dispatchToActiveSession(context, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)) {
                    sendMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                }
            }
            WearSyncProtocol.ACTION_PREVIOUS -> {
                if (!dispatchToActiveSession(context, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
                    sendMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
            WearSyncProtocol.ACTION_VOLUME_UP -> adjustVolume(context, true)
            WearSyncProtocol.ACTION_VOLUME_DOWN -> adjustVolume(context, false)
            WearSyncProtocol.ACTION_MUTE -> toggleMute(context)
            else -> Log.w(TAG, "Unknown action from watch: $action")
        }
    }

    fun syncSettingsToWatch(context: Context) {
        val payload = mapOf(
            "primary_action" to ControlPreferences.getPrimaryAction(context),
            "prefer_explicit_play_pause" to ControlPreferences.preferExplicitPlayPauseKey(context).toString(),
            "allow_proxy_screen_off" to ControlPreferences.allowProxyControlWhenScreenOff(context).toString(),
            "enable_offscreen_retry" to ControlPreferences.isOffscreenRetryEnabled(context).toString(),
            "offscreen_retry_delay_ms" to ControlPreferences.getOffscreenRetryDelayMs(context).toString(),
            "haptics_enabled" to ControlPreferences.isHapticsEnabled(context).toString(),
            "route_media_to_phone" to "true",
            "control_enabled" to ControlPreferences.isControlEnabled(context).toString(),
            "sync_ts" to System.currentTimeMillis().toString()
        )
        sendMessageToConnectedNodes(
            context,
            WearSyncProtocol.PATH_SETTINGS_SYNC,
            WearSyncProtocol.encodeMap(payload)
        )
    }

    fun syncSettingsToWatchAsync(context: Context) {
        val appContext = context.applicationContext
        syncExecutor.execute {
            try {
                syncSettingsToWatch(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Background settings sync failed", e)
            }
        }
    }

    private fun handlePlayPause(context: Context) {
        val key = if (ControlPreferences.preferExplicitPlayPauseKey(context)) {
            inferExplicitPlayPauseKey(context) ?: android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        } else {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        sendMediaKey(context, key)

        if (shouldRetryOffScreen(context, key)) {
            val delay = ControlPreferences.getOffscreenRetryDelayMs(context).toLong()
            android.os.Handler(context.mainLooper).postDelayed(
                { sendMediaKey(context, key) },
                delay
            )
            Log.d(TAG, "Scheduled off-screen retry key=$key delay=${delay}ms")
        }
    }

    private fun shouldRetryOffScreen(context: Context, key: Int): Boolean {
        if (!ControlPreferences.isOffscreenRetryEnabled(context)) return false
        if (key == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return false
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !power.isInteractive
    }

    private fun inferExplicitPlayPauseKey(context: Context): Int? {
        return try {
            if (!PhoneNotificationListener.isNotificationAccessEnabled(context)) {
                return null
            }
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = android.content.ComponentName(context, PhoneNotificationListener::class.java)
            val sessions = msm.getActiveSessions(component)
            if (sessions.isEmpty()) return null

            val filtered = if (ControlPreferences.allowProxyControlWhenScreenOff(context)) {
                sessions
            } else {
                sessions.filterNot { isLikelyProxyPackage(it.packageName) }
            }
            val candidate = if (filtered.isNotEmpty()) filtered else sessions
            val active = candidate.firstOrNull { controller ->
                val state = controller.playbackState?.state
                state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_CONNECTING
            }
            if (active != null) android.view.KeyEvent.KEYCODE_MEDIA_PAUSE else android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        } catch (e: Exception) {
            Log.w(TAG, "Unable to infer explicit play/pause key", e)
            null
        }
    }

    private fun dispatchToActiveSession(context: Context, keyCode: Int): Boolean {
        if (!PhoneNotificationListener.isNotificationAccessEnabled(context)) {
            return false
        }
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, PhoneNotificationListener::class.java)
            val sessions = msm.getActiveSessions(component)
            if (sessions.isEmpty()) {
                return false
            }
            val filtered = if (ControlPreferences.allowProxyControlWhenScreenOff(context)) {
                sessions
            } else {
                sessions.filterNot { isLikelyProxyPackage(it.packageName) }
            }
            val candidates = (if (filtered.isNotEmpty()) filtered else sessions).sortedByDescending { controller ->
                val state = controller.playbackState?.state
                val isPlaying = state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_CONNECTING
                var score = 0
                if (isPlaying) score += 100
                if (supportsAction(controller, keyCode)) score += 20
                if (controller.metadata != null) score += 5
                score
            }

            candidates.forEachIndexed { index, controller ->
                Log.d(
                    TAG,
                    "PhoneController[$index]: pkg=${controller.packageName}, state=${controller.playbackState?.state}, supports=${supportsAction(controller, keyCode)}"
                )
            }

            for (controller in candidates) {
                if (sendMediaButtonToController(controller, keyCode)) {
                    Log.d(TAG, "Media action sent via phone MediaController media button. pkg=${controller.packageName}, keyCode=$keyCode")
                    return true
                }
                if (sendTransportActionToController(controller, keyCode)) {
                    Log.d(TAG, "Media action sent via phone MediaController transport controls. pkg=${controller.packageName}, keyCode=$keyCode")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "dispatchToActiveSession failed keyCode=$keyCode", e)
            false
        }
    }

    private fun sendMediaButtonToController(controller: MediaController, keyCode: Int): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0)
            controller.dispatchMediaButtonEvent(down) || controller.dispatchMediaButtonEvent(up)
        } catch (e: Exception) {
            Log.v(TAG, "dispatchMediaButtonEvent failed for ${controller.packageName}", e)
            false
        }
    }

    private fun sendTransportActionToController(controller: MediaController, keyCode: Int): Boolean {
        return try {
            val transport = controller.transportControls
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val state = controller.playbackState?.state
                    val isPlaying = state == PlaybackState.STATE_PLAYING ||
                        state == PlaybackState.STATE_BUFFERING ||
                        state == PlaybackState.STATE_CONNECTING
                    if (isPlaying) transport.pause() else transport.play()
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> transport.skipToNext()
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> transport.skipToPrevious()
                else -> return false
            }
            true
        } catch (e: Exception) {
            Log.v(TAG, "transportControls failed for ${controller.packageName}", e)
            false
        }
    }

    private fun supportsAction(controller: MediaController, keyCode: Int): Boolean {
        val actions = controller.playbackState?.actions ?: return false
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L ||
                    (actions and PlaybackState.ACTION_PLAY) != 0L ||
                    (actions and PlaybackState.ACTION_PAUSE) != 0L
            }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
            else -> false
        }
    }

    private fun isLikelyProxyPackage(packageName: String): Boolean {
        val proxyPrefixes = listOf(
            "com.google.wear",
            "com.google.android.clockwork",
            "com.oplus.wearable.media.sessions",
            "com.heytap.wearable"
        )
        return proxyPrefixes.any { packageName.startsWith(it) }
    }

    private fun sendMediaKey(context: Context, keyCode: Int) {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            val down = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0)
            audio.dispatchMediaKeyEvent(down)
            audio.dispatchMediaKeyEvent(up)

            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, down)
            }
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, up)
            }
            context.sendBroadcast(downIntent)
            context.sendBroadcast(upIntent)
            Log.d(TAG, "Sent media key=$keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media key=$keyCode", e)
        }
    }

    private fun adjustVolume(context: Context, up: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustVolume(
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun toggleMute(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
    }

    private fun sendMessageToConnectedNodes(context: Context, path: String, data: ByteArray) {
        try {
            val nodeClient = Wearable.getNodeClient(context)
            val messageClient = Wearable.getMessageClient(context)
            val nodes = Tasks.await(nodeClient.connectedNodes, 1500, TimeUnit.MILLISECONDS)
            if (nodes.isEmpty()) {
                Log.d(TAG, "No connected watch nodes for path=$path")
                return
            }
            for (node in nodes) {
                try {
                    Tasks.await(messageClient.sendMessage(node.id, path, data), 1500, TimeUnit.MILLISECONDS)
                    Log.d(TAG, "Sent path=$path to node=${node.displayName}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send path=$path to node=${node.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendMessageToConnectedNodes failed for path=$path", e)
        }
    }
}
