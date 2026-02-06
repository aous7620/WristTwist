package com.snappinch.gesture

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SnapPinchMain"
        private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        private const val EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME"
    }

    private lateinit var statusText: TextView
    private lateinit var currentActionText: TextView
    private lateinit var reverseActionText: TextView
    private lateinit var changeActionButton: Button
    private lateinit var changeReverseActionButton: Button
    private lateinit var enableGestureButton: Button
    private lateinit var toggleServiceButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        currentActionText = findViewById(R.id.current_action_text)
        reverseActionText = findViewById(R.id.reverse_action_text)
        changeActionButton = findViewById(R.id.change_action_button)
        changeReverseActionButton = findViewById(R.id.change_reverse_action_button)
        enableGestureButton = findViewById(R.id.enable_gesture_button)
        toggleServiceButton = findViewById(R.id.toggle_service_button)

        changeActionButton.setOnClickListener { showActionSelectionDialog(reverse = false) }
        changeReverseActionButton.setOnClickListener { showActionSelectionDialog(reverse = true) }
        enableGestureButton.setOnClickListener { openAccessibilitySettings() }
        toggleServiceButton.setOnClickListener { toggleService() }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        val lastSyncTs = ActionPreferences.getLastPhoneSyncTs(this)
        if (System.currentTimeMillis() - lastSyncTs > 5000L) {
            thread(name = "WatchSettingsRequest") {
                WatchToPhoneBridge.requestSettingsSync(this)
            }
        }
    }

    private fun updateUI() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isPaused = !ActionPreferences.isServiceEnabled(this)

        Log.d(
            TAG,
            "serviceEnabled=$isAccessibilityEnabled paused=$isPaused"
        )

        when {
            !isAccessibilityEnabled -> {
                statusText.text = getString(R.string.service_disabled_state)
                statusText.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorError))
                toggleServiceButton.text = getString(R.string.enable_in_settings)
                enableGestureButton.text = getString(R.string.enable_gesture)
            }
            isPaused -> {
                statusText.text = getString(R.string.service_paused_state)
                statusText.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorTertiary))
                toggleServiceButton.text = getString(R.string.resume_service)
                enableGestureButton.text = getString(R.string.open_accessibility)
            }
            else -> {
                statusText.text = getString(R.string.service_active_state)
                statusText.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
                toggleServiceButton.text = getString(R.string.pause_service)
                enableGestureButton.text = getString(R.string.gesture_enabled)
            }
        }

        updateActionDisplay()
    }

    private fun updateActionDisplay() {
        val action = ActionPreferences.getSelectedAction(this)
        val reverseAction = ActionPreferences.getReverseAction(this)
        currentActionText.text = ActionPreferences.getActionDisplayName(action)
        reverseActionText.text = ActionPreferences.getActionDisplayName(reverseAction)
    }

    private fun showActionSelectionDialog(reverse: Boolean) {
        val actions = ActionPreferences.getAllActions()
        val actionNames = actions.map { ActionPreferences.getActionDisplayName(it) }.toTypedArray()
        val currentAction = if (reverse) {
            ActionPreferences.getReverseAction(this)
        } else {
            ActionPreferences.getSelectedAction(this)
        }
        val currentIndex = actions.indexOf(currentAction)

        AlertDialog.Builder(this)
            .setTitle(
                if (reverse) getString(R.string.select_reverse_action)
                else getString(R.string.select_action)
            )
            .setSingleChoiceItems(actionNames, currentIndex) { dialog, which ->
                val selectedAction = actions[which]
                if (reverse) {
                    ActionPreferences.setReverseAction(this, selectedAction)
                } else {
                    ActionPreferences.setSelectedAction(this, selectedAction)
                }
                pushWatchSettingsToPhone()
                updateActionDisplay()
                dialog.dismiss()
                Toast.makeText(this, getString(R.string.action_updated), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleService() {
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings()
            return
        }

        val currentlyPaused = !ActionPreferences.isServiceEnabled(this)
        ActionPreferences.setServiceEnabled(this, currentlyPaused)

        val intent = Intent(OnePlusGestureService.ACTION_TOGGLE_PAUSE).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        pushWatchSettingsToPhone()

        Toast.makeText(
            this,
            if (currentlyPaused) getString(R.string.service_resumed) else getString(R.string.service_paused_toast),
            Toast.LENGTH_SHORT
        ).show()
        updateUI()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, OnePlusGestureService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (TextUtils.isEmpty(enabledServices)) {
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val enabledComponent = ComponentName.unflattenFromString(colonSplitter.next())
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun openAccessibilitySettings() {
        val serviceComponent = ComponentName(this, OnePlusGestureService::class.java).flattenToString()
        try {
            val detailIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                putExtra(EXTRA_COMPONENT_NAME, serviceComponent)
            }
            startActivity(detailIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open accessibility settings fallback", e2)
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun pushWatchSettingsToPhone() {
        val payload = mapOf(
            "primary_action" to ActionPreferences.getSelectedAction(this),
            "reverse_action" to ActionPreferences.getReverseAction(this),
            "control_enabled" to ActionPreferences.isServiceEnabled(this).toString(),
            "sync_ts" to System.currentTimeMillis().toString()
        )
        thread(name = "WatchSettingsPush") {
            val sent = WatchToPhoneBridge.sendSettingsUpdate(this, payload)
            Log.d(TAG, "Pushed watch settings to phone sent=$sent payload=$payload")
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
