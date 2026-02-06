package com.snappinch.gesture.phone

object WearSyncProtocol {
    const val PATH_MEDIA_COMMAND = "/snappinch/media_command"
    const val PATH_SETTINGS_SYNC = "/snappinch/settings_sync"
    const val PATH_SETTINGS_REQUEST = "/snappinch/settings_request"
    const val PATH_WATCH_SETTINGS_UPDATE = "/snappinch/watch_settings_update"

    const val ACTION_PLAY_PAUSE = "play_pause"
    const val ACTION_PLAY = "play"
    const val ACTION_PAUSE = "pause"
    const val ACTION_STOP = "stop"
    const val ACTION_NEXT = "next_track"
    const val ACTION_PREVIOUS = "prev_track"
    const val ACTION_FAST_FORWARD = "fast_forward"
    const val ACTION_REWIND = "rewind"
    const val ACTION_VOLUME_UP = "volume_up"
    const val ACTION_VOLUME_DOWN = "volume_down"
    const val ACTION_MUTE = "mute"
    const val ACTION_OPEN_CAMERA = "open_camera"
    const val ACTION_LAUNCH_ASSISTANT = "launch_assistant"
    const val ACTION_FIND_PHONE = "find_phone"

    fun encodeMap(payload: Map<String, String>): ByteArray {
        val body = payload.entries.joinToString("\n") { (k, v) -> "$k=$v" }
        return body.toByteArray(Charsets.UTF_8)
    }

    fun decodeMap(bytes: ByteArray): Map<String, String> {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) return emptyMap()
        return text
            .lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0 || idx == line.lastIndex) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isEmpty()) null else key to value
            }
            .toMap()
    }
}
