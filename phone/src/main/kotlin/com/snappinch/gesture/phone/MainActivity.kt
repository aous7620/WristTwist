package com.snappinch.gesture.phone

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var currentActionText: TextView
    private lateinit var reverseActionText: TextView

    private lateinit var changeActionButton: Button
    private lateinit var changeReverseActionButton: Button
    private lateinit var settingsButton: Button
    private lateinit var toggleServiceButton: Button

    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PhoneSyncListenerService.ACTION_PHONE_SETTINGS_CHANGED) {
                updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        bindActions()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        PhoneMediaExecutor.syncSettingsToWatchAsync(this)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PhoneSyncListenerService.ACTION_PHONE_SETTINGS_CHANGED)
        ContextCompat.registerReceiver(
            this,
            settingsChangedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(settingsChangedReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun bindViews() {
        currentActionText = findViewById(R.id.current_action_text)
        reverseActionText = findViewById(R.id.reverse_action_text)

        changeActionButton = findViewById(R.id.change_action_button)
        changeReverseActionButton = findViewById(R.id.change_reverse_action_button)
        settingsButton = findViewById(R.id.settings_button)
        toggleServiceButton = findViewById(R.id.toggle_service_button)
    }

    private fun bindActions() {
        changeActionButton.setOnClickListener { showActionSelectionDialog(reverse = false) }
        changeReverseActionButton.setOnClickListener { showActionSelectionDialog(reverse = true) }
        settingsButton.setOnClickListener { openNotificationListenerSettings() }
        toggleServiceButton.setOnClickListener { toggleControl() }
    }

    private fun updateUI() {
        val controlEnabled = ControlPreferences.isControlEnabled(this)

        toggleServiceButton.text = if (controlEnabled) {
            getString(R.string.pause_control)
        } else {
            getString(R.string.resume_control)
        }
        currentActionText.text = ControlPreferences.getActionDisplayName(
            ControlPreferences.getPrimaryAction(this)
        )
        reverseActionText.text = ControlPreferences.getActionDisplayName(
            ControlPreferences.getReverseAction(this)
        )
    }

    private fun toggleControl() {
        val next = !ControlPreferences.isControlEnabled(this)
        ControlPreferences.setControlEnabled(this, next)
        PhoneMediaExecutor.syncSettingsToWatchAsync(this)
        updateUI()
        Toast.makeText(
            this,
            if (next) getString(R.string.control_resumed) else getString(R.string.control_paused),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showActionSelectionDialog(reverse: Boolean) {
        val actions = ControlPreferences.getAllActions()
        val names = actions.map(ControlPreferences::getActionDisplayName).toTypedArray()
        val current = if (reverse) {
            ControlPreferences.getReverseAction(this)
        } else {
            ControlPreferences.getPrimaryAction(this)
        }
        val currentIndex = actions.indexOf(current)

        AlertDialog.Builder(this)
            .setTitle(
                if (reverse) getString(R.string.select_reverse_action)
                else getString(R.string.select_primary_action)
            )
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                if (reverse) {
                    ControlPreferences.setReverseAction(this, actions[which])
                } else {
                    ControlPreferences.setPrimaryAction(this, actions[which])
                }
                PhoneMediaExecutor.syncSettingsToWatchAsync(this)
                updateUI()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openNotificationListenerSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val detailIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(this@MainActivity, PhoneNotificationListener::class.java).flattenToString()
                    )
                }
                startActivity(detailIntent)
            } else {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.notification_settings_fallback_hint),
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}
