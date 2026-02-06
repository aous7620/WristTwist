package com.snappinch.gesture

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class OnePlusGestureService : AccessibilityService(), SensorEventListener, WearDetector.WearStateListener {

    companion object {
        private const val TAG = "SnapPinchService"
        const val ACTION_TOGGLE_PAUSE = "com.snappinch.gesture.TOGGLE_PAUSE"
        private const val SENSOR_LOG_INTERVAL = 1000L
        private const val VERBOSE_SENSOR_LOGS = false
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        private const val SEEK_STEP_MS = 10_000L
    }

    private lateinit var sensorManager: SensorManager
    private var linearAccelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var isSensorRegistered = false
    
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockRefreshRunnable = object : Runnable {
        override fun run() {
            if (!ActionPreferences.isServiceEnabled(this@OnePlusGestureService) || !isWatchWorn) {
                return
            }
            refreshWakeLock()
            sensorHandler?.postDelayed(this, WAKE_LOCK_REFRESH_INTERVAL_MS)
        }
    }

    private lateinit var vibrator: Vibrator

    private lateinit var gestureClassifier: PinchGestureClassifier
    
    private lateinit var wearDetector: WearDetector
    private var isWatchWorn = true

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON")
                    if (ActionPreferences.isServiceEnabled(this@OnePlusGestureService)) {
                        registerSensorListener()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF - sensors remain active for gesture detection")
                }
            }
        }
    }
    
    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE_PAUSE) {
                updatePauseState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SnapPinch Service created")
        gestureClassifier = PinchGestureClassifier()
        wearDetector = WearDetector()
        wearDetector.setListener(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "SnapPinch Accessibility Service connected")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        linearAccelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroscope =
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE, true)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (linearAccelerometer == null) {
            Log.e(TAG, "Linear acceleration sensor not available on this device!")
            return
        }

        if (gyroscope == null) {
            Log.w(TAG, "Gyroscope not available - gesture accuracy may be reduced")
        }

        Log.i(
            TAG,
            "Sensors selected: linearAccelWakeUp=${linearAccelerometer?.isWakeUpSensor}, gyroWakeUp=${gyroscope?.isWakeUpSensor}"
        )

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)

        wearDetector.start(sensorManager)

        registerSensorListener()
        
        registerPauseReceiver()
        
        updatePauseState()

        requestSettingsSyncFromPhone()

        Log.i(TAG, "SnapPinch Service fully initialized with ML classifier")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        Log.w(TAG, "SnapPinch Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SnapPinch Service destroyed")
        
        releaseResources()
        
        wearDetector.stop()
        
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Screen state receiver was not registered")
        }
        try {
            unregisterReceiver(pauseReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Pause receiver was not registered")
        }
    }
    
    private fun updatePauseState() {
        val isEnabled = ActionPreferences.isServiceEnabled(this)
        Log.i(TAG, "Updating pause state: enabled=$isEnabled")
        
        if (isEnabled) {
            acquireWakeLock()
            registerSensorListener()
            Log.i(TAG, "Service RESUMED - sensors active")
        } else {
            releaseResources()
            Log.i(TAG, "Service PAUSED - sensors stopped, battery saving")
        }
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SnapPinch::SensorWakeLock"
            )
            wakeLock?.setReferenceCounted(false)
        }
        refreshWakeLock()
        sensorHandler?.removeCallbacks(wakeLockRefreshRunnable)
        sensorHandler?.postDelayed(wakeLockRefreshRunnable, WAKE_LOCK_REFRESH_INTERVAL_MS)
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                Log.d(TAG, "Wakelock acquired/refreshed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh wakelock", e)
        }
    }
    
    private fun releaseResources() {
        sensorHandler?.removeCallbacks(wakeLockRefreshRunnable)
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wakelock released")
            }
        }
        
        unregisterSensorListener()
        
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        Log.d(TAG, "Sensor thread stopped")
        
        gestureClassifier.reset()
    }

    override fun onWearStateChanged(isWorn: Boolean) {
        isWatchWorn = isWorn
        Log.i(TAG, "Watch wear state: ${if (isWorn) "WORN" else "OFF WRIST"}")
        
        if (!isWorn) {
            Log.i(TAG, "Watch OFF WRIST - stopping sensors to save battery")
            releaseResources()
        } else {
            if (ActionPreferences.isServiceEnabled(this)) {
                Log.i(TAG, "Watch WORN - resuming sensors")
                acquireWakeLock()
                registerSensorListener()
            }
        }
    }

    private fun registerSensorListener() {
        if (isSensorRegistered) {
            Log.d(TAG, "Sensor already registered, skipping")
            return
        }

        if (sensorThread == null) {
            sensorThread = HandlerThread("SensorThread", Thread.MAX_PRIORITY).apply {
                start()
            }
            sensorHandler = Handler(sensorThread!!.looper)
            Log.d(TAG, "Sensor thread started with MAX_PRIORITY")
        }

        if (wearDetector.needsAccelerometerFeed()) {
            linearAccelerometer?.let { sensor ->
                val success = sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    0,
                    sensorHandler
                )
                if (success) {
                    Log.d(TAG, "Accelerometer listener registered on dedicated thread")
                }
            }
        } else {
            Log.d(TAG, "Skipping accelerometer listener - off-body sensor available")
        }
        
        gyroscope?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                0,
                sensorHandler
            )
            Log.d(TAG, "Gyroscope listener registered on dedicated thread")
        }
        
        isSensorRegistered = true
        Log.d(TAG, "Sensor listeners registered on dedicated thread")
    }

    private fun unregisterSensorListener() {
        if (!isSensorRegistered) {
            Log.d(TAG, "Sensor not registered, skipping unregister")
            return
        }

        sensorManager.unregisterListener(this)
        isSensorRegistered = false
        
        gestureClassifier.reset()
        
        Log.d(TAG, "Sensor listeners unregistered - power save mode")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) {
            Log.w(TAG, "onSensorChanged: event is null")
            return
        }
        
        eventCounter++
        val shouldLog = VERBOSE_SENSOR_LOGS && eventCounter % SENSOR_LOG_INTERVAL == 0L
        
        if (shouldLog) {
            Log.d(TAG, "onSensorChanged: type=${event.sensor.type}, worn=$isWatchWorn, values=${event.values.take(3)}")
        }
        
        if (!isWatchWorn) {
            return
        }
        
        val currentTime = event.timestamp / 1_000_000L
        
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!wearDetector.needsAccelerometerFeed()) {
                    return
                }
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                
                if (shouldLog) {
                    Log.d(TAG, "Accel: x=$x, y=$y, z=$z")
                }
                
                wearDetector.processAccelerometer(x, y, z)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gestureClassifier.updateGyroscope(
                    event.values[0],
                    event.values[1],
                    event.values[2]
                )

                val gestureStartDirection = gestureClassifier.processSample(currentTime)
                if (gestureStartDirection != null) {
                    performGestureAction(gestureStartDirection)
                }
            }
        }
    }

    private fun registerPauseReceiver() {
        val filter = IntentFilter(ACTION_TOGGLE_PAUSE)
        ContextCompat.registerReceiver(
            this,
            pauseReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
    
    private var eventCounter = 0L

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (VERBOSE_SENSOR_LOGS) {
            Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} = $accuracy")
        }
    }

    private fun performGestureAction(startDirection: Int) {
        try {
            if (!ActionPreferences.isServiceEnabled(this)) {
                Log.d(TAG, "Service is paused, ignoring gesture")
                return
            }

            vibrateDoubleBuzz()

            val action = if (startDirection >= 0) {
                ActionPreferences.getReverseAction(this)
            } else {
                ActionPreferences.getSelectedAction(this)
            }
            Log.d(TAG, "Performing action: $action (startDirection=$startDirection)")

            val success = when (action) {
                ActionPreferences.ACTION_PLAY_PAUSE -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                )
                ActionPreferences.ACTION_PLAY -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                )
                ActionPreferences.ACTION_PAUSE -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                )
                ActionPreferences.ACTION_STOP -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_STOP
                )
                ActionPreferences.ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                ActionPreferences.ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                ActionPreferences.ACTION_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                ActionPreferences.ACTION_NEXT_TRACK -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                )
                ActionPreferences.ACTION_PREV_TRACK -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                )
                ActionPreferences.ACTION_FAST_FORWARD -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                )
                ActionPreferences.ACTION_REWIND -> performMediaAction(
                    action,
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND
                )
                ActionPreferences.ACTION_VOLUME_UP -> performRemoteMediaAction(action) || adjustVolume(true)
                ActionPreferences.ACTION_VOLUME_DOWN -> performRemoteMediaAction(action) || adjustVolume(false)
                ActionPreferences.ACTION_MUTE -> performRemoteMediaAction(action) || toggleMute()
                ActionPreferences.ACTION_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                ActionPreferences.ACTION_OPEN_CAMERA -> WatchToPhoneBridge.sendMediaCommand(
                    this,
                    WearSyncProtocol.ACTION_OPEN_CAMERA
                )
                ActionPreferences.ACTION_LAUNCH_ASSISTANT -> WatchToPhoneBridge.sendMediaCommand(
                    this,
                    WearSyncProtocol.ACTION_LAUNCH_ASSISTANT
                )
                ActionPreferences.ACTION_FIND_PHONE -> WatchToPhoneBridge.sendMediaCommand(
                    this,
                    WearSyncProtocol.ACTION_FIND_PHONE
                )
                else -> performMediaAction(
                    ActionPreferences.ACTION_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                )
            }

            if (success) {
                Log.i(TAG, "Action '$action' performed successfully")
            } else {
                Log.w(TAG, "Failed to perform action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "performGestureAction crashed", e)
        }
    }
    
    private fun performMediaAction(actionId: String, keyCode: Int): Boolean {
        if (ActionPreferences.shouldRouteMediaToPhone(this)) {
            val remoteAction = toRemoteAction(actionId)
            if (remoteAction != null) {
                val sentToPhone = WatchToPhoneBridge.sendMediaCommand(this, remoteAction)
                if (sentToPhone) {
                    Log.d(TAG, "Media action routed to phone: $remoteAction")
                    return true
                }
                Log.d(TAG, "Phone route unavailable, falling back to local media control")
            }
        }

        val isInteractive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val allowProxyScreenOffSuccess = isInteractive || ActionPreferences.allowProxyControlWhenScreenOff(this)
        if (sendMediaCommandToActiveSession(keyCode, allowProxyScreenOffSuccess = allowProxyScreenOffSuccess)) {
            return true
        }

        return if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            sendPlayPauseFallbackCombo(isInteractive)
        } else {
            sendMediaKey(keyCode)
        }
    }

    private fun toRemoteAction(actionId: String): String? {
        return when (actionId) {
            ActionPreferences.ACTION_PLAY_PAUSE -> WearSyncProtocol.ACTION_PLAY_PAUSE
            ActionPreferences.ACTION_PLAY -> WearSyncProtocol.ACTION_PLAY
            ActionPreferences.ACTION_PAUSE -> WearSyncProtocol.ACTION_PAUSE
            ActionPreferences.ACTION_STOP -> WearSyncProtocol.ACTION_STOP
            ActionPreferences.ACTION_NEXT_TRACK -> WearSyncProtocol.ACTION_NEXT
            ActionPreferences.ACTION_PREV_TRACK -> WearSyncProtocol.ACTION_PREVIOUS
            ActionPreferences.ACTION_FAST_FORWARD -> WearSyncProtocol.ACTION_FAST_FORWARD
            ActionPreferences.ACTION_REWIND -> WearSyncProtocol.ACTION_REWIND
            ActionPreferences.ACTION_VOLUME_UP -> WearSyncProtocol.ACTION_VOLUME_UP
            ActionPreferences.ACTION_VOLUME_DOWN -> WearSyncProtocol.ACTION_VOLUME_DOWN
            ActionPreferences.ACTION_MUTE -> WearSyncProtocol.ACTION_MUTE
            ActionPreferences.ACTION_OPEN_CAMERA -> WearSyncProtocol.ACTION_OPEN_CAMERA
            ActionPreferences.ACTION_LAUNCH_ASSISTANT -> WearSyncProtocol.ACTION_LAUNCH_ASSISTANT
            ActionPreferences.ACTION_FIND_PHONE -> WearSyncProtocol.ACTION_FIND_PHONE
            else -> null
        }
    }

    private fun performRemoteMediaAction(actionId: String): Boolean {
        if (!ActionPreferences.shouldRouteMediaToPhone(this)) {
            return false
        }
        val remoteAction = toRemoteAction(actionId) ?: return false
        val sent = WatchToPhoneBridge.sendMediaCommand(this, remoteAction)
        if (sent) {
            Log.d(TAG, "Remote media action sent to phone: $remoteAction")
        }
        return sent
    }

    private fun requestSettingsSyncFromPhone() {
        thread(name = "WatchSettingsSync") {
            val requested = WatchToPhoneBridge.requestSettingsSync(this)
            Log.d(TAG, "Settings sync request sent=$requested")
        }
    }

    private fun sendPlayPauseFallbackCombo(isInteractive: Boolean): Boolean {
        val preferredKeyCode = if (ActionPreferences.preferExplicitPlayPauseKey(this)) {
            getPreferredPlayPauseKeyCode()
        } else {
            null
        }
        val keyToSend = preferredKeyCode ?: android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        val sent = sendMediaKey(keyToSend)

        if (
            !isInteractive &&
            preferredKeyCode != null &&
            ActionPreferences.isOffscreenRetryEnabled(this)
        ) {
            val retryDelayMs = ActionPreferences.getOffscreenRetryDelayMs(this).toLong()
            sensorHandler?.postDelayed(
                { sendMediaKey(keyToSend) },
                retryDelayMs
            )
            Log.d(TAG, "Scheduled off-screen media retry for keyCode=$keyToSend after ${retryDelayMs}ms")
        }

        return sent
    }

    private fun getPreferredPlayPauseKeyCode(): Int? {
        return try {
            if (!GestureNotificationListener.isNotificationAccessEnabled(this)) {
                return null
            }

            val mediaSessionManager =
                getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(this, GestureNotificationListener::class.java)
            val controllers = mediaSessionManager.getActiveSessions(listenerComponent)
            if (controllers.isEmpty()) {
                return null
            }

            val playingController = controllers.firstOrNull { controller ->
                val state = controller.playbackState?.state
                state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_CONNECTING
            }

            if (playingController != null) {
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            } else {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            }
        } catch (e: Exception) {
            Log.v(TAG, "Unable to infer preferred play/pause key", e)
            null
        }
    }

    private fun sendMediaCommandToActiveSession(
        keyCode: Int,
        allowProxyScreenOffSuccess: Boolean
    ): Boolean {
        if (!GestureNotificationListener.isNotificationAccessEnabled(this)) {
            Log.w(TAG, "Notification access missing; cannot query active media sessions")
            return false
        }

        return try {
            val mediaSessionManager =
                getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(this, GestureNotificationListener::class.java)
            val controllers = mediaSessionManager.getActiveSessions(listenerComponent)
            val prioritizedControllers = chooseControllersForAction(controllers, keyCode)
            logControllerCandidates(prioritizedControllers, keyCode)

            if (prioritizedControllers.isEmpty()) {
                Log.d(TAG, "No suitable active media controller found for keyCode=$keyCode")
                return false
            }

            val nonProxyControllers = prioritizedControllers.filterNot {
                isLikelyProxyPackage(it.packageName)
            }
            val proxyControllers = prioritizedControllers.filter {
                isLikelyProxyPackage(it.packageName)
            }

            for (controller in nonProxyControllers) {
                if (sendMediaButtonToController(controller, keyCode)) {
                    Log.d(
                        TAG,
                        "Media action sent via MediaController media button. pkg=${controller.packageName}, keyCode=$keyCode"
                    )
                    return true
                }

                if (sendTransportActionToController(controller, keyCode)) {
                    Log.d(
                        TAG,
                        "Media action sent via MediaController transport controls. pkg=${controller.packageName}, keyCode=$keyCode"
                    )
                    return true
                }
            }

            val isInteractive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            if (!isInteractive && nonProxyControllers.isEmpty() && proxyControllers.isNotEmpty()) {
                val proxyController = proxyControllers.first()
                if (sendTransportActionToController(proxyController, keyCode)) {
                    Log.d(
                        TAG,
                        "Screen-off proxy transport action sent. pkg=${proxyController.packageName}, keyCode=$keyCode"
                    )
                    if (allowProxyScreenOffSuccess) {
                        return true
                    }
                }
            }

            proxyControllers.forEach { proxyController ->
                Log.d(
                    TAG,
                    "Skipping proxy controller for direct control; will use fallback dispatch. pkg=${proxyController.packageName}"
                )
            }

            false
        } catch (se: SecurityException) {
            Log.w(TAG, "No permission to access active media sessions", se)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to control active media session", e)
            false
        }
    }

    private fun sendMediaButtonToController(
        controller: MediaController,
        keyCode: Int
    ): Boolean {
        return try {
            val now = android.os.SystemClock.uptimeMillis()
            val keyDown = android.view.KeyEvent(
                now,
                now,
                android.view.KeyEvent.ACTION_DOWN,
                keyCode,
                0
            )
            val keyUp = android.view.KeyEvent(
                now,
                now,
                android.view.KeyEvent.ACTION_UP,
                keyCode,
                0
            )

            val downAccepted = controller.dispatchMediaButtonEvent(keyDown)
            val upAccepted = controller.dispatchMediaButtonEvent(keyUp)
            downAccepted || upAccepted
        } catch (e: Exception) {
            Log.v(TAG, "dispatchMediaButtonEvent failed for ${controller.packageName}", e)
            false
        }
    }

    private fun sendTransportActionToController(
        controller: MediaController,
        keyCode: Int
    ): Boolean {
        return try {
            val transport = controller.transportControls
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val playbackState = controller.playbackState?.state
                    val isPlaying = playbackState == PlaybackState.STATE_PLAYING ||
                        playbackState == PlaybackState.STATE_BUFFERING ||
                        playbackState == PlaybackState.STATE_CONNECTING
                    if (isPlaying) {
                        transport.pause()
                    } else {
                        transport.play()
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> transport.play()
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> transport.pause()
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> transport.stop()
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> transport.skipToNext()
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> transport.skipToPrevious()
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val actions = controller.playbackState?.actions ?: 0L
                    if ((actions and PlaybackState.ACTION_FAST_FORWARD) != 0L) {
                        transport.fastForward()
                    } else if (!seekByTransportFallback(controller, SEEK_STEP_MS)) {
                        return false
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    val actions = controller.playbackState?.actions ?: 0L
                    if ((actions and PlaybackState.ACTION_REWIND) != 0L) {
                        transport.rewind()
                    } else if (!seekByTransportFallback(controller, -SEEK_STEP_MS)) {
                        return false
                    }
                }
                else -> return false
            }
            true
        } catch (e: Exception) {
            Log.v(TAG, "transportControls failed for ${controller.packageName}", e)
            false
        }
    }

    private fun seekByTransportFallback(controller: MediaController, deltaMs: Long): Boolean {
        return try {
            val state = controller.playbackState ?: return false
            if ((state.actions and PlaybackState.ACTION_SEEK_TO) == 0L) return false
            val current = state.position
            if (current < 0L) return false
            val target = (current + deltaMs).coerceAtLeast(0L)
            controller.transportControls.seekTo(target)
            true
        } catch (e: Exception) {
            Log.v(TAG, "seekTo fallback failed for ${controller.packageName}", e)
            false
        }
    }

    private fun chooseControllersForAction(
        controllers: List<MediaController>,
        keyCode: Int
    ): List<MediaController> {
        if (controllers.isEmpty()) {
            return emptyList()
        }

        return controllers.sortedByDescending { controller ->
            val state = controller.playbackState?.state
            val isPlaying = state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING
            val supportsAction = supportsAction(controller, keyCode)
            val isProxy = isLikelyProxyPackage(controller.packageName)

            var score = 0
            if (isPlaying) score += 100
            if (supportsAction) score += 20
            if (controller.metadata != null) score += 5
            if (isProxy) score -= 50
            score
        }
    }

    private fun logControllerCandidates(
        controllers: List<MediaController>,
        keyCode: Int
    ) {
        if (controllers.isEmpty()) {
            Log.d(TAG, "No active media controllers for keyCode=$keyCode")
            return
        }

        controllers.forEachIndexed { index, controller ->
            val state = controller.playbackState?.state ?: -1
            val actions = controller.playbackState?.actions ?: 0L
            val proxy = isLikelyProxyPackage(controller.packageName)
            Log.d(
                TAG,
                "Controller[$index]: pkg=${controller.packageName}, state=$state, supports=${supportsAction(controller, keyCode)}, proxy=$proxy, actions=$actions"
            )
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
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                (actions and PlaybackState.ACTION_PLAY) != 0L
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                (actions and PlaybackState.ACTION_PAUSE) != 0L
            }
            android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                (actions and PlaybackState.ACTION_STOP) != 0L
            }
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                (actions and PlaybackState.ACTION_FAST_FORWARD) != 0L ||
                    (actions and PlaybackState.ACTION_SEEK_TO) != 0L
            }
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                (actions and PlaybackState.ACTION_REWIND) != 0L ||
                    (actions and PlaybackState.ACTION_SEEK_TO) != 0L
            }
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

    private fun sendMediaKey(keyCode: Int): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val now = android.os.SystemClock.uptimeMillis()
            
            val keyDown = android.view.KeyEvent(
                now, now,
                android.view.KeyEvent.ACTION_DOWN,
                keyCode, 0
            )
            val keyUp = android.view.KeyEvent(
                now, now,
                android.view.KeyEvent.ACTION_UP,
                keyCode, 0
            )
            
            audioManager.dispatchMediaKeyEvent(keyDown)
            audioManager.dispatchMediaKeyEvent(keyUp)
            Log.d(TAG, "Media key $keyCode dispatched via AudioManager fallback")
            
            sendMediaButtonBroadcast(keyCode)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media key fallback", e)
            false
        }
    }
    
    private fun sendMediaButtonBroadcast(keyCode: Int) {
        try {
            val now = android.os.SystemClock.uptimeMillis()
            
            val keyDownEvent = android.view.KeyEvent(
                now, now,
                android.view.KeyEvent.ACTION_DOWN,
                keyCode, 0,
                0, 0, 0,
                android.view.KeyEvent.FLAG_FROM_SYSTEM
            )
            val intentDown = Intent(Intent.ACTION_MEDIA_BUTTON)
            intentDown.putExtra(Intent.EXTRA_KEY_EVENT, keyDownEvent)
            sendBroadcast(intentDown)
            
            val keyUpEvent = android.view.KeyEvent(
                now + 50, now + 50,
                android.view.KeyEvent.ACTION_UP,
                keyCode, 0,
                0, 0, 0,
                android.view.KeyEvent.FLAG_FROM_SYSTEM
            )
            val intentUp = Intent(Intent.ACTION_MEDIA_BUTTON)
            intentUp.putExtra(Intent.EXTRA_KEY_EVENT, keyUpEvent)
            sendBroadcast(intentUp)
            
            Log.d(TAG, "Media button broadcast sent for keyCode: $keyCode")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send media button broadcast", e)
        }
    }
    
    private fun adjustVolume(up: Boolean): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val direction = if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
            audioManager.adjustVolume(direction, android.media.AudioManager.FLAG_SHOW_UI)
            Log.d(TAG, "Volume ${if (up) "raised" else "lowered"}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume", e)
            false
        }
    }
    
    private fun toggleMute(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.adjustVolume(android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
            Log.d(TAG, "Mute toggled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle mute", e)
            false
        }
    }

    private fun vibrateDoubleBuzz() {
        if (!ActionPreferences.isHapticsEnabled(this)) {
            return
        }

        val pattern = longArrayOf(0, 50, 50, 50)
        val effect = VibrationEffect.createWaveform(pattern, -1)
        vibrator.vibrate(effect)
    }
}
