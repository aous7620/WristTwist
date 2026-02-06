package com.snappinch.gesture

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

object WatchToPhoneBridge {
    private const val TAG = "WatchToPhoneBridge"

    fun sendMediaCommand(context: Context, action: String): Boolean {
        val ts = System.currentTimeMillis()
        val payload = WearSyncProtocol.encodeMap(
            mapOf(
                "action" to action,
                "ts" to ts.toString()
            )
        )
        val messageSent = sendMessage(context, WearSyncProtocol.PATH_MEDIA_COMMAND, payload)
        if (messageSent) {
            return true
        }
        return sendMediaCommandDataItem(context, action, ts)
    }

    fun requestSettingsSync(context: Context): Boolean {
        return sendMessage(context, WearSyncProtocol.PATH_SETTINGS_REQUEST, ByteArray(0))
    }

    fun sendSettingsUpdate(context: Context, payload: Map<String, String>): Boolean {
        return sendMessage(
            context,
            WearSyncProtocol.PATH_WATCH_SETTINGS_UPDATE,
            WearSyncProtocol.encodeMap(payload)
        )
    }

    private fun sendMessage(context: Context, path: String, data: ByteArray): Boolean {
        return try {
            val nodeClient = Wearable.getNodeClient(context)
            val messageClient = Wearable.getMessageClient(context)
            val nodes = Tasks.await(nodeClient.connectedNodes, 3000, TimeUnit.MILLISECONDS)
            if (nodes.isEmpty()) {
                Log.d(TAG, "No connected phone nodes for path=$path")
                return false
            }

            var sent = false
            for (node in nodes) {
                try {
                    Tasks.await(messageClient.sendMessage(node.id, path, data), 3000, TimeUnit.MILLISECONDS)
                    Log.d(TAG, "Sent message path=$path to node=${node.displayName}")
                    sent = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending message to node=${node.displayName} path=$path", e)
                }
            }
            sent
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage failed for path=$path", e)
            false
        }
    }

    private fun sendMediaCommandDataItem(context: Context, action: String, ts: Long): Boolean {
        return try {
            val request = PutDataMapRequest.create(WearSyncProtocol.PATH_MEDIA_COMMAND).apply {
                dataMap.putString("action", action)
                dataMap.putLong("ts", ts)
            }.asPutDataRequest().setUrgent()

            Tasks.await(Wearable.getDataClient(context).putDataItem(request), 3000, TimeUnit.MILLISECONDS)
            Log.d(TAG, "Sent data item fallback for media command action=$action")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed media command data item fallback", e)
            false
        }
    }
}
