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
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val RETRY_DELAY_MIN_MS = 120
        private const val RETRY_DELAY_MAX_MS = 1000
    }

    private lateinit var statusText: TextView
    private lateinit var currentActionText: TextView
    private lateinit var reverseActionText: TextView
    private lateinit var retryDelayValueText: TextView

    private lateinit var changeActionButton: Button
    private lateinit var changeReverseActionButton: Button
    private lateinit var settingsButton: Button
    private lateinit var toggleServiceButton: Button

    private lateinit var switchExplicitPlayPause: Switch
    private lateinit var switchAllowProxyScreenOff: Switch
    private lateinit var switchOffscreenRetry: Switch
    private lateinit var switchHaptics: Switch
    private lateinit var retryDelaySeekBar: SeekBar

    private var bindingCustomizationState = false

    private val settingsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PhoneSyncListenerService.ACTION_PHONE_SETTINGS_CHANGED) {
                updateUI()
                bindCustomizationState()
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
        bindCustomizationState()
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
        statusText = findViewById(R.id.status_text)
        currentActionText = findViewById(R.id.current_action_text)
        reverseActionText = findViewById(R.id.reverse_action_text)
        retryDelayValueText = findViewById(R.id.retry_delay_value_text)

        changeActionButton = findViewById(R.id.change_action_button)
        changeReverseActionButton = findViewById(R.id.change_reverse_action_button)
        settingsButton = findViewById(R.id.settings_button)
        toggleServiceButton = findViewById(R.id.toggle_service_button)

        switchExplicitPlayPause = findViewById(R.id.switch_explicit_play_pause)
        switchAllowProxyScreenOff = findViewById(R.id.switch_allow_proxy_screen_off)
        switchOffscreenRetry = findViewById(R.id.switch_offscreen_retry)
        switchHaptics = findViewById(R.id.switch_haptics)
        retryDelaySeekBar = findViewById(R.id.retry_delay_seekbar)
    }

    private fun bindActions() {
        changeActionButton.setOnClickListener { showActionSelectionDialog(reverse = false) }
        changeReverseActionButton.setOnClickListener { showActionSelectionDialog(reverse = true) }
        settingsButton.setOnClickListener { openNotificationListenerSettings() }
        toggleServiceButton.setOnClickListener { toggleControl() }

        switchExplicitPlayPause.setOnCheckedChangeListener { _, isChecked ->
            if (!bindingCustomizationState) {
                ControlPreferences.setPreferExplicitPlayPauseKey(this, isChecked)
                PhoneMediaExecutor.syncSettingsToWatchAsync(this)
            }
        }
        switchAllowProxyScreenOff.setOnCheckedChangeListener { _, isChecked ->
            if (!bindingCustomizationState) {
                ControlPreferences.setAllowProxyControlWhenScreenOff(this, isChecked)
                PhoneMediaExecutor.syncSettingsToWatchAsync(this)
            }
        }
        switchOffscreenRetry.setOnCheckedChangeListener { _, isChecked ->
            if (!bindingCustomizationState) {
                ControlPreferences.setOffscreenRetryEnabled(this, isChecked)
                PhoneMediaExecutor.syncSettingsToWatchAsync(this)
            }
        }
        switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            if (!bindingCustomizationState) {
                ControlPreferences.setHapticsEnabled(this, isChecked)
                PhoneMediaExecutor.syncSettingsToWatchAsync(this)
            }
        }

        retryDelaySeekBar.max = RETRY_DELAY_MAX_MS - RETRY_DELAY_MIN_MS
        retryDelaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                retryDelayValueText.text = getString(
                    R.string.retry_delay_format,
                    progress + RETRY_DELAY_MIN_MS
                )
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val delay = (seekBar?.progress ?: 0) + RETRY_DELAY_MIN_MS
                ControlPreferences.setOffscreenRetryDelayMs(this@MainActivity, delay)
                PhoneMediaExecutor.syncSettingsToWatchAsync(this@MainActivity)
            }
        })
    }

    private fun bindCustomizationState() {
        bindingCustomizationState = true
        switchExplicitPlayPause.isChecked = ControlPreferences.preferExplicitPlayPauseKey(this)
        switchAllowProxyScreenOff.isChecked = ControlPreferences.allowProxyControlWhenScreenOff(this)
        switchOffscreenRetry.isChecked = ControlPreferences.isOffscreenRetryEnabled(this)
        switchHaptics.isChecked = ControlPreferences.isHapticsEnabled(this)

        val delay = ControlPreferences.getOffscreenRetryDelayMs(this)
        retryDelaySeekBar.progress = delay - RETRY_DELAY_MIN_MS
        retryDelayValueText.text = getString(R.string.retry_delay_format, delay)
        bindingCustomizationState = false
    }

    private fun updateUI() {
        val notificationAccess = PhoneNotificationListener.isNotificationAccessEnabled(this)
        val controlEnabled = ControlPreferences.isControlEnabled(this)

        when {
            !notificationAccess -> {
                statusText.text = getString(R.string.status_notification_access_missing)
                statusText.setTextColor(0xFFFFD180.toInt())
            }
            !controlEnabled -> {
                statusText.text = getString(R.string.status_control_paused)
                statusText.setTextColor(0xFFFF8A80.toInt())
            }
            else -> {
                statusText.text = getString(R.string.status_control_ready)
                statusText.setTextColor(0xFFA5D6A7.toInt())
            }
        }

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
